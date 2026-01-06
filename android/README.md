# EveryStreet Survey - Android App

An Android navigation app for car-based street survey/streetview data collection. The app calculates the optimal route to cover all streets in a selected area with minimal distance traveled, using the Chinese Postman Problem algorithm.

## Features

- **Optimal Route Calculation**: Uses the Edmonds-Johnson algorithm (Chinese Postman Problem) to find the shortest route that covers every street at least once
- **Turn-by-Turn Navigation**: Voice-guided navigation with visual turn instructions
- **OpenStreetMap Integration**: Uses OSM data for street networks and map display
- **Progress Tracking**: Tracks which streets have been surveyed and saves progress
- **Offline Support**: Downloaded routes can be used offline (map tiles require caching)
- **Survey Area Selection**: Interactive map-based selection of survey area

## Architecture

```
app/
├── algorithm/              # Route optimization algorithms
│   ├── ChinesePostman.kt   # Core CPP solver (Hierholzer, Dijkstra, matching)
│   └── NavigationInstructionGenerator.kt
├── data/                   # Data layer
│   ├── Models.kt           # Data classes (LatLng, GraphNode, GraphEdge, etc.)
│   └── Database.kt         # Room database for routes and progress
├── service/                # Background services
│   ├── LocationService.kt  # GPS tracking
│   ├── NavigationService.kt # Foreground navigation service
│   ├── OverpassApiService.kt # OSM data fetching
│   └── RouteCalculationService.kt
├── ui/                     # Compose UI
│   ├── screens/            # App screens (Home, Navigation, Settings, etc.)
│   ├── navigation/         # Navigation graph
│   └── theme/              # Material 3 theming
└── di/                     # Hilt dependency injection
```

## Algorithm

The app solves the Chinese Postman Problem (Route Inspection Problem):

1. **Build Graph**: Fetch street network from OpenStreetMap Overpass API
2. **Find Odd Nodes**: Identify all nodes with odd degree (odd number of connections)
3. **Shortest Paths**: Calculate shortest paths between all pairs of odd nodes (Dijkstra)
4. **Minimum Matching**: Find minimum weight perfect matching of odd nodes
5. **Augment Graph**: Add duplicate edges for matched pairs
6. **Eulerian Circuit**: Find Eulerian circuit using Hierholzer's algorithm

## Building

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Build Steps

```bash
cd android
./gradlew assembleDebug
```

The APK will be in `app/build/outputs/apk/debug/`

## Dependencies

- **Jetpack Compose**: Modern UI toolkit
- **Hilt**: Dependency injection
- **Room**: Local database
- **OSMDroid**: OpenStreetMap Android SDK
- **Retrofit/OkHttp**: Network requests
- **Kotlin Coroutines**: Async programming
- **Google Play Services Location**: GPS

## Permissions

- `ACCESS_FINE_LOCATION` - GPS navigation
- `ACCESS_COARSE_LOCATION` - Location services
- `INTERNET` - Map tiles and OSM data
- `FOREGROUND_SERVICE` - Background navigation
- `WAKE_LOCK` - Keep screen on during navigation

## Usage

1. **Select Area**: Tap on the map to set center point, adjust radius
2. **Calculate Route**: App fetches streets and calculates optimal path
3. **Start Navigation**: Follow turn-by-turn directions to survey all streets
4. **Track Progress**: View completed streets and resume paused surveys

## License

Same as parent project.
