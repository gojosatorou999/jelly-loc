# Jelly Loc

Set a fake GPS location on your phone. Jelly Loc has three parts:

| Part | What it does | Guide |
|---|---|---|
| **Android app** (`android/`) | Fakes the location for the **whole phone**, so WhatsApp live location, Google Maps etc. see it. Runs on the phone alone, no computer needed. | **[docs/ANDROID.md](docs/ANDROID.md)** |
| **iPhone via Xcode** (`ios/`) | Small host app plus a GPX file. While it runs from Xcode on a Mac, the iPhone reports the fake location to every app. | **[docs/IOS-XCODE.md](docs/IOS-XCODE.md)** |
| **Web app** (`api/`, `static/`) | Map, search and favorites; exports GPX (Xcode) and ADB scripts; a bookmarklet that fakes location inside a browser tab. Deploys to Vercel. | below |

> **Why not just the website?** Browsers sandbox web pages, so a site or PWA can't change the location other apps see. On Android use the app; on iPhone use Xcode.

**Android download (latest):** https://github.com/gojosatorou999/jelly-loc/releases/latest/download/jelly-loc.apk

---

## Web app

FastAPI backend plus a Leaflet PWA (Esri dark basemap, OpenStreetMap/Nominatim search).

- Search places or paste `lat, lng`; tap or drag the pin; favorites and recents (saved in your browser).
- Joystick / WASD movement with Walk, Bike and Drive speeds, plus optional GPS jitter.
- Exports: **GPX** for Xcode/3uTools, an **ADB script** for Android, **Apple Shortcuts** JSON, and a **browser bookmarklet** that overrides `navigator.geolocation` in the tab you run it on.

State lives in the browser (`localStorage`). Export endpoints take `?lat=&lon=&name=` so they work on Vercel's stateless serverless functions.

### Run locally
```bash
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python main.py                                       # http://localhost:8000
```
Tests, with the server running in another terminal:
```bash
python test_app.py                                   # or: JELLY_URL=http://127.0.0.1:8765 python test_app.py
```

### Deploy to Vercel
Import the repo at [vercel.com](https://vercel.com) with Framework Preset **Other** and Root Directory `./`, or run `vercel` with the CLI. `vercel.json` routes everything to `api/index.py` and bundles `static/`.

### API
| Endpoint | Method | Description |
|---|---|---|
| `/api/health` | GET | Health check |
| `/api/location`, `/api/status` | GET | Current server-side mock location (best-effort on serverless) |
| `/api/mock/set` | POST | Set mock location (`latitude`, `longitude`, `name`, …) |
| `/api/mock/stop` | POST | Stop mocking |
| `/api/mock/step` | POST | Move by `bearing` + `distance_meters` (optional start `latitude`/`longitude`) |
| `/api/mock/jitter` | POST | Configure jitter |
| `/api/search?q=` | GET | Nominatim search or coordinate parse |
| `/api/favorites` | GET/POST/DELETE | Server-side favorites |
| `/api/recents` | GET | Server-side recents |
| `/api/export/gpx?lat=&lon=&name=` | GET | GPX file |
| `/api/export/adb?lat=&lon=&name=` | GET | ADB shell script |
| `/api/ios/shortcut?lat=&lon=&name=` | GET | JSON for Apple Shortcuts |
| `/api/hook.js?lat=&lon=` | GET | Geolocation override script (bookmarklet) |

---

## Project structure
```
android/            Android app (Kotlin, Compose) — builds to the APK
ios/                Xcode host app (XcodeGen spec, Swift, GPX, set-location script)
api/index.py        FastAPI backend (Vercel serverless entry)
static/             PWA frontend (HTML, CSS, JS, service worker, icons)
docs/               ANDROID.md, IOS-XCODE.md
.github/workflows/  android-apk.yml (APK release), ios-build.yml (iOS compile check)
main.py             Local dev server
test_app.py         End-to-end API tests
```

## Responsible use
For development, testing location-based apps, and personal privacy. Don't use it to deceive people who rely on your location for safety, and follow the laws and terms of service that apply to you.
