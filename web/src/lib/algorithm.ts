import type { LatLng, GraphNode, GraphEdge, StreetGraph, RouteResult, NavigationInstruction, InstructionType } from './types';

/**
 * Chinese Postman Problem solver
 * Finds optimal route to cover all streets with minimal distance
 */
export class ChinesePostmanSolver {

  /**
   * Calculate optimal survey route
   */
  solve(graph: StreetGraph, startNodeId?: number): RouteResult {
    // Step 1: Find odd-degree nodes
    const oddNodes = this.findOddDegreeNodes(graph);

    let circuit: EdgeTraversal[];

    if (oddNodes.length === 0) {
      // Graph is already Eulerian
      const start = startNodeId ?? graph.nodes.keys().next().value;
      circuit = this.findEulerianCircuit(graph, start);
    } else {
      // Step 2: Calculate shortest paths between odd nodes
      const shortestPaths = this.calculateShortestPaths(graph, oddNodes);

      // Step 3: Find minimum weight matching
      const matching = this.findMinimumMatching(oddNodes, shortestPaths);

      // Step 4: Create augmented graph
      const augmented = this.createAugmentedGraph(graph, matching, shortestPaths);

      // Step 5: Find Eulerian circuit
      const start = startNodeId ?? oddNodes[0] ?? graph.nodes.keys().next().value;
      circuit = this.findEulerianCircuit(augmented, start);
    }

    return this.buildResult(graph, circuit);
  }

  private findOddDegreeNodes(graph: StreetGraph): number[] {
    const degrees = new Map<number, number>();

    for (const edge of graph.edges) {
      degrees.set(edge.from, (degrees.get(edge.from) ?? 0) + 1);
      degrees.set(edge.to, (degrees.get(edge.to) ?? 0) + 1);
    }

    return Array.from(degrees.entries())
      .filter(([_, degree]) => degree % 2 === 1)
      .map(([nodeId]) => nodeId);
  }

  private calculateShortestPaths(
    graph: StreetGraph,
    oddNodes: number[]
  ): Map<string, ShortestPath> {
    const paths = new Map<string, ShortestPath>();

    for (let i = 0; i < oddNodes.length; i++) {
      const dijkstraResult = this.dijkstra(graph, oddNodes[i]);

      for (let j = i + 1; j < oddNodes.length; j++) {
        const target = oddNodes[j];
        const path = this.reconstructPath(dijkstraResult, oddNodes[i], target);

        const key1 = `${oddNodes[i]}-${target}`;
        const key2 = `${target}-${oddNodes[i]}`;
        paths.set(key1, path);
        paths.set(key2, { ...path, nodes: [...path.nodes].reverse(), edges: [...path.edges].reverse() });
      }
    }

    return paths;
  }

  private dijkstra(graph: StreetGraph, source: number): DijkstraResult {
    const distances = new Map<number, number>();
    const previous = new Map<number, number | null>();
    const previousEdge = new Map<number, GraphEdge | null>();
    const visited = new Set<number>();

    // Initialize
    for (const nodeId of graph.nodes.keys()) {
      distances.set(nodeId, Infinity);
      previous.set(nodeId, null);
      previousEdge.set(nodeId, null);
    }
    distances.set(source, 0);

    // Priority queue (simple implementation)
    const queue: Array<{ node: number; dist: number }> = [{ node: source, dist: 0 }];

    while (queue.length > 0) {
      queue.sort((a, b) => a.dist - b.dist);
      const { node: current, dist } = queue.shift()!;

      if (visited.has(current)) continue;
      visited.add(current);

      const neighbors = graph.adjacency.get(current) ?? [];
      for (const { neighbor, edge } of neighbors) {
        if (visited.has(neighbor)) continue;

        const newDist = dist + edge.length;
        if (newDist < (distances.get(neighbor) ?? Infinity)) {
          distances.set(neighbor, newDist);
          previous.set(neighbor, current);
          previousEdge.set(neighbor, edge);
          queue.push({ node: neighbor, dist: newDist });
        }
      }
    }

    return { distances, previous, previousEdge };
  }

  private reconstructPath(result: DijkstraResult, source: number, target: number): ShortestPath {
    const nodes: number[] = [];
    const edges: GraphEdge[] = [];
    let distance = 0;

    let current: number | null = target;
    while (current !== null && current !== source) {
      nodes.unshift(current);
      const edge = result.previousEdge.get(current);
      if (edge) {
        edges.unshift(edge);
        distance += edge.length;
      }
      current = result.previous.get(current) ?? null;
    }

    if (current === source) {
      nodes.unshift(source);
    }

    return { source, target, nodes, edges, distance };
  }

  private findMinimumMatching(
    oddNodes: number[],
    shortestPaths: Map<string, ShortestPath>
  ): Array<[number, number]> {
    if (oddNodes.length < 2) return [];
    if (oddNodes.length === 2) return [[oddNodes[0], oddNodes[1]]];

    // For small sets, try all permutations
    if (oddNodes.length <= 10) {
      return this.findOptimalMatching(oddNodes, shortestPaths);
    }

    // Greedy approximation for larger sets
    return this.findGreedyMatching(oddNodes, shortestPaths);
  }

  private findOptimalMatching(
    oddNodes: number[],
    shortestPaths: Map<string, ShortestPath>
  ): Array<[number, number]> {
    let bestMatching: Array<[number, number]> = [];
    let bestCost = Infinity;

    const generateMatchings = (
      remaining: number[],
      current: Array<[number, number]>,
      currentCost: number
    ) => {
      if (remaining.length < 2) {
        if (currentCost < bestCost) {
          bestCost = currentCost;
          bestMatching = [...current];
        }
        return;
      }

      if (currentCost >= bestCost) return; // Prune

      const first = remaining[0];
      for (let i = 1; i < remaining.length; i++) {
        const second = remaining[i];
        const key = `${Math.min(first, second)}-${Math.max(first, second)}`;
        const pathCost = shortestPaths.get(key)?.distance ?? Infinity;

        const newRemaining = remaining.filter((_, idx) => idx !== 0 && idx !== i);
        generateMatchings(newRemaining, [...current, [first, second]], currentCost + pathCost);
      }
    };

    generateMatchings(oddNodes, [], 0);
    return bestMatching;
  }

  private findGreedyMatching(
    oddNodes: number[],
    shortestPaths: Map<string, ShortestPath>
  ): Array<[number, number]> {
    const matching: Array<[number, number]> = [];
    const unmatched = new Set(oddNodes);

    // Sort pairs by distance
    const pairs: Array<{ a: number; b: number; dist: number }> = [];
    for (let i = 0; i < oddNodes.length; i++) {
      for (let j = i + 1; j < oddNodes.length; j++) {
        const key = `${oddNodes[i]}-${oddNodes[j]}`;
        const dist = shortestPaths.get(key)?.distance ?? Infinity;
        pairs.push({ a: oddNodes[i], b: oddNodes[j], dist });
      }
    }
    pairs.sort((a, b) => a.dist - b.dist);

    for (const { a, b } of pairs) {
      if (unmatched.has(a) && unmatched.has(b)) {
        matching.push([a, b]);
        unmatched.delete(a);
        unmatched.delete(b);
        if (unmatched.size === 0) break;
      }
    }

    return matching;
  }

  private createAugmentedGraph(
    original: StreetGraph,
    matching: Array<[number, number]>,
    shortestPaths: Map<string, ShortestPath>
  ): StreetGraph {
    const edges = [...original.edges];
    const adjacency = new Map<number, Array<{ neighbor: number; edge: GraphEdge }>>();

    // Copy original adjacency
    for (const [nodeId, neighbors] of original.adjacency) {
      adjacency.set(nodeId, [...neighbors]);
    }

    // Add edges for matching
    for (const [a, b] of matching) {
      const key = `${a}-${b}`;
      const path = shortestPaths.get(key);
      if (!path) continue;

      for (const edge of path.edges) {
        edges.push(edge);

        const fromNeighbors = adjacency.get(edge.from) ?? [];
        fromNeighbors.push({ neighbor: edge.to, edge });
        adjacency.set(edge.from, fromNeighbors);

        const toNeighbors = adjacency.get(edge.to) ?? [];
        toNeighbors.push({ neighbor: edge.from, edge });
        adjacency.set(edge.to, toNeighbors);
      }
    }

    return { nodes: original.nodes, edges, adjacency };
  }

  private findEulerianCircuit(graph: StreetGraph, startNode: number): EdgeTraversal[] {
    // Build mutable adjacency with usage tracking
    const adjacency = new Map<number, Array<{ neighbor: number; edge: GraphEdge; used: boolean }>>();

    for (const [nodeId, neighbors] of graph.adjacency) {
      adjacency.set(
        nodeId,
        neighbors.map(n => ({ ...n, used: false }))
      );
    }

    const circuit: EdgeTraversal[] = [];
    const stack: number[] = [startNode];
    let current = startNode;

    while (stack.length > 0) {
      const neighbors = adjacency.get(current) ?? [];
      const unusedEdge = neighbors.find(n => !n.used);

      if (unusedEdge) {
        stack.push(current);
        unusedEdge.used = true;

        // Mark reverse edge as used
        const reverseNeighbors = adjacency.get(unusedEdge.neighbor) ?? [];
        const reverseEdge = reverseNeighbors.find(
          n => n.edge.id === unusedEdge.edge.id && n.neighbor === current && !n.used
        );
        if (reverseEdge) reverseEdge.used = true;

        current = unusedEdge.neighbor;
      } else {
        const prev = stack.pop()!;
        if (stack.length > 0 || circuit.length > 0) {
          const prevNeighbors = adjacency.get(prev) ?? [];
          const usedEdge = prevNeighbors.find(n => n.neighbor === current && n.used);
          if (usedEdge) {
            circuit.unshift({
              edge: usedEdge.edge,
              from: prev,
              to: current
            });
          }
        }
        current = prev;
      }
    }

    return circuit;
  }

  private buildResult(graph: StreetGraph, circuit: EdgeTraversal[]): RouteResult {
    const path: LatLng[] = [];
    const edgeOrder: number[] = [];
    let totalDistance = 0;

    for (const traversal of circuit) {
      edgeOrder.push(traversal.edge.id);
      totalDistance += traversal.edge.length;

      // Add geometry in correct direction
      const geometry = traversal.from === traversal.edge.from
        ? traversal.edge.geometry
        : [...traversal.edge.geometry].reverse();

      if (path.length === 0) {
        path.push(...geometry);
      } else {
        path.push(...geometry.slice(1));
      }
    }

    const instructions = this.generateInstructions(circuit, graph);

    return { path, totalDistance, edgeOrder, instructions };
  }

  private generateInstructions(circuit: EdgeTraversal[], graph: StreetGraph): NavigationInstruction[] {
    if (circuit.length === 0) return [];

    const instructions: NavigationInstruction[] = [];

    // Start instruction
    const firstEdge = circuit[0];
    const startNode = graph.nodes.get(firstEdge.from);
    if (startNode) {
      instructions.push({
        type: 'start',
        streetName: firstEdge.edge.name,
        distance: firstEdge.edge.length,
        location: startNode.location,
        bearing: this.calculateBearing(firstEdge.edge.geometry[0], firstEdge.edge.geometry[1] ?? firstEdge.edge.geometry[0])
      });
    }

    let accumulatedDistance = 0;

    for (let i = 0; i < circuit.length - 1; i++) {
      const currentEdge = circuit[i];
      const nextEdge = circuit[i + 1];
      accumulatedDistance += currentEdge.edge.length;

      const currentGeom = currentEdge.from === currentEdge.edge.from
        ? currentEdge.edge.geometry
        : [...currentEdge.edge.geometry].reverse();
      const nextGeom = nextEdge.from === nextEdge.edge.from
        ? nextEdge.edge.geometry
        : [...nextEdge.edge.geometry].reverse();

      const currentBearing = this.calculateBearing(
        currentGeom[currentGeom.length - 2] ?? currentGeom[0],
        currentGeom[currentGeom.length - 1]
      );
      const nextBearing = this.calculateBearing(nextGeom[0], nextGeom[1] ?? nextGeom[0]);

      const turnAngle = this.normalizeBearing(nextBearing - currentBearing);
      const instructionType = this.getInstructionType(turnAngle);
      const streetChanged = currentEdge.edge.name !== nextEdge.edge.name;

      if (instructionType !== 'continue' || streetChanged) {
        const turnNode = graph.nodes.get(currentEdge.to);
        if (turnNode && accumulatedDistance >= 20) {
          instructions.push({
            type: instructionType,
            streetName: nextEdge.edge.name,
            distance: accumulatedDistance,
            location: turnNode.location,
            bearing: nextBearing
          });
          accumulatedDistance = 0;
        }
      }
    }

    // Arrival instruction
    const lastEdge = circuit[circuit.length - 1];
    const endNode = graph.nodes.get(lastEdge.to);
    if (endNode) {
      instructions.push({
        type: 'arrived',
        streetName: null,
        distance: accumulatedDistance + lastEdge.edge.length,
        location: endNode.location,
        bearing: 0
      });
    }

    return instructions;
  }

  private calculateBearing(from: LatLng, to: LatLng): number {
    const lat1 = from.lat * Math.PI / 180;
    const lat2 = to.lat * Math.PI / 180;
    const dLng = (to.lng - from.lng) * Math.PI / 180;

    const x = Math.sin(dLng) * Math.cos(lat2);
    const y = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);

    const bearing = Math.atan2(x, y) * 180 / Math.PI;
    return (bearing + 360) % 360;
  }

  private normalizeBearing(bearing: number): number {
    let normalized = bearing % 360;
    if (normalized > 180) normalized -= 360;
    if (normalized < -180) normalized += 360;
    return normalized;
  }

  private getInstructionType(turnAngle: number): InstructionType {
    const abs = Math.abs(turnAngle);
    if (abs < 15) return 'continue';
    if (abs < 45) return turnAngle > 0 ? 'slight_right' : 'slight_left';
    if (abs < 120) return turnAngle > 0 ? 'turn_right' : 'turn_left';
    if (abs < 160) return turnAngle > 0 ? 'sharp_right' : 'sharp_left';
    return 'u_turn';
  }
}

interface DijkstraResult {
  distances: Map<number, number>;
  previous: Map<number, number | null>;
  previousEdge: Map<number, GraphEdge | null>;
}

interface ShortestPath {
  source: number;
  target: number;
  nodes: number[];
  edges: GraphEdge[];
  distance: number;
}

interface EdgeTraversal {
  edge: GraphEdge;
  from: number;
  to: number;
}
