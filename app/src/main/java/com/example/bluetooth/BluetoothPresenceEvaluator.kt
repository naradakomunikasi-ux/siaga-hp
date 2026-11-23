package com.example.bluetooth

import com.example.data.local.CheckStatus
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.local.TriggerSource

data class PhonePresenceResult(
    val phone: RegisteredPhoneEntity,
    val isPresent: Boolean,
    val measuredRssi: Int?,
    val isActivelyConnected: Boolean,
    val reason: String,
    val heldByStabilityGrace: Boolean = false
)

data class PresenceEvaluation(
    val results: List<PhonePresenceResult>,
    val detectedAddresses: Set<String>,
    val missingPhones: List<RegisteredPhoneEntity>,
    val status: CheckStatus,
    val graceRetainedMacs: Set<String> = emptySet()
)

object BluetoothPresenceEvaluator {

    /**
     * Extra RSSI hysteresis margin (in dBm) granted to devices already confirmed present
     * recently or verified by active GATT/ACL probing. Prevents iPhones in pockets/bags from
     * flip-flopping between Detected and Missing when BLE RSSI fluctuates by 4-6 dBm.
     */
    const val RSSI_HYSTERESIS_MARGIN_DB: Int = 6

    /**
     * Number of consecutive missed background periodic scans required before declaring a
     * recently-seen iPhone / bonded phone as missing (anti-flap protection).
     */
    const val BACKGROUND_ANTI_FLAP_MISS_THRESHOLD: Int = 2

    fun evaluate(
        monitoredPhones: List<RegisteredPhoneEntity>,
        detectedRssiByMac: Map<String, Int?>,
        connectedMacs: Set<String>,
        sightedDevices: Map<String, ScannedBluetoothDevice> = emptyMap(),
        previouslyConfirmedMacs: Set<String> = emptySet(),
        probedReachableMacs: Set<String> = emptySet()
    ): PresenceEvaluation {
        val normalizedConnected = connectedMacs.map { it.uppercase() }.toSet()
        val normalizedDetected = detectedRssiByMac.entries.associate { (k, v) -> k.uppercase() to v }
        val normalizedPrevConfirmed = previouslyConfirmedMacs.map { it.uppercase() }.toSet()
        val normalizedProbed = probedReachableMacs.map { it.uppercase() }.toSet()

        val results = mutableListOf<PhonePresenceResult>()
        val detectedAddresses = mutableSetOf<String>()
        val missingPhones = mutableListOf<RegisteredPhoneEntity>()

        for (phone in monitoredPhones) {
            val mac = phone.macAddress.uppercase()
            val isConnected = mac in normalizedConnected
            val wasProbedReachable = mac in normalizedProbed
            val wasSightedByMac = normalizedDetected.containsKey(mac)
            val directRssi = normalizedDetected[mac]

            // Fallback for iOS BLE Resolvable Private Address (RPA) rotation:
            // Only matches if the iPhone broadcasts a distinctive custom name matching this registered phone.
            val iosRpaMatch: ScannedBluetoothDevice? = if (!isConnected && !wasSightedByMac) {
                sightedDevices.values
                    .filter { sighted ->
                        normalizedDetected.containsKey(sighted.address.uppercase()) &&
                            AppleBluetoothHelper.matchesByCustomNameForIosRpa(
                                registeredDeviceName = phone.deviceName,
                                registeredOwnerLabel = phone.ownerLabel,
                                sightedName = sighted.name,
                                isAppleDevice = sighted.isAppleDevice
                            )
                    }
                    .maxByOrNull { it.rssi ?: Int.MIN_VALUE }
            } else {
                null
            }

            val wasSighted = wasSightedByMac || iosRpaMatch != null || wasProbedReachable
            val rssi = if (wasSightedByMac) {
                directRssi
            } else {
                iosRpaMatch?.let { normalizedDetected[it.address.uppercase()] ?: it.rssi }
            }

            // Apply RSSI hysteresis margin if previously confirmed or actively answered a GATT ping
            val effectiveThreshold = if (mac in normalizedPrevConfirmed || wasProbedReachable) {
                phone.rssiThreshold - RSSI_HYSTERESIS_MARGIN_DB
            } else {
                phone.rssiThreshold
            }

            val withinRange = when {
                isConnected -> true
                !wasSighted -> false
                rssi == null -> true
                else -> rssi >= effectiveThreshold
            }

            if (withinRange) {
                detectedAddresses.add(mac)
                val reason = when {
                    isConnected -> "Terhubung aktif via Bluetooth"
                    wasProbedReachable && rssi != null -> "Respons ping BLE GATT ($rssi dBm)"
                    wasProbedReachable -> "Respons ping BLE GATT terkonfirmasi"
                    iosRpaMatch != null && rssi != null -> "Terdeteksi via BLE iOS ($rssi dBm)"
                    iosRpaMatch != null -> "Terdeteksi via BLE iOS dalam jangkauan"
                    rssi != null -> "Sinyal terdeteksi ($rssi dBm)"
                    else -> "Terdeteksi dalam jangkauan Bluetooth"
                }
                results.add(
                    PhonePresenceResult(
                        phone = phone,
                        isPresent = true,
                        measuredRssi = rssi ?: if (isConnected || wasProbedReachable) -54 else null,
                        isActivelyConnected = isConnected,
                        reason = reason
                    )
                )
            } else {
                missingPhones.add(phone)
                val reason = if (wasSighted && rssi != null) {
                    "Sinyal terlalu lemah ($rssi dBm < batas ${phone.rssiThreshold} dBm)"
                } else {
                    "Sinyal Bluetooth tidak terdeteksi"
                }
                results.add(
                    PhonePresenceResult(
                        phone = phone,
                        isPresent = false,
                        measuredRssi = rssi,
                        isActivelyConnected = false,
                        reason = reason
                    )
                )
            }
        }

        val status = if (missingPhones.isEmpty()) {
            CheckStatus.ALL_PRESENT
        } else {
            CheckStatus.MISSING_ALERT
        }

        return PresenceEvaluation(
            results = results,
            detectedAddresses = detectedAddresses,
            missingPhones = missingPhones,
            status = status
        )
    }

    /**
     * Stabilizes periodic background evaluations against brief iOS BLE advertising pauses or
     * transient GATT glitches. On the first single missed periodic check for a previously
     * confirmed phone, retains it under stability grace instead of flapping to MISSING_ALERT.
     * Critical ignition/car-BT/manual departure checks ALWAYS evaluate strictly without grace.
     */
    fun applyBackgroundAntiFlap(
        evaluation: PresenceEvaluation,
        triggerSource: TriggerSource,
        consecutiveMissCounts: Map<String, Int>,
        recentlyConfirmedMacs: Set<String>
    ): PresenceEvaluation {
        if (triggerSource != TriggerSource.BACKGROUND_PERIODIC) return evaluation
        if (evaluation.missingPhones.isEmpty()) return evaluation

        val normalizedRecent = recentlyConfirmedMacs.map { it.uppercase() }.toSet()
        val updatedResults = mutableListOf<PhonePresenceResult>()
        val updatedDetected = evaluation.detectedAddresses.toMutableSet()
        val updatedMissing = mutableListOf<RegisteredPhoneEntity>()
        val retainedByGrace = mutableSetOf<String>()

        for (item in evaluation.results) {
            val mac = item.phone.macAddress.uppercase()
            if (item.isPresent) {
                updatedResults.add(item)
            } else {
                val missCountAfterThisCycle = (consecutiveMissCounts[mac] ?: 0) + 1
                val eligibleForGrace = mac in normalizedRecent &&
                    missCountAfterThisCycle < BACKGROUND_ANTI_FLAP_MISS_THRESHOLD

                if (eligibleForGrace) {
                    retainedByGrace.add(mac)
                    updatedDetected.add(mac)
                    updatedResults.add(
                        item.copy(
                            isPresent = true,
                            measuredRssi = item.phone.lastRssi,
                            reason = "Menjaga kestabilan sinyal iOS (verifikasi ulang siklus berikutnya)",
                            heldByStabilityGrace = true
                        )
                    )
                } else {
                    updatedMissing.add(item.phone)
                    updatedResults.add(item)
                }
            }
        }

        val finalStatus = if (updatedMissing.isEmpty()) {
            CheckStatus.ALL_PRESENT
        } else {
            CheckStatus.MISSING_ALERT
        }

        return PresenceEvaluation(
            results = updatedResults,
            detectedAddresses = updatedDetected,
            missingPhones = updatedMissing,
            status = finalStatus,
            graceRetainedMacs = retainedByGrace
        )
    }

    /**
     * Prevents notification spam during periodic background scans while guaranteeing
     * immediate alerts whenever a car ignition or manual check triggers, or when a
     * newly missing smartphone is detected during background monitoring.
     */
    fun shouldFireMissingAlert(
        triggerSource: TriggerSource,
        currentMissingMacs: Set<String>,
        previousBackgroundMissingMacs: Set<String>
    ): Boolean {
        if (currentMissingMacs.isEmpty()) return false
        return when (triggerSource) {
            TriggerSource.CAR_BLUETOOTH,
            TriggerSource.ANDROID_AUTO,
            TriggerSource.IGNITION_POWER,
            TriggerSource.MANUAL_SCAN -> true

            TriggerSource.BACKGROUND_PERIODIC -> {
                // Alert if any device just became missing compared to the previous background cycle
                currentMissingMacs.any { it !in previousBackgroundMissingMacs }
            }
        }
    }
}
