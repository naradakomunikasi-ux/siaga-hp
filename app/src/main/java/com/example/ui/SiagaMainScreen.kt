package com.example.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.bluetooth.BluetoothPermissionHelper
import com.example.ui.components.AboutAppDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DevicesScreen
import com.example.ui.screens.RulesAndLogsScreen

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_DEVICES = "devices"
const val ROUTE_RULES = "rules"

private data class SiagaNavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val SiagaDestinations = listOf(
    SiagaNavDestination(
        route = ROUTE_DASHBOARD,
        labelRes = R.string.nav_dashboard,
        selectedIcon = Icons.Rounded.Radar,
        unselectedIcon = Icons.Outlined.Radar,
        testTag = "nav_tab_dashboard"
    ),
    SiagaNavDestination(
        route = ROUTE_DEVICES,
        labelRes = R.string.nav_devices,
        selectedIcon = Icons.Rounded.Devices,
        unselectedIcon = Icons.Outlined.Devices,
        testTag = "nav_tab_devices"
    ),
    SiagaNavDestination(
        route = ROUTE_RULES,
        labelRes = R.string.nav_rules,
        selectedIcon = Icons.Rounded.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_tab_rules"
    )
)

@Composable
fun SiagaMainScreen(
    viewModel: SiagaViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_DASHBOARD
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAboutDialog by remember { mutableStateOf(false) }

    // Permission launcher for Bluetooth + Notification permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionsUpdated()
    }

    // System Bluetooth Enable launcher
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshBluetoothState()
    }

    // Refresh Bluetooth state & service whenever app returns to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBluetoothState()
                viewModel.syncMonitoringServiceWithSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Surface engine status messages via Snackbar
    LaunchedEffect(uiState.btState.statusMessage) {
        val msg = uiState.btState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    val navigateToTab: (String) -> Unit = { route ->
        if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val requestAllPermissions: () -> Unit = {
        permissionLauncher.launch(BluetoothPermissionHelper.allRequiredPermissions())
    }

    val requestEnableBluetooth: () -> Unit = {
        if (!uiState.hasBluetoothPermissions) {
            requestAllPermissions()
        } else {
            try {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                viewModel.refreshBluetoothState()
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        SiagaDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(text = stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.statusBarsPadding()
                    ) {
                        SiagaDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(text = stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_DASHBOARD,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(ROUTE_DASHBOARD) {
                            DashboardScreen(
                                uiState = uiState,
                                onRequestPermissions = requestAllPermissions,
                                onRequestEnableBluetooth = requestEnableBluetooth,
                                onScanCabinNow = {
                                    if (!uiState.hasBluetoothPermissions) {
                                        requestAllPermissions()
                                    } else {
                                        viewModel.startManualCabinCheck()
                                    }
                                },
                                onRunIgnitionCheck = {
                                    if (!uiState.hasBluetoothPermissions) {
                                        requestAllPermissions()
                                    } else {
                                        viewModel.startIgnitionTriggeredCheck()
                                    }
                                },
                                onStopScan = viewModel::stopScanning,
                                onToggleBackgroundService = { enabled ->
                                    if (enabled && !uiState.hasBluetoothPermissions) {
                                        requestAllPermissions()
                                    }
                                    viewModel.setAutoMonitorEnabled(enabled)
                                },
                                onTogglePhoneMonitored = viewModel::togglePhoneMonitored,
                                onNavigateToDevices = { navigateToTab(ROUTE_DEVICES) },
                                onNavigateToRules = { navigateToTab(ROUTE_RULES) },
                                onOpenAbout = { showAboutDialog = true }
                            )
                        }

                        composable(ROUTE_DEVICES) {
                            DevicesScreen(
                                uiState = uiState,
                                onStartNearbyDiscovery = viewModel::startNearbyDiscovery,
                                onStopScan = viewModel::stopScanning,
                                onRefreshPaired = viewModel::refreshBluetoothState,
                                onRegisterOrUpdatePhone = viewModel::registerOrUpdatePhone,
                                onQuickRegisterDevice = viewModel::quickRegisterScannedDevice,
                                onBatchRegisterDevices = viewModel::registerMultipleScannedDevices,
                                onDeletePhone = viewModel::deletePhone,
                                onSetCarBluetooth = viewModel::setCarBluetoothDevice,
                                onClearCarBluetooth = viewModel::clearCarBluetoothDevice,
                                onRequestPermissions = requestAllPermissions,
                                onRequestEnableBluetooth = requestEnableBluetooth
                            )
                        }

                        composable(ROUTE_RULES) {
                            RulesAndLogsScreen(
                                uiState = uiState,
                                onToggleAutoMonitor = { enabled ->
                                    if (enabled && !uiState.hasBluetoothPermissions) {
                                        requestAllPermissions()
                                    }
                                    viewModel.setAutoMonitorEnabled(enabled)
                                },
                                onTogglePeriodicScan = viewModel::setPeriodicBackgroundScanEnabled,
                                onSelectScanPowerProfile = viewModel::setScanPowerProfile,
                                onSelectPeriodicInterval = viewModel::setPeriodicScanIntervalSeconds,
                                onToggleCarBtTrigger = viewModel::setTriggerOnCarBluetooth,
                                onTogglePowerTrigger = viewModel::setTriggerOnPowerConnected,
                                onScanDurationChange = viewModel::setScanDurationSeconds,
                                onToggleVoiceAlert = viewModel::setVoiceAlertEnabled,
                                onToggleVibrateAlert = viewModel::setVibrateAlertEnabled,
                                onToggleSafeNotif = viewModel::setNotifyWhenAllSafe,
                                onSelectThemeMode = viewModel::setThemeMode,
                                onTestAlert = {
                                    if (!uiState.hasNotificationPermission) {
                                        requestAllPermissions()
                                    }
                                    viewModel.triggerTestNotificationAlert()
                                },
                                onClearLogs = viewModel::clearCheckLogs,
                                onOpenAbout = { showAboutDialog = true }
                            )
                        }
                    }
                }
            }
        }

        if (showAboutDialog) {
            AboutAppDialog(onDismiss = { showAboutDialog = false })
        }
    }
}
