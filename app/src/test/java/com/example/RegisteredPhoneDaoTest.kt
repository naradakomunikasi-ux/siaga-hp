package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PhoneCategory
import com.example.data.local.RegisteredPhoneDao
import com.example.data.local.RegisteredPhoneEntity
import com.example.data.local.SiagaDatabase
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.SiagaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegisteredPhoneDaoTest {

    private lateinit var database: SiagaDatabase
    private lateinit var phoneDao: RegisteredPhoneDao
    private lateinit var repository: SiagaRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SiagaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        phoneDao = database.registeredPhoneDao()
        repository = SiagaRepository(
            phoneDao = phoneDao,
            logDao = database.siagaDao(),
            settingsRepository = SettingsRepository(context)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `stores and observes multiple registered smartphone MAC addresses`() = runTest {
        val devices = listOf(
            RegisteredPhoneEntity(
                macAddress = "aa:bb:cc:11:22:33",
                deviceName = "Pixel 9",
                ownerLabel = "HP Utama",
                category = PhoneCategory.SMARTPHONE.name,
                isMonitored = true
            ),
            RegisteredPhoneEntity(
                macAddress = "dd:ee:ff:44:55:66",
                deviceName = "HUAWEI WATCH GT",
                ownerLabel = "Jam Tangan",
                category = PhoneCategory.SMARTWATCH.name,
                isMonitored = true
            ),
            RegisteredPhoneEntity(
                macAddress = "11:22:33:aa:bb:cc",
                deviceName = "iPad Air",
                ownerLabel = "Tablet Kerja",
                category = PhoneCategory.TABLET.name,
                isMonitored = false
            )
        )

        repository.registerMultiplePhones(devices)

        val allPhones = phoneDao.observeAllPhones().first()
        assertEquals(3, allPhones.size)
        assertEquals(3, phoneDao.getRegisteredCount())

        val monitoredMacs = phoneDao.observeMonitoredMacAddresses().first()
        assertEquals(2, monitoredMacs.size)
        assertTrue("AA:BB:CC:11:22:33" in monitoredMacs)
        assertTrue("DD:EE:FF:44:55:66" in monitoredMacs)
        assertFalse("11:22:33:AA:BB:CC" in monitoredMacs)
    }

    @Test
    fun `updating phone metadata preserves lastSeenTimestamp and lastRssi`() = runTest {
        repository.registerOrUpdatePhone(
            macAddress = "aa-bb-cc-99-88-77",
            deviceName = "Pixel 9",
            ownerLabel = "HP Utama",
            category = PhoneCategory.SMARTPHONE,
            rssiThreshold = -85,
            isMonitored = true
        )

        val seenTime = 1_750_000_000_000L
        repository.markPhoneSeen("AA:BB:CC:99:88:77", seenTime, -58)

        // Update owner label & threshold for the same MAC
        repository.registerOrUpdatePhone(
            macAddress = "aa:bb:cc:99:88:77",
            deviceName = "Pixel 9 Pro",
            ownerLabel = "HP Utama Baru",
            category = PhoneCategory.TABLET,
            rssiThreshold = -78,
            isMonitored = true
        )

        val updated = repository.getPhoneByMac("AA:BB:CC:99:88:77")
        assertNotNull(updated)
        assertEquals("HP Utama Baru", updated?.ownerLabel)
        assertEquals("Pixel 9 Pro", updated?.deviceName)
        assertEquals(-78, updated?.rssiThreshold)
        assertEquals(seenTime, updated?.lastSeenTimestamp)
        assertEquals(-58, updated?.lastRssi)
    }

    @Test
    fun `toggling monitoring and deleting a specific MAC address works cleanly`() = runTest {
        repository.registerOrUpdatePhone(
            macAddress = "10:20:30:40:50:60",
            deviceName = "Phone A",
            ownerLabel = "Ponsel A",
            category = PhoneCategory.SMARTPHONE
        )
        repository.registerOrUpdatePhone(
            macAddress = "70:80:90:A0:B0:C0",
            deviceName = "Phone B",
            ownerLabel = "Ponsel B",
            category = PhoneCategory.AUDIO
        )

        repository.togglePhoneMonitored("10:20:30:40:50:60", false)
        val monitoredAfterToggle = repository.getMonitoredPhonesOnce()
        assertEquals(1, monitoredAfterToggle.size)
        assertEquals("70:80:90:A0:B0:C0", monitoredAfterToggle.first().macAddress)

        repository.deletePhone("10:20:30:40:50:60")
        assertNull(repository.getPhoneByMac("10:20:30:40:50:60"))
        assertEquals(1, phoneDao.getRegisteredCount())
    }
}
