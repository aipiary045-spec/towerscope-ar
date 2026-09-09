# TowerScope

Android field toolkit for WISP / wireless install techs.

**Tools** — every field tool on one screen (search or filter Network / Install)  
**Network** — on-device checks without an account  
**Install** — site aiming, locate map, and line-of-sight clearance

## Features

### Network
- **Wi‑Fi signal** — live RSSI, active channel, overlapping APs, RF interference hints
- **Speed test** — Cloudflare download / upload / latency
- **Ping & loss** — ping multiple IPs at once with a live log
- **Path Doctor** — layered path check (link → DNS → TCP → TLS → HTTP) that shows where it failed
- **Subnet scanner** — live LAN hosts, MAC when available, tap IP to open in a browser

### Install
- **Compass** — high-precision bearings to nearby sites (KML/KMZ)
- **Locate** — satellite map, install pin, nearby or all-sites view
- **Line of sight** — Fresnel clearance ranking + elevation profiles (LiDAR/DEM when configured)
- **Import sites** — KML / KMZ / CSV

## Sideload install

Latest APK (replaceable tag):

https://github.com/aipiary045-spec/towerscope-ar/releases/download/sideload-latest/TowerScope-AR-sideload.apk

Older builds stay under dated `sideload-*` release tags if you need to roll back.

## Requirements

- Android Studio (JDK 17+)
- Physical device with GPS + magnetometer (minSdk **33**)
- Outdoor use for best compass / GPS results
- Internet for speed test, Path Doctor targets, and satellite tiles (Esri World Imagery — no Google Maps billing)

## Open in Android Studio

1. **File → Open** → this folder (`towerscope-ar`)
2. Let Gradle sync finish
3. Run the `app` configuration on a device

## Quick start

1. Open **Tools** and tap a tool (or filter **Network** / **Install**, or type to find)
2. Import sites from **Sites** on Tools / Install, or from Settings, when using install tools
3. Grant **precise Location** (and nearby Wi‑Fi when scanning)
4. Use **Settings** (accordion) for theme, units, compass improve, RF params, and range

## LOS elevation (optional)

Installation LOS profiles prefer a small elevation API (USGS LiDAR first-return, 3DEP DEM fallback) and fall back to on-device 3DEP DEM if the API is unreachable.

1. Deploy [`elevation-api/`](elevation-api/README.md)
2. Add to `local.properties` (not committed):

```
LOS_ELEVATION_API_BASE_URL=https://your-elevation-host
```

## Project layout

| Path | Role |
|------|------|
| `HomeActivity` | Tools launcher (all tools, one tap) |
| `network/` | Wi‑Fi, speed, ping, Path Doctor, subnet |
| `MapActivity` | Satellite locate map (osmdroid + Esri) |
| `ui/CompassRadarView.kt` | Hybrid rotating radar compass |
| `data/KmlParser.kt` | KML / KMZ parser |
| `elevation-api/` | Optional LiDAR + DEM LOS service |
| `scripts/publish_sideload.ps1` | Build + publish sideload APK |

## Stack

- Kotlin · XML UI · AppCompat light/dark
- Play Services Location · rotation-vector compass (no ARCore camera)
- osmdroid + Esri World Imagery

## Notes

- Package id is `com.towerscope.ar` (sideload continuity)
- Network tools run on-device; no login required
- Sample / demo coordinates may be near Austin, TX — import your own sites for local work
