package com.example

import com.example.bluetooth.ActiveDeviceProber
import com.example.bluetooth.BluetoothPresenceEvaluator
import com.example.data.local.CheckStatus
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.local.TriggerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneBluetoothStabilityTest {

    @Test
    fun evaluator_appliesRssiHysteresisMarginForPreviouslyConfirmedOrProbedIphone() {
        val iphone = RegisteredPhoneEntity(
            macAddress = "AA:BB:CC:DD:EE:01",
            deviceName = "iPhone 15 Pro",
            ownerLabel = "iPhone Utama",
            rssiThreshold = -88
        )

        // Without prior confirmation or probe, -93 dBm is below -88 dBm threshold
        val strictEval = BluetoothPresenceEvaluator.evaluate(
            monitoredPhones = listOf(iphone),
            detectedRssiByMac = mapOf(iphone.macAddress to -93),
            connectedMacs = emptySet()
        )
        assertEquals(CheckStatus.MISSING_ALERT, strictEval.status)

        // When previously confirmed present (within hysteresis margin: -88 - 6 = -94 dBm),
        // -93 dBm stays stably detected instead of flapping
        val hysteresisEval = BluetoothPresenceEvaluator.evaluate(
            monitoredPhones = listOf(iphone),
            detectedRssiByMac = mapOf(iphone.macAddress to -93),
            connectedMacs = emptySet(),
            previouslyConfirmedMacs = setOf(iphone.macAddress)
        )
        assertEquals(CheckStatus.ALL_PRESENT, hysteresisEval.status)
        assertTrue(iphone.macAddress in hysteresisEval.detectedAddresses)

        // Active GATT probe reachability also stabilizes presence with hysteresis
        val probedEval = BluetoothPresenceEvaluator.evaluate(
            monitoredPhones = listOf(iphone),
            detectedRssiByMac = mapOf(iphone.macAddress to -94),
            connectedMacs = emptySet(),
            probedReachableMacs = setOf(iphone.macAddress)
        )
        assertEquals(CheckStatus.ALL_PRESENT, probedEval.status)
    }

    @Test
    fun antiFlap_retainsIphoneOnSingleBackgroundMissButAlertsOnSecondMissOrIgnition() {
        val iphone = RegisteredPhoneEntity(
            macAddress = "AA:BB:CC:DD:EE:02",
            deviceName = "iPhone 16 Pro",
            ownerLabel = "iPhone Kerja",
            rssiThreshold = -95,
            lastRssi = -64
        )

        val rawMissing = BluetoothPresenceEvaluator.evaluate(
            monitoredPhones = listOf(iphone),
            detectedRssiByMac = emptyMap(),
            connectedMacs = emptySet()
        )
        assertEquals(CheckStatus.MISSING_ALERT, rawMissing.status)

        // Background cycle 1 miss for a recently-seen iPhone -> held by stability grace (no false alarm)
        val firstBackgroundMiss = BluetoothPresenceEvaluator.applyBackgroundAntiFlap(
            evaluation = rawMissing,
            triggerSource = TriggerSource.BACKGROUND_PERIODIC,
            consecutiveMissCounts = mapOf(iphone.macAddress to 0),
            recentlyConfirmedMacs = setOf(iphone.macAddress)
        )
        assertEquals(CheckStatus.ALL_PRESENT, firstBackgroundMiss.status)
        assertTrue(iphone.macAddress in firstBackgroundMiss.graceRetainedMacs)
        assertTrue(firstBackgroundMiss.results.first().heldByStabilityGrace)

        // Background cycle 2 consecutive miss -> confirmed missing!
        val secondBackgroundMiss = BluetoothPresenceEvaluator.applyBackgroundAntiFlap(
            evaluation = rawMissing,
            triggerSource = TriggerSource.BACKGROUND_PERIODIC,
            consecutiveMissCounts = mapOf(iphone.macAddress to 1),
            recentlyConfirmedMacs = setOf(iphone.macAddress)
        )
        assertEquals(CheckStatus.MISSING_ALERT, secondBackgroundMiss.status)
        assertEquals(1, secondBackgroundMiss.missingPhones.size)

        // Ignition trigger MUST alert immediately without waiting for 2 cycles
        val ignitionEval = BluetoothPresenceEvaluator.applyBackgroundAntiFlap(
            evaluation = rawMissing,
            triggerSource = TriggerSource.IGNITION_POWER,
            consecutiveMissCounts = mapOf(iphone.macAddress to 0),
            recentlyConfirmedMacs = setOf(iphone.macAddress)
        )
        assertEquals(CheckStatus.MISSING_ALERT, ignitionEval.status)
        assertTrue(ignitionEval.graceRetainedMacs.isEmpty())
    }

    @Test
    fun prober_classifiesTransientIosGattErrorsAndCalculatesBackoff() {
        assertTrue(ActiveDeviceProber.isTransientGattError(133))
        assertTrue(ActiveDeviceProber.isTransientGattError(8))
        assertTrue(ActiveDeviceProber.isTransientGattError(19))
        assertTrue(ActiveDeviceProber.isTransientGattError(22))
        assertFalse(ActiveDeviceProber.isTransientGattError(0))

        assertEquals(2000L, ActiveDeviceProber.calculateReconnectBackoffMs(0))
        assertEquals(5000L, ActiveDeviceProber.calculateReconnectBackoffMs(1))
        assertEquals(10000L, ActiveDeviceProber.calculateReconnectBackoffMs(2))
        assertEquals(20000L, ActiveDeviceProber.calculateReconnectBackoffMs(3))
        assertEquals(20000L, ActiveDeviceProber.calculateReconnectBackoffMs(10))
    }
}
