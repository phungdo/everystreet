package com.everystreet.survey.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.everystreet.survey.R
import com.everystreet.survey.algorithm.NavigationInstructionGenerator
import com.everystreet.survey.data.*
import com.everystreet.survey.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import javax.inject.Inject

/**
 * Foreground service for handling turn-by-turn navigation
 */
@AndroidEntryPoint
class NavigationService : Service(), TextToSpeech.OnInitListener {

    @Inject lateinit var locationService: LocationService
    @Inject lateinit var database: SurveyDatabase

    private val binder = NavigationBinder()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val instructionGenerator = NavigationInstructionGenerator()

    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var voiceGuidanceEnabled = true

    private var currentRoute: SurveyRoute? = null
    private var currentProgress: SurveyProgress? = null
    private var locationJob: Job? = null

    private val _navigationState = MutableStateFlow<NavigationState?>(null)
    val navigationState: StateFlow<NavigationState?> = _navigationState.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "navigation_channel"
        private const val NOTIFICATION_ID = 1001
        private const val OFF_ROUTE_THRESHOLD = 50f // meters
        private const val INSTRUCTION_THRESHOLD = 100f // meters before announcing
        private const val COMPLETED_THRESHOLD = 20f // meters to mark street completed
    }

    inner class NavigationBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        textToSpeech = TextToSpeech(this, this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NAVIGATION -> {
                val routeId = intent.getLongExtra(EXTRA_ROUTE_ID, -1)
                if (routeId != -1L) {
                    startNavigation(routeId)
                }
            }
            ACTION_STOP_NAVIGATION -> stopNavigation()
            ACTION_PAUSE_NAVIGATION -> pauseNavigation()
            ACTION_RESUME_NAVIGATION -> resumeNavigation()
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.getDefault())
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech?.shutdown()
        scope.cancel()
        locationJob?.cancel()
    }

    /**
     * Start navigation for a route
     */
    fun startNavigation(routeId: Long) {
        scope.launch {
            val route = database.surveyRouteDao().getRouteById(routeId)
            if (route == null) return@launch

            currentRoute = route
            currentProgress = database.surveyProgressDao().getProgress(routeId)
                ?: SurveyProgress(
                    routeId = routeId,
                    totalEdges = route.edgeIds.size,
                    totalDistance = route.totalDistance
                )

            _isNavigating.value = true
            database.surveyRouteDao().updateRouteStatus(routeId, RouteStatus.IN_PROGRESS)

            // Start foreground service
            startForeground(NOTIFICATION_ID, createNavigationNotification())

            // Start location tracking
            locationService.startNavigationTracking()
            startLocationUpdates()

            speak("Starting navigation. ${route.instructions.firstOrNull()?.let {
                instructionGenerator.getInstructionText(it)
            } ?: "Follow the route."}")
        }
    }

    /**
     * Stop navigation
     */
    fun stopNavigation() {
        scope.launch {
            currentRoute?.let { route ->
                database.surveyRouteDao().updateRouteStatus(route.id, RouteStatus.PAUSED)
            }
            saveProgress()
        }

        _isNavigating.value = false
        locationJob?.cancel()
        locationService.stopTracking()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Pause navigation
     */
    fun pauseNavigation() {
        scope.launch {
            currentProgress?.let {
                database.surveyProgressDao().setPaused(it.routeId, true)
            }
        }
        locationJob?.cancel()
        locationService.stopTracking()
        speak("Navigation paused")
    }

    /**
     * Resume navigation
     */
    fun resumeNavigation() {
        scope.launch {
            currentProgress?.let {
                database.surveyProgressDao().setPaused(it.routeId, false)
            }
        }
        locationService.startNavigationTracking()
        startLocationUpdates()
        speak("Navigation resumed")
    }

    /**
     * Start receiving location updates
     */
    private fun startLocationUpdates() {
        locationJob?.cancel()
        locationJob = scope.launch {
            locationService.locationUpdates.collect { location ->
                updateNavigation(location)
            }
        }
    }

    /**
     * Update navigation state based on current location
     */
    private suspend fun updateNavigation(currentLocation: LatLng) {
        val route = currentRoute ?: return
        val progress = currentProgress ?: return

        // Find closest point on route
        val (closestIndex, distance) = locationService.findClosestRoutePoint(
            currentLocation, route.routePath
        )

        // Check if off route
        val isOffRoute = distance > OFF_ROUTE_THRESHOLD

        if (isOffRoute) {
            speak("You are off route. Please return to the route.")
        }

        // Calculate distance remaining
        val distanceRemaining = calculateDistanceRemaining(route.routePath, closestIndex)

        // Find current instruction
        val currentInstructionIndex = findCurrentInstruction(route.instructions, currentLocation)

        // Calculate distance to next instruction
        val nextInstruction = route.instructions.getOrNull(currentInstructionIndex + 1)
        val distanceToNextInstruction = nextInstruction?.let {
            locationService.calculateDistance(currentLocation, it.location).toDouble()
        } ?: distanceRemaining

        // Check if approaching next instruction
        if (distanceToNextInstruction < INSTRUCTION_THRESHOLD && nextInstruction != null) {
            val distanceText = instructionGenerator.formatDistance(distanceToNextInstruction)
            speak("In $distanceText, ${instructionGenerator.getInstructionText(nextInstruction)}")
        }

        // Update surveyed streets
        val completedStreets = updateSurveyedStreets(route, currentLocation, closestIndex)

        // Update navigation state
        val state = NavigationState(
            route = route,
            currentInstructionIndex = currentInstructionIndex,
            currentPosition = currentLocation,
            distanceToNextInstruction = distanceToNextInstruction,
            distanceRemaining = distanceRemaining,
            timeRemaining = instructionGenerator.estimateTravelTime(distanceRemaining),
            streetsCompleted = progress.completedEdges + completedStreets,
            totalStreets = progress.totalEdges,
            isNavigating = true,
            isOffRoute = isOffRoute
        )

        _navigationState.value = state

        // Update notification
        updateNotification(state)

        // Check if arrived
        if (distanceRemaining < COMPLETED_THRESHOLD) {
            completeNavigation()
        }

        // Save progress periodically
        if (completedStreets > 0) {
            currentProgress = progress.copy(
                currentEdgeIndex = closestIndex,
                completedEdges = progress.completedEdges + completedStreets,
                distanceCovered = route.totalDistance - distanceRemaining
            )
            saveProgress()
        }
    }

    /**
     * Calculate remaining distance from current position
     */
    private fun calculateDistanceRemaining(path: List<LatLng>, fromIndex: Int): Double {
        if (fromIndex >= path.size - 1) return 0.0

        var distance = 0.0
        for (i in fromIndex until path.size - 1) {
            distance += path[i].distanceTo(path[i + 1])
        }
        return distance
    }

    /**
     * Find the current navigation instruction based on location
     */
    private fun findCurrentInstruction(
        instructions: List<NavigationInstruction>,
        currentLocation: LatLng
    ): Int {
        var closestIndex = 0
        var closestDistance = Float.MAX_VALUE

        instructions.forEachIndexed { index, instruction ->
            val distance = locationService.calculateDistance(currentLocation, instruction.location)
            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = index
            }
        }

        return closestIndex
    }

    /**
     * Update surveyed streets based on current location
     */
    private suspend fun updateSurveyedStreets(
        route: SurveyRoute,
        location: LatLng,
        pathIndex: Int
    ): Int {
        // In a full implementation, this would track which edges have been covered
        // For now, return 0 and let the progress be tracked by path index
        return 0
    }

    /**
     * Save current progress to database
     */
    private suspend fun saveProgress() {
        currentProgress?.let {
            database.surveyProgressDao().updateProgress(it)
        }
    }

    /**
     * Complete navigation
     */
    private fun completeNavigation() {
        scope.launch {
            currentRoute?.let { route ->
                database.surveyRouteDao().updateRouteStatus(route.id, RouteStatus.COMPLETED)
            }
        }

        speak("You have completed the survey. All streets have been covered.")
        stopNavigation()
    }

    /**
     * Speak a navigation instruction
     */
    private fun speak(text: String) {
        if (isTtsReady && voiceGuidanceEnabled) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    /**
     * Enable or disable voice guidance
     */
    fun setVoiceGuidance(enabled: Boolean) {
        voiceGuidanceEnabled = enabled
    }

    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Navigation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows navigation instructions"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * Create navigation notification
     */
    private fun createNavigationNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Street Survey Navigation")
            .setContentText("Navigating...")
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                createActionIntent(ACTION_STOP_NAVIGATION)
            )
            .build()
    }

    /**
     * Update notification with current state
     */
    private fun updateNotification(state: NavigationState) {
        val currentInstruction = state.route.instructions.getOrNull(state.currentInstructionIndex)
        val nextInstruction = state.route.instructions.getOrNull(state.currentInstructionIndex + 1)

        val title = currentInstruction?.let {
            instructionGenerator.getInstructionText(it)
        } ?: "Navigating"

        val text = buildString {
            append("${state.streetsCompleted}/${state.totalStreets} streets")
            append(" • ")
            append(instructionGenerator.formatDistance(state.distanceRemaining))
            append(" remaining")
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_navigation)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                createActionIntent(ACTION_STOP_NAVIGATION)
            )
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Create pending intent for notification actions
     */
    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, NavigationService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object Actions {
        const val ACTION_START_NAVIGATION = "com.everystreet.survey.START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "com.everystreet.survey.STOP_NAVIGATION"
        const val ACTION_PAUSE_NAVIGATION = "com.everystreet.survey.PAUSE_NAVIGATION"
        const val ACTION_RESUME_NAVIGATION = "com.everystreet.survey.RESUME_NAVIGATION"
        const val EXTRA_ROUTE_ID = "route_id"
    }
}
