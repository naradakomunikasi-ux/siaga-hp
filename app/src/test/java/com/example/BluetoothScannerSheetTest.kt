package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.bluetooth.BluetoothEngineState
import com.example.bluetooth.ScannedBluetoothDevice
import com.example.ui.SiagaUiState
import com.example.ui.components.BluetoothScannerSheetContent
import com.example.ui.theme.SiagaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BluetoothScannerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `scanner sheet filters nearby devices and supports 1-tap quick add and batch save`() {
        val quickAdded = mutableListOf<String>()
        val batchAdded = mutableListOf<String>()

        val dev1 = ScannedBluetoothDevice(
            address = "AA:BB:CC:11:22:33",
            name = "Pixel 9 Pro",
            rssi = -54,
            isBonded = true,
            isConnected = true,
            isLikelyCarAudio = false
        )
        val dev2 = ScannedBluetoothDevice(
            address = "DD:EE:FF:44:55:66",
            name = "Galaxy S24 Ultra",
            rssi = -68,
            isBonded = false,
            isConnected = false,
            isLikelyCarAudio = false
        )
        val unnamedBeacon = ScannedBluetoothDevice(
            address = "99:88:77:66:55:44",
            name = "Perangkat Bluetooth",
            rssi = -82,
            isBonded = false,
            isConnected = false,
            isLikelyCarAudio = false
        )

        val state = SiagaUiState(
            btState = BluetoothEngineState(
                isHardwareSupported = true,
                isBluetoothEnabled = true,
                discoveredDevices = mapOf(
                    dev1.address to dev1,
                    dev2.address to dev2,
                    unnamedBeacon.address to unnamedBeacon
                )
            ),
            hasBluetoothPermissions = true,
            hasNotificationPermission = true
        )

        composeTestRule.setContent {
            SiagaTheme(darkTheme = true) {
                BluetoothScannerSheetContent(
                    uiState = state,
                    registeredMacs = emptySet(),
                    onStartScan = {},
                    onStopScan = {},
                    onRequestPermissions = {},
                    onRequestEnableBluetooth = {},
                    onQuickAddDevice = { quickAdded.add(it.address) },
                    onCustomizeAddDevice = {},
                    onBatchAddDevices = { list -> batchAdded.addAll(list.map { it.address }) },
                    onClose = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Verify named devices & MAC addresses appear
        composeTestRule.onNodeWithText("Pixel 9 Pro").assertIsDisplayed()
        composeTestRule.onNodeWithText("AA:BB:CC:11:22:33").assertIsDisplayed()

        // 1-tap quick add for Pixel 9 Pro
        composeTestRule.onNodeWithTag("quick_add_device_AA:BB:CC:11:22:33").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("AA:BB:CC:11:22:33"), quickAdded)

        // Filter by MAC substring for Galaxy S24 Ultra & batch select
        composeTestRule.onNodeWithTag("scanner_search_input").performTextInput("DD:EE")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Galaxy S24 Ultra").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scanner_checkbox_DD:EE:FF:44:55:66").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("batch_save_devices_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("DD:EE:FF:44:55:66"), batchAdded)
    }
}
