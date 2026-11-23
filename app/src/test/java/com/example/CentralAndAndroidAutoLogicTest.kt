package com.example

import com.example.bluetooth.BluetoothPresenceEvaluator
import com.example.data.local.PhoneCategory
import com.example.data.local.TriggerSource
import com.example.service.AndroidAutoConnectionMonitor
import com.example.service.AndroidAutoConnectionService
import com.example.service.AndroidAutoConnectionType
import com.example.ui.components.estimateDistanceMeters
import com.example.ui.components.formatDistanceMeters
import com.example.ui.components.formatRssiQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CentralAndAndroidAutoLogicTest {

    @Test
    fun `infers Bluetooth device categories accurately and preserves legacy DB values`() {
        assertEquals(
            PhoneCategory.SMARTWATCH,
            PhoneCategory.inferFromDevice("HUAWEI WATCH GT 4", isLikelyCarAudio = false)
        )
        assertEquals(
            PhoneCategory.CAR_HEADUNIT,
            PhoneCategory.inferFromDevice("Red Cross", isLikelyCarAudio = true)
        )
        assertEquals(
            PhoneCategory.TABLET,
            PhoneCategory.inferFromDevice("iPad Pro 11", isLikelyCarAudio = false)
        )
        assertEquals(
            PhoneCategory.AUDIO,
            PhoneCategory.inferFromDevice("AirPods Pro", isLikelyCarAudio = false)
        )
        assertEquals(
            PhoneCategory.SMARTPHONE,
            PhoneCategory.inferFromDevice("iPhone Petty", isLikelyCarAudio = false)
        )
        assertEquals(PhoneCategory.SMARTPHONE, PhoneCategory.fromName("PRIMARY"))
        assertEquals(PhoneCategory.SMARTPHONE, PhoneCategory.fromName("WORK"))
        assertEquals(PhoneCategory.SMARTPHONE, PhoneCategory.fromName("FAMILY"))
        assertEquals(PhoneCategory.SMARTPHONE, PhoneCategory.fromName("BACKUP"))
    }

    @Test
    fun `estimates distance in meters and formats Sentral proximity wording`() {
        val nearMeters = estimateDistanceMeters(-59)
        val mediumMeters = estimateDistanceMeters(-75)
        val farMeters = estimateDistanceMeters(-90)

        assertTrue(nearMeters in 0.8..1.2)
        assertTrue(mediumMeters > nearMeters)
        assertTrue(farMeters > mediumMeters)

        val nearFormatted = formatDistanceMeters(-59)
        assertTrue("Expected meter unit in $nearFormatted", nearFormatted.endsWith("m"))

        val qualityText = formatRssiQuality(-72)
        assertTrue("Expected Sentral wording in $qualityText", qualityText.contains("Sentral"))
        assertTrue("Expected meter estimate in $qualityText", qualityText.contains("m)"))
    }

    @Test
    fun `maps Android Auto connection codes and triggers immediate missing-device alert`() {
        assertEquals(
            AndroidAutoConnectionType.NOT_CONNECTED,
            AndroidAutoConnectionMonitor.mapCarConnectionCode(AndroidAutoConnectionMonitor.CONNECTION_TYPE_NOT_CONNECTED)
        )
        assertEquals(
            AndroidAutoConnectionType.AUTOMOTIVE_OS,
            AndroidAutoConnectionMonitor.mapCarConnectionCode(AndroidAutoConnectionMonitor.CONNECTION_TYPE_NATIVE)
        )
        assertEquals(
            AndroidAutoConnectionType.ANDROID_AUTO,
            AndroidAutoConnectionMonitor.mapCarConnectionCode(AndroidAutoConnectionMonitor.CONNECTION_TYPE_PROJECTION)
        )
        assertTrue(AndroidAutoConnectionType.ANDROID_AUTO.isConnected)

        val shouldAlert = BluetoothPresenceEvaluator.shouldFireMissingAlert(
            triggerSource = TriggerSource.ANDROID_AUTO,
            currentMissingMacs = setOf("48:05:E2:15:7A:A8"),
            previousBackgroundMissingMacs = setOf("48:05:E2:15:7A:A8")
        )
        assertTrue(shouldAlert)
    }

    @Test
    fun `android auto service triggers auto scan only on fresh connection outside debounce window`() {
        val now = 100_000L
        // Fresh disconnected -> connected transition triggers auto scan
        assertTrue(
            AndroidAutoConnectionService.shouldTriggerAutoScan(
                previousConnected = false,
                newConnected = true,
                nowMs = now,
                lastTriggerMs = 0L
            )
        )
        // Already connected does not re-trigger
        assertFalse(
            AndroidAutoConnectionService.shouldTriggerAutoScan(
                previousConnected = true,
                newConnected = true,
                nowMs = now + 30_000L,
                lastTriggerMs = now
            )
        )
        // Disconnecting never triggers scan
        assertFalse(
            AndroidAutoConnectionService.shouldTriggerAutoScan(
                previousConnected = true,
                newConnected = false,
                nowMs = now + 30_000L,
                lastTriggerMs = now
            )
        )
        // Reconnecting within debounce window is suppressed to prevent handshake duplicates
        assertFalse(
            AndroidAutoConnectionService.shouldTriggerAutoScan(
                previousConnected = false,
                newConnected = true,
                nowMs = now + 5_000L,
                lastTriggerMs = now
            )
        )
        // Reconnecting after debounce window triggers fresh auto scan
        assertTrue(
            AndroidAutoConnectionService.shouldTriggerAutoScan(
                previousConnected = false,
                newConnected = true,
                nowMs = now + AndroidAutoConnectionService.AUTO_SCAN_DEBOUNCE_MS,
                lastTriggerMs = now
            )
        )
    }
}
