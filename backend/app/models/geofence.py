"""Geofence model — simple circular zones used for GPS-based toll
auto-detection (blueprint 5.2.4), the Sydney Airport ground-transport access
fee (`kind="airport"`, charged once when a hiring STARTS inside the zone —
see `app.services.trips.apply_airport_access_fee_at_start`) and, later,
tariff-zone auto-selection (blueprint 7.2.5's "Region Map" — `kind="region"`
rows are reserved for that future use and are not yet consumed by any
service).

Kinds:
  * `toll`    — charged on ENTRY, mid-trip, by `apply_tick` (a toll gantry).
  * `airport` — charged ONCE at trip creation when the pickup point is inside
                the zone (the NSW Fares Order airport access fee). NEVER
                charged by the tick path: driving into the airport to drop a
                passenger off is not a pickup.
  * `region`  — no pricing; reserved.

The `kind` column is `String(10)`: "airport" (7 chars) fits without a
migration.

Deliberately CIRCLES, not arbitrary polygons: there is no PostGIS available in
this sqlite dev setup, and a plain point-in-circle (haversine distance vs.
`radius_m`) check is all a "near this landmark" toll check needs — see
`app/services/geofence.py`.

Multi-tenancy note: `tenant_id` is NULLABLE, mirroring the exact pattern
`app/models/tariffs.py` already uses for its global Fares Order reference row
— NULL marks a platform-wide reference geofence (the seeded Sydney toll
landmarks in `scripts/seed.py`), visible to every tenant but only writable
outside the normal tenant-scoped CRUD API. Every geofence created via
`app/api/v1/geofences.py` carries a real tenant_id (from
`get_current_tenant_id`); the nullable column exists solely for the global
reference rows.
"""
from __future__ import annotations

import uuid
from decimal import Decimal

from sqlalchemy import ForeignKey, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin

GEOFENCE_KIND_TOLL = "toll"
GEOFENCE_KIND_REGION = "region"
GEOFENCE_KIND_AIRPORT = "airport"
GEOFENCE_KINDS = {GEOFENCE_KIND_TOLL, GEOFENCE_KIND_REGION, GEOFENCE_KIND_AIRPORT}
# Kinds whose rows carry pricing (`toll_amount` is REQUIRED) and are therefore
# platform-owner-only to write — see app/api/v1/geofences.py.
GEOFENCE_PRICING_KINDS = {GEOFENCE_KIND_TOLL, GEOFENCE_KIND_AIRPORT}

_MONEY = Numeric(10, 2)


class Geofence(Base, TimestampMixin):
    __tablename__ = "geofences"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    # Nullable — see module docstring. NULL == platform-wide reference geofence.
    tenant_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=True, index=True
    )

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    kind: Mapped[str] = mapped_column(String(10), nullable=False, index=True)  # toll|region|airport

    # --- circle definition (see module docstring on why circles, not polygons) --
    center_lat: Mapped[float] = mapped_column(nullable=False)
    center_lng: Mapped[float] = mapped_column(nullable=False)
    radius_m: Mapped[float] = mapped_column(nullable=False)

    # Only meaningful for the pricing kinds: kind == "toll" (the amount
    # auto-added to a trip's `tolls` running total on entry, see
    # app.services.trips.apply_tick) and kind == "airport" (the airport
    # access fee added once at trip creation, see
    # app.services.trips.apply_airport_access_fee_at_start). Left NULL for
    # kind == "region" rows.
    toll_amount: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
