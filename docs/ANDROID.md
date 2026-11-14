# Jelly Loc on Android

Jelly Loc sets a fake GPS location for your **whole phone**, so apps like WhatsApp (Share live location), Google Maps and others see the place you pick. No laptop or server is needed. Everything runs on the phone.

> The Jelly Loc **website** can't do this. A web page can never change your phone's location for other apps. You need the Android app below.

---

## 1. Install

1. On your phone, open: **https://github.com/gojosatorou999/jelly-loc/releases/latest/download/jelly-loc.apk**
2. Open the downloaded `jelly-loc.apk`.
3. If Android says it can't install apps from this source, tap **Settings**, allow **Install unknown apps** for your browser, then go back and tap **Install**.
4. If Play Protect warns about an unrecognized app, tap **More details → Install anyway**. That warning is normal for apps not installed from the Play Store.

**Updating:** download the same link again and install over the old app. Your favorites are kept.

---

## 2. One-time setup

Open Jelly Loc. It walks you through these steps and moves on to the map once they're done.

### a) Turn on Developer options
Settings → **About phone** → tap **Build number** 7 times, then enter your PIN if asked.
Where to find it:

| Brand | Path |
|---|---|
| Samsung | Settings → About phone → Software information → Build number |
| Xiaomi / Redmi / POCO | Settings → About phone → **OS version** (MIUI/HyperOS version), tap 7 times |
| OnePlus / Oppo / Realme | Settings → About device → Version → Build number |
| Pixel / Motorola / others | Settings → About phone → Build number |

### b) Select Jelly Loc as the mock location app
Settings → System → **Developer options** → **Select mock location app** → **Jelly Loc**.
(On Samsung, Developer options is at the bottom of the main Settings list.)

### c) Allow permissions
When Jelly Loc asks, allow **Location** and **Notifications**. Choose **Precise** location if asked.

### d) Stop the battery saver from killing it
If Jelly Loc shows a red **"Battery saver may stop mocking"** card, tap **Fix** → **Allow**.
On Xiaomi, Oppo, Vivo and Samsung, also do this:
- **Xiaomi:** Settings → Apps → Jelly Loc → Battery saver → **No restrictions**, and turn on **Autostart**.
- **Samsung:** Settings → Battery → Background usage limits → **Never sleeping apps** → add Jelly Loc.
- **Oppo / Realme / Vivo:** Settings → Battery → Jelly Loc → **Allow background activity**.

---

## 3. Fake your location

1. Open Jelly Loc.
2. Search for a place, paste coordinates like `17.3616, 78.4747`, or **tap the map**.
3. Tap **Start mocking**. A **"Mock location active"** notification appears.
4. You can now leave Jelly Loc. It keeps running in the background.

To **move** while mocking, just pick another place. The location changes right away.

### Sharing a fake live location on WhatsApp
1. Start mocking in Jelly Loc (step 3 above) and wait about 5 seconds.
2. Optional check: open Google Maps. The blue dot should be at your fake place.
3. In WhatsApp, open the chat → 📎 → **Location** → **Share live location** → pick a duration → send.
4. Leave Jelly Loc running for the whole duration.

> ⚠️ **Stop sharing in WhatsApp *before* you stop mocking.** If you stop mocking while live location is still on, WhatsApp starts sending your **real** location.

### Stopping
Tap **Stop mocking** in the app, or **Stop** in the notification. Your real GPS comes back within a few seconds.

---

## 4. Troubleshooting

| Problem | Fix |
|---|---|
| App stays on the **"One-time setup"** screen | Step 2b isn't done. Re-select Jelly Loc under *Select mock location app*, then come back. |
| "Select Jelly Loc as the mock location app" error | Same as above. Some phones reset this after an update. |
| WhatsApp or Maps still shows your real location | Check that the notification is showing. Force-close and reopen WhatsApp/Maps. Turn Wi-Fi off and on. If it still jumps back, turn off Settings → Location → **Location services** → *Wi-Fi scanning* and *Bluetooth scanning*, and on some phones **Google Location Accuracy**. |
| Mocking stops on its own after a while | Do every part of step 2d. Don't use "clear all" in recent apps on Xiaomi/Oppo unless Jelly Loc is locked there (long-press its card → 🔒). |
| Map is blank | It needs internet to load map tiles. Search still works, and so does pasting coordinates. |
| "App not installed" when updating | You have a version signed differently. Uninstall Jelly Loc once, then install the new APK. |

---

## 5. Good to know

- **Apps can tell it's a mock.** Android marks fake locations as coming from a mock provider, and any app can check that flag. Most apps, including WhatsApp at the time of writing, don't block it, but some banking, ride-hailing and game apps do.
- **A location that never moves can look suspicious.** Jelly Loc adds a few meters of natural drift automatically.
- **Be responsible.** Faking your location to mislead someone who relies on it for safety (family, partner, work) can cause real harm.

---

## For developers

- Source code: `android/` (Kotlin, Jetpack Compose, osmdroid map).
- Every push to `main` that changes `android/**` triggers `.github/workflows/android-apk.yml`. It builds a signed release APK and publishes it as a GitHub Release, so the download link above always points to the newest build.
- The signing key is stored as repository secrets (`JELLYLOC_KEYSTORE_BASE64`, `JELLYLOC_KEYSTORE_PASSWORD`, `JELLYLOC_KEY_ALIAS`, `JELLYLOC_KEY_PASSWORD`). Keep a private backup of the keystore. Without it, future APKs can't install as updates.
- Local build: install JDK 17 and the Android SDK (platform 35), then run `cd android && ./gradlew assembleRelease`.
