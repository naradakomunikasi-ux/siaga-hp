package com.example.bluetooth

import android.bluetooth.le.ScanRecord
import java.util.Locale

/**
 * Utilities for identifying Apple / iPhone Bluetooth devices and mitigating iOS-specific
 * Bluetooth privacy behaviors (BLE Resolvable Private Address rotation & non-discoverable Classic).
 */
object AppleBluetoothHelper {

    /** Bluetooth SIG Company Identifier for Apple, Inc. */
    const val APPLE_MANUFACTURER_ID: Int = 0x004C

    /** Known Apple Continuity / ANCS / AMS / iAP2 Service UUID prefixes & full UUIDs (uppercase). */
    val APPLE_SERVICE_UUIDS: Set<String> = setOf(
        "7905F431-B5CE-4E99-A40F-4B1E122D00D0", // Apple Notification Center Service (ANCS)
        "89D3502B-0F36-433A-8EF4-C502AD55F8DC", // Apple Media Service (AMS)
        "D0611E78-BBB4-4591-A5F8-487910AE4366", // Apple Continuity Service
        "9FA480E0-4967-4542-9390-D343DC5D04AE", // Apple Continuity Secondary Service
        "00000000-DECA-FADE-DECA-DEAFDECACAFE"  // Apple iAP2
    )

    private val GENERIC_NAMES: Set<String> = setOf(
        "perangkat bluetooth",
        "iphone",
        "apple",
        "ios",
        "unknown",
        "unknown device",
        "smartphone",
        "smartphone terdaftar",
        "iphone / perangkat apple (ble)",
        "iphone / apple (nearby)",
        "iphone / apple (handoff)",
        "iphone / apple (airdrop)"
    )

    fun extractAppleManufacturerData(scanRecord: ScanRecord?): ByteArray? {
        return scanRecord?.getManufacturerSpecificData(APPLE_MANUFACTURER_ID)
    }

    fun hasAppleManufacturerData(scanRecord: ScanRecord?): Boolean {
        return extractAppleManufacturerData(scanRecord) != null
    }

    /**
     * Produces a human-friendly fallback label for anonymous Apple Continuity BLE advertisements
     * (which typically omit the Bluetooth local name on locked iPhones).
     */
    fun describeAppleContinuityPayload(manufacturerData: ByteArray?): String? {
        if (manufacturerData == null || manufacturerData.isEmpty()) return null
        return when (manufacturerData[0].toInt() and 0xFF) {
            0x10 -> "iPhone / Apple (Nearby)"
            0x0C -> "iPhone / Apple (Handoff)"
            0x05 -> "iPhone / Apple (AirDrop)"
            else -> "iPhone / Perangkat Apple (BLE)"
        }
    }

    fun isLikelyAppleDevice(
        name: String?,
        hasAppleManufacturerData: Boolean = false,
        uuidStrings: Collection<String> = emptyList()
    ): Boolean {
        if (hasAppleManufacturerData) return true
        val lowerName = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (lowerName.contains("iphone") ||
            lowerName.contains("ipad") ||
            lowerName.contains("apple") ||
            lowerName.contains("ios")
        ) {
            return true
        }
        return uuidStrings.any { uuid ->
            uuid.uppercase(Locale.ROOT) in APPLE_SERVICE_UUIDS
        }
    }

    fun isGenericDeviceName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized.isBlank() || normalized in GENERIC_NAMES
    }

    /**
     * Fallback matcher for iOS BLE Resolvable Private Address (RPA) rotation.
     * Only matches when the registered phone has a distinctive custom name (never generic "iPhone")
     * and the sighted BLE/Apple device advertises that exact distinctive name.
     */
    fun matchesByCustomNameForIosRpa(
        registeredDeviceName: String,
        registeredOwnerLabel: String,
        sightedName: String?,
        isAppleDevice: Boolean
    ): Boolean {
        if (!isAppleDevice) return false
        val candidate = sightedName?.trim().orEmpty()
        if (isGenericDeviceName(candidate)) return false

        val regDevice = registeredDeviceName.trim()
        val regOwner = registeredOwnerLabel.trim()

        if (!isGenericDeviceName(regDevice) && regDevice.equals(candidate, ignoreCase = true)) {
            return true
        }
        if (!isGenericDeviceName(regOwner) && regOwner.equals(candidate, ignoreCase = true)) {
            return true
        }
        return false
    }
}
