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
    speed_kmh: float | None = Field(
        default=None,
        description="Device-reported ground speed from the last live position publish (see "
        "PositionPublishRequest.speed_kmh below). None if never reported -- unlike battery/"
        "network there is no Device-row fallback for this: it is a property of a specific fix, "
        "not a slowly-changing property of the tablet, so a stale persisted value would be "
        "actively misleading rather than merely lagging.",
    )
    heading: float | None = Field(
        default=None,
        description="Device-reported compass bearing in degrees (0=north) from the last live "
        "position publish. None if never reported. Same no-fallback rationale as speed_kmh above.",
    )
    lat: float | None
    lng: float | None
    live_status: str = Field(
        description=(
            "Best-known live status, in priority order: (1) 'on_trip' if the vehicle has a "
            "currently-open trip -- this ALWAYS wins, since it's a real signal from the trips "
            "domain and a stale/placeholder client-published status must never override it "
            "(see app.services.live_ops._compose_vehicle_live's module-level note on the "
            "2026-09 fix for the bug this priority order corrects); (2) else the last-published "
            "live position's status, if one has ever been published AND it's a real value (not "
            "None, not the literal placeholder 'unknown'); (3) else, if a driver is on shift in "
            "this vehicle, 'available'/'break' from that driver's DriverAvailability.is_available "
            "toggle (jobs domain); (4) else its fleet-domain vehicle_status as a last-resort "
            "fallback."
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
    planned_dest_lat: float | None = Field(
        default=None,
        description="The driver's currently-selected destination for the open trip, if any -- "
        "see app.models.trips.Trip.planned_dest_lat's doc comment for the distinction from the "
        "trip's eventual (real) end_lat/end_lng, only known at close. None if there's no open "
        "trip at all, or there is one but nothing's been picked yet -- never a fabricated 0.",
    )
    planned_dest_lng: float | None = Field(
        default=None,
        description="Longitude counterpart to planned_dest_lat above -- same None semantics.",
    )
    current_driver_id: str | None = Field(
        description="Driver on this vehicle's currently-open Shift, if any -- 'who has this "
        "vehicle checked out right now'. Never a cached pointer: always derived live from the "
        "shift domain, same convention as DriverLiveRead.vehicle_id's own doc comment."
    )
    current_driver_name: str | None = Field(
        description="Display name for current_driver_id, joined in for a dashboard that doesn't "
        "want a second lookup."
    )
    current_shift_id: str | None
    current_shift_start_at: datetime | None = Field(
        description="When the current shift started -- e.g. to show 'Driver X, on since 6:00am' "
        "in a fleet list."
    )


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
    speed_kmh: float | None = Field(
        default=None,
        ge=0,
        le=300,
        description="Optional device-reported ground speed in km/h, on the same periodic "
        "position heartbeat as lat/lng (mirrors app.schemas.duress.DuressGpsPoint.speed_kmh, "
        "the sibling domain's equivalent field on its own GPS-fix schema). 300 is a generous "
        "sanity ceiling, not a decided policy -- see this codebase's convention of flagging "
        "such numbers explicitly (docs/DURESS_DEVICE_INTEGRATION.md sec 8) rather than passing "
        "them off as a business rule. Purely ephemeral, live-cache-only -- unlike battery/network "
        "there is no Device-row persistence for this (see PositionRead.speed_kmh's own doc).",
    )
    heading: float | None = Field(
        default=None,
        ge=0,
        lt=360,
        description="Optional compass bearing in degrees, 0=north. None if the device can't "
        "report one (stationary / no bearing fix) -- a real absent value, not a fabricated 0, "
        "same honest-null convention as this codebase's Android LocationFix and the battery/"
        "network fields above.",
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
    speed_kmh: float | None = Field(
        default=None,
        description="Ground speed in km/h from the publish that produced this position, if the "
        "device reported one -- see PositionPublishRequest.speed_kmh.",
    )
    heading: float | None = Field(
        default=None,
        description="Compass bearing in degrees (0=north) from the publish that produced this "
        "position, if the device reported one -- see PositionPublishRequest.heading.",
    )
    updated_at: datetime


class PositionPublishResponse(PositionRead):
    subscriber_count: int = Field(
        description="Number of currently-connected WS /v1/fleet/live listeners this update reached "
        "(0 is normal -- most publishes happen with no dashboard currently watching)."
    )


# --- durable position history + driving signals ----------------------------------
#
# Unlike everything else in this file, these two schemas ARE backed by a real
# table -- `app.models.fleet.VehiclePositionHistory` (dispatcher-replay pass,
# see that model's own docstring and `app.services.live_ops`'s module
# docstring for the full rationale). They live here rather than in a new
# schemas file for the same "no models file of its own, but real schemas for
# what this domain reads/writes" convention already used by
# `PositionPublishRequest`/`PositionRead` above.


class PositionHistoryPoint(BaseModel):
    """One row of `GET /v1/vehicles/{vehicle_id}/position-history` -- a single
    recorded fix, in the same lat/lng/speed_kmh/heading/status shape as
    `PositionRead` above, plus `recorded_at` (when THIS fix happened, as
    opposed to `PositionRead.updated_at`, which is "when the cache entry was
    last written" -- the same value for a fresh cache read, but a distinct
    concept once history is involved)."""

    lat: float
    lng: float
    speed_kmh: float | None = Field(
        default=None,
        description="Same honest-null convention as PositionRead.speed_kmh -- None if this "
        "particular fix didn't report one, never a fabricated 0.",
    )
    heading: float | None = Field(
        default=None, description="Same honest-null convention as PositionRead.heading."
    )
    status: str
    recorded_at: datetime


class VehiclePositionHistoryRead(BaseModel):
    """Response shape of `GET /v1/vehicles/{vehicle_id}/position-history`:
    the recorded points (oldest first) plus two simple, INFORMATIONAL
    telematics signals derived from them by
    `app.services.live_ops.get_position_history` /`_count_harsh_events`.

    HONESTY NOTE (per this codebase's own convention of never dressing up an
    informal number as a certified one -- see e.g.
    `PositionPublishRequest.speed_kmh`'s "generous sanity ceiling, not a
    decided policy" doc comment): `harsh_brake_events`/`rapid_accel_events`
    are a plain count of consecutive-point speed deltas that cross
    `threshold_kmh_per_s`. This is a commonly-cited fleet-telematics
    rule-of-thumb sensitivity, NOT a certified/legal safety score, and must
    never be presented to a user as one -- it is exactly as reliable as the
    underlying speed_kmh reports, which are optional, device-self-reported,
    and can be sparse or entirely absent for a given vehicle.
    """

    items: list[PositionHistoryPoint]
    harsh_brake_events: int = Field(
        description="Count of consecutive recorded points where speed dropped by more than "
        "threshold_kmh_per_s per second -- an informational telematics signal, NOT a "
        "certified/legal safety score. See this schema's own docstring."
    )
    rapid_accel_events: int = Field(
        description="Same as harsh_brake_events, for speed INCREASES exceeding threshold_kmh_per_s "
        "per second."
    )
    threshold_kmh_per_s: float = Field(
        description="The exact km/h-per-second threshold used to compute harsh_brake_events/"
        "rapid_accel_events above, so a consumer of this response never has to guess or "
        "separately hardcode the value this API actually used -- see "
        "app.services.live_ops.HARSH_EVENT_THRESHOLD_KMH_PER_S's own doc comment for where "
        "this number comes from."
    )