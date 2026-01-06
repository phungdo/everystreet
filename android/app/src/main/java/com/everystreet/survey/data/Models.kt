package com.everystreet.survey.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.parcelize.Parcelize

/**
 * Represents a geographic coordinate (latitude, longitude)
 */
@Parcelize
data class LatLng(
    val latitude: Double,
    val longitude: Double
) : Parcelable {
    fun distanceTo(other: LatLng): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLng = Math.toRadians(other.longitude - longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }
}

/**
 * Represents a node (intersection) in the street network
 */
@Parcelize
data class GraphNode(
    val id: Long,
    val location: LatLng,
    var degree: Int = 0
) : Parcelable

/**
 * Represents an edge (street segment) in the street network
 */
@Parcelize
data class GraphEdge(
    val id: Long,
    val fromNode: Long,
    val toNode: Long,
    val name: String?,
    val length: Double, // meters
    val geometry: List<LatLng>, // Detailed path points
    val isOneWay: Boolean = false,
    val highway: String? = null // road type
) : Parcelable

/**
 * Represents the street network graph
 */
data class StreetGraph(
    val nodes: Map<Long, GraphNode>,
    val edges: List<GraphEdge>,
    val adjacencyList: Map<Long, List<Pair<Long, GraphEdge>>> // nodeId -> list of (neighborId, edge)
)

/**
 * Represents a navigation instruction
 */
@Parcelize
data class NavigationInstruction(
    val type: InstructionType,
    val streetName: String?,
    val distance: Double, // meters to next instruction
    val location: LatLng,
    val bearing: Double // degrees from north
) : Parcelable

enum class InstructionType {
    START,
    CONTINUE,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    U_TURN,
    ARRIVED
}

/**
 * Represents an optimized survey route
 */
@Entity(tableName = "survey_routes")
@TypeConverters(RouteConverters::class)
data class SurveyRoute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val areaName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val totalDistance: Double, // meters
    val estimatedTime: Long, // milliseconds
    val routePath: List<LatLng>,
    val instructions: List<NavigationInstruction>,
    val edgeIds: List<Long>, // Order of edges to traverse
    val status: RouteStatus = RouteStatus.CREATED
)

enum class RouteStatus {
    CREATED,
    IN_PROGRESS,
    COMPLETED,
    PAUSED
}

/**
 * Represents a street that has been surveyed
 */
@Entity(tableName = "surveyed_streets")
@TypeConverters(RouteConverters::class)
data class SurveyedStreet(
    @PrimaryKey val edgeId: Long,
    val routeId: Long,
    val name: String?,
    val geometry: List<LatLng>,
    val surveyedAt: Long = System.currentTimeMillis(),
    val surveyCount: Int = 1 // How many times this street was traversed
)

/**
 * Represents the current navigation state
 */
data class NavigationState(
    val route: SurveyRoute,
    val currentInstructionIndex: Int = 0,
    val currentPosition: LatLng,
    val distanceToNextInstruction: Double,
    val distanceRemaining: Double,
    val timeRemaining: Long, // milliseconds
    val streetsCompleted: Int,
    val totalStreets: Int,
    val isNavigating: Boolean = false,
    val isOffRoute: Boolean = false
)

/**
 * Represents survey progress
 */
@Entity(tableName = "survey_progress")
data class SurveyProgress(
    @PrimaryKey val routeId: Long,
    val currentEdgeIndex: Int = 0,
    val completedEdges: Int = 0,
    val totalEdges: Int,
    val distanceCovered: Double = 0.0,
    val totalDistance: Double,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isPaused: Boolean = false
)

/**
 * Area bounds for querying street data
 */
@Parcelize
data class AreaBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val name: String = ""
) : Parcelable {
    fun contains(point: LatLng): Boolean {
        return point.latitude in south..north && point.longitude in west..east
    }

    fun center(): LatLng {
        return LatLng((north + south) / 2, (east + west) / 2)
    }
}

/**
 * Settings for route calculation
 */
data class RouteSettings(
    val startLocation: LatLng? = null,
    val avoidHighways: Boolean = true,
    val preferResidential: Boolean = true,
    val maxRouteLength: Double? = null // meters, null = no limit
)

/**
 * Room type converters for complex types
 */
class RouteConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromLatLngList(value: List<LatLng>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toLatLngList(value: String): List<LatLng> {
        val type = object : TypeToken<List<LatLng>>() {}.type
        return gson.fromJson(value, type)
    }

    @TypeConverter
    fun fromInstructionList(value: List<NavigationInstruction>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toInstructionList(value: String): List<NavigationInstruction> {
        val type = object : TypeToken<List<NavigationInstruction>>() {}.type
        return gson.fromJson(value, type)
    }

    @TypeConverter
    fun fromLongList(value: List<Long>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toLongList(value: String): List<Long> {
        val type = object : TypeToken<List<Long>>() {}.type
        return gson.fromJson(value, type)
    }

    @TypeConverter
    fun fromRouteStatus(value: RouteStatus): String {
        return value.name
    }

    @TypeConverter
    fun toRouteStatus(value: String): RouteStatus {
        return RouteStatus.valueOf(value)
    }
}
