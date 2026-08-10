# TowerScope Elevation API

LiDAR-first LOS elevation sampling with 3DEP DEM fallback.

Optional response cache (7-day TTL) is skipped when the client sends `"bypassCache": true` (TowerScope Android always does).

## Endpoints

- `GET /health` — liveness; reports whether `pdal` is on `PATH`
- `POST /v1/los-profile` — sample geodesic path elevations

### Request

```json
{
  "observer": { "lat": 35.90, "lon": -96.87 },
  "tower": { "lat": 35.897, "lon": -96.875 },
  "sampleCount": 50
}
```

### Response

Each sample includes `groundElevationMeters` and `source` (`lidar` | `dem`), plus `lidarCoverageFraction`.

## Local (DEM-only without PDAL)

```bash
cd elevation-api
python -m venv .venv
# Windows: .venv\Scripts\activate
pip install -r requirements.txt
# Optional (LiDAR index): pip install shapely==2.1.0
uvicorn app.main:app --reload --port 8080
```

Without PDAL/shapely, all samples use the USGS 3DEPElevation ImageServer (bare-earth DEM).
Docker installs shapely + PDAL for LiDAR.

## Docker (LiDAR + DEM)

Requires PDAL for USGS EPT first-return queries:

```bash
docker build -t towerscope-elevation .
docker run --rm -p 8080:8080 towerscope-elevation
```

## Fly.io

```bash
fly apps create towerscope-elevation   # once
fly deploy
```

Set the Android `LOS_ELEVATION_API_BASE_URL` in `local.properties` to the public HTTPS origin (no trailing slash), e.g.:

```
LOS_ELEVATION_API_BASE_URL=https://towerscope-elevation.fly.dev
```

## Notes

- Vertical values are meters. DEM is orthometric (3DEP). EPT Z is used as returned by the resource (typically survey meters); mixed lidar/DEM fills may have small datum offsets.
- Optional server cache TTL is 7 days; key rounds observer (~25 m) and tower (~1 m).
- Clients may set `"bypassCache": true` on `POST /v1/los-profile` to force a live LiDAR/DEM query (TowerScope does this).
- Rate limit: 30 requests / minute / IP.
