"""3DEP Bare-Earth DEM via ImageServer multipoint identify."""

from __future__ import annotations

import json
import logging
import re
from typing import Sequence

import httpx

from .geo import LatLng

logger = logging.getLogger(__name__)

IMAGE_SERVER = (
    "https://elevation.nationalmap.gov/arcgis/rest/services/"
    "3DEPElevation/ImageServer/identify"
)
MOSAIC_RULE = {
    "ascending": True,
    "mosaicMethod": "esriMosaicAttribute",
    "sortField": "Best",
}
VALUE_RE = re.compile(
    r'"value"\s*:\s*"?(?P<num>[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)"?'
)
# ArcGIS identify multipoint can struggle with very large payloads; chunk.
CHUNK_SIZE = 40


def _parse_values(body: str, expected: int) -> list[float | None]:
    try:
        data = json.loads(body)
    except json.JSONDecodeError:
        return [None] * expected
    results = data.get("results")
    if not isinstance(results, list):
        # Single-point identify returns value at top level.
        match = VALUE_RE.search(body)
        if match and expected == 1:
            try:
                return [float(match.group("num"))]
            except ValueError:
                return [None]
        return [None] * expected
    out: list[float | None] = []
    for item in results:
        raw = item.get("value") if isinstance(item, dict) else None
        if raw is None or raw == "NoData" or raw == "":
            out.append(None)
            continue
        try:
            out.append(float(raw))
        except (TypeError, ValueError):
            out.append(None)
    while len(out) < expected:
        out.append(None)
    return out[:expected]


async def elevations_meters(
    points: Sequence[LatLng],
    client: httpx.AsyncClient,
) -> list[float | None]:
    if not points:
        return []
    out: list[float | None] = []
    for start in range(0, len(points), CHUNK_SIZE):
        chunk = points[start : start + CHUNK_SIZE]
        geometry = {
            "points": [[p.lon, p.lat] for p in chunk],
            "spatialReference": {"wkid": 4326},
        }
        params = {
            "geometry": json.dumps(geometry, separators=(",", ":")),
            "geometryType": "esriGeometryMultipoint",
            "mosaicRule": json.dumps(MOSAIC_RULE, separators=(",", ":")),
            "returnGeometry": "false",
            "returnCatalogItems": "false",
            "f": "json",
        }
        try:
            resp = await client.get(IMAGE_SERVER, params=params, timeout=30.0)
            resp.raise_for_status()
            out.extend(_parse_values(resp.text, len(chunk)))
        except Exception as exc:  # noqa: BLE001
            logger.warning("DEM identify failed for chunk: %s", exc)
            out.extend([None] * len(chunk))
    return out
