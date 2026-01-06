package com.everystreet.survey.algorithm

import com.everystreet.survey.data.*
import kotlin.math.*

/**
 * Generates turn-by-turn navigation instructions from a route
 */
class NavigationInstructionGenerator {

    companion object {
        private const val MIN_TURN_DISTANCE = 20.0 // meters
        private const val STRAIGHT_THRESHOLD = 15.0 // degrees
        private const val SLIGHT_TURN_THRESHOLD = 45.0 // degrees
        private const val TURN_THRESHOLD = 120.0 // degrees
    }

    /**
     * Generate navigation instructions from an optimal route result
     */
    fun generateInstructions(
        route: OptimalRouteResult,
        graph: StreetGraph
    ): List<NavigationInstruction> {
        if (route.circuit.isEmpty()) return emptyList()

        val instructions = mutableListOf<NavigationInstruction>()

        // Start instruction
        val startEdge = route.circuit.first()
        val startNode = graph.nodes[startEdge.fromNode]
        if (startNode != null) {
            instructions.add(
                NavigationInstruction(
                    type = InstructionType.START,
                    streetName = startEdge.edge.name,
                    distance = startEdge.edge.length,
                    location = startNode.location,
                    bearing = calculateBearing(startEdge, startEdge.fromNode)
                )
            )
        }

        // Process each edge transition
        var accumulatedDistance = 0.0
        var previousEdge: EdgeTraversal? = null
        var previousStreetName: String? = null

        for (i in route.circuit.indices) {
            val currentEdge = route.circuit[i]
            accumulatedDistance += currentEdge.edge.length

            val nextEdge = if (i < route.circuit.size - 1) route.circuit[i + 1] else null

            if (nextEdge != null) {
                val currentBearing = calculateBearing(currentEdge, currentEdge.toNode)
                val nextBearing = calculateBearing(nextEdge, nextEdge.fromNode)
                val turnAngle = normalizeBearing(nextBearing - currentBearing)

                val instructionType = determineInstructionType(turnAngle)
                val currentStreetName = currentEdge.edge.name
                val nextStreetName = nextEdge.edge.name

                // Generate instruction if:
                // 1. There's a significant turn
                // 2. Street name changes
                val streetNameChanged = currentStreetName != nextStreetName &&
                                        nextStreetName != null
                val significantTurn = instructionType != InstructionType.CONTINUE

                if (significantTurn || streetNameChanged) {
                    val turnNode = graph.nodes[currentEdge.toNode]
                    if (turnNode != null && accumulatedDistance >= MIN_TURN_DISTANCE) {
                        instructions.add(
                            NavigationInstruction(
                                type = if (significantTurn) instructionType else InstructionType.CONTINUE,
                                streetName = nextStreetName,
                                distance = accumulatedDistance,
                                location = turnNode.location,
                                bearing = nextBearing
                            )
                        )
                        accumulatedDistance = 0.0
                    }
                }
            }

            previousEdge = currentEdge
            previousStreetName = currentEdge.edge.name
        }

        // Arrival instruction
        val lastEdge = route.circuit.last()
        val endNode = graph.nodes[lastEdge.toNode]
        if (endNode != null) {
            instructions.add(
                NavigationInstruction(
                    type = InstructionType.ARRIVED,
                    streetName = null,
                    distance = accumulatedDistance,
                    location = endNode.location,
                    bearing = 0.0
                )
            )
        }

        return instructions
    }

    /**
     * Generate simplified instructions for display
     */
    fun generateSimpleInstructions(
        path: List<LatLng>,
        streetNames: List<String?>
    ): List<NavigationInstruction> {
        if (path.size < 2) return emptyList()

        val instructions = mutableListOf<NavigationInstruction>()

        // Start instruction
        instructions.add(
            NavigationInstruction(
                type = InstructionType.START,
                streetName = streetNames.firstOrNull(),
                distance = 0.0,
                location = path.first(),
                bearing = calculateBearing(path[0], path[1])
            )
        )

        var accumulatedDistance = 0.0
        var previousBearing = calculateBearing(path[0], path[1])
        var currentStreetIndex = 0

        for (i in 1 until path.size - 1) {
            val segmentDistance = path[i - 1].distanceTo(path[i])
            accumulatedDistance += segmentDistance

            val currentBearing = calculateBearing(path[i], path[i + 1])
            val turnAngle = normalizeBearing(currentBearing - previousBearing)

            val instructionType = determineInstructionType(turnAngle)
            val streetName = if (i < streetNames.size) streetNames[i] else null
            val streetChanged = streetName != streetNames.getOrNull(currentStreetIndex)

            if (instructionType != InstructionType.CONTINUE || streetChanged) {
                if (accumulatedDistance >= MIN_TURN_DISTANCE) {
                    instructions.add(
                        NavigationInstruction(
                            type = instructionType,
                            streetName = streetName,
                            distance = accumulatedDistance,
                            location = path[i],
                            bearing = currentBearing
                        )
                    )
                    accumulatedDistance = 0.0
                    if (streetChanged && streetName != null) {
                        currentStreetIndex = i
                    }
                }
            }

            previousBearing = currentBearing
        }

        // Final segment distance
        if (path.size >= 2) {
            accumulatedDistance += path[path.size - 2].distanceTo(path.last())
        }

        // Arrival instruction
        instructions.add(
            NavigationInstruction(
                type = InstructionType.ARRIVED,
                streetName = null,
                distance = accumulatedDistance,
                location = path.last(),
                bearing = 0.0
            )
        )

        return instructions
    }

    /**
     * Calculate bearing from an edge at a specific node
     */
    private fun calculateBearing(traversal: EdgeTraversal, atNode: Long): Double {
        val geometry = if (atNode == traversal.fromNode) {
            traversal.edge.geometry
        } else {
            traversal.edge.geometry.reversed()
        }

        return if (geometry.size >= 2) {
            calculateBearing(geometry[0], geometry[1])
        } else {
            0.0
        }
    }

    /**
     * Calculate bearing between two points (in degrees, 0 = North)
     */
    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)

        val x = sin(dLng) * cos(lat2)
        val y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)

        val bearing = Math.toDegrees(atan2(x, y))
        return (bearing + 360) % 360
    }

    /**
     * Normalize bearing difference to -180 to 180 range
     */
    private fun normalizeBearing(bearing: Double): Double {
        var normalized = bearing % 360
        if (normalized > 180) normalized -= 360
        if (normalized < -180) normalized += 360
        return normalized
    }

    /**
     * Determine the instruction type based on turn angle
     */
    private fun determineInstructionType(turnAngle: Double): InstructionType {
        val absAngle = abs(turnAngle)

        return when {
            absAngle < STRAIGHT_THRESHOLD -> InstructionType.CONTINUE
            absAngle < SLIGHT_TURN_THRESHOLD -> {
                if (turnAngle > 0) InstructionType.SLIGHT_RIGHT else InstructionType.SLIGHT_LEFT
            }
            absAngle < TURN_THRESHOLD -> {
                if (turnAngle > 0) InstructionType.TURN_RIGHT else InstructionType.TURN_LEFT
            }
            absAngle < 160 -> {
                if (turnAngle > 0) InstructionType.SHARP_RIGHT else InstructionType.SHARP_LEFT
            }
            else -> InstructionType.U_TURN
        }
    }

    /**
     * Get human-readable instruction text
     */
    fun getInstructionText(instruction: NavigationInstruction): String {
        val streetPart = instruction.streetName?.let { " onto $it" } ?: ""
        val distancePart = formatDistance(instruction.distance)

        return when (instruction.type) {
            InstructionType.START -> "Start on${instruction.streetName?.let { " $it" } ?: " the route"}"
            InstructionType.CONTINUE -> "Continue$streetPart for $distancePart"
            InstructionType.TURN_LEFT -> "Turn left$streetPart"
            InstructionType.TURN_RIGHT -> "Turn right$streetPart"
            InstructionType.SLIGHT_LEFT -> "Slight left$streetPart"
            InstructionType.SLIGHT_RIGHT -> "Slight right$streetPart"
            InstructionType.SHARP_LEFT -> "Sharp left$streetPart"
            InstructionType.SHARP_RIGHT -> "Sharp right$streetPart"
            InstructionType.U_TURN -> "Make a U-turn$streetPart"
            InstructionType.ARRIVED -> "You have arrived at your destination"
        }
    }

    /**
     * Get short instruction text for display
     */
    fun getShortInstructionText(instruction: NavigationInstruction): String {
        return when (instruction.type) {
            InstructionType.START -> "Start"
            InstructionType.CONTINUE -> "Continue"
            InstructionType.TURN_LEFT -> "Left"
            InstructionType.TURN_RIGHT -> "Right"
            InstructionType.SLIGHT_LEFT -> "Slight L"
            InstructionType.SLIGHT_RIGHT -> "Slight R"
            InstructionType.SHARP_LEFT -> "Sharp L"
            InstructionType.SHARP_RIGHT -> "Sharp R"
            InstructionType.U_TURN -> "U-turn"
            InstructionType.ARRIVED -> "Arrive"
        }
    }

    /**
     * Format distance for display
     */
    fun formatDistance(meters: Double): String {
        return when {
            meters < 1000 -> "${meters.toInt()} m"
            else -> String.format("%.1f km", meters / 1000)
        }
    }

    /**
     * Format estimated time for display
     */
    fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60

        return when {
            hours > 0 -> "${hours}h ${minutes}min"
            else -> "${minutes} min"
        }
    }

    /**
     * Estimate travel time based on distance (assuming city driving)
     */
    fun estimateTravelTime(distanceMeters: Double, averageSpeedKmh: Double = 30.0): Long {
        val hours = distanceMeters / 1000 / averageSpeedKmh
        return (hours * 3600 * 1000).toLong()
    }
}
