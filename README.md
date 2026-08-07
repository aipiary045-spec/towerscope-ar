# TowerScope

Android field app that loads tower locations from **KML/KMZ** and shows them on a **hybrid compass radar**, a **satellite map** with you→tower lines, and ranked line-of-sight elevation profiles.

## Requirements

- Android Studio Ladybug (or newer) with JDK 17
- Physical device with magnetometer + GPS
- Outdoor use with clear sky for best GPS / compass results
- Internet for satellite tiles (Esri World Imagery — no Google Maps billing)

## Open in Android Studio

1. **File → Open** and select this folder (`towerscope-ar`)
2. Let Gradle sync finish
3. Connect a device and run the `app` configuration

## How to use

1. Launch **Home** — import KML/KMZ (empty-state CTA or Settings → Import sites)
2. Grant **precise Location** when entering field tools
3. **Aim** — compass bearings; hold upright; sun/moon calibrate for best heading
4. **Locate** — satellite map; pin an **install site** (pin FAB = GPS, or long-press map); yellow path to selected AP
5. **Check LOS** — ranked APs by 60% first Fresnel (freq + CPE height in Settings)
6. Drag **range / frequency / CPE AGL** in Settings (persists across launches)

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
| `MapActivity.kt` | Satellite map (osmdroid + Esri imagery) |
| `elevation-api/` | LiDAR + DEM LOS elevation service |
| `assets/sample_towers.kml` | Demo placemarks |

## Stack

- Kotlin, minSdk **33**
- XML outdoor HUD + Play Services Location
- Rotation vector compass (no ARCore / camera)
- osmdroid + Esri World Imagery (no Google Maps billing)

## Notes

- Sample coordinates are near Austin, TX — replace with your own KML for local testing
- Hidden towers are session-only (not persisted)
- Package id remains `com.towerscope.ar` for sideload continuity
