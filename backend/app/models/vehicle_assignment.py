"""VehicleAssignment -- the driver roster for a vehicle: which drivers are
currently authorised to drive a given vehicle (plan Part 4 Phase 2, WP-24
model half). Consumed later by WP-30's `start_shift` validation, which must
check that the driver opening a shift is on the vehicle's active roster.

DESIGN NOTE (column list intentionally not specified by the plan -- judgment
call documented here): this is deliberately a roster/allow-list, NOT a
live-occupancy lock. `app.models.shift.Shift` (see D-1 in the architecture
doc) already owns live occupancy via its own partial unique indexes
(`uq_shifts_one_open_per_vehicle` / `uq_shifts_one_open_per_driver`) --
exactly one open shift per vehicle and per driver at a time. This table
answers a different question: "is this driver *allowed* to drive this
vehicle at all", independent of whether anyone is in it right now. That
makes it many-to-many by design -- e.g. a two-driver 12-hour-shift roster
authorises both Driver A and Driver B on the same vehicle simultaneously
(neither can *open a shift* at the same time as the other -- Shift own
index still enforces that -- but both are legitimately on the allow-list at
once).

Mirrors `app.models.fleet.DevicePairingCode` / the sibling `DeviceAssignment`
(D-3) shape for an authorisation-with-history record: who authorised it, when,
and -- if it has been revoked -- when and why. `revoked_at IS NULL` means
"currently authorised"; a non-null `revoked_at` is a closed/historical
authorisation, never deleted (audit trail).

Constraint choice: a plain unique constraint on
`(tenant_id, vehicle_id, driver_id, revoked_at)` is WRONG here -- two
*simultaneously active* rows would both have `revoked_at IS NULL`, which a
plain unique index treats as NULL != NULL (no conflict) on both SQLite and
Postgres -- i.e. it would silently allow the double-authorisation this table
exists to prevent. Instead: a PARTIAL unique index on
`(tenant_id, vehicle_id, driver_id) WHERE revoked_at IS NULL`, same
dual-dialect `postgresql_where`/`sqlite_where` pattern as D-1's
`uq_shifts_one_open_per_vehicle`/`uq_shifts_one_open_per_driver` (see
`app.models.shift` and the architecture doc D-1/D-3). This lets a driver be
re-authorised on a vehicle any number of times across history (revoke then
re-authorise succeeds), while guaranteeing at most one currently-active
authorisation per (tenant, vehicle, driver) triple at any moment.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin


class VehicleAssignment(Base, TimestampMixin, TenantScopedMixin):
    __tablename__ = "vehicle_assignments"
    __table_args__ = (
        # Partial unique index: at most one currently-active (revoked_at IS NULL)
        # authorisation per (tenant, vehicle, driver) triple. Portable across
        # SQLite and Postgres via the dual postgresql_where/sqlite_where kwargs
        # (same pattern as app.models.shift D-1 partial unique indexes).
        Index(
            "uq_vehicle_assignments_active_driver_vehicle",
            "tenant_id",
            "vehicle_id",
            "driver_id",
            unique=True,
            postgresql_where=text("revoked_at IS NULL"),
            sqlite_where=text("revoked_at IS NULL"),
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    vehicle_id: Mapped[str] = mapped_column(String(36), ForeignKey("vehicles.id"), nullable=False, index=True)
    driver_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False, index=True)

    # Who authorised this driver on this vehicle, and when. Not client-set --
    # authorised_by_user_id comes from the authenticated caller (a dispatcher/
    # admin/owner), authorised_at from the server clock at creation time, same
    # convention as app.models.fleet.DevicePairingCode.used_at being
    # service-set rather than client-supplied.
    authorised_by_user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    authorised_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    # NULL == currently authorised (on the roster). Non-null == revoked/closed,
    # kept as a permanent historical row rather than deleted, same
    # append-only-history convention as D-3 DeviceAssignment.
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    revoked_reason: Mapped[str | None] = mapped_column(String(255), nullable=True)