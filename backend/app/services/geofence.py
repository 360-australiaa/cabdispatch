"""Business logic for the geofences domain: a haversine-distance point-in-
circle containment check, and the `detect_geofences` helper
`app.services.trips.apply_tick` calls on every submitted telemetry point to
auto-detect toll entry (blueprint 5.2.4).

Kept deliberately tiny — no PostGIS, no spatial index, just haversine vs.
`radius_m` over the (small, admin-managed) set of geofence rows. Fine for
"near this landmark" checks; would need revisiting if the geofence table ever
grew large enough for a full table scan per tick to matter.
"""
from __future__ import annotations

from math import asin, cos, radians, sin, sqrt

from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.geofence import Geofence

# Mean earth radius in metres (same constant family as
# app.services.trips.haversine_km's _EARTH_RADIUS_KM, just in metres since
# geofence radii are naturally metre-scale rather than kilometre-scale).
_EARTH_RADIUS_M = 6_371_008.8


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Great-circle distance between two lat/lng points, in metres."""
    phi1, phi2 = radians(lat1), radians(lat2)
    dphi = radians(lat2 - lat1)
    dlambda = radians(lng2 - lng1)
    a = sin(dphi / 2) ** 2 + cos(phi1) * cos(phi2) * sin(dlambda / 2) ** 2
    c = 2 * asin(sqrt(a))
    return _EARTH_RADIUS_M * c


def point_in_geofence(lat: float, lng: float, geofence: Geofence) -> bool:
    """True if (lat, lng) falls on or inside `geofence`'s circle."""
    return haversine_m(lat, lng, geofence.center_lat, geofence.center_lng) <= geofence.radius_m


async def detect_geofences(
    session: AsyncSession,
    *,
    tenant_id: str,
    lat: float,
    lng: float,
    kind: str | None = None,
) -> list[Geofence]:
    """Returns every geofence containing (lat, lng), scoped to rows this
    tenant can see: its own tenant-owned zones PLUS the platform-wide
    (tenant_id IS NULL) reference set — the same visibility rule
    `app.services.tariffs` uses for the global Fares Order reference tariff.
    Pass `kind` (e.g. `GEOFENCE_KIND_TOLL`) to restrict to one geofence kind.
    """
    stmt = select(Geofence).where(or_(Geofence.tenant_id == tenant_id, Geofence.tenant_id.is_(None)))
    if kind is not None:
        stmt = stmt.where(Geofence.kind == kind)

    result = await session.execute(stmt)
    candidates = result.scalars().all()
    return [g for g in candidates if point_in_geofence(lat, lng, g)]
