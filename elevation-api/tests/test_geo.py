"""Smoke tests for elevation-api geo helpers (no network)."""

from app.geo import cache_key, sample_geodesic


def test_sample_geodesic_endpoints():
    pts = sample_geodesic(35.90, -96.87, 35.897, -96.875, 5)
    assert len(pts) == 5
    assert abs(pts[0].lat - 35.90) < 1e-9
    assert abs(pts[-1].lat - 35.897) < 1e-9


def test_cache_key_observer_quantization():
    a = cache_key(35.90001, -96.87001, 35.89700, -96.87500, 50)
    b = cache_key(35.90010, -96.87005, 35.89700, -96.87500, 50)
    assert a == b
    c = cache_key(35.90100, -96.87001, 35.89700, -96.87500, 50)
    assert a != c
