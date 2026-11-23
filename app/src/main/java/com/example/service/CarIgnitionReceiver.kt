package com.example.service

import android.app.UiModeManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.example.SiagaApp
import com.example.data.local.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CarIgnitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SiagaApp ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    DepartureAlertNotifier.ACTION_DISMISS_ALERT -> {
                        app.container.notifier.dismissAlertNotification()
                    }

                    DepartureAlertNotifier.ACTION_TRIGGER_DEPARTURE_CHECK -> {
                        app.container.notifier.dismissAlertNotification()
                        val startedService = CarDepartureMonitorService.triggerImmediateScan(
                            context = context,
                            triggerSource = TriggerSource.MANUAL_SCAN
                        )
                        if (!startedService) {
                            app.container.scannerEngine.startCabinDepartureCheck(TriggerSource.MANUAL_SCAN)
                        }
                    }

                    Intent.ACTION_POWER_CONNECTED -> {
                        val settings = app.container.repository.getSettingsOnce()
                        if (settings.autoMonitorEnabled && settings.triggerOnPowerConnected) {
                            val startedService = CarDepartureMonitorService.triggerImmediateScan(
                                context = context,
                                triggerSource = TriggerSource.IGNITION_POWER
                            )
                            if (!startedService) {
                                app.container.scannerEngine.startCabinDepartureCheck(TriggerSource.IGNITION_POWER)
                            }
                        }
                    }

                    UiModeManager.ACTION_ENTER_CAR_MODE -> {
                        app.container.androidAutoMonitor.syncExternalState(AndroidAutoConnectionType.ANDROID_AUTO)
                        val settings = app.container.repository.getSettingsOnce()
                        if (settings.autoMonitorEnabled && settings.triggerOnAndroidAuto) {
                            val startedAutoService = AndroidAutoConnectionService.notifyCarModeEntered(context)
                            if (!startedAutoService) {
                                app.container.scannerEngine.startCabinDepartureCheck(TriggerSource.ANDROID_AUTO)
                            }
                        }
                    }

                    UiModeManager.ACTION_EXIT_CAR_MODE -> {
                        app.container.androidAutoMonitor.refreshConnectionState(allowTrigger = false)
                        AndroidAutoConnectionService.notifyCarModeExited(context)
                    }

                    AndroidAutoConnectionMonitor.ACTION_CAR_CONNECTION_UPDATED -> {
                        app.container.androidAutoMonitor.refreshConnectionState(allowTrigger = true)
                        val settings = app.container.repository.getSettingsOnce()
                        if (settings.autoMonitorEnabled && settings.triggerOnAndroidAuto) {
                            AndroidAutoConnectionService.start(context)
                        }
                    }

                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = IntentCompat.getParcelableExtra(
                            intent,
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                        val connectedMac = device?.address?.uppercase() ?: return@launch
                        val settings = app.container.repository.getSettingsOnce()
                        if (!settings.autoMonitorEnabled || !settings.triggerOnCarBluetooth) return@launch

                        val targetCarMac = settings.carBluetoothAddress.uppercase()
                        val monitoredMacs = app.container.repository.getMonitoredPhonesOnce()
                            .map { it.macAddress.uppercase() }
                            .toSet()
                        val matchesCar = (targetCarMac.isNotBlank() && connectedMac == targetCarMac) ||
                            (targetCarMac.isBlank() && connectedMac !in monitoredMacs && app.container.scannerEngine.isCarAudioDevice(device))

                        if (matchesCar) {
                            val startedService = CarDepartureMonitorService.triggerImmediateScan(
                                context = context,
                                triggerSource = TriggerSource.CAR_BLUETOOTH
                            )
                            if (!startedService) {
                                app.container.scannerEngine.startCabinDepartureCheck(TriggerSource.CAR_BLUETOOTH)
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
