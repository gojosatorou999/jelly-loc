# Jelly Loc — GPS Simulator & Mock Location PWA

[![FastAPI](https://img.shields.io/badge/FastAPI-0.110+-009688.svg?style=flat&logo=fastapi)](https://fastapi.tiangolo.com)
[![Python](https://img.shields.io/badge/Python-3.10+-blue.svg?logo=python)](https://python.org)
[![PWA Ready](https://img.shields.io/badge/PWA-iOS%20%26%20Android-success.svg?logo=pwa)](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps)
[![Vercel](https://img.shields.io/badge/Deploy-Vercel-black.svg?logo=vercel)](https://vercel.com)

A modern, high-performance **GPS Simulator and Mock Location application** built with **Python & FastAPI**, optimized to run natively as a **Progressive Web App (PWA)** on both **iOS** and **Android**, with one-click deployment on **Vercel**.

---

## ✨ Features

- **Interactive Dark Map**: Leaflet.js with CartoDB Dark Matter tiles, 60fps smooth zooming, draggable neon marker, and live accuracy radius circle.
- **Instant Search & Coordinate Parser**:
  - OpenStreetMap (Nominatim) search with 300ms debounce and autocomplete suggestions.
  - Direct coordinate input: paste raw coordinates like `17.3616, 78.4747` or `48.8584, 2.2945` to teleport instantly.
- **Virtual Joystick / D-Pad**:
  - Touch-friendly 360° on-screen joystick for mobile screens.
  - Desktop keyboard controls (`W`, `A`, `S`, `D` or Arrow keys).
  - Variable speed profiles: **Walk (5 km/h)**, **Bike (20 km/h)**, **Drive (60 km/h)**.
- **Realistic GPS Jitter**:
  - Optional Gaussian micro-drift (±4.5m natural satellite noise) so location-tracking apps (e.g., WhatsApp, Maps) don't detect a frozen mock.
- **Mobile Hardware Integration**:
  - **Screen Wake Lock API**: Prevents the mobile screen from turning off while location mocking is running.
  - **Haptic Feedback**: Tactile vibration feedback on button presses and joystick steps.
- **Favorites & Recent Places**:
  - Quick-access horizontal chip strip with 1-tap teleportation.
- **Export & Simulation Tools**:
  - **iOS Xcode `.gpx` generator**: 1-click download for Xcode Location Simulation, 3uTools, or LocationSimulator.
  - **Android ADB Shell Script**: Ready-to-run terminal commands for Android mock GPS.
  - **Apple Shortcuts Webhook**: Clean JSON output for iOS Shortcuts automations.
  - **Web Geolocation Hook**: Injectable JavaScript snippet / bookmarklet for mobile browsers.
- **Clean Stop**:
  - 1-tap Stop immediately terminates mock mode and restores real GPS.

---

## 📱 Running on iOS

### 1. Install as PWA (Recommended)
1. Open your deployed URL (e.g. `https://your-jelly-loc.vercel.app`) in **Safari** on iPhone or iPad.
2. Tap the **Share** button (box with upward arrow) at the bottom.
3. Scroll down and tap **Add to Home Screen**.
4. Launch **Jelly Loc** from your home screen — it runs in fullscreen standalone mode with offline caching.

### 2. System-Wide Location Simulation (Xcode / 3uTools)
1. Pick any location on the map.
2. Tap the **📖 Guide** icon → Select **🍎 iOS Setup** → Tap **Download Current GPX**.
3. In **Xcode**:
   - Go to `Product → Scheme → Edit Scheme → Run → Options`.
   - Under **Default Location**, choose **Add GPX File...** and select the downloaded file.
   - Run your project — your connected iPhone will report the mocked coordinates across all apps!
4. Or drag and drop the `.gpx` file into **3uTools**, **LocationSimulator**, or **AnyGo**.

### 3. Apple Shortcuts Integration
- Set up an iOS Shortcut that fetches `https://your-domain.vercel.app/api/ios/shortcut` to automate navigation or check mock status.

---

## 🤖 Running on Android

### 1. Install as PWA
1. Open the URL in **Chrome** or **Brave** on Android.
2. Tap the install prompt banner at the top or Chrome menu (`⋮`) → **Install app** / **Add to Home screen**.

### 2. Testing with WhatsApp & Google Maps
1. **Developer Options Setup**:
   - Go to `Settings → About Phone → Software Information`.
   - Tap **Build Number 7 times** to unlock Developer Options.
2. **Via ADB**:
   - Connect your Android device via USB/Wi-Fi debugging.
   - Run the auto-generated script from `http://your-domain/api/export/adb` or:
     ```bash
     adb emu geo fix <longitude> <latitude>
     ```

---

## 🚀 Deploying to Vercel

This repository is pre-configured for Vercel's Python Serverless Runtime via `vercel.json`.

### Option A: Deploy via Vercel Web Dashboard
1. Push this repository to **GitHub**.
2. Go to [vercel.com](https://vercel.com) and click **Add New → Project**.
3. Import your GitHub repository.
4. Leave **Framework Preset** as **Other**.
5. Leave the Root Directory as `./`.
6. Click **Deploy**. Vercel will build and deploy the Python FastAPI backend and PWA static assets automatically!

### Option B: Deploy via Vercel CLI
```bash
# Install Vercel CLI if not already installed
npm install -g vercel

# Deploy directly from workspace root
vercel
```

---

## 💻 Local Development

### 1. Clone & Set Up Virtual Environment
```bash
git clone https://github.com/gojosatorou999/jelly-loc.git
cd jelly-loc

# Create virtual environment
python3 -m venv .venv
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt
```

### 2. Start the Server
```bash
python main.py
```
Open **[http://localhost:8000](http://localhost:8000)** in your browser or mobile device on the same local Wi-Fi.

### 3. Run Automated Validation Tests
```bash
python test_app.py
```

---

## 📂 Project Structure

```
.
├── api/
│   ├── __init__.py
│   └── index.py            # FastAPI application & serverless endpoints
├── static/
│   ├── index.html          # PWA frontend UI & mobile layout
│   ├── styles.css          # Dark glassmorphism styling & animations
│   ├── app.js              # Leaflet map, joystick, route simulation
│   ├── sw.js               # Service Worker with offline caching
│   ├── manifest.json       # Web App Manifest for iOS & Android
│   └── icons/              # App icons (SVG, 192px, 512px, Apple Touch)
├── main.py                 # Local runner (uvicorn)
├── test_app.py             # E2E test suite (15 automated tests)
├── requirements.txt        # Python dependencies
├── vercel.json             # Vercel Serverless configuration
└── README.md               # Documentation
```

---

## 📡 API Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | `GET` | Serves the Jelly Loc PWA application |
| `/api/health` | `GET` | Server health and status check |
| `/api/location` | `GET` | Current active mock coordinates with jitter & telemetry |
| `/api/mock/set` | `POST` | Set active mock location (`latitude`, `longitude`, `name`, `jitter`) |
| `/api/mock/stop` | `POST` | Stop mock location and restore real GPS |
| `/api/mock/step` | `POST` | Move position via joystick (`bearing`, `distance_meters`) |
| `/api/mock/jitter` | `POST` | Toggle and configure GPS jitter noise (`meters`) |
| `/api/search` | `GET` | Search places via OpenStreetMap or parse coordinates |
| `/api/favorites` | `GET/POST/DELETE` | Manage favorite saved places |
| `/api/recents` | `GET` | Get recent search locations |
| `/api/export/gpx` | `GET` | Download standard GPX file for iOS Xcode / LocationSimulator |
| `/api/export/adb` | `GET` | Download Android ADB mock location script |
| `/api/ios/shortcut` | `GET` | Formatted JSON for Apple Shortcuts |
| `/api/hook.js` | `GET` | JavaScript bookmarklet for mobile browser GPS injection |
| `/manifest.json` | `GET` | Progressive Web App manifest |
| `/sw.js` | `GET` | Service worker script |

---

## ⚖️ License & Responsible Use

This tool is designed for software development, testing location-based applications, and privacy testing. Please use responsibly according to applicable terms of service and laws.
