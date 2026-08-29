"""Shift model — a driver's on-duty period in a specific vehicle.

Covers the pre-shift vehicle inspection checklist (captured as JSON at
`POST /v1/shifts/start`) through to end-of-shift reconciliation (cash vs card
takings, PSL owed) at `POST /v1/shifts/{id}/end`. `trips_count`, `km_total`,
`cash_total`, `card_total` are NOT accepted from the client on close — they are
recomputed server-side by aggregating the shift's own trips (see
`app.services.shift`), so they can't be spoofed by a compromised/offline device.

DEVIATION (flagged per task instructions): `driver_id` and `vehicle_id` are
plain indexed `String(36)` columns with NO ForeignKey constraint, matching the
precedent already set by the sibling `trips` domain (`app/models/trips.py`).
`driver_id` *could* validly FK `users.id` (that table exists in the shared
foundation), but `vehicle_id` cannot yet — the `vehicles` table belongs to a
sibling domain not present in this agent's slice of the tree. Leaving both
unconstrained keeps `Base.metadata.create_all` / Alembic autogenerate
order-independent of tables this domain doesn't own, and multi-tenant
isolation is already enforced at the application layer via
`get_current_tenant_id`, not via these FKs. A later integration step can add
the FKs once all 12 domains are registered together.

DEVIATION (zones/live-demand-stats pass, added on top of the original shift
domain, matching a screen on a real competitor taxi meter -- MTI): the task
brief for that pass asked for "a driver marks themselves as actively waiting
in a specific zone (distinct from just being on shift)" and specified
checking this model first for the right place to add it rather than a new
table. `plotted_zone_id` / `plotted_at` are added here rather than as a new
table because a "currently plotted" driver is inherently scoped to their
current *open* shift (per `app.services.live_ops._open_shifts_by_driver`'s
existing "the open shift IS the driver's current session" convention) -- a
driver with no open shift has nothing to be plotted against. Both columns are
nullable: "not currently plotted" is the default/common state, distinct
from "on shift". `plotted_zone_id` is a plain unconstrained `String(36)`
ref to `app.models.zones.Zone.id`, for the same "no FK across domains built
independently" reason as `driver_id`/`vehicle_id` above -- enforced instead
at the application layer by `app.services.zones.plot_into_zone` looking the
zone up scoped to the caller's own tenant_id before setting it. Managed
exclusively via `POST /v1/zones/{id}/plot` and `POST /v1/zones/unplot` (see
`app.services.zones`); not exposed on `ShiftCreate`/`ShiftUpdate`.

DEVIATION (break-tracking pass, added on top of the original shift domain, to
wire the previously-unwired `no_break_taken` fatigue alert -- see the
comment on `app.models.fatigue_alert.FATIGUE_ALERT_NO_BREAK_TAKEN` and
`app.services.fatigue.check_no_break_taken`): `break_started_at` and
`break_taken` are added here, following the exact same nullable-extra-column
precedent as `plotted_zone_id`/`plotted_at` above, rather than a new table.
Deliberately kept as ONE break slot per shift (not a full break-history log
of every break taken during the shift) -- the only question the fatigue
check needs answered is whether ANY break was taken this shift, not
when/how many/how long; a full history table would be more correct for a
driver-facing break log feature but is unneeded complexity for this alert.
`break_started_at` is nullable and cleared back to `None` when the break
ends -- no break currently in progress is the default/common state.
`break_taken` is a plain non-nullable `Boolean` defaulting to `False` that
latches permanently to `True` the moment a break is ended (it does not reset
until this shift itself ends; a new `Shift` row starts fresh with
`break_taken=False` by default). Managed exclusively via
`POST /v1/shifts/{id}/break/start` and `POST /v1/shifts/{id}/break/end`
(see `app.services.shift.start_break` and `app.services.shift.end_break`);
not exposed on `ShiftCreate`/`ShiftUpdate`.

DEVIATION (WP-33, plan Part 4 Phase 3, on top of the Stage 1/Stage 2 work
above): `odometer_start` / `odometer_end` and `end_inspection_json` are
added here. `odometer_start` is captured at shift open -- either supplied
directly by the client on `POST /v1/shifts/start` (`ShiftStart.odometer_start`)
or, for a shift opened via a handover (`app.services.shift_handover.
perform_handover`), copied automatically from the outgoing shift's
`odometer_end` reading (this was the Stage 2 "STAGE 3 NOTE" pointer on
`app.models.shift_handover.ShiftHandover`, now wired). `odometer_end` mirrors
`inspection_json`'s existing pre-shift convention but for shift CLOSE: a
plain client-supplied Integer accepted on `POST /v1/shifts/{id}/end`
(`ShiftEnd.odometer_end`), not recomputed/derived server-side. Both are
nullable Integer -- "not captured" is the default/common state, e.g. for
tenants that don't track odometer readings at all, or where a client sends
neither field. `end_inspection_json` is the end-of-shift counterpart to the
existing pre-shift `inspection_json` column: same freeform-JSON,
shape-owned-by-the-client convention, nullable, accepted optionally on
`POST /v1/shifts/{id}/end` (`ShiftEnd.end_inspection_json`).
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import JSON, Boolean, DateTime, Index, Integer, Numeric, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin


class Shift(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "shifts"
    __table_args__ = (
        # D-1 (architecture plan): at most one currently-open (end_at IS NULL)
        # shift per vehicle at a time -- this is the actual mechanism that
        # makes double-booking a vehicle impossible by construction. Portable
        # across SQLite and Postgres via the dual postgresql_where/sqlite_where
        # kwargs, same pattern as app.models.device_assignment /
        # app.models.vehicle_assignment.
        Index(
            "uq_shifts_one_open_per_vehicle",
            "tenant_id",
            "vehicle_id",
            unique=True,
            postgresql_where=text("end_at IS NULL"),
            sqlite_where=text("end_at IS NULL"),
        ),
        # D-1: at most one currently-open shift per driver at a time -- a
        # driver cannot be simultaneously "on shift" in two vehicles.
        Index(
            "uq_shifts_one_open_per_driver",
            "tenant_id",
            "driver_id",
            unique=True,
            postgresql_where=text("end_at IS NULL"),
            sqlite_where=text("end_at IS NULL"),
        ),
    )

    id: Mapped[str] = mapped_column(
        String(36), primary_key=True, default=lambda: str(uuid.uuid4())
    )

    # --- assignment (unconstrained cross-domain refs, see module docstring) ---
    driver_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)
    vehicle_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    start_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    end_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # Pre-shift vehicle inspection checklist, freeform (tyres/lights/meter seal/
    # camera/first-aid etc.) — shape owned by the mobile app, not enforced here.
    inspection_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)

    # --- Aggregates, recomputed server-side at shift end (never client-supplied) ---
    trips_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    km_total: Mapped[Decimal] = mapped_column(Numeric(10, 3), nullable=False, default=Decimal(0))
    cash_total: Mapped[Decimal] = mapped_column(
        Numeric(10, 2), nullable=False, default=Decimal(0)
    )
    card_total: Mapped[Decimal] = mapped_column(
        Numeric(10, 2), nullable=False, default=Decimal(0)
    )

    # --- Reconciliation figures, accepted from the client at shift end ---
    psl_owed: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal(0))
    reconciled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    # --- zone plotting (see class docstring DEVIATION note) ---
    plotted_zone_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    plotted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # --- break tracking (see class docstring DEVIATION note) ---
    break_started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    break_taken: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    # --- odometer + end-of-shift inspection (WP-33, see class docstring
    # DEVIATION note) ---
    odometer_start: Mapped[int | None] = mapped_column(Integer, nullable=True)
    odometer_end: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # End-of-shift vehicle inspection checklist, freeform -- mirrors
    # `inspection_json` above but captured at shift CLOSE, not open.
    end_inspection_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
