export interface LatLng {
  lat: number;
  lng: number;
}

export interface GraphNode {
  id: number;
  location: LatLng;
  degree: number;
}

export interface GraphEdge {
  id: number;
  from: number;
  to: number;
  name: string | null;
  length: number;
  geometry: LatLng[];
}

export interface StreetGraph {
  nodes: Map<number, GraphNode>;
  edges: GraphEdge[];
  adjacency: Map<number, Array<{ neighbor: number; edge: GraphEdge }>>;
}

export interface RouteResult {
  path: LatLng[];
  totalDistance: number;
  edgeOrder: number[];
  instructions: NavigationInstruction[];
}

export interface NavigationInstruction {
  type: InstructionType;
  streetName: string | null;
  distance: number;
  location: LatLng;
  bearing: number;
}

export type InstructionType =
  | 'start'
  | 'continue'
  | 'turn_left'
  | 'turn_right'
  | 'slight_left'
  | 'slight_right'
  | 'sharp_left'
  | 'sharp_right'
  | 'u_turn'
  | 'arrived';

export interface SurveyState {
  status: 'idle' | 'selecting' | 'calculating' | 'ready' | 'navigating' | 'paused';
  route: RouteResult | null;
  currentInstructionIndex: number;
  distanceCovered: number;
  currentLocation: LatLng | null;
}
