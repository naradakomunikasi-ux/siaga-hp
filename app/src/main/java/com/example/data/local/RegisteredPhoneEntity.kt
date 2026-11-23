package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

enum class PhoneCategory(val label: String) {
    SMARTPHONE("Smartphone"),
    SMARTWATCH("Jam Tangan"),
    TABLET("Tablet"),
    CAR_HEADUNIT("Mobil / Head Unit"),
    AUDIO("Earbuds / Audio"),
    OTHER("Lainnya");

    companion object {
        fun fromName(name: String): PhoneCategory {
            val upper = name.trim().uppercase(Locale.ROOT)
            return entries.firstOrNull { it.name == upper } ?: when (upper) {
                "PRIMARY", "WORK", "FAMILY", "BACKUP" -> SMARTPHONE
                else -> SMARTPHONE
            }
        }

        /**
         * Infers an intuitive Bluetooth device category from the advertised device name and
         * Bluetooth device class so watches, tablets, car head units, and earbuds are grouped
         * cleanly instead of always defaulting to smartphone.
         */
        fun inferFromDevice(
            deviceName: String,
            isLikelyCarAudio: Boolean = false
        ): PhoneCategory {
            val lower = deviceName.trim().lowercase(Locale.ROOT)
            return when {
                isLikelyCarAudio ||
                    lower.contains("car") ||
                    lower.contains("mobil") ||
                    lower.contains("headunit") ||
                    lower.contains("head unit") ||
                    lower.contains("toyota") ||
                    lower.contains("honda") ||
                    lower.contains("suzuki") ||
                    lower.contains("mitsubishi") ||
                    lower.contains("hyundai") ||
                    lower.contains("wuling") ||
                    lower.contains("mazda") ||
                    lower.contains("bmw") ||
                    lower.contains("mercedes") ||
                    lower.contains("android auto") ||
                    lower.contains("carplay") -> CAR_HEADUNIT

                lower.contains("watch") ||
                    lower.contains("band") ||
                    lower.contains("fit") ||
                    lower.contains("garmin") ||
                    lower.contains("amazfit") ||
                    lower.contains("mi band") -> SMARTWATCH

                lower.contains("ipad") ||
                    lower.contains("tab") ||
                    lower.contains("pad") -> TABLET

                lower.contains("buds") ||
                    lower.contains("airpods") ||
                    lower.contains("earbuds") ||
                    lower.contains("headphone") ||
                    lower.contains("headset") ||
                    lower.contains("tws") ||
                    lower.contains("speaker") ||
                    lower.contains("jbl") ||
                    lower.contains("sony wh") ||
                    lower.contains("sony wf") -> AUDIO

                lower.contains("iphone") ||
                    lower.contains("galaxy") ||
                    lower.contains("pixel") ||
                    lower.contains("xiaomi") ||
                    lower.contains("redmi") ||
                    lower.contains("poco") ||
                    lower.contains("oppo") ||
                    lower.contains("vivo") ||
                    lower.contains("realme") ||
                    lower.contains("hp") ||
                    lower.contains("phone") -> SMARTPHONE

                else -> SMARTPHONE
            }
        }
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
    val category: String = PhoneCategory.SMARTPHONE.name,
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
