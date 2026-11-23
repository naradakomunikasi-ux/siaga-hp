package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.bluetooth.BluetoothPresenceEvaluator
import com.example.data.local.PhoneCategory
import com.example.data.local.SiagaDatabase
import com.example.data.local.TriggerSource
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.SiagaRepository
import com.example.service.AndroidAutoConnectionMonitor
import com.example.service.AndroidAutoConnectionType
import com.example.ui.components.estimateDistanceMeters
import com.example.ui.components.formatDistanceMeters
import com.example.ui.components.formatRssiQuality
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CentralAndAndroidAutoTest {

    private lateinit var database: SiagaDatabase
    private lateinit var repository: SiagaRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SiagaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SiagaRepository(
            phoneDao = database.registeredPhoneDao(),
            logDao = database.siagaDao(),
            settingsRepository = SettingsRepository(context)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

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
        // Legacy Room DB values remain compatible
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
    fun `promoting a device to Central removes it from monitored carried devices`() = runTest {
        val headUnitMac = "10:4E:89:FD:12:66"
        val watchMac = "48:05:E2:15:7A:A8"

        repository.registerOrUpdatePhone(
            macAddress = headUnitMac,
            deviceName = "Red Cross",
            ownerLabel = "Red Cross",
            category = PhoneCategory.CAR_HEADUNIT
        )
        repository.registerOrUpdatePhone(
            macAddress = watchMac,
            deviceName = "HUAWEI WATCH",
            ownerLabel = "kipi watch",
            category = PhoneCategory.SMARTWATCH
        )

        repository.promoteToCentralDevice(headUnitMac, "Red Cross")

        val settings = repository.getSettingsOnce()
        assertEquals(headUnitMac, settings.carBluetoothAddress)
        assertEquals("Red Cross", settings.carBluetoothName)
        assertNull(repository.getPhoneByMac(headUnitMac))
        assertEquals(1, repository.getMonitoredPhonesOnce().size)
        assertEquals(watchMac, repository.getMonitoredPhonesOnce().first().macAddress)
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
}
