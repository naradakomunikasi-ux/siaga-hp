package com.example.data.prefs

import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.siagaDataStore: DataStore<Preferences> by preferencesDataStore(name = "siagahp_settings")

enum class ThemeMode(val label: String) {
    DARK("Kokpit Gelap"),
    LIGHT("Terang"),
    SYSTEM("Sistem");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: DARK
    }
}

enum class ScanPowerProfile(
    val label: String,
    val description: String,
    val defaultIntervalSeconds: Int,
    val bleScanMode: Int,
    val useClassicInBackground: Boolean
) {
    RESPONSIVE(
        label = "Responsif",
        description = "Cek tiap 30 detik • BLE Low-Latency + Classic (daya lebih tinggi)",
        defaultIntervalSeconds = 30,
        bleScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
        useClassicInBackground = true
    ),
    BALANCED(
        label = "Seimbang",
        description = "Cek tiap 60 detik • BLE Balanced + Classic (ideal harian)",
        defaultIntervalSeconds = 60,
        bleScanMode = ScanSettings.SCAN_MODE_BALANCED,
        useClassicInBackground = true
    ),
    POWER_SAVING(
        label = "Hemat Daya",
        description = "Cek tiap 3 menit • BLE Low-Power hemat baterai (cek penuh tetap aktif saat mesin menyala)",
        defaultIntervalSeconds = 180,
        bleScanMode = ScanSettings.SCAN_MODE_LOW_POWER,
        useClassicInBackground = false
    );

    companion object {
        fun fromName(name: String?): ScanPowerProfile =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

data class AppSettings(
    val autoMonitorEnabled: Boolean = true,
    val periodicBackgroundScanEnabled: Boolean = true,
    val scanPowerProfile: ScanPowerProfile = ScanPowerProfile.BALANCED,
    val periodicScanIntervalSeconds: Int = 60,
    val triggerOnCarBluetooth: Boolean = true,
    val triggerOnAndroidAuto: Boolean = true,
    val carBluetoothAddress: String = "",
    val carBluetoothName: String = "",
    val triggerOnPowerConnected: Boolean = true,
    val scanDurationSeconds: Int = 12,
    val voiceAlertEnabled: Boolean = true,
    val vibrateAlertEnabled: Boolean = true,
    val notifyWhenAllSafe: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val AUTO_MONITOR = booleanPreferencesKey("auto_monitor_enabled")
        val PERIODIC_BG_SCAN = booleanPreferencesKey("periodic_bg_scan_enabled")
        val SCAN_POWER_PROFILE = stringPreferencesKey("scan_power_profile")
        val PERIODIC_SCAN_INTERVAL = intPreferencesKey("periodic_scan_interval_sec")
        val TRIGGER_CAR_BT = booleanPreferencesKey("trigger_car_bt")
        val TRIGGER_ANDROID_AUTO = booleanPreferencesKey("trigger_android_auto")
        val CAR_BT_ADDRESS = stringPreferencesKey("car_bt_address")
        val CAR_BT_NAME = stringPreferencesKey("car_bt_name")
        val TRIGGER_POWER = booleanPreferencesKey("trigger_power")
        val SCAN_DURATION = intPreferencesKey("scan_duration_sec")
        val VOICE_ALERT = booleanPreferencesKey("voice_alert_enabled")
        val VIBRATE_ALERT = booleanPreferencesKey("vibrate_alert_enabled")
        val NOTIFY_SAFE = booleanPreferencesKey("notify_when_all_safe")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settingsFlow: Flow<AppSettings> = context.siagaDataStore.data.map { prefs ->
        val profile = ScanPowerProfile.fromName(prefs[Keys.SCAN_POWER_PROFILE])
        AppSettings(
            autoMonitorEnabled = prefs[Keys.AUTO_MONITOR] ?: true,
            periodicBackgroundScanEnabled = prefs[Keys.PERIODIC_BG_SCAN] ?: true,
            scanPowerProfile = profile,
            periodicScanIntervalSeconds = (prefs[Keys.PERIODIC_SCAN_INTERVAL] ?: profile.defaultIntervalSeconds).coerceIn(30, 300),
            triggerOnCarBluetooth = prefs[Keys.TRIGGER_CAR_BT] ?: true,
            triggerOnAndroidAuto = prefs[Keys.TRIGGER_ANDROID_AUTO] ?: true,
            carBluetoothAddress = prefs[Keys.CAR_BT_ADDRESS] ?: "",
            carBluetoothName = prefs[Keys.CAR_BT_NAME] ?: "",
            triggerOnPowerConnected = prefs[Keys.TRIGGER_POWER] ?: true,
            scanDurationSeconds = (prefs[Keys.SCAN_DURATION] ?: 12).coerceIn(8, 25),
            voiceAlertEnabled = prefs[Keys.VOICE_ALERT] ?: true,
            vibrateAlertEnabled = prefs[Keys.VIBRATE_ALERT] ?: true,
            notifyWhenAllSafe = prefs[Keys.NOTIFY_SAFE] ?: true,
            themeMode = ThemeMode.fromName(prefs[Keys.THEME_MODE])
        )
    }

    suspend fun getSettingsOnce(): AppSettings = settingsFlow.first()

    suspend fun setAutoMonitorEnabled(enabled: Boolean) {
        context.siagaDataStore.edit { it[Keys.AUTO_MONITOR] = enabled }
    }

    suspend fun setPeriodicBackgroundScanEnabled(enabled: Boolean) {
        context.siagaDataStore.edit { it[Keys.PERIODIC_BG_SCAN] = enabled }
    }

    suspend fun setScanPowerProfile(profile: ScanPowerProfile) {
        context.siagaDataStore.edit {
            it[Keys.SCAN_POWER_PROFILE] = profile.name
            it[Keys.PERIODIC_SCAN_INTERVAL] = profile.defaultIntervalSeconds
        }
    }

    suspend fun setPeriodicScanIntervalSeconds(seconds: Int) {
        context.siagaDataStore.edit { it[Keys.PERIODIC_SCAN_INTERVAL] = seconds.coerceIn(30, 300) }
    }

    suspend fun setTriggerOnCarBluetooth(enabled: Boolean) {
        context.siagaDataStore.edit { it[Keys.TRIGGER_CAR_BT] = enabled }
    }

    suspend fun setTriggerOnAndroidAuto(enabled: Boolean) {
        context.siagaDataStore.edit { it[Keys.TRIGGER_ANDROID_AUTO] = enabled }
    }

    suspend fun setCarBluetoothDevice(address: String, name: String) {
        context.siagaDataStore.edit {
            it[Keys.CAR_BT_ADDRESS] = address.uppercase()
            it[Keys.CAR_BT_NAME] = name
        }
    }

    suspend fun clearCarBluetoothDevice() {
        context.siagaDataStore.edit {
            it[Keys.CAR_BT_ADDRESS] = ""
            it[Keys.CAR_BT_NAME] = ""
        }
    }

    suspend fun setTriggerOnPowerConnected(enabled: Boolean) {
        context.siagaDataStore.edit { it[Keys.TRIGGER_POWER] = enabled }
    }

    suspend fun setScanDurationSeconds(seconds: Int) {
        context.siagaDataStore.edit { it[Keys.SCAN_DURATION] = seconds.coerceIn(8, 25) }
    }

    suspend fun setVoiceAlertEnabled(enabled: Boolean) {
        context.siagaDataStore.edit { it[Keys.VOICE_ALERT] = enabled }
    }

    suspend fun setVibrateAlertEnabled(enabled: Boolean) {
        context.siagaDataStore.edit { it[Keys.VIBRATE_ALERT] = enabled }
    }

    suspend fun setNotifyWhenAllSafe(enabled: Boolean) {
        context.siagaDataStore.edit { it[Keys.NOTIFY_SAFE] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.siagaDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }
}
