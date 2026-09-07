"""NSW toll-road reference data — real per-road pricing rules and the 141
physical toll gantries that GPS-detect a trip's crossings, replacing the old
`Geofence(kind="toll")` flat-circle-with-one-amount model for the 13 roads in
the product owner's authoritative NSW toll dataset (see
`app/data/nsw_toll_roads.json` / `app/data/nsw_toll_gantries.csv`, and
`scripts/seed_toll_roads.py` which loads them).

Why this replaces the old model (see that module's docstring for what it
could not express): a real NSW toll road is priced by one of several distinct
models (flat / zone_flat / distance / distance_with_flagfall / time_of_day),
is sometimes directional (one-way, or northbound/southbound-only), and is
detected by MANY physical gantries that must all charge the SAME road only
ONCE per trip — a single circle-with-one-amount cannot represent any of that.

Two tables, split for a specific reason:

- `TollRoad` — static identity (id, operator, pricing model, directionality).
  NEVER carries a price column directly.
- `TollRoadPriceRevision` — dated pricing snapshots, one-to-many per road.
  Real NSW tolls reindex QUARTERLY (WestConnex annually) — see
  `app/data/nsw_toll_roads.json`'s own `notes`. Splitting pricing out like
  this means a quarterly reindexation INSERTS a new revision instead of
  UPDATE-ing the old one away, so a disputed historical trip can always be
  checked against the price that was actually in force on the day it
  happened (fare evidence — see `app.services.tolls.current_price_revision`),
  not today's price. `TollRoad.current_revision` (the highest `effective_date`
  not in the future) is what `app.services.tolls` actually prices against.

`TollGantry` — one row per physical tolling point (141 total, real NSW
Transport coordinates). `direction` is populated only where the SOURCE data
recorded a per-gantry carriageway direction (roughly a third of them — see
`scripts/seed_toll_roads.py`); NULL otherwise, in which case a directional
road's direction check falls back to the trip's own GPS bearing (see
`app.services.tolls.classify_bearing`).

Both tables are platform-wide reference data with NO tenant_id column at
all — unlike `app.models.geofence.Geofence` (which supports tenant-owned ad
hoc zones alongside a nullable-tenant_id global reference set), a toll ROAD
is never tenant-specific in the first place, so there is no ownership
dimension to model. Every tenant's trips price against the exact same NSW
toll registry. Writes (price revisions) are platform-owner-only — see
`app/api/v1/toll_roads.py`.
"""
from __future__ import annotations

import uuid
from datetime import date
from decimal import Decimal

from sqlalchemy import JSON, Date, ForeignKey, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base, TimestampMixin

_MONEY = Numeric(10, 2)

# Real pricing models present in the authoritative dataset, plus "unpriced"
# (this codebase's own label, not the source data's) for a road with either
# no captured price at all (confidence="not_captured": M4/M8/M5E/
# M4M5_ROZELLE) or a genuinely ambiguous zone_flat gantry (see
# app.services.tolls's zone_flat handling for why that's deliberately never
# auto-charged a guessed number).
TOLL_PRICING_MODELS = {
    "flat",
    "zone_flat",
    "distance",
    "distance_with_flagfall",
    "time_of_day",
    "unpriced",
}

# Real `directional` values from the source dataset.
TOLL_DIRECTIONS = {
    "both",
    "one_way",
    "northbound_only",
    "southbound_only",
}

# Real `confidence` values from the source dataset.
TOLL_CONFIDENCE_LEVELS = {"verified", "needs_verification", "not_captured"}


class TollRoad(Base, TimestampMixin):
    __tablename__ = "toll_roads"

    # Natural key -- the source dataset's own `id` (e.g. "M7", "SHB_SHT"),
    # not a synthetic UUID, so it can be embedded directly in
    # Trip.auto_tolled_roads / Trip.unpriced_toll_road_ids without a lookup.
    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    api_code: Mapped[str | None] = mapped_column(String(16), nullable=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    # Nullable: the one road in this table NOT from the 13-road authoritative
    # dataset (the "M12" stub -- see scripts/seed_toll_roads.py's module
    # docstring) has no known operator/directionality in the source data at
    # all, and NULL here is the honest way to say "not captured", rather than
    # defaulting to a guessed value like "both".
    operator: Mapped[str | None] = mapped_column(String(255), nullable=True)
    pricing_model: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    directional: Mapped[str | None] = mapped_column(String(32), nullable=True)
    description: Mapped[str | None] = mapped_column(String(1000), nullable=True)

    # Real-data DERIVATION (not an invented figure) used only by the
    # "distance" pricing model (currently M7 alone) to back out an effective
    # $/km rate from the road's published Class A cap -- see
    # app.services.tolls.distance_rate_per_km_class_a's docstring for exactly
    # how this is computed (max pairwise haversine distance across the
    # road's own real gantry coordinates) and why that's honest, not guessed.
    derived_corridor_km: Mapped[Decimal | None] = mapped_column(Numeric(8, 3), nullable=True)

    # Free-text provenance note carried through from the raw dataset (or, for
    # the one road that isn't in it -- see M12 below -- explaining why not).
    source_note: Mapped[str | None] = mapped_column(String(2000), nullable=True)

    gantries: Mapped[list[TollGantry]] = relationship(
        back_populates="road", cascade="all, delete-orphan"
    )
    price_revisions: Mapped[list[TollRoadPriceRevision]] = relationship(
        back_populates="road",
        cascade="all, delete-orphan",
        order_by="TollRoadPriceRevision.effective_date",
    )


class TollRoadPriceRevision(Base, TimestampMixin):
    """A dated pricing snapshot for one `TollRoad` -- see that model's
    docstring for why prices are versioned here rather than columns on
    TollRoad itself. Every field below (other than `id`/`toll_road_id`) is
    copied verbatim from `app/data/nsw_toll_roads.json` for that road -- see
    `scripts/seed_toll_roads.py` -- `confidence`/`verify_note` in particular
    are NOT decoration: a `needs_verification` or `not_captured` row must
    stay visibly flagged as such all the way to the dashboard, since this is
    fare evidence for a legally rate-capped taxi meter.
    """

    __tablename__ = "toll_road_price_revisions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    toll_road_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("toll_roads.id"), nullable=False, index=True
    )

    price_class_a_min: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    price_class_a_max: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    price_class_b_min: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    price_class_b_max: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    cap_class_a: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)
    cap_class_b: Mapped[Decimal | None] = mapped_column(_MONEY, nullable=True)

    # time_of_day pricing model only (SHB_SHT today) -- a JSON list of
    # {"band": "peak"|"off_peak"|"night", "price": "4.55", "windows": "..."}
    # objects, Class A only (what a taxi pays) -- copied verbatim from the
    # source dataset's own `time_of_day_rates_class_a`.
    time_of_day_rates_class_a: Mapped[list | None] = mapped_column(JSON, nullable=True)

    currency: Mapped[str] = mapped_column(String(3), nullable=False, default="AUD")
    gst_included: Mapped[bool] = mapped_column(nullable=False, default=True)
    effective_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    indexation: Mapped[str] = mapped_column(String(32), nullable=False)
    confidence: Mapped[str] = mapped_column(String(32), nullable=False)
    verify_note: Mapped[str | None] = mapped_column(String(2000), nullable=True)

    road: Mapped[TollRoad] = relationship(back_populates="price_revisions")


class TollGantry(Base, TimestampMixin):
    __tablename__ = "toll_gantries"

    # Natural key -- the source dataset's own `gantry_id` string (already
    # globally unique across all 141 rows).
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    toll_road_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("toll_roads.id"), nullable=False, index=True
    )

    location: Mapped[str] = mapped_column(String(255), nullable=False)
    ramp: Mapped[str | None] = mapped_column(String(16), nullable=True)  # entry|exit|NULL
    # north/south/east/westbound, where the source data records one (see
    # module docstring) -- NULL otherwise.
    direction: Mapped[str | None] = mapped_column(String(16), nullable=True)
    latitude: Mapped[float] = mapped_column(nullable=False)
    longitude: Mapped[float] = mapped_column(nullable=False)
    source_sheet: Mapped[str | None] = mapped_column(String(32), nullable=True)

    road: Mapped[TollRoad] = relationship(back_populates="gantries")
