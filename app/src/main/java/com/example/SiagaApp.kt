package com.example

import android.app.Application
import com.example.bluetooth.BluetoothScannerEngine
import com.example.data.local.SiagaDatabase
import com.example.data.local.TriggerSource
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.SiagaRepository
import com.example.service.AndroidAutoConnectionMonitor
import com.example.service.AndroidAutoConnectionService
import com.example.service.CarDepartureMonitorService
import com.example.service.DepartureAlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    val androidAutoMonitor: AndroidAutoConnectionMonitor = AndroidAutoConnectionMonitor(
        context = application,
        onAutoConnected = {
            appScope.launch {
                val settings = repository.getSettingsOnce()
                if (settings.autoMonitorEnabled && settings.triggerOnAndroidAuto) {
                    val startedAutoService = AndroidAutoConnectionService.notifyCarModeEntered(application)
                    if (!startedAutoService) {
                        val startedMonitor = CarDepartureMonitorService.triggerImmediateScan(
                            context = application,
                            triggerSource = TriggerSource.ANDROID_AUTO
                        )
                        if (!startedMonitor) {
                            scannerEngine.startCabinDepartureCheck(TriggerSource.ANDROID_AUTO)
                        }
                    }
                }
            }
        }
    )
}

class SiagaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.androidAutoMonitor.startMonitoring()
    }
}
