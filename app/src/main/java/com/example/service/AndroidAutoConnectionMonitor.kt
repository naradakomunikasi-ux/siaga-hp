package com.example.service

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AndroidAutoConnectionType(val label: String) {
    NOT_CONNECTED("Tidak Terhubung"),
    ANDROID_AUTO("Android Auto Terhubung"),
    AUTOMOTIVE_OS("Android Automotive OS");

    val isConnected: Boolean
        get() = this == ANDROID_AUTO || this == AUTOMOTIVE_OS
}

/**
 * Observes Android Auto projection and Car Mode connection status using the official
 * `androidx.car.app.connection` ContentProvider + broadcast contract together with
 * `UiModeManager` (`ACTION_ENTER_CAR_MODE` / `UI_MODE_TYPE_CAR`) fallback.
 *
 * When a transition into a connected Android Auto session is detected, invokes [onAutoConnected]
 * (debounced to avoid duplicate scans during USB handshakes).
 */
class AndroidAutoConnectionMonitor(
    private val context: Context,
    private val onAutoConnected: (() -> Unit)? = null
) {
    companion object {
        const val ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"
        const val CAR_CONNECTION_STATE_COLUMN = "CarConnectionState"

        const val CONNECTION_TYPE_NOT_CONNECTED = 0
        const val CONNECTION_TYPE_NATIVE = 1
        const val CONNECTION_TYPE_PROJECTION = 2

        private const val TRIGGER_DEBOUNCE_MS = 12_000L

        fun mapCarConnectionCode(code: Int): AndroidAutoConnectionType = when (code) {
            CONNECTION_TYPE_PROJECTION -> AndroidAutoConnectionType.ANDROID_AUTO
            CONNECTION_TYPE_NATIVE -> AndroidAutoConnectionType.AUTOMOTIVE_OS
            else -> AndroidAutoConnectionType.NOT_CONNECTED
        }

        fun isCarConnectionProviderAvailable(context: Context): Boolean {
            return try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.resolveContentProvider(
                        CAR_CONNECTION_AUTHORITY,
                        android.content.pm.PackageManager.ComponentInfoFlags.of(0)
                    ) != null
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.resolveContentProvider(CAR_CONNECTION_AUTHORITY, 0) != null
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableStateFlow(AndroidAutoConnectionType.NOT_CONNECTED)
    val connectionState: StateFlow<AndroidAutoConnectionType> = _connectionState.asStateFlow()

    private var receiverRegistered = false
    private var lastTriggerTimestamp = 0L

    private val carModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CAR_CONNECTION_UPDATED -> refreshConnectionState(allowTrigger = true)
                UiModeManager.ACTION_ENTER_CAR_MODE -> {
                    updateState(AndroidAutoConnectionType.ANDROID_AUTO, allowTrigger = true)
                }
                UiModeManager.ACTION_EXIT_CAR_MODE -> {
                    refreshConnectionState(allowTrigger = false)
                }
            }
        }
    }

    fun startMonitoring() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_CAR_CONNECTION_UPDATED)
                addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            }
            try {
                ContextCompat.registerReceiver(
                    context,
                    carModeReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                receiverRegistered = true
            } catch (_: Exception) {
                // Safe fallback
            }
        }
        refreshConnectionState(allowTrigger = false)
    }

    fun stopMonitoring() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(carModeReceiver)
            } catch (_: Exception) {
                // Already unregistered
            }
            receiverRegistered = false
        }
    }

    fun refreshConnectionState(allowTrigger: Boolean = false) {
        scope.launch {
            val detected = queryCurrentConnectionType()
            updateState(detected, allowTrigger = allowTrigger)
        }
    }

    fun syncExternalState(state: AndroidAutoConnectionType) {
        _connectionState.value = state
    }

    private fun queryCurrentConnectionType(): AndroidAutoConnectionType {
        // 1. Query official Android Auto CarConnection ContentProvider only if installed
        if (isCarConnectionProviderAvailable(context)) {
            val providerUri = Uri.Builder()
                .scheme("content")
                .authority(CAR_CONNECTION_AUTHORITY)
                .build()
            try {
                context.contentResolver.query(
                    providerUri,
                    arrayOf(CAR_CONNECTION_STATE_COLUMN),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val colIndex = cursor.getColumnIndex(CAR_CONNECTION_STATE_COLUMN)
                    if (colIndex >= 0 && cursor.moveToFirst()) {
                        val code = cursor.getInt(colIndex)
                        val mapped = mapCarConnectionCode(code)
                        if (mapped.isConnected) return mapped
                    }
                }
            } catch (_: Exception) {
                // Provider unavailable or access denied
            }
        }

        // 2. Fallback to Android UiModeManager car mode state
        return try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR) {
                AndroidAutoConnectionType.ANDROID_AUTO
            } else {
                AndroidAutoConnectionType.NOT_CONNECTED
            }
        } catch (_: Exception) {
            AndroidAutoConnectionType.NOT_CONNECTED
        }
    }

    private fun updateState(newState: AndroidAutoConnectionType, allowTrigger: Boolean) {
        val previous = _connectionState.value
        _connectionState.value = newState

        if (allowTrigger && !previous.isConnected && newState.isConnected) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTimestamp >= TRIGGER_DEBOUNCE_MS) {
                lastTriggerTimestamp = now
                onAutoConnected?.invoke()
            }
        }
    }
}
