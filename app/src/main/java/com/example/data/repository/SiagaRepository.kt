package com.example.data.repository

import com.example.data.local.CheckLogEntity
import com.example.data.local.PhoneCategory
import com.example.data.local.RegisteredPhoneDao
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.local.SiagaDao
import com.example.data.prefs.AppSettings
import com.example.data.prefs.ScanPowerProfile
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.ThemeMode
import kotlinx.coroutines.flow.Flow

class SiagaRepository(
    private val phoneDao: RegisteredPhoneDao,
    private val logDao: SiagaDao,
    private val settingsRepository: SettingsRepository
) {
    val registeredPhones: Flow<List<RegisteredPhoneEntity>> = phoneDao.observeAllPhones()
    val monitoredPhones: Flow<List<RegisteredPhoneEntity>> = phoneDao.observeMonitoredPhones()
    val monitoredMacAddresses: Flow<List<String>> = phoneDao.observeMonitoredMacAddresses()
    val checkLogs: Flow<List<CheckLogEntity>> = logDao.observeCheckLogs()
    val settings: Flow<AppSettings> = settingsRepository.settingsFlow

    suspend fun getMonitoredPhonesOnce(): List<RegisteredPhoneEntity> = phoneDao.getMonitoredPhonesOnce()

    suspend fun getPhoneByMac(macAddress: String): RegisteredPhoneEntity? =
        phoneDao.getPhoneByMac(RegisteredPhoneEntity.normalizeMacAddress(macAddress))

    suspend fun getSettingsOnce(): AppSettings = settingsRepository.getSettingsOnce()

    suspend fun registerOrUpdatePhone(
        macAddress: String,
        deviceName: String,
        ownerLabel: String,
        category: PhoneCategory,
        rssiThreshold: Int = -88,
        isMonitored: Boolean = true
    ) {
        val normalizedMac = RegisteredPhoneEntity.normalizeMacAddress(macAddress)
        val safeDeviceName = deviceName.ifBlank { "Perangkat Bluetooth" }
        val safeOwnerLabel = ownerLabel.ifBlank { safeDeviceName }
        val safeThreshold = rssiThreshold.coerceIn(-100, -45)

        val existing = phoneDao.getPhoneByMac(normalizedMac)
        if (existing != null) {
            // Preserve lastSeenTimestamp, lastRssi, and addedAt on edit
            phoneDao.updatePhoneMetadata(
                macAddress = normalizedMac,
                deviceName = safeDeviceName,
                ownerLabel = safeOwnerLabel,
                category = category.name,
                rssiThreshold = safeThreshold,
                isMonitored = isMonitored
            )
        } else {
            phoneDao.upsertPhone(
                RegisteredPhoneEntity(
                    macAddress = normalizedMac,
                    deviceName = safeDeviceName,
                    ownerLabel = safeOwnerLabel,
                    category = category.name,
                    isMonitored = isMonitored,
                    rssiThreshold = safeThreshold
                )
            )
        }
    }

    suspend fun registerMultiplePhones(phones: List<RegisteredPhoneEntity>) {
        val normalized = phones.map { phone ->
            phone.copy(
                macAddress = RegisteredPhoneEntity.normalizeMacAddress(phone.macAddress),
                rssiThreshold = phone.rssiThreshold.coerceIn(-100, -45)
            )
        }
        phoneDao.upsertPhones(normalized)
    }

    suspend fun togglePhoneMonitored(macAddress: String, isMonitored: Boolean) {
        phoneDao.setPhoneMonitored(
            RegisteredPhoneEntity.normalizeMacAddress(macAddress),
            isMonitored
        )
    }

    suspend fun markPhoneSeen(macAddress: String, timestamp: Long, rssi: Int?) {
        phoneDao.markPhoneSeen(
            RegisteredPhoneEntity.normalizeMacAddress(macAddress),
            timestamp,
            rssi
        )
    }

    suspend fun deletePhone(macAddress: String) {
        phoneDao.deletePhoneByMac(RegisteredPhoneEntity.normalizeMacAddress(macAddress))
    }

    suspend fun recordCheckLog(log: CheckLogEntity) {
        logDao.insertCheckLog(log)
    }

    suspend fun clearCheckLogs() {
        logDao.clearCheckLogs()
    }

    suspend fun setAutoMonitorEnabled(enabled: Boolean) = settingsRepository.setAutoMonitorEnabled(enabled)
    suspend fun setPeriodicBackgroundScanEnabled(enabled: Boolean) = settingsRepository.setPeriodicBackgroundScanEnabled(enabled)
    suspend fun setScanPowerProfile(profile: ScanPowerProfile) = settingsRepository.setScanPowerProfile(profile)
    suspend fun setPeriodicScanIntervalSeconds(seconds: Int) = settingsRepository.setPeriodicScanIntervalSeconds(seconds)
    suspend fun setTriggerOnCarBluetooth(enabled: Boolean) = settingsRepository.setTriggerOnCarBluetooth(enabled)
    suspend fun setCarBluetoothDevice(address: String, name: String) = settingsRepository.setCarBluetoothDevice(address, name)
    suspend fun clearCarBluetoothDevice() = settingsRepository.clearCarBluetoothDevice()
    suspend fun setTriggerOnPowerConnected(enabled: Boolean) = settingsRepository.setTriggerOnPowerConnected(enabled)
    suspend fun setScanDurationSeconds(seconds: Int) = settingsRepository.setScanDurationSeconds(seconds)
    suspend fun setVoiceAlertEnabled(enabled: Boolean) = settingsRepository.setVoiceAlertEnabled(enabled)
    suspend fun setVibrateAlertEnabled(enabled: Boolean) = settingsRepository.setVibrateAlertEnabled(enabled)
    suspend fun setNotifyWhenAllSafe(enabled: Boolean) = settingsRepository.setNotifyWhenAllSafe(enabled)
    suspend fun setThemeMode(mode: ThemeMode) = settingsRepository.setThemeMode(mode)
}
