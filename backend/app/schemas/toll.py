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
    location: str
    ramp: str | None
    direction: str | None
    latitude: float
    longitude: float


class TollRoadPriceRevisionRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    price_class_a_min: Decimal | None
    price_class_a_max: Decimal | None
    price_class_b_min: Decimal | None
    price_class_b_max: Decimal | None
    cap_class_a: Decimal | None
    cap_class_b: Decimal | None
    time_of_day_rates_class_a: list[dict] | None
    currency: str
    gst_included: bool
    effective_date: date
    indexation: str
    confidence: str
    verify_note: str | None


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
    directional: str | None
    description: str | None
    derived_corridor_km: Decimal | None
    source_note: str | None
    gantry_count: int
    current_price: TollRoadPriceRevisionRead | None


class TollRoadDetailRead(TollRoadRead):
    gantries: list[TollGantryRead]
    price_history: list[TollRoadPriceRevisionRead]
