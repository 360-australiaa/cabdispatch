"""Pydantic v2 schemas for the read-mostly `/v1/toll-roads` domain — the real
NSW toll-road registry (`app.models.toll`). See that module's docstring for
why pricing is a separate, dated `TollRoadPriceRevision` list rather than
columns on the road itself.
"""
from __future__ import annotations

from datetime import date
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field


class TollGantryRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    toll_road_id: str
    # Non-NULL only on a `pricing_model == "per_point"` road (M2/CCT/LCT) —
    # says WHICH named toll point this physical gantry belongs to, and so
    # which per-point price it charges. NULL on every other road, which
    # prices at the road level instead. See `app.models.toll.TollGantry`.
    toll_point_id: str | None = None
    location: str
    ramp: str | None
    direction: str | None
    latitude: float
    longitude: float
    # REAL position/distance along this gantry's own road (2026-09-09
    # cross-check pass) — see `app.models.toll.TollGantry`'s docstring. Both
    # NULL for every road whose real chain data doesn't resolve cleanly (the
    # majority today — see `scripts/seed_toll_roads.py`'s
    # `_REAL_ROAD_CHAIN_WAYPOINTS`), which still means exactly what it always
    # has: "no real order known for this road", not an error. Exposed here
    # (a tiny addition — `from_attributes=True` reads it straight off the
    # ORM row, no route logic changed) so the on-device registry sync this
    # endpoint already feeds can eventually read real chain order instead of
    # reconstructing a nearest-neighbour guess client-side — see Android's
    # `KnownCorridor.kt`, which explicitly flags that reconstruction as its
    # own "honest limitation" for exactly this reason. Not consumed there
    # yet; this pass only makes the data available on the existing response.
    sequence_position: int | None = None
    cumulative_distance_km: Decimal | None = None


class TollRoadPriceRevisionRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    price_class_a_min: Decimal | None
    price_class_a_max: Decimal | None
    price_class_b_min: Decimal | None
    price_class_b_max: Decimal | None
    cap_class_a: Decimal | None
    cap_class_b: Decimal | None
    # Formula inputs for the "distance"/"distance_with_flagfall" models, and
    # the WestConnex network-wide cap. All nullable: a revision captured
    # before the 2026-09-07 correction pass has none of them, which is
    # honest rather than a data loss. See `app.models.toll`.
    rate_per_km_class_a: Decimal | None = None
    rate_per_km_class_b: Decimal | None = None
    flagfall_class_a: Decimal | None = None
    flagfall_class_b: Decimal | None = None
    network_cap_class_a: Decimal | None = None
    network_cap_class_b: Decimal | None = None
    time_of_day_rates_class_a: list[dict] | None
    currency: str
    gst_included: bool
    effective_date: date
    indexation: str
    confidence: str
    verify_note: str | None
    # Provenance. On a fare-regulated meter every published figure must be
    # traceable back to the page it was read off and the day it was read —
    # surfaced to the dashboard so an operator defending a disputed toll can
    # open the source rather than take the number on trust.
    source_url: str | None = None
    retrieved_at: date | None = None


class TollPointPriceRevisionRead(BaseModel):
    """One dated price for one named toll point of a `per_point` road — the
    same static-identity/dated-revision split `TollRoadPriceRevisionRead`
    uses, one level down."""

    model_config = ConfigDict(from_attributes=True)

    id: str
    price_class_a: Decimal | None
    price_class_b: Decimal | None
    currency: str
    gst_included: bool
    effective_date: date
    indexation: str
    confidence: str
    verify_note: str | None
    source_url: str | None = None
    retrieved_at: date | None = None


class TollPointRead(BaseModel):
    """A named toll point of a `pricing_model == "per_point"` road. Empty for
    every other road. `current_price` is resolved exactly like a road's is
    (highest effective_date not in the future) — see
    `app.services.tolls.current_toll_point_price_revision`."""

    id: str
    toll_road_id: str
    name: str
    description: str | None
    source_note: str | None
    gantry_count: int
    current_price: TollPointPriceRevisionRead | None


class TollRoadPriceRevisionCreate(BaseModel):
    """Payload for `POST /v1/toll-roads/{id}/price-revisions` — platform-owner
    only (same pricing-is-platform-admin-only rule `app/api/v1/geofences.py`
    already applies to toll geofences and `app/api/v1/tariffs.py` applies to
    the Fares Order reference). INSERTS a new dated revision; never mutates
    an existing one — see `app.models.toll.TollRoadPriceRevision`'s docstring
    for why."""

    price_class_a_min: Decimal | None = Field(default=None, ge=0)
    price_class_a_max: Decimal | None = Field(default=None, ge=0)
    price_class_b_min: Decimal | None = Field(default=None, ge=0)
    price_class_b_max: Decimal | None = Field(default=None, ge=0)
    cap_class_a: Decimal | None = Field(default=None, ge=0)
    cap_class_b: Decimal | None = Field(default=None, ge=0)
    time_of_day_rates_class_a: list[dict] | None = None
    currency: str = "AUD"
    gst_included: bool = True
    effective_date: date
    indexation: str = Field(min_length=1, max_length=32)
    confidence: str = Field(min_length=1, max_length=32)
    verify_note: str | None = None


class TollRoadRead(BaseModel):
    id: str
    api_code: str | None
    name: str
    operator: str | None
    pricing_model: str
    # How gantry crossings become a charge — "once_per_road",
    # "cumulative_per_point" (M2 only), or "distance_metered". Not derivable
    # from `pricing_model`: M2 and LCT are both "per_point" yet charge
    # differently. See `app.models.toll.TOLL_CHARGING_POLICIES`.
    charging_policy: str
    # Roads sharing one network-wide cap for a single trip (today only
    # "WESTCONNEX"). NULL for every road without one.
    network_group: str | None
    directional: str | None
    description: str | None
    derived_corridor_km: Decimal | None
    source_note: str | None
    gantry_count: int
    current_price: TollRoadPriceRevisionRead | None
    # Populated only for a "per_point" road. Returned on the LIST endpoint
    # too, not just the detail one: without it the dashboard's road list has
    # no real price to show for M2/CCT/LCT at all (their road-level min/max
    # is a descriptive range, never what a trip is charged), and the meter's
    # own registry sync would cache a road it then cannot price.
    toll_points: list[TollPointRead] = []


class TollRoadDetailRead(TollRoadRead):
    gantries: list[TollGantryRead]
    price_history: list[TollRoadPriceRevisionRead]
