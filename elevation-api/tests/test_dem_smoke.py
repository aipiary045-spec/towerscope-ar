"""Async smoke: DEM path for a known CONUS corridor (requires network)."""

import asyncio

import httpx
import pytest

from app import dem
from app.geo import LatLng


@pytest.mark.asyncio
async def test_dem_identify_smoke():
    points = [
        LatLng(35.90, -96.87),
        LatLng(35.899, -96.872),
        LatLng(35.898, -96.874),
    ]
    async with httpx.AsyncClient(
        headers={"User-Agent": "TowerScopeElevationAPI-Test/1.0"},
        follow_redirects=True,
    ) as client:
        vals = await dem.elevations_meters(points, client)
    assert len(vals) == 3
    assert any(v is not None for v in vals)
    for v in vals:
        if v is not None:
            assert 0 < v < 5000
