package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TriggerSource(val label: String) {
    ANDROID_AUTO("Android Auto"),
    CAR_BLUETOOTH("Bluetooth Sentral / Mobil"),
    IGNITION_POWER("Daya Mesin (USB/ACC)"),
    BACKGROUND_PERIODIC("Pemindaian Latar Belakang"),
    MANUAL_SCAN("Cek Manual");

    companion object {
        fun fromName(name: String): TriggerSource =
            entries.firstOrNull { it.name == name } ?: MANUAL_SCAN
    }
}

enum class CheckStatus(val label: String) {
    ALL_PRESENT("Semua Terbawa"),
    MISSING_ALERT("Perangkat Tertinggal"),
    BT_UNAVAILABLE("Bluetooth Nonaktif");

    companion object {
        fun fromName(name: String): CheckStatus =
            entries.firstOrNull { it.name == name } ?: ALL_PRESENT
    }
}

@Entity(tableName = "check_logs")
data class CheckLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val triggerSource: String,
    val totalMonitored: Int,
    val detectedCount: Int,
    val missingPhoneNames: String,
    val status: String
)
