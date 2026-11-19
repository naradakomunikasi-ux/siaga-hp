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

    private var scanJob: Job? = null
    private var bleCallback: ScanCallback? = null
    private var receiverRegistered = false
    private var lastPeriodicMissingMacs: Set<String> = emptySet()

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
                    } else {
                        stopAllScanning()
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
                    _state.update { it.copy(connectedAddresses = it.connectedAddresses - mac) }
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
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                    ?.mapNotNull { it.address?.uppercase() }
                    ?.toSet()
                    ?: emptySet()
            } catch (_: Exception) {
                emptySet()
            }

            val mergedConnected = _state.value.connectedAddresses + gattConnected
            val bonded = adapter.bondedDevices.orEmpty().mapNotNull { device ->
                val address = device.address?.uppercase() ?: return@mapNotNull null
                val name = safeDeviceName(device)
                ScannedBluetoothDevice(
                    address = address,
                    name = name,
                    rssi = if (address in mergedConnected) -50 else null,
                    isBonded = true,
                    isConnected = address in mergedConnected,
                    isLikelyCarAudio = isCarAudioClass(device)
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
                        it.copy(statusMessage = "Belum ada smartphone terdaftar untuk dipantau.")
                    }
                }
                return@withLock null
            }

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled || !BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
                val msg = when {
                    bluetoothAdapter == null -> "Perangkat ini tidak memiliki hardware Bluetooth."
                    !bluetoothAdapter.isEnabled -> "Bluetooth nonaktif — aktifkan untuk memeriksa ponsel."
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

            val initialDetected = mutableMapOf<String, Int?>()
            val currentlyConnected = _state.value.connectedAddresses
            for (phone in monitoredPhones) {
                val mac = phone.macAddress.uppercase()
                if (mac in currentlyConnected) {
                    initialDetected[mac] = -48
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
                        "Memeriksa ${monitoredPhones.size} ponsel (${triggerSource.label})…"
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

            beginHardwareScans(bleScanMode = bleMode, enableClassicDiscovery = enableClassic)

            val totalMillis = durationSec * 1000L
            val tickMillis = 250L
            var elapsed = 0L
            val monitoredMacs = monitoredPhones.map { it.macAddress.uppercase() }.toSet()

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
                        connectedMacs = _state.value.connectedAddresses
                    )
                    if (elapsed >= 2500L && monitoredMacs.all { it in evalNow.detectedAddresses }) {
                        break
                    }
                }
            } finally {
                stopHardwareScansOnly()
            }

            finalizeCabinCheck(
                monitoredPhones = monitoredPhones,
                triggerSource = triggerSource
            )
        }
    }

    private suspend fun finalizeCabinCheck(
        monitoredPhones: List<RegisteredPhoneEntity>,
        triggerSource: TriggerSource
    ): CabinCheckSummary {
        val now = System.currentTimeMillis()
        val settings = repository.getSettingsOnce()

        val evaluation = BluetoothPresenceEvaluator.evaluate(
            monitoredPhones = monitoredPhones,
            detectedRssiByMac = _state.value.currentCheckDetectedRssi,
            connectedMacs = _state.value.connectedAddresses
        )

        for (result in evaluation.results) {
            if (result.isPresent) {
                repository.markPhoneSeen(
                    macAddress = result.phone.macAddress,
                    timestamp = now,
                    rssi = result.measuredRssi
                )
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
            "Aman! ${evaluation.detectedAddresses.size}/${monitoredPhones.size} ponsel terdeteksi di dalam mobil."
        } else {
            "Peringatan! ${evaluation.missingPhones.size} ponsel tidak terdeteksi: ${evaluation.missingPhones.joinToString(", ") { it.ownerLabel }}"
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
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothPermissionHelper.hasBluetoothScanPermission(context)) return

        // 1. Start Classic BluetoothAdapter discovery (skipped in Power Saving background cycles)
        if (enableClassicDiscovery) {
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

        // 2. Start BLE active scan with configured power profile mode
        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val device = result.device ?: return
                        val advertisedName = result.scanRecord?.deviceName
                        onDeviceSighted(device, result.rssi, advertisedName = advertisedName)
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        results.forEach { result ->
                            val device = result.device ?: return@forEach
                            onDeviceSighted(device, result.rssi, advertisedName = result.scanRecord?.deviceName)
                        }
                    }
                }
                bleCallback = callback
                val scanSettings = ScanSettings.Builder()
                    .setScanMode(bleScanMode)
                    .build()
                scanner.startScan(null, scanSettings, callback)
            }
        } catch (_: Exception) {
            // BLE scanner may be unavailable on legacy/emulator hardware
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopHardwareScansOnly() {
        val adapter = bluetoothAdapter ?: return
        if (!BluetoothPermissionHelper.hasBluetoothScanPermission(context)) return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (_: Exception) {
            // Ignore cleanup errors
        } finally {
            _state.update { it.copy(isClassicDiscovering = false) }
        }
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
        isConnectedOverride: Boolean = false
    ) {
        val address = device.address?.uppercase() ?: return
        val existing = _state.value.discoveredDevices[address]
        val resolvedName = advertisedName?.takeIf { it.isNotBlank() }
            ?: safeDeviceName(device).takeIf { it != "Perangkat Bluetooth" }
            ?: existing?.name
            ?: "Perangkat Bluetooth"

        val isBonded = try {
            if (BluetoothPermissionHelper.hasBluetoothConnectPermission(context)) {
                device.bondState == BluetoothDevice.BOND_BONDED
            } else {
                existing?.isBonded ?: false
            }
        } catch (_: Exception) {
            existing?.isBonded ?: false
        }

        val isConnected = isConnectedOverride || address in _state.value.connectedAddresses
        val scanned = ScannedBluetoothDevice(
            address = address,
            name = resolvedName,
            rssi = rssi ?: existing?.rssi,
            isBonded = isBonded,
            isConnected = isConnected,
            isLikelyCarAudio = isCarAudioClass(device) || (existing?.isLikelyCarAudio == true),
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
