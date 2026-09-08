"""Pydantic v2 schemas for the geofences domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, model_validator

GeofenceKind = Literal["toll", "region", "airport"]
# Kinds for which `toll_amount` is the price and therefore mandatory — must
# stay in step with app.models.geofence.GEOFENCE_PRICING_KINDS.
_PRICING_KINDS: frozenset[str] = frozenset({"toll", "airport"})


# --- Pagination (local to this domain, same shape as app.schemas.fleet.Page) ---

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- Geofence -----------------------------------------------------------------


class GeofenceBase(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    kind: GeofenceKind
    center_lat: float = Field(ge=-90, le=90)
    center_lng: float = Field(ge=-180, le=180)
    radius_m: float = Field(gt=0)
    toll_amount: Decimal | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def _toll_amount_required_for_pricing_kinds(self) -> GeofenceBase:
        # For kind="airport", toll_amount IS the airport access fee.
        if self.kind in _PRICING_KINDS and self.toll_amount is None:
            raise ValueError(f"toll_amount is required when kind='{self.kind}'")
        return self


class GeofenceCreate(GeofenceBase):
    pass


class GeofenceUpdate(BaseModel):
    """Partial update — every field optional."""

    name: str | None = Field(default=None, min_length=1, max_length=255)
    kind: GeofenceKind | None = None
    center_lat: float | None = Field(default=None, ge=-90, le=90)
    center_lng: float | None = Field(default=None, ge=-180, le=180)
    radius_m: float | None = Field(default=None, gt=0)
    toll_amount: Decimal | None = Field(default=None, ge=0)


class GeofenceRead(GeofenceBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str | None
    created_at: datetime
    updated_at: datetime


# --- Airport terminal presets (GET /v1/geofences/presets/airport) -------------


class AirportZonePreset(BaseModel):
    """One ready-to-POST airport zone, mirroring
    `app.services.regions.nsw.SYDNEY_AIRPORT_TERMINAL_ZONES` plus the region's
    `airport_access_fee` as `toll_amount`. Same field names as GeofenceCreate
    so the dashboard can POST an item straight back to `POST /v1/geofences`."""

    name: str
    kind: Literal["airport"] = "airport"
    center_lat: float
    center_lng: float
    radius_m: float
    toll_amount: Decimal
