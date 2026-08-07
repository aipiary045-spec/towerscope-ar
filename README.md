# TowerScope AR

Android AR app that loads tower locations from **KML/KMZ** files and renders them as outdoor Geospatial markers with name labels, high-accuracy GPS, and a distance filter.

## Requirements

- Android Studio Ladybug (or newer) with JDK 17
- Physical ARCore-supported device (Geospatial needs a compatible magnetometer)
- Google Play Services for AR installed
- Outdoor use with clear sky for best Geospatial / GPS results
- Google Cloud project with the **ARCore API** enabled

## Open in Android Studio

1. **File → Open** and select this folder (`towerscope-ar`)
2. Let Gradle sync finish
3. Connect a device and run the `app` configuration

## API key setup

1. Create (or open) a project in [Google Cloud Console](https://console.cloud.google.com/)
2. Enable **ARCore API**
3. Create an API key (or use keyless auth for your signing certificate)
4. Replace `YOUR_API_KEY` in [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml):

```xml
<meta-data
    android:name="com.google.android.ar.API_KEY"
    android:value="YOUR_API_KEY" />
```

## How to use

1. Grant **Camera** and **precise Location** permissions
2. Tap **Sample** to load bundled placemarks, or **Load KML** to pick a `.kml` / `.kmz` file
3. Wait until the HUD shows **EARTH OK** and a GPS accuracy reading
4. Drag the **Max distance** slider (100 m – 10 km) to hide far towers
5. Tap a yellow marker (or a name chip) → **Hide tower** to filter it out for the session
6. Use the eye icon to restore hidden towers

## LOS elevation (LiDAR + DEM)

Tower details can show a line-of-sight elevation profile. The app prefers a small elevation API (USGS LiDAR first-return, 3DEP DEM fallback, caches) and falls back to on-device 3DEP DEM if the API is unreachable.

1. Deploy [`elevation-api/`](elevation-api/README.md) (Docker / Fly.io)
2. Add to `local.properties` (not committed):

```
LOS_ELEVATION_API_BASE_URL=https://your-elevation-host
```

3. Clutter slider applies only to **DEM** samples (LiDAR already includes canopy)

## Project layout

| Path | Role |
|------|------|
| `data/KmlParser.kt` | Direct KML / KMZ parser (`XmlPullParser` + zip) |
| `location/HighAccuracyLocationClient.kt` | Fused location at `PRIORITY_HIGH_ACCURACY` |
| `ar/TowerMarkerController.kt` | Geospatial anchors + distance detach |
| `elevation-api/` | LiDAR + DEM LOS elevation service |
| `assets/sample_towers.kml` | Demo placemarks |

## Stack

- Kotlin, minSdk **33**
- [SceneView](https://github.com/SceneView/sceneview-android) `arsceneview` **2.2.1** + ARCore Geospatial
- XML outdoor HUD (high-contrast) + Play Services Location

## Notes

- Sample coordinates are near Austin, TX — replace with your own KML for local testing
- Marker altitude uses KML altitude when present; otherwise camera Geospatial altitude
- Hidden towers are session-only (not persisted)
