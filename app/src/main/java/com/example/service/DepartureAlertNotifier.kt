package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.bluetooth.BluetoothPermissionHelper
import com.example.data.local.TriggerSource
import java.util.Locale

class DepartureAlertNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_MONITOR = "siagahp_monitor_channel"
        const val CHANNEL_MISSING_ALERT = "siagahp_missing_alert_channel"
        const val CHANNEL_ALL_SAFE = "siagahp_safe_channel"

        const val NOTIF_ID_MONITOR = 1001
        const val NOTIF_ID_MISSING_ALERT = 2002
        const val NOTIF_ID_ALL_SAFE = 2003

        const val ACTION_TRIGGER_DEPARTURE_CHECK = "com.example.ACTION_TRIGGER_DEPARTURE_CHECK"
        const val ACTION_DISMISS_ALERT = "com.example.ACTION_DISMISS_ALERT"
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.notif_channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_monitor_desc)
            setShowBadge(false)
        }

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alertChannel = NotificationChannel(
            CHANNEL_MISSING_ALERT,
            context.getString(R.string.notif_channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_alert_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 180, 350, 180, 500)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val safeChannel = NotificationChannel(
            CHANNEL_ALL_SAFE,
            context.getString(R.string.notif_channel_safe_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_safe_desc)
        }

        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(alertChannel)
        manager.createNotificationChannel(safeChannel)
    }

    fun buildMonitorForegroundNotification(statusText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val scanNowIntent = PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, CarIgnitionReceiver::class.java).apply {
                action = ACTION_TRIGGER_DEPARTURE_CHECK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_monitor_title))
            .setContentText(statusText ?: context.getString(R.string.notif_monitor_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_search,
                context.getString(R.string.notif_action_rescan),
                scanNowIntent
            )
            .build()
    }

    fun showMissingPhonesAlert(
        missingPhoneLabels: List<String>,
        triggerSource: TriggerSource,
        vibrate: Boolean,
        speakVoice: Boolean
    ) {
        val missingJoined = missingPhoneLabels.joinToString(", ")
        val bodyText = "Tidak terdeteksi di mobil (${triggerSource.label}): $missingJoined. Pastikan ponsel tidak tertinggal di rumah!"

        if (BluetoothPermissionHelper.hasNotificationPermission(context)) {
            val openIntent = PendingIntent.getActivity(
                context,
                20,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val rescanIntent = PendingIntent.getBroadcast(
                context,
                21,
                Intent(context, CarIgnitionReceiver::class.java).apply {
                    action = ACTION_TRIGGER_DEPARTURE_CHECK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val dismissIntent = PendingIntent.getBroadcast(
                context,
                22,
                Intent(context, CarIgnitionReceiver::class.java).apply {
                    action = ACTION_DISMISS_ALERT
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_MISSING_ALERT)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.notif_alert_title))
                .setContentText("Tertinggal: $missingJoined")
                .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .addAction(
                    android.R.drawable.ic_popup_sync,
                    context.getString(R.string.notif_action_rescan),
                    rescanIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.notif_action_dismiss),
                    dismissIntent
                )
                .build()

            try {
                NotificationManagerCompat.from(context).notify(NOTIF_ID_MISSING_ALERT, notification)
            } catch (_: SecurityException) {
                // Permission revoked mid-flight
            }
        }

        if (vibrate) {
            triggerAlertVibration()
        }

        if (speakVoice) {
            speakIndonesian(
                "Perhatian! Ponsel $missingJoined tidak terdeteksi di dalam mobil. Mohon periksa sebelum berangkat."
            )
        }
    }

    fun showAllSafeNotification(
        detectedCount: Int,
        triggerSource: TriggerSource
    ) {
        dismissAlertNotification()
        if (!BluetoothPermissionHelper.hasNotificationPermission(context)) return

        val openIntent = PendingIntent.getActivity(
            context,
            30,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALL_SAFE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_safe_title))
            .setContentText("$detectedCount smartphone terdaftar terdeteksi via ${triggerSource.label}. Selamat berkendara!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_ALL_SAFE, notification)
        } catch (_: SecurityException) {
            // Permission revoked mid-flight
        }
    }

    fun showTestAlert(vibrate: Boolean, speakVoice: Boolean) {
        showMissingPhonesAlert(
            missingPhoneLabels = listOf("Contoh: Ponsel Utama"),
            triggerSource = TriggerSource.IGNITION_POWER,
            vibrate = vibrate,
            speakVoice = speakVoice
        )
    }

    fun dismissAlertNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_MISSING_ALERT)
    }

    private fun triggerAlertVibration() {
        val pattern = longArrayOf(0, 320, 160, 320, 160, 480)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {
            // Device may not have a vibrator
        }
    }

    private fun speakIndonesian(text: String) {
        if (tts == null) {
            pendingSpeech = text
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val indonesian = Locale.Builder().setLanguage("id").setRegion("ID").build()
                    val result = tts?.setLanguage(indonesian)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.language = Locale.getDefault()
                    }
                    ttsReady = true
                    pendingSpeech?.let { queued ->
                        tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "siaga_alert_tts")
                        pendingSpeech = null
                    }
                }
            }
        } else if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "siaga_alert_tts")
        } else {
            pendingSpeech = text
        }
    }
}
