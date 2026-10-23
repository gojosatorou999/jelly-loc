"""
Jelly Loc — FastAPI backend and PWA server.
Supports iOS & Android mock location emulation, GPX export, ADB generator,
Nominatim search proxy, virtual joystick step, route simulation, and favorites sync.
Deployable on Vercel as a Serverless Python Function or runnable locally with Uvicorn.
"""

import math
import mimetypes
import random
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional

import httpx
from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, PlainTextResponse
from pydantic import BaseModel, Field

# Base directories
BASE_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = BASE_DIR / "static"

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
    "accuracy": 7.0,   # meters
    "altitude": 505.0, # meters
    "speed": 0.0,      # m/s
    "bearing": 0.0,    # degrees
    "jitter_enabled": True,
    "jitter_meters": 4.5,
    "sim_mode": "static",  # static | walking | cycling | driving
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


# ---------------------------------------------------------------------------
# Math helpers
# ---------------------------------------------------------------------------

def calculate_destination(lat: float, lon: float, bearing_deg: float, distance_m: float) -> tuple[float, float]:
    """Calculate new coordinates given a start point, bearing (degrees), and distance (meters)."""
    R = 6378137.0  # Earth radius in metres
    lat_rad = math.radians(lat)
    lon_rad = math.radians(lon)
    bearing_rad = math.radians(bearing_deg)
    d_div_r = distance_m / R

    new_lat_rad = math.asin(
        math.sin(lat_rad) * math.cos(d_div_r)
        + math.cos(lat_rad) * math.sin(d_div_r) * math.cos(bearing_rad)
    )
    new_lon_rad = lon_rad + math.atan2(
        math.sin(bearing_rad) * math.sin(d_div_r) * math.cos(lat_rad),
        math.cos(d_div_r) - math.sin(lat_rad) * math.sin(new_lat_rad),
    )
    return math.degrees(new_lat_rad), math.degrees(new_lon_rad)


def get_current_effective_location() -> Dict[str, Any]:
    """Return current location, applying realistic Gaussian micro-jitter when active."""
    lat = state["latitude"]
    lon = state["longitude"]
    acc = state["accuracy"]

    jitter_lat_offset = 0.0
    jitter_lon_offset = 0.0

    if state["is_mocking"] and state["jitter_enabled"]:
        dist = random.gauss(0, state["jitter_meters"] / 2.0)
        angle = random.uniform(0, 360)
        j_lat, j_lon = calculate_destination(lat, lon, angle, dist)
        jitter_lat_offset = j_lat - lat
        jitter_lon_offset = j_lon - lon
        # Accuracy fluctuates realistically between 3–14 m
        acc = round(max(3.0, min(14.0, 7.0 + random.uniform(-2.5, 2.5))), 1)

    return {
        "is_mocking": state["is_mocking"],
        "name": state["name"],
        "address": state["address"],
        "latitude": round(lat + jitter_lat_offset, 7),
        "longitude": round(lon + jitter_lon_offset, 7),
        "base_latitude": lat,
        "base_longitude": lon,
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


# ---------------------------------------------------------------------------
# Pydantic request schemas
# ---------------------------------------------------------------------------

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


# Regex for raw coordinate input: "17.3616, 78.4747"
COORD_REGEX = re.compile(r"^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$")


# ---------------------------------------------------------------------------
# API endpoints
# ---------------------------------------------------------------------------

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
    """Retrieve current mock location with active status and realistic GPS telemetry."""
    return get_current_effective_location()


@app.post("/api/mock/set")
async def set_mock(payload: LocationPayload):
    """Set the active mocked location."""
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

    # Add to recents (max 8, deduplicated by proximity)
    new_recent = {
        "name": state["name"],
        "address": state["address"],
        "latitude": state["latitude"],
        "longitude": state["longitude"],
    }
    filtered = [
        r for r in state["recents"]
        if not (
            abs(r["latitude"] - payload.latitude) < 0.0001
            and abs(r["longitude"] - payload.longitude) < 0.0001
        )
    ]
    state["recents"] = [new_recent] + filtered[:7]

    return {"status": "mocking_active", "location": get_current_effective_location()}


@app.post("/api/mock/stop")
async def stop_mock():
    """Stop mocking and restore idle state."""
    state["is_mocking"] = False
    state["speed"] = 0.0
    state["sim_mode"] = "static"
    state["updated_at"] = datetime.now(timezone.utc).isoformat()
    return {"status": "mocking_stopped", "location": get_current_effective_location()}


@app.post("/api/mock/step")
async def step_mock(payload: StepPayload):
    """Move the mocked position in a direction (joystick / D-Pad / keyboard)."""
    new_lat, new_lon = calculate_destination(
        state["latitude"],
        state["longitude"],
        payload.bearing,
        payload.distance_meters,
    )
    state["latitude"] = round(new_lat, 7)
    state["longitude"] = round(new_lon, 7)
    state["bearing"] = round(payload.bearing, 1)
    state["updated_at"] = datetime.now(timezone.utc).isoformat()
    return {"status": "stepped", "location": get_current_effective_location()}


@app.post("/api/mock/jitter")
async def update_jitter(payload: JitterPayload):
    """Toggle GPS jitter and configure its radius."""
    state["jitter_enabled"] = payload.enabled
    if payload.meters:
        state["jitter_meters"] = payload.meters
    return {
        "status": "jitter_updated",
        "jitter_enabled": state["jitter_enabled"],
        "meters": state["jitter_meters"],
    }


@app.get("/api/search")
async def search_places(q: str = Query(..., min_length=1)):
    """Search OpenStreetMap Nominatim or parse a raw coordinate string."""
    q_stripped = q.strip()

    # Direct coordinate input: "17.3616, 78.4747"
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

    # Nominatim OSM geocode
    url = "https://nominatim.openstreetmap.org/search"
    headers = {
        "User-Agent": "JellyLoc/2.0 (FastAPI PWA; github.com/gojosatorou999/jelly-loc)",
        "Accept-Language": "en",
    }
    params = {"q": q_stripped, "format": "jsonv2", "addressdetails": 1, "limit": 6}

    try:
        async with httpx.AsyncClient(timeout=6.0) as client:
            resp = await client.get(url, params=params, headers=headers)
            if resp.status_code == 200:
                output = []
                for item in resp.json():
                    raw_name = item.get("display_name", "")
                    parts = [p.strip() for p in raw_name.split(",") if p.strip()]
                    output.append({
                        "name": parts[0] if parts else "Location",
                        "address": ", ".join(parts[1:]) if len(parts) > 1 else raw_name,
                        "latitude": float(item["lat"]),
                        "longitude": float(item["lon"]),
                        "type": item.get("type", "place"),
                    })
                return output
    except Exception:
        pass
    return []


@app.get("/api/favorites")
async def get_favorites():
    return state["favorites"]


@app.post("/api/favorites")
async def add_favorite(fav: FavoritePayload):
    for existing in state["favorites"]:
        if (
            abs(existing["latitude"] - fav.latitude) < 0.0001
            and abs(existing["longitude"] - fav.longitude) < 0.0001
        ):
            return {"status": "exists", "favorites": state["favorites"]}
    state["favorites"].append({
        "name": fav.name,
        "address": fav.address,
        "latitude": fav.latitude,
        "longitude": fav.longitude,
    })
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


# ---------------------------------------------------------------------------
# iOS & Android export tools
# ---------------------------------------------------------------------------

@app.get("/api/export/gpx")
async def export_gpx(name: Optional[str] = None):
    """Generate a standard GPX file for Xcode / 3uTools / LocationSimulator."""
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
    """Generate an ADB shell script for Android mock GPS."""
    loc = get_current_effective_location()
    lat = loc["latitude"]
    lon = loc["longitude"]

    script = f"""#!/bin/bash
# Jelly Loc — Android ADB Mock Location Script
# Connect your Android phone via USB/Wi-Fi with USB debugging enabled, then run this script.

echo ">> Teleporting Android location to {loc['name']} ({lat}, {lon})..."

# For Android Emulator:
adb emu geo fix {lon} {lat}

# For physical Android device via LocationManager:
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
    """Formatted JSON for Apple Shortcuts on iPhone / iPad."""
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
    """JavaScript bookmarklet: overrides navigator.geolocation in any browser tab."""
    host = request.headers.get("host", "localhost:8000")
    scheme = request.headers.get("x-forwarded-proto", "http")
    api_url = f"{scheme}://{host}/api/location"

    hook_code = f"""// Jelly Loc HTML5 Geolocation Injector
(function() {{
  const API_URL = "{api_url}";
  console.log("[JellyLoc] Geolocation Override active via " + API_URL);

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
      console.warn("[JellyLoc] Failed to fetch mock location, using real GPS", e);
      return null;
    }}
  }}

  if (navigator.geolocation) {{
    const _origGet = navigator.geolocation.getCurrentPosition.bind(navigator.geolocation);
    navigator.geolocation.getCurrentPosition = async function(success, error, options) {{
      const mock = await fetchMockPos();
      if (mock) {{ success(mock); }} else {{ _origGet(success, error, options); }}
    }};
    navigator.geolocation.watchPosition = function(success) {{
      return setInterval(async () => {{
        const mock = await fetchMockPos();
        if (mock) success(mock);
      }}, 1000);
    }};
    console.log("[JellyLoc] navigator.geolocation successfully hooked!");
  }}
}})();"""
    return Response(content=hook_code, media_type="application/javascript")


# ---------------------------------------------------------------------------
# Static file serving — explicit routes required for Vercel serverless
# ---------------------------------------------------------------------------

@app.api_route("/sw.js", methods=["GET", "HEAD"])
async def service_worker():
    """Serve the PWA Service Worker with correct headers."""
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
    """Serve the PWA Web App Manifest."""
    manifest_file = STATIC_DIR / "manifest.json"
    if manifest_file.exists():
        return FileResponse(
            manifest_file,
            media_type="application/manifest+json",
            headers={"Cache-Control": "public, max-age=3600"},
        )
    raise HTTPException(status_code=404, detail="manifest.json not found")


@app.get("/static/{file_path:path}")
async def serve_static(file_path: str):
    """Serve static assets — styles, JS, icons, etc."""
    full_path = STATIC_DIR / file_path
    if not full_path.exists() or not full_path.is_file():
        raise HTTPException(status_code=404, detail=f"Static file not found: {file_path}")
    # Prevent path-traversal attacks
    try:
        full_path.resolve().relative_to(STATIC_DIR.resolve())
    except ValueError:
        raise HTTPException(status_code=403, detail="Forbidden")
    mime, _ = mimetypes.guess_type(str(full_path))
    return FileResponse(full_path, media_type=mime or "application/octet-stream")


@app.api_route("/", methods=["GET", "HEAD"])
async def root():
    """Serve the Jelly Loc PWA frontend."""
    index_file = STATIC_DIR / "index.html"
    if index_file.exists():
        return FileResponse(index_file, media_type="text/html")
    return HTMLResponse("<h1>Jelly Loc</h1><p>Frontend static files not found.</p>")
