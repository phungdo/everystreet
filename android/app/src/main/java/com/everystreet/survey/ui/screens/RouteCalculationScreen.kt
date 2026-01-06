package com.everystreet.survey.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.everystreet.survey.data.*
import com.everystreet.survey.service.OverpassApiService
import com.everystreet.survey.service.RouteCalculationState
import com.everystreet.survey.algorithm.ChinesePostmanSolver
import com.everystreet.survey.algorithm.NavigationInstructionGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RouteCalculationViewModel @Inject constructor(
    private val overpassApi: OverpassApiService,
    private val routeDao: SurveyRouteDao,
    private val progressDao: SurveyProgressDao
) : ViewModel() {

    private val solver = ChinesePostmanSolver()
    private val instructionGenerator = NavigationInstructionGenerator()

    private val _state = MutableStateFlow<RouteCalculationState>(RouteCalculationState.Idle)
    val state: StateFlow<RouteCalculationState> = _state.asStateFlow()

    fun calculateRoute(
        bounds: AreaBounds,
        routeName: String,
        onComplete: (Long) -> Unit
    ) {
        viewModelScope.launch {
            try {
                _state.value = RouteCalculationState.FetchingStreets(0f)

                // Fetch street network
                val graphResult = overpassApi.fetchStreetNetwork(bounds, false)
                if (graphResult.isFailure) {
                    val error = graphResult.exceptionOrNull()?.message ?: "Failed to fetch streets"
                    _state.value = RouteCalculationState.Error(error)
                    return@launch
                }

                val graph = graphResult.getOrThrow()

                if (graph.edges.isEmpty()) {
                    _state.value = RouteCalculationState.Error("No streets found in selected area")
                    return@launch
                }

                _state.value = RouteCalculationState.Calculating(0.3f, "Found ${graph.edges.size} street segments")

                // Calculate optimal route
                _state.value = RouteCalculationState.Calculating(0.5f, "Calculating optimal route...")
                val optimalRoute = solver.calculateOptimalRoute(graph, null)

                _state.value = RouteCalculationState.Calculating(0.7f, "Generating navigation instructions...")
                val instructions = instructionGenerator.generateInstructions(optimalRoute, graph)
                val estimatedTime = instructionGenerator.estimateTravelTime(optimalRoute.totalDistance)

                _state.value = RouteCalculationState.Calculating(0.9f, "Saving route...")

                // Create and save route
                val surveyRoute = SurveyRoute(
                    name = routeName,
                    areaName = bounds.name,
                    totalDistance = optimalRoute.totalDistance,
                    estimatedTime = estimatedTime,
                    routePath = optimalRoute.path,
                    instructions = instructions,
                    edgeIds = optimalRoute.edgeOrder,
                    status = RouteStatus.CREATED
                )

                val routeId = routeDao.insertRoute(surveyRoute)

                // Initialize progress
                val progress = SurveyProgress(
                    routeId = routeId,
                    totalEdges = optimalRoute.edgeOrder.size,
                    totalDistance = optimalRoute.totalDistance
                )
                progressDao.insertProgress(progress)

                val savedRoute = surveyRoute.copy(id = routeId)
                _state.value = RouteCalculationState.Success(savedRoute)

                onComplete(routeId)

            } catch (e: Exception) {
                _state.value = RouteCalculationState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    fun reset() {
        _state.value = RouteCalculationState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteCalculationScreen(
    areaName: String,
    north: Double,
    south: Double,
    east: Double,
    west: Double,
    viewModel: RouteCalculationViewModel = hiltViewModel(),
    onRouteCreated: (Long) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    val bounds = remember {
        AreaBounds(
            north = north,
            south = south,
            east = east,
            west = west,
            name = areaName
        )
    }

    // Start calculation on first load
    LaunchedEffect(bounds) {
        if (state is RouteCalculationState.Idle) {
            viewModel.calculateRoute(bounds, areaName, onRouteCreated)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calculating Route") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when (val currentState = state) {
                is RouteCalculationState.Idle -> {
                    CircularProgressIndicator()
                }

                is RouteCalculationState.FetchingStreets -> {
                    CalculationProgress(
                        title = "Fetching Streets",
                        message = "Downloading street data from OpenStreetMap...",
                        progress = currentState.progress,
                        icon = Icons.Default.CloudDownload
                    )
                }

                is RouteCalculationState.Calculating -> {
                    CalculationProgress(
                        title = "Calculating Route",
                        message = currentState.message,
                        progress = currentState.progress,
                        icon = Icons.Default.Route
                    )
                }

                is RouteCalculationState.Success -> {
                    val route = currentState.route
                    CalculationSuccess(
                        route = route,
                        onStartNavigation = { onRouteCreated(route.id) }
                    )
                }

                is RouteCalculationState.Error -> {
                    CalculationError(
                        message = currentState.message,
                        onRetry = {
                            viewModel.reset()
                            viewModel.calculateRoute(bounds, areaName, onRouteCreated)
                        },
                        onBack = onBack
                    )
                }
            }
        }
    }
}

@Composable
fun CalculationProgress(
    title: String,
    message: String,
    progress: Float,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .rotate(if (icon == Icons.Default.Route) rotation else 0f),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CalculationSuccess(
    route: SurveyRoute,
    onStartNavigation: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Route Ready!",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Route stats
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                RouteStatRow("Total Distance", formatDistance(route.totalDistance))
                RouteStatRow("Estimated Time", formatTime(route.estimatedTime))
                RouteStatRow("Street Segments", "${route.edgeIds.size}")
                RouteStatRow("Navigation Points", "${route.instructions.size}")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onStartNavigation,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Navigation")
        }
    }
}

@Composable
fun RouteStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun CalculationError(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Calculation Failed",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Go Back")
            }
            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        "${meters.toInt()} m"
    } else {
        String.format("%.1f km", meters / 1000)
    }
}

private fun formatTime(millis: Long): String {
    val hours = millis / 3600000
    val minutes = (millis % 3600000) / 60000
    return if (hours > 0) {
        "${hours}h ${minutes}min"
    } else {
        "${minutes} min"
    }
}
