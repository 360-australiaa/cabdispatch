"""Pydantic v2 schemas for the Jobs (dispatch/job-offer) domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, model_validator

JobStatus = Literal["queued", "offered", "accepted", "expired", "cancelled"]
JobOfferStatus = Literal["pending", "accepted", "declined", "expired"]
JobType = Literal["booked", "rank_hail"]


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

    job_type: JobType = "booked"
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
    job_type: JobType
    fare_estimate_low: Decimal
    fare_estimate_high: Decimal
    # Server-computed at creation (haversine + a flat average-speed heuristic,
    # not a routed/live-traffic estimate) -- see
    # app.services.jobs.create_job_and_broadcast. None only for jobs created
    # before this field existed.
    distance_km: Decimal | None = None
    eta_min: int | None = None
    requested_at: datetime
    created_by_user_id: str | None
    accepted_by_driver_id: str | None
    created_at: datetime
    updated_at: datetime


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
