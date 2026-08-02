"""Pydantic v2 schemas for the geofences domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, model_validator

GeofenceKind = Literal["toll", "region"]


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
    def _toll_amount_required_for_toll_kind(self) -> GeofenceBase:
        if self.kind == "toll" and self.toll_amount is None:
            raise ValueError("toll_amount is required when kind='toll'")
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
