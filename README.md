# TowerScope

Android field app that loads tower locations from **KML/KMZ** and shows them on a **hybrid compass radar** (bearings + distance), with high-accuracy GPS, sun/moon heading calibration, and line-of-sight elevation profiles.

## Requirements

- Android Studio Ladybug (or newer) with JDK 17
- Physical device with magnetometer + GPS
- Outdoor use with clear sky for best GPS / compass results

## Open in Android Studio

1. **File → Open** and select this folder (`towerscope-ar`)
2. Let Gradle sync finish
3. Connect a device and run the `app` configuration

## How to use

1. Launch into **Home** — upload KML/KMZ, open Compass, Elevation profiles, or Settings
2. Grant **precise Location** when entering Compass
3. Hold the phone upright — the top of the radar disc is the direction you face
4. Tap the sun button to calibrate with the sun or moon for best heading accuracy
5. Drag the **Max distance** slider in Settings (persists across launches)
6. Tap a tower for details / LOS, or open **Elevation profiles** for ranked LOS in range

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
| `location/DeviceHeadingClient.kt` | True-north heading + declination |
| `ui/CompassRadarView.kt` | Hybrid rotating radar compass |
| `elevation-api/` | LiDAR + DEM LOS elevation service |
| `assets/sample_towers.kml` | Demo placemarks |

## Stack

- Kotlin, minSdk **33**
- XML outdoor HUD + Play Services Location
- Rotation vector compass (no ARCore / camera)

## Notes

- Sample coordinates are near Austin, TX — replace with your own KML for local testing
- Hidden towers are session-only (not persisted)
- Package id remains `com.towerscope.ar` for sideload continuity
