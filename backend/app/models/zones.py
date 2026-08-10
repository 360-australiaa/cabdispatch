"""Zone model — named dispatch zones with a driver-facing short code,
matching the "plot into zone" screen found on a real competitor taxi meter
(MTI): a driver-facing numbered map region (e.g. "1", "17", "23") a driver
plots themselves into while waiting for work, and a dispatcher-facing
live-stats screen keyed by the same zones (see `app/services/zones.py` and
`GET /v1/zones/stats`).

Deliberately CIRCLES, not arbitrary polygons — same rationale as the sibling
`app.models.geofence.Geofence` (no PostGIS in this sqlite dev setup, and a
plain point-in-circle haversine check is all a "is this point inside the
zone" check needs). This model does NOT reuse the `Geofence` table itself:
geofences are an admin-managed toll/region-detection concept consumed by
`app.services.trips.apply_tick`, whereas zones are a dispatch-ops concept
(driver plotting + live per-zone stats) with its own CRUD surface and its own
`number` field geofences have no equivalent of — mixing the two concepts into
one table would conflate two different lifecycles. The point-in-circle
containment check itself IS reused verbatim from
`app.services.geofence.point_in_geofence` (duck-typed on `.center_lat` /
`.center_lng` / `.radius_m` — see `app/services/zones.py`), per the task
instruction not to invent a second geometry representation.

Unlike `Geofence`, `tenant_id` here is NOT nullable — zones are always a
tenant's own dispatch zones (mirroring the plain `TenantScopedMixin` pattern
every other domain in this tree uses); there is no platform-wide reference
zone concept analogous to geofences' seeded toll landmarks.
"""
from __future__ import annotations

import uuid

from sqlalchemy import String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin


class Zone(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "zones"
    __table_args__ = (
        # A driver reading "17" off the app needs that to mean exactly one
        # zone within their own tenant — mirrors the real competitor meter's
        # numbered-zone convention. Not globally unique (two tenants can both
        # have a zone "1").
        UniqueConstraint("tenant_id", "number", name="uq_zones_tenant_number"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    # Short driver-facing code, e.g. "1", "17", "23" — deliberately a string,
    # not an int: real competitor zone boards mix numeric and short
    # alphanumeric codes (e.g. "17A") and this is never arithmetic'd on.
    number: Mapped[str] = mapped_column(String(10), nullable=False, index=True)

    # --- circle definition (see module docstring) ---
    center_lat: Mapped[float] = mapped_column(nullable=False)
    center_lng: Mapped[float] = mapped_column(nullable=False)
    radius_m: Mapped[float] = mapped_column(nullable=False)
