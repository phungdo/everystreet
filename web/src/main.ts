import type { LatLng, RouteResult, SurveyState, NavigationInstruction } from './lib/types';
import { ChinesePostmanSolver } from './lib/algorithm';
import { fetchStreetNetwork, calculateDistance } from './lib/overpass';
import { VoiceGuidance, getInstructionIcon, getInstructionText, formatDistance, formatTime } from './lib/voice';

declare const L: typeof import('leaflet');

// State
let state: SurveyState = {
  status: 'idle',
  route: null,
  currentInstructionIndex: 0,
  distanceCovered: 0,
  currentLocation: null
};

// Services
const voice = new VoiceGuidance();
const solver = new ChinesePostmanSolver();

// Map elements
let map: L.Map;
let routePolyline: L.Polyline | null = null;
let selectionCircle: L.Circle | null = null;
let selectionCenter: LatLng | null = null;
let locationMarker: L.CircleMarker | null = null;
let watchId: number | null = null;

// DOM Elements
const $ = (id: string) => document.getElementById(id)!;

// Initialize app
document.addEventListener('DOMContentLoaded', () => {
  initMap();
  initEventListeners();
  requestLocationPermission();
});

function initMap() {
  map = L.map('map', {
    zoomControl: false
  }).setView([51.505, -0.09], 15);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19
  }).addTo(map);

  // Add zoom control to bottom right
  L.control.zoom({ position: 'bottomright' }).addTo(map);

  // Map click handler for area selection
  map.on('click', (e: L.LeafletMouseEvent) => {
    if (state.status === 'selecting') {
      selectionCenter = { lat: e.latlng.lat, lng: e.latlng.lng };
      updateSelectionCircle();
      ($('confirmAreaBtn') as HTMLButtonElement).disabled = false;
    }
  });
}

function initEventListeners() {
  // New survey button
  $('newSurveyBtn').addEventListener('click', () => {
    state.status = 'selecting';
    $('areaOverlay').classList.add('active');
    selectionCenter = null;
    ($('confirmAreaBtn') as HTMLButtonElement).disabled = true;
  });

  // Cancel area selection
  $('cancelAreaBtn').addEventListener('click', () => {
    state.status = 'idle';
    $('areaOverlay').classList.remove('active');
    if (selectionCircle) {
      map.removeLayer(selectionCircle);
      selectionCircle = null;
    }
  });

  // Radius slider
  $('radiusSlider').addEventListener('input', (e) => {
    const value = (e.target as HTMLInputElement).value;
    $('radiusValue').textContent = value;
    updateSelectionCircle();
  });

  // Confirm area and calculate route
  $('confirmAreaBtn').addEventListener('click', () => {
    if (selectionCenter) {
      const radius = parseInt(($('radiusSlider') as HTMLInputElement).value);
      calculateRoute(selectionCenter, radius);
    }
  });

  // Voice toggle
  $('voiceToggle').addEventListener('click', () => {
    const enabled = !voice.isEnabled();
    voice.setEnabled(enabled);
    $('voiceToggle').textContent = enabled ? '🔊' : '🔇';
    $('voiceToggle').classList.toggle('muted', !enabled);
  });

  // Start navigation
  $('startBtn').addEventListener('click', startNavigation);

  // Pause navigation
  $('pauseBtn').addEventListener('click', () => {
    if (state.status === 'navigating') {
      state.status = 'paused';
      $('pauseBtn').textContent = 'Resume';
      voice.speak('Navigation paused');
    } else if (state.status === 'paused') {
      state.status = 'navigating';
      $('pauseBtn').textContent = 'Pause';
      voice.speak('Navigation resumed');
    }
  });

  // Stop navigation
  $('stopBtn').addEventListener('click', () => {
    if (confirm('Stop navigation? Your progress will be lost.')) {
      stopNavigation();
    }
  });
}

function requestLocationPermission() {
  if (!navigator.geolocation) {
    updateGpsStatus(false, 'GPS not supported');
    return;
  }

  navigator.geolocation.getCurrentPosition(
    (position) => {
      const loc: LatLng = {
        lat: position.coords.latitude,
        lng: position.coords.longitude
      };
      state.currentLocation = loc;
      map.setView([loc.lat, loc.lng], 16);
      updateGpsStatus(true, 'GPS Active');
      updateLocationMarker(loc);
    },
    (error) => {
      console.error('Location error:', error);
      updateGpsStatus(false, 'GPS unavailable');
    },
    { enableHighAccuracy: true }
  );
}

function startLocationTracking() {
  if (watchId !== null) return;

  watchId = navigator.geolocation.watchPosition(
    (position) => {
      const loc: LatLng = {
        lat: position.coords.latitude,
        lng: position.coords.longitude
      };
      state.currentLocation = loc;
      updateLocationMarker(loc);
      updateGpsStatus(true, 'GPS Active');

      if (state.status === 'navigating') {
        updateNavigation(loc);
      }
    },
    (error) => {
      console.error('Watch error:', error);
      updateGpsStatus(false, 'GPS error');
    },
    {
      enableHighAccuracy: true,
      maximumAge: 1000,
      timeout: 10000
    }
  );
}

function stopLocationTracking() {
  if (watchId !== null) {
    navigator.geolocation.clearWatch(watchId);
    watchId = null;
  }
}

function updateGpsStatus(active: boolean, text: string) {
  $('gpsDot').classList.toggle('active', active);
  $('gpsText').textContent = text;
}

function updateLocationMarker(loc: LatLng) {
  if (locationMarker) {
    locationMarker.setLatLng([loc.lat, loc.lng]);
  } else {
    locationMarker = L.circleMarker([loc.lat, loc.lng], {
      radius: 8,
      fillColor: '#2196F3',
      fillOpacity: 1,
      color: 'white',
      weight: 3
    }).addTo(map);
  }
}

function updateSelectionCircle() {
  if (!selectionCenter) return;

  const radius = parseInt(($('radiusSlider') as HTMLInputElement).value);

  if (selectionCircle) {
    selectionCircle.setLatLng([selectionCenter.lat, selectionCenter.lng]);
    selectionCircle.setRadius(radius);
  } else {
    selectionCircle = L.circle([selectionCenter.lat, selectionCenter.lng], {
      radius: radius,
      color: '#2196F3',
      fillColor: '#2196F3',
      fillOpacity: 0.2,
      weight: 2
    }).addTo(map);
  }

  map.fitBounds(selectionCircle.getBounds(), { padding: [50, 50] });
}

async function calculateRoute(center: LatLng, radiusMeters: number) {
  state.status = 'calculating';
  $('areaOverlay').classList.remove('active');
  showLoading(true, 'Calculating route...');

  try {
    // Fetch street network
    const graph = await fetchStreetNetwork(center, radiusMeters, (msg) => {
      $('loadingProgress').textContent = msg;
    });

    if (graph.edges.length === 0) {
      throw new Error('No streets found in selected area');
    }

    $('loadingProgress').textContent = `Found ${graph.edges.length} streets. Calculating optimal route...`;

    // Find closest node to center as start
    let closestNodeId: number | undefined;
    let closestDist = Infinity;
    for (const [id, node] of graph.nodes) {
      const dist = calculateDistance(center, node.location);
      if (dist < closestDist) {
        closestDist = dist;
        closestNodeId = id;
      }
    }

    // Calculate optimal route
    const route = solver.solve(graph, closestNodeId);

    state.route = route;
    state.status = 'ready';
    state.currentInstructionIndex = 0;
    state.distanceCovered = 0;

    // Clear selection circle
    if (selectionCircle) {
      map.removeLayer(selectionCircle);
      selectionCircle = null;
    }

    // Draw route on map
    drawRoute(route);

    // Update UI
    updateStats(route);
    ($('startBtn') as HTMLButtonElement).disabled = false;

    showLoading(false);

  } catch (error) {
    console.error('Route calculation error:', error);
    showLoading(false);
    alert(`Failed to calculate route: ${(error as Error).message}`);
    state.status = 'idle';
  }
}

function drawRoute(route: RouteResult) {
  // Remove existing route
  if (routePolyline) {
    map.removeLayer(routePolyline);
  }

  // Draw new route
  const latLngs = route.path.map(p => [p.lat, p.lng] as [number, number]);
  routePolyline = L.polyline(latLngs, {
    color: '#2196F3',
    weight: 5,
    opacity: 0.8
  }).addTo(map);

  // Fit map to route
  map.fitBounds(routePolyline.getBounds(), { padding: [50, 100] });

  // Add start/end markers
  if (route.path.length > 0) {
    L.marker([route.path[0].lat, route.path[0].lng])
      .bindPopup('Start')
      .addTo(map);
  }
}

function updateStats(route: RouteResult) {
  $('distanceValue').textContent = formatDistance(route.totalDistance);
  $('streetsValue').textContent = `${route.edgeOrder.length}`;

  // Estimate time at 30 km/h average
  const timeSeconds = (route.totalDistance / 1000) / 30 * 3600;
  $('timeValue').textContent = formatTime(timeSeconds);
}

function startNavigation() {
  if (!state.route) return;

  state.status = 'navigating';
  state.currentInstructionIndex = 0;
  state.distanceCovered = 0;

  // Start GPS tracking
  startLocationTracking();

  // Show navigation UI
  $('mainControls').style.display = 'none';
  $('navControls').style.display = 'flex';
  $('instructionCard').classList.add('active');
  $('progressBar').style.display = 'block';

  // Initial instruction
  updateInstructionDisplay();

  const firstInstruction = state.route.instructions[0];
  voice.announceStart(firstInstruction?.streetName ?? null);

  // Center map on current location
  if (state.currentLocation) {
    map.setView([state.currentLocation.lat, state.currentLocation.lng], 17);
  }
}

function stopNavigation() {
  state.status = 'idle';
  stopLocationTracking();
  voice.reset();

  // Hide navigation UI
  $('mainControls').style.display = 'flex';
  $('navControls').style.display = 'none';
  $('instructionCard').classList.remove('active');
  $('progressBar').style.display = 'none';
  $('pauseBtn').textContent = 'Pause';
}

function updateNavigation(location: LatLng) {
  if (!state.route || state.status !== 'navigating') return;

  const route = state.route;

  // Find closest point on route
  let closestIdx = 0;
  let closestDist = Infinity;
  for (let i = 0; i < route.path.length; i++) {
    const dist = calculateDistance(location, route.path[i]);
    if (dist < closestDist) {
      closestDist = dist;
      closestIdx = i;
    }
  }

  // Check if off route
  const isOffRoute = closestDist > 50;
  if (isOffRoute) {
    voice.announceOffRoute();
  }

  // Calculate distance covered
  let covered = 0;
  for (let i = 0; i < closestIdx && i < route.path.length - 1; i++) {
    covered += calculateDistance(route.path[i], route.path[i + 1]);
  }
  state.distanceCovered = covered;

  // Find current instruction
  let currentInstIdx = 0;
  for (let i = route.instructions.length - 1; i >= 0; i--) {
    const instLoc = route.instructions[i].location;
    const distToInst = calculateDistance(location, instLoc);
    if (distToInst < 30) {
      currentInstIdx = i;
      break;
    }
    // Check if we've passed this instruction
    const instPointIdx = findClosestPathIndex(route.path, instLoc);
    if (closestIdx >= instPointIdx) {
      currentInstIdx = i;
      break;
    }
  }

  state.currentInstructionIndex = currentInstIdx;

  // Announce upcoming instruction
  const nextInstIdx = Math.min(currentInstIdx + 1, route.instructions.length - 1);
  const nextInst = route.instructions[nextInstIdx];
  if (nextInst) {
    const distToNext = calculateDistance(location, nextInst.location);

    // Announce at 100m, 50m, and 20m
    if (distToNext < 100 && distToNext > 80) {
      voice.announceApproaching(nextInst, distToNext);
    } else if (distToNext < 50 && distToNext > 30) {
      voice.announceApproaching(nextInst, distToNext);
    } else if (distToNext < 25) {
      voice.announceInstruction(nextInst, distToNext, nextInstIdx);
    }
  }

  // Check if arrived
  const lastPoint = route.path[route.path.length - 1];
  const distToEnd = calculateDistance(location, lastPoint);
  if (distToEnd < 20 && closestIdx > route.path.length - 10) {
    voice.announceArrival();
    completeNavigation();
    return;
  }

  // Update UI
  updateInstructionDisplay();
  updateProgress();

  // Center map on location
  map.panTo([location.lat, location.lng]);
}

function findClosestPathIndex(path: LatLng[], point: LatLng): number {
  let closestIdx = 0;
  let closestDist = Infinity;
  for (let i = 0; i < path.length; i++) {
    const dist = calculateDistance(point, path[i]);
    if (dist < closestDist) {
      closestDist = dist;
      closestIdx = i;
    }
  }
  return closestIdx;
}

function updateInstructionDisplay() {
  if (!state.route) return;

  const instructions = state.route.instructions;
  const current = instructions[state.currentInstructionIndex];
  const next = instructions[Math.min(state.currentInstructionIndex + 1, instructions.length - 1)];

  if (current) {
    $('instructionIcon').textContent = getInstructionIcon(current.type);
    $('instructionDirection').textContent = getInstructionText(current.type);
    $('instructionStreet').textContent = current.streetName ?? '';

    if (state.currentLocation && next) {
      const dist = calculateDistance(state.currentLocation, next.location);
      $('instructionDistance').textContent = formatDistance(dist);
    }
  }
}

function updateProgress() {
  if (!state.route) return;

  const progress = state.distanceCovered / state.route.totalDistance;
  $('progressFill').style.width = `${Math.min(progress * 100, 100)}%`;

  // Update stats with remaining
  const remaining = state.route.totalDistance - state.distanceCovered;
  $('distanceValue').textContent = formatDistance(remaining);

  const timeRemaining = (remaining / 1000) / 30 * 3600;
  $('timeValue').textContent = formatTime(timeRemaining);
}

function completeNavigation() {
  state.status = 'idle';
  stopLocationTracking();

  // Show completion message
  alert('Survey complete! All streets have been covered.');

  // Reset UI
  $('mainControls').style.display = 'flex';
  $('navControls').style.display = 'none';
  $('instructionCard').classList.remove('active');
  $('progressBar').style.display = 'none';
  $('progressFill').style.width = '100%';
}

function showLoading(show: boolean, text?: string) {
  $('loadingOverlay').classList.toggle('active', show);
  if (text) {
    $('loadingText').textContent = text;
  }
  $('loadingProgress').textContent = '';
}
