"""Pydantic v2 schemas for the Jobs (dispatch/job-offer) domain."""
from __future__ import annotations

from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.services.trips import haversine_km

JobStatus = Literal["queued", "offered", "accepted", "expired", "cancelled"]
JobOfferStatus = Literal["pending", "accepted", "declined", "expired"]


# --- Pagination (local to this domain, same shape as the sibling `fleet`/
# `live_ops` domains' local `Page`, until a shared one exists in app.core) ------

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- Job ----------------------------------------------------------------------


class JobCreate(BaseModel):
    """Body for `POST /v1/jobs` — a new ride request. `status`, `requested_at`,
    `created_by_user_id` are all server-assigned, never client-supplied."""

    origin_lat: float = Field(ge=-90, le=90)
    origin_lng: float = Field(ge=-180, le=180)
    origin_address: str = Field(min_length=1, max_length=500)
    dest_lat: float = Field(ge=-90, le=90)
    dest_lng: float = Field(ge=-180, le=180)
    dest_address: str = Field(min_length=1, max_length=500)
    fare_estimate_low: Decimal = Field(ge=0)
    fare_estimate_high: Decimal = Field(ge=0)

    @model_validator(mode="after")
    def _check_fare_range(self) -> "JobCreate":
        if self.fare_estimate_high < self.fare_estimate_low:
            raise ValueError("fare_estimate_high must be >= fare_estimate_low")
        return self


class JobRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    origin_lat: float
    origin_lng: float
    origin_address: str
    dest_lat: float
    dest_lng: float
    dest_address: str
    status: JobStatus
    fare_estimate_low: Decimal
    fare_estimate_high: Decimal
    requested_at: datetime
    created_by_user_id: str | None
    accepted_by_driver_id: str | None
    created_at: datetime
    updated_at: datetime
    # Real gap closed (2026-09-05 API-audit pass): Android's JobDto already
    # declared/read `distance_km`, but no backend field ever backed it.
    # Straight-line (haversine) origin -> destination distance, computed here
    # from this same response's own origin/dest lat/lng — never a stored
    # column, never a routed/live-traffic distance. Same approximation
    # app.services.trips.haversine_km already uses elsewhere in this
    # codebase (e.g. toll-geofence detection), so this is an existing,
    # already-trusted approximation, not a new one invented for this field.
    # Deliberately real and always-present (never null) — no `eta_min`
    # alongside it, and no `job_type` on this schema at all: unlike a
    # straight-line distance between two known points, a genuine
    # arrival-time estimate needs a real routing/traffic service this
    # codebase does not have, and this domain's `Job` model has no per-record
    # "type" classification stored to expose (every row here is, by this
    # domain's own construction, a dispatch/broadcast job) — both are
    # deliberately left unbuilt rather than fabricated. See this pass's
    # report for the full reasoning.
    distance_km: Decimal | None = None

    @model_validator(mode="after")
    def _derive_distance_km(self) -> "JobRead":
        raw = haversine_km(self.origin_lat, self.origin_lng, self.dest_lat, self.dest_lng)
        self.distance_km = raw.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        return self


# --- JobOffer -------------------------------------------------------------------


class JobOfferRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    job_id: str
    tenant_id: str
    driver_id: str
    status: JobOfferStatus
    offered_at: datetime
    expires_at: datetime
    responded_at: datetime | None


class JobOfferPushEvent(BaseModel):
    """Shape of the JSON message pushed over `WS /v1/jobs/live` when a job
    offer is created for the connected driver — a `job_offer` event carrying
    both the offer and enough of the job to render a driver-app offer card
    without a follow-up request."""

    type: Literal["job_offer"] = "job_offer"
    offer: JobOfferRead
    job: JobRead


# --- Driver availability toggle (see app.models.jobs.DriverAvailability) -------


class DriverAvailabilityUpdate(BaseModel):
    """Body for `POST /v1/jobs/availability` — a driver's own self-toggle."""

    is_available: bool


class DriverAvailabilityRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    tenant_id: str
    driver_id: str
    is_available: bool
    updated_at: datetime
