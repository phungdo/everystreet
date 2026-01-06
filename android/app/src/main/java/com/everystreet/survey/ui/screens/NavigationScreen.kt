package com.everystreet.survey.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.everystreet.survey.algorithm.NavigationInstructionGenerator
import com.everystreet.survey.data.*
import com.everystreet.survey.service.LocationService
import com.everystreet.survey.service.NavigationService
import com.everystreet.survey.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val routeDao: SurveyRouteDao,
    private val progressDao: SurveyProgressDao,
    private val locationService: LocationService
) : ViewModel() {

    private val _route = MutableStateFlow<SurveyRoute?>(null)
    val route: StateFlow<SurveyRoute?> = _route.asStateFlow()

    private val _progress = MutableStateFlow<SurveyProgress?>(null)
    val progress: StateFlow<SurveyProgress?> = _progress.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _currentInstructionIndex = MutableStateFlow(0)
    val currentInstructionIndex: StateFlow<Int> = _currentInstructionIndex.asStateFlow()

    val instructionGenerator = NavigationInstructionGenerator()

    fun loadRoute(routeId: Long) {
        viewModelScope.launch {
            _route.value = routeDao.getRouteById(routeId)
            _progress.value = progressDao.getProgress(routeId)
        }
    }

    fun startNavigation() {
        _isNavigating.value = true
        locationService.startNavigationTracking()

        viewModelScope.launch {
            locationService.locationUpdates.collect { location ->
                _currentLocation.value = location
                updateProgress(location)
            }
        }
    }

    fun pauseNavigation() {
        _isNavigating.value = false
        locationService.stopTracking()
    }

    fun resumeNavigation() {
        _isNavigating.value = true
        locationService.startNavigationTracking()
    }

    fun stopNavigation() {
        _isNavigating.value = false
        locationService.stopTracking()
    }

    private fun updateProgress(location: LatLng) {
        val currentRoute = _route.value ?: return
        val currentProgress = _progress.value ?: return

        // Find closest instruction
        var closestIndex = 0
        var closestDistance = Double.MAX_VALUE

        currentRoute.instructions.forEachIndexed { index, instruction ->
            val distance = location.distanceTo(instruction.location)
            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = index
            }
        }

        _currentInstructionIndex.value = closestIndex

        // Update progress in database
        viewModelScope.launch {
            val (routeIndex, _) = locationService.findClosestRoutePoint(
                location,
                currentRoute.routePath
            )

            val distanceCovered = calculateDistanceCovered(currentRoute.routePath, routeIndex)

            progressDao.updateProgressState(
                routeId = currentProgress.routeId,
                edgeIndex = routeIndex,
                completed = (routeIndex * currentProgress.totalEdges / currentRoute.routePath.size),
                distance = distanceCovered
            )
        }
    }

    private fun calculateDistanceCovered(path: List<LatLng>, upToIndex: Int): Double {
        if (upToIndex <= 0) return 0.0
        var distance = 0.0
        for (i in 0 until minOf(upToIndex, path.size - 1)) {
            distance += path[i].distanceTo(path[i + 1])
        }
        return distance
    }

    fun updateRouteStatus(status: RouteStatus) {
        viewModelScope.launch {
            _route.value?.let { route ->
                routeDao.updateRouteStatus(route.id, status)
                _route.value = route.copy(status = status)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    routeId: Long,
    viewModel: NavigationViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val route by viewModel.route.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val isNavigating by viewModel.isNavigating.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val currentInstructionIndex by viewModel.currentInstructionIndex.collectAsState()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var showStopDialog by remember { mutableStateOf(false) }

    LaunchedEffect(routeId) {
        viewModel.loadRoute(routeId)
    }

    // Update map when location changes
    LaunchedEffect(currentLocation) {
        currentLocation?.let { location ->
            mapView?.let { map ->
                updateLocationMarker(map, location)
                if (isNavigating) {
                    map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route?.name ?: "Navigation") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isNavigating) {
                            showStopDialog = true
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            currentLocation?.let { location ->
                                mapView?.controller?.animateTo(
                                    GeoPoint(location.latitude, location.longitude)
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Center on location")
                    }
                }
            )
        }
    ) { paddingValues ->
        route?.let { currentRoute ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Map View
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(17.0)

                            // Draw route
                            drawRoute(this, currentRoute.routePath)

                            // Center on first point
                            if (currentRoute.routePath.isNotEmpty()) {
                                val firstPoint = currentRoute.routePath.first()
                                controller.setCenter(GeoPoint(firstPoint.latitude, firstPoint.longitude))
                            }

                            mapView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Navigation Panel
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Current instruction card at top
                    if (currentRoute.instructions.isNotEmpty()) {
                        val currentInstruction = currentRoute.instructions.getOrNull(currentInstructionIndex)
                        val nextInstruction = currentRoute.instructions.getOrNull(currentInstructionIndex + 1)

                        InstructionCard(
                            currentInstruction = currentInstruction,
                            nextInstruction = nextInstruction,
                            instructionGenerator = viewModel.instructionGenerator
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Bottom control panel
                    NavigationControlPanel(
                        route = currentRoute,
                        progress = progress,
                        isNavigating = isNavigating,
                        onStart = {
                            viewModel.startNavigation()
                            viewModel.updateRouteStatus(RouteStatus.IN_PROGRESS)
                        },
                        onPause = { viewModel.pauseNavigation() },
                        onResume = { viewModel.resumeNavigation() },
                        onStop = { showStopDialog = true }
                    )
                }
            }
        } ?: Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }

    // Stop navigation dialog
    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Stop Navigation?") },
            text = { Text("Your progress will be saved. You can resume this survey later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.stopNavigation()
                        viewModel.updateRouteStatus(RouteStatus.PAUSED)
                        showStopDialog = false
                        onBack()
                    }
                ) {
                    Text("Stop")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text("Continue")
                }
            }
        )
    }
}

@Composable
fun InstructionCard(
    currentInstruction: NavigationInstruction?,
    nextInstruction: NavigationInstruction?,
    instructionGenerator: NavigationInstructionGenerator
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Current instruction
            currentInstruction?.let { instruction ->
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Direction icon
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getInstructionIcon(instruction.type),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = instructionGenerator.getShortInstructionText(instruction),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        instruction.streetName?.let { street ->
                            Text(
                                text = street,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Distance
                    Text(
                        text = instructionGenerator.formatDistance(instruction.distance),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Next instruction preview
            nextInstruction?.let { next ->
                Divider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Then",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = getInstructionIcon(next.type),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = next.streetName ?: instructionGenerator.getShortInstructionText(next),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun NavigationControlPanel(
    route: SurveyRoute,
    progress: SurveyProgress?,
    isNavigating: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val instructionGenerator = NavigationInstructionGenerator()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Progress bar
            progress?.let { p ->
                val progressPercent = if (p.totalDistance > 0) {
                    (p.distanceCovered / p.totalDistance).toFloat()
                } else 0f

                LinearProgressIndicator(
                    progress = { progressPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(progressPercent * 100).toInt()}% Complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${p.completedEdges}/${p.totalEdges} streets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Distance",
                    value = instructionGenerator.formatDistance(
                        progress?.let { route.totalDistance - it.distanceCovered } ?: route.totalDistance
                    )
                )
                StatItem(
                    label = "Time",
                    value = instructionGenerator.formatTime(
                        progress?.let {
                            instructionGenerator.estimateTravelTime(route.totalDistance - it.distanceCovered)
                        } ?: route.estimatedTime
                    )
                )
                StatItem(
                    label = "Streets",
                    value = "${route.edgeIds.size}"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isNavigating) {
                    Button(
                        onClick = if (progress?.isPaused == true) onResume else onStart,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (progress?.isPaused == true) "Resume" else "Start")
                    }
                } else {
                    OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pause")
                    }
                }

                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun getInstructionIcon(type: InstructionType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        InstructionType.START -> Icons.Default.PlayArrow
        InstructionType.CONTINUE -> Icons.Default.ArrowUpward
        InstructionType.TURN_LEFT -> Icons.Default.TurnLeft
        InstructionType.TURN_RIGHT -> Icons.Default.TurnRight
        InstructionType.SLIGHT_LEFT -> Icons.Default.TurnSlightLeft
        InstructionType.SLIGHT_RIGHT -> Icons.Default.TurnSlightRight
        InstructionType.SHARP_LEFT -> Icons.Default.TurnSharpLeft
        InstructionType.SHARP_RIGHT -> Icons.Default.TurnSharpRight
        InstructionType.U_TURN -> Icons.Default.UTurnLeft
        InstructionType.ARRIVED -> Icons.Default.Flag
    }
}

private fun drawRoute(mapView: MapView, routePath: List<LatLng>) {
    val polyline = Polyline().apply {
        outlinePaint.color = 0xFF2196F3.toInt()
        outlinePaint.strokeWidth = 10f

        val geoPoints = routePath.map { GeoPoint(it.latitude, it.longitude) }
        setPoints(geoPoints)
    }

    mapView.overlays.add(polyline)

    // Add start marker
    if (routePath.isNotEmpty()) {
        val startMarker = Marker(mapView).apply {
            position = GeoPoint(routePath.first().latitude, routePath.first().longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Start"
        }
        mapView.overlays.add(startMarker)

        // Add end marker
        val endMarker = Marker(mapView).apply {
            position = GeoPoint(routePath.last().latitude, routePath.last().longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "End"
        }
        mapView.overlays.add(endMarker)
    }

    mapView.invalidate()
}

private fun updateLocationMarker(mapView: MapView, location: LatLng) {
    // Remove existing location marker
    mapView.overlays.removeAll { it is Marker && (it as Marker).title == "Current Location" }

    // Add new location marker
    val marker = Marker(mapView).apply {
        position = GeoPoint(location.latitude, location.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = "Current Location"
    }

    mapView.overlays.add(marker)
    mapView.invalidate()
}
