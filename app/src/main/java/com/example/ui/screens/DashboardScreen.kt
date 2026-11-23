package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.bluetooth.AppleBluetoothHelper
import com.example.data.local.PhoneCategory
import com.example.ui.MonitoredPhoneUiItem
import com.example.ui.PhonePresenceStatus
import com.example.ui.SiagaUiState
import com.example.ui.components.categoryIcon
import com.example.ui.components.formatDistanceMeters
import com.example.ui.components.formatRelativeTime
import com.example.ui.theme.SiagaThemeTokens
import com.example.ui.theme.TelemetryMonoStyle

@Composable
fun DashboardScreen(
    uiState: SiagaUiState,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onScanCabinNow: () -> Unit,
    onRunIgnitionCheck: () -> Unit,
    onStopScan: () -> Unit,
    onToggleBackgroundService: (Boolean) -> Unit,
    onTogglePhoneMonitored: (String, Boolean) -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToRules: () -> Unit,
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = SiagaThemeTokens.colors

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("dashboard_list"),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Clean Top Header
            item {
                DashboardHeader(
                    uiState = uiState,
                    onOpenAbout = onOpenAbout
                )
            }

            // 2. Actionable Permission / Bluetooth Banner (only shown if needed)
            if (!uiState.hasBluetoothPermissions || !uiState.btState.isBluetoothEnabled || !uiState.btState.isHardwareSupported) {
                item {
                    RequirementAlertBanner(
                        uiState = uiState,
                        onRequestPermissions = onRequestPermissions,
                        onRequestEnableBluetooth = onRequestEnableBluetooth
                    )
                }
            }

            // 3. Simple, High-Signal Status & Quick-Scan Card
            item {
                PrimaryStatusCard(
                    uiState = uiState,
                    onScanNow = onScanCabinNow,
                    onStopScan = onStopScan,
                    onNavigateToDevices = onNavigateToDevices
                )
            }

            // 4. Compact Background Service + Central + Android Auto Strip
            item {
                ServiceAndAutoStatusCard(
                    uiState = uiState,
                    onToggleBackgroundService = onToggleBackgroundService,
                    onNavigateToDevices = onNavigateToDevices,
                    onNavigateToRules = onNavigateToRules
                )
            }

            // 5. Monitored Bluetooth Devices Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Perangkat Dipantau (${uiState.monitoredCount}/${uiState.phones.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Aktifkan sakelar untuk perangkat yang dicek saat Android Auto / scan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = onNavigateToDevices,
                        modifier = Modifier.testTag("dashboard_manage_devices_button")
                    ) {
                        Text("Kelola")
                    }
                }
            }

            // 6. Monitored Device Rows
            if (uiState.phones.isEmpty()) {
                item {
                    EmptyDevicesPromptCard(onAddDevices = onNavigateToDevices)
                }
            } else {
                items(
                    items = uiState.phones,
                    key = { it.entity.macAddress }
                ) { item ->
                    val isLikelyIphone = AppleBluetoothHelper.isLikelyAppleDevice(item.entity.deviceName) ||
                        AppleBluetoothHelper.isLikelyAppleDevice(item.entity.ownerLabel)
                    MonitoredDeviceRowCard(
                        item = item,
                        isLikelyIphone = isLikelyIphone,
                        onToggleMonitored = { checked ->
                            onTogglePhoneMonitored(item.entity.macAddress, checked)
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun DashboardHeader(
    uiState: SiagaUiState,
    onOpenAbout: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val (pillLabel, pillColor, pillBg) = when {
        uiState.androidAutoState.isConnected -> Triple("ANDROID AUTO", tokens.safe, tokens.safeContainer)
        !uiState.btState.isBluetoothEnabled -> Triple("BT MATI", tokens.warning, tokens.warningContainer)
        uiState.isMonitorServiceRunning -> Triple("SIAGA", tokens.radarAccent, MaterialTheme.colorScheme.primaryContainer)
        else -> Triple("MANUAL", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.dashboard_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = pillBg,
                border = BorderStroke(1.dp, pillColor.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(pillColor)
                    )
                    Text(
                        text = pillLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = pillColor
                    )
                }
            }
            IconButton(
                onClick = onOpenAbout,
                modifier = Modifier.testTag("dashboard_about_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.cd_about_app),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RequirementAlertBanner(
    uiState: SiagaUiState,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val (icon, title, buttonText, onAction) = when {
        !uiState.btState.isHardwareSupported -> Quadruple(
            Icons.Rounded.BluetoothDisabled,
            "Hardware Bluetooth tidak tersedia pada perangkat ini.",
            null,
            {}
        )
        !uiState.hasBluetoothPermissions -> Quadruple(
            Icons.Rounded.Security,
            "Izin Bluetooth & Notifikasi dibutuhkan untuk memindai perangkat.",
            "Izinkan",
            onRequestPermissions
        )
        else -> Quadruple(
            Icons.Rounded.BluetoothDisabled,
            "Bluetooth nonaktif. Aktifkan untuk memantau perangkat terdaftar.",
            "Aktifkan",
            onRequestEnableBluetooth
        )
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = tokens.warningContainer,
        border = BorderStroke(1.dp, tokens.warning.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tokens.warning,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.onWarningContainer,
                modifier = Modifier.weight(1f)
            )
            if (buttonText != null) {
                FilledTonalButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("requirement_action_button")
                ) {
                    Text(buttonText, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PrimaryStatusCard(
    uiState: SiagaUiState,
    onScanNow: () -> Unit,
    onStopScan: () -> Unit,
    onNavigateToDevices: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val missingItems = uiState.phones.filter {
        it.entity.isMonitored && it.liveStatus == PhonePresenceStatus.MISSING
    }

    val (accent, badgeBg, icon, headline, subtitle) = when {
        uiState.isScanningCabin -> Quintuple(
            tokens.radarAccent,
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Rounded.BluetoothSearching,
            "Memindai Perangkat… (${uiState.btState.remainingSeconds}d)",
            "Mengecek ${uiState.monitoredCount} perangkat aktif via ${uiState.btState.activeTriggerSource?.label ?: "Bluetooth"}"
        )
        uiState.monitoredCount == 0 -> Quintuple(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Rounded.Radar,
            stringResource(R.string.status_no_phones),
            "Tambahkan HP, jam tangan, atau tablet agar siap dipantau."
        )
        missingItems.isNotEmpty() -> Quintuple(
            tokens.alert,
            tokens.alertContainer,
            Icons.Rounded.WarningAmber,
            "${missingItems.size} Perangkat Tidak Terdeteksi!",
            "Tertinggal: ${missingItems.joinToString(", ") { it.entity.ownerLabel }}"
        )
        uiState.detectedCount == uiState.monitoredCount -> Quintuple(
            tokens.safe,
            tokens.safeContainer,
            Icons.Rounded.CheckCircle,
            stringResource(R.string.status_all_present),
            uiState.btState.lastCheckSummary?.let {
                "Cek terakhir ${formatRelativeTime(it.timestamp)} • ${it.triggerSource.label}"
            } ?: "Semua perangkat aktif berada dalam radius Sentral."
        )
        else -> Quintuple(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Rounded.Radar,
            stringResource(R.string.status_standby),
            uiState.btState.lastCheckSummary?.let {
                "Cek terakhir ${formatRelativeTime(it.timestamp)} • ${it.triggerSource.label}"
            } ?: "Siap memindai otomatis saat Android Auto atau Sentral terhubung."
        )
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("radar_cockpit_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Headline status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(badgeBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missingItems.isNotEmpty()) tokens.alert else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Inline 3-Metric KPI Strip
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KpiStatCell(
                        value = uiState.detectedCount.toString(),
                        label = "Terdeteksi",
                        valueColor = if (uiState.detectedCount > 0) tokens.safe else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(26.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    KpiStatCell(
                        value = uiState.missingCount.toString(),
                        label = "Tertinggal",
                        valueColor = if (uiState.missingCount > 0) tokens.alert else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(26.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    KpiStatCell(
                        value = uiState.monitoredCount.toString(),
                        label = "Dipantau",
                        valueColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AnimatedVisibility(visible = uiState.isScanningCabin) {
                LinearProgressIndicator(
                    progress = { uiState.btState.scanProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = tokens.radarAccent
                )
            }

            // Single Crisp Primary Action
            if (uiState.isScanningCabin) {
                FilledTonalButton(
                    onClick = onStopScan,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("stop_scan_button")
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Hentikan Pemindaian (${uiState.btState.remainingSeconds}d)")
                }
            } else if (uiState.monitoredCount == 0) {
                Button(
                    onClick = onNavigateToDevices,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("scan_cabin_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_add_phone))
                }
            } else {
                Button(
                    onClick = onScanNow,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (missingItems.isNotEmpty()) tokens.alert else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("scan_cabin_button")
                ) {
                    Icon(Icons.Rounded.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (missingItems.isNotEmpty()) "Pindai Ulang Sekarang" else stringResource(R.string.action_scan_now),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun KpiStatCell(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = valueColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ServiceAndAutoStatusCard(
    uiState: SiagaUiState,
    onToggleBackgroundService: (Boolean) -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToRules: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val isServiceActive = uiState.isMonitorServiceRunning
    val serviceAccent = if (isServiceActive) tokens.safe else MaterialTheme.colorScheme.onSurfaceVariant

    val centralName = uiState.settings.carBluetoothName.ifBlank { "Otomatis" }
    val autoLabel = when {
        uiState.androidAutoServiceState.isAutoScanInProgress -> "Memindai…"
        uiState.androidAutoState.isConnected -> "Terhubung"
        uiState.androidAutoServiceState.isServiceRunning -> "Service Aktif"
        uiState.settings.triggerOnAndroidAuto -> "Siaga"
        else -> "Nonaktif"
    }
    val autoColor = when {
        uiState.androidAutoServiceState.isAutoScanInProgress -> tokens.radarAccent
        uiState.androidAutoState.isConnected -> tokens.safe
        uiState.androidAutoServiceState.isServiceRunning -> tokens.radarAccent
        uiState.settings.triggerOnAndroidAuto -> tokens.radarAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (isServiceActive) tokens.safe.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("background_service_telemetry_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isServiceActive) tokens.safeContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = serviceAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Layanan Siaga",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (isServiceActive) tokens.safeContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isServiceActive) "Aktif Berjalan" else "Nonaktif",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isServiceActive) tokens.onSafeContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = uiState.serviceTelemetry.lastStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = uiState.settings.autoMonitorEnabled,
                    onCheckedChange = onToggleBackgroundService,
                    modifier = Modifier.testTag("dashboard_service_toggle")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Compact 3-pill summary: Sentral | Android Auto | Interval
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniInfoChip(
                    icon = Icons.Rounded.Hub,
                    label = "Sentral",
                    value = centralName,
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    onClick = onNavigateToDevices,
                    modifier = Modifier.weight(1f)
                )
                MiniInfoChip(
                    icon = Icons.Rounded.DirectionsCar,
                    label = "Android Auto",
                    value = autoLabel,
                    valueColor = autoColor,
                    onClick = onNavigateToRules,
                    modifier = Modifier.weight(1f)
                )
                MiniInfoChip(
                    icon = Icons.Rounded.Tune,
                    label = "Mode Scan",
                    value = "${uiState.settings.scanPowerProfile.label} • ${uiState.settings.periodicScanIntervalSeconds}d",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    onClick = onNavigateToRules,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MiniInfoChip(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MonitoredDeviceRowCard(
    item: MonitoredPhoneUiItem,
    isLikelyIphone: Boolean,
    onToggleMonitored: (Boolean) -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val category = PhoneCategory.fromName(item.entity.category)

    val (statusText, statusColor, statusBg) = when {
        !item.entity.isMonitored -> Triple(
            "Dijeda",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant
        )
        item.isCurrentlyConnected -> Triple(
            "Terhubung Bluetooth",
            tokens.safe,
            tokens.safeContainer
        )
        item.liveStatus == PhonePresenceStatus.DETECTED -> Triple(
            "Terdeteksi",
            tokens.safe,
            tokens.safeContainer
        )
        item.liveStatus == PhonePresenceStatus.MISSING -> Triple(
            "Terputus • Tertinggal!",
            tokens.alert,
            tokens.alertContainer
        )
        item.liveStatus == PhonePresenceStatus.SCANNING -> Triple(
            "Memindai…",
            tokens.radarAccent,
            MaterialTheme.colorScheme.primaryContainer
        )
        else -> Triple(
            "Siaga",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant
        )
    }

    val liveSignalLine = when {
        item.liveRssi != null -> {
            val dist = formatDistanceMeters(item.liveRssi)
            val seen = if (item.isCurrentlyConnected) "Aktif sekarang" else formatRelativeTime(item.entity.lastSeenTimestamp)
            "${item.liveRssi} dBm • $dist • $seen"
        }
        else -> {
            val thresholdDist = formatDistanceMeters(item.entity.rssiThreshold)
            "Batas ${item.entity.rssiThreshold} dBm ($thresholdDist)"
        }
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (item.liveStatus == PhonePresenceStatus.MISSING && item.entity.isMonitored) 1.4.dp else 1.dp,
            color = when {
                !item.entity.isMonitored -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                item.liveStatus == PhonePresenceStatus.MISSING -> tokens.alert.copy(alpha = 0.7f)
                item.liveStatus == PhonePresenceStatus.DETECTED -> tokens.safe.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("phone_telemetry_card_${item.entity.macAddress}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = categoryIcon(category, isLikelyIphone),
                    contentDescription = stringResource(R.string.cd_phone_icon),
                    tint = statusColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.entity.ownerLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "• ${category.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusBg
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = liveSignalLine,
                    style = TelemetryMonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = item.entity.isMonitored,
                onCheckedChange = onToggleMonitored,
                modifier = Modifier.testTag("toggle_monitor_${item.entity.macAddress}")
            )
        }
    }
}

@Composable
private fun EmptyDevicesPromptCard(onAddDevices: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = "Belum ada perangkat yang dipantau",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Tambahkan HP, jam tangan, atau tablet agar otomatis diperiksa saat terhubung ke Android Auto atau Sentral.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onAddDevices,
                modifier = Modifier.testTag("empty_add_device_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Tambah Perangkat")
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
