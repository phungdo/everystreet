package com.everystreet.survey.service

import com.everystreet.survey.data.*
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for fetching street network data from OpenStreetMap via Overpass API
 */
@Singleton
class OverpassApiService @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"

        // Highway types to include for car navigation
        private val CAR_HIGHWAY_TYPES = listOf(
            "motorway", "trunk", "primary", "secondary", "tertiary",
            "unclassified", "residential", "living_street", "service"
        )

        // Highway types for survey (excluding highways for local street surveys)
        private val SURVEY_HIGHWAY_TYPES = listOf(
            "primary", "secondary", "tertiary",
            "unclassified", "residential", "living_street", "service"
        )
    }

    /**
     * Fetch street network for an area defined by bounding box
     */
    suspend fun fetchStreetNetwork(
        bounds: AreaBounds,
        includeHighways: Boolean = false
    ): Result<StreetGraph> = withContext(Dispatchers.IO) {
        try {
            val highwayTypes = if (includeHighways) CAR_HIGHWAY_TYPES else SURVEY_HIGHWAY_TYPES
            val highwayFilter = highwayTypes.joinToString("|")

            val query = buildOverpassQuery(bounds, highwayFilter)
            val response = executeQuery(query)

            if (response.isSuccess) {
                val json = response.getOrThrow()
                val graph = parseOverpassResponse(json)
                Result.success(graph)
            } else {
                Result.failure(response.exceptionOrNull() ?: Exception("Unknown error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch street network for a named place (city, neighborhood, etc.)
     */
    suspend fun fetchStreetNetworkByPlace(
        placeName: String,
        includeHighways: Boolean = false
    ): Result<StreetGraph> = withContext(Dispatchers.IO) {
        try {
            val highwayTypes = if (includeHighways) CAR_HIGHWAY_TYPES else SURVEY_HIGHWAY_TYPES
            val highwayFilter = highwayTypes.joinToString("|")

            val query = buildPlaceQuery(placeName, highwayFilter)
            val response = executeQuery(query)

            if (response.isSuccess) {
                val json = response.getOrThrow()
                val graph = parseOverpassResponse(json)
                Result.success(graph)
            } else {
                Result.failure(response.exceptionOrNull() ?: Exception("Unknown error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build Overpass QL query for bounding box
     */
    private fun buildOverpassQuery(bounds: AreaBounds, highwayFilter: String): String {
        return """
            [out:json][timeout:120];
            (
              way["highway"~"$highwayFilter"]
                ["access"!~"private|no"]
                ["motor_vehicle"!~"no|private"]
                (${bounds.south},${bounds.west},${bounds.north},${bounds.east});
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    /**
     * Build Overpass QL query for named place
     */
    private fun buildPlaceQuery(placeName: String, highwayFilter: String): String {
        return """
            [out:json][timeout:120];
            area["name"="$placeName"]->.searchArea;
            (
              way["highway"~"$highwayFilter"]
                ["access"!~"private|no"]
                ["motor_vehicle"!~"no|private"]
                (area.searchArea);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    /**
     * Execute Overpass API query
     */
    private fun executeQuery(query: String): Result<String> {
        val body = query.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder()
            .url(OVERPASS_URL)
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    Result.success(responseBody)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse Overpass API JSON response into a StreetGraph
     */
    private fun parseOverpassResponse(json: String): StreetGraph {
        val nodes = mutableMapOf<Long, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val adjacencyList = mutableMapOf<Long, MutableList<Pair<Long, GraphEdge>>>()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val elements = jsonObject.getAsJsonArray("elements")

        // First pass: collect all nodes
        val nodeLocations = mutableMapOf<Long, LatLng>()
        for (element in elements) {
            val obj = element.asJsonObject
            val type = obj.get("type").asString

            if (type == "node") {
                val id = obj.get("id").asLong
                val lat = obj.get("lat").asDouble
                val lon = obj.get("lon").asDouble
                nodeLocations[id] = LatLng(lat, lon)
            }
        }

        // Second pass: process ways (streets)
        var edgeId = 0L
        for (element in elements) {
            val obj = element.asJsonObject
            val type = obj.get("type").asString

            if (type == "way") {
                val wayId = obj.get("id").asLong
                val nodeRefs = obj.getAsJsonArray("nodes")
                    .map { it.asLong }

                val tags = obj.getAsJsonObject("tags")
                val name = tags?.get("name")?.asString
                val highway = tags?.get("highway")?.asString
                val oneway = tags?.get("oneway")?.asString

                val isOneWay = oneway == "yes" || oneway == "1" || oneway == "true"

                // Create edges for each segment of the way
                for (i in 0 until nodeRefs.size - 1) {
                    val fromNodeId = nodeRefs[i]
                    val toNodeId = nodeRefs[i + 1]

                    val fromLoc = nodeLocations[fromNodeId] ?: continue
                    val toLoc = nodeLocations[toNodeId] ?: continue

                    // Create or update nodes
                    val fromNode = nodes.getOrPut(fromNodeId) {
                        GraphNode(fromNodeId, fromLoc, 0)
                    }
                    val toNode = nodes.getOrPut(toNodeId) {
                        GraphNode(toNodeId, toLoc, 0)
                    }

                    // Calculate edge length
                    val length = fromLoc.distanceTo(toLoc)

                    // Create edge
                    val edge = GraphEdge(
                        id = edgeId++,
                        fromNode = fromNodeId,
                        toNode = toNodeId,
                        name = name,
                        length = length,
                        geometry = listOf(fromLoc, toLoc),
                        isOneWay = isOneWay,
                        highway = highway
                    )

                    edges.add(edge)

                    // Update degrees and adjacency list
                    fromNode.degree++
                    toNode.degree++

                    adjacencyList.getOrPut(fromNodeId) { mutableListOf() }
                        .add(Pair(toNodeId, edge))

                    // For non-one-way streets, add reverse adjacency
                    if (!isOneWay) {
                        adjacencyList.getOrPut(toNodeId) { mutableListOf() }
                            .add(Pair(fromNodeId, edge))
                    }
                }
            }
        }

        return StreetGraph(
            nodes = nodes,
            edges = edges,
            adjacencyList = adjacencyList
        )
    }

    /**
     * Fetch street network around a point with radius
     */
    suspend fun fetchStreetNetworkAround(
        center: LatLng,
        radiusMeters: Double,
        includeHighways: Boolean = false
    ): Result<StreetGraph> {
        // Calculate bounding box from center and radius
        val latOffset = radiusMeters / 111320.0 // approx meters per degree latitude
        val lngOffset = radiusMeters / (111320.0 * kotlin.math.cos(Math.toRadians(center.latitude)))

        val bounds = AreaBounds(
            north = center.latitude + latOffset,
            south = center.latitude - latOffset,
            east = center.longitude + lngOffset,
            west = center.longitude - lngOffset,
            name = "Area around ${center.latitude}, ${center.longitude}"
        )

        return fetchStreetNetwork(bounds, includeHighways)
    }
}
