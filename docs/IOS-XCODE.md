# Jelly Loc on iPhone (Xcode)

This guide is written so a person **or a coding agent on a Mac** can follow it top to bottom.

## How it works (read first)

iOS does **not** let apps fake the location for other apps. No App Store app, web page or PWA can do it. The one supported method is **Xcode's location simulation**:

1. Build the small `JellyLocHost` app (in `ios/`) onto the iPhone from Xcode.
2. While it runs from Xcode, Xcode tells iOS to report a location from a GPX file.
3. **Every app** on the iPhone (Maps, WhatsApp, …) then sees that location.

**Limits:**
- **A Mac is required, and Xcode stays in charge.** Keep the Mac awake and the iPhone connected (USB, or Wi-Fi pairing) while you need the fake location. When the debug session ends or the phone is disconnected, iOS can switch back to the real location. A reboot always resets it.
- **WhatsApp live location on iPhone** works only while this is active. Stop sharing in WhatsApp *before* you stop Xcode, or WhatsApp starts sending the real location.

For a Mac-free option, use an Android phone with the Jelly Loc app. See [ANDROID.md](ANDROID.md).

---

## Requirements

| Item | Notes |
|---|---|
| Mac with **Xcode 15 or newer** | Install from the App Store, then run Xcode once and accept the license. |
| Apple ID | A free account works. Apps signed with it expire after 7 days; just rebuild. |
| iPhone with **iOS 16+** | Cable or Wi-Fi pairing. |
| **Developer Mode** on the iPhone (iOS 16+) | Settings → Privacy & Security → Developer Mode → On → restart. It only appears after the phone has been connected to Xcode once. |
| Homebrew | Used to install XcodeGen: https://brew.sh |

---

## Agent runbook

Run these from the repository root unless a step says otherwise.

### Step 1: Tools
```bash
xcode-select -p || xcode-select --install    # command-line tools
sudo xcodebuild -license accept              # once, needs the user's password
brew install xcodegen
```

### Step 2: Choose the fake location
```bash
cd ios
./scripts/set-location.sh 17.361563 78.474665 "Charminar, Hyderabad"
```
This writes `ios/Locations/Location.gpx`. To get coordinates for a place, use the Jelly Loc web app's search, or Google Maps (long-press a spot and copy the numbers).

### Step 3: Generate the Xcode project
```bash
xcodegen generate          # creates ios/JellyLocHost.xcodeproj (git-ignored, always regenerate)
```

### Step 4: Compile check (no phone or signing needed)
```bash
xcodebuild -project JellyLocHost.xcodeproj -scheme JellyLocHost \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```
It should end with `** BUILD SUCCEEDED **`. CI runs this same check on every push (`.github/workflows/ios-build.yml`).

### Step 5: Sign in and set up signing (needs the human, once)
The agent can't type the Apple ID password, so ask the user to:
1. Open Xcode → **Settings → Accounts** → **+** → sign in with their Apple ID.
2. Note the **Team ID**: in that Accounts screen, select the team; the ID looks like `AB12CD34EF`.

Then the agent can sign from the command line:
```bash
TEAM_ID=AB12CD34EF   # from the user
xcodebuild -project JellyLocHost.xcodeproj -scheme JellyLocHost \
  -destination 'generic/platform=iOS' \
  DEVELOPMENT_TEAM="$TEAM_ID" -allowProvisioningUpdates build
```
If signing fails with *"bundle identifier is not available"*, change `PRODUCT_BUNDLE_IDENTIFIER` in `ios/project.yml` to something unique (for example append `.$TEAM_ID`), then run `xcodegen generate` again.

### Step 6: Run on the iPhone with the simulated location (Xcode GUI)
Location simulation on a real device is applied by Xcode's debugger, so this step uses the Xcode app:
```bash
open JellyLocHost.xcodeproj
```
Then, in Xcode:
1. Connect the iPhone, unlock it, and tap **Trust** if asked.
2. In the toolbar, pick the **JellyLocHost** scheme and the **iPhone** as the run destination.
3. **Signing & Capabilities** tab → Team = the user's team (skip if Step 5 already set it).
4. Press **⌘R** (Run).
5. First time only, on the iPhone: Settings → General → **VPN & Device Management** → trust the developer app. Then run again.
6. When the app opens, allow location. It shows **"iOS is reporting"** with the GPX coordinates.

The scheme is generated with **Allow Location Simulation** on and `Locations/Location.gpx` as the default, so the location applies as soon as the app launches.

### Step 7: Check it
- The JellyLocHost screen shows the GPX coordinates.
- Open **Apple Maps** on the iPhone. The blue dot should be at the fake place.

### Changing the location while it's running
In Xcode's menu: **Debug → Simulate Location → Location** (the GPX), or pick a built-in city.
To use a new place: run `./scripts/set-location.sh LAT LON "Name"` again, then in Xcode select **Debug → Simulate Location → Location** again (or stop and press ⌘R).

### Stopping
Press **⏹ Stop** in Xcode, or choose **Debug → Simulate Location → Don't Simulate Location**. If the phone keeps the fake location, restart the iPhone.

---

## iOS Simulator (no iPhone)
For testing on the Mac only:
```bash
xcrun simctl boot "iPhone 15" 2>/dev/null || true   # any installed simulator name
xcrun simctl location booted set 17.361563,78.474665
```

## Troubleshooting

| Problem | Fix |
|---|---|
| iPhone not listed in Xcode | Unlock it, tap Trust, turn on Developer Mode, and check the cable. In Xcode: Window → Devices and Simulators. |
| "Untrusted Developer" on launch | iPhone Settings → General → VPN & Device Management → trust your Apple ID. |
| Location doesn't change | Debug → Simulate Location → pick **Location**. Check the scheme: Product → Scheme → Edit Scheme → Run → Options → *Allow Location Simulation* is ticked and *Default Location* = Location. |
| App stops opening after 7 days | Free-account signing expired. Press ⌘R again. |
| `xcodegen: command not found` | `brew install xcodegen` |
| Agent can't finish Step 6 headlessly | Expected. Real-device location simulation goes through Xcode's debugger. Have the human press ⌘R. |

## Files
| Path | What |
|---|---|
| `ios/project.yml` | XcodeGen spec: target, bundle ID, location permission text, scheme with location simulation |
| `ios/JellyLocHost/JellyLocHostApp.swift` | The host app: shows the location iOS reports |
| `ios/Locations/Location.gpx` | The location Xcode simulates (written by the script) |
| `ios/scripts/set-location.sh` | Writes the GPX from `LAT LON "Name"` |
