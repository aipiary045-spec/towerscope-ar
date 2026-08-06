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

## Project layout

| Path | Role |
|------|------|
| `data/KmlParser.kt` | Direct KML / KMZ parser (`XmlPullParser` + zip) |
| `location/HighAccuracyLocationClient.kt` | Fused location at `PRIORITY_HIGH_ACCURACY` |
| `ar/TowerMarkerController.kt` | Geospatial anchors + distance detach |
| `ui/ArScreen.kt` | SceneView AR scene + outdoor HUD |
| `assets/sample_towers.kml` | Demo placemarks |

## Stack

- Kotlin, Jetpack Compose, minSdk **33**
- [SceneView](https://github.com/SceneView/sceneview) `arsceneview` **4.26.0** (ARCore Geospatial)
- Play Services Location

## Notes

- Sample coordinates are near Austin, TX — replace with your own KML for local testing
- Marker altitude uses KML altitude when present; otherwise camera Geospatial altitude
- Hidden towers are session-only (not persisted)
