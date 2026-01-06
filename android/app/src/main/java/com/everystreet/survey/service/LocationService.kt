package com.everystreet.survey.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.everystreet.survey.data.LatLng
import com.google.android.gms.location.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for managing GPS location tracking
 */
@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _locationUpdates = MutableSharedFlow<LatLng>(replay = 1)
    val locationUpdates: SharedFlow<LatLng> = _locationUpdates.asSharedFlow()

    private var locationCallback: LocationCallback? = null
    private var isTracking = false

    companion object {
        // High accuracy for navigation
        private val NAVIGATION_REQUEST = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // 1 second interval
        )
            .setMinUpdateDistanceMeters(5f) // Minimum 5 meters movement
            .setWaitForAccurateLocation(true)
            .build()

        // Standard accuracy for area selection
        private val STANDARD_REQUEST = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            5000L // 5 second interval
        )
            .setMinUpdateDistanceMeters(10f)
            .build()

        // Battery-saving for background
        private val BACKGROUND_REQUEST = LocationRequest.Builder(
            Priority.PRIORITY_LOW_POWER,
            30000L // 30 second interval
        )
            .setMinUpdateDistanceMeters(50f)
            .build()
    }

    /**
     * Check if location permissions are granted
     */
    fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if background location permission is granted
     */
    fun hasBackgroundLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get the last known location
     */
    suspend fun getLastLocation(): LatLng? {
        if (!hasLocationPermission()) return null

        return try {
            val location = fusedLocationClient.lastLocation.await()
            location?.let { LatLng(it.latitude, it.longitude) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Start location tracking for navigation
     */
    fun startNavigationTracking() {
        startTracking(NAVIGATION_REQUEST)
    }

    /**
     * Start standard location tracking
     */
    fun startStandardTracking() {
        startTracking(STANDARD_REQUEST)
    }

    /**
     * Start background location tracking
     */
    fun startBackgroundTracking() {
        startTracking(BACKGROUND_REQUEST)
    }

    private fun startTracking(request: LocationRequest) {
        if (!hasLocationPermission()) return
        if (isTracking) {
            stopTracking()
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val latLng = LatLng(location.latitude, location.longitude)
                    _currentLocation.value = latLng
                    _locationUpdates.tryEmit(latLng)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                // Handle location availability changes if needed
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isTracking = true
        } catch (e: SecurityException) {
            // Permission denied
        }
    }

    /**
     * Stop location tracking
     */
    fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        isTracking = false
    }

    /**
     * Get continuous location updates as a Flow
     */
    fun getLocationFlow(highAccuracy: Boolean = false): Flow<LatLng> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val request = if (highAccuracy) NAVIGATION_REQUEST else STANDARD_REQUEST

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(LatLng(location.latitude, location.longitude))
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            close(e)
        }

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    /**
     * Calculate bearing between current location and target
     */
    fun calculateBearing(from: LatLng, to: LatLng): Float {
        val fromLocation = Location("").apply {
            latitude = from.latitude
            longitude = from.longitude
        }

        val toLocation = Location("").apply {
            latitude = to.latitude
            longitude = to.longitude
        }

        return fromLocation.bearingTo(toLocation)
    }

    /**
     * Calculate distance between two points
     */
    fun calculateDistance(from: LatLng, to: LatLng): Float {
        val fromLocation = Location("").apply {
            latitude = from.latitude
            longitude = from.longitude
        }

        val toLocation = Location("").apply {
            latitude = to.latitude
            longitude = to.longitude
        }

        return fromLocation.distanceTo(toLocation)
    }

    /**
     * Check if location is on route (within threshold)
     */
    fun isOnRoute(
        currentLocation: LatLng,
        routePath: List<LatLng>,
        thresholdMeters: Float = 30f
    ): Boolean {
        for (point in routePath) {
            if (calculateDistance(currentLocation, point) <= thresholdMeters) {
                return true
            }
        }
        return false
    }

    /**
     * Find the closest point on the route to current location
     */
    fun findClosestRoutePoint(
        currentLocation: LatLng,
        routePath: List<LatLng>
    ): Pair<Int, Float> {
        var closestIndex = 0
        var closestDistance = Float.MAX_VALUE

        routePath.forEachIndexed { index, point ->
            val distance = calculateDistance(currentLocation, point)
            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = index
            }
        }

        return Pair(closestIndex, closestDistance)
    }
}

/**
 * Extension to await FusedLocationProviderClient.lastLocation
 */
private suspend fun com.google.android.gms.tasks.Task<Location>.await(): Location? {
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { location ->
            continuation.resume(location) { }
        }
        addOnFailureListener { exception ->
            continuation.resume(null) { }
        }
    }
}
