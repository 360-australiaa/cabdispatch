"""Pydantic v2 schemas for the fatigue-alerts domain (blueprint 12.3)."""
from __future__ import annotations

from datetime import datetime
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict

FatigueAlertKind = Literal[
    "shift_duration_exceeded",
    "no_break_taken",
    "speed_exceeded",
    "license_expiring_soon",
    "license_expired",
    "authority_expiring_soon",
    "authority_expired",
    "registration_expiring_soon",
    "registration_expired",
    "insurance_expiring_soon",
    "insurance_expired",
]

# --- Pagination (local to this domain, same shape as app.schemas.fleet.Page,
# until a shared one exists in app.core) -----------------------------------------

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


class FatigueAlertRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    # Nullable since the compliance-expiry pass (blueprint 7.2.3/7.2.4/10.1):
    # vehicle-facing kinds (registration/insurance) carry vehicle_id instead
    # of driver_id — see app.models.fatigue_alert's class-level DEVIATION note.
    driver_id: str | None
    vehicle_id: str | None = None
    shift_id: str | None
    kind: FatigueAlertKind
    triggered_at: datetime
    details_json: dict | None
    acknowledged: bool
    created_at: datetime
    updated_at: datetime


class FatigueAlertAcknowledge(BaseModel):
    acknowledged: bool = True
