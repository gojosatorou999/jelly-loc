"""
Automated validation script for Jelly Loc FastAPI server & PWA assets.
"""

import sys
import httpx

BASE_URL = "http://127.0.0.1:8000"

def run_tests():
    print("🧪 Running Jelly Loc End-to-End Validation Tests...\n")
    passed = 0
    total = 0

    def assert_test(name, condition, details=""):
        nonlocal passed, total
        total += 1
        if condition:
            passed += 1
            print(f"  ✅ [PASS] {name}")
        else:
            print(f"  ❌ [FAIL] {name} - {details}")

    with httpx.Client(base_url=BASE_URL, timeout=10.0) as client:
        # 1. Health check
        r = client.get("/api/health")
        assert_test("GET /api/health returns 200 OK", r.status_code == 200 and r.json().get("status") == "online")

        # 2. Status / location
        r = client.get("/api/status")
        data = r.json()
        assert_test("GET /api/status returns location payload", r.status_code == 200 and "latitude" in data and "longitude" in data)

        # 3. Set Mock location
        payload = {
            "latitude": 48.8584,
            "longitude": 2.2945,
            "name": "Eiffel Tower, Paris",
            "address": "Champ de Mars, Paris, France",
            "jitter": True,
            "speed_kmh": 25.0,
            "altitude": 324.0,
        }
        r = client.post("/api/mock/set", json=payload)
        res_data = r.json()
        assert_test("POST /api/mock/set activates mock mode", r.status_code == 200 and res_data.get("status") == "mocking_active")

        # 4. Joystick Step
        step_payload = {"bearing": 180, "distance_meters": 20.0}
        r = client.post("/api/mock/step", json=step_payload)
        step_data = r.json()
        assert_test("POST /api/mock/step updates position via bearing", r.status_code == 200 and step_data.get("status") == "stepped")

        # 5. Jitter adjustment
        r = client.post("/api/mock/jitter", json={"enabled": True, "meters": 6.0})
        assert_test("POST /api/mock/jitter adjusts noise", r.status_code == 200 and r.json().get("meters") == 6.0)

        # 6. Coordinate Search Parsing
        r = client.get("/api/search", params={"q": "17.3616, 78.4747"})
        search_res = r.json()
        assert_test("GET /api/search parses raw coordinates correctly", r.status_code == 200 and len(search_res) > 0 and search_res[0]["latitude"] == 17.3616)

        # 7. Favorites Sync
        fav_payload = {
            "name": "Taj Mahal",
            "address": "Dharmapuri, Forest Colony, Agra, Uttar Pradesh 282001",
            "latitude": 27.1751,
            "longitude": 78.0421,
        }
        r = client.post("/api/favorites", json=fav_payload)
        assert_test("POST /api/favorites saves favorite", r.status_code == 200 and r.json().get("status") in ["added", "exists"])

        # 8. Export GPX
        r = client.get("/api/export/gpx")
        assert_test("GET /api/export/gpx generates valid GPX XML", r.status_code == 200 and "<gpx" in r.text and "<wpt" in r.text)

        # 9. Export ADB script
        r = client.get("/api/export/adb")
        assert_test("GET /api/export/adb generates Android shell script", r.status_code == 200 and "adb" in r.text)

        # 10. iOS Shortcut API
        r = client.get("/api/ios/shortcut")
        sc_data = r.json()
        assert_test("GET /api/ios/shortcut returns Apple Maps / Shortcut JSON", r.status_code == 200 and "apple_maps_url" in sc_data)

        # 11. Geolocation Hook
        r = client.get("/api/hook.js")
        assert_test("GET /api/hook.js returns JS injection bookmarklet", r.status_code == 200 and "navigator.geolocation" in r.text)

        # 12. PWA Manifest
        r = client.get("/manifest.json")
        assert_test("GET /manifest.json returns valid PWA manifest", r.status_code == 200 and r.json().get("display") == "standalone")

        # 13. Service Worker
        r = client.get("/sw.js")
        assert_test("GET /sw.js returns Service Worker script with headers", r.status_code == 200 and "Service-Worker-Allowed" in r.headers)

        # 14. Root HTML
        r = client.get("/")
        assert_test("GET / serves PWA index.html", r.status_code == 200 and "Jelly Loc" in r.text and "leaflet" in r.text)

        # 15. Stop Mocking
        r = client.post("/api/mock/stop")
        assert_test("POST /api/mock/stop deactivates mock mode", r.status_code == 200 and r.json().get("status") == "mocking_stopped")

    print(f"\nSummary: {passed}/{total} tests passed ({passed/total*100:.1f}%)")
    return passed == total

if __name__ == "__main__":
    success = run_tests()
    sys.exit(0 if success else 1)
