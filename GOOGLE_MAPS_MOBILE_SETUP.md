# Google Maps + OBD/DR (mobile Xiaomi 13T only)

This experimental feature is isolated to the **phone** source branch. FYT 7870 is not modified.

## Architecture
- Google Maps SDK for Android draws Google's map, copyright and attribution.
- Google Routes API (ComputeRoutes) provides the road geometry and turn descriptions.
- `NavService` provides filtered GPS, Android network positioning, calibrated gyro and OBD speed.
- `RouteOdometer` advances a **display-only** estimated position on the existing Google route when GNSS becomes unreliable. It performs small, gated route-progress corrections from independently obtained positions.
- The DR estimate is never fed back as a genuine GNSS fix, and is labelled as an estimate.
- Existing offline/OSM navigation is unchanged. Google route geometry is not drawn on the OSM basemap.

## Required configuration
Enable **Maps SDK for Android** and **Routes API** in a Google Cloud project with billing.
Supply the following GitHub Actions repository secrets:
- `GOOGLE_MAPS_ANDROID_KEY`: a Maps SDK key restricted to Android package `ua.obdgps.nav` and the installed app's permanent signing SHA-1 certificate. If building a parallel install, whitelist its separate package ID.
- `GOOGLE_ROUTES_API_KEY`: Routes API key. In this prototype it is used from the Android client. **Important security limitation:** any key embedded in an APK can be extracted, and server-side API restrictions are not equivalent to Android-restricted Maps SDK credentials. For production, move the Routes API request to your authenticated backend or a quota-controlled proxy before enabling public distribution. Restrict the key's allowed APIs, set budgets/quotas, and rotate it before public release.

Without these keys the code builds and the existing app remains usable, but the new Google map/route mode cannot load real Google data.

## Testing
1. Confirm the Google map loads and shows attribution.
2. Tap destination, request Google route, and select Start.
3. Test GPS working; verify OBD distance and map progress.
4. Disable GPS and test with fresh OBD speed and calibrated gyro; verify it stays an **estimated** route position.
5. Check wrong turns, long GPS outages, Wi-Fi/cell corrections and spoofed jumps.
6. Ensure installed signature and package ID match previous phone APK; do not uninstall the app or clear offline data.

Known limitations: no autonomous reroute without location evidence, no offline Google basemap guarantee, no spoof-proof GNSS authentication, Google billing and API availability required. The derived route follower cannot prove that a driver chose the correct branch of a junction.
