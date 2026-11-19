package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.bluetooth.BluetoothEngineState
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.prefs.AppSettings
import com.example.service.BackgroundServiceTelemetry
import com.example.ui.MonitoredPhoneUiItem
import com.example.ui.PhonePresenceStatus
import com.example.ui.SiagaUiState
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.SiagaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DashboardUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `dashboard displays registered smartphone connection status and background service state`() {
        var serviceToggled = false
        var scanClicked = false

        val sampleState = SiagaUiState(
            phones = listOf(
                MonitoredPhoneUiItem(
                    entity = RegisteredPhoneEntity(
                        macAddress = "AA:BB:CC:11:22:33",
                        deviceName = "Pixel 9 Pro",
                        ownerLabel = "Ponsel Pribadi",
                        isMonitored = true
                    ),
                    liveStatus = PhonePresenceStatus.DETECTED,
                    liveRssi = -56,
                    isCurrentlyConnected = true
                ),
                MonitoredPhoneUiItem(
                    entity = RegisteredPhoneEntity(
                        macAddress = "DD:EE:FF:44:55:66",
                        deviceName = "Galaxy S24",
                        ownerLabel = "Ponsel Kantor",
                        isMonitored = true
                    ),
                    liveStatus = PhonePresenceStatus.MISSING,
                    liveRssi = null,
                    isCurrentlyConnected = false
                )
            ),
            settings = AppSettings(
                autoMonitorEnabled = true,
                periodicBackgroundScanEnabled = true,
                periodicScanIntervalSeconds = 60
            ),
            btState = BluetoothEngineState(
                isHardwareSupported = true,
                isBluetoothEnabled = true
            ),
            serviceTelemetry = BackgroundServiceTelemetry(
                isRunning = true,
                isScanningNow = false,
                isBluetoothAdapterReady = true,
                lastStatusText = "Semua ponsel diperiksa"
            ),
            isMonitorServiceRunning = true,
            hasBluetoothPermissions = true,
            hasNotificationPermission = true
        )

        composeTestRule.setContent {
            SiagaTheme(darkTheme = true) {
                DashboardScreen(
                    uiState = sampleState,
                    onRequestPermissions = {},
                    onRequestEnableBluetooth = {},
                    onScanCabinNow = { scanClicked = true },
                    onRunIgnitionCheck = {},
                    onStopScan = {},
                    onToggleBackgroundService = { serviceToggled = true },
                    onTogglePhoneMonitored = { _, _ -> },
                    onNavigateToDevices = {},
                    onNavigateToRules = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Verify primary scan CTA in Radar Cockpit Card
        composeTestRule.onNodeWithTag("dashboard_list")
            .performScrollToNode(hasTestTag("radar_cockpit_card"))
        composeTestRule.onNodeWithTag("scan_cabin_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(scanClicked)

        // Scroll to Bluetooth Background Service panel & verify status + direct toggle
        composeTestRule.onNodeWithTag("dashboard_list")
            .performScrollToNode(hasTestTag("background_service_telemetry_card"))
        composeTestRule.onNodeWithTag("background_service_telemetry_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Aktif Berjalan").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dashboard_service_toggle").performClick()
        composeTestRule.waitForIdle()
        assertTrue(serviceToggled)

        // Scroll to Registered Smartphones connection status cards & verify badges
        composeTestRule.onNodeWithTag("dashboard_list")
            .performScrollToNode(hasTestTag("phone_telemetry_card_AA:BB:CC:11:22:33"))
        composeTestRule.onNodeWithText("Ponsel Pribadi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Terhubung Bluetooth").assertIsDisplayed()

        composeTestRule.onNodeWithTag("dashboard_list")
            .performScrollToNode(hasTestTag("phone_telemetry_card_DD:EE:FF:44:55:66"))
        composeTestRule.onNodeWithText("Ponsel Kantor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Terputus • Tertinggal!").assertIsDisplayed()
    }
}
