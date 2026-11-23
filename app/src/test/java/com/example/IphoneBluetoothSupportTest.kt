package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.bluetooth.AppleBluetoothHelper
import com.example.bluetooth.BluetoothEngineState
import com.example.bluetooth.BluetoothPresenceEvaluator
import com.example.bluetooth.ScannedBluetoothDevice
import com.example.data.local.CheckStatus
import com.example.data.local.RegisteredPhoneEntity
import com.example.ui.SiagaUiState
import com.example.ui.components.BluetoothScannerSheetContent
import com.example.ui.theme.SiagaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IphoneBluetoothSupportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appleHelper_identifiesAppleContinuityAndAncsUuids() {
        assertEquals(
            "iPhone / Apple (Nearby)",
            AppleBluetoothHelper.describeAppleContinuityPayload(byteArrayOf(0x10, 0x05, 0x01))
        )
        assertEquals(
            "iPhone / Apple (Handoff)",
            AppleBluetoothHelper.describeAppleContinuityPayload(byteArrayOf(0x0C, 0x0E))
        )
        assertTrue(
            AppleBluetoothHelper.isLikelyAppleDevice(
                name = "Ponsel Pribadi",
                uuidStrings = listOf("7905F431-B5CE-4E99-A40F-4B1E122D00D0") // ANCS
            )
        )
        assertTrue(
            AppleBluetoothHelper.isLikelyAppleDevice(
                name = "iPhone 15 Pro Budi"
            )
        )
        assertFalse(
            AppleBluetoothHelper.isLikelyAppleDevice(
                name = "Pixel 9 Pro"
            )
        )
    }

    @Test
    fun presenceEvaluator_matchesRotatedIosRpaByUniqueCustomNameOnly() {
        val registeredIphone = RegisteredPhoneEntity(
            macAddress = "AA:11:22:33:44:55",
            deviceName = "iPhone 15 Pro Budi",
            ownerLabel = "HP Utama Budi",
            rssiThreshold = -88
        )
        val genericIphone = RegisteredPhoneEntity(
            macAddress = "BB:11:22:33:44:55",
            deviceName = "iPhone",
            ownerLabel = "iPhone",
            rssiThreshold = -88
        )

        // iOS rotated its BLE MAC to a new RPA address (5A:77:88:99:00:11)
        val rotatedRpaMac = "5A:77:88:99:00:11"
        val genericRpaMac = "6B:77:88:99:00:22"
        val sighted = mapOf(
            rotatedRpaMac to ScannedBluetoothDevice(
                address = rotatedRpaMac,
                name = "iPhone 15 Pro Budi",
                rssi = -62,
                isBonded = false,
                isConnected = false,
                isLikelyCarAudio = false,
                isAppleDevice = true
            ),
            genericRpaMac to ScannedBluetoothDevice(
                address = genericRpaMac,
                name = "iPhone",
                rssi = -55,
                isBonded = false,
                isConnected = false,
                isLikelyCarAudio = false,
                isAppleDevice = true
            )
        )

        val evaluation = BluetoothPresenceEvaluator.evaluate(
            monitoredPhones = listOf(registeredIphone, genericIphone),
            detectedRssiByMac = mapOf(rotatedRpaMac to -62, genericRpaMac to -55),
            connectedMacs = emptySet(),
            sightedDevices = sighted
        )

        // Unique custom-named iPhone resolves despite BLE RPA MAC rotation
        assertTrue(registeredIphone.macAddress in evaluation.detectedAddresses)
        // Generic "iPhone" MUST NOT match strangers' iPhones by name
        assertFalse(genericIphone.macAddress in evaluation.detectedAddresses)
        assertEquals(CheckStatus.MISSING_ALERT, evaluation.status)
        assertEquals(1, evaluation.missingPhones.size)
        assertEquals(genericIphone.macAddress, evaluation.missingPhones.first().macAddress)
    }

    @Test
    fun scannerSheet_displaysIphoneBadgeAndTriggersPairAction() {
        val iphoneMac = "4C:88:99:AA:BB:CC"
        val discoveredIphone = ScannedBluetoothDevice(
            address = iphoneMac,
            name = "iPhone 16 Pro",
            rssi = -54,
            isBonded = false,
            isConnected = false,
            isLikelyCarAudio = false,
            isAppleDevice = true
        )
        var pairedMac: String? = null

        val uiState = SiagaUiState(
            hasBluetoothPermissions = true,
            btState = BluetoothEngineState(
                isHardwareSupported = true,
                isBluetoothEnabled = true,
                discoveredDevices = mapOf(iphoneMac to discoveredIphone)
            )
        )

        composeTestRule.setContent {
            SiagaTheme {
                BluetoothScannerSheetContent(
                    uiState = uiState,
                    registeredMacs = emptySet(),
                    onStartScan = {},
                    onStopScan = {},
                    onRequestPermissions = {},
                    onRequestEnableBluetooth = {},
                    onQuickAddDevice = {},
                    onCustomizeAddDevice = {},
                    onBatchAddDevices = {},
                    onClose = {},
                    onPairDevice = { pairedMac = it }
                )
            }
        }

        composeTestRule.onNodeWithText("iPhone 16 Pro").assertIsDisplayed()
        composeTestRule.onNodeWithText("iPhone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scanner_pair_device_$iphoneMac").performClick()

        assertEquals(iphoneMac, pairedMac)
    }
}
