"""Geodesic sampling (matches Android GeoUtils.sampleGeodesic)."""

from __future__ import annotations

import math
from dataclasses import dataclass

EARTH_RADIUS_METERS = 6_371_000.0


@dataclass(frozen=True)
class LatLng:
    lat: float
    lon: float


def _angular_distance_radians(phi1: float, lam1: float, phi2: float, lam2: float) -> float:
    d_lat = phi2 - phi1
    d_lon = lam2 - lam1
    a = (
        math.sin(d_lat / 2) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(d_lon / 2) ** 2
    )
    return 2 * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1.0 - a)))


def intermediate_point(
    lat1: float, lon1: float, lat2: float, lon2: float, fraction: float
) -> LatLng:
    phi1 = math.radians(lat1)
    lam1 = math.radians(lon1)
    phi2 = math.radians(lat2)
    lam2 = math.radians(lon2)
    d = _angular_distance_radians(phi1, lam1, phi2, lam2)
    if d < 1e-12:
        return LatLng(lat1, lon1)
    a = math.sin((1.0 - fraction) * d) / math.sin(d)
    b = math.sin(fraction * d) / math.sin(d)
    x = a * math.cos(phi1) * math.cos(lam1) + b * math.cos(phi2) * math.cos(lam2)
    y = a * math.cos(phi1) * math.sin(lam1) + b * math.cos(phi2) * math.sin(lam2)
    z = a * math.sin(phi1) + b * math.sin(phi2)
    phi_i = math.atan2(z, math.sqrt(x * x + y * y))
    lam_i = math.atan2(y, x)
    return LatLng(math.degrees(phi_i), math.degrees(lam_i))


def sample_geodesic(
    start_lat: float,
    start_lon: float,
    end_lat: float,
    end_lon: float,
    count: int,
) -> list[LatLng]:
    if count < 2:
        raise ValueError("count must be >= 2")
    return [
        intermediate_point(
            start_lat,
            start_lon,
            end_lat,
            end_lon,
            i / (count - 1),
        )
        for i in range(count)
    ]


def haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    d = _angular_distance_radians(
        math.radians(lat1),
        math.radians(lon1),
        math.radians(lat2),
        math.radians(lon2),
    )
    return EARTH_RADIUS_METERS * d


def cache_key(
    observer_lat: float,
    observer_lon: float,
    tower_lat: float,
    tower_lon: float,
    sample_count: int,
) -> str:
    """~25 m observer cell, ~1 m tower cell."""
    obs_q = 0.00025  # ~28 m
    twr_q = 0.00001  # ~1 m
    olat = round(observer_lat / obs_q) * obs_q
    olon = round(observer_lon / obs_q) * obs_q
    tlat = round(tower_lat / twr_q) * twr_q
    tlon = round(tower_lon / twr_q) * twr_q
    return f"{olat:.5f},{olon:.5f}|{tlat:.5f},{tlon:.5f}|{sample_count}"
