package com.example

import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.AppSettings
import com.example.data.prefs.ScanPowerProfile
import com.example.data.prefs.SettingsRepository
import com.example.ui.SiagaUiState
import com.example.ui.screens.RulesAndLogsScreen
import com.example.ui.theme.SiagaTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScanPowerProfileTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `power profiles map to expected BLE ScanSettings modes and intervals`() {
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, ScanPowerProfile.RESPONSIVE.bleScanMode)
        assertEquals(30, ScanPowerProfile.RESPONSIVE.defaultIntervalSeconds)
        assertTrue(ScanPowerProfile.RESPONSIVE.useClassicInBackground)

        assertEquals(ScanSettings.SCAN_MODE_BALANCED, ScanPowerProfile.BALANCED.bleScanMode)
        assertEquals(60, ScanPowerProfile.BALANCED.defaultIntervalSeconds)
        assertTrue(ScanPowerProfile.BALANCED.useClassicInBackground)

        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, ScanPowerProfile.POWER_SAVING.bleScanMode)
        assertEquals(180, ScanPowerProfile.POWER_SAVING.defaultIntervalSeconds)
        assertFalse(ScanPowerProfile.POWER_SAVING.useClassicInBackground)
    }

    @Test
    fun `settings repository persists power profile and syncs default scan interval`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)

        repo.setScanPowerProfile(ScanPowerProfile.POWER_SAVING)
        val savedPowerSaving = repo.getSettingsOnce()
        assertEquals(ScanPowerProfile.POWER_SAVING, savedPowerSaving.scanPowerProfile)
        assertEquals(180, savedPowerSaving.periodicScanIntervalSeconds)

        repo.setScanPowerProfile(ScanPowerProfile.BALANCED)
        val savedBalanced = repo.getSettingsOnce()
        assertEquals(ScanPowerProfile.BALANCED, savedBalanced.scanPowerProfile)
        assertEquals(60, savedBalanced.periodicScanIntervalSeconds)
    }

    @Test
    fun `rules screen allows switching between balanced and power saving profiles and custom intervals`() {
        var selectedProfile: ScanPowerProfile? = null
        var selectedInterval: Int? = null

        val state = SiagaUiState(
            settings = AppSettings(
                autoMonitorEnabled = true,
                periodicBackgroundScanEnabled = true,
                scanPowerProfile = ScanPowerProfile.BALANCED,
                periodicScanIntervalSeconds = 60
            )
        )

        composeTestRule.setContent {
            SiagaTheme(darkTheme = true) {
                RulesAndLogsScreen(
                    uiState = state,
                    onToggleAutoMonitor = {},
                    onTogglePeriodicScan = {},
                    onSelectScanPowerProfile = { selectedProfile = it },
                    onSelectPeriodicInterval = { selectedInterval = it },
                    onToggleCarBtTrigger = {},
                    onTogglePowerTrigger = {},
                    onScanDurationChange = {},
                    onToggleVoiceAlert = {},
                    onToggleVibrateAlert = {},
                    onToggleSafeNotif = {},
                    onSelectThemeMode = {},
                    onTestAlert = {},
                    onClearLogs = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("power_profile_balanced").assertIsDisplayed()
        composeTestRule.onNodeWithTag("power_profile_power_saving").assertIsDisplayed()
        composeTestRule.onNodeWithTag("power_profile_power_saving").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ScanPowerProfile.POWER_SAVING, selectedProfile)

        composeTestRule.onNodeWithTag("interval_chip_180")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(180, selectedInterval)
    }
}
