package com.everystreet.survey.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.everystreet.survey.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppSettings(
    val voiceGuidance: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showSpeedometer: Boolean = false,
    val offRouteAlerts: Boolean = true,
    val autoRecenter: Boolean = true,
    val mapStyle: String = "standard",
    val distanceUnit: String = "km",
    val averageSpeed: Float = 30f // km/h
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val routeDao: SurveyRouteDao
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _routeCount = MutableStateFlow(0)
    val routeCount: StateFlow<Int> = _routeCount.asStateFlow()

    private val _totalDistance = MutableStateFlow(0.0)
    val totalDistance: StateFlow<Double> = _totalDistance.asStateFlow()

    init {
        viewModelScope.launch {
            routeDao.getAllRoutes().collect { routes ->
                _routeCount.value = routes.size
                _totalDistance.value = routes.sumOf { it.totalDistance }
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        // In a real app, persist to DataStore
    }

    fun clearAllData(onComplete: () -> Unit) {
        viewModelScope.launch {
            routeDao.getAllRoutes().first().forEach { route ->
                routeDao.deleteRoute(route)
            }
            onComplete()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val routeCount by viewModel.routeCount.collectAsState()
    val totalDistance by viewModel.totalDistance.collectAsState()

    var showClearDataDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Navigation Settings
            SettingsSection(title = "Navigation") {
                SwitchSettingItem(
                    icon = Icons.Default.VolumeUp,
                    title = "Voice Guidance",
                    subtitle = "Announce turn-by-turn instructions",
                    checked = settings.voiceGuidance,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(voiceGuidance = it))
                    }
                )

                SwitchSettingItem(
                    icon = Icons.Default.WbSunny,
                    title = "Keep Screen On",
                    subtitle = "Prevent screen from turning off during navigation",
                    checked = settings.keepScreenOn,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(keepScreenOn = it))
                    }
                )

                SwitchSettingItem(
                    icon = Icons.Default.Warning,
                    title = "Off-Route Alerts",
                    subtitle = "Alert when you leave the planned route",
                    checked = settings.offRouteAlerts,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(offRouteAlerts = it))
                    }
                )

                SwitchSettingItem(
                    icon = Icons.Default.CenterFocusStrong,
                    title = "Auto Recenter",
                    subtitle = "Automatically center map on your location",
                    checked = settings.autoRecenter,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(autoRecenter = it))
                    }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Route Calculation Settings
            SettingsSection(title = "Route Calculation") {
                ClickableSettingItem(
                    icon = Icons.Default.Speed,
                    title = "Average Speed",
                    subtitle = "${settings.averageSpeed.toInt()} km/h",
                    onClick = { showSpeedDialog = true }
                )

                ClickableSettingItem(
                    icon = Icons.Default.Straighten,
                    title = "Distance Unit",
                    subtitle = if (settings.distanceUnit == "km") "Kilometers" else "Miles",
                    onClick = {
                        val newUnit = if (settings.distanceUnit == "km") "mi" else "km"
                        viewModel.updateSettings(settings.copy(distanceUnit = newUnit))
                    }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Statistics
            SettingsSection(title = "Statistics") {
                StatSettingItem(
                    icon = Icons.Default.Route,
                    title = "Total Routes",
                    value = "$routeCount"
                )
                StatSettingItem(
                    icon = Icons.Default.Straighten,
                    title = "Total Distance",
                    value = formatDistance(totalDistance)
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Data Management
            SettingsSection(title = "Data") {
                ClickableSettingItem(
                    icon = Icons.Default.Delete,
                    title = "Clear All Data",
                    subtitle = "Delete all routes and progress",
                    onClick = { showClearDataDialog = true },
                    isDestructive = true
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // About
            SettingsSection(title = "About") {
                StatSettingItem(
                    icon = Icons.Default.Info,
                    title = "Version",
                    value = "1.0.0"
                )
                ClickableSettingItem(
                    icon = Icons.Default.Policy,
                    title = "Privacy Policy",
                    subtitle = null,
                    onClick = { /* Open privacy policy */ }
                )
                ClickableSettingItem(
                    icon = Icons.Default.Article,
                    title = "Open Source Licenses",
                    subtitle = null,
                    onClick = { /* Open licenses */ }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Clear data confirmation dialog
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Clear All Data?") },
            text = { Text("This will permanently delete all your routes and progress. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllData {
                            showClearDataDialog = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Speed selection dialog
    if (showSpeedDialog) {
        var sliderValue by remember { mutableStateOf(settings.averageSpeed) }

        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Average Speed") },
            text = {
                Column {
                    Text("Set the average driving speed for time estimates")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "${sliderValue.toInt()} km/h",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 10f..60f,
                        steps = 9
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateSettings(settings.copy(averageSpeed = sliderValue))
                        showSpeedDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
fun SwitchSettingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
fun ClickableSettingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun StatSettingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

private fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        "${meters.toInt()} m"
    } else {
        String.format("%.1f km", meters / 1000)
    }
}

private fun androidx.compose.ui.Modifier.clickable(onClick: () -> Unit): Modifier {
    return this.then(androidx.compose.foundation.clickable { onClick() })
}
