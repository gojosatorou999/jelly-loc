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