MockLoc — Android Mock Location App
A small Android app that lets me set my phone's location to anywhere by searching for it (like Google Maps), and have other apps — especially WhatsApp (current + live location) — see that location properly.
Agent: read this whole file before writing code. If anything is ambiguous, ask me instead of guessing.
1. Goals (must have)
	1.	Search to set location. A search bar at the top with autocomplete, exactly like Google Maps. I type "Charminar" or "Eiffel Tower", pick a suggestion, the map moves, and I tap Set Location.
	2.	Works in WhatsApp. Sharing Current location and Live location in WhatsApp must show the mocked location, and live location must keep updating smoothly for the whole duration (15 min / 1 hr / 8 hr).
	3.	Does not interfere with signals. No touching cellular, Wi-Fi, Bluetooth or airplane mode. No root. No disabling real providers. The app only feeds location data through Android's official mock-location API. Calls, data, and hotspot must behave normally while it runs.
	4.	Clean stop. Tapping Stop immediately restores the real location (remove test providers, turn off fused mock mode). Nothing should linger after stopping or force-closing the app.
	5.	Easy to use. One main screen, big Set/Stop button, clear status ("Mocking: Charminar, Hyderabad").
2. Nice to have (v2, don't build first)
	•	Tap or long-press on the map to drop a pin
	•	Paste raw coordinates (17.3616, 78.4747) into the search bar
	•	Recent searches + favorites (Room DB)
	•	Optional tiny position jitter (a few metres, default OFF)
	•	Simple movement simulation (walk/drive along a route at a set speed)
	•	Home-screen widget or quick-settings tile for Start/Stop
3. Non-goals
	•	iOS support (not feasible without jailbreak/Xcode tooling — Android only)
	•	Rooting, Xposed/Magisk modules, or hooking other apps
	•	Bypassing other apps' mock-location detection. If an app checks Location.isMock() and rejects mocks, that's out of scope.
4. Tech stack (defaults — tell me if you'd pick something else)
|Area           |Choice                                                                                            |
|---------------|--------------------------------------------------------------------------------------------------|
|Language       |Kotlin                                                                                            |
|UI             |Jetpack Compose + Material 3                                                                      |
|Min SDK        |26 (Android 8), target latest stable                                                              |
|Map            |Google Maps Compose (`maps-compose`)                                                              |
|Search         |Google Places SDK (New) Autocomplete, with session tokens and ~300 ms debounce                    |
|Search fallback|Nominatim (OpenStreetMap) or Photon if I don't want a Google API key. Ask me which before starting|
|Background     |Foreground Service (`foregroundServiceType="location"`)                                           |
|Storage        |DataStore (settings), Room (favorites/recents, v2)                                                |
|Architecture   |Single-activity, ViewModel + StateFlow, simple repository layer                                   |


5. How the mocking must work
Android only allows mock locations from an app the user has picked under Developer options → Select mock location app. Design around that:
	1.	Manifest: declare android.permission.ACCESS_MOCK_LOCATION (add tools:ignore="MockLocation"), plus ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS, INTERNET. Without ACCESS_MOCK_LOCATION in the main manifest the app won't show up in the mock-app picker.
	2.	Framework providers: use LocationManager.addTestProvider() + setTestProviderEnabled() + setTestProviderLocation() for both GPS_PROVIDER and NETWORK_PROVIDER.
	3.	Fused provider (important): WhatsApp and Google Maps mostly read from Google Play Services' Fused provider. Also call FusedLocationProviderClient.setMockMode(true) and setMockLocation(location). Feed both paths, or WhatsApp may show the real location.
	4.	Keep pushing updates. Live location only works if fresh fixes keep arriving. Push a new Location roughly every 1 second from a coroutine inside the foreground service.
	5.	Populate every Location field realistically:
	•	latitude, longitude
	•	accuracy ≈ 5–10 m
	•	altitude (0 or a sane fixed value)
	•	speed = 0, bearing = 0 (unless movement sim is on)
	•	time = System.currentTimeMillis()
	•	elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() (required, or the update is rejected or looks stale)
	6.	Stopping: removeTestProvider() for each provider (wrapped in try/catch), setMockMode(false), stop the service, cancel the notification. Also clean up in onDestroy and when the task is removed.
	7.	Foreground service with a persistent notification ("Mock location active: <place>") and a Stop action button. Handle the battery-optimization prompt so the OS doesn't kill it.
Signal safety rules (hard requirements)
	•	Never call anything that toggles radios, airplane mode, Wi-Fi, mobile data or Bluetooth.
	•	Never request WRITE_SECURE_SETTINGS or use root/ADB tricks at runtime.
	•	Only interact with LocationManager test providers and the Fused mock API.
	•	Leave real providers alone; if the mock is stopped, the phone's real GPS/Network location must resume on its own.
6. Screens and UX
Main screen (only screen for v1)
	•	Top: search bar with autocomplete dropdown (Google Maps style: place name + secondary address line)
	•	Middle: full-screen map, camera moves to the selected result, pin at the chosen spot
	•	Bottom: card with place name, coordinates, and a primary button: Set Location (turns into Stop while active)
	•	Status chip: Idle / Mocking / Setup needed
First-run setup check
	•	Detect if the app is currently the selected mock location app (check AppOpsManager for android:mock_location, or catch the SecurityException from addTestProvider)
	•	If not, show a short guide + a button that deep-links to Developer options (Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), with a note on how to enable Developer options first (tap Build number 7×)
	•	Request location + notification permissions with clear rationale text
Error states to handle: no internet (search fails), Places API quota/key error, mock permission missing, service killed by the OS, location permission denied.
7. Suggested structure


app/src/main/java/.../mockloc/
  MainActivity.kt
  ui/            (MainScreen, SearchBar, MapView, SetupGuide, theme)
  viewmodel/     (MainViewModel: search state, selected place, mock state)
  data/          (PlacesRepository, SettingsStore, later: Room favorites)
  mock/          (MockLocationManager, MockLocationService)

	•	MockLocationManager — pure logic: add/remove test providers, build Location objects, push to framework + fused. No UI code.
	•	MockLocationService — foreground service, owns the 1 s update loop, calls MockLocationManager.
	•	API keys go in local.properties → BuildConfig/manifest placeholders. Never commit keys.
8. Milestones
	1.	Project scaffold, permissions, manifest, setup-check screen
	2.	Map + search + pin selection (no mocking yet)
	3.	MockLocationManager + foreground service feeding framework and fused providers
	4.	Verify against Google Maps (blue dot jumps to mocked spot)
	5.	Verify WhatsApp current + live location (see checklist)
	6.	Clean stop, edge cases, notification actions
	7.	Polish (icon, dark mode, empty/error states), then v2 items
9. Test checklist (all must pass)
	☐︎	Setting a location moves the Google Maps blue dot within a couple of seconds
	☐︎	WhatsApp → Attach → Location → Send your current location shows the mocked place
	☐︎	WhatsApp → Share live location (15 min) keeps showing the mocked place, updates don't freeze
	☐︎	Live location still works for at least 1 hour with screen off
	☐︎	Stop button restores the real location within a few seconds
	☐︎	Force-stopping the app leaves no stuck mock location
	☐︎	Calls, mobile data, Wi-Fi and hotspot all work normally while mocking
	☐︎	Search works for landmarks, addresses, and city names
	☐︎	Rotating the screen / backgrounding the app doesn't drop the mock
	☐︎	Battery drain is reasonable (no wake-lock abuse)
10. Known limitations (document these in the app's About screen)
	•	Apps that check Location.isMock() may reject or flag mocked locations. Nothing here tries to hide that.
	•	Phone must have Developer options enabled and this app selected as the mock location app.
	•	Behavior can vary slightly across OEM skins (Samsung, Xiaomi, etc.) because of aggressive battery management.
11. Responsible use
This is for testing, development, and privacy (e.g., not sharing my real spot). Don't add features meant to deceive safety-critical services or violate other apps' terms.




12. Questions the agent should ask me before starting
	1.	Google Maps + Places (needs an API key and billing account) or free OpenStreetMap-based search? ans: osm based search
	2.	Which Android version and phone model am I testing on? ans: samsung s24
	3.	App name and package name preference? ans:jelly loc
	4.	Do I want any v2 items pulled into v1? ans:yeah all.



## Vercel deployment

This repository is primarily an Android app source project.  
For Vercel, a static landing page is included with:

- `/index.html`
- `/vercel.json`

Deploy steps:

1. Import this repository in Vercel.
2. Set **Framework Preset** to **Other**.
3. Leave build command empty.
4. Deploy.

Vercel will serve `index.html` for all routes using `vercel.json` rewrites.
