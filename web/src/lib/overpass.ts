import type { LatLng, StreetGraph, GraphNode, GraphEdge } from './types';

const OVERPASS_URL = 'https://overpass-api.de/api/interpreter';

const HIGHWAY_TYPES = [
  'primary', 'secondary', 'tertiary',
  'unclassified', 'residential', 'living_street', 'service'
].join('|');

export async function fetchStreetNetwork(
  center: LatLng,
  radiusMeters: number,
  onProgress?: (message: string) => void
): Promise<StreetGraph> {
  onProgress?.('Fetching street data from OpenStreetMap...');

  // Calculate bounding box
  const latOffset = radiusMeters / 111320;
  const lngOffset = radiusMeters / (111320 * Math.cos(center.lat * Math.PI / 180));

  const south = center.lat - latOffset;
  const north = center.lat + latOffset;
  const west = center.lng - lngOffset;
  const east = center.lng + lngOffset;

  const query = `
    [out:json][timeout:120];
    (
      way["highway"~"${HIGHWAY_TYPES}"]
        ["access"!~"private|no"]
        ["motor_vehicle"!~"no|private"]
        (${south},${west},${north},${east});
    );
    out body;
    >;
    out skel qt;
  `;

  const response = await fetch(OVERPASS_URL, {
    method: 'POST',
    body: query,
    headers: {
      'Content-Type': 'text/plain'
    }
  });

  if (!response.ok) {
    throw new Error(`Failed to fetch streets: ${response.status}`);
  }

  onProgress?.('Parsing street data...');

  const data = await response.json();
  return parseOverpassResponse(data, onProgress);
}

function parseOverpassResponse(
  data: OverpassResponse,
  onProgress?: (message: string) => void
): StreetGraph {
  const nodes = new Map<number, GraphNode>();
  const edges: GraphEdge[] = [];
  const adjacency = new Map<number, Array<{ neighbor: number; edge: GraphEdge }>>();

  // First pass: collect all node locations
  const nodeLocations = new Map<number, LatLng>();
  for (const element of data.elements) {
    if (element.type === 'node') {
      nodeLocations.set(element.id, { lat: element.lat!, lng: element.lon! });
    }
  }

  onProgress?.(`Found ${nodeLocations.size} nodes, processing streets...`);

  // Second pass: process ways
  let edgeId = 0;
  for (const element of data.elements) {
    if (element.type !== 'way') continue;

    const nodeRefs = element.nodes ?? [];
    const name = element.tags?.name ?? null;

    // Create edges for each segment
    for (let i = 0; i < nodeRefs.length - 1; i++) {
      const fromId = nodeRefs[i];
      const toId = nodeRefs[i + 1];

      const fromLoc = nodeLocations.get(fromId);
      const toLoc = nodeLocations.get(toId);
      if (!fromLoc || !toLoc) continue;

      // Create/update nodes
      if (!nodes.has(fromId)) {
        nodes.set(fromId, { id: fromId, location: fromLoc, degree: 0 });
      }
      if (!nodes.has(toId)) {
        nodes.set(toId, { id: toId, location: toLoc, degree: 0 });
      }

      nodes.get(fromId)!.degree++;
      nodes.get(toId)!.degree++;

      const length = calculateDistance(fromLoc, toLoc);

      const edge: GraphEdge = {
        id: edgeId++,
        from: fromId,
        to: toId,
        name,
        length,
        geometry: [fromLoc, toLoc]
      };

      edges.push(edge);

      // Add to adjacency list (both directions for undirected graph)
      if (!adjacency.has(fromId)) adjacency.set(fromId, []);
      if (!adjacency.has(toId)) adjacency.set(toId, []);

      adjacency.get(fromId)!.push({ neighbor: toId, edge });
      adjacency.get(toId)!.push({ neighbor: fromId, edge });
    }
  }

  onProgress?.(`Processed ${edges.length} street segments`);

  return { nodes, edges, adjacency };
}

export function calculateDistance(from: LatLng, to: LatLng): number {
  const R = 6371000; // Earth radius in meters
  const dLat = (to.lat - from.lat) * Math.PI / 180;
  const dLng = (to.lng - from.lng) * Math.PI / 180;

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(from.lat * Math.PI / 180) *
    Math.cos(to.lat * Math.PI / 180) *
    Math.sin(dLng / 2) * Math.sin(dLng / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

interface OverpassResponse {
  elements: OverpassElement[];
}

interface OverpassElement {
  type: 'node' | 'way' | 'relation';
  id: number;
  lat?: number;
  lon?: number;
  nodes?: number[];
  tags?: Record<string, string>;
}
