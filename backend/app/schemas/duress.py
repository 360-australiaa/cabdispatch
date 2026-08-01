"""Pydantic v2 schemas for the Duress domain."""
from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

DuressTrigger = Literal["button", "gesture", "voice", "auto"]
DuressStatus = Literal["open", "escalating", "dispatched", "resolved", "cancelled"]


class DuressTriggerRequest(BaseModel):
    """Body for `POST /v1/duress/trigger` — opens a new duress event."""

    vehicle_id: str
    driver_id: str
    trigger: DuressTrigger
    gps_stream_ref: str | None = Field(
        default=None,
        description="Reference to the live GPS trace for this event. If omitted, "
        "a default `duress/{event_id}/gps` reference is generated.",
    )
    audio_ref: str | None = Field(default=None, description="Captured audio reference, if any.")


class DuressCancelRequest(BaseModel):
    """Body for `POST /v1/duress/{id}/cancel`. No fields required — reserved for
    an optional operator note."""

    note: str | None = Field(default=None, max_length=500)


class DuressEscalateRequest(BaseModel):
    """Body for `POST /v1/duress/{id}/escalate`. Each call advances exactly one
    stage of the fixed cascade (see `app.models.duress.ESCALATION_STAGES`)."""

    note: str | None = Field(default=None, max_length=500)


class DuressCloseRequest(BaseModel):
    """Body for `POST /v1/duress/{id}/close`."""

    note: str | None = Field(default=None, max_length=500)


class DuressGpsPoint(BaseModel):
    """Body for `POST /v1/duress/{id}/gps` — a single GPS fix, broadcast
    verbatim (plus a server timestamp) to every connected `/live` websocket
    listener for this event. Not persisted — see `app.services.duress.GPSBroadcaster`."""

    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    speed_kmh: float | None = Field(default=None, ge=0)
    accuracy_m: float | None = Field(default=None, ge=0)
    ts: datetime | None = Field(
        default=None, description="Device-reported fix time. Defaults to server time."
    )


class DuressEventCreate(BaseModel):
    """Generic create — accepted for CRUD completeness/admin backfill. The
    normal path for opening an event is `POST /v1/duress/trigger` above."""

    vehicle_id: str
    driver_id: str
    trigger: DuressTrigger
    status: DuressStatus = "open"
    opened_at: datetime | None = None
    closed_at: datetime | None = None
    gps_stream_ref: str
    audio_ref: str | None = None
    escalation_log_json: dict | None = None


class DuressEventUpdate(BaseModel):
    """Partial update — every field optional. Intended for admin corrections
    (e.g. fixing a mis-keyed reference), NOT for driving the state machine —
    use `/cancel`, `/escalate`, `/close` for that."""

    vehicle_id: str | None = None
    driver_id: str | None = None
    gps_stream_ref: str | None = None
    audio_ref: str | None = None


class DuressEventRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    vehicle_id: str
    driver_id: str
    trigger: str
    status: str
    opened_at: datetime
    closed_at: datetime | None
    gps_stream_ref: str
    audio_ref: str | None
    escalation_log_json: dict
    created_at: datetime
    updated_at: datetime


class DuressEventListResponse(BaseModel):
    items: list[DuressEventRead]
    total: int
    limit: int
    offset: int
