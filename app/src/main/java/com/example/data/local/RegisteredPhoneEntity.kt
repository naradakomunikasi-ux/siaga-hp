package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PhoneCategory(val label: String) {
    PRIMARY("HP Utama"),
    WORK("HP Kerja"),
    FAMILY("HP Keluarga"),
    BACKUP("HP Cadangan");

    companion object {
        fun fromName(name: String): PhoneCategory =
            entries.firstOrNull { it.name == name } ?: PRIMARY
    }
}

@Entity(
    tableName = "registered_phones",
    indices = [Index(value = ["isMonitored"])]
)
data class RegisteredPhoneEntity(
    @PrimaryKey
    @ColumnInfo(name = "macAddress")
    val macAddress: String,
    @ColumnInfo(name = "deviceName")
    val deviceName: String,
    @ColumnInfo(name = "ownerLabel")
    val ownerLabel: String,
    @ColumnInfo(name = "category")
    val category: String = PhoneCategory.PRIMARY.name,
    @ColumnInfo(name = "isMonitored")
    val isMonitored: Boolean = true,
    @ColumnInfo(name = "rssiThreshold")
    val rssiThreshold: Int = -88,
    @ColumnInfo(name = "lastSeenTimestamp")
    val lastSeenTimestamp: Long? = null,
    @ColumnInfo(name = "lastRssi")
    val lastRssi: Int? = null,
    @ColumnInfo(name = "addedAt")
    val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun normalizeMacAddress(raw: String): String =
            raw.trim().replace('-', ':').uppercase()

        fun isValidMacAddress(raw: String): Boolean =
            MAC_REGEX.matches(normalizeMacAddress(raw))
    }
}
