"""PSL (Passenger Service Levy) ledger — per-driver, per-period accrual/collection
tracking, plus an append-only top-up transaction log.

Two tables:
  - `psl_ledger`: one row per (tenant, driver, period) aggregate — `trips_count`
    and `amount_owed` accrue from trip-level PSL charges (see `tariffs.psl_amount`
    in the trips domain, out of scope here), `amount_collected` is the running
    total collected via top-ups (and/or at-trip settlement, out of scope here),
    and `remitted_at` is set once the tenant has remitted the period's levy to
    TCT. Full CRUD — an admin may need to correct a ledger row (e.g. a
    reconciliation adjustment) or delete a row created in error.
  - `psl_topups`: append-only log of individual top-up transactions (simulated
    or real Stripe debits of a driver's card, see app.services.psl_ledger).
    Create + list only, no update/delete — an audit/money-movement trail is
    meaningless if past entries can be silently edited or removed.
  - `psl_trip_accruals`: one row per CLOSED trip whose levy has been accrued
    into a `psl_ledger` row — the per-trip idempotency key that makes
    "accrue on trip close" safe to replay (a synced trip is retried by the
    meter's outbox as a matter of course). Before this table existed nothing
    wrote to `psl_ledger` on close at all: the production PSL Centre showed 0
    ledger rows against 140 closed trips each carrying the $1.32 levy. See
    `app.services.psl_ledger.accrue_trip_psl` / `rebuild_ledger`.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import DateTime, ForeignKey, Integer, Numeric, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

TOPUP_STATUS_SUCCEEDED = "succeeded"
TOPUP_STATUS_FAILED = "failed"


class PSLLedgerEntry(Base, TimestampMixin, TenantScopedMixin):
    __tablename__ = "psl_ledger"
    __table_args__ = (
        UniqueConstraint("tenant_id", "driver_id", "period", name="uq_psl_ledger_tenant_driver_period"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    driver_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False, index=True)
    period: Mapped[str] = mapped_column(String(7), nullable=False, index=True)  # "YYYY-MM"
    trips_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    amount_owed: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal("0.00"))
    amount_collected: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal("0.00"))
    remitted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class PSLTripAccrual(Base, TimestampMixin, TenantScopedMixin):
    """The levy of ONE closed trip, as accrued into its driver's ledger row.

    `trip_id` is unique: a trip is accrued exactly once no matter how many
    times its close or sync is replayed (see `accrue_trip_psl`). `driver_id`/
    `period`/`amount` are copied from the trip at accrual time so a rebuild
    can tell whether a ledger row still agrees with the trips beneath it.
    No FK on `trip_id`/`driver_id` — same unconstrained-cross-domain-ref
    precedent as `app.models.trips.Trip`, and so a force-wiped driver
    (app.services.fleet_wipe) is never blocked by this table.
    """

    __tablename__ = "psl_trip_accruals"
    __table_args__ = (UniqueConstraint("trip_id", name="uq_psl_trip_accruals_trip_id"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    trip_id: Mapped[str] = mapped_column(String(36), nullable=False)
    driver_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    period: Mapped[str] = mapped_column(String(7), nullable=False, index=True)  # "YYYY-MM"
    amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)


class PSLTopUp(Base, TimestampMixin, TenantScopedMixin):
    """Append-only: create + list only, no update/delete endpoints (see router)."""

    __tablename__ = "psl_topups"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    driver_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False, index=True)
    period: Mapped[str] = mapped_column(String(7), nullable=False, index=True)  # "YYYY-MM"
    amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)
    payment_method: Mapped[str] = mapped_column(String(20), nullable=False, default="card")
    stripe_charge_id: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=TOPUP_STATUS_SUCCEEDED)
