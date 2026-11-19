package com.example

import android.app.Application
import com.example.bluetooth.BluetoothScannerEngine
import com.example.data.local.SiagaDatabase
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.SiagaRepository
import com.example.service.DepartureAlertNotifier

class AppContainer(application: Application) {
    val database: SiagaDatabase = SiagaDatabase.getInstance(application)
    val settingsRepository: SettingsRepository = SettingsRepository(application)
    val repository: SiagaRepository = SiagaRepository(
        phoneDao = database.registeredPhoneDao(),
        logDao = database.siagaDao(),
        settingsRepository = settingsRepository
    )
    val notifier: DepartureAlertNotifier = DepartureAlertNotifier(application)
    val scannerEngine: BluetoothScannerEngine = BluetoothScannerEngine(
        context = application,
        repository = repository,
        notifier = notifier
    )
}

class SiagaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
