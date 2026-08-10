"""TowerScope LOS elevation API: LiDAR first-return + 3DEP DEM fallback."""

from __future__ import annotations

import logging
import time
from collections import defaultdict
from typing import Literal

import httpx
from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from . import cache, dem, lidar
from .geo import LatLng, cache_key, haversine_meters, sample_geodesic

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("elevation-api")

app = FastAPI(title="TowerScope Elevation API", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

_RATE_WINDOW_SEC = 60.0
_RATE_MAX = 30
_rate_hits: dict[str, list[float]] = defaultdict(list)


class LatLon(BaseModel):
    lat: float = Field(..., ge=-90, le=90)
    lon: float = Field(..., ge=-180, le=180)


class LosProfileRequest(BaseModel):
    observer: LatLon
    tower: LatLon
    sampleCount: int = Field(50, ge=2, le=200)
    bypassCache: bool = False


class LosSampleOut(BaseModel):
    index: int
    latitude: float
    longitude: float
    distanceMeters: float
    groundElevationMeters: float
    source: Literal["lidar", "dem"]


class LosProfileResponse(BaseModel):
    samples: list[LosSampleOut]
    sampleCount: int
    totalDistanceMeters: float
    lidarCoverageFraction: float
    fromCache: bool = False


def _client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    if request.client:
        return request.client.host
    return "unknown"


def _check_rate(request: Request) -> None:
    ip = _client_ip(request)
    now = time.time()
    hits = _rate_hits[ip]
    _rate_hits[ip] = [t for t in hits if now - t < _RATE_WINDOW_SEC]
    if len(_rate_hits[ip]) >= _RATE_MAX:
        raise HTTPException(status_code=429, detail="Rate limit exceeded")
    _rate_hits[ip].append(now)


def _fill_elevations(raw: list[float | None]) -> list[float | None]:
    if not raw:
        return []
    out = list(raw)
    last: float | None = None
    for i, v in enumerate(out):
        if v is not None:
            last = v
        elif last is not None:
            out[i] = last
    last = None
    for i in range(len(out) - 1, -1, -1):
        v = out[i]
        if v is not None:
            last = v
        elif last is not None:
            out[i] = last
    return out


@app.get("/health")
async def health() -> dict:
    return {
        "ok": True,
        "pdal": lidar.pdal_available(),
    }


@app.post("/v1/los-profile", response_model=LosProfileResponse)
async def los_profile(
    body: LosProfileRequest, request: Request, response: Response
) -> LosProfileResponse:
    _check_rate(request)
    key = cache_key(
        body.observer.lat,
        body.observer.lon,
        body.tower.lat,
        body.tower.lon,
        body.sampleCount,
    )
    if not body.bypassCache:
        cached = cache.get(key)
        if isinstance(cached, dict):
            response.headers["Cache-Control"] = "public, max-age=604800"
            response.headers["X-Cache"] = "HIT"
            data = {**cached, "fromCache": True}
            return LosProfileResponse(**data)

    points = sample_geodesic(
        body.observer.lat,
        body.observer.lon,
        body.tower.lat,
        body.tower.lon,
        body.sampleCount,
    )
    total = haversine_meters(
        body.observer.lat,
        body.observer.lon,
        body.tower.lat,
        body.tower.lon,
    )

    async with httpx.AsyncClient(
        headers={"User-Agent": "TowerScopeElevationAPI/1.0"},
        follow_redirects=True,
    ) as client:
        dem_vals = await dem.elevations_meters(points, client)
        lidar_vals = await lidar.elevations_meters(points, client)

    sources: list[Literal["lidar", "dem"] | None] = [None] * len(points)
    elevs: list[float | None] = [None] * len(points)
    lidar_hits = 0
    for i in range(len(points)):
        z_lidar = lidar_vals[i] if i < len(lidar_vals) else None
        z_dem = dem_vals[i] if i < len(dem_vals) else None
        if z_lidar is not None:
            elevs[i] = z_lidar
            sources[i] = "lidar"
            lidar_hits += 1
        elif z_dem is not None:
            elevs[i] = z_dem
            sources[i] = "dem"

    elevs = _fill_elevations(elevs)
    # Propagate sources with the same forward/back fill pattern.
    last_src: Literal["lidar", "dem"] | None = None
    for i, s in enumerate(sources):
        if s is not None:
            last_src = s
        elif last_src is not None and elevs[i] is not None:
            sources[i] = last_src
    last_src = None
    for i in range(len(sources) - 1, -1, -1):
        s = sources[i]
        if s is not None:
            last_src = s
        elif last_src is not None and elevs[i] is not None:
            sources[i] = last_src

    if all(e is None for e in elevs):
        raise HTTPException(
            status_code=503,
            detail="Elevation unavailable (network or outside coverage)",
        )

    samples: list[LosSampleOut] = []
    for i, point in enumerate(points):
        elev = elevs[i]
        src = sources[i]
        if elev is None or src is None:
            continue
        distance = 0.0 if body.sampleCount <= 1 else total * i / (body.sampleCount - 1)
        samples.append(
            LosSampleOut(
                index=i,
                latitude=point.lat,
                longitude=point.lon,
                distanceMeters=distance,
                groundElevationMeters=elev,
                source=src,
            )
        )

    if len(samples) < 2:
        raise HTTPException(
            status_code=503,
            detail="Elevation unavailable (network or outside coverage)",
        )

    fraction = lidar_hits / float(body.sampleCount)
    payload = LosProfileResponse(
        samples=samples,
        sampleCount=len(samples),
        totalDistanceMeters=total,
        lidarCoverageFraction=fraction,
        fromCache=False,
    )
    to_store = payload.model_dump()
    to_store["fromCache"] = False
    if not body.bypassCache:
        cache.put(key, to_store)
        response.headers["Cache-Control"] = "public, max-age=604800"
    else:
        response.headers["Cache-Control"] = "no-store"
    response.headers["X-Cache"] = "BYPASS" if body.bypassCache else "MISS"
    return payload
