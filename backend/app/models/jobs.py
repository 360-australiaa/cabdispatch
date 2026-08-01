"""Jobs domain models — the in-house dispatch/job-offer system.

`Job` is a ride request (pickup + destination, fare estimate) that needs a
driver. `JobOffer` is one broadcast of a `Job` to one specific driver, with its
own 20-second accept/decline window (`app.services.jobs.OFFER_WINDOW_SECONDS`).
A single `Job` fans out to *many* `JobOffer` rows at once (one per currently
available driver) — first driver to accept wins, every sibling offer is then
flipped to `expired` (see `app.services.jobs.accept_offer`).

`DriverAvailability` is the minimal new concept this domain adds to answer "is
this driver currently available for a job offer". Per the task brief: a driver
is only offer-eligible when ALL of the following hold —

    1. `DriverAvailability.is_available` is True (a driver-toggled flag; see
       `POST /v1/jobs/availability`) — being on shift does NOT imply willing to
       take dispatch jobs (e.g. a driver working rank/hail only).
    2. They have a currently-open `Shift` (`app.models.shift.Shift.end_at IS
       NULL`) — no shift, no dispatch offers.
    3. They do NOT have a currently-open `Trip` (`app.models.trips.Trip.status
       == 'open'`) — already mid-fare, can't take a new job.

This table is intentionally tiny (one boolean + the usual tenant/timestamp
columns) — it does NOT duplicate or redesign shift/trip state, it only adds
the one bit of state (a driver's own dispatch-availability toggle) that
doesn't already exist anywhere in the `shift`/`user` domains (checked both
before adding this).

DEVIATION (flagged per task instructions, matching the precedent already set
by the sibling `shift`/`trips`/`duress` domains): `driver_id` /
`created_by_user_id` / `accepted_by_driver_id` are plain indexed `String(36)`
columns with NO ForeignKey constraint, even though `users.id` already exists
in the shared foundation. Left unconstrained so `Base.metadata.create_all` /
Alembic autogenerate stay order-independent of tables this domain doesn't own
(this agent must not touch `app/models/__init__.py`, so it cannot guarantee
sibling models are registered on `Base.metadata` when this module is imported
in isolation). Multi-tenant isolation is enforced at the application layer via
`get_current_tenant_id`, not via these FKs. `JobOffer.job_id` DOES FK
`jobs.id` since `Job` is owned by this same domain/module. A later integration
step can add the cross-domain FKs once all domains are registered together.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Numeric, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

# --- Job status enum (plain strings, sqlite/postgres portable) --------------

JOB_STATUS_QUEUED = "queued"
JOB_STATUS_OFFERED = "offered"
JOB_STATUS_ACCEPTED = "accepted"
JOB_STATUS_EXPIRED = "expired"
JOB_STATUS_CANCELLED = "cancelled"
JOB_STATUSES = {
    JOB_STATUS_QUEUED,
    JOB_STATUS_OFFERED,
    JOB_STATUS_ACCEPTED,
    JOB_STATUS_EXPIRED,
    JOB_STATUS_CANCELLED,
}
# Terminal — no further state transitions permitted once here.
JOB_TERMINAL_STATUSES = {JOB_STATUS_ACCEPTED, JOB_STATUS_EXPIRED, JOB_STATUS_CANCELLED}

# --- JobOffer status enum -----------------------------------------------------

OFFER_STATUS_PENDING = "pending"
OFFER_STATUS_ACCEPTED = "accepted"
OFFER_STATUS_DECLINED = "declined"
OFFER_STATUS_EXPIRED = "expired"
OFFER_STATUSES = {
    OFFER_STATUS_PENDING,
    OFFER_STATUS_ACCEPTED,
    OFFER_STATUS_DECLINED,
    OFFER_STATUS_EXPIRED,
}


class Job(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "jobs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # --- origin / destination -------------------------------------------------
    origin_lat: Mapped[float] = mapped_column(Float, nullable=False)
    origin_lng: Mapped[float] = mapped_column(Float, nullable=False)
    origin_address: Mapped[str] = mapped_column(String(500), nullable=False)
    dest_lat: Mapped[float] = mapped_column(Float, nullable=False)
    dest_lng: Mapped[float] = mapped_column(Float, nullable=False)
    dest_address: Mapped[str] = mapped_column(String(500), nullable=False)

    status: Mapped[str] = mapped_column(String(12), nullable=False, default=JOB_STATUS_QUEUED, index=True)

    fare_estimate_low: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)
    fare_estimate_high: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)

    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    # --- assignment (unconstrained cross-domain refs, see module docstring) ---
    created_by_user_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    accepted_by_driver_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)


class JobOffer(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "job_offers"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    job_id: Mapped[str] = mapped_column(String(36), ForeignKey("jobs.id"), nullable=False, index=True)

    # Unconstrained cross-domain ref, see module docstring.
    driver_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    status: Mapped[str] = mapped_column(String(10), nullable=False, default=OFFER_STATUS_PENDING, index=True)
    offered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    responded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class DriverAvailability(Base, TenantScopedMixin, TimestampMixin):
    """One row per (tenant, driver) — the driver's own dispatch-availability
    toggle. See module docstring for the full eligibility rule this feeds
    (available flag AND open shift AND not mid-trip). Toggled via
    `POST /v1/jobs/availability`; there is deliberately no separate read
    endpoint beyond what's echoed back from that call — the matching logic in
    `app.services.jobs` is the only other reader."""

    __tablename__ = "driver_availability"
    __table_args__ = (
        UniqueConstraint("tenant_id", "driver_id", name="uq_driver_availability_tenant_driver"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    # Unconstrained cross-domain ref, see module docstring.
    driver_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    is_available: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
