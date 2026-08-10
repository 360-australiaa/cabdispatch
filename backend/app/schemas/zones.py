"""Pydantic v2 schemas for the zones domain -- named dispatch zones, "plot
into a zone", and live per-zone demand stats (see `app.services.zones`)."""
from __future__ import annotations

from datetime import datetime
from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

# --- Pagination (local to this domain, same shape as app.schemas.geofence.Page) --

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- Zone -----------------------------------------------------------------


class ZoneWrite(BaseModel):
    """Shared body shape for `POST /v1/zones` (create) and
    `PUT /v1/zones/{id}` (full replace -- see `app.services.zones.update_zone`
    docstring on why this domain uses PUT rather than the PATCH convention
    every other domain in this tree uses)."""

    name: str = Field(min_length=1, max_length=255)
    number: str = Field(min_length=1, max_length=10, description='Short driver-facing code, e.g. "17".')
    center_lat: float = Field(ge=-90, le=90)
    center_lng: float = Field(ge=-180, le=180)
    radius_m: float = Field(gt=0)


class ZoneCreate(ZoneWrite):
    pass


class ZoneUpdate(ZoneWrite):
    pass


class ZoneRead(ZoneWrite):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    created_at: datetime
    updated_at: datetime


# --- Plot / unplot ----------------------------------------------------------


class ZonePlotRead(BaseModel):
    """Response for `POST /v1/zones/{id}/plot` and `POST /v1/zones/unplot` --
    the calling driver's own current-shift plotting state (a thin projection
    of `app.models.shift.Shift`, not the full `ShiftRead` shape, since the
    caller only needs to know where -- if anywhere -- they're plotted).
    Built by the router from a plain dict (not `from_attributes` off the
    Shift ORM object directly), since `shift_id` renames the ORM's `id`."""

    shift_id: str
    driver_id: str
    vehicle_id: str
    plotted_zone_id: str | None
    plotted_at: datetime | None


# --- Live stats ---------------------------------------------------------------


class ZoneStats(BaseModel):
    """One row of `GET /v1/zones/stats` -- see
    `app.services.zones.compute_zone_stats`'s docstring for the exact
    definition (and documented simplifications) of every field below."""

    zone_id: str
    zone_name: str
    zone_number: str
    plotted_vehicles: int = Field(description="Drivers with an open shift currently plotted into this zone.")
    vacant_vehicles: int = Field(
        description="Vehicles with a known live position inside this zone, not currently on a trip."
    )
    busy_vehicles: int = Field(
        description="Vehicles with a known live position inside this zone, currently on a trip."
    )
    jobs_holding: int = Field(
        description="Open (queued/offered, not-yet-accepted) Job rows whose pickup point falls inside this zone."
    )
    bookings_last_hour: int = Field(
        description="Trip rows of type 'booked' with start_at in the last hour, starting inside this zone."
    )
    street_hails_last_hour: int = Field(
        description="Trip rows of type 'rank_hail' with start_at in the last hour, starting inside this zone."
    )