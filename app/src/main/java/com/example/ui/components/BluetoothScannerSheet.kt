package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bluetooth.ScannedBluetoothDevice
import com.example.ui.SiagaUiState
import com.example.ui.theme.SiagaThemeTokens
import com.example.ui.theme.TelemetryMonoStyle

private enum class ScannerSourceFilter(val label: String) {
    ALL("Semua"),
    NEARBY("Sekitar"),
    PAIRED("Terpasang")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothScannerModalSheet(
    uiState: SiagaUiState,
    registeredMacs: Set<String>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onPairDevice: (String) -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {},
    onQuickAddDevice: (ScannedBluetoothDevice) -> Unit,
    onCustomizeAddDevice: (ScannedBluetoothDevice) -> Unit,
    onBatchAddDevices: (List<ScannedBluetoothDevice>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Automatically trigger nearby discovery when sheet opens and BT is ready
    LaunchedEffect(Unit) {
        if (uiState.hasBluetoothPermissions && uiState.btState.isBluetoothEnabled && !uiState.isScanningNearby) {
            onStartScan()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (uiState.isScanningNearby) onStopScan()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        BluetoothScannerSheetContent(
            uiState = uiState,
            registeredMacs = registeredMacs,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onRequestPermissions = onRequestPermissions,
            onRequestEnableBluetooth = onRequestEnableBluetooth,
            onPairDevice = onPairDevice,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
            onQuickAddDevice = onQuickAddDevice,
            onCustomizeAddDevice = onCustomizeAddDevice,
            onBatchAddDevices = { selected ->
                onBatchAddDevices(selected)
                onDismiss()
            },
            onClose = {
                if (uiState.isScanningNearby) onStopScan()
                onDismiss()
            }
        )
    }
}

@Composable
fun BluetoothScannerSheetContent(
    uiState: SiagaUiState,
    registeredMacs: Set<String>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onQuickAddDevice: (ScannedBluetoothDevice) -> Unit,
    onCustomizeAddDevice: (ScannedBluetoothDevice) -> Unit,
    onBatchAddDevices: (List<ScannedBluetoothDevice>) -> Unit,
    onClose: () -> Unit,
    onPairDevice: (String) -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = SiagaThemeTokens.colors
    var searchQuery by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf(ScannerSourceFilter.ALL) }
    var hideUnnamed by remember { mutableStateOf(false) }
    var selectedMacs by remember { mutableStateOf<Set<String>>(emptySet()) }

    val combinedDevices = remember(
        uiState.nearbyDiscoveredSorted,
        uiState.btState.pairedDevices,
        sourceFilter
    ) {
        val map = LinkedHashMap<String, ScannedBluetoothDevice>()
        when (sourceFilter) {
            ScannerSourceFilter.NEARBY -> {
                uiState.nearbyDiscoveredSorted.forEach { map[it.address.uppercase()] = it }
            }
            ScannerSourceFilter.PAIRED -> {
                uiState.btState.pairedDevices.forEach { map[it.address.uppercase()] = it }
            }
            ScannerSourceFilter.ALL -> {
                uiState.nearbyDiscoveredSorted.forEach { map[it.address.uppercase()] = it }
                uiState.btState.pairedDevices.forEach { dev ->
                    val key = dev.address.uppercase()
                    if (!map.containsKey(key)) {
                        map[key] = dev
                    }
                }
            }
        }
        map.values.sortedWith(
            compareByDescending<ScannedBluetoothDevice> { it.isConnected }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.name }
        )
    }

    val filteredDevices = remember(combinedDevices, searchQuery, hideUnnamed) {
        val q = searchQuery.trim().lowercase()
        combinedDevices.filter { dev ->
            val matchesNameRule = !hideUnnamed || (dev.name.isNotBlank() && dev.name != "Perangkat Bluetooth")
            val matchesQuery = q.isEmpty() ||
                dev.name.lowercase().contains(q) ||
                dev.address.lowercase().contains(q)
            matchesNameRule && matchesQuery
        }
    }

    // Keep selected set pruned to unregistered visible/known devices
    val validSelectedDevices = remember(combinedDevices, selectedMacs, registeredMacs) {
        combinedDevices.filter { dev ->
            val mac = dev.address.uppercase()
            mac in selectedMacs && mac !in registeredMacs
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .testTag("bluetooth_scanner_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pindai Bluetooth Sekitar",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Pilih perangkat terdekat untuk disimpan ke database pemantauan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("scanner_close_button")
            ) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Tutup pemindai")
            }
        }

        // Permission or BT Disabled Recovery Banner
        if (!uiState.hasBluetoothPermissions) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = tokens.warningContainer,
                border = BorderStroke(1.dp, tokens.warning.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = tokens.warning)
                    Text(
                        text = "Izin Bluetooth diperlukan untuk memindai perangkat sekitar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.onWarningContainer,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.testTag("scanner_grant_perm_button")
                    ) {
                        Text("Izinkan")
                    }
                }
            }
        } else if (!uiState.btState.isBluetoothEnabled) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = tokens.warningContainer,
                border = BorderStroke(1.dp, tokens.warning.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.BluetoothDisabled, contentDescription = null, tint = tokens.warning)
                    Text(
                        text = "Bluetooth sedang nonaktif. Nyalakan untuk memulai pemindaian.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.onWarningContainer,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = onRequestEnableBluetooth,
                        modifier = Modifier.testTag("scanner_enable_bt_button")
                    ) {
                        Text("Aktifkan")
                    }
                }
            }
        }

        // Active Scan Control Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.isScanningNearby) {
                FilledTonalButton(
                    onClick = onStopScan,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("scanner_stop_button")
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Memindai… Hentikan (${uiState.btState.remainingSeconds}d)")
                }
            } else {
                Button(
                    onClick = {
                        if (!uiState.hasBluetoothPermissions) onRequestPermissions()
                        else if (!uiState.btState.isBluetoothEnabled) onRequestEnableBluetooth()
                        else onStartScan()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("scanner_start_button")
                ) {
                    Icon(Icons.Rounded.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pindai Ulang Sekitar")
                }
            }
        }

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

        // Search input by name or MAC address
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Cari nama perangkat atau MAC (AA:BB:...)") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Hapus pencarian")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scanner_search_input")
        )

        // Source & Unnamed Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScannerSourceFilter.entries.forEach { filter ->
                FilterChip(
                    selected = sourceFilter == filter,
                    onClick = { sourceFilter = filter },
                    label = { Text(filter.label) },
                    modifier = Modifier.testTag("scanner_filter_${filter.name.lowercase()}")
                )
            }
            FilterChip(
                selected = hideUnnamed,
                onClick = { hideUnnamed = !hideUnnamed },
                label = { Text("Bernama") },
                modifier = Modifier.testTag("scanner_filter_named_only")
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Discovered Device List
        if (filteredDevices.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = if (uiState.isScanningNearby) {
                            "Mencari perangkat Bluetooth di sekitar…"
                        } else {
                            "Tidak ada perangkat yang cocok"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Pastikan Bluetooth pada smartphone target dalam keadaan menyala agar sinyalnya terdeteksi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .testTag("scanner_results_list"),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = filteredDevices,
                    key = { it.address.uppercase() }
                ) { device ->
                    val mac = device.address.uppercase()
                    val isAlreadySaved = mac in registeredMacs
                    val isSelected = mac in selectedMacs

                    ScannerDeviceRow(
                        device = device,
                        isAlreadySaved = isAlreadySaved,
                        isSelectedForBatch = isSelected,
                        onToggleBatchSelect = { checked ->
                            selectedMacs = if (checked) selectedMacs + mac else selectedMacs - mac
                        },
                        onPair = { onPairDevice(device.address) },
                        onQuickAdd = {
                            onQuickAddDevice(device)
                            selectedMacs = selectedMacs - mac
                        },
                        onCustomizeAdd = { onCustomizeAddDevice(device) }
                    )
                }
            }
        }

        // Batch Save Sticky Action Bar when 1+ devices are checked
        AnimatedVisibility(visible = validSelectedDevices.isNotEmpty()) {
            Button(
                onClick = { onBatchAddDevices(validSelectedDevices) },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("batch_save_devices_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Simpan ${validSelectedDevices.size} Perangkat ke Database",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ScannerDeviceRow(
    device: ScannedBluetoothDevice,
    isAlreadySaved: Boolean,
    isSelectedForBatch: Boolean,
    onToggleBatchSelect: (Boolean) -> Unit,
    onPair: () -> Unit,
    onQuickAdd: () -> Unit,
    onCustomizeAdd: () -> Unit
) {
    val tokens = SiagaThemeTokens.colors
    val mac = device.address.uppercase()

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            when {
                isAlreadySaved -> tokens.safe.copy(alpha = 0.55f)
                isSelectedForBatch -> tokens.radarAccent.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scanner_device_row_$mac")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isAlreadySaved) {
                    Checkbox(
                        checked = isSelectedForBatch,
                        onCheckedChange = onToggleBatchSelect,
                        modifier = Modifier.testTag("scanner_checkbox_$mac")
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.safeContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Sudah tersimpan",
                            tint = tokens.safe,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                device.isLikelyCarAudio -> Icons.Rounded.DirectionsCar
                                device.isAppleDevice -> Icons.Rounded.PhoneIphone
                                device.isConnected -> Icons.Rounded.BluetoothConnected
                                else -> Icons.Rounded.Bluetooth
                            },
                            contentDescription = null,
                            tint = if (device.isConnected) tokens.safe else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleSmall,
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
                                    text = if (device.isBonded) "iOS Paired" else "iPhone",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = mac,
                        style = TelemetryMonoStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (device.rssi != null) {
                        Text(
                            text = formatRssiQuality(device.rssi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (device.rssi != null) {
                    SignalStrengthIndicator(rssi = device.rssi)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (!device.isBonded && device.isAppleDevice) {
                    OutlinedButton(
                        onClick = onPair,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("scanner_pair_device_$mac")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pasangkan", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                if (isAlreadySaved) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = tokens.safeContainer
                    ) {
                        Text(
                            text = "Tersimpan di Database ✓",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = tokens.onSafeContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onCustomizeAdd,
                        modifier = Modifier.testTag("customize_add_device_$mac")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Ubah nama sebelum simpan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = onQuickAdd,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("quick_add_device_$mac")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Simpan", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
