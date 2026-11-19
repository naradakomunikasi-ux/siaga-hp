package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.SiagaApp
import com.example.bluetooth.BluetoothPermissionHelper
import com.example.bluetooth.CabinCheckSummary
import com.example.data.local.CheckStatus
import com.example.data.local.TriggerSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BackgroundServiceTelemetry(
    val isRunning: Boolean = false,
    val isScanningNow: Boolean = false,
    val isBluetoothAdapterReady: Boolean = false,
    val activePowerProfileLabel: String = "Seimbang",
    val lastScanTimestamp: Long? = null,
    val nextScanTimestamp: Long? = null,
    val lastDetectedCount: Int = 0,
    val lastMonitoredCount: Int = 0,
    val lastStatusText: String = "Menunggu aktivasi layanan"
)

class CarDepartureMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START_MONITOR"
        const val ACTION_STOP = "com.example.service.ACTION_STOP_MONITOR"
        const val ACTION_RUN_CHECK = "com.example.service.ACTION_RUN_CHECK"
        const val ACTION_REFRESH_SCHEDULE = "com.example.service.ACTION_REFRESH_SCHEDULE"
        const val EXTRA_TRIGGER_SOURCE = "extra_trigger_source"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _telemetry = MutableStateFlow(BackgroundServiceTelemetry())
        val telemetry: StateFlow<BackgroundServiceTelemetry> = _telemetry.asStateFlow()

        fun start(context: Context): Boolean {
            if (!BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
                return false
            }
            val intent = Intent(context, CarDepartureMonitorService::class.java).apply {
                action = ACTION_START
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun triggerImmediateScan(context: Context, triggerSource: TriggerSource): Boolean {
            if (!BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
                return false
            }
            val intent = Intent(context, CarDepartureMonitorService::class.java).apply {
                action = ACTION_RUN_CHECK
                putExtra(EXTRA_TRIGGER_SOURCE, triggerSource.name)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun notifyScheduleUpdated(context: Context) {
            if (!_isRunning.value) return
            val intent = Intent(context, CarDepartureMonitorService::class.java).apply {
                action = ACTION_REFRESH_SCHEDULE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Safe fallback if background start is restricted
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CarDepartureMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
            _isRunning.value = false
            _telemetry.update {
                it.copy(
                    isRunning = false,
                    isScanningNow = false,
                    nextScanTimestamp = null,
                    lastStatusText = "Layanan siaga dihentikan"
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicScanJob: Job? = null
    private var ignitionReceiverRegistered = false
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val dynamicTriggerReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            val app = application as? SiagaApp ?: return
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val ready = btState == BluetoothAdapter.STATE_ON
                    _telemetry.update {
                        it.copy(
                            isBluetoothAdapterReady = ready,
                            lastStatusText = if (ready) "Bluetooth aktif • Siap memindai" else "Bluetooth nonaktif"
                        )
                    }
                    updateForegroundNotification(
                        if (ready) "Bluetooth aktif • Memantau perangkat terdaftar"
                        else "Bluetooth nonaktif — aktifkan untuk pemindaian otomatis"
                    )
                    if (ready) {
                        restartPeriodicScanLoop()
                    }
                }

                Intent.ACTION_POWER_CONNECTED -> {
                    serviceScope.launch {
                        val settings = app.container.repository.getSettingsOnce()
                        if (settings.autoMonitorEnabled && settings.triggerOnPowerConnected) {
                            runPresenceScanAndUpdateNotification(TriggerSource.IGNITION_POWER)
                        }
                    }
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    ) ?: return
                    val connectedMac = device.address?.uppercase() ?: return

                    serviceScope.launch {
                        val settings = app.container.repository.getSettingsOnce()
                        if (!settings.autoMonitorEnabled || !settings.triggerOnCarBluetooth) return@launch

                        val targetCarMac = settings.carBluetoothAddress.uppercase()
                        val matchesSelectedCar = targetCarMac.isNotBlank() && connectedMac == targetCarMac
                        val matchesAnyCarAudio = targetCarMac.isBlank() && app.container.scannerEngine.isCarAudioDevice(device)

                        if (matchesSelectedCar || matchesAnyCarAudio) {
                            runPresenceScanAndUpdateNotification(TriggerSource.CAR_BLUETOOTH)
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter
        registerDynamicTriggerReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? SiagaApp
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!BluetoothPermissionHelper.hasBluetoothPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val adapterReady = bluetoothAdapter?.isEnabled == true
        val initialStatus = if (adapterReady) {
            "BluetoothAdapter aktif • Memantau smartphone terdaftar"
        } else {
            "Menunggu Bluetooth aktif untuk memindai ponsel"
        }

        val notification = app?.container?.notifier?.buildMonitorForegroundNotification(initialStatus)
        if (notification != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        DepartureAlertNotifier.NOTIF_ID_MONITOR,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(DepartureAlertNotifier.NOTIF_ID_MONITOR, notification)
                }
                _isRunning.value = true
                _telemetry.update {
                    it.copy(
                        isRunning = true,
                        isBluetoothAdapterReady = adapterReady,
                        lastStatusText = initialStatus
                    )
                }
            } catch (_: Exception) {
                _isRunning.value = false
                _telemetry.update { it.copy(isRunning = false) }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_RUN_CHECK -> {
                val triggerName = intent.getStringExtra(EXTRA_TRIGGER_SOURCE)
                val trigger = triggerName?.let { TriggerSource.fromName(it) } ?: TriggerSource.MANUAL_SCAN
                serviceScope.launch {
                    runPresenceScanAndUpdateNotification(trigger)
                }
            }

            ACTION_REFRESH_SCHEDULE -> {
                restartPeriodicScanLoop()
            }

            else -> {
                restartPeriodicScanLoop()
            }
        }

        return START_STICKY
    }

    private fun restartPeriodicScanLoop() {
        periodicScanJob?.cancel()
        periodicScanJob = serviceScope.launch {
            val app = application as? SiagaApp ?: return@launch
            while (isActive) {
                val settings = app.container.repository.getSettingsOnce()
                if (!settings.autoMonitorEnabled || !settings.periodicBackgroundScanEnabled) {
                    _telemetry.update {
                        it.copy(
                            nextScanTimestamp = null,
                            lastStatusText = "Siaga pemicu mesin (scan berkala nonaktif)"
                        )
                    }
                    updateForegroundNotification("Menunggu pemicu mesin mobil (Bluetooth / Daya ACC)")
                    return@launch
                }

                val intervalMs = settings.periodicScanIntervalSeconds * 1000L
                val profileLabel = settings.scanPowerProfile.label
                _telemetry.update { it.copy(activePowerProfileLabel = profileLabel) }
                val monitored = app.container.repository.getMonitoredPhonesOnce()
                val adapterEnabled = bluetoothAdapter?.isEnabled == true

                if (monitored.isNotEmpty() && adapterEnabled) {
                    runPresenceScanAndUpdateNotification(TriggerSource.BACKGROUND_PERIODIC)
                } else {
                    val statusMsg = when {
                        monitored.isEmpty() -> "Mode $profileLabel • Belum ada smartphone terdaftar"
                        else -> "Mode $profileLabel • Menunggu Bluetooth menyala"
                    }
                    _telemetry.update {
                        it.copy(
                            isBluetoothAdapterReady = adapterEnabled,
                            lastStatusText = statusMsg
                        )
                    }
                    updateForegroundNotification(statusMsg)
                }

                val nextTime = System.currentTimeMillis() + intervalMs
                _telemetry.update { it.copy(nextScanTimestamp = nextTime) }
                delay(intervalMs)
            }
        }
    }

    private suspend fun runPresenceScanAndUpdateNotification(triggerSource: TriggerSource) {
        val app = application as? SiagaApp ?: return
        val adapterReady = bluetoothAdapter?.isEnabled == true
        _telemetry.update {
            it.copy(
                isScanningNow = true,
                isBluetoothAdapterReady = adapterReady,
                lastStatusText = "Memindai Bluetooth (${triggerSource.label})…"
            )
        }
        updateForegroundNotification("Memindai sinyal Bluetooth (${triggerSource.label})…")

        val summary: CabinCheckSummary? = app.container.scannerEngine.performCabinDepartureCheck(triggerSource)
        val now = System.currentTimeMillis()

        val statusLine = when {
            summary == null -> "Belum ada smartphone terdaftar untuk dipantau"
            summary.status == CheckStatus.BT_UNAVAILABLE -> "Bluetooth tidak aktif saat pemeriksaan"
            summary.status == CheckStatus.ALL_PRESENT ->
                "Semua ponsel terdeteksi (${summary.detectedAddresses.size}/${summary.totalMonitored})"
            else -> {
                val missingNames = summary.missingPhones.joinToString(", ") { it.ownerLabel }
                "Tidak terdeteksi (${summary.missingPhones.size}): $missingNames"
            }
        }

        _telemetry.update {
            it.copy(
                isScanningNow = false,
                isBluetoothAdapterReady = bluetoothAdapter?.isEnabled == true,
                lastScanTimestamp = now,
                lastDetectedCount = summary?.detectedAddresses?.size ?: 0,
                lastMonitoredCount = summary?.totalMonitored ?: 0,
                lastStatusText = statusLine
            )
        }
        updateForegroundNotification(statusLine)
    }

    private fun updateForegroundNotification(statusText: String) {
        val app = application as? SiagaApp ?: return
        if (!BluetoothPermissionHelper.hasNotificationPermission(this)) return
        try {
            val updated = app.container.notifier.buildMonitorForegroundNotification(statusText)
            NotificationManagerCompat.from(this).notify(DepartureAlertNotifier.NOTIF_ID_MONITOR, updated)
        } catch (_: SecurityException) {
            // Notification permission revoked
        }
    }

    private fun registerDynamicTriggerReceiver() {
        if (ignitionReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        try {
            ContextCompat.registerReceiver(
                this,
                dynamicTriggerReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            ignitionReceiverRegistered = true
        } catch (_: Exception) {
            // Safe fallback
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        periodicScanJob?.cancel()
        _isRunning.value = false
        _telemetry.update {
            it.copy(
                isRunning = false,
                isScanningNow = false,
                nextScanTimestamp = null
            )
        }
        if (BluetoothPermissionHelper.hasBluetoothScanPermission(this)) {
            try {
                if (bluetoothAdapter?.isDiscovering == true) {
                    bluetoothAdapter?.cancelDiscovery()
                }
            } catch (_: Exception) {
                // Safe cleanup
            }
        }
        if (ignitionReceiverRegistered) {
            try {
                unregisterReceiver(dynamicTriggerReceiver)
            } catch (_: Exception) {
                // Already unregistered
            }
            ignitionReceiverRegistered = false
        }
        serviceScope.cancel()
    }
}
