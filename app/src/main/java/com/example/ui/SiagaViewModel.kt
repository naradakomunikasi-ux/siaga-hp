package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.SiagaApp
import com.example.bluetooth.BluetoothEngineState
import com.example.bluetooth.BluetoothPermissionHelper
import com.example.bluetooth.ScanMode
import com.example.bluetooth.ScannedBluetoothDevice
import com.example.data.local.CheckLogEntity
import com.example.data.local.CheckStatus
import com.example.data.local.PhoneCategory
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.local.TriggerSource
import com.example.data.prefs.AppSettings
import com.example.data.prefs.ScanPowerProfile
import com.example.data.prefs.ThemeMode
import com.example.service.BackgroundServiceTelemetry
import com.example.service.CarDepartureMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class PhonePresenceStatus {
    DETECTED,
    MISSING,
    SCANNING,
    STANDBY,
    PAUSED
}

data class MonitoredPhoneUiItem(
    val entity: RegisteredPhoneEntity,
    val liveStatus: PhonePresenceStatus,
    val liveRssi: Int?,
    val isCurrentlyConnected: Boolean
)

data class SiagaUiState(
    val phones: List<MonitoredPhoneUiItem> = emptyList(),
    val checkLogs: List<CheckLogEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val btState: BluetoothEngineState = BluetoothEngineState(),
    val serviceTelemetry: BackgroundServiceTelemetry = BackgroundServiceTelemetry(),
    val isMonitorServiceRunning: Boolean = false,
    val hasBluetoothPermissions: Boolean = false,
    val hasNotificationPermission: Boolean = false
) {
    val monitoredCount: Int get() = phones.count { it.entity.isMonitored }
    val detectedCount: Int get() = phones.count { it.entity.isMonitored && it.liveStatus == PhonePresenceStatus.DETECTED }
    val missingCount: Int get() = phones.count { it.entity.isMonitored && it.liveStatus == PhonePresenceStatus.MISSING }
    val isScanningCabin: Boolean get() = btState.scanMode == ScanMode.CABIN_CHECK
    val isScanningNearby: Boolean get() = btState.scanMode == ScanMode.NEARBY_DISCOVERY

    val nearbyDiscoveredSorted: List<ScannedBluetoothDevice>
        get() = btState.discoveredDevices.values
            .sortedWith(
                compareByDescending<ScannedBluetoothDevice> { it.isConnected }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                    .thenBy { it.name }
            )
}

class SiagaViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SiagaApp
    private val repository = app.container.repository
    private val scannerEngine = app.container.scannerEngine
    private val notifier = app.container.notifier

    private val permissionTrigger = MutableStateFlow(0)

    private val baseDataFlow = combine(
        repository.registeredPhones,
        repository.checkLogs,
        repository.settings,
        scannerEngine.state
    ) { phones, logs, settings, btState ->
        BaseSnapshot(phones, logs, settings, btState)
    }

    val uiState: StateFlow<SiagaUiState> = combine(
        baseDataFlow,
        CarDepartureMonitorService.telemetry,
        permissionTrigger
    ) { base, telemetry, _ ->
        val hasBtPerm = BluetoothPermissionHelper.hasBluetoothPermissions(app)
        val hasNotifPerm = BluetoothPermissionHelper.hasNotificationPermission(app)
        val phoneItems = base.phones.map { entity ->
            mapPhoneToUiItem(entity, base.btState)
        }
        SiagaUiState(
            phones = phoneItems,
            checkLogs = base.logs,
            settings = base.settings,
            btState = base.btState,
            serviceTelemetry = telemetry,
            isMonitorServiceRunning = telemetry.isRunning,
            hasBluetoothPermissions = hasBtPerm,
            hasNotificationPermission = hasNotifPerm
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SiagaUiState(
            hasBluetoothPermissions = BluetoothPermissionHelper.hasBluetoothPermissions(app),
            hasNotificationPermission = BluetoothPermissionHelper.hasNotificationPermission(app)
        )
    )

    private data class BaseSnapshot(
        val phones: List<RegisteredPhoneEntity>,
        val logs: List<CheckLogEntity>,
        val settings: AppSettings,
        val btState: BluetoothEngineState
    )

    private fun mapPhoneToUiItem(
        entity: RegisteredPhoneEntity,
        btState: BluetoothEngineState
    ): MonitoredPhoneUiItem {
        val mac = entity.macAddress.uppercase()
        val isConnected = mac in btState.connectedAddresses
        val liveDiscovered = btState.discoveredDevices[mac]

        if (!entity.isMonitored) {
            return MonitoredPhoneUiItem(
                entity = entity,
                liveStatus = PhonePresenceStatus.PAUSED,
                liveRssi = liveDiscovered?.rssi ?: entity.lastRssi,
                isCurrentlyConnected = isConnected
            )
        }

        return when (btState.scanMode) {
            ScanMode.CABIN_CHECK -> {
                val hitMap = btState.currentCheckDetectedRssi
                if (hitMap.containsKey(mac)) {
                    val rssi = hitMap[mac]
                    val inRange = rssi == null || rssi >= entity.rssiThreshold
                    MonitoredPhoneUiItem(
                        entity = entity,
                        liveStatus = if (inRange) PhonePresenceStatus.DETECTED else PhonePresenceStatus.SCANNING,
                        liveRssi = rssi,
                        isCurrentlyConnected = isConnected
                    )
                } else {
                    MonitoredPhoneUiItem(
                        entity = entity,
                        liveStatus = PhonePresenceStatus.SCANNING,
                        liveRssi = null,
                        isCurrentlyConnected = isConnected
                    )
                }
            }

            else -> {
                val summary = btState.lastCheckSummary
                val liveStatus = when {
                    isConnected -> PhonePresenceStatus.DETECTED
                    summary != null && summary.status != CheckStatus.BT_UNAVAILABLE -> {
                        if (mac in summary.detectedAddresses) {
                            PhonePresenceStatus.DETECTED
                        } else if (summary.missingPhones.any { it.macAddress.equals(mac, ignoreCase = true) }) {
                            PhonePresenceStatus.MISSING
                        } else {
                            PhonePresenceStatus.STANDBY
                        }
                    }
                    else -> PhonePresenceStatus.STANDBY
                }
                val rssi = if (isConnected) {
                    liveDiscovered?.rssi ?: -50
                } else if (liveStatus == PhonePresenceStatus.DETECTED) {
                    liveDiscovered?.rssi ?: entity.lastRssi
                } else {
                    null
                }
                MonitoredPhoneUiItem(
                    entity = entity,
                    liveStatus = liveStatus,
                    liveRssi = rssi,
                    isCurrentlyConnected = isConnected
                )
            }
        }
    }

    fun onPermissionsUpdated() {
        permissionTrigger.value += 1
        scannerEngine.refreshHardwareAndPairedState()
        syncMonitoringServiceWithSettings()
    }

    fun refreshBluetoothState() {
        scannerEngine.refreshHardwareAndPairedState()
        permissionTrigger.value += 1
    }

    fun syncMonitoringServiceWithSettings() {
        viewModelScope.launch {
            val settings = repository.getSettingsOnce()
            if (settings.autoMonitorEnabled && BluetoothPermissionHelper.hasBluetoothPermissions(app)) {
                CarDepartureMonitorService.start(app)
            } else if (!settings.autoMonitorEnabled) {
                CarDepartureMonitorService.stop(app)
            }
        }
    }

    fun startManualCabinCheck() {
        scannerEngine.startCabinDepartureCheck(TriggerSource.MANUAL_SCAN)
    }

    fun startIgnitionTriggeredCheck() {
        scannerEngine.startCabinDepartureCheck(TriggerSource.IGNITION_POWER)
    }

    fun stopScanning() {
        scannerEngine.stopAllScanning()
    }

    fun startNearbyDiscovery() {
        scannerEngine.startNearbyDiscovery()
    }

    fun registerOrUpdatePhone(
        macAddress: String,
        deviceName: String,
        ownerLabel: String,
        category: PhoneCategory,
        rssiThreshold: Int = -88,
        isMonitored: Boolean = true
    ) {
        viewModelScope.launch {
            repository.registerOrUpdatePhone(
                macAddress = macAddress,
                deviceName = deviceName,
                ownerLabel = ownerLabel,
                category = category,
                rssiThreshold = rssiThreshold,
                isMonitored = isMonitored
            )
        }
    }

    fun quickRegisterScannedDevice(device: ScannedBluetoothDevice) {
        viewModelScope.launch {
            repository.registerOrUpdatePhone(
                macAddress = device.address,
                deviceName = device.name,
                ownerLabel = device.name,
                category = PhoneCategory.PRIMARY,
                rssiThreshold = -88,
                isMonitored = true
            )
        }
    }

    fun registerMultipleScannedDevices(devices: List<ScannedBluetoothDevice>) {
        if (devices.isEmpty()) return
        viewModelScope.launch {
            val entities = devices.map { device ->
                RegisteredPhoneEntity(
                    macAddress = RegisteredPhoneEntity.normalizeMacAddress(device.address),
                    deviceName = device.name.ifBlank { "Perangkat Bluetooth" },
                    ownerLabel = device.name.ifBlank { "Smartphone" },
                    category = PhoneCategory.PRIMARY.name,
                    isMonitored = true,
                    rssiThreshold = -88,
                    lastSeenTimestamp = device.lastSeenAt,
                    lastRssi = device.rssi
                )
            }
            repository.registerMultiplePhones(entities)
        }
    }

    fun togglePhoneMonitored(macAddress: String, isMonitored: Boolean) {
        viewModelScope.launch {
            repository.togglePhoneMonitored(macAddress, isMonitored)
        }
    }

    fun deletePhone(macAddress: String) {
        viewModelScope.launch {
            repository.deletePhone(macAddress)
        }
    }

    fun setCarBluetoothDevice(address: String, name: String) {
        viewModelScope.launch {
            repository.setCarBluetoothDevice(address, name)
        }
    }

    fun clearCarBluetoothDevice() {
        viewModelScope.launch {
            repository.clearCarBluetoothDevice()
        }
    }

    fun setAutoMonitorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoMonitorEnabled(enabled)
            if (enabled) {
                CarDepartureMonitorService.start(app)
            } else {
                CarDepartureMonitorService.stop(app)
            }
        }
    }

    fun setPeriodicBackgroundScanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setPeriodicBackgroundScanEnabled(enabled)
            CarDepartureMonitorService.notifyScheduleUpdated(app)
        }
    }

    fun setScanPowerProfile(profile: ScanPowerProfile) {
        viewModelScope.launch {
            repository.setScanPowerProfile(profile)
            CarDepartureMonitorService.notifyScheduleUpdated(app)
        }
    }

    fun setPeriodicScanIntervalSeconds(seconds: Int) {
        viewModelScope.launch {
            repository.setPeriodicScanIntervalSeconds(seconds)
            CarDepartureMonitorService.notifyScheduleUpdated(app)
        }
    }

    fun setTriggerOnCarBluetooth(enabled: Boolean) {
        viewModelScope.launch { repository.setTriggerOnCarBluetooth(enabled) }
    }

    fun setTriggerOnPowerConnected(enabled: Boolean) {
        viewModelScope.launch { repository.setTriggerOnPowerConnected(enabled) }
    }

    fun setScanDurationSeconds(seconds: Int) {
        viewModelScope.launch { repository.setScanDurationSeconds(seconds) }
    }

    fun setVoiceAlertEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setVoiceAlertEnabled(enabled) }
    }

    fun setVibrateAlertEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setVibrateAlertEnabled(enabled) }
    }

    fun setNotifyWhenAllSafe(enabled: Boolean) {
        viewModelScope.launch { repository.setNotifyWhenAllSafe(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun triggerTestNotificationAlert() {
        viewModelScope.launch {
            val settings = repository.getSettingsOnce()
            notifier.showTestAlert(
                vibrate = settings.vibrateAlertEnabled,
                speakVoice = settings.voiceAlertEnabled
            )
        }
    }

    fun clearCheckLogs() {
        viewModelScope.launch { repository.clearCheckLogs() }
    }

    fun clearStatusMessage() {
        scannerEngine.clearStatusMessage()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return SiagaViewModel(app) as T
            }
        }
    }
}
