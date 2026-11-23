package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.data.local.CheckLogEntity
import com.example.data.local.CheckStatus
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.local.TriggerSource
import com.example.data.repository.SiagaRepository
import com.example.service.DepartureAlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScannedBluetoothDevice(
    val address: String,
    val name: String,
    val rssi: Int?,
    val isBonded: Boolean,
    val isConnected: Boolean,
    val isLikelyCarAudio: Boolean,
    val isAppleDevice: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis()
)

enum class ScanMode {
    IDLE,
    CABIN_CHECK,
    NEARBY_DISCOVERY
}

data class CabinCheckSummary(
    val timestamp: Long,
    val triggerSource: TriggerSource,
    val totalMonitored: Int,
    val detectedAddresses: Set<String>,
    val missingPhones: List<RegisteredPhoneEntity>,
    val status: CheckStatus
)

data class BluetoothEngineState(
    val isHardwareSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val isClassicDiscovering: Boolean = false,
    val scanMode: ScanMode = ScanMode.IDLE,
    val scanProgress: Float = 0f,
    val remainingSeconds: Int = 0,
    val activeTriggerSource: TriggerSource? = null,
    val pairedDevices: List<ScannedBluetoothDevice> = emptyList(),
    val discoveredDevices: Map<String, ScannedBluetoothDevice> = emptyMap(),
    val connectedAddresses: Set<String> = emptySet(),
    val currentCheckDetectedRssi: Map<String, Int?> = emptyMap(),
    val lastCheckSummary: CabinCheckSummary? = null,
    val statusMessage: String? = null
)

class BluetoothScannerEngine(
    private val context: Context,
    private val repository: SiagaRepository,
    private val notifier: DepartureAlertNotifier
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checkMutex = Mutex()
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _state = MutableStateFlow(
        BluetoothEngineState(
            isHardwareSupported = bluetoothAdapter != null,
            isBluetoothEnabled = bluetoothAdapter?.isEnabled == true
        )
    )
    val state: StateFlow<BluetoothEngineState> = _state.asStateFlow()

    private val activeDeviceProber = ActiveDeviceProber(context, scope)

    private var scanJob: Job? = null
    private var bleCallback: ScanCallback? = null
    private var receiverRegistered = false
    private var lastPeriodicMissingMacs: Set<String> = emptySet()

    // Anti-flap & stability tracking for intermittent iOS BLE advertising
    private val consecutiveMissCountByMac = mutableMapOf<String, Int>()
    private val lastConfirmedPresenceAtByMac = mutableMapOf<String, Long>()
    private val currentCheckProbedReachableMacs = mutableSetOf<String>()
    private var keepAliveEnabled = false

    private val systemBtReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val enabled = btState == BluetoothAdapter.STATE_ON
                    _state.update { it.copy(isBluetoothEnabled = enabled) }
                    if (enabled) {
                        refreshBondedAndConnectedDevices()
                        if (keepAliveEnabled) {
                            scope.launch { syncIphoneKeepAlive(true) }
                        }
                    } else {
                        stopAllScanning()
                        activeDeviceProber.stopAllKeepAlive()
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _state.update { it.copy(isClassicDiscovering = true) }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _state.update { it.copy(isClassicDiscovering = false) }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    ) ?: return
                    val rawRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = if (rawRssi == Short.MIN_VALUE) null else rawRssi.toInt()
                    onDeviceSighted(device, rssi)
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    ) ?: return
                    val mac = device.address?.uppercase() ?: return
                    _state.update { it.copy(connectedAddresses = it.connectedAddresses + mac) }
                    onDeviceSighted(device, rssi = -48, isConnectedOverride = true)
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    ) ?: return
                    val mac = device.address?.uppercase() ?: return
                    // Ignore self-induced ACL bounce when our temporary GATT probe finishes cleanly,
                    // or if a persistent iPhone keep-alive link is still active.
                    if (activeDeviceProber.shouldSuppressProbeAclDisconnect(mac) ||
                        activeDeviceProber.isKeepAliveConnected(mac)
                    ) {
                        return
                    }
                    _state.update { it.copy(connectedAddresses = it.connectedAddresses - mac) }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    ) ?: return
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val devName = safeDeviceName(device)

                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            refreshBondedAndConnectedDevices()
                            onDeviceSighted(device, rssi = null)
                            _state.update {
                                it.copy(statusMessage = "$devName berhasil dipasangkan! Siap dipantau meski layar terkunci.")
                            }
                        }

                        BluetoothDevice.BOND_NONE -> {
                            refreshBondedAndConnectedDevices()
                            if (prevBondState == BluetoothDevice.BOND_BONDING) {
                                _state.update {
                                    it.copy(statusMessage = "Pemasangan dengan $devName dibatalkan atau gagal. Coba ulangi dari Pengaturan Bluetooth.")
                                }
                            }
                        }
                    }
                }

                BluetoothDevice.ACTION_UUID -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    ) ?: return
                    val parcelUuids = IntentCompat.getParcelableArrayExtra(
                        intent,
                        BluetoothDevice.EXTRA_UUID,
                        ParcelUuid::class.java
                    )
                    val uuidStrings = parcelUuids?.mapNotNull { (it as? ParcelUuid)?.uuid?.toString() }.orEmpty()
                    val isApple = AppleBluetoothHelper.isLikelyAppleDevice(
                        name = safeDeviceName(device),
                        uuidStrings = uuidStrings
                    )
                    if (isApple) {
                        val mac = device.address?.uppercase() ?: return
                        _state.update { current ->
                            val existing = current.discoveredDevices[mac]
                            if (existing != null && !existing.isAppleDevice) {
                                val updated = current.discoveredDevices.toMutableMap()
                                updated[mac] = existing.copy(isAppleDevice = true)
                                current.copy(discoveredDevices = updated)
                            } else {
                                current
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        registerBluetoothReceiver()
        refreshHardwareAndPairedState()
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_UUID)
        }
        try {
            ContextCompat.registerReceiver(
                context,
                systemBtReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Exception) {
            // Safe fallback
        }
    }

    fun refreshHardwareAndPairedState() {
        val adapter = bluetoothAdapter
        val supported = adapter != null
        val enabled = adapter?.isEnabled == true
        _state.update {
            it.copy(
                isHardwareSupported = supported,
                isBluetoothEnabled = enabled
            )
        }
        if (supported && enabled) {
            refreshBondedAndConnectedDevices()
            queryConnectedBluetoothProfiles()
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryConnectedBluetoothProfiles() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) return

        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        for (profileType in profiles) {
            try {
                adapter.getProfileProxy(
                    context,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            try {
                                val connected = proxy.connectedDevices.orEmpty()
                                val macs = connected.mapNotNull { it.address?.uppercase() }.toSet()
                                if (macs.isNotEmpty()) {
                                    _state.update { it.copy(connectedAddresses = it.connectedAddresses + macs) }
                                    connected.forEach { dev ->
                                        onDeviceSighted(dev, rssi = -48, isConnectedOverride = true)
                                    }
                                }
                            } catch (_: Exception) {
                                // Ignore permission or binder issues
                            } finally {
                                try {
                                    adapter.closeProfileProxy(profile, proxy)
                                } catch (_: Exception) {
                                    // Safe cleanup
                                }
                            }
                        }

                        override fun onServiceDisconnected(profile: Int) = Unit
                    },
                    profileType
                )
            } catch (_: Exception) {
                // Profile not supported on device
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedAndConnectedDevices() {
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) return

        try {
            val gattConnected = try {
                val clientConns = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                    ?.mapNotNull { it.address?.uppercase() }
                    .orEmpty()
                val serverConns = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT_SERVER)
                    ?.mapNotNull { it.address?.uppercase() }
                    .orEmpty()
                (clientConns + serverConns).toSet()
            } catch (_: Exception) {
                emptySet()
            }

            val mergedConnected = _state.value.connectedAddresses +
                gattConnected +
                activeDeviceProber.getKeepAliveConnectedMacs()
            val bonded = adapter.bondedDevices.orEmpty().mapNotNull { device ->
                val address = device.address?.uppercase() ?: return@mapNotNull null
                val name = safeDeviceName(device)
                val deviceUuids = try {
                    device.uuids?.map { it.uuid.toString() }.orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                ScannedBluetoothDevice(
                    address = address,
                    name = name,
                    rssi = if (address in mergedConnected) -50 else null,
                    isBonded = true,
                    isConnected = address in mergedConnected,
                    isLikelyCarAudio = isCarAudioClass(device),
                    isAppleDevice = AppleBluetoothHelper.isLikelyAppleDevice(
                        name = name,
                        uuidStrings = deviceUuids
                    )
                )
            }.sortedWith(compareByDescending<ScannedBluetoothDevice> { it.isConnected }.thenBy { it.name })

            _state.update {
                it.copy(
                    pairedDevices = bonded,
                    connectedAddresses = mergedConnected
                )
            }
        } catch (_: SecurityException) {
            // Permission not granted
        }
    }

    /**
     * Initiates Android-to-iPhone/device Bluetooth pairing (bonding).
     * Cancels Classic discovery first because iOS frequently rejects bonding requests
     * initiated while an Android adapter is actively inquiring.
     */
    @SuppressLint("MissingPermission")
    fun pairDevice(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _state.update { it.copy(statusMessage = "Aktifkan Bluetooth terlebih dahulu untuk memasangkan perangkat.") }
            return
        }
        if (!BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) {
            _state.update { it.copy(statusMessage = "Izin Bluetooth diperlukan untuk memasangkan perangkat.") }
            return
        }

        val normalizedMac = address.uppercase()
        try {
            if (BluetoothPermissionHelper.hasBluetoothScanPermission(context) && adapter.isDiscovering) {
                adapter.cancelDiscovery()
                _state.update { it.copy(isClassicDiscovering = false) }
            }
        } catch (_: Exception) {
            // Best-effort cancel discovery
        }

        try {
            val device = adapter.getRemoteDevice(normalizedMac)
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                refreshBondedAndConnectedDevices()
                _state.update { it.copy(statusMessage = "${safeDeviceName(device)} sudah terpasang (Paired).") }
                return
            }

            val initiated = device.createBond()
            if (initiated) {
                _state.update {
                    it.copy(statusMessage = "Meminta pairing dengan ${safeDeviceName(device)}… Konfirmasi di layar iPhone/ponsel target.")
                }
            } else {
                _state.update {
                    it.copy(statusMessage = "Gagal memulai pairing otomatis. Gunakan tombol Pengaturan Bluetooth untuk memasangkan iPhone.")
                }
            }
        } catch (_: Exception) {
            _state.update {
                it.copy(statusMessage = "Alamat Bluetooth tidak mendukung pairing langsung. Pasangkan lewat Pengaturan Bluetooth.")
            }
        }
    }

    /**
     * Maintains lightweight passive (`autoConnect = true`, `TRANSPORT_LE`) BLE keep-alive links
     * for bonded iPhones / Apple devices while monitoring is active. Android's Bluetooth controller
     * resolves rotating iOS RPA addresses in hardware using the stored IRK.
     */
    suspend fun syncIphoneKeepAlive(enabled: Boolean) {
        keepAliveEnabled = enabled
        if (!enabled) {
            activeDeviceProber.stopAllKeepAlive()
            return
        }
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) {
            activeDeviceProber.stopAllKeepAlive()
            return
        }

        val monitored = repository.getMonitoredPhonesOnce()
        val bondedMacs = _state.value.pairedDevices.map { it.address.uppercase() }.toSet()
        val bondedIphoneMacs = monitored
            .map { it.macAddress.uppercase() to it }
            .filter { (mac, phone) ->
                val isBonded = mac in bondedMacs
                val isApple = AppleBluetoothHelper.isLikelyAppleDevice(phone.deviceName) ||
                    AppleBluetoothHelper.isLikelyAppleDevice(phone.ownerLabel) ||
                    _state.value.pairedDevices.any { it.address.equals(mac, ignoreCase = true) && it.isAppleDevice }
                isBonded && isApple
            }
            .map { it.first }
            .toSet()

        activeDeviceProber.syncBondedIphoneKeepAlive(
            adapter = adapter,
            bondedIphoneMacs = bondedIphoneMacs
        ) { device, mac, connected, rssi ->
            if (connected) {
                lastConfirmedPresenceAtByMac[mac] = System.currentTimeMillis()
                consecutiveMissCountByMac[mac] = 0
                _state.update { it.copy(connectedAddresses = it.connectedAddresses + mac) }
                onDeviceSighted(
                    device = device,
                    rssi = rssi ?: -56,
                    isConnectedOverride = true,
                    isAppleOverride = true
                )
            } else {
                _state.update { it.copy(connectedAddresses = it.connectedAddresses - mac) }
            }
        }
    }

    fun stopIphoneKeepAlive() {
        keepAliveEnabled = false
        activeDeviceProber.stopAllKeepAlive()
    }

    /**
     * Runs a clean, contention-free diagnostic BLE GATT ping against a single smartphone/iPhone
     * so users can verify stable connectivity and live RSSI on demand.
     */
    @SuppressLint("MissingPermission")
    fun testPhoneConnection(macAddress: String, displayName: String) {
        scope.launch {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled || !BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
                _state.update {
                    it.copy(statusMessage = "Aktifkan Bluetooth & berikan izin terlebih dahulu untuk menguji koneksi.")
                }
                return@launch
            }

            checkMutex.withLock {
                // Cancel Classic discovery first to avoid GATT 133 radio contention
                cancelClassicDiscoveryOnly()
                delay(150L)

                _state.update {
                    it.copy(statusMessage = "Menguji koneksi BLE GATT ke $displayName…")
                }

                val outcome = activeDeviceProber.pingSingleDevice(
                    adapter = adapter,
                    macAddress = macAddress,
                    timeoutMs = 5500L
                )

                if (outcome.reachable) {
                    val now = System.currentTimeMillis()
                    val normalizedMac = macAddress.uppercase()
                    lastConfirmedPresenceAtByMac[normalizedMac] = now
                    consecutiveMissCountByMac[normalizedMac] = 0
                    repository.markPhoneSeen(
                        macAddress = normalizedMac,
                        timestamp = now,
                        rssi = outcome.rssi ?: -58
                    )
                    val rssiLabel = outcome.rssi?.let { "$it dBm" } ?: "terkonfirmasi"
                    _state.update {
                        it.copy(
                            statusMessage = "$displayName terhubung stabil! Sinyal $rssiLabel (${outcome.attemptsUsed}x percobaan)"
                        )
                    }
                } else {
                    val isBonded = _state.value.pairedDevices.any {
                        it.address.equals(macAddress, ignoreCase = true)
                    }
                    val advice = if (!isBonded) {
                        "Pasangkan (Pair) iPhone terlebih dahulu agar deteksi iOS stabil."
                    } else {
                        "Pastikan Bluetooth iPhone aktif & berada dekat kabin (${outcome.note})."
                    }
                    _state.update {
                        it.copy(statusMessage = "$displayName tidak merespons ping. $advice")
                    }
                }
            }
        }
    }

    fun startNearbyDiscovery(durationSeconds: Int = 18) {
        if (!canRunBluetoothScan()) return
        stopAllScanning()
        refreshBondedAndConnectedDevices()
        queryConnectedBluetoothProfiles()

        _state.update {
            it.copy(
                scanMode = ScanMode.NEARBY_DISCOVERY,
                scanProgress = 0f,
                remainingSeconds = durationSeconds,
                activeTriggerSource = null,
                statusMessage = "Memindai perangkat Bluetooth sekitar…"
            )
        }

        beginHardwareScans()

        scanJob = scope.launch {
            val totalMillis = durationSeconds * 1000L
            val tickMillis = 250L
            var elapsed = 0L
            while (elapsed < totalMillis) {
                delay(tickMillis)
                elapsed += tickMillis
                val progress = (elapsed.toFloat() / totalMillis).coerceIn(0f, 1f)
                val remaining = ((totalMillis - elapsed + 999L) / 1000L).toInt().coerceAtLeast(0)
                _state.update {
                    it.copy(scanProgress = progress, remainingSeconds = remaining)
                }
            }
            stopHardwareScansOnly()
            _state.update {
                it.copy(
                    scanMode = ScanMode.IDLE,
                    scanProgress = 0f,
                    remainingSeconds = 0,
                    statusMessage = "Pemindaian sekitar selesai"
                )
            }
        }
    }

    fun startCabinDepartureCheck(triggerSource: TriggerSource) {
        scope.launch {
            performCabinDepartureCheck(triggerSource)
        }
    }

    /**
     * Executes a full BluetoothAdapter (Classic + BLE + Active Profile/ACL) presence scan
     * for all monitored registered smartphones and returns the resulting summary.
     * Can be awaited directly by CarDepartureMonitorService during periodic/ignition checks.
     */
    suspend fun performCabinDepartureCheck(triggerSource: TriggerSource): CabinCheckSummary? {
        return checkMutex.withLock {
            val monitoredPhones = repository.getMonitoredPhonesOnce()
            val settings = repository.getSettingsOnce()

            if (monitoredPhones.isEmpty()) {
                if (triggerSource != TriggerSource.BACKGROUND_PERIODIC) {
                    _state.update {
                        it.copy(statusMessage = "Belum ada perangkat terdaftar untuk dipantau.")
                    }
                }
                return@withLock null
            }

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled || !BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
                val msg = when {
                    bluetoothAdapter == null -> "Perangkat ini tidak memiliki hardware Bluetooth."
                    !bluetoothAdapter.isEnabled -> "Bluetooth nonaktif — aktifkan untuk memeriksa perangkat."
                    else -> "Izin Bluetooth belum diberikan."
                }
                _state.update {
                    it.copy(
                        isHardwareSupported = bluetoothAdapter != null,
                        isBluetoothEnabled = bluetoothAdapter?.isEnabled == true,
                        statusMessage = if (triggerSource == TriggerSource.BACKGROUND_PERIODIC) null else msg
                    )
                }
                if (triggerSource != TriggerSource.BACKGROUND_PERIODIC) {
                    repository.recordCheckLog(
                        CheckLogEntity(
                            triggerSource = triggerSource.name,
                            totalMonitored = monitoredPhones.size,
                            detectedCount = 0,
                            missingPhoneNames = monitoredPhones.joinToString(", ") { it.ownerLabel },
                            status = CheckStatus.BT_UNAVAILABLE.name
                        )
                    )
                }
                return@withLock CabinCheckSummary(
                    timestamp = System.currentTimeMillis(),
                    triggerSource = triggerSource,
                    totalMonitored = monitoredPhones.size,
                    detectedAddresses = emptySet(),
                    missingPhones = monitoredPhones,
                    status = CheckStatus.BT_UNAVAILABLE
                )
            }

            stopAllScanning()
            refreshBondedAndConnectedDevices()
            queryConnectedBluetoothProfiles()

            val monitoredMacs = monitoredPhones.map { it.macAddress.uppercase() }.toSet()
            currentCheckProbedReachableMacs.clear()

            // Refresh live RSSI on any persistent iPhone keep-alive connections
            activeDeviceProber.refreshKeepAliveRssi(monitoredMacs)

            val initialDetected = mutableMapOf<String, Int?>()
            val currentlyConnected = _state.value.connectedAddresses
            for (phone in monitoredPhones) {
                val mac = phone.macAddress.uppercase()
                if (mac in currentlyConnected) {
                    val liveRssi = _state.value.discoveredDevices[mac]?.rssi ?: -50
                    initialDetected[mac] = liveRssi
                }
            }

            val durationSec = settings.scanDurationSeconds
            _state.update {
                it.copy(
                    scanMode = ScanMode.CABIN_CHECK,
                    scanProgress = 0f,
                    remainingSeconds = durationSec,
                    activeTriggerSource = triggerSource,
                    currentCheckDetectedRssi = initialDetected,
                    statusMessage = if (triggerSource == TriggerSource.BACKGROUND_PERIODIC) {
                        null
                    } else {
                        "Memeriksa ${monitoredPhones.size} perangkat (${triggerSource.label})…"
                    }
                )
            }

            val useBackgroundPowerPolicy = triggerSource == TriggerSource.BACKGROUND_PERIODIC
            val bleMode = if (useBackgroundPowerPolicy) {
                settings.scanPowerProfile.bleScanMode
            } else {
                ScanSettings.SCAN_MODE_LOW_LATENCY
            }
            val enableClassic = if (useBackgroundPowerPolicy) {
                settings.scanPowerProfile.useClassicInBackground
            } else {
                true
            }

            // ZERO-CONTENTION PHASED SCANNING:
            // Phase 1: Cancel Classic discovery first so 2.4GHz radio is clean for BLE scan +
            // serialized Main-thread iPhone GATT probes (eliminates GATT Error 133).
            cancelClassicDiscoveryOnly()
            delay(150L)
            beginBleScanOnly(bleScanMode = bleMode)

            val totalMillis = durationSec * 1000L
            val phase1BudgetMs = if (enableClassic) {
                (totalMillis * 0.62).toLong().coerceAtLeast(4500L).coerceAtMost(totalMillis)
            } else {
                totalMillis
            }

            val probeJob = scope.launch {
                val perDeviceTimeout = if (monitoredPhones.size <= 2) 3400L else 2600L
                activeDeviceProber.probeDevicesSerialized(
                    adapter = bluetoothAdapter,
                    monitoredMacs = monitoredPhones.map { it.macAddress.uppercase() },
                    alreadyDetectedMacs = {
                        val eval = BluetoothPresenceEvaluator.evaluate(
                            monitoredPhones = monitoredPhones,
                            detectedRssiByMac = _state.value.currentCheckDetectedRssi,
                            connectedMacs = _state.value.connectedAddresses,
                            sightedDevices = _state.value.discoveredDevices,
                            previouslyConfirmedMacs = recentlyConfirmedMacsSnapshot(),
                            probedReachableMacs = currentCheckProbedReachableMacs
                        )
                        eval.detectedAddresses
                    },
                    perDeviceTimeoutMs = perDeviceTimeout,
                    onReachable = { device, mac, rssi ->
                        currentCheckProbedReachableMacs.add(mac)
                        onDeviceSighted(
                            device = device,
                            rssi = rssi ?: -58,
                            isConnectedOverride = false
                        )
                        _state.update { current ->
                            if (current.scanMode == ScanMode.CABIN_CHECK) {
                                val updatedHits = current.currentCheckDetectedRssi.toMutableMap()
                                val prev = updatedHits[mac]
                                val effectiveRssi = rssi ?: prev ?: -58
                                updatedHits[mac] = if (prev != null) maxOf(prev, effectiveRssi) else effectiveRssi
                                current.copy(currentCheckDetectedRssi = updatedHits)
                            } else {
                                current
                            }
                        }
                    }
                )
            }

            val tickMillis = 250L
            var elapsed = 0L
            var phase2ClassicStarted = false

            try {
                while (elapsed < totalMillis && _state.value.scanMode == ScanMode.CABIN_CHECK) {
                    delay(tickMillis)
                    elapsed += tickMillis
                    val progress = (elapsed.toFloat() / totalMillis).coerceIn(0f, 1f)
                    val remaining = ((totalMillis - elapsed + 999L) / 1000L).toInt().coerceAtLeast(0)
                    _state.update {
                        it.copy(scanProgress = progress, remainingSeconds = remaining)
                    }

                    val evalNow = BluetoothPresenceEvaluator.evaluate(
                        monitoredPhones = monitoredPhones,
                        detectedRssiByMac = _state.value.currentCheckDetectedRssi,
                        connectedMacs = _state.value.connectedAddresses,
                        sightedDevices = _state.value.discoveredDevices,
                        previouslyConfirmedMacs = recentlyConfirmedMacsSnapshot(),
                        probedReachableMacs = currentCheckProbedReachableMacs
                    )
                    if (elapsed >= 2000L && monitoredMacs.all { it in evalNow.detectedAddresses }) {
                        break
                    }

                    // Phase 2: Only if devices remain missing after Phase 1 BLE + GATT probing,
                    // cleanly hand off the radio to Classic SDP page-scan + Classic discovery.
                    if (enableClassic && !phase2ClassicStarted && elapsed >= phase1BudgetMs) {
                        phase2ClassicStarted = true
                        probeJob.cancel()
                        activeDeviceProber.cancelAllActiveProbes()
                        delay(200L)
                        val missingMacs = monitoredMacs - evalNow.detectedAddresses
                        if (missingMacs.isNotEmpty()) {
                            beginClassicDiscoveryOnly()
                            scope.launch {
                                activeDeviceProber.triggerClassicSdpFallback(
                                    adapter = bluetoothAdapter,
                                    missingMacs = missingMacs
                                )
                            }
                        }
                    }
                }
            } finally {
                probeJob.cancel()
                stopHardwareScansOnly()
            }

            finalizeCabinCheck(
                monitoredPhones = monitoredPhones,
                triggerSource = triggerSource
            )
        }
    }

    private fun recentlyConfirmedMacsSnapshot(nowMs: Long = System.currentTimeMillis()): Set<String> {
        val graceWindowMs = 3 * 60 * 1000L // 3 minutes recent stability window
        return lastConfirmedPresenceAtByMac
            .filterValues { ts -> (nowMs - ts) <= graceWindowMs }
            .keys
            .toSet()
    }

    private suspend fun finalizeCabinCheck(
        monitoredPhones: List<RegisteredPhoneEntity>,
        triggerSource: TriggerSource
    ): CabinCheckSummary {
        val now = System.currentTimeMillis()
        val settings = repository.getSettingsOnce()
        val recentConfirmed = recentlyConfirmedMacsSnapshot(now)

        val rawEvaluation = BluetoothPresenceEvaluator.evaluate(
            monitoredPhones = monitoredPhones,
            detectedRssiByMac = _state.value.currentCheckDetectedRssi,
            connectedMacs = _state.value.connectedAddresses,
            sightedDevices = _state.value.discoveredDevices,
            previouslyConfirmedMacs = recentConfirmed,
            probedReachableMacs = currentCheckProbedReachableMacs
        )

        val evaluation = BluetoothPresenceEvaluator.applyBackgroundAntiFlap(
            evaluation = rawEvaluation,
            triggerSource = triggerSource,
            consecutiveMissCounts = consecutiveMissCountByMac,
            recentlyConfirmedMacs = recentConfirmed
        )

        // Update anti-flap miss counters & confirmed timestamps
        for (result in evaluation.results) {
            val mac = result.phone.macAddress.uppercase()
            if (result.isPresent && !result.heldByStabilityGrace) {
                consecutiveMissCountByMac[mac] = 0
                lastConfirmedPresenceAtByMac[mac] = now
                repository.markPhoneSeen(
                    macAddress = result.phone.macAddress,
                    timestamp = now,
                    rssi = result.measuredRssi
                )
            } else if (result.heldByStabilityGrace) {
                consecutiveMissCountByMac[mac] = (consecutiveMissCountByMac[mac] ?: 0) + 1
            } else {
                consecutiveMissCountByMac[mac] = (consecutiveMissCountByMac[mac] ?: 0) + 1
            }
        }

        val summary = CabinCheckSummary(
            timestamp = now,
            triggerSource = triggerSource,
            totalMonitored = monitoredPhones.size,
            detectedAddresses = evaluation.detectedAddresses,
            missingPhones = evaluation.missingPhones,
            status = evaluation.status
        )

        val currentMissingMacs = evaluation.missingPhones.map { it.macAddress.uppercase() }.toSet()
        val shouldRecordLog = triggerSource != TriggerSource.BACKGROUND_PERIODIC ||
            currentMissingMacs != lastPeriodicMissingMacs

        if (shouldRecordLog) {
            repository.recordCheckLog(
                CheckLogEntity(
                    timestamp = now,
                    triggerSource = triggerSource.name,
                    totalMonitored = monitoredPhones.size,
                    detectedCount = evaluation.detectedAddresses.size,
                    missingPhoneNames = evaluation.missingPhones.joinToString(", ") { it.ownerLabel },
                    status = evaluation.status.name
                )
            )
        }

        val resultMessage = if (evaluation.missingPhones.isEmpty()) {
            "Aman! ${evaluation.detectedAddresses.size}/${monitoredPhones.size} perangkat terdeteksi dalam radius sentral."
        } else {
            "Peringatan! ${evaluation.missingPhones.size} perangkat tidak terdeteksi: ${evaluation.missingPhones.joinToString(", ") { it.ownerLabel }}"
        }

        _state.update {
            it.copy(
                scanMode = ScanMode.IDLE,
                scanProgress = 1f,
                remainingSeconds = 0,
                activeTriggerSource = null,
                lastCheckSummary = summary,
                statusMessage = if (triggerSource == TriggerSource.BACKGROUND_PERIODIC) null else resultMessage
            )
        }

        val shouldAlert = BluetoothPresenceEvaluator.shouldFireMissingAlert(
            triggerSource = triggerSource,
            currentMissingMacs = currentMissingMacs,
            previousBackgroundMissingMacs = lastPeriodicMissingMacs
        )

        if (triggerSource == TriggerSource.BACKGROUND_PERIODIC) {
            lastPeriodicMissingMacs = currentMissingMacs
        } else if (currentMissingMacs.isEmpty()) {
            lastPeriodicMissingMacs = emptySet()
        }

        if (shouldAlert) {
            notifier.showMissingPhonesAlert(
                missingPhoneLabels = evaluation.missingPhones.map { it.ownerLabel },
                triggerSource = triggerSource,
                vibrate = settings.vibrateAlertEnabled,
                speakVoice = settings.voiceAlertEnabled
            )
        } else if (evaluation.missingPhones.isEmpty() && settings.notifyWhenAllSafe && triggerSource != TriggerSource.BACKGROUND_PERIODIC) {
            notifier.showAllSafeNotification(
                detectedCount = evaluation.detectedAddresses.size,
                triggerSource = triggerSource
            )
        }

        return summary
    }

    fun stopAllScanning() {
        scanJob?.cancel()
        scanJob = null
        stopHardwareScansOnly()
        _state.update {
            it.copy(
                scanMode = ScanMode.IDLE,
                isClassicDiscovering = false,
                scanProgress = 0f,
                remainingSeconds = 0,
                activeTriggerSource = null
            )
        }
    }

    private fun canRunBluetoothScan(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _state.update {
                it.copy(
                    isHardwareSupported = false,
                    statusMessage = "Hardware Bluetooth tidak tersedia pada perangkat ini."
                )
            }
            return false
        }
        if (!adapter.isEnabled) {
            _state.update {
                it.copy(
                    isBluetoothEnabled = false,
                    statusMessage = "Aktifkan Bluetooth terlebih dahulu."
                )
            }
            return false
        }
        if (!BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
            _state.update {
                it.copy(statusMessage = "Izin Bluetooth diperlukan untuk memindai perangkat.")
            }
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun beginHardwareScans(
        bleScanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
        enableClassicDiscovery: Boolean = true
    ) {
        if (enableClassicDiscovery) {
            beginClassicDiscoveryOnly()
        }
        beginBleScanOnly(bleScanMode = bleScanMode)
    }

    @SuppressLint("MissingPermission")
    private fun beginClassicDiscoveryOnly() {
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothPermissionHelper.hasBluetoothScanPermission(context)) return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val started = adapter.startDiscovery()
            if (started) {
                _state.update { it.copy(isClassicDiscovering = true) }
            }
        } catch (_: Exception) {
            // Classic discovery may fail on restricted devices; BLE still runs
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelClassicDiscoveryOnly() {
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothPermissionHelper.hasBluetoothScanPermission(context)) return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (_: Exception) {
            // Ignore
        } finally {
            _state.update { it.copy(isClassicDiscovering = false) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginBleScanOnly(
        bleScanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY
    ) {
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothPermissionHelper.hasBluetoothScanPermission(context)) return

        // Start BLE active scan with configured power profile mode (aggressive match for short iOS BLE bursts)
        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        handleBleScanResult(result)
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        results.forEach { result -> handleBleScanResult(result) }
                    }
                }
                bleCallback = callback
                val settingsBuilder = ScanSettings.Builder()
                    .setScanMode(bleScanMode)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    settingsBuilder
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setMatchMode(
                            if (bleScanMode == ScanSettings.SCAN_MODE_LOW_POWER) {
                                ScanSettings.MATCH_MODE_STICKY
                            } else {
                                ScanSettings.MATCH_MODE_AGGRESSIVE
                            }
                        )
                }
                scanner.startScan(null, settingsBuilder.build(), callback)
            }
        } catch (_: Exception) {
            // BLE scanner may be unavailable on legacy/emulator hardware
        }
    }

    private fun handleBleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val record = result.scanRecord
        val appleData = AppleBluetoothHelper.extractAppleManufacturerData(record)
        val hasAppleData = appleData != null
        val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        val rawAdvName = record?.deviceName?.takeIf { it.isNotBlank() }
        val fallbackAppleName = if (rawAdvName == null && hasAppleData) {
            AppleBluetoothHelper.describeAppleContinuityPayload(appleData)
        } else {
            null
        }
        val isApple = AppleBluetoothHelper.isLikelyAppleDevice(
            name = rawAdvName,
            hasAppleManufacturerData = hasAppleData,
            uuidStrings = serviceUuids
        )
        onDeviceSighted(
            device = device,
            rssi = result.rssi,
            advertisedName = rawAdvName ?: fallbackAppleName,
            isAppleOverride = isApple
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopHardwareScansOnly() {
        activeDeviceProber.cancelAllActiveProbes()
        cancelClassicDiscoveryOnly()
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothPermissionHelper.hasBluetoothScanPermission(context)) return
        try {
            bleCallback?.let { callback ->
                adapter.bluetoothLeScanner?.stopScan(callback)
            }
        } catch (_: Exception) {
            // Ignore cleanup errors
        } finally {
            bleCallback = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun onDeviceSighted(
        device: BluetoothDevice,
        rssi: Int?,
        advertisedName: String? = null,
        isConnectedOverride: Boolean = false,
        isAppleOverride: Boolean = false
    ) {
        val address = device.address?.uppercase() ?: return
        val existing = _state.value.discoveredDevices[address]
        val systemName = safeDeviceName(device).takeIf { it != "Perangkat Bluetooth" }
        val cleanAdvName = advertisedName?.takeIf { it.isNotBlank() }
        val resolvedName = when {
            cleanAdvName != null && !AppleBluetoothHelper.isGenericDeviceName(cleanAdvName) -> cleanAdvName
            systemName != null && !AppleBluetoothHelper.isGenericDeviceName(systemName) -> systemName
            existing?.name != null && !AppleBluetoothHelper.isGenericDeviceName(existing.name) -> existing.name
            cleanAdvName != null -> cleanAdvName
            systemName != null -> systemName
            else -> existing?.name ?: "Perangkat Bluetooth"
        }

        val isBonded = try {
            if (BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) {
                device.bondState == BluetoothDevice.BOND_BONDED
            } else {
                existing?.isBonded ?: false
            }
        } catch (_: Exception) {
            existing?.isBonded ?: false
        }

        val deviceUuids = try {
            if (BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) {
                device.uuids?.map { it.uuid.toString() }.orEmpty()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        val isApple = isAppleOverride ||
            (existing?.isAppleDevice == true) ||
            AppleBluetoothHelper.isLikelyAppleDevice(
                name = resolvedName,
                uuidStrings = deviceUuids
            )

        val isConnected = isConnectedOverride || address in _state.value.connectedAddresses
        val scanned = ScannedBluetoothDevice(
            address = address,
            name = resolvedName,
            rssi = rssi ?: existing?.rssi,
            isBonded = isBonded,
            isConnected = isConnected,
            isLikelyCarAudio = isCarAudioClass(device) || (existing?.isLikelyCarAudio == true),
            isAppleDevice = isApple,
            lastSeenAt = System.currentTimeMillis()
        )

        _state.update { current ->
            val updatedDiscovered = current.discoveredDevices.toMutableMap()
            updatedDiscovered[address] = scanned

            val updatedCheckHits = if (current.scanMode == ScanMode.CABIN_CHECK) {
                current.currentCheckDetectedRssi.toMutableMap().apply {
                    val prev = this[address]
                    this[address] = when {
                        prev == null -> rssi
                        rssi == null -> prev
                        else -> maxOf(prev, rssi)
                    }
                }
            } else {
                current.currentCheckDetectedRssi
            }

            current.copy(
                discoveredDevices = updatedDiscovered,
                currentCheckDetectedRssi = updatedCheckHits
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            if (BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) {
                device.name?.takeIf { it.isNotBlank() } ?: "Perangkat Bluetooth"
            } else {
                "Perangkat Bluetooth"
            }
        } catch (_: SecurityException) {
            "Perangkat Bluetooth"
        }
    }

    @SuppressLint("MissingPermission")
    private fun isCarAudioClass(device: BluetoothDevice): Boolean {
        return try {
            if (!BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) return false
            val btClass = device.bluetoothClass ?: return false
            val major = btClass.majorDeviceClass
            val specific = btClass.deviceClass
            major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                specific == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                specific == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        } catch (_: Exception) {
            false
        }
    }

    fun isCarAudioDevice(device: BluetoothDevice): Boolean = isCarAudioClass(device)

    fun clearStatusMessage() {
        _state.update { it.copy(statusMessage = null) }
    }
}
