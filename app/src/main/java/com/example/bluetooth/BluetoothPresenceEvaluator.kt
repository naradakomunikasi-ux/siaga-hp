package com.example.bluetooth

import com.example.data.local.CheckStatus
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.local.TriggerSource

data class PhonePresenceResult(
    val phone: RegisteredPhoneEntity,
    val isPresent: Boolean,
    val measuredRssi: Int?,
    val isActivelyConnected: Boolean,
    val reason: String
)

data class PresenceEvaluation(
    val results: List<PhonePresenceResult>,
    val detectedAddresses: Set<String>,
    val missingPhones: List<RegisteredPhoneEntity>,
    val status: CheckStatus
)

object BluetoothPresenceEvaluator {

    fun evaluate(
        monitoredPhones: List<RegisteredPhoneEntity>,
        detectedRssiByMac: Map<String, Int?>,
        connectedMacs: Set<String>
    ): PresenceEvaluation {
        val normalizedConnected = connectedMacs.map { it.uppercase() }.toSet()
        val normalizedDetected = detectedRssiByMac.entries.associate { (k, v) -> k.uppercase() to v }

        val results = mutableListOf<PhonePresenceResult>()
        val detectedAddresses = mutableSetOf<String>()
        val missingPhones = mutableListOf<RegisteredPhoneEntity>()

        for (phone in monitoredPhones) {
            val mac = phone.macAddress.uppercase()
            val isConnected = mac in normalizedConnected
            val wasSighted = normalizedDetected.containsKey(mac)
            val rssi = normalizedDetected[mac]

            val withinRange = when {
                isConnected -> true
                !wasSighted -> false
                rssi == null -> true
                else -> rssi >= phone.rssiThreshold
            }

            if (withinRange) {
                detectedAddresses.add(mac)
                val reason = when {
                    isConnected -> "Terhubung aktif via Bluetooth"
                    rssi != null -> "Sinyal terdeteksi ($rssi dBm)"
                    else -> "Terdeteksi dalam jangkauan Bluetooth"
                }
                results.add(
                    PhonePresenceResult(
                        phone = phone,
                        isPresent = true,
                        measuredRssi = rssi ?: if (isConnected) -50 else null,
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
            TriggerSource.IGNITION_POWER,
            TriggerSource.MANUAL_SCAN -> true

            TriggerSource.BACKGROUND_PERIODIC -> {
                // Alert if any phone just became missing compared to the previous background cycle
                currentMissingMacs.any { it !in previousBackgroundMissingMacs }
            }
        }
    }
}
