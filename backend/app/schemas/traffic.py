"""Pydantic v2 schemas for the read-only `/v1/traffic` domain (live NSW
traffic cameras + hazards) — see `app.models.traffic` for the underlying
tables and `app.services.live_traffic` for where the data actually comes
from."""
from __future__ import annotations

from datetime import datetime
from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict

# --- Pagination (local to this domain, same shape as app.schemas.geofence.Page) ---

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


class TrafficCameraRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    name: str
    latitude: float
    longitude: float
    direction: str | None
    image_url: str
    region: str | None


class TrafficHazardRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    category: str
    latitude: float
    longitude: float
    headline: str | None
    closure_type: str | None
    direction: str | None
    speed_limit: int | None
    expected_delay_minutes: int | None
    ended: bool
    source_last_published: datetime
    raw_json: dict
