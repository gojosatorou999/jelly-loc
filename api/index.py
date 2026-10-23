"""
Jelly Loc — FastAPI backend and PWA server.
Supports iOS & Android mock location emulation, GPX export, ADB generator,
Nominatim search proxy, virtual joystick step, route simulation, and favorites sync.
Deployable on Vercel as a Serverless Python Function or runnable locally with Uvicorn.
"""

import math
import os
import random
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx
from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

# Base directories
BASE_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = BASE_DIR / "static"
DATA_FILE = BASE_DIR / "data" / "saved_state.json"

app = FastAPI(
    title="Jelly Loc API",
    description="Mock location server and PWA engine for iOS & Android",
    version="2.0.0",
)

# CORS support for cross-origin mobile access & shortcuts
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# In-memory application state
state: Dict[str, Any] = {
    "is_mocking": False,
    "latitude": 17.385044,
    "longitude": 78.486671,
    "name": "Charminar, Hyderabad",
    "address": "Charminar Rd, Char Kaman, Ghansi Bazaar, Hyderabad, Telangana 500002",
    "accuracy": 7.0,  # meters
    "altitude": 505.0,  # meters
    "speed": 0.0,  # m/s
    "bearing": 0.0,  # degrees
    "jitter_enabled": True,
    "jitter_meters": 4.5,
    "sim_mode": "static",  # static, walking, cycling, driving
    "sim_speed_kmh": 5.0,
    "updated_at": datetime.now(timezone.utc).isoformat(),
    "favorites": [
        {
            "name": "Charminar",
            "address": "Hyderabad, Telangana, India",
            "latitude": 17.361563,
            "longitude": 78.474665,
        },
        {
            "name": "Eiffel Tower",
            "address": "Champ de Mars, 5 Av. Anatole France, 75007 Paris, France",
            "latitude": 48.858370,
            "longitude": 2.294481,
        },
        {
            "name": "Times Square",
            "address": "Manhattan, NY 10036, United States",
            "latitude": 40.758896,
            "longitude": -73.985130,
        },
        {
            "name": "Tokyo Tower",
            "address": "4 Chome-2-8 Shibakoen, Minato City, Tokyo 105-0011, Japan",
            "latitude": 35.658581,
            "longitude": 139.745438,
        },
    ],
    "recents": [
        {
            "name": "Charminar",
            "address": "Hyderabad, Telangana, India",
            "latitude": 17.361563,
            "longitude": 78.474665,
        }
    ],
}

# Math helper: Great Circle destination calculation
def calculate_destination(lat: float, lon: float, bearing_deg: float, distance_m: float) -> tuple[float, float]:
    """Calculate new coordinates given start point, bearing in degrees, and distance in meters."""
    R = 6378137.0  # Earth's radius in meters
    lat_rad = math.radians(lat)
    lon_rad = math.radians(lon)
    bearing_rad = math.radians(bearing_deg)

    d_div_r = distance_m / R

    new_lat_rad = math.asin(
        math.sin(lat_rad) * math.cos(d_div_r) +
        math.cos(lat_rad) * math.sin(d_div_r) * math.cos(bearing_rad)
    )
    new_lon_rad = lon_rad + math.atan2(
        math.sin(bearing_rad) * math.sin(d_div_r) * math.cos(lat_rad),
        math.cos(d_div_r) - math.sin(lat_rad) * math.sin(new_lat_rad)
    )

    return math.degrees(new_lat_rad), math.degrees(new_lon_rad)

def get_current_effective_location() -> Dict[str, Any]:
    """Returns current location, applying realistic Gaussian micro-jitter if active."""
    lat = state["latitude"]
    lon = state["longitude"]
    acc = state["accuracy"]

    jitter_lat_offset = 0.0
    jitter_lon_offset = 0.0

    if state["is_mocking"] and state["jitter_enabled"]:
        # Apply Box-Muller Gaussian jitter in meters
        dist = random.gauss(0, state["jitter_meters"] / 2.0)
        angle = random.uniform(0, 360)
        j_lat, j_lon = calculate_destination(lat, lon, angle, dist)
        jitter_lat_offset = j_lat - lat
        jitter_lon_offset = j_lon - lon
        # Accuracy fluctuates realistically around 5-10m
        acc = round(max(3.0, min(14.0, 7.0 + random.uniform(-2.5, 2.5))), 1)

    effective_lat = round(lat + jitter_lat_offset, 7)
    effective_lon = round(lon + jitter_lon_offset, 7)

    return {
        "is_mocking": state["is_mocking"],
        "name": state["name"],
        "address": state["address"],
        "latitude": effective_lat,
        "longitude": effective_lon,
        "base_latitude": state["latitude"],
        "base_longitude": state["longitude"],
        "accuracy": acc,
        "altitude": state["altitude"],
        "speed": state["speed"],
        "bearing": state["bearing"],
        "jitter_enabled": state["jitter_enabled"],
        "jitter_meters": state["jitter_meters"],
        "sim_mode": state["sim_mode"],
        "sim_speed_kmh": state["sim_speed_kmh"],
        "updated_at": state["updated_at"],
        "timestamp_ms": int(time.time() * 1000),
    }

# Pydantic Schemas
class LocationPayload(BaseModel):
    latitude: float
    longitude: float
    name: Optional[str] = "Selected Location"
    address: Optional[str] = ""
    jitter: Optional[bool] = True
    speed_kmh: Optional[float] = 0.0
    altitude: Optional[float] = 500.0

class StepPayload(BaseModel):
    bearing: float = Field(..., ge=0, le=360)
    distance_meters: float = Field(default=10.0, gt=0, le=500)

class JitterPayload(BaseModel):
    enabled: bool
    meters: Optional[float] = 4.5

class FavoritePayload(BaseModel):
    name: str
    address: str = ""
    latitude: float
    longitude: float

# Coordinate Regex helper
COORD_REGEX = re.compile(r"^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$")

# --- API Endpoints ---

@app.get("/api/health")
async def health():
    return {
        "status": "online",
        "app": "Jelly Loc",
        "version": "2.0.0",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }

@app.get("/api/status")
@app.get("/api/location")
async def get_location():
    """Retrieve current mock location details with active status and realistic GPS fields."""
    return get_current_effective_location()

@app.post("/api/mock/set")
async def set_mock(payload: LocationPayload):
    """Set active mocked location coordinates."""
    state["latitude"] = payload.latitude
    state["longitude"] = payload.longitude
    state["name"] = payload.name or f"{payload.latitude:.5f}, {payload.longitude:.5f}"
    state["address"] = payload.address or f"{payload.latitude:.6f}, {payload.longitude:.6f}"
    state["is_mocking"] = True
    if payload.jitter is not None:
        state["jitter_enabled"] = payload.jitter
    if payload.altitude is not None:
        state["altitude"] = payload.altitude
    state["updated_at"] = datetime.now(timezone.utc).isoformat()

    # Add to recent places (up to 8, no duplicates)
    new_recent = {
        "name": state["name"],
        "address": state["address"],
        "latitude": state["latitude"],
        "longitude": state["longitude"],
    }
    filtered_recents = [
        r for r in state["recents"]
        if not (abs(r["latitude"] - payload.latitude) < 0.0001 and abs(r["longitude"] - payload.longitude) < 0.0001)
    ]
    state["recents"] = [new_recent] + filtered_recents[:7]

    return {"status": "mocking_active", "location": get_current_effective_location()}

@app.post("/api/mock/stop")
async def stop_mock():
    """Stop mocking and restore realistic idle state."""
    state["is_mocking"] = False
    state["speed"] = 0.0
    state["sim_mode"] = "static"
    state["updated_at"] = datetime.now(timezone.utc).isoformat()
    return {"status": "mocking_stopped", "location": get_current_effective_location()}

@app.post("/api/mock/step")
async def step_mock(payload: StepPayload):
    """Move the mocked position in a direction (for Joystick / D-Pad controls)."""
    new_lat, new_lon = calculate_destination(
        state["latitude"],
        state["longitude"],
        payload.bearing,
        payload.distance_meters,
    )
    state["latitude"] = round(new_lat, 7)
    state["longitude"] = round(new_lon, 7)
    state["bearing"] = round(payload.bearing, 1)
    # Estimate speed based on step: 10m/s ≈ 36km/h or user configured
    state["updated_at"] = datetime.now(timezone.utc).isoformat()
    return {"status": "stepped", "location": get_current_effective_location()}

@app.post("/api/mock/jitter")
async def update_jitter(payload: JitterPayload):
    state["jitter_enabled"] = payload.enabled
    if payload.meters:
        state["jitter_meters"] = payload.meters
    return {"status": "jitter_updated", "jitter_enabled": state["jitter_enabled"], "meters": state["jitter_meters"]}

@app.get("/api/search")
async def search_places(q: str = Query(..., min_length=1)):
    """Search OpenStreetMap Nominatim or direct parse coordinate string."""
    q_stripped = q.strip()

    # 1. Check if query is raw coordinates: e.g. "17.3616, 78.4747"
    coord_match = COORD_REGEX.match(q_stripped)
    if coord_match:
        lat = float(coord_match.group(1))
        lon = float(coord_match.group(2))
        if -90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0:
            return [
                {
                    "name": "Pinned Coordinates",
                    "address": f"Latitude: {lat:.6f}, Longitude: {lon:.6f}",
                    "latitude": lat,
                    "longitude": lon,
                    "type": "coordinate",
                }
            ]

    # 2. Call Nominatim OSM search
    url = "https://nominatim.openstreetmap.org/search"
    headers = {
        "User-Agent": "JellyLoc/2.0 (FastAPI PWA on Vercel; contact: info@jellyloc.internal)",
        "Accept-Language": "en",
    }
    params = {
        "q": q_stripped,
        "format": "jsonv2",
        "addressdetails": 1,
        "limit": 6,
    }

    try:
        async with httpx.AsyncClient(timeout=6.0) as client:
            resp = await client.get(url, params=params, headers=headers)
            if resp.status_code == 200:
                results = resp.json()
                output = []
                for item in results:
                    raw_name = item.get("display_name", "")
                    parts = [p.strip() for p in raw_name.split(",") if p.strip()]
                    name = parts[0] if parts else "Location"
                    address = ", ".join(parts[1:]) if len(parts) > 1 else raw_name

                    output.append({
                        "name": name,
                        "address": address,
                        "latitude": float(item["lat"]),
                        "longitude": float(item["lon"]),
                        "type": item.get("type", "place"),
                    })
                return output
            else:
                return []
    except Exception as e:
        # Graceful fallback on network timeout
        return []

@app.get("/api/favorites")
async def get_favorites():
    return state["favorites"]

@app.post("/api/favorites")
async def add_favorite(fav: FavoritePayload):
    # Check if exists
    for existing in state["favorites"]:
        if abs(existing["latitude"] - fav.latitude) < 0.0001 and abs(existing["longitude"] - fav.longitude) < 0.0001:
            return {"status": "exists", "favorites": state["favorites"]}

    new_fav = {
        "name": fav.name,
        "address": fav.address,
        "latitude": fav.latitude,
        "longitude": fav.longitude,
    }
    state["favorites"].append(new_fav)
    return {"status": "added", "favorites": state["favorites"]}

@app.delete("/api/favorites")
async def remove_favorite(lat: float, lon: float):
    state["favorites"] = [
        f for f in state["favorites"]
        if not (abs(f["latitude"] - lat) < 0.0001 and abs(f["longitude"] - lon) < 0.0001)
    ]
    return {"status": "removed", "favorites": state["favorites"]}

@app.get("/api/recents")
async def get_recents():
    return state["recents"]

# --- iOS & Android Export Tools ---

@app.get("/api/export/gpx")
async def export_gpx(name: Optional[str] = None):
    """Generate an RFC-standard GPX file for Xcode (iOS Location Simulation), 3uTools, or LocationSimulator."""
    loc = get_current_effective_location()
    place_name = name or loc["name"]
    lat = loc["latitude"]
    lon = loc["longitude"]
    ele = loc["altitude"]
    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    gpx_content = f"""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="JellyLoc - https://github.com/gojosatorou999/jelly-loc"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <metadata>
    <name>{place_name}</name>
    <desc>Generated by Jelly Loc for iOS Xcode &amp; Location Simulator</desc>
    <time>{now_iso}</time>
  </metadata>
  <wpt lat="{lat}" lon="{lon}">
    <ele>{ele}</ele>
    <time>{now_iso}</time>
    <name>{place_name}</name>
    <desc>{loc["address"]}</desc>
  </wpt>
</gpx>"""

    return Response(
        content=gpx_content,
        media_type="application/gpx+xml",
        headers={"Content-Disposition": f'attachment; filename="jelly_loc_{lat:.4f}_{lon:.4f}.gpx"'},
    )

@app.get("/api/export/adb")
async def export_adb():
    """Generate ADB script commands for Android Mock Location & GPS Emulation."""
    loc = get_current_effective_location()
    lat = loc["latitude"]
    lon = loc["longitude"]

    script = f"""#!/bin/bash
# Jelly Loc — Android ADB Mock Location Script
# Run this in your terminal while your Android phone is connected via USB/Wi-Fi with USB debugging enabled.

echo ">> Teleporting Android location to {loc['name']} ({lat}, {lon})..."

# 1. For Android Emulator:
adb emu geo fix {lon} {lat}

# 2. For Physical Android Device via LocationManager:
adb shell cmd location set-test-provider-location gps --location {lat},{lon}
adb shell cmd location set-test-provider-location network --location {lat},{lon}

echo ">> Done! Location updated."
"""
    return PlainTextResponse(
        content=script,
        headers={"Content-Disposition": 'attachment; filename="jelly_loc_adb.sh"'},
    )

@app.get("/api/ios/shortcut")
async def ios_shortcut():
    """Formatted JSON for direct consumption by Apple Shortcuts on iPhone / iPad."""
    loc = get_current_effective_location()
    return {
        "status": "success",
        "name": loc["name"],
        "latitude": loc["latitude"],
        "longitude": loc["longitude"],
        "accuracy": loc["accuracy"],
        "altitude": loc["altitude"],
        "is_mocking": loc["is_mocking"],
        "apple_maps_url": f"https://maps.apple.com/?ll={loc['latitude']},{loc['longitude']}&q={loc['name']}",
        "google_maps_url": f"https://www.google.com/maps?q={loc['latitude']},{loc['longitude']}",
    }

@app.get("/api/hook.js")
async def geolocation_hook(request: Request):
    """JavaScript injection hook: Overrides navigator.geolocation in any mobile browser to read from Jelly Loc."""
    host = request.headers.get("host", "localhost:8000")
    scheme = request.headers.get("x-forwarded-proto", "http")
    api_url = f"{scheme}://{host}/api/location"

    hook_code = f"""// Jelly Loc HTML5 Geolocation Injector for iOS & Android Browsers
(function() {{
  const API_URL = "{api_url}";
  console.log("[JellyLoc] Initializing Geolocation Override via " + API_URL);

  async function fetchMockPos() {{
    try {{
      const res = await fetch(API_URL);
      const data = await res.json();
      return {{
        coords: {{
          latitude: data.latitude,
          longitude: data.longitude,
          accuracy: data.accuracy || 8,
          altitude: data.altitude || 0,
          altitudeAccuracy: 1,
          heading: data.bearing || 0,
          speed: data.speed || 0,
        }},
        timestamp: Date.now()
      }};
    }} catch (e) {{
      console.warn("[JellyLoc] Failed to fetch mock location, falling back", e);
      return null;
    }}
  }}

  if (navigator.geolocation) {{
    const originalGetCurrentPosition = navigator.geolocation.getCurrentPosition.bind(navigator.geolocation);
    const originalWatchPosition = navigator.geolocation.watchPosition.bind(navigator.geolocation);

    navigator.geolocation.getCurrentPosition = async function(success, error, options) {{
      const mock = await fetchMockPos();
      if (mock) {{
        success(mock);
      }} else {{
        originalGetCurrentPosition(success, error, options);
      }}
    }};

    navigator.geolocation.watchPosition = function(success, error, options) {{
      const id = setInterval(async () => {{
        const mock = await fetchMockPos();
        if (mock) success(mock);
      }}, 1000);
      return id;
    }};
    console.log("[JellyLoc] Navigator Geolocation successfully hooked!");
  }}
}})();"""
    return Response(content=hook_code, media_type="application/javascript")

# --- Static File Serving & PWA Routes ---

@app.api_route("/sw.js", methods=["GET", "HEAD"])
async def service_worker():
    sw_file = STATIC_DIR / "sw.js"
    if sw_file.exists():
        return FileResponse(
            sw_file,
            media_type="application/javascript",
            headers={
                "Service-Worker-Allowed": "/",
                "Cache-Control": "no-cache, no-store, must-revalidate",
            },
        )
    raise HTTPException(status_code=404, detail="sw.js not found")

@app.api_route("/manifest.json", methods=["GET", "HEAD"])
@app.api_route("/manifest.webmanifest", methods=["GET", "HEAD"])
async def web_manifest():
    manifest_file = STATIC_DIR / "manifest.json"
    if manifest_file.exists():
        return FileResponse(
            manifest_file,
            media_type="application/manifest+json",
            headers={"Cache-Control": "public, max-age=3600"},
        )
    raise HTTPException(status_code=404, detail="manifest not found")

@app.api_route("/", methods=["GET", "HEAD"])
async def root():
    index_file = STATIC_DIR / "index.html"
    if index_file.exists():
        return FileResponse(index_file, media_type="text/html")
    return HTMLResponse("<h1>Jelly Loc</h1><p>Frontend static files not found.</p>")

# Mount the static directory so /static/styles.css, /static/app.js, /static/icons/... work directly
if STATIC_DIR.exists():
    app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

