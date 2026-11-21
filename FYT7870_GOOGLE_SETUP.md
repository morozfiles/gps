# FYT 7870 — Google Maps + OBD / GPS / Route DR

This is a **separate FYT 7870 build**, based on the mobile navigator v0.12.9.
The phone branch `main` and legacy FYT branch `fyt7870` are not overwritten.

## Installation

- Package: `ua.obdgps.nav.fyt7870.google` (not `ua.obdgps.nav` and not `ua.obdgps.nav.fyt7870`).
- App display name: **FYT 7870 Google Nav**.
- Version: `0.13.0-fyt-google` (`versionCode=52`).
- The old FYT app remains installed for rollback. The new app starts with separate
  private preferences, saved Home address, learning data, ELM327 selection and search
  history: Android does not let one package access another's private storage.
- GitHub Actions signs the FYT APK with the same permanent certificate used in this
  project's CI builds, and rejects any different certificate. Future FYT Google
  builds with this same package and signer will update in place.

## Google Cloud API-key setup (important)

Maps SDK for Android **must** permit:
- package `ua.obdgps.nav.fyt7870.google`
- the **SHA-1** of the actual CI certificate. Find the certificate SHA-1 in
  **Build FYT7870 Google APK → Verify APK signature** step, which prints
  `Signer #1 certificate SHA-1 digest`. Do not use the SHA-256 in its place.

Prefer two separate GitHub Actions secrets:
- `GOOGLE_MAPS_FYT7870_KEY`: Android application restricted to this FYT
  package + SHA-1, API restriction **Maps SDK for Android**.
- `GOOGLE_ROUTES_FYT7870_KEY`: API restriction **Routes API**, with
  appropriate application restrictions and budget/quotas. The app sends
  `X-Android-Package` and `X-Android-Cert` headers.

Until those two FYT secrets are provided, CI falls back to the existing phone
`GOOGLE_MAPS_ANDROID_KEY` and `GOOGLE_ROUTES_API_KEY`. **A successful APK
build does not prove the fallback credentials authorize the new FYT package.**
A phone-only Android-restricted key will render blank map tiles or fail to
compute routes. Either configure FYT-specific keys or explicitly permit the
FYT package and signer in compatible existing credentials. Never publish API
keys in GitHub source, issues or chat.

Google Cloud Routes API may incur charges above free limits. Keep the existing
daily quota and billing alerts. Do not enable new paid services without consent.

## FYT hardware caveats and behavior

1. Uses the built-in Android `GPS_PROVIDER` if present, plus independent
   Android Wi-Fi/cellular network positioning and Play-services fused estimates.
   A GPS coordinate is checked for spoofing and implausible jumps before use.
2. When reliable GPS disappears, **fresh OBD speed** advances the position along
   the selected Google route. Gyroscope yaw helps match planned turns **only if
   a real Android gyroscope is exposed**; many head-unit revisions lack it.
   No gyro means no independently detected turns: the displayed route-constrained
   position is only an estimate, with a growing uncertainty circle.
3. Bluetooth ELM327 works through Android Bluetooth Classic/SPP or BLE when
   `BluetoothManager` exposes a real adapter. Some FYT firmwares route Bluetooth
   through an MCU/vendor service; third-party apps cannot automatically access
   that proprietary connection. The diagnostics panel reports this limitation.
   If there is no OBD speed, it cannot dead-reckon distance from OBD.
4. Google Maps SDK requires a working **Google Play services** installation.
   When unavailable, the app displays a clear diagnostic message. The APK
   does not install or replace Google services.
5. Microphone button uses Android speech recognition activity, which requires
   a compatible speech recognizer and working head-unit microphone. If absent,
   the app displays an explanation and typed search remains available.
6. UI is landscape-first, with narrower dark floating search and navigation
   controls. It includes Home, five recent searches, voice lookup, adaptive
   camera/arrow/uncertainty, night map and ETA. No mock GPS injection.
7. Home/history, route session and OBD settings belong to this new package.
   Route geometry is cached for at most 29 days; monitoring stops when no
   app screen is visible and resumes on return.

## First on-device check

- Open the app and verify Google Maps displays. If missing, check Play services
  and the package's Google API-key restrictions.
- Tap the settings gear → **Проверить оборудование магнитолы** to see
  GPS, network, gyro, Bluetooth and GMS availability.
- Select an ELM327 in settings (standard Android Bluetooth only); confirm OBD
  speed updates. Without OBD, do not rely on DR after GNSS loss.
- Select a destination, build and choose a Google route, tap **Поехали**.
- Check the arrow, route follow camera, Home search, voice input, and GNSS →
  OBD → GNSS recovery. Test with a stationary or simulated environment before
  relying on live directions; spoofing protections cannot guarantee a valid fix.
