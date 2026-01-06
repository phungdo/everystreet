package com.everystreet.survey.algorithm

import com.everystreet.survey.data.*
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.abs

/**
 * Implementation of the Chinese Postman Problem (Route Inspection Problem)
 *
 * This algorithm finds the shortest route that visits every edge (street)
 * at least once, which is ideal for street survey applications.
 *
 * The algorithm follows these steps:
 * 1. Find all odd-degree nodes in the graph
 * 2. Calculate shortest paths between all pairs of odd-degree nodes
 * 3. Find minimum weight perfect matching of odd-degree nodes
 * 4. Add matched edges to create an Eulerian graph
 * 5. Find an Eulerian circuit using Hierholzer's algorithm
 */
class ChinesePostmanSolver {

    /**
     * Calculate the optimal survey route for the given street network
     */
    fun calculateOptimalRoute(
        graph: StreetGraph,
        startNodeId: Long? = null
    ): OptimalRouteResult {
        // Step 1: Find odd-degree nodes
        val oddNodes = findOddDegreeNodes(graph)

        if (oddNodes.isEmpty()) {
            // Graph is already Eulerian - find circuit directly
            val circuit = findEulerianCircuit(graph, startNodeId ?: graph.nodes.keys.first())
            return buildRouteResult(graph, circuit)
        }

        // Step 2: Calculate shortest paths between all pairs of odd nodes
        val shortestPaths = calculateShortestPathsBetweenOddNodes(graph, oddNodes)

        // Step 3: Find minimum weight perfect matching
        val matching = findMinimumWeightMatching(oddNodes, shortestPaths)

        // Step 4: Create augmented graph with extra edges for matching
        val augmentedGraph = createAugmentedGraph(graph, matching, shortestPaths)

        // Step 5: Find Eulerian circuit in augmented graph
        val startNode = startNodeId ?: findBestStartNode(graph, oddNodes)
        val circuit = findEulerianCircuit(augmentedGraph, startNode)

        return buildRouteResult(graph, circuit)
    }

    /**
     * Find all nodes with odd degree (odd number of edges)
     */
    private fun findOddDegreeNodes(graph: StreetGraph): List<Long> {
        val degrees = HashMap<Long, Int>()

        for (edge in graph.edges) {
            degrees[edge.fromNode] = (degrees[edge.fromNode] ?: 0) + 1
            degrees[edge.toNode] = (degrees[edge.toNode] ?: 0) + 1
        }

        return degrees.filter { it.value % 2 == 1 }.keys.toList()
    }

    /**
     * Calculate shortest paths between all pairs of odd-degree nodes using Dijkstra
     */
    private fun calculateShortestPathsBetweenOddNodes(
        graph: StreetGraph,
        oddNodes: List<Long>
    ): Map<Pair<Long, Long>, ShortestPath> {
        val paths = HashMap<Pair<Long, Long>, ShortestPath>()

        for (i in oddNodes.indices) {
            val distances = dijkstra(graph, oddNodes[i])
            for (j in (i + 1) until oddNodes.size) {
                val targetNode = oddNodes[j]
                val path = reconstructPath(distances, oddNodes[i], targetNode)
                paths[Pair(oddNodes[i], targetNode)] = path
                paths[Pair(targetNode, oddNodes[i])] = path.reversed()
            }
        }

        return paths
    }

    /**
     * Dijkstra's algorithm for shortest paths from a source node
     */
    private fun dijkstra(graph: StreetGraph, source: Long): DijkstraResult {
        val distances = HashMap<Long, Double>()
        val previous = HashMap<Long, Long?>()
        val previousEdge = HashMap<Long, GraphEdge?>()
        val visited = HashSet<Long>()

        // Priority queue: (distance, nodeId)
        val pq = PriorityQueue<Pair<Double, Long>>(compareBy { it.first })

        // Initialize
        for (nodeId in graph.nodes.keys) {
            distances[nodeId] = Double.MAX_VALUE
            previous[nodeId] = null
        }
        distances[source] = 0.0
        pq.add(Pair(0.0, source))

        while (pq.isNotEmpty()) {
            val (dist, current) = pq.poll()

            if (current in visited) continue
            visited.add(current)

            // Get neighbors
            val neighbors = graph.adjacencyList[current] ?: continue

            for ((neighbor, edge) in neighbors) {
                if (neighbor in visited) continue

                val newDist = dist + edge.length
                if (newDist < (distances[neighbor] ?: Double.MAX_VALUE)) {
                    distances[neighbor] = newDist
                    previous[neighbor] = current
                    previousEdge[neighbor] = edge
                    pq.add(Pair(newDist, neighbor))
                }
            }
        }

        return DijkstraResult(distances, previous, previousEdge)
    }

    /**
     * Reconstruct the shortest path from Dijkstra results
     */
    private fun reconstructPath(
        dijkstraResult: DijkstraResult,
        source: Long,
        target: Long
    ): ShortestPath {
        val nodes = mutableListOf<Long>()
        val edges = mutableListOf<GraphEdge>()
        var distance = 0.0

        var current: Long? = target
        while (current != null && current != source) {
            nodes.add(0, current)
            val edge = dijkstraResult.previousEdge[current]
            if (edge != null) {
                edges.add(0, edge)
                distance += edge.length
            }
            current = dijkstraResult.previous[current]
        }

        if (current == source) {
            nodes.add(0, source)
        }

        return ShortestPath(source, target, nodes, edges, distance)
    }

    /**
     * Find minimum weight perfect matching of odd-degree nodes
     * Uses a greedy approximation algorithm
     */
    private fun findMinimumWeightMatching(
        oddNodes: List<Long>,
        shortestPaths: Map<Pair<Long, Long>, ShortestPath>
    ): List<Pair<Long, Long>> {
        if (oddNodes.size < 2) return emptyList()

        // For small numbers of odd nodes, try all permutations
        if (oddNodes.size <= 10) {
            return findOptimalMatching(oddNodes, shortestPaths)
        }

        // For larger sets, use greedy approximation
        return findGreedyMatching(oddNodes, shortestPaths)
    }

    /**
     * Find optimal matching by trying all possibilities (for small sets)
     */
    private fun findOptimalMatching(
        oddNodes: List<Long>,
        shortestPaths: Map<Pair<Long, Long>, ShortestPath>
    ): List<Pair<Long, Long>> {
        val n = oddNodes.size
        if (n == 0) return emptyList()
        if (n == 2) return listOf(Pair(oddNodes[0], oddNodes[1]))

        var bestMatching: List<Pair<Long, Long>>? = null
        var bestCost = Double.MAX_VALUE

        fun generateMatchings(
            remaining: List<Long>,
            current: List<Pair<Long, Long>>,
            currentCost: Double
        ) {
            if (remaining.size < 2) {
                if (currentCost < bestCost) {
                    bestCost = currentCost
                    bestMatching = current.toList()
                }
                return
            }

            // Pruning: if current cost already exceeds best, skip
            if (currentCost >= bestCost) return

            val first = remaining[0]
            for (i in 1 until remaining.size) {
                val second = remaining[i]
                val key = if (first < second) Pair(first, second) else Pair(second, first)
                val pathCost = shortestPaths[key]?.distance ?: Double.MAX_VALUE

                val newRemaining = remaining.toMutableList()
                newRemaining.removeAt(i)
                newRemaining.removeAt(0)

                generateMatchings(
                    newRemaining,
                    current + Pair(first, second),
                    currentCost + pathCost
                )
            }
        }

        generateMatchings(oddNodes, emptyList(), 0.0)
        return bestMatching ?: emptyList()
    }

    /**
     * Greedy approximation for minimum weight matching
     */
    private fun findGreedyMatching(
        oddNodes: List<Long>,
        shortestPaths: Map<Pair<Long, Long>, ShortestPath>
    ): List<Pair<Long, Long>> {
        val matching = mutableListOf<Pair<Long, Long>>()
        val unmatched = oddNodes.toMutableSet()

        // Sort all pairs by distance
        val pairs = mutableListOf<Triple<Long, Long, Double>>()
        for (i in oddNodes.indices) {
            for (j in (i + 1) until oddNodes.size) {
                val key = Pair(oddNodes[i], oddNodes[j])
                val distance = shortestPaths[key]?.distance ?: Double.MAX_VALUE
                pairs.add(Triple(oddNodes[i], oddNodes[j], distance))
            }
        }
        pairs.sortBy { it.third }

        // Greedily match closest unmatched pairs
        for ((a, b, _) in pairs) {
            if (a in unmatched && b in unmatched) {
                matching.add(Pair(a, b))
                unmatched.remove(a)
                unmatched.remove(b)

                if (unmatched.isEmpty()) break
            }
        }

        return matching
    }

    /**
     * Create augmented graph by adding edges for matched pairs
     */
    private fun createAugmentedGraph(
        originalGraph: StreetGraph,
        matching: List<Pair<Long, Long>>,
        shortestPaths: Map<Pair<Long, Long>, ShortestPath>
    ): AugmentedGraph {
        val edgeCopies = HashMap<Long, Int>()
        val additionalEdges = mutableListOf<GraphEdge>()

        // Count original edges
        for (edge in originalGraph.edges) {
            edgeCopies[edge.id] = 1
        }

        // Add duplicate edges for matching paths
        for ((a, b) in matching) {
            val key = if (a < b) Pair(a, b) else Pair(b, a)
            val path = shortestPaths[key] ?: continue

            for (edge in path.edges) {
                edgeCopies[edge.id] = (edgeCopies[edge.id] ?: 0) + 1
                additionalEdges.add(edge)
            }
        }

        // Build augmented adjacency list
        val augmentedAdjacency = HashMap<Long, MutableList<Pair<Long, GraphEdge>>>()

        // Add original edges
        for ((nodeId, neighbors) in originalGraph.adjacencyList) {
            augmentedAdjacency[nodeId] = neighbors.toMutableList()
        }

        // Add additional edges for matching
        for (edge in additionalEdges) {
            augmentedAdjacency.getOrPut(edge.fromNode) { mutableListOf() }
                .add(Pair(edge.toNode, edge))
            augmentedAdjacency.getOrPut(edge.toNode) { mutableListOf() }
                .add(Pair(edge.fromNode, edge))
        }

        return AugmentedGraph(
            originalGraph.nodes,
            originalGraph.edges + additionalEdges,
            augmentedAdjacency,
            edgeCopies
        )
    }

    /**
     * Find Eulerian circuit using Hierholzer's algorithm
     */
    private fun findEulerianCircuit(
        graph: StreetGraph,
        startNode: Long
    ): List<EdgeTraversal> {
        // Build mutable adjacency list with edge usage tracking
        val adjacency = HashMap<Long, MutableList<EdgeWithUsage>>()

        for (edge in graph.edges) {
            adjacency.getOrPut(edge.fromNode) { mutableListOf() }
                .add(EdgeWithUsage(edge, edge.toNode, false))
            adjacency.getOrPut(edge.toNode) { mutableListOf() }
                .add(EdgeWithUsage(edge, edge.fromNode, false))
        }

        val circuit = mutableListOf<EdgeTraversal>()
        val stack = Stack<Long>()
        stack.push(startNode)

        var currentNode = startNode

        while (stack.isNotEmpty()) {
            val neighbors = adjacency[currentNode] ?: mutableListOf()
            val unusedEdge = neighbors.find { !it.used }

            if (unusedEdge != null) {
                stack.push(currentNode)
                unusedEdge.used = true

                // Mark the reverse edge as used too
                val reverseNeighbors = adjacency[unusedEdge.targetNode] ?: mutableListOf()
                reverseNeighbors.find {
                    it.edge.id == unusedEdge.edge.id &&
                    it.targetNode == currentNode &&
                    !it.used
                }?.used = true

                currentNode = unusedEdge.targetNode
            } else {
                val prevNode = stack.pop()
                if (stack.isNotEmpty() || circuit.isNotEmpty()) {
                    // Find the edge we came from
                    val usedEdge = adjacency[prevNode]?.find {
                        it.targetNode == currentNode && it.used
                    }
                    if (usedEdge != null) {
                        circuit.add(0, EdgeTraversal(usedEdge.edge, prevNode, currentNode))
                    }
                }
                currentNode = prevNode
            }
        }

        return circuit
    }

    /**
     * Find Eulerian circuit in augmented graph
     */
    private fun findEulerianCircuit(
        graph: AugmentedGraph,
        startNode: Long
    ): List<EdgeTraversal> {
        // Build mutable adjacency list with edge usage tracking
        val adjacency = HashMap<Long, MutableList<EdgeWithUsage>>()

        for ((nodeId, neighbors) in graph.augmentedAdjacency) {
            adjacency[nodeId] = neighbors.map { (targetNode, edge) ->
                EdgeWithUsage(edge, targetNode, false)
            }.toMutableList()
        }

        val circuit = mutableListOf<EdgeTraversal>()
        val stack = Stack<Long>()
        stack.push(startNode)

        var currentNode = startNode

        while (stack.isNotEmpty()) {
            val neighbors = adjacency[currentNode] ?: mutableListOf()
            val unusedEdge = neighbors.find { !it.used }

            if (unusedEdge != null) {
                stack.push(currentNode)
                unusedEdge.used = true

                // Mark one reverse edge as used too
                val reverseNeighbors = adjacency[unusedEdge.targetNode] ?: mutableListOf()
                reverseNeighbors.find {
                    it.edge.id == unusedEdge.edge.id &&
                    it.targetNode == currentNode &&
                    !it.used
                }?.used = true

                currentNode = unusedEdge.targetNode
            } else {
                if (stack.isNotEmpty()) {
                    val prevNode = stack.pop()
                    // Find the edge we came from
                    val neighbors2 = adjacency[prevNode]
                    if (neighbors2 != null) {
                        for (edgeUsage in neighbors2) {
                            if (edgeUsage.targetNode == currentNode && edgeUsage.used) {
                                circuit.add(0, EdgeTraversal(edgeUsage.edge, prevNode, currentNode))
                                break
                            }
                        }
                    }
                    currentNode = prevNode
                } else {
                    break
                }
            }
        }

        return circuit
    }

    /**
     * Find the best starting node (prefer nodes close to user location or first odd node)
     */
    private fun findBestStartNode(graph: StreetGraph, oddNodes: List<Long>): Long {
        return oddNodes.firstOrNull() ?: graph.nodes.keys.first()
    }

    /**
     * Build the final route result from the Eulerian circuit
     */
    private fun buildRouteResult(
        graph: StreetGraph,
        circuit: List<EdgeTraversal>
    ): OptimalRouteResult {
        val path = mutableListOf<LatLng>()
        val edgeOrder = mutableListOf<Long>()
        var totalDistance = 0.0
        val streetCounts = HashMap<Long, Int>()

        for (traversal in circuit) {
            edgeOrder.add(traversal.edge.id)
            streetCounts[traversal.edge.id] = (streetCounts[traversal.edge.id] ?: 0) + 1
            totalDistance += traversal.edge.length

            // Add geometry points in correct direction
            val geometry = if (traversal.fromNode == traversal.edge.fromNode) {
                traversal.edge.geometry
            } else {
                traversal.edge.geometry.reversed()
            }

            // Add points (skip first if continuing from previous)
            if (path.isEmpty()) {
                path.addAll(geometry)
            } else {
                path.addAll(geometry.drop(1))
            }
        }

        val duplicateStreets = streetCounts.filter { it.value > 1 }.keys.toList()

        return OptimalRouteResult(
            path = path,
            edgeOrder = edgeOrder,
            totalDistance = totalDistance,
            originalDistance = graph.edges.sumOf { it.length },
            duplicateStreets = duplicateStreets,
            circuit = circuit
        )
    }
}

/**
 * Helper data classes
 */
data class DijkstraResult(
    val distances: Map<Long, Double>,
    val previous: Map<Long, Long?>,
    val previousEdge: Map<Long, GraphEdge?>
)

data class ShortestPath(
    val source: Long,
    val target: Long,
    val nodes: List<Long>,
    val edges: List<GraphEdge>,
    val distance: Double
) {
    fun reversed(): ShortestPath {
        return ShortestPath(
            source = target,
            target = source,
            nodes = nodes.reversed(),
            edges = edges.reversed(),
            distance = distance
        )
    }
}

data class AugmentedGraph(
    val nodes: Map<Long, GraphNode>,
    val edges: List<GraphEdge>,
    val augmentedAdjacency: Map<Long, MutableList<Pair<Long, GraphEdge>>>,
    val edgeCopies: Map<Long, Int>
)

data class EdgeWithUsage(
    val edge: GraphEdge,
    val targetNode: Long,
    var used: Boolean
)

data class EdgeTraversal(
    val edge: GraphEdge,
    val fromNode: Long,
    val toNode: Long
)

data class OptimalRouteResult(
    val path: List<LatLng>,
    val edgeOrder: List<Long>,
    val totalDistance: Double,
    val originalDistance: Double,
    val duplicateStreets: List<Long>,
    val circuit: List<EdgeTraversal>
) {
    val extraDistance: Double get() = totalDistance - originalDistance
    val efficiency: Double get() = if (totalDistance > 0) originalDistance / totalDistance else 1.0
}
