"""Simple TTL response cache."""

from __future__ import annotations

from typing import Any

from cachetools import TTLCache

# ~7 days
_CACHE = TTLCache(maxsize=512, ttl=7 * 24 * 3600)


def get(key: str) -> Any | None:
    return _CACHE.get(key)


def put(key: str, value: Any) -> None:
    _CACHE[key] = value
