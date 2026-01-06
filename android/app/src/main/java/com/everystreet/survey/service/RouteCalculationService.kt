package com.everystreet.survey.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.everystreet.survey.algorithm.ChinesePostmanSolver
import com.everystreet.survey.algorithm.NavigationInstructionGenerator
import com.everystreet.survey.data.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * State of route calculation
 */
sealed class RouteCalculationState {
    object Idle : RouteCalculationState()
    data class FetchingStreets(val progress: Float) : RouteCalculationState()
    data class Calculating(val progress: Float, val message: String) : RouteCalculationState()
    data class Success(val route: SurveyRoute) : RouteCalculationState()
    data class Error(val message: String) : RouteCalculationState()
}

/**
 * Service for calculating optimal survey routes
 */
@AndroidEntryPoint
class RouteCalculationService : Service() {

    @Inject lateinit var overpassApi: OverpassApiService
    @Inject lateinit var database: SurveyDatabase

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val solver = ChinesePostmanSolver()
    private val instructionGenerator = NavigationInstructionGenerator()

    private val _calculationState = MutableStateFlow<RouteCalculationState>(RouteCalculationState.Idle)
    val calculationState: StateFlow<RouteCalculationState> = _calculationState.asStateFlow()

    private var currentJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * Calculate optimal route for an area defined by bounds
     */
    fun calculateRoute(
        bounds: AreaBounds,
        routeName: String,
        startLocation: LatLng? = null,
        includeHighways: Boolean = false,
        onComplete: (Result<SurveyRoute>) -> Unit
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                _calculationState.value = RouteCalculationState.FetchingStreets(0f)

                // Fetch street network
                val graphResult = overpassApi.fetchStreetNetwork(bounds, includeHighways)
                if (graphResult.isFailure) {
                    val error = graphResult.exceptionOrNull()?.message ?: "Failed to fetch streets"
                    _calculationState.value = RouteCalculationState.Error(error)
                    onComplete(Result.failure(Exception(error)))
                    return@launch
                }

                val graph = graphResult.getOrThrow()

                if (graph.edges.isEmpty()) {
                    _calculationState.value = RouteCalculationState.Error("No streets found in area")
                    onComplete(Result.failure(Exception("No streets found")))
                    return@launch
                }

                _calculationState.value = RouteCalculationState.Calculating(0.2f, "Analyzing street network...")

                // Find starting node closest to start location
                val startNodeId = if (startLocation != null) {
                    findClosestNode(graph, startLocation)
                } else {
                    null
                }

                _calculationState.value = RouteCalculationState.Calculating(0.4f, "Finding optimal route...")

                // Calculate optimal route using Chinese Postman algorithm
                val optimalRoute = solver.calculateOptimalRoute(graph, startNodeId)

                _calculationState.value = RouteCalculationState.Calculating(0.7f, "Generating navigation instructions...")

                // Generate navigation instructions
                val instructions = instructionGenerator.generateInstructions(optimalRoute, graph)

                // Estimate travel time (assuming 30 km/h average in urban areas)
                val estimatedTime = instructionGenerator.estimateTravelTime(optimalRoute.totalDistance)

                _calculationState.value = RouteCalculationState.Calculating(0.9f, "Saving route...")

                // Create SurveyRoute
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

                // Save to database
                val routeId = database.surveyRouteDao().insertRoute(surveyRoute)
                val savedRoute = surveyRoute.copy(id = routeId)

                // Initialize progress
                val progress = SurveyProgress(
                    routeId = routeId,
                    totalEdges = optimalRoute.edgeOrder.size,
                    totalDistance = optimalRoute.totalDistance
                )
                database.surveyProgressDao().insertProgress(progress)

                _calculationState.value = RouteCalculationState.Success(savedRoute)
                onComplete(Result.success(savedRoute))

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _calculationState.value = RouteCalculationState.Error(e.message ?: "Unknown error")
                onComplete(Result.failure(e))
            }
        }
    }

    /**
     * Calculate route around a point with radius
     */
    fun calculateRouteAround(
        center: LatLng,
        radiusMeters: Double,
        routeName: String,
        includeHighways: Boolean = false,
        onComplete: (Result<SurveyRoute>) -> Unit
    ) {
        val latOffset = radiusMeters / 111320.0
        val lngOffset = radiusMeters / (111320.0 * kotlin.math.cos(Math.toRadians(center.latitude)))

        val bounds = AreaBounds(
            north = center.latitude + latOffset,
            south = center.latitude - latOffset,
            east = center.longitude + lngOffset,
            west = center.longitude - lngOffset,
            name = "Survey area"
        )

        calculateRoute(bounds, routeName, center, includeHighways, onComplete)
    }

    /**
     * Calculate route for a named place
     */
    fun calculateRouteByPlace(
        placeName: String,
        routeName: String,
        startLocation: LatLng? = null,
        includeHighways: Boolean = false,
        onComplete: (Result<SurveyRoute>) -> Unit
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                _calculationState.value = RouteCalculationState.FetchingStreets(0f)

                val graphResult = overpassApi.fetchStreetNetworkByPlace(placeName, includeHighways)
                if (graphResult.isFailure) {
                    val error = graphResult.exceptionOrNull()?.message ?: "Failed to fetch streets"
                    _calculationState.value = RouteCalculationState.Error(error)
                    onComplete(Result.failure(Exception(error)))
                    return@launch
                }

                val graph = graphResult.getOrThrow()

                if (graph.edges.isEmpty()) {
                    _calculationState.value = RouteCalculationState.Error("No streets found for $placeName")
                    onComplete(Result.failure(Exception("No streets found")))
                    return@launch
                }

                _calculationState.value = RouteCalculationState.Calculating(0.3f, "Optimizing route...")

                val startNodeId = if (startLocation != null) {
                    findClosestNode(graph, startLocation)
                } else null

                val optimalRoute = solver.calculateOptimalRoute(graph, startNodeId)
                val instructions = instructionGenerator.generateInstructions(optimalRoute, graph)
                val estimatedTime = instructionGenerator.estimateTravelTime(optimalRoute.totalDistance)

                val surveyRoute = SurveyRoute(
                    name = routeName,
                    areaName = placeName,
                    totalDistance = optimalRoute.totalDistance,
                    estimatedTime = estimatedTime,
                    routePath = optimalRoute.path,
                    instructions = instructions,
                    edgeIds = optimalRoute.edgeOrder,
                    status = RouteStatus.CREATED
                )

                val routeId = database.surveyRouteDao().insertRoute(surveyRoute)
                val savedRoute = surveyRoute.copy(id = routeId)

                val progress = SurveyProgress(
                    routeId = routeId,
                    totalEdges = optimalRoute.edgeOrder.size,
                    totalDistance = optimalRoute.totalDistance
                )
                database.surveyProgressDao().insertProgress(progress)

                _calculationState.value = RouteCalculationState.Success(savedRoute)
                onComplete(Result.success(savedRoute))

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _calculationState.value = RouteCalculationState.Error(e.message ?: "Unknown error")
                onComplete(Result.failure(e))
            }
        }
    }

    /**
     * Find the closest node in the graph to a location
     */
    private fun findClosestNode(graph: StreetGraph, location: LatLng): Long {
        var closestNodeId = graph.nodes.keys.first()
        var closestDistance = Double.MAX_VALUE

        for ((nodeId, node) in graph.nodes) {
            val distance = location.distanceTo(node.location)
            if (distance < closestDistance) {
                closestDistance = distance
                closestNodeId = nodeId
            }
        }

        return closestNodeId
    }

    /**
     * Cancel current calculation
     */
    fun cancelCalculation() {
        currentJob?.cancel()
        _calculationState.value = RouteCalculationState.Idle
    }

    /**
     * Reset calculation state
     */
    fun resetState() {
        _calculationState.value = RouteCalculationState.Idle
    }
}
