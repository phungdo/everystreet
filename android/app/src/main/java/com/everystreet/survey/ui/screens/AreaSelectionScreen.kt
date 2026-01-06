package com.everystreet.survey.ui.screens

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.everystreet.survey.data.AreaBounds
import com.everystreet.survey.data.LatLng
import com.everystreet.survey.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import javax.inject.Inject

@HiltViewModel
class AreaSelectionViewModel @Inject constructor(
    private val locationService: LocationService
) : ViewModel() {
    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _selectedBounds = MutableStateFlow<AreaBounds?>(null)
    val selectedBounds: StateFlow<AreaBounds?> = _selectedBounds.asStateFlow()

    suspend fun getCurrentLocation(): LatLng? {
        val location = locationService.getLastLocation()
        _currentLocation.value = location
        return location
    }

    fun setSelectedBounds(bounds: AreaBounds) {
        _selectedBounds.value = bounds
    }

    fun clearSelection() {
        _selectedBounds.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreaSelectionScreen(
    viewModel: AreaSelectionViewModel = hiltViewModel(),
    onAreaSelected: (AreaBounds) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentLocation by viewModel.currentLocation.collectAsState()
    val selectedBounds by viewModel.selectedBounds.collectAsState()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var areaName by remember { mutableStateOf("Survey Area") }
    var radiusKm by remember { mutableStateOf(0.5f) }
    var isSelectingRadius by remember { mutableStateOf(true) }
    var centerPoint by remember { mutableStateOf<GeoPoint?>(null) }

    // Load current location on start
    LaunchedEffect(Unit) {
        val location = viewModel.getCurrentLocation()
        location?.let {
            centerPoint = GeoPoint(it.latitude, it.longitude)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Survey Area") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val location = viewModel.getCurrentLocation()
                                location?.let {
                                    centerPoint = GeoPoint(it.latitude, it.longitude)
                                    mapView?.controller?.animateTo(centerPoint)
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "My Location")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Area name input
                    OutlinedTextField(
                        value = areaName,
                        onValueChange = { areaName = it },
                        label = { Text("Area Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Radius slider
                    Text(
                        text = "Survey Radius: ${String.format("%.1f", radiusKm)} km",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = radiusKm,
                        onValueChange = {
                            radiusKm = it
                            updateSelectionOverlay(mapView, centerPoint, radiusKm)
                        },
                        valueRange = 0.2f..3f,
                        steps = 13
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Estimated info
                    centerPoint?.let { center ->
                        val bounds = createBoundsFromCenter(center, radiusKm)
                        Text(
                            text = "Tap on map to set center point",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Confirm button
                    Button(
                        onClick = {
                            centerPoint?.let { center ->
                                val bounds = createBoundsFromCenter(center, radiusKm).copy(name = areaName)
                                onAreaSelected(bounds)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = centerPoint != null && areaName.isNotBlank()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Calculate Route")
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // OpenStreetMap view
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)

                        // Default to a location or current location
                        centerPoint?.let {
                            controller.setCenter(it)
                        } ?: controller.setCenter(GeoPoint(51.5074, -0.1278)) // London default

                        // Handle map clicks to set center
                        setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_UP) {
                                val proj = projection
                                val geoPoint = proj.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                                centerPoint = geoPoint
                                updateSelectionOverlay(this, geoPoint, radiusKm)
                            }
                            false
                        }

                        mapView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { map ->
                    centerPoint?.let { center ->
                        if (map.overlays.isEmpty()) {
                            updateSelectionOverlay(map, center, radiusKm)
                        }
                    }
                }
            )

            // Instructions overlay
            if (centerPoint == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Tap on the map to select survey area center",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun updateSelectionOverlay(mapView: MapView?, center: GeoPoint?, radiusKm: Float) {
    mapView ?: return
    center ?: return

    // Remove existing overlays
    mapView.overlays.clear()

    // Create circle polygon
    val points = mutableListOf<GeoPoint>()
    val radiusMeters = radiusKm * 1000
    val numPoints = 60

    for (i in 0 until numPoints) {
        val angle = (i * 360.0 / numPoints) * Math.PI / 180
        val lat = center.latitude + (radiusMeters / 111320) * Math.cos(angle)
        val lng = center.longitude + (radiusMeters / (111320 * Math.cos(Math.toRadians(center.latitude)))) * Math.sin(angle)
        points.add(GeoPoint(lat, lng))
    }
    points.add(points.first()) // Close the circle

    val polygon = Polygon().apply {
        this.points = points
        fillPaint.color = 0x302196F3 // Semi-transparent blue
        outlinePaint.color = 0xFF2196F3.toInt() // Blue outline
        outlinePaint.strokeWidth = 3f
    }

    mapView.overlays.add(polygon)
    mapView.invalidate()
}

private fun createBoundsFromCenter(center: GeoPoint, radiusKm: Float): AreaBounds {
    val radiusMeters = radiusKm * 1000
    val latOffset = radiusMeters / 111320.0
    val lngOffset = radiusMeters / (111320.0 * kotlin.math.cos(Math.toRadians(center.latitude)))

    return AreaBounds(
        north = center.latitude + latOffset,
        south = center.latitude - latOffset,
        east = center.longitude + lngOffset,
        west = center.longitude - lngOffset,
        name = ""
    )
}
