package com.example

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.bluetooth.BluetoothPresenceEvaluator
import com.example.data.local.CheckStatus
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.local.TriggerSource
import com.example.service.CarDepartureMonitorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BluetoothBackgroundServiceTest {

    @Test
    fun `evaluator marks phones within RSSI threshold or connected as present`() {
        val phones = listOf(
            RegisteredPhoneEntity(
                macAddress = "AA:BB:CC:11:22:33",
                deviceName = "Pixel 9",
                ownerLabel = "HP Utama",
                rssiThreshold = -85
            ),
            RegisteredPhoneEntity(
                macAddress = "DD:EE:FF:44:55:66",
                deviceName = "Galaxy S24",
                ownerLabel = "HP Kerja",
                rssiThreshold = -85
            ),
            RegisteredPhoneEntity(
                macAddress = "11:22:33:AA:BB:CC",
                deviceName = "iPhone 16",
                ownerLabel = "HP Pasangan",
                rssiThreshold = -80
            )
        )

        val evaluation = BluetoothPresenceEvaluator.evaluate(
            monitoredPhones = phones,
            detectedRssiByMac = mapOf(
                "AA:BB:CC:11:22:33" to -62,
                "11:22:33:AA:BB:CC" to -94 // Too weak: below -80 dBm threshold
            ),
            connectedMacs = setOf("DD:EE:FF:44:55:66") // Actively connected via ACL/profile
        )

        assertEquals(CheckStatus.MISSING_ALERT, evaluation.status)
        assertEquals(setOf("AA:BB:CC:11:22:33", "DD:EE:FF:44:55:66"), evaluation.detectedAddresses)
        assertEquals(1, evaluation.missingPhones.size)
        assertEquals("HP Pasangan", evaluation.missingPhones.first().ownerLabel)
    }

    @Test
    fun `evaluator deduplicates repeated periodic background alerts while always alerting on ignition`() {
        val missingSet = setOf("11:22:33:AA:BB:CC")

        // First periodic scan missing -> fires alert
        assertTrue(
            BluetoothPresenceEvaluator.shouldFireMissingAlert(
                triggerSource = TriggerSource.BACKGROUND_PERIODIC,
                currentMissingMacs = missingSet,
                previousBackgroundMissingMacs = emptySet()
            )
        )

        // Second identical periodic scan -> suppresses duplicate spam
        assertFalse(
            BluetoothPresenceEvaluator.shouldFireMissingAlert(
                triggerSource = TriggerSource.BACKGROUND_PERIODIC,
                currentMissingMacs = missingSet,
                previousBackgroundMissingMacs = missingSet
            )
        )

        // Car ignition trigger -> always alerts
        assertTrue(
            BluetoothPresenceEvaluator.shouldFireMissingAlert(
                triggerSource = TriggerSource.CAR_BLUETOOTH,
                currentMissingMacs = missingSet,
                previousBackgroundMissingMacs = missingSet
            )
        )
    }

    @Test
    fun `CarDepartureMonitorService starts foreground and tracks running state when permissions granted`() {
        val app = ApplicationProvider.getApplicationContext<SiagaApp>()
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val startIntent = Intent(app, CarDepartureMonitorService::class.java).apply {
            action = CarDepartureMonitorService.ACTION_START
        }

        val controller = Robolectric.buildService(CarDepartureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        assertTrue(CarDepartureMonitorService.isRunning.value)
        assertTrue(CarDepartureMonitorService.telemetry.value.isRunning)

        controller.destroy()
        assertFalse(CarDepartureMonitorService.isRunning.value)
        assertFalse(CarDepartureMonitorService.telemetry.value.isRunning)
    }
}
