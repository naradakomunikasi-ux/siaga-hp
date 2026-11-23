package com.example.service

import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.launch

data class AndroidAutoServiceState(
    val isServiceRunning: Boolean = false,
    val connectionType: AndroidAutoConnectionType = AndroidAutoConnectionType.NOT_CONNECTED,
    val isCarModeActive: Boolean = false,
    val isAutoScanInProgress: Boolean = false,
    val autoScanTriggerCount: Int = 0,
    val lastConnectedAt: Long? = null,
    val lastAutoScanTimestamp: Long? = null,
    val lastAutoScanSummaryText: String = "Menunggu koneksi Android Auto"
) {
    val isConnected: Boolean
        get() = connectionType.isConnected || isCarModeActive
}

/**
 * Dedicated Android foreground Service that monitors Android Auto projection and Car Mode
 * (`UiModeManager` + `androidx.car.app.connection` ContentProvider/broadcasts).
 *
 * When a connection to Android Auto / Car Mode is detected, automatically executes the
 * departure scan script to verify all registered Bluetooth devices are present.
 */
class AndroidAutoConnectionService : Service() {

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START_ANDROID_AUTO_SERVICE"
        const val ACTION_STOP = "com.example.service.ACTION_STOP_ANDROID_AUTO_SERVICE"
        const val ACTION_CAR_MODE_ENTERED = "com.example.service.ACTION_CAR_MODE_ENTERED"
        const val ACTION_CAR_MODE_EXITED = "com.example.service.ACTION_CAR_MODE_EXITED"
        const val ACTION_RUN_AUTO_SCAN = "com.example.service.ACTION_RUN_ANDROID_AUTO_SCAN"

        const val AUTO_SCAN_DEBOUNCE_MS = 15_000L
        private const val HANDSHAKE_STABILIZATION_DELAY_MS = 800L

        private val _serviceState = MutableStateFlow(AndroidAutoServiceState())
        val serviceState: StateFlow<AndroidAutoServiceState> = _serviceState.asStateFlow()

        /**
         * Pure decision rule for automatic scan triggering on connection transitions.
         * Fires only on a fresh disconnected -> connected transition outside the debounce window.
         */
        fun shouldTriggerAutoScan(
            previousConnected: Boolean,
            newConnected: Boolean,
            nowMs: Long,
            lastTriggerMs: Long,
            debounceMs: Long = AUTO_SCAN_DEBOUNCE_MS
        ): Boolean {
            if (!newConnected || previousConnected) return false
            return (nowMs - lastTriggerMs) >= debounceMs
        }

        fun start(context: Context): Boolean {
            if (!BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
                return false
            }
            val intent = Intent(context, AndroidAutoConnectionService::class.java).apply {
                action = ACTION_START
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun notifyCarModeEntered(context: Context): Boolean {
            if (!BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
                return false
            }
            val intent = Intent(context, AndroidAutoConnectionService::class.java).apply {
                action = ACTION_CAR_MODE_ENTERED
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun notifyCarModeExited(context: Context) {
            if (!_serviceState.value.isServiceRunning) {
                _serviceState.update {
                    it.copy(
                        connectionType = AndroidAutoConnectionType.NOT_CONNECTED,
                        isCarModeActive = false,
                        lastAutoScanSummaryText = "Koneksi Android Auto terputus"
                    )
                }
                return
            }
            val intent = Intent(context, AndroidAutoConnectionService::class.java).apply {
                action = ACTION_CAR_MODE_EXITED
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Safe fallback
            }
        }

        fun triggerAutoScanCheck(context: Context): Boolean {
            if (!BluetoothPermissionHelper.hasBluetoothPermissions(context)) {
                return false
            }
            val intent = Intent(context, AndroidAutoConnectionService::class.java).apply {
                action = ACTION_RUN_AUTO_SCAN
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AndroidAutoConnectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
            _serviceState.update {
                it.copy(
                    isServiceRunning = false,
                    isAutoScanInProgress = false,
                    lastAutoScanSummaryText = "Service Android Auto nonaktif"
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeScanJob: Job? = null
    private var receiverRegistered = false
    private var contentObserverRegistered = false
    private var lastAutoTriggerTimestamp = 0L

    private val carConnectionUri: Uri = Uri.Builder()
        .scheme("content")
        .authority(AndroidAutoConnectionMonitor.CAR_CONNECTION_AUTHORITY)
        .build()

    private val carConnectionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            evaluateConnectionState(allowAutoScanTrigger = true)
        }
    }

    private val carModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                AndroidAutoConnectionMonitor.ACTION_CAR_CONNECTION_UPDATED -> {
                    evaluateConnectionState(allowAutoScanTrigger = true)
                }

                UiModeManager.ACTION_ENTER_CAR_MODE -> {
                    handleConnectionTransition(
                        detectedType = AndroidAutoConnectionType.ANDROID_AUTO,
                        carModeActive = true,
                        allowAutoScanTrigger = true
                    )
                }

                UiModeManager.ACTION_EXIT_CAR_MODE -> {
                    evaluateConnectionState(allowAutoScanTrigger = false)
                }

                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    evaluateConnectionState(allowAutoScanTrigger = true)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerCarModeReceivers()
        registerCarConnectionObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!BluetoothPermissionHelper.hasBluetoothPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val initialConnected = queryCurrentConnectionType().isConnected || isSystemInCarMode()
        val initialStatus = if (initialConnected) {
            "Android Auto terhubung • Siap memindai perangkat terdaftar"
        } else {
            "Service Android Auto aktif • Menunggu Car Mode terhubung"
        }

        if (!promoteToForeground(initialStatus, initialConnected)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_CAR_MODE_ENTERED -> {
                handleConnectionTransition(
                    detectedType = AndroidAutoConnectionType.ANDROID_AUTO,
                    carModeActive = true,
                    allowAutoScanTrigger = true
                )
            }

            ACTION_CAR_MODE_EXITED -> {
                evaluateConnectionState(allowAutoScanTrigger = false)
            }

            ACTION_RUN_AUTO_SCAN -> {
                launchAutoScanScript(bypassSettingCheck = true, stabilizeDelayMs = 0L)
            }

            else -> {
                // On initial start, inspect current state; if already in Car Mode, run auto scan once
                evaluateConnectionState(allowAutoScanTrigger = true)
            }
        }

        return START_STICKY
    }

    private fun promoteToForeground(statusText: String, isConnected: Boolean): Boolean {
        val app = application as? SiagaApp ?: return false
        val notification = app.container.notifier.buildAndroidAutoForegroundNotification(
            statusText = statusText,
            isConnected = isConnected
        )
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    DepartureAlertNotifier.NOTIF_ID_ANDROID_AUTO,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(DepartureAlertNotifier.NOTIF_ID_ANDROID_AUTO, notification)
            }
            _serviceState.update {
                it.copy(
                    isServiceRunning = true,
                    lastAutoScanSummaryText = statusText
                )
            }
            true
        } catch (_: Exception) {
            _serviceState.update { it.copy(isServiceRunning = false) }
            false
        }
    }

    private fun evaluateConnectionState(allowAutoScanTrigger: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            val carModeActive = isSystemInCarMode()
            val providerType = queryCurrentConnectionType()
            val resolvedType = when {
                providerType.isConnected -> providerType
                carModeActive -> AndroidAutoConnectionType.ANDROID_AUTO
                else -> AndroidAutoConnectionType.NOT_CONNECTED
            }
            handleConnectionTransition(
                detectedType = resolvedType,
                carModeActive = carModeActive,
                allowAutoScanTrigger = allowAutoScanTrigger
            )
        }
    }

    private fun handleConnectionTransition(
        detectedType: AndroidAutoConnectionType,
        carModeActive: Boolean,
        allowAutoScanTrigger: Boolean
    ) {
        val app = application as? SiagaApp
        app?.container?.androidAutoMonitor?.syncExternalState(detectedType)

        val previousConnected = _serviceState.value.isConnected
        val nowConnected = detectedType.isConnected || carModeActive
        val now = System.currentTimeMillis()

        val statusMsg = when {
            nowConnected && !previousConnected ->
                "${detectedType.label} • Menjalankan scan perangkat terdaftar…"
            nowConnected ->
                _serviceState.value.lastAutoScanSummaryText
            else ->
                "Service Android Auto aktif • Menunggu koneksi mobil"
        }

        _serviceState.update { current ->
            current.copy(
                connectionType = detectedType,
                isCarModeActive = carModeActive,
                lastConnectedAt = if (nowConnected && !previousConnected) now else current.lastConnectedAt,
                lastAutoScanSummaryText = statusMsg
            )
        }
        updateForegroundNotification(statusMsg, nowConnected)

        if (allowAutoScanTrigger && shouldTriggerAutoScan(
                previousConnected = previousConnected,
                newConnected = nowConnected,
                nowMs = now,
                lastTriggerMs = lastAutoTriggerTimestamp
            )
        ) {
            lastAutoTriggerTimestamp = now
            launchAutoScanScript(
                bypassSettingCheck = false,
                stabilizeDelayMs = HANDSHAKE_STABILIZATION_DELAY_MS
            )
        }
    }

    /**
     * Automated departure check script invoked whenever Android Auto / Car Mode connects.
     * Verifies settings, prepares Bluetooth & iPhone keep-alive, scans all registered monitored
     * devices, records the check log, and notifies if any device is missing.
     */
    private fun launchAutoScanScript(
        bypassSettingCheck: Boolean,
        stabilizeDelayMs: Long
    ) {
        if (activeScanJob?.isActive == true) return
        activeScanJob = serviceScope.launch {
            val app = application as? SiagaApp ?: return@launch
            val settings = app.container.repository.getSettingsOnce()

            if (!bypassSettingCheck && (!settings.autoMonitorEnabled || !settings.triggerOnAndroidAuto)) {
                val pausedMsg = "Android Auto terhubung • Pemicu scan otomatis dinonaktifkan"
                _serviceState.update { it.copy(lastAutoScanSummaryText = pausedMsg) }
                updateForegroundNotification(pausedMsg, isConnected = true)
                return@launch
            }

            val monitoredDevices = app.container.repository.getMonitoredPhonesOnce()
            if (monitoredDevices.isEmpty()) {
                val emptyMsg = "Android Auto terhubung • Belum ada perangkat terdaftar untuk dipindai"
                _serviceState.update {
                    it.copy(
                        isAutoScanInProgress = false,
                        lastAutoScanTimestamp = System.currentTimeMillis(),
                        lastAutoScanSummaryText = emptyMsg
                    )
                }
                updateForegroundNotification(emptyMsg, isConnected = _serviceState.value.isConnected)
                return@launch
            }

            val scanningMsg = "Android Auto aktif • Memindai ${monitoredDevices.size} perangkat terdaftar…"
            _serviceState.update {
                it.copy(
                    isAutoScanInProgress = true,
                    autoScanTriggerCount = it.autoScanTriggerCount + 1,
                    lastAutoScanSummaryText = scanningMsg
                )
            }
            updateForegroundNotification(scanningMsg, isConnected = true)

            // Let initial USB/Wireless Android Auto Bluetooth audio handshake settle before BLE/GATT probe
            if (stabilizeDelayMs > 0L) {
                delay(stabilizeDelayMs)
            }

            app.container.scannerEngine.refreshHardwareAndPairedState()
            app.container.scannerEngine.syncIphoneKeepAlive(enabled = true)

            val summary: CabinCheckSummary? =
                app.container.scannerEngine.performCabinDepartureCheck(TriggerSource.ANDROID_AUTO)

            val completedAt = System.currentTimeMillis()
            val resultText = when {
                summary == null -> "Belum ada perangkat terdaftar untuk diperiksa"
                summary.status == CheckStatus.BT_UNAVAILABLE ->
                    "Android Auto • Bluetooth nonaktif saat pemindaian"
                summary.status == CheckStatus.ALL_PRESENT ->
                    "Android Auto • Semua perangkat terbawa (${summary.detectedAddresses.size}/${summary.totalMonitored})"
                else -> {
                    val missingList = summary.missingPhones.joinToString(", ") { it.ownerLabel }
                    "Android Auto • ${summary.missingPhones.size} tertinggal: $missingList"
                }
            }

            _serviceState.update {
                it.copy(
                    isAutoScanInProgress = false,
                    lastAutoScanTimestamp = completedAt,
                    lastAutoScanSummaryText = resultText
                )
            }
            updateForegroundNotification(resultText, isConnected = _serviceState.value.isConnected)
        }
    }

    private fun queryCurrentConnectionType(): AndroidAutoConnectionType {
        if (!AndroidAutoConnectionMonitor.isCarConnectionProviderAvailable(this)) {
            return AndroidAutoConnectionType.NOT_CONNECTED
        }
        try {
            contentResolver.query(
                carConnectionUri,
                arrayOf(AndroidAutoConnectionMonitor.CAR_CONNECTION_STATE_COLUMN),
                null,
                null,
                null
            )?.use { cursor ->
                val colIndex = cursor.getColumnIndex(AndroidAutoConnectionMonitor.CAR_CONNECTION_STATE_COLUMN)
                if (colIndex >= 0 && cursor.moveToFirst()) {
                    val code = cursor.getInt(colIndex)
                    val mapped = AndroidAutoConnectionMonitor.mapCarConnectionCode(code)
                    if (mapped.isConnected) return mapped
                }
            }
        } catch (_: Exception) {
            // Provider unavailable
        }
        return AndroidAutoConnectionType.NOT_CONNECTED
    }

    private fun isSystemInCarMode(): Boolean {
        val configCarMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR
        if (configCarMode) return true
        return try {
            val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR
        } catch (_: Exception) {
            false
        }
    }

    private fun updateForegroundNotification(statusText: String, isConnected: Boolean) {
        val app = application as? SiagaApp ?: return
        if (!BluetoothPermissionHelper.hasNotificationPermission(this)) return
        try {
            val notification = app.container.notifier.buildAndroidAutoForegroundNotification(
                statusText = statusText,
                isConnected = isConnected
            )
            NotificationManagerCompat.from(this).notify(
                DepartureAlertNotifier.NOTIF_ID_ANDROID_AUTO,
                notification
            )
        } catch (_: SecurityException) {
            // Notification permission revoked
        }
    }

    private fun registerCarModeReceivers() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(AndroidAutoConnectionMonitor.ACTION_CAR_CONNECTION_UPDATED)
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(
                this,
                carModeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (_: Exception) {
            // Safe fallback
        }
    }

    private fun registerCarConnectionObserver() {
        if (contentObserverRegistered) return
        if (!AndroidAutoConnectionMonitor.isCarConnectionProviderAvailable(this)) return
        try {
            contentResolver.registerContentObserver(carConnectionUri, true, carConnectionObserver)
            contentObserverRegistered = true
        } catch (_: Exception) {
            // Provider may not exist on devices without Android Auto installed
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeScanJob?.cancel()
        if (receiverRegistered) {
            try {
                unregisterReceiver(carModeReceiver)
            } catch (_: Exception) {
                // Already unregistered
            }
            receiverRegistered = false
        }
        if (contentObserverRegistered) {
            try {
                contentResolver.unregisterContentObserver(carConnectionObserver)
            } catch (_: Exception) {
                // Already unregistered
            }
            contentObserverRegistered = false
        }
        _serviceState.update {
            it.copy(
                isServiceRunning = false,
                isAutoScanInProgress = false
            )
        }
        serviceScope.cancel()
    }
}
