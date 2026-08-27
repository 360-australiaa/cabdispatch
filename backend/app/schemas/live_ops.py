"""Pydantic v2 schemas for the Live Ops domain.

This domain has no ORM models of its own (see `app.services.live_ops`'s module
docstring) -- every Read schema here is built from a plain dict composed by
joining sibling domains' tables (`app.models.fleet.Vehicle`/`Device`,
`app.models.trips.Trip`, `app.models.shift.Shift`, `app.models.user.User`)
with the in-process live-position cache. None of these Read schemas has a
matching Create/Update pair tied to a table this domain owns -- see
`app.api.v1.live_ops`'s module docstring for the full CRUD-shape rationale.
"""
from __future__ import annotations

from datetime import datetime
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, Field

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    """Pagination envelope -- same shape as the sibling `fleet`/`tariffs`
    domains' local `Page`, until a shared one exists in `app.core`."""

    items: list[T]
    total: int
    skip: int
    limit: int


PositionSource = Literal["live", "trip", "none"]


# --- vehicles -------------------------------------------------------------------


class VehicleLiveRead(BaseModel):
    """One row of `GET /v1/vehicles` -- a Vehicle (owned by the fleet domain)
    with its latest known position/status joined in from the live-position
    cache, its paired Device (if any), and its currently-open Trip (if any).
    Read-only: there is no create/update/delete here, this domain does not own
    the `vehicles` table."""

    id: str
    tenant_id: str
    rego: str
    vehicle_class: str
    vehicle_status: str = Field(
        description="Vehicle.status from the fleet domain (active/maintenance/suspended/retired)"
    )
    device_id: str | None
    device_last_seen_at: datetime | None
    battery: int | None = Field(
        default=None,
        description="0-100 tablet battery pct -- the paired device's most recent report, from "
        "whichever arrived last: a live position publish carrying it (see "
        "PositionPublishRequest.battery below), or a plain "
        "POST /v1/fleet/devices/{id}/heartbeat. None if never reported.",
    )
    network: str | None = Field(
        default=None,
        description="e.g. \"wifi\"/\"4g\"/\"offline\" -- same source/freshness rule as battery above.",
    )
    lat: float | None
    lng: float | None
    live_status: str = Field(
        description=(
            "Best-known live status: the last-published position's status if one has ever "
            "been published for this vehicle, else 'on_trip' if it has a currently-open trip, "
            "else its fleet-domain vehicle_status as a fallback."
        )
    )
    position_updated_at: datetime | None = Field(
        description="Timestamp of the position lat/lng above -- either a live publish or a trip's last tick."
    )
    position_source: PositionSource = Field(
        description="'live' = from a WS/POST position publish; 'trip' = fallback to the open "
        "trip's last tick; 'none' = no position known at all."
    )
    current_trip_id: str | None


# --- drivers --------------------------------------------------------------------


class DriverLiveRead(BaseModel):
    """One row of `GET /v1/drivers` -- a driver (`User` with role="driver",
    owned by the user domain) with on-shift status joined in from the shift
    domain. Read-only, same rationale as `VehicleLiveRead`."""

    id: str
    tenant_id: str
    name: str
    phone: str | None
    user_status: str = Field(description="User.status from the user domain")
    on_shift: bool
    shift_id: str | None
    vehicle_id: str | None = Field(description="Vehicle currently assigned via the open shift, if on_shift.")
    shift_start_at: datetime | None
    current_trip_id: str | None


# --- live positions ---------------------------------------------------------------


class PositionPublishRequest(BaseModel):
    """Body for `POST /v1/fleet/positions` -- a device/tick handler's position
    report for one vehicle. Not persisted to the database (see
    `app.services.live_ops` module docstring); it updates the in-memory
    latest-position cache and is fanned out live to every `WS /v1/fleet/live`
    subscriber for the caller's tenant."""

    vehicle_id: str
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    status: str = Field(
        min_length=1, max_length=20, description="e.g. \"available\", \"on_trip\", \"offline\", \"break\""
    )
    battery: int | None = Field(
        default=None,
        ge=0,
        le=100,
        description="Optional tablet battery pct, reported on the same call as position so a "
        "single periodic heartbeat covers both -- best-effort persisted onto the paired Device "
        "row too (see app.services.live_ops.publish_position), so it survives a broadcaster "
        "restart and shows up on GET /v1/fleet/devices, not just the live feed.",
    )
    network: str | None = Field(
        default=None,
        max_length=20,
        description="Optional connectivity type, e.g. \"wifi\"/\"4g\"/\"offline\" -- same "
        "best-effort persist-onto-Device convention as battery above.",
    )


class PositionRead(BaseModel):
    """Shape of one cached/broadcast position -- this is also exactly the JSON
    message shape sent over `WS /v1/fleet/live`."""

    vehicle_id: str
    lat: float
    lng: float
    status: str
    battery: int | None = None
    network: str | None = None
    updated_at: datetime


class PositionPublishResponse(PositionRead):
    subscriber_count: int = Field(
        description="Number of currently-connected WS /v1/fleet/live listeners this update reached "
        "(0 is normal -- most publishes happen with no dashboard currently watching)."
    )