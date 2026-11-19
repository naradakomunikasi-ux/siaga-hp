package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.CheckStatus
import com.example.data.local.PhoneCategory
import com.example.ui.MonitoredPhoneUiItem
import com.example.ui.PhonePresenceStatus
import com.example.ui.SiagaUiState
import com.example.ui.components.BluetoothRadarHud
import com.example.ui.components.RadarVisualState
import com.example.ui.components.SignalStrengthIndicator
import com.example.ui.components.formatRelativeTime
import com.example.ui.components.formatRssiQuality
import com.example.ui.theme.SiagaThemeTokens
import com.example.ui.theme.TelemetryMonoStyle

private enum class PhoneStatusFilter(val label: String) {
    ALL("Semua"),
    CONNECTED_OR_DETECTED("Terdeteksi"),
    MISSING("Tertinggal")
}

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
    var activeFilter by remember { mutableStateOf(PhoneStatusFilter.ALL) }

    val filteredPhones = remember(uiState.phones, activeFilter) {
        when (activeFilter) {
            PhoneStatusFilter.ALL -> uiState.phones
            PhoneStatusFilter.CONNECTED_OR_DETECTED -> uiState.phones.filter {
                it.isCurrentlyConnected || it.liveStatus == PhonePresenceStatus.DETECTED
            }
            PhoneStatusFilter.MISSING -> uiState.phones.filter {
                it.liveStatus == PhonePresenceStatus.MISSING
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val isExpandedLayout = maxWidth >= 840.dp

        if (isExpandedLayout) {
            // Adaptive two-pane automotive cockpit layout for tablets / foldables
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(0.48f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        CockpitHeader(
                            isServiceRunning = uiState.isMonitorServiceRunning,
                            isBluetoothOn = uiState.btState.isBluetoothEnabled,
                            isScanningNow = uiState.serviceTelemetry.isScanningNow || uiState.isScanningCabin,
                            onClickStatusBadge = onNavigateToRules,
                            onOpenAbout = onOpenAbout
                        )
                    }
                    item {
                        HardwareAndPermissionBannerSection(
                            uiState = uiState,
                            onRequestPermissions = onRequestPermissions,
                            onRequestEnableBluetooth = onRequestEnableBluetooth,
                            onNavigateToRules = onNavigateToRules
                        )
                    }
                    if (uiState.phones.isEmpty()) {
                        item { EmptyOnboardingHeroCard(onAddPhoneClick = onNavigateToDevices) }
                    } else {
                        item {
                            RadarCockpitCard(
                                uiState = uiState,
                                onScanCabinNow = onScanCabinNow,
                                onRunIgnitionCheck = onRunIgnitionCheck,
                                onStopScan = onStopScan
                            )
                        }
                    }
                    item {
                        BluetoothBackgroundServicePanel(
                            uiState = uiState,
                            onToggleService = onToggleBackgroundService,
                            onConfigureRules = onNavigateToRules
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(0.52f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        IgnitionTriggerSummaryRow(
                            uiState = uiState,
                            onClick = onNavigateToRules
                        )
                    }
                    item {
                        ConnectionSummaryHeader(
                            uiState = uiState,
                            activeFilter = activeFilter,
                            onSelectFilter = { activeFilter = it },
                            onManageDevices = onNavigateToDevices
                        )
                    }
                    if (uiState.phones.isEmpty()) {
                        item {
                            EmptyRegisteredPhonesCard(onAddPhoneClick = onNavigateToDevices)
                        }
                    } else if (filteredPhones.isEmpty()) {
                        item {
                            EmptyFilterResultCard(
                                filterLabel = activeFilter.label,
                                onResetFilter = { activeFilter = PhoneStatusFilter.ALL }
                            )
                        }
                    } else {
                        items(
                            items = filteredPhones,
                            key = { it.entity.macAddress }
                        ) { item ->
                            DashboardPhoneTelemetryCard(
                                item = item,
                                onToggleMonitored = { enabled ->
                                    onTogglePhoneMonitored(item.entity.macAddress, enabled)
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Compact / Medium single-column layout
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .testTag("dashboard_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    CockpitHeader(
                        isServiceRunning = uiState.isMonitorServiceRunning,
                        isBluetoothOn = uiState.btState.isBluetoothEnabled,
                        isScanningNow = uiState.serviceTelemetry.isScanningNow || uiState.isScanningCabin,
                        onClickStatusBadge = onNavigateToRules,
                        onOpenAbout = onOpenAbout
                    )
                }

                item {
                    HardwareAndPermissionBannerSection(
                        uiState = uiState,
                        onRequestPermissions = onRequestPermissions,
                        onRequestEnableBluetooth = onRequestEnableBluetooth,
                        onNavigateToRules = onNavigateToRules
                    )
                }

                if (uiState.phones.isEmpty()) {
                    item {
                        EmptyOnboardingHeroCard(onAddPhoneClick = onNavigateToDevices)
                    }
                } else {
                    item {
                        RadarCockpitCard(
                            uiState = uiState,
                            onScanCabinNow = onScanCabinNow,
                            onRunIgnitionCheck = onRunIgnitionCheck,
                            onStopScan = onStopScan
                        )
                    }
                }

                // Dedicated Bluetooth Background Service Status & Controls Panel
                item {
                    BluetoothBackgroundServicePanel(
                        uiState = uiState,
                        onToggleService = onToggleBackgroundService,
                        onConfigureRules = onNavigateToRules
                    )
                }

                // Active Ignition Triggers Summary Strip
                item {
                    IgnitionTriggerSummaryRow(
                        uiState = uiState,
                        onClick = onNavigateToRules
                    )
                }

                // Registered Smartphones Connection Status Section
                if (uiState.phones.isNotEmpty()) {
                    item {
                        ConnectionSummaryHeader(
                            uiState = uiState,
                            activeFilter = activeFilter,
                            onSelectFilter = { activeFilter = it },
                            onManageDevices = onNavigateToDevices
                        )
                    }

                    if (filteredPhones.isEmpty()) {
                        item {
                            EmptyFilterResultCard(
                                filterLabel = activeFilter.label,
                                onResetFilter = { activeFilter = PhoneStatusFilter.ALL }
                            )
                        }
                    } else {
                        items(
                            items = filteredPhones,
                            key = { it.entity.macAddress }
                        ) { item ->
                            DashboardPhoneTelemetryCard(
                                item = item,
                                onToggleMonitored = { enabled ->
                                    onTogglePhoneMonitored(item.entity.macAddress, enabled)
                                }
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun HardwareAndPermissionBannerSection(
    uiState: SiagaUiState,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onNavigateToRules: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    when {
        !uiState.hasBluetoothPermissions || !uiState.hasNotificationPermission -> {
            ActionBannerCard(
                icon = Icons.Rounded.Security,
                title = "Izin Bluetooth & Notifikasi Diperlukan",
                description = "Aktifkan izin agar layanan Bluetooth dapat memindai ponsel terdaftar dan memberi peringatan otomatis saat mesin menyala.",
                buttonText = stringResource(R.string.action_grant_permission),
                accentColor = tokens.warning,
                containerColor = tokens.warningContainer,
                contentColor = tokens.onWarningContainer,
                buttonTag = "grant_permissions_button",
                onClick = onRequestPermissions
            )
        }
        !uiState.btState.isHardwareSupported -> {
            ActionBannerCard(
                icon = Icons.Rounded.BluetoothDisabled,
                title = stringResource(R.string.status_bt_unsupported),
                description = "Perangkat ini tidak memiliki modul Bluetooth. Anda tetap dapat mengelola daftar ponsel dan menguji sistem notifikasi.",
                buttonText = "Kelola Aturan",
                accentColor = tokens.alert,
                containerColor = tokens.alertContainer,
                contentColor = tokens.onAlertContainer,
                buttonTag = "bt_unsupported_rules_button",
                onClick = onNavigateToRules
            )
        }
        !uiState.btState.isBluetoothEnabled -> {
            ActionBannerCard(
                icon = Icons.Rounded.BluetoothDisabled,
                title = stringResource(R.string.status_bt_off),
                description = "Nyalakan Bluetooth agar layanan latar belakang dapat mendeteksi koneksi dan sinyal smartphone terdaftar.",
                buttonText = stringResource(R.string.action_enable_bt),
                accentColor = tokens.warning,
                containerColor = tokens.warningContainer,
                contentColor = tokens.onWarningContainer,
                buttonTag = "enable_bluetooth_button",
                onClick = onRequestEnableBluetooth
            )
        }
    }
}

@Composable
private fun CockpitHeader(
    isServiceRunning: Boolean,
    isBluetoothOn: Boolean,
    isScanningNow: Boolean,
    onClickStatusBadge: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val infiniteTransition = rememberInfiniteTransition(label = "header_pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_dot_alpha"
    )

    val isLive = isServiceRunning && isBluetoothOn
    val dotColor = when {
        isScanningNow -> tokens.radarAccent
        isLive -> tokens.safe
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.dashboard_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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

            Surface(
                onClick = onClickStatusBadge,
                shape = RoundedCornerShape(999.dp),
                color = when {
                    isScanningNow -> MaterialTheme.colorScheme.primaryContainer
                    isLive -> tokens.safeContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                border = BorderStroke(
                    1.dp,
                    when {
                        isScanningNow -> tokens.radarAccent.copy(alpha = 0.6f)
                        isLive -> tokens.safe.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.outline
                    }
                ),
                modifier = Modifier.testTag("monitor_status_pill")
            ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(if (isLive || isScanningNow) dotAlpha else 1f)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = when {
                        isScanningNow -> "MEMINDAI"
                        isLive -> "SIAGA AKTIF"
                        isServiceRunning -> "BT NONAKTIF"
                        else -> "SIAGA OFF"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = when {
                        isScanningNow -> MaterialTheme.colorScheme.onPrimaryContainer
                        isLive -> tokens.onSafeContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        }
    }
}

@Composable
private fun BluetoothBackgroundServicePanel(
    uiState: SiagaUiState,
    onToggleService: (Boolean) -> Unit,
    onConfigureRules: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val telemetry = uiState.serviceTelemetry
    val settings = uiState.settings
    val isActive = telemetry.isRunning
    val isScanning = telemetry.isScanningNow || uiState.isScanningCabin
    val btReady = uiState.btState.isBluetoothEnabled && uiState.btState.isHardwareSupported

    val accentColor = when {
        isScanning -> tokens.radarAccent
        isActive && btReady -> tokens.safe
        isActive && !btReady -> tokens.warning
        else -> MaterialTheme.colorScheme.outline
    }

    val statusBadgeLabel = when {
        isScanning -> "Sedang Memindai"
        isActive && btReady -> "Aktif Berjalan"
        isActive && !btReady -> "Menunggu Bluetooth"
        else -> "Nonaktif"
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (isActive) accentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("background_service_telemetry_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Row: Title + Live Status Badge + Direct Service Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isActive) accentColor.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Rounded.BluetoothSearching else Icons.Rounded.Radar,
                        contentDescription = null,
                        tint = if (isActive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Layanan Bluetooth Latar Belakang",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (isActive) accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = statusBadgeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isActive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .testTag("background_service_status_badge")
                        )
                    }
                }

                Switch(
                    checked = settings.autoMonitorEnabled,
                    onCheckedChange = onToggleService,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF090E1A),
                        checkedTrackColor = accentColor
                    ),
                    modifier = Modifier.testTag("dashboard_service_toggle")
                )
            }

            // Live Status Narrative from Service Telemetry
            Text(
                text = telemetry.lastStatusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("background_service_status_detail")
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 3-Column Cockpit Telemetry Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ServiceMetricCell(
                    label = "ADAPTER BT",
                    value = when {
                        !uiState.btState.isHardwareSupported -> "N/A"
                        uiState.btState.isBluetoothEnabled -> "Aktif"
                        else -> "Mati"
                    },
                    valueColor = if (btReady) tokens.safe else tokens.warning,
                    modifier = Modifier.weight(1f)
                )
                ServiceMetricCell(
                    label = "MODE SCAN",
                    value = if (settings.autoMonitorEnabled && settings.periodicBackgroundScanEnabled) {
                        "${settings.scanPowerProfile.label} • ${settings.periodicScanIntervalSeconds}d"
                    } else {
                        "Pemicu Saja"
                    },
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ServiceMetricCell(
                    label = "CEK TERAKHIR",
                    value = if (telemetry.lastScanTimestamp != null) {
                        formatRelativeTime(telemetry.lastScanTimestamp)
                    } else {
                        "Belum ada"
                    },
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onConfigureRules,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("configure_service_rules_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Atur Interval & Pemicu", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ServiceMetricCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConnectionSummaryHeader(
    uiState: SiagaUiState,
    activeFilter: PhoneStatusFilter,
    onSelectFilter: (PhoneStatusFilter) -> Unit,
    onManageDevices: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val connectedOrDetected = uiState.phones.count {
        it.entity.isMonitored && (it.isCurrentlyConnected || it.liveStatus == PhonePresenceStatus.DETECTED)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Status Koneksi Smartphone",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${uiState.monitoredCount} dipantau dari ${uiState.phones.size} perangkat terdaftar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onManageDevices,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("add_more_phone_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Kelola", style = MaterialTheme.typography.labelLarge)
            }
        }

        // At-a-glance Connection Counter Chips
        if (uiState.phones.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryStatTile(
                    label = "Terdeteksi",
                    count = connectedOrDetected,
                    accent = tokens.safe,
                    container = tokens.safeContainer,
                    content = tokens.onSafeContainer,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("summary_detected_count")
                )
                SummaryStatTile(
                    label = "Tertinggal",
                    count = uiState.missingCount,
                    accent = tokens.alert,
                    container = if (uiState.missingCount > 0) tokens.alertContainer else MaterialTheme.colorScheme.surfaceVariant,
                    content = if (uiState.missingCount > 0) tokens.onAlertContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("summary_missing_count")
                )
                SummaryStatTile(
                    label = "Dipantau",
                    count = uiState.monitoredCount,
                    accent = tokens.radarAccent,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("summary_monitored_count")
                )
            }
        }

        // Quick Filter Chips when multiple phones are registered
        if (uiState.phones.size >= 2) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                PhoneStatusFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.label) },
                        modifier = Modifier.testTag("phone_filter_${filter.name.lowercase()}")
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStatTile(
    label: String,
    count: Int,
    accent: Color,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = content
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun EmptyRegisteredPhonesCard(
    onAddPhoneClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Smartphone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "Belum ada smartphone terdaftar",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Tambahkan smartphone di tab Perangkat agar status koneksi Bluetooth-nya tampil di dashboard ini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onAddPhoneClick) {
                Text(stringResource(R.string.action_add_phone))
            }
        }
    }
}

@Composable
private fun EmptyFilterResultCard(
    filterLabel: String,
    onResetFilter: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Tidak ada smartphone dengan status \"$filterLabel\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onResetFilter) {
                Text("Tampilkan Semua")
            }
        }
    }
}

@Composable
private fun ActionBannerCard(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    accentColor: Color,
    containerColor: Color,
    contentColor: Color,
    buttonTag: String,
    onClick: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.88f)
            )
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = Color(0xFF090E1A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(buttonTag)
            ) {
                Text(text = buttonText, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun EmptyOnboardingHeroCard(
    onAddPhoneClick: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(196.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_car_radar_hero),
                    contentDescription = stringResource(R.string.cd_hero_radar),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Radar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "RADAR KABIN BLUETOOTH",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Pastikan Ponsel Terbawa Sebelum Berangkat",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Daftarkan smartphone utama, HP kerja, atau ponsel keluarga. Saat mesin mobil menyala, SiagaHP memindai sinyal Bluetooth dan memberi peringatan otomatis bila ada yang tertinggal di rumah.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    StepRow(number = "1", text = "Daftarkan smartphone via pemindaian Bluetooth atau daftar Paired")
                    StepRow(number = "2", text = "Pilih pemicu otomatis (Bluetooth mobil dan/atau daya USB 12V)")
                    StepRow(number = "3", text = "Pantau status koneksi & terima peringatan bila ponsel tertinggal")
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onAddPhoneClick,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.radarAccent,
                        contentColor = Color(0xFF090E1A)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("register_first_phone_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_add_phone),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RadarCockpitCard(
    uiState: SiagaUiState,
    onScanCabinNow: () -> Unit,
    onRunIgnitionCheck: () -> Unit,
    onStopScan: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val summary = uiState.btState.lastCheckSummary

    val visualState = when {
        uiState.isScanningCabin -> RadarVisualState.SCANNING
        uiState.monitoredCount == 0 -> RadarVisualState.STANDBY
        uiState.missingCount > 0 || summary?.status == CheckStatus.MISSING_ALERT -> RadarVisualState.MISSING_ALERT
        uiState.detectedCount >= uiState.monitoredCount && uiState.monitoredCount > 0 -> RadarVisualState.ALL_SAFE
        else -> RadarVisualState.STANDBY
    }

    val headline = when (visualState) {
        RadarVisualState.SCANNING -> stringResource(R.string.status_scanning)
        RadarVisualState.ALL_SAFE -> stringResource(R.string.status_all_present)
        RadarVisualState.MISSING_ALERT -> stringResource(R.string.status_missing_alert)
        RadarVisualState.STANDBY -> stringResource(R.string.status_standby)
    }

    val subtitle = when (visualState) {
        RadarVisualState.SCANNING -> {
            val triggerLabel = uiState.btState.activeTriggerSource?.label ?: "Manual"
            "Pemicu: $triggerLabel • Sisa ${uiState.btState.remainingSeconds} detik"
        }
        RadarVisualState.ALL_SAFE -> {
            val timeStr = formatRelativeTime(summary?.timestamp)
            "${uiState.detectedCount} dari ${uiState.monitoredCount} ponsel terdeteksi di dalam mobil • $timeStr"
        }
        RadarVisualState.MISSING_ALERT -> {
            val missingNames = uiState.phones
                .filter { it.liveStatus == PhonePresenceStatus.MISSING }
                .joinToString(", ") { it.entity.ownerLabel }
            "Belum terdeteksi: $missingNames"
        }
        RadarVisualState.STANDBY -> {
            "Tekan periksa kabin atau nyalakan mesin mobil untuk deteksi otomatis."
        }
    }

    val borderColor = when (visualState) {
        RadarVisualState.SCANNING -> tokens.radarAccent.copy(alpha = 0.55f)
        RadarVisualState.ALL_SAFE -> tokens.safe.copy(alpha = 0.55f)
        RadarVisualState.MISSING_ALERT -> tokens.alert.copy(alpha = 0.7f)
        RadarVisualState.STANDBY -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("radar_cockpit_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BluetoothRadarHud(
                visualState = visualState,
                phones = uiState.phones,
                detectedCount = uiState.detectedCount,
                monitoredCount = uiState.monitoredCount,
                remainingSeconds = uiState.btState.remainingSeconds
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.headlineMedium,
                    color = when (visualState) {
                        RadarVisualState.MISSING_ALERT -> tokens.alert
                        RadarVisualState.ALL_SAFE -> tokens.safe
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = uiState.isScanningCabin) {
                LinearProgressIndicator(
                    progress = { uiState.btState.scanProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = tokens.radarAccent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (uiState.isScanningCabin) {
                    Button(
                        onClick = onStopScan,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.alert,
                            contentColor = Color.White
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("stop_scan_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_stop_scan),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                    Button(
                        onClick = onScanCabinNow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("scan_cabin_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_scan_now),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (!uiState.isScanningCabin) {
                OutlinedButton(
                    onClick = onRunIgnitionCheck,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("simulate_ignition_check_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_simulate_ignition),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun IgnitionTriggerSummaryRow(
    uiState: SiagaUiState,
    onClick: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val carBtName = uiState.settings.carBluetoothName.ifBlank { "Auto Audio Mobil" }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TriggerMiniCard(
            icon = Icons.Rounded.Bluetooth,
            title = "BT Mobil",
            subtitle = if (uiState.settings.triggerOnCarBluetooth) carBtName else "Nonaktif",
            isActive = uiState.settings.autoMonitorEnabled && uiState.settings.triggerOnCarBluetooth,
            activeColor = tokens.radarAccent,
            onClick = onClick,
            modifier = Modifier.weight(1f)
        )
        TriggerMiniCard(
            icon = Icons.Rounded.Power,
            title = "Daya Mesin",
            subtitle = if (uiState.settings.triggerOnPowerConnected) "USB / Charger 12V" else "Nonaktif",
            isActive = uiState.settings.autoMonitorEnabled && uiState.settings.triggerOnPowerConnected,
            activeColor = tokens.safe,
            onClick = onClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TriggerMiniCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isActive) activeColor.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) activeColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DashboardPhoneTelemetryCard(
    item: MonitoredPhoneUiItem,
    onToggleMonitored: (Boolean) -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val category = PhoneCategory.fromName(item.entity.category)

    val connectionVisual = when {
        !item.entity.isMonitored -> ConnectionBadgeStyle(
            badgeText = "Pemantauan Dijeda",
            detailText = "Aktifkan sakelar untuk memantau",
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            border = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            icon = Icons.Rounded.PauseCircleOutline
        )
        item.isCurrentlyConnected -> ConnectionBadgeStyle(
            badgeText = "Terhubung Bluetooth",
            detailText = "Koneksi aktif di dalam kabin",
            container = tokens.safeContainer,
            content = tokens.onSafeContainer,
            border = tokens.safe.copy(alpha = 0.55f),
            icon = Icons.Rounded.BluetoothConnected
        )
        item.liveStatus == PhonePresenceStatus.DETECTED -> ConnectionBadgeStyle(
            badgeText = "Sinyal Terdeteksi",
            detailText = formatRssiQuality(item.liveRssi),
            container = tokens.safeContainer,
            content = tokens.onSafeContainer,
            border = tokens.safe.copy(alpha = 0.45f),
            icon = Icons.Rounded.CheckCircle
        )
        item.liveStatus == PhonePresenceStatus.MISSING -> ConnectionBadgeStyle(
            badgeText = "Terputus • Tertinggal!",
            detailText = "Sinyal tidak terdeteksi (${formatRelativeTime(item.entity.lastSeenTimestamp)})",
            container = tokens.alertContainer,
            content = tokens.onAlertContainer,
            border = tokens.alert.copy(alpha = 0.7f),
            icon = Icons.Rounded.LinkOff
        )
        item.liveStatus == PhonePresenceStatus.SCANNING -> ConnectionBadgeStyle(
            badgeText = "Memindai Sinyal…",
            detailText = "Mencari perangkat di sekitar mobil",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            border = tokens.radarAccent.copy(alpha = 0.5f),
            icon = Icons.Rounded.BluetoothSearching
        )
        else -> ConnectionBadgeStyle(
            badgeText = "Siaga • Belum Dicek",
            detailText = formatRelativeTime(item.entity.lastSeenTimestamp),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            border = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            icon = Icons.Rounded.Smartphone
        )
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, connectionVisual.border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("phone_telemetry_card_${item.entity.macAddress}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(connectionVisual.container),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.isCurrentlyConnected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Smartphone,
                        contentDescription = stringResource(R.string.cd_phone_icon),
                        tint = connectionVisual.content,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = item.entity.ownerLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "${item.entity.deviceName} • ${item.entity.macAddress}",
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Switch(
                    checked = item.entity.isMonitored,
                    onCheckedChange = onToggleMonitored,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF090E1A),
                        checkedTrackColor = tokens.radarAccent
                    ),
                    modifier = Modifier.testTag("toggle_monitor_${item.entity.macAddress}")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = connectionVisual.container
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = connectionVisual.icon,
                            contentDescription = null,
                            tint = connectionVisual.content,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = connectionVisual.badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = connectionVisual.content
                        )
                    }
                }

                if (item.liveRssi != null) {
                    SignalStrengthIndicator(rssi = item.liveRssi)
                }
            }

            Text(
                text = connectionVisual.detailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class ConnectionBadgeStyle(
    val badgeText: String,
    val detailText: String,
    val container: Color,
    val content: Color,
    val border: Color,
    val icon: ImageVector
)
