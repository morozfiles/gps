# FYT 7870 — Google Maps + OBD / GPS / Route DR

This is an **upgrade-compatible FYT 7870 build**, based on the mobile navigator v0.12.9.
The phone branch `main` and legacy FYT branch `fyt7870` are not overwritten.

## Installation

- Package: `ua.obdgps.nav.fyt7870` (same as legacy FYT).
- App display name: **FYT 7870 Google Nav**.
- Version: `0.13.1-fyt-upgrade` (`versionCode=53`).
- Same-package upgrades retain existing data when APK signatures match. **Signing matters:**
  - The signed FYT upgrade has SHA-256 `0F97878527A8C268705C3BEBBF4570D78034DC26159EDB56D78743CFD202784E`, matching this build.
  - The legacy debug APK from run `44814332960` was signed with a **different** ephemeral key (SHA-1 `2C3089CCE7CF171D0629481E85B62B53AE4D3AE3`). It cannot be updated in place without that original private key.
  - If Android reports an update signature conflict, **do not uninstall yet**; preserve settings and data first.
- GitHub Actions signs the FYT APK with the same permanent certificate used in this
  project's CI builds, and rejects any different certificate. Future FYT Google
  builds with this same package and signer will update in place.

## Google Cloud API-key setup (important)

Maps SDK for Android **must** permit:
- package `ua.obdgps.nav.fyt7870`
- signing certificate **SHA-1**: `A7:26:85:42:23:72:15:BC:AC:74:11:03:86:06:0B:37:49:ED:27:2A`
  (verified in GitHub Actions **Verify APK signature** step).
  Do not enter the SHA-256 in its place.

Prefer two separate GitHub Actions secrets:
- `GOOGLE_MAPS_FYT7870_KEY`: Android application restricted to this FYT
  package + SHA-1, API restriction **Maps SDK for Android**.
- `GOOGLE_ROUTES_FYT7870_KEY`: API restriction **Routes API**, with
  appropriate application restrictions and budget/quotas. The app sends
  `X-Android-Package` and `X-Android-Cert` headers.

CI currently warns that the FYT-specific keys are **not present**, so the
new APK uses phone credentials. A Google Cloud project admin must add the
package/signing identity above to permitted Android applications on those
credentials, or create the dedicated FYT keys.

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
3. FYT-specific settings include **Калибровка гироскопа FYT · 5 секунд**
   while stationary, without requiring a linear acceleration sensor. The app
   supports either standard or uncalibrated Android gyroscope. If the gyro is
   wired to a vendor MCU only, it needs an Android-accessible interface.
4. Bluetooth ELM327 works through Android Bluetooth Classic/SPP or BLE when
   `BluetoothManager` exposes a real adapter. Some FYT firmwares route Bluetooth
   through an MCU/vendor service; third-party apps cannot automatically access
   that proprietary connection. The diagnostics panel reports this limitation.
   If there is no OBD speed, it cannot dead-reckon distance from OBD.
5. Google Maps SDK requires a working **Google Play services** installation.
   When unavailable, the app displays a clear diagnostic message. The APK
   does not install or replace Google services.
6. Microphone button uses Android speech recognition activity, which requires
   a compatible speech recognizer and working head-unit microphone. If absent,
   the app displays an explanation and typed search remains available.
7. UI is landscape-first, with narrower dark floating search and navigation
   controls. It includes Home, five recent searches, voice lookup, adaptive
   camera/arrow/uncertainty, night map and ETA. No mock GPS injection.
8. Home/history, route session and OBD settings belong to the existing FYT package.
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

## Google Cloud & GitHub links

- Project: `carbide-trees-515409-v5`
- [API credentials](https://console.cloud.google.com/apis/credentials?project=carbide-trees-515409-v5)
- [Actions Secrets](https://github.com/morozfiles/gps/settings/secrets/actions)

A successful build has only confirmed that phone Google keys are available.
It has **not** confirmed the FYT package is authorized in Google Cloud.
Allowlist the FYT package and SHA-1 above alongside the phone application,
without removing the existing phone entry, or create FYT-specific restricted
keys for Maps SDK for Android and Routes API. Preserve existing quota and
billing safeguards. API credentials cannot be edited by the GitHub connector.
