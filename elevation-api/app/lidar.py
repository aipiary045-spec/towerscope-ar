"""USGS 3DEP LiDAR first-return sampling via EPT + PDAL (optional)."""

from __future__ import annotations

import json
import logging
import math
import shutil
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

import httpx

from .geo import LatLng

logger = logging.getLogger(__name__)

try:
    from shapely.geometry import Point, shape
    from shapely.strtree import STRtree

    _HAS_SHAPELY = True
except ImportError:  # pragma: no cover - DEM-only local installs
    Point = None  # type: ignore[misc,assignment]
    shape = None  # type: ignore[misc,assignment]
    STRtree = None  # type: ignore[misc,assignment]
    _HAS_SHAPELY = False

RESOURCES_URL = (
    "https://raw.githubusercontent.com/hobu/usgs-lidar/master/"
    "boundaries/resources.geojson"
)
EPT_RESOLUTION_METERS = 2.0
SAMPLE_RADIUS_METERS = 2.0
RESOURCES_TTL_SEC = 24 * 3600
_WEB_MERCATOR_MAX = 20037508.342789244


def lonlat_to_web_mercator(lon: float, lat: float) -> tuple[float, float]:
    """WGS84 → EPSG:3857 meters (pure Python; no PROJ dependency)."""
    lat = max(min(lat, 85.05112878), -85.05112878)
    x = lon * _WEB_MERCATOR_MAX / 180.0
    y = math.log(math.tan(math.pi / 4.0 + math.radians(lat) / 2.0)) * (
        _WEB_MERCATOR_MAX / math.pi
    )
    return x, y


@dataclass
class _Resource:
    name: str
    url: str
    geometry: Any  # shapely geom


class LidarIndex:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._resources: list[_Resource] = []
        self._tree: Any = None
        self._geoms: list[Any] = []
        self._loaded_at = 0.0

    async def ensure_loaded(self, client: httpx.AsyncClient) -> None:
        if not _HAS_SHAPELY:
            return
        with self._lock:
            fresh = self._resources and (time.time() - self._loaded_at) < RESOURCES_TTL_SEC
            if fresh:
                return
        try:
            resp = await client.get(RESOURCES_URL, timeout=60.0)
            resp.raise_for_status()
            data = resp.json()
        except Exception as exc:  # noqa: BLE001
            logger.warning("Failed to load LiDAR resource index: %s", exc)
            return
        resources: list[_Resource] = []
        for feature in data.get("features", []):
            props = feature.get("properties") or {}
            name = props.get("name")
            url = props.get("url")
            geom_json = feature.get("geometry")
            if not name or not url or not geom_json:
                continue
            try:
                geom = shape(geom_json)
            except Exception:  # noqa: BLE001
                continue
            if geom.is_empty:
                continue
            resources.append(_Resource(name=str(name), url=str(url), geometry=geom))
        geoms = [r.geometry for r in resources]
        tree = STRtree(geoms) if geoms else None
        with self._lock:
            self._resources = resources
            self._geoms = geoms
            self._tree = tree
            self._loaded_at = time.time()
        logger.info("Loaded %d LiDAR EPT resources", len(resources))

    def covering(self, points: Sequence[LatLng]) -> list[_Resource]:
        if not _HAS_SHAPELY:
            return []
        with self._lock:
            if not self._tree or not self._resources:
                return []
            tree = self._tree
            geoms = self._geoms
            resources = self._resources
        hits: dict[str, _Resource] = {}
        for p in points:
            pt = Point(p.lon, p.lat)
            for idx in tree.query(pt):
                i = int(idx)
                geom = geoms[i]
                if geom.covers(pt) or geom.intersects(pt.buffer(0.00005)):
                    r = resources[i]
                    hits[r.name] = r
        return list(hits.values())


_index = LidarIndex()
_pdal_path = shutil.which("pdal")


def pdal_available() -> bool:
    return _pdal_path is not None and _HAS_SHAPELY


def _buffer_bounds_3857(
    points: Sequence[LatLng], radius_m: float
) -> tuple[float, float, float, float]:
    xs: list[float] = []
    ys: list[float] = []
    for p in points:
        x, y = lonlat_to_web_mercator(p.lon, p.lat)
        xs.append(x)
        ys.append(y)
    return (
        min(xs) - radius_m,
        min(ys) - radius_m,
        max(xs) + radius_m,
        max(ys) + radius_m,
    )


def _run_pdal_first_returns(
    ept_url: str, bounds: tuple[float, float, float, float]
) -> list[tuple[float, float, float]]:
    """Return list of (x3857, y3857, z) first-return points."""
    if not _pdal_path:
        return []
    xmin, ymin, xmax, ymax = bounds
    with tempfile.TemporaryDirectory() as tmp:
        out_path = Path(tmp) / "out.csv"
        pipe_path = Path(tmp) / "pipe.json"
        pipeline = {
            "pipeline": [
                {
                    "type": "readers.ept",
                    "filename": ept_url,
                    "bounds": f"([{xmin}, {xmax}], [{ymin}, {ymax}])",
                    "resolution": EPT_RESOLUTION_METERS,
                },
                {
                    "type": "filters.range",
                    "limits": "ReturnNumber[1:1]",
                },
                {
                    "type": "writers.text",
                    "filename": str(out_path),
                    "format": "csv",
                    "order": "X,Y,Z",
                    "keep_unspecified": False,
                    "write_header": True,
                },
            ]
        }
        pipe_path.write_text(json.dumps(pipeline), encoding="utf-8")
        try:
            subprocess.run(
                [_pdal_path, "pipeline", str(pipe_path)],
                check=True,
                capture_output=True,
                text=True,
                timeout=120,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            err = getattr(exc, "stderr", "") or str(exc)
            logger.warning("PDAL EPT read failed for %s: %s", ept_url, err[:500])
            return []
        if not out_path.exists():
            return []
        text = out_path.read_text(encoding="utf-8", errors="ignore").strip()
        points: list[tuple[float, float, float]] = []
        lines = text.splitlines()
        start = 1 if lines and lines[0].lower().startswith("x") else 0
        for line in lines[start:]:
            parts = [p.strip() for p in line.replace(";", ",").split(",")]
            if len(parts) < 3:
                continue
            try:
                points.append((float(parts[0]), float(parts[1]), float(parts[2])))
            except ValueError:
                continue
        return points


def _max_z_near(
    cloud: Sequence[tuple[float, float, float]],
    x: float,
    y: float,
    radius_m: float,
) -> float | None:
    r2 = radius_m * radius_m
    best: float | None = None
    for cx, cy, cz in cloud:
        dx = cx - x
        dy = cy - y
        if dx * dx + dy * dy <= r2:
            if best is None or cz > best:
                best = cz
    return best


async def elevations_meters(
    points: Sequence[LatLng],
    client: httpx.AsyncClient,
) -> list[float | None]:
    """First-return max Z (meters) per sample; None where LiDAR unavailable."""
    if not points or not pdal_available():
        return [None] * len(points)

    await _index.ensure_loaded(client)
    resources = _index.covering(points)
    if not resources:
        return [None] * len(points)

    out: list[float | None] = [None] * len(points)
    bounds = _buffer_bounds_3857(points, SAMPLE_RADIUS_METERS * 4)
    width = bounds[2] - bounds[0]
    height = bounds[3] - bounds[1]
    if width * height > (25_000 * 25_000):
        logger.info("LiDAR corridor too large (%.0f x %.0f m); skipping", width, height)
        return out

    clouds: list[tuple[float, float, float]] = []
    for resource in resources[:3]:
        cloud = _run_pdal_first_returns(resource.url, bounds)
        if cloud:
            clouds.extend(cloud)
            logger.info("LiDAR %s: %d first-return points", resource.name, len(cloud))

    if not clouds:
        return out

    for i, p in enumerate(points):
        x, y = lonlat_to_web_mercator(p.lon, p.lat)
        z = _max_z_near(clouds, x, y, SAMPLE_RADIUS_METERS)
        if z is not None and math.isfinite(z):
            out[i] = float(z)
    return out
