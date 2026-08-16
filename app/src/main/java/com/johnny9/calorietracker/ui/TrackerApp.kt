package com.johnny9.calorietracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.health.connect.client.PermissionController
import com.johnny9.calorietracker.AppViewModel
import com.johnny9.calorietracker.health.HealthConnectManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class AppTab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Default.Restaurant),
    FOODS("Foods", Icons.Default.Fastfood),
    TRENDS("Trends", Icons.Default.BarChart),
    FASTING("Fasting", Icons.Default.Timer),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun TrackerApp(viewModel: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    val snackbar = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsState()
    val isWorking by viewModel.isWorking.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::export) }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.onHealthPermissionResult() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            AppTab.TODAY -> TodayScreen(viewModel, padding, onOpenSettings = { tab = AppTab.SETTINGS })
            AppTab.FOODS -> FoodsScreen(viewModel, padding)
            AppTab.TRENDS -> TrendsScreen(viewModel, padding)
            AppTab.FASTING -> FastingScreen(viewModel, padding)
            AppTab.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                padding = padding,
                onRequestHealthPermission = {
                    healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                },
                onExport = {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                    exportLauncher.launch("calorie-tracker-export-$stamp.zip")
                },
            )
        }
        if (isWorking) LinearProgressIndicator(modifier = Modifier)
    }
}
