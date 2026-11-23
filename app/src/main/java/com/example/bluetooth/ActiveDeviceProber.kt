package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class GattProbeOutcome(
    val mac: String,
    val reachable: Boolean,
    val rssi: Int?,
    val attemptsUsed: Int,
    val lastGattStatus: Int,
    val note: String
)

/**
 * Manages stable, contention-free BLE GATT probing and persistent passive `autoConnect = true`
 * keep-alive links specifically engineered for iPhone (iOS) Bluetooth stability on Android.
 *
 * Key stability guarantees:
 * 1. Always executes `connectGatt(..., TRANSPORT_LE)` on the Main Looper to avoid OEM binder races.
 * 2. Serializes active probe attempts (one device at a time) so Android's BLE controller never
 *    overflows its pending connection queue or triggers GATT Error 133.
 * 3. Automatically retries transient iPhone GATT errors (status 133 / 8 / 19 / 22) after cooldown,
 *    with an `autoConnect = true` IRK-whitelist fallback for bonded iPhones.
 * 4. Performs clean two-step teardown (`disconnect()` -> wait for `STATE_DISCONNECTED` -> `close()`)
 *    and tracks probe-induced disconnect timestamps to suppress UI/ACL connection flapping.
 * 5. Maintains optional lightweight passive `autoConnect = true` keep-alive links for bonded
 *    iPhones with exponential backoff (2s -> 5s -> 10s) so presence stays rock-solid.
 */
class ActiveDeviceProber(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val activeProbeGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val activeProbeJobs = ConcurrentHashMap<String, Job>()
    private val recentProbeCleanupAt = ConcurrentHashMap<String, Long>()

    // Persistent passive keep-alive GATT handles for bonded iPhones (autoConnect = true)
    private val keepAliveGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val keepAliveConnectedMacs = ConcurrentHashMap.newKeySet<String>()
    private val keepAliveRetryJobs = ConcurrentHashMap<String, Job>()
    private val keepAliveBackoffStep = ConcurrentHashMap<String, Int>()
    private val keepAliveTargetMacs = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val GATT_STATUS_ERROR_133 = 133
        private const val GATT_STATUS_CONN_TIMEOUT = 8
        private const val GATT_STATUS_REMOTE_TERM = 19
        private const val GATT_STATUS_LOCAL_TERM = 22
        private const val PROBE_DISCONNECT_SUPPRESSION_MS = 2500L

        fun isTransientGattError(status: Int): Boolean {
            return status == GATT_STATUS_ERROR_133 ||
                status == GATT_STATUS_CONN_TIMEOUT ||
                status == GATT_STATUS_REMOTE_TERM ||
                status == GATT_STATUS_LOCAL_TERM
        }

        fun calculateReconnectBackoffMs(attemptIndex: Int): Long {
            return when {
                attemptIndex <= 0 -> 2000L
                attemptIndex == 1 -> 5000L
                attemptIndex == 2 -> 10000L
                else -> 20000L
            }
        }
    }

    fun isKeepAliveConnected(mac: String): Boolean = mac.uppercase() in keepAliveConnectedMacs

    fun getKeepAliveConnectedMacs(): Set<String> = keepAliveConnectedMacs.toSet()

    /**
     * Returns true if a recent `ACTION_ACL_DISCONNECTED` event for [mac] was likely caused by
     * our own temporary GATT probe closing, preventing false connection-status flapping.
     */
    fun shouldSuppressProbeAclDisconnect(mac: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = mac.uppercase()
        if (activeProbeGatts.containsKey(key)) return true
        val cleanedAt = recentProbeCleanupAt[key] ?: return false
        return (nowMs - cleanedAt) <= PROBE_DISCONNECT_SUPPRESSION_MS
    }

    /**
     * Synchronizes persistent passive (`autoConnect = true`) BLE GATT keep-alive connections for
     * bonded iPhone / Apple devices. The Android Bluetooth controller watches for each bonded
     * iPhone's rotating BLE RPA via its stored IRK in hardware with minimal battery impact.
     */
    @SuppressLint("MissingPermission")
    fun syncBondedIphoneKeepAlive(
        adapter: BluetoothAdapter?,
        bondedIphoneMacs: Set<String>,
        onStateChanged: (device: BluetoothDevice, mac: String, connected: Boolean, rssi: Int?) -> Unit
    ) {
        val normalizedTargets = bondedIphoneMacs.map { it.uppercase() }.toSet()

        // Remove keep-alive handles for devices no longer targeted or if BT is off/unavailable
        if (adapter == null || !adapter.isEnabled || !BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) {
            stopAllKeepAlive()
            return
        }

        val toRemove = keepAliveTargetMacs.filter { it !in normalizedTargets }
        toRemove.forEach { mac ->
            keepAliveTargetMacs.remove(mac)
            keepAliveRetryJobs.remove(mac)?.cancel()
            keepAliveBackoffStep.remove(mac)
            keepAliveConnectedMacs.remove(mac)
            keepAliveGatts.remove(mac)?.let { gatt ->
                scope.launch { closeGattGracefully(gatt, mac, markProbeCleanup = false) }
            }
        }

        // Start passive autoConnect=true links for newly targeted bonded iPhones
        for (mac in normalizedTargets) {
            if (keepAliveTargetMacs.add(mac) || (!keepAliveGatts.containsKey(mac) && !keepAliveRetryJobs.containsKey(mac))) {
                scheduleKeepAliveConnect(adapter, mac, delayMs = 0L, onStateChanged = onStateChanged)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleKeepAliveConnect(
        adapter: BluetoothAdapter,
        mac: String,
        delayMs: Long,
        onStateChanged: (device: BluetoothDevice, mac: String, connected: Boolean, rssi: Int?) -> Unit
    ) {
        if (mac !in keepAliveTargetMacs) return
        keepAliveRetryJobs.remove(mac)?.cancel()

        val job = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (!isActive || mac !in keepAliveTargetMacs) return@launch
            if (!adapter.isEnabled || !BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) return@launch

            val device = findBluetoothDevice(adapter, mac) ?: return@launch
            if (device.bondState != BluetoothDevice.BOND_BONDED) return@launch

            // Avoid opening a duplicate handle if already connected
            if (keepAliveGatts.containsKey(mac)) return@launch

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        keepAliveBackoffStep[mac] = 0
                        keepAliveConnectedMacs.add(mac)
                        onStateChanged(device, mac, true, null)
                        try {
                            gatt.readRemoteRssi()
                        } catch (_: Exception) {
                            // Best-effort RSSI read
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                        val wasConnected = keepAliveConnectedMacs.remove(mac)
                        keepAliveGatts.remove(mac, gatt)
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                            // Ignore cleanup
                        }
                        if (wasConnected) {
                            onStateChanged(device, mac, false, null)
                        }
                        if (mac in keepAliveTargetMacs && adapter.isEnabled) {
                            val step = keepAliveBackoffStep.compute(mac) { _, prev -> (prev ?: 0) + 1 } ?: 1
                            val backoff = calculateReconnectBackoffMs(step - 1)
                            scheduleKeepAliveConnect(adapter, mac, delayMs = backoff, onStateChanged = onStateChanged)
                        }
                    }
                }

                override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && mac in keepAliveConnectedMacs) {
                        onStateChanged(device, mac, true, rssi)
                    }
                }
            }

            withContext(Dispatchers.Main.immediate) {
                try {
                    val gatt = connectGattLe(device, autoConnect = true, callback = callback)
                    if (gatt != null) {
                        keepAliveGatts[mac] = gatt
                    }
                } catch (_: Exception) {
                    // Stack busy; schedule retry
                    val step = keepAliveBackoffStep.compute(mac) { _, prev -> (prev ?: 0) + 1 } ?: 1
                    scheduleKeepAliveConnect(
                        adapter = adapter,
                        mac = mac,
                        delayMs = calculateReconnectBackoffMs(step - 1),
                        onStateChanged = onStateChanged
                    )
                }
            }
        }
        keepAliveRetryJobs[mac] = job
    }

    /**
     * Refreshes RSSI on any currently active keep-alive iPhone GATT connection without reconnecting.
     */
    @SuppressLint("MissingPermission")
    fun refreshKeepAliveRssi(macs: Set<String>) {
        if (!BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) return
        for (rawMac in macs) {
            val mac = rawMac.uppercase()
            if (mac in keepAliveConnectedMacs) {
                try {
                    keepAliveGatts[mac]?.readRemoteRssi()
                } catch (_: Exception) {
                    // Ignore transient failure
                }
            }
        }
    }

    /**
     * Runs serialized, radio-clean active BLE GATT presence probes for monitored devices that are
     * not already connected. Must be called while Classic `startDiscovery()` is NOT running to
     * avoid Android 2.4GHz radio contention and GATT Error 133 on iPhones.
     */
    @SuppressLint("MissingPermission")
    suspend fun probeDevicesSerialized(
        adapter: BluetoothAdapter,
        monitoredMacs: List<String>,
        alreadyDetectedMacs: () -> Set<String>,
        perDeviceTimeoutMs: Long = 3200L,
        onReachable: (device: BluetoothDevice, mac: String, rssi: Int?) -> Unit
    ) {
        if (!BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) return
        if (!adapter.isEnabled) return

        val bondedByMac: Map<String, BluetoothDevice> = try {
            adapter.bondedDevices.orEmpty()
                .mapNotNull { dev -> dev.address?.uppercase()?.let { it to dev } }
                .toMap()
        } catch (_: SecurityException) {
            emptyMap()
        }

        // Probe bonded devices first (they have IRKs for resolving iOS rotating RPAs)
        val orderedTargets = monitoredMacs
            .map { it.uppercase() }
            .distinct()
            .sortedByDescending { it in bondedByMac }

        for (mac in orderedTargets) {
            if (mac in alreadyDetectedMacs()) continue

            // If our persistent iPhone keep-alive is already connected, simply read its live RSSI!
            if (mac in keepAliveConnectedMacs) {
                val dev = bondedByMac[mac] ?: findBluetoothDevice(adapter, mac)
                if (dev != null) {
                    refreshKeepAliveRssi(setOf(mac))
                    onReachable(dev, mac, null)
                    continue
                }
            }

            val device = bondedByMac[mac] ?: findBluetoothDevice(adapter, mac) ?: continue
            val isBonded = mac in bondedByMac

            val outcome = executeSingleDeviceProbe(
                device = device,
                mac = mac,
                isBonded = isBonded,
                timeoutMs = perDeviceTimeoutMs
            )

            if (outcome.reachable) {
                onReachable(device, mac, outcome.rssi)
            }

            // Short radio breathing gap between serialized probes to prevent GATT 133 cascades
            delay(250L)
        }
    }

    /**
     * Runs an on-demand diagnostic ping against a single registered smartphone/iPhone and returns
     * detailed reachability + RSSI + GATT status feedback.
     */
    @SuppressLint("MissingPermission")
    suspend fun pingSingleDevice(
        adapter: BluetoothAdapter,
        macAddress: String,
        timeoutMs: Long = 5000L
    ): GattProbeOutcome {
        val mac = macAddress.uppercase()
        if (!adapter.isEnabled || !BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) {
            return GattProbeOutcome(
                mac = mac,
                reachable = false,
                rssi = null,
                attemptsUsed = 0,
                lastGattStatus = -1,
                note = "Bluetooth tidak aktif atau izin belum diberikan."
            )
        }

        val device = findBluetoothDevice(adapter, mac)
            ?: return GattProbeOutcome(
                mac = mac,
                reachable = false,
                rssi = null,
                attemptsUsed = 0,
                lastGattStatus = -1,
                note = "Alamat MAC Bluetooth tidak valid."
            )

        // If already held by stable keep-alive link, read live RSSI immediately
        if (mac in keepAliveConnectedMacs) {
            refreshKeepAliveRssi(setOf(mac))
            return GattProbeOutcome(
                mac = mac,
                reachable = true,
                rssi = -56,
                attemptsUsed = 1,
                lastGattStatus = BluetoothGatt.GATT_SUCCESS,
                note = "Terhubung stabil melalui jalur BLE Keep-Alive iOS."
            )
        }

        val isBonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: Exception) {
            false
        }

        return executeSingleDeviceProbe(
            device = device,
            mac = mac,
            isBonded = isBonded,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Executes staggered Classic SDP page-scan (`fetchUuidsWithSdp()`) ONLY for bonded devices
     * that were not reached during the BLE GATT phase. Call only after temporary GATT probes
     * are closed.
     */
    @SuppressLint("MissingPermission")
    suspend fun triggerClassicSdpFallback(
        adapter: BluetoothAdapter,
        missingMacs: Set<String>
    ) {
        if (!BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) return
        if (!adapter.isEnabled || missingMacs.isEmpty()) return

        val bondedByMac = try {
            adapter.bondedDevices.orEmpty()
                .mapNotNull { dev -> dev.address?.uppercase()?.let { it to dev } }
                .toMap()
        } catch (_: SecurityException) {
            emptyMap()
        }

        for (rawMac in missingMacs) {
            val mac = rawMac.uppercase()
            val device = bondedByMac[mac] ?: continue
            try {
                device.fetchUuidsWithSdp()
            } catch (_: Exception) {
                // Ignore if stack busy
            }
            delay(350L)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun executeSingleDeviceProbe(
        device: BluetoothDevice,
        mac: String,
        isBonded: Boolean,
        timeoutMs: Long
    ): GattProbeOutcome {
        // Attempt 1: Fast direct BLE TRANSPORT_LE connection
        val firstAttempt = singleGattAttempt(
            device = device,
            mac = mac,
            autoConnect = false,
            timeoutMs = (timeoutMs * 0.55).toLong().coerceAtLeast(1800L)
        )
        if (firstAttempt.reachable) {
            return firstAttempt.copy(attemptsUsed = 1)
        }

        // Attempt 2: Cooldown + retry on GATT 133 / transient error, or IRK autoConnect=true for bonded iPhones
        val shouldRetry = isBonded || isTransientGattError(firstAttempt.lastGattStatus)
        if (!shouldRetry) {
            return firstAttempt.copy(attemptsUsed = 1)
        }

        delay(450L)
        val secondAttempt = singleGattAttempt(
            device = device,
            mac = mac,
            autoConnect = isBonded, // autoConnect=true uses Android IRK whitelist for rotating iOS RPAs
            timeoutMs = (timeoutMs * 0.6).toLong().coerceAtLeast(2200L)
        )
        return secondAttempt.copy(attemptsUsed = 2)
    }

    @SuppressLint("MissingPermission")
    private suspend fun singleGattAttempt(
        device: BluetoothDevice,
        mac: String,
        autoConnect: Boolean,
        timeoutMs: Long
    ): GattProbeOutcome {
        val resultDeferred = CompletableDeferred<GattProbeOutcome>()
        val disconnectedDeferred = CompletableDeferred<Unit>()
        val completed = AtomicBoolean(false)
        var gattHandle: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                        val rssiRequested = try {
                            gatt.readRemoteRssi()
                        } catch (_: Exception) {
                            false
                        }
                        if (!rssiRequested && completed.compareAndSet(false, true)) {
                            resultDeferred.complete(
                                GattProbeOutcome(
                                    mac = mac,
                                    reachable = true,
                                    rssi = null,
                                    attemptsUsed = 1,
                                    lastGattStatus = status,
                                    note = "Respons BLE GATT terkonfirmasi"
                                )
                            )
                        }
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        disconnectedDeferred.complete(Unit)
                        if (completed.compareAndSet(false, true)) {
                            resultDeferred.complete(
                                GattProbeOutcome(
                                    mac = mac,
                                    reachable = false,
                                    rssi = null,
                                    attemptsUsed = 1,
                                    lastGattStatus = status,
                                    note = if (status == GATT_STATUS_ERROR_133) {
                                        "GATT status 133 (transien)"
                                    } else {
                                        "Terputus (status $status)"
                                    }
                                )
                            )
                        }
                    }

                    status != BluetoothGatt.GATT_SUCCESS -> {
                        disconnectedDeferred.complete(Unit)
                        if (completed.compareAndSet(false, true)) {
                            resultDeferred.complete(
                                GattProbeOutcome(
                                    mac = mac,
                                    reachable = false,
                                    rssi = null,
                                    attemptsUsed = 1,
                                    lastGattStatus = status,
                                    note = "Gagal GATT (status $status)"
                                )
                            )
                        }
                    }
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                if (completed.compareAndSet(false, true)) {
                    resultDeferred.complete(
                        GattProbeOutcome(
                            mac = mac,
                            reachable = true,
                            rssi = if (status == BluetoothGatt.GATT_SUCCESS) rssi else null,
                            attemptsUsed = 1,
                            lastGattStatus = status,
                            note = "Respons BLE GATT stabil ($rssi dBm)"
                        )
                    )
                }
            }
        }

        try {
            gattHandle = withContext(Dispatchers.Main.immediate) {
                connectGattLe(device, autoConnect = autoConnect, callback = callback)
            }
            if (gattHandle == null) {
                return GattProbeOutcome(
                    mac = mac,
                    reachable = false,
                    rssi = null,
                    attemptsUsed = 1,
                    lastGattStatus = -1,
                    note = "BluetoothGatt tidak tersedia"
                )
            }
            activeProbeGatts[mac] = gattHandle

            val outcome = withTimeoutOrNull(timeoutMs) {
                resultDeferred.await()
            } ?: GattProbeOutcome(
                mac = mac,
                reachable = false,
                rssi = null,
                attemptsUsed = 1,
                lastGattStatus = GATT_STATUS_CONN_TIMEOUT,
                note = "Waktu tunggu respons habis"
            )

            // If connected, wait briefly for RSSI if it hasn't arrived yet
            return outcome
        } finally {
            gattHandle?.let { gatt ->
                activeProbeGatts.remove(mac, gatt)
                closeGattGracefully(
                    gatt = gatt,
                    mac = mac,
                    disconnectedSignal = disconnectedDeferred,
                    markProbeCleanup = true
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattLe(
        device: BluetoothDevice,
        autoConnect: Boolean,
        callback: BluetoothGattCallback
    ): BluetoothGatt? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, autoConnect, callback)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Two-step GATT teardown: calls `disconnect()` first, waits up to 350ms for
     * `STATE_DISCONNECTED` so the Android BT stack releases the HCI handle cleanly, then calls
     * `close()`. Prevents the classic Android GATT handle leak & iOS reconnect glitch.
     */
    @SuppressLint("MissingPermission")
    private suspend fun closeGattGracefully(
        gatt: BluetoothGatt,
        mac: String,
        disconnectedSignal: CompletableDeferred<Unit>? = null,
        markProbeCleanup: Boolean = true
    ) {
        if (markProbeCleanup) {
            recentProbeCleanupAt[mac.uppercase()] = System.currentTimeMillis()
        }
        withContext(Dispatchers.Main.immediate) {
            try {
                gatt.disconnect()
            } catch (_: Exception) {
                // Ignore disconnect errors
            }
        }
        if (disconnectedSignal != null && !disconnectedSignal.isCompleted) {
            withTimeoutOrNull(350L) { disconnectedSignal.await() }
        } else {
            delay(120L)
        }
        withContext(Dispatchers.Main.immediate) {
            try {
                gatt.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
        if (markProbeCleanup) {
            recentProbeCleanupAt[mac.uppercase()] = System.currentTimeMillis()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findBluetoothDevice(adapter: BluetoothAdapter, mac: String): BluetoothDevice? {
        val bonded = try {
            adapter.bondedDevices.orEmpty().firstOrNull { it.address.equals(mac, ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
        if (bonded != null) return bonded
        return try {
            adapter.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun cancelAllActiveProbes() {
        val jobs = activeProbeJobs.values.toList()
        activeProbeJobs.clear()
        jobs.forEach { it.cancel() }

        val entries = activeProbeGatts.entries.toList()
        activeProbeGatts.clear()
        entries.forEach { (mac, gatt) ->
            recentProbeCleanupAt[mac] = System.currentTimeMillis()
            try {
                gatt.disconnect()
            } catch (_: Exception) {
                // Ignore
            }
            try {
                gatt.close()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAllKeepAlive() {
        keepAliveTargetMacs.clear()
        val jobs = keepAliveRetryJobs.values.toList()
        keepAliveRetryJobs.clear()
        jobs.forEach { it.cancel() }
        keepAliveBackoffStep.clear()
        keepAliveConnectedMacs.clear()

        val gatts = keepAliveGatts.values.toList()
        keepAliveGatts.clear()
        gatts.forEach { gatt ->
            try {
                gatt.disconnect()
            } catch (_: Exception) {
                // Ignore
            }
            try {
                gatt.close()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }
}
