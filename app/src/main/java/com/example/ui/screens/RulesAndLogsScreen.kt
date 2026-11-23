package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.CheckLogEntity
import com.example.data.local.CheckStatus
import com.example.data.local.TriggerSource
import com.example.data.prefs.ScanPowerProfile
import com.example.data.prefs.ThemeMode
import com.example.ui.SiagaUiState
import com.example.ui.components.AboutAppCard
import com.example.ui.components.formatClockTimestamp
import com.example.ui.theme.SiagaThemeTokens
import com.example.ui.theme.TelemetryMonoStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesAndLogsScreen(
    uiState: SiagaUiState,
    onToggleAutoMonitor: (Boolean) -> Unit,
    onTogglePeriodicScan: (Boolean) -> Unit,
    onSelectScanPowerProfile: (ScanPowerProfile) -> Unit,
    onSelectPeriodicInterval: (Int) -> Unit,
    onToggleCarBtTrigger: (Boolean) -> Unit,
    onToggleAndroidAutoTrigger: (Boolean) -> Unit = {},
    onTriggerAndroidAutoScan: () -> Unit = {},
    onTogglePowerTrigger: (Boolean) -> Unit,
    onScanDurationChange: (Int) -> Unit,
    onToggleVoiceAlert: (Boolean) -> Unit,
    onToggleVibrateAlert: (Boolean) -> Unit,
    onToggleSafeNotif: (Boolean) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onTestAlert: () -> Unit,
    onClearLogs: () -> Unit,
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = SiagaThemeTokens.colors
    val settings = uiState.settings

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("rules_list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Aturan Mesin & Riwayat",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Konfigurasi pemicu otomatis saat mesin mobil menyala",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onOpenAbout,
                        modifier = Modifier.testTag("rules_about_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.cd_about_app),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Master Background Monitoring Card
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.autoMonitorEnabled) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (settings.autoMonitorEnabled) tokens.radarAccent.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Shield,
                            title = "Pemantauan Siaga Otomatis",
                            subtitle = if (uiState.isMonitorServiceRunning) {
                                uiState.serviceTelemetry.lastStatusText
                            } else if (settings.autoMonitorEnabled) {
                                "Aktif — berikan izin Bluetooth untuk menjalankan layanan siaga"
                            } else {
                                "Nonaktif — pemeriksaan hanya dilakukan secara manual"
                            },
                            checked = settings.autoMonitorEnabled,
                            onCheckedChange = onToggleAutoMonitor,
                            testTag = "switch_auto_monitor"
                        )

                        if (settings.autoMonitorEnabled) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            SettingsSwitchRow(
                                icon = Icons.Rounded.Bluetooth,
                                title = "Pemindaian Berkala (BluetoothAdapter)",
                                subtitle = "Pindai sinyal perangkat terdaftar secara rutin di latar belakang",
                                checked = settings.periodicBackgroundScanEnabled,
                                onCheckedChange = onTogglePeriodicScan,
                                testTag = "switch_periodic_bg_scan"
                            )

                            if (settings.periodicBackgroundScanEnabled) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Mode Daya & Hemat Baterai",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        ScanPowerProfile.entries.forEach { profile ->
                                            val selected = settings.scanPowerProfile == profile
                                            val icon = when (profile) {
                                                ScanPowerProfile.RESPONSIVE -> Icons.Rounded.Bolt
                                                ScanPowerProfile.BALANCED -> Icons.Rounded.Tune
                                                ScanPowerProfile.POWER_SAVING -> Icons.Rounded.BatterySaver
                                            }
                                            val activeColor = when (profile) {
                                                ScanPowerProfile.RESPONSIVE -> tokens.warning
                                                ScanPowerProfile.BALANCED -> tokens.radarAccent
                                                ScanPowerProfile.POWER_SAVING -> tokens.safe
                                            }
                                            Surface(
                                                onClick = { onSelectScanPowerProfile(profile) },
                                                shape = MaterialTheme.shapes.medium,
                                                color = if (selected) activeColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(
                                                    width = if (selected) 1.5.dp else 1.dp,
                                                    color = if (selected) activeColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("power_profile_${profile.name.lowercase()}")
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        tint = if (selected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Text(
                                                        text = profile.label,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = if (selected) activeColor else MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = "${profile.defaultIntervalSeconds}d",
                                                        style = TelemetryMonoStyle,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = settings.scanPowerProfile.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("power_profile_description")
                                    )

                                    Text(
                                        text = "Sesuaikan Interval Cek Latar Belakang",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        val presets = listOf(
                                            30 to "30 dtk",
                                            60 to "1 mnt",
                                            120 to "2 mnt",
                                            180 to "3 mnt",
                                            300 to "5 mnt"
                                        )
                                        presets.forEach { (sec, label) ->
                                            FilterChip(
                                                selected = settings.periodicScanIntervalSeconds == sec,
                                                onClick = { onSelectPeriodicInterval(sec) },
                                                label = { Text(label) },
                                                modifier = Modifier.testTag("interval_chip_$sec")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Ignition Trigger Rules
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "Pemicu Otomatis & Android Auto",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )

                        val autoService = uiState.androidAutoServiceState
                        SettingsSwitchRow(
                            icon = Icons.Rounded.DirectionsCar,
                            title = when {
                                uiState.androidAutoState.isConnected -> "Service Android Auto (Terhubung)"
                                autoService.isServiceRunning -> "Service Android Auto (Aktif)"
                                else -> "Service Android Auto (Car Mode)"
                            },
                            subtitle = "Service khusus mendeteksi Android Auto / Car Mode lalu otomatis memindai semua perangkat terdaftar (HP, jam tangan, tablet, dll.).",
                            checked = settings.triggerOnAndroidAuto,
                            onCheckedChange = onToggleAndroidAutoTrigger,
                            testTag = "switch_android_auto_trigger"
                        )

                        if (settings.triggerOnAndroidAuto) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (uiState.androidAutoState.isConnected) {
                                    tokens.safeContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (uiState.androidAutoState.isConnected) tokens.safe.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                    .testTag("android_auto_service_status_box")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = autoService.lastAutoScanSummaryText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (uiState.androidAutoState.isConnected) {
                                            tokens.onSafeContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    OutlinedButton(
                                        onClick = onTriggerAndroidAutoScan,
                                        enabled = !autoService.isAutoScanInProgress && !uiState.isScanningCabin,
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("test_android_auto_scan_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DirectionsCar,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (autoService.isAutoScanInProgress) {
                                                "Menjalankan Scan Android Auto…"
                                            } else {
                                                "Uji Pemicu Scan Android Auto"
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        SettingsSwitchRow(
                            icon = Icons.Rounded.Bluetooth,
                            title = "Koneksi Perangkat Sentral",
                            subtitle = if (settings.carBluetoothName.isNotBlank()) {
                                "Memicu cek otomatis saat tersambung ke Sentral: ${settings.carBluetoothName}"
                            } else {
                                "Memicu cek otomatis saat tersambung ke head unit mobil atau perangkat sentral"
                            },
                            checked = settings.triggerOnCarBluetooth,
                            onCheckedChange = onToggleCarBtTrigger,
                            testTag = "switch_car_bt_trigger"
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        SettingsSwitchRow(
                            icon = Icons.Rounded.Power,
                            title = "Daya Charger / ACC 12V Tersambung",
                            subtitle = "Cocok untuk head unit Android atau HP di mobil yang menyala mengikuti kunci kontak",
                            checked = settings.triggerOnPowerConnected,
                            onCheckedChange = onTogglePowerTrigger,
                            testTag = "switch_power_trigger"
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Durasi Pemindaian Bluetooth",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${settings.scanDurationSeconds} detik",
                                    style = TelemetryMonoStyle,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Waktu tunggu mencari sinyal perangkat di sekitar Sentral sebelum menyimpulkan tertinggal.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = settings.scanDurationSeconds.toFloat(),
                                onValueChange = { onScanDurationChange(it.roundToInt()) },
                                valueRange = 8f..25f,
                                steps = 16,
                                modifier = Modifier.testTag("slider_scan_duration")
                            )
                        }
                    }
                }
            }

            // Alerts & Appearance Card
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "Peringatan & Tampilan",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )

                        SettingsSwitchRow(
                            icon = Icons.Rounded.RecordVoiceOver,
                            title = "Peringatan Suara (Bahasa Indonesia)",
                            subtitle = "Membacakan nama perangkat yang tertinggal lewat speaker secara otomatis",
                            checked = settings.voiceAlertEnabled,
                            onCheckedChange = onToggleVoiceAlert,
                            testTag = "switch_voice_alert"
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        SettingsSwitchRow(
                            icon = Icons.Rounded.Vibration,
                            title = "Getar Pola Darurat",
                            subtitle = "Bergetar berulang saat perangkat terdaftar tidak terdeteksi",
                            checked = settings.vibrateAlertEnabled,
                            onCheckedChange = onToggleVibrateAlert,
                            testTag = "switch_vibrate_alert"
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        SettingsSwitchRow(
                            icon = Icons.Rounded.NotificationsActive,
                            title = "Notifikasi Konfirmasi Aman",
                            subtitle = "Tampilkan notifikasi singkat saat semua perangkat terbawa",
                            checked = settings.notifyWhenAllSafe,
                            onCheckedChange = onToggleSafeNotif,
                            testTag = "switch_safe_notif"
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Tema Antarmuka",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ThemeMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = settings.themeMode == mode,
                                        onClick = { onSelectThemeMode(mode) },
                                        label = { Text(mode.label) }
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            OutlinedButton(
                                onClick = onTestAlert,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("test_alert_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.NotificationsActive,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_test_alert) + " (Notifikasi & Suara)")
                            }
                        }
                    }
                }
            }

            // Departure Check Logs Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Riwayat Pemeriksaan (${uiState.checkLogs.size})",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    if (uiState.checkLogs.isNotEmpty()) {
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.testTag("clear_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Bersihkan riwayat"
                            )
                        }
                    }
                }
            }

            if (uiState.checkLogs.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Belum ada riwayat pemeriksaan",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Setiap pemeriksaan otomatis saat mesin menyala atau cek manual akan tercatat di sini.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(
                    items = uiState.checkLogs,
                    key = { it.id }
                ) { log ->
                    CheckLogCard(log = log)
                }
            }

            // About section
            item {
                AboutAppCard()
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun CheckLogCard(log: CheckLogEntity) {
    val tokens = SiagaThemeTokens.colors
    val status = CheckStatus.fromName(log.status)
    val trigger = TriggerSource.fromName(log.triggerSource)

    val (accent, container, icon) = when (status) {
        CheckStatus.ALL_PRESENT -> Triple(tokens.safe, tokens.safeContainer, Icons.Rounded.CheckCircle)
        CheckStatus.MISSING_ALERT -> Triple(tokens.alert, tokens.alertContainer, Icons.Rounded.WarningAmber)
        CheckStatus.BT_UNAVAILABLE -> Triple(tokens.warning, tokens.warningContainer, Icons.Rounded.BluetoothDisabled)
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(container),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = accent
                    )
                    Text(
                        text = "${log.detectedCount}/${log.totalMonitored} perangkat",
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (log.missingPhoneNames.isNotBlank() && status == CheckStatus.MISSING_ALERT) {
                    Text(
                        text = "Tertinggal: ${log.missingPhoneNames}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "${trigger.label} • ${formatClockTimestamp(log.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
