package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsBluetooth
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.bluetooth.AppleBluetoothHelper
import com.example.bluetooth.ScannedBluetoothDevice
import com.example.data.local.PhoneCategory
import com.example.data.local.RegisteredPhoneEntity
import com.example.ui.SiagaUiState
import com.example.ui.components.BluetoothScannerModalSheet
import com.example.ui.components.SignalStrengthIndicator
import com.example.ui.components.categoryIcon
import com.example.ui.components.formatDistanceMeters
import com.example.ui.components.formatRssiQuality
import com.example.ui.theme.SiagaThemeTokens
import com.example.ui.theme.TelemetryMonoStyle
import kotlin.math.roundToInt

@Composable
fun DevicesScreen(
    uiState: SiagaUiState,
    onStartNearbyDiscovery: () -> Unit,
    onStopScan: () -> Unit,
    onRefreshPaired: () -> Unit,
    onRegisterOrUpdatePhone: (
        mac: String,
        deviceName: String,
        ownerLabel: String,
        category: PhoneCategory,
        rssiThreshold: Int,
        isMonitored: Boolean
    ) -> Unit,
    onQuickRegisterDevice: (ScannedBluetoothDevice) -> Unit,
    onBatchRegisterDevices: (List<ScannedBluetoothDevice>) -> Unit,
    onDeletePhone: (String) -> Unit,
    onSetCarBluetooth: (String, String) -> Unit,
    onPromoteToCentral: (String, String) -> Unit = onSetCarBluetooth,
    onClearCarBluetooth: () -> Unit,
    onPairDevice: (String) -> Unit = {},
    onTestPhoneConnection: (String, String) -> Unit = { _, _ -> },
    onOpenBluetoothSettings: () -> Unit = {},
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = SiagaThemeTokens.colors
    var selectedDiscoveryTab by remember { mutableIntStateOf(1) } // 0 = Paired, 1 = Nearby
    var showScannerSheet by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var pendingRegisterDevice by remember { mutableStateOf<ScannedBluetoothDevice?>(null) }
    var editingPhone by remember { mutableStateOf<RegisteredPhoneEntity?>(null) }

    val registeredMacs = remember(uiState.phones) {
        uiState.phones.map { it.entity.macAddress.uppercase() }.toSet()
    }
    val pairedMacs = remember(uiState.btState.pairedDevices) {
        uiState.btState.pairedDevices.map { it.address.uppercase() }.toSet()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
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
                            text = "Manajemen Perangkat",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Atur perangkat Sentral (pemicu) & perangkat Bluetooth yang dipantau",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showManualDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("manual_mac_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("MAC")
                    }
                }
            }

            // Prominent Quick-Scan & Add Nearby Bluetooth Devices CTA Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showScannerSheet = true },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("open_scanner_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Pindai & Tambah Bluetooth",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Car Head Unit Bluetooth Assignment Card
            item {
                CarBluetoothAssignmentCard(
                    carName = uiState.settings.carBluetoothName,
                    carAddress = uiState.settings.carBluetoothAddress,
                    onClear = onClearCarBluetooth
                )
            }

            // iPhone / iOS Compatibility & Pairing Helper Card
            item {
                IphoneConnectionGuideCard(
                    onOpenBluetoothSettings = onOpenBluetoothSettings
                )
            }

            // Registered Smartphones Section
            item {
                Text(
                    text = "${stringResource(R.string.section_registered_phones)} (${uiState.phones.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (uiState.phones.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Smartphone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "Belum ada perangkat dipantau",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pindai perangkat Bluetooth (HP, jam tangan, tablet, dll.) di bawah atau tekan tombol + MAC.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(
                    items = uiState.phones,
                    key = { "reg_${it.entity.macAddress}" }
                ) { item ->
                    val mac = item.entity.macAddress.uppercase()
                    val isBonded = mac in pairedMacs
                    val isLikelyIphone = AppleBluetoothHelper.isLikelyAppleDevice(item.entity.deviceName) ||
                        AppleBluetoothHelper.isLikelyAppleDevice(item.entity.ownerLabel) ||
                        uiState.btState.discoveredDevices[mac]?.isAppleDevice == true
                    RegisteredPhoneManageCard(
                        entity = item.entity,
                        isBonded = isBonded,
                        isLikelyIphone = isLikelyIphone,
                        onPair = { onPairDevice(item.entity.macAddress) },
                        onPromoteToCentral = {
                            onPromoteToCentral(item.entity.macAddress, item.entity.ownerLabel.ifBlank { item.entity.deviceName })
                        },
                        onTestPing = { onTestPhoneConnection(item.entity.macAddress, item.entity.ownerLabel) },
                        onEdit = { editingPhone = item.entity },
                        onDelete = { onDeletePhone(item.entity.macAddress) }
                    )
                }
            }

            // Bluetooth Discovery & Paired Section
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Temukan Perangkat Bluetooth",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (uiState.isScanningNearby) {
                        FilledTonalButton(
                            onClick = onStopScan,
                            modifier = Modifier.testTag("stop_nearby_scan_button")
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hentikan (${uiState.btState.remainingSeconds}s)")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (!uiState.hasBluetoothPermissions) {
                                    onRequestPermissions()
                                } else {
                                    selectedDiscoveryTab = 1
                                    onStartNearbyDiscovery()
                                }
                            },
                            modifier = Modifier.testTag("scan_nearby_devices_button")
                        ) {
                            Icon(Icons.Rounded.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_scan_nearby))
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(visible = uiState.isScanningNearby) {
                    LinearProgressIndicator(
                        progress = { uiState.btState.scanProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = tokens.radarAccent
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedDiscoveryTab == 0,
                        onClick = {
                            selectedDiscoveryTab = 0
                            onRefreshPaired()
                        },
                        label = { Text("Terpasang (${uiState.btState.pairedDevices.size})") },
                        modifier = Modifier.testTag("tab_paired_devices")
                    )
                    FilterChip(
                        selected = selectedDiscoveryTab == 1,
                        onClick = { selectedDiscoveryTab = 1 },
                        label = { Text("Sekitar (${uiState.nearbyDiscoveredSorted.size})") },
                        modifier = Modifier.testTag("tab_nearby_devices")
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onRefreshPaired,
                        modifier = Modifier.testTag("refresh_paired_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Muat ulang daftar Bluetooth"
                        )
                    }
                }
            }

            val sourceList = if (selectedDiscoveryTab == 0) {
                uiState.btState.pairedDevices
            } else {
                uiState.nearbyDiscoveredSorted
            }

            if (sourceList.isEmpty()) {
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
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = if (selectedDiscoveryTab == 0) {
                                    "Tidak ada perangkat Bluetooth terpasang"
                                } else {
                                    "Belum ada perangkat terdeteksi di sekitar"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (!uiState.hasBluetoothPermissions) {
                                    "Berikan izin Bluetooth terlebih dahulu agar daftar perangkat dapat dibaca."
                                } else if (!uiState.btState.isBluetoothEnabled) {
                                    "Aktifkan Bluetooth pada perangkat ini, lalu tekan Pindai Sekitar."
                                } else {
                                    "Tekan tombol Pindai Sekitar dengan Bluetooth ponsel target dalam keadaan aktif, atau gunakan tombol + MAC."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(
                    items = sourceList,
                    key = { "bt_${selectedDiscoveryTab}_${it.address}" }
                ) { device ->
                    val isRegistered = device.address.uppercase() in registeredMacs
                    val isCarBt = uiState.settings.carBluetoothAddress.equals(device.address, ignoreCase = true)
                    DiscoveredBluetoothDeviceCard(
                        device = device,
                        isRegisteredPhone = isRegistered,
                        isSelectedCarBt = isCarBt,
                        onPair = { onPairDevice(device.address) },
                        onRegisterAsPhone = { pendingRegisterDevice = device },
                        onSelectAsCarBt = { onSetCarBluetooth(device.address, device.name) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // Modal Bottom Sheet: Interactive Nearby Bluetooth Scanner with 1-tap & Batch Save to Room
    if (showScannerSheet) {
        BluetoothScannerModalSheet(
            uiState = uiState,
            registeredMacs = registeredMacs,
            onStartScan = onStartNearbyDiscovery,
            onStopScan = onStopScan,
            onRequestPermissions = onRequestPermissions,
            onRequestEnableBluetooth = onRequestEnableBluetooth,
            onPairDevice = onPairDevice,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
            onQuickAddDevice = onQuickRegisterDevice,
            onCustomizeAddDevice = { device ->
                showScannerSheet = false
                pendingRegisterDevice = device
            },
            onBatchAddDevices = onBatchRegisterDevices,
            onDismiss = { showScannerSheet = false }
        )
    }

    // Dialog: Register discovered device as monitored Bluetooth device
    pendingRegisterDevice?.let { device ->
        val defaultThreshold = if (device.isAppleDevice || AppleBluetoothHelper.isLikelyAppleDevice(device.name)) -95 else -88
        val inferredCategory = PhoneCategory.inferFromDevice(device.name, device.isLikelyCarAudio)
        PhoneRegistrationDialog(
            title = "Daftarkan Perangkat",
            initialMac = device.address,
            macEditable = false,
            initialDeviceName = device.name,
            initialOwnerLabel = device.name,
            initialCategory = inferredCategory,
            initialRssiThreshold = defaultThreshold,
            onDismiss = { pendingRegisterDevice = null },
            onPromoteToCentral = { mac, label ->
                onPromoteToCentral(mac, label)
                pendingRegisterDevice = null
            },
            onConfirm = { mac, devName, owner, cat, threshold ->
                onRegisterOrUpdatePhone(mac, devName, owner, cat, threshold, true)
                pendingRegisterDevice = null
            }
        )
    }

    // Dialog: Edit existing registered device
    editingPhone?.let { existing ->
        PhoneRegistrationDialog(
            title = "Ubah Detail Perangkat",
            initialMac = existing.macAddress,
            macEditable = false,
            initialDeviceName = existing.deviceName,
            initialOwnerLabel = existing.ownerLabel,
            initialCategory = PhoneCategory.fromName(existing.category),
            initialRssiThreshold = existing.rssiThreshold,
            onDismiss = { editingPhone = null },
            onPromoteToCentral = { mac, label ->
                onPromoteToCentral(mac, label)
                editingPhone = null
            },
            onConfirm = { mac, devName, owner, cat, threshold ->
                onRegisterOrUpdatePhone(mac, devName, owner, cat, threshold, existing.isMonitored)
                editingPhone = null
            }
        )
    }

    // Dialog: Manual MAC address entry
    if (showManualDialog) {
        PhoneRegistrationDialog(
            title = "Tambah Perangkat (MAC Manual)",
            initialMac = "",
            macEditable = true,
            initialDeviceName = "",
            initialOwnerLabel = "",
            initialCategory = PhoneCategory.SMARTPHONE,
            initialRssiThreshold = -88,
            onDismiss = { showManualDialog = false },
            onPromoteToCentral = { mac, label ->
                onPromoteToCentral(mac, label)
                showManualDialog = false
            },
            onConfirm = { mac, devName, owner, cat, threshold ->
                onRegisterOrUpdatePhone(mac, devName, owner, cat, threshold, true)
                showManualDialog = false
            }
        )
    }
}

@Composable
private fun CarBluetoothAssignmentCard(
    carName: String,
    carAddress: String,
    onClear: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val hasCarSelected = carAddress.isNotBlank()

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (hasCarSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (hasCarSelected) tokens.radarAccent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Hub,
                    contentDescription = stringResource(R.string.cd_car_icon),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Perangkat Sentral / Pemicu",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (hasCarSelected) {
                    Text(
                        text = carName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "$carAddress • Pemicu scan (tidak dihitung barang bawaan)",
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Mode Otomatis — jadikan head unit mobil (cth: Red Cross) sebagai Sentral agar memicu scan dan tidak ikut dipantau sebagai barang bawaan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (hasCarSelected) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.testTag("clear_car_bt_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Hapus pilihan Perangkat Sentral"
                    )
                }
            }
        }
    }
}

@Composable
private fun IphoneConnectionGuideCard(
    onOpenBluetoothSettings: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tokens.radarAccent.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("iphone_connection_guide_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhoneIphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Mode Kompatibilitas iPhone (iOS)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Pasangkan (Pair) iPhone 1x agar SiagaHP bisa ping BLE GATT + IRK saat layar iPhone terkunci dan alamat BLE berubah.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = onOpenBluetoothSettings,
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_bt_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SettingsBluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Buka Pengaturan Bluetooth (Pairing iPhone)")
            }
        }
    }
}

@Composable
private fun RegisteredPhoneManageCard(
    entity: RegisteredPhoneEntity,
    isBonded: Boolean,
    isLikelyIphone: Boolean,
    onPair: () -> Unit,
    onPromoteToCentral: () -> Unit,
    onTestPing: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val category = PhoneCategory.fromName(entity.category)
    val distanceLabel = formatDistanceMeters(entity.rssiThreshold)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = categoryIcon(category, isLikelyIphone),
                        contentDescription = stringResource(R.string.cd_phone_icon),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = entity.ownerLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isLikelyIphone) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (isBonded) tokens.safeContainer else tokens.warningContainer
                            ) {
                                Text(
                                    text = if (isBonded) "iOS Stabil" else "iOS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isBonded) tokens.onSafeContainer else tokens.onWarningContainer,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${category.label} • ${entity.deviceName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entity.macAddress} • ${entity.rssiThreshold} dBm ($distanceLabel)",
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_phone_${entity.macAddress}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.cd_edit_device)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_phone_${entity.macAddress}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.cd_delete_device),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (isLikelyIphone && !isBonded) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = tokens.warningContainer,
                    border = BorderStroke(1.dp, tokens.warning.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = tokens.warning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "iPhone belum dipasangkan — pasangkan agar BLE Keep-Alive & deteksi stabil saat layar terkunci.",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.onWarningContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onPair,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("pair_registered_${entity.macAddress}")
                        ) {
                            Text("Pasangkan")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPromoteToCentral,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("promote_central_${entity.macAddress}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Jadikan Sentral",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onTestPing,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ping_phone_${entity.macAddress}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isLikelyIphone) "Uji Ping iPhone" else "Uji Ping",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveredBluetoothDeviceCard(
    device: ScannedBluetoothDevice,
    isRegisteredPhone: Boolean,
    isSelectedCarBt: Boolean,
    onPair: () -> Unit,
    onRegisterAsPhone: () -> Unit,
    onSelectAsCarBt: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            when {
                isRegisteredPhone -> tokens.safe.copy(alpha = 0.5f)
                isSelectedCarBt -> tokens.radarAccent.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val guessedCategory = PhoneCategory.inferFromDevice(device.name, device.isLikelyCarAudio)
                    Icon(
                        imageVector = categoryIcon(guessedCategory, device.isAppleDevice),
                        contentDescription = stringResource(R.string.cd_bluetooth_icon),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (device.isAppleDevice) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = if (device.isBonded) "iOS Paired" else "iPhone / Apple",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = if (device.rssi != null) {
                            "${device.address} • ${formatDistanceMeters(device.rssi)}"
                        } else {
                            device.address
                        },
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (device.rssi != null) {
                    SignalStrengthIndicator(rssi = device.rssi)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onRegisterAsPhone,
                    enabled = !isRegisteredPhone && !isSelectedCarBt,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("register_device_${device.address}")
                ) {
                    Icon(
                        imageVector = if (isRegisteredPhone) Icons.Rounded.Check else Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRegisteredPhone) "Dipantau" else stringResource(R.string.action_register_phone),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!device.isBonded && device.isAppleDevice) {
                    OutlinedButton(
                        onClick = onPair,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pair_device_${device.address}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Pasangkan",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onSelectAsCarBt,
                        enabled = !isSelectedCarBt,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("set_car_bt_${device.address}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSelectedCarBt) "Sentral ✓" else stringResource(R.string.action_set_car_bt),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneRegistrationDialog(
    title: String,
    initialMac: String,
    macEditable: Boolean,
    initialDeviceName: String,
    initialOwnerLabel: String,
    initialCategory: PhoneCategory,
    initialRssiThreshold: Int,
    onDismiss: () -> Unit,
    onPromoteToCentral: ((mac: String, label: String) -> Unit)? = null,
    onConfirm: (
        mac: String,
        deviceName: String,
        ownerLabel: String,
        category: PhoneCategory,
        rssiThreshold: Int
    ) -> Unit
) {
    var macInput by remember { mutableStateOf(initialMac) }
    var deviceNameInput by remember { mutableStateOf(initialDeviceName) }
    var ownerLabelInput by remember { mutableStateOf(initialOwnerLabel) }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var rssiThreshold by remember { mutableIntStateOf(initialRssiThreshold) }
    var macError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ownerLabelInput,
                    onValueChange = { ownerLabelInput = it },
                    label = { Text("Label Perangkat (cth: HP Saya / Jam Huawei)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_owner_label_input")
                )

                if (macEditable) {
                    OutlinedTextField(
                        value = deviceNameInput,
                        onValueChange = { deviceNameInput = it },
                        label = { Text("Nama Perangkat Bluetooth") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_device_name_input")
                    )
                    OutlinedTextField(
                        value = macInput,
                        onValueChange = {
                            macInput = it.uppercase()
                            macError = null
                        },
                        label = { Text("Alamat MAC Bluetooth") },
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                        isError = macError != null,
                        supportingText = macError?.let { err -> { Text(err) } },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_mac_input")
                    )
                } else {
                    Text(
                        text = "MAC: $macInput",
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Kategori Perangkat",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PhoneCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            leadingIcon = {
                                Icon(
                                    imageVector = categoryIcon(cat),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text(cat.label) }
                        )
                    }
                }

                if (selectedCategory == PhoneCategory.CAR_HEADUNIT && onPromoteToCentral != null) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Head unit mobil sebaiknya dijadikan Perangkat Sentral (pemicu scan), bukan barang bawaan yang dipantau.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            TextButton(
                                onClick = {
                                    val normalizedMac = RegisteredPhoneEntity.normalizeMacAddress(macInput)
                                    if (macEditable && !RegisteredPhoneEntity.isValidMacAddress(normalizedMac)) {
                                        macError = "Format MAC harus seperti AA:BB:CC:11:22:33"
                                        return@TextButton
                                    }
                                    val finalLabel = ownerLabelInput.trim().ifBlank {
                                        deviceNameInput.trim().ifBlank { "Perangkat Sentral" }
                                    }
                                    onPromoteToCentral(normalizedMac, finalLabel)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("dialog_promote_central_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Hub,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Jadikan Perangkat Sentral")
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Radius Jarak Sentral",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$rssiThreshold dBm • ${formatDistanceMeters(rssiThreshold)}",
                            style = TelemetryMonoStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "${formatRssiQuality(rssiThreshold)} — jarak dari titik sentral (mobil, tas, atau hub), tidak harus selalu di dalam kabin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = rssiThreshold.toFloat(),
                        onValueChange = { rssiThreshold = it.roundToInt() },
                        valueRange = -98f..-55f
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalizedMac = RegisteredPhoneEntity.normalizeMacAddress(macInput)
                    if (macEditable && !RegisteredPhoneEntity.isValidMacAddress(normalizedMac)) {
                        macError = "Format MAC harus seperti AA:BB:CC:11:22:33"
                        return@Button
                    }
                    val finalLabel = ownerLabelInput.trim().ifBlank {
                        deviceNameInput.trim().ifBlank { selectedCategory.label }
                    }
                    val finalDeviceName = deviceNameInput.trim().ifBlank { finalLabel }
                    onConfirm(normalizedMac, finalDeviceName, finalLabel, selectedCategory, rssiThreshold)
                },
                modifier = Modifier.testTag("dialog_save_phone_button")
            ) {
                Text("Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
