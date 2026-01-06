# EveryStreet Survey - Web App

A web-based street survey navigation app that calculates optimal routes to cover all streets in an area with minimal distance.

## Features

- **Optimal Route Calculation** - Chinese Postman Problem algorithm for efficient street coverage
- **Voice Guidance** - Web Speech API for turn-by-turn voice instructions
- **Real-time GPS Tracking** - Geolocation API for live position tracking
- **OpenStreetMap** - Free map tiles and street data via Overpass API
- **No Installation Required** - Works directly in mobile browser

## Tech Stack

- **Vite** - Fast build tool
- **TypeScript** - Type safety
- **Leaflet** - Interactive maps
- **Web Speech API** - Voice synthesis
- **Geolocation API** - GPS tracking

## Local Development

```bash
# Install dependencies
npm install

# Start dev server
npm run dev

# Build for production
npm run build
```

## Deploy to Cloudflare Pages

### Option 1: Via GitHub (Recommended)

1. Push code to GitHub
2. Go to [Cloudflare Pages](https://pages.cloudflare.com)
3. Create new project > Connect to Git
4. Select your repository
5. Configure build:
   - **Build command:** `npm run build`
   - **Build output directory:** `dist`
   - **Root directory:** `web`
6. Deploy!

Your app will be available at: `https://your-project.pages.dev`

### Option 2: Direct Upload

```bash
# Build
npm run build

# Deploy with Wrangler
npx wrangler pages deploy dist
```

## Usage

1. **Open the app** on your phone's browser
2. **Allow location access** when prompted
3. **Tap "New Survey"** to start
4. **Tap on map** to select survey center
5. **Adjust radius** with the slider
6. **Tap "Calculate Route"** - wait for processing
7. **Tap "Start Navigation"** - follow voice directions
8. **Drive all streets** - the app tracks your progress

## Browser Permissions Needed

- **Location** - For GPS tracking during navigation
- **Speech** - For voice guidance (auto-granted)

## How It Works

1. **Fetches street data** from OpenStreetMap Overpass API
2. **Builds a graph** where intersections are nodes and streets are edges
3. **Finds odd-degree nodes** (intersections with odd number of streets)
4. **Calculates shortest paths** between odd nodes (Dijkstra)
5. **Finds minimum matching** to pair odd nodes optimally
6. **Creates Eulerian circuit** (Hierholzer's algorithm)
7. **Generates turn-by-turn instructions** from the route

This solves the [Chinese Postman Problem](https://en.wikipedia.org/wiki/Chinese_postman_problem) - finding the shortest route that traverses every edge at least once.

## Limitations

- Large areas may be slow to calculate (browser limitation)
- Requires internet for map tiles and initial route calculation
- GPS accuracy depends on device
- Voice guidance requires browser support (most modern browsers work)

## License

Same as parent project.
