# Offline Ukraine overview + on-demand oblast map packs

This feature is implemented in Android source, but **the app does not ship a
public, preconfigured regional map server**. Do not represent it as a turnkey
automatic map download until the pack hosting step below has been completed.

## Expected result

- On a phone with no offline maps, a configured pack host allows the app to
  download `overview.map`, `kyiv.map` and `kyiv.poi` on first launch.
- A second oblast is downloaded only after selection in **Офлайн-карты**.
- Every pack is stored under Android app-private `files/offline-region-packs`;
  subsequent launches render from local files and work with network off.
- Overview, detailed oblast and separately imported older nationwide map can be
  rendered simultaneously through Mapsforge MultiMapDataStore.
- A partial download is retained, resumed with HTTPS HTTP Range, validated,
  and atomically swapped only after successful completion.
- The old country-wide 833 MB Mapsforge download remains available separately.
- Local address search depends on the Mapsforge POI data; routes still require
  internet for route *calculation* (GPS/OBD/DR position continues offline).

## Publish download files

1. In GitHub Actions, run `Prepare Ukraine overview or detailed oblast
   Mapsforge pack` with `region=overview`, then `region=kyiv`, and additional
   regions as needed.
2. Download the generated Actions artifacts. Artifacts expire after 30 days,
   so **do not** use their artifact URLs directly as app map URLs.
3. Place the unpacked files on your own/static HTTPS file host. The directory
   must serve files named exactly:
   ```
   overview.map
   kyiv.map
   kyiv.poi
   lviv.map
   lviv.poi
   # ... other selected region IDs from OfflineRegionPacks.kt
   ```
4. In **Офлайн-карты → Настроить источник, Wi-Fi и автозагрузку**, enter that
   HTTPS directory URL, then save. Auto mode downloads missing overview and
   Kyiv maps, while other regions download when selected.
5. Test downloads and rendering in airplane mode, and ensure the server
   supports HTTP Range for efficient resume.

The workflow uses Geofabrik/OSM extracts and Mapsforge writer, not public OSM
raster tile bulk downloads. All OSM-derived map files must have appropriate
OpenStreetMap attribution and ODbL compliance. Verify that the supplied OSM
extract covers all administrative areas and that the workflow has sufficient
runner resources before public distribution.

## Known constraints

- **This is not finished one-tap distribution** until the pack host exists.
- Missing files, unsupported format, insufficient storage and network
  restrictions show an error instead of wiping prior files.
- The overview is intentionally low detail: large roads, city labels and
  boundaries. Detailed streets require downloaded oblast packs.
- Mapsforge POIs may not contain complete house numbers, so offline search
  accuracy varies by the available OpenStreetMap source data.
- The existing raster MBTiles import and viewed-tile cache are unchanged.
- A public HTTPS map package server is separate from the private source
  repository; never embed GitHub access tokens into the APK.
