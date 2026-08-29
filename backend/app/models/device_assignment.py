"""DeviceAssignment model -- the binding of a Device (meter tablet) to a
Vehicle, tracked as a row of *history* rather than a single mutable FK, per
plan D-3 ("meter binding as an assignment with history").

Why history instead of a mutable `Device.vehicle_id` pointer: an assignment
needs a start (`bound_at`) and, once superseded, an end (`unbound_at` +
`unbound_reason`) so a full audit trail of which meter was in which car and
when survives across re-pairings, vehicle retirements, and manual admin
corrections -- the same "append rows, don't overwrite state" convention as
`app.models.fleet.DeviceVersionHistory` and `app.models.tariffs.TariffChangeLog`
elsewhere in this codebase. `Device.vehicle_id` (a plain nullable FK) still
exists for the current-pairing-at-a-glance case; this table is the durable
record behind it.

Partial-unique-index precedent note: the task brief pointed at D-1's shift
partial-unique-index as an existing example of the dual-dialect
`postgresql_where`/`sqlite_where` pattern to mirror. As of this pass,
`app.models.shift.Shift` has no DB-level partial unique index -- a repo-wide
grep for `postgresql_where`/`sqlite_where` across `app/models/*.py` turns up
no precedent at all, and D-1's "one open shift per vehicle" rule is enforced
at the application/service layer instead (see `app.services.shift`), not via
a DB constraint. So the syntax below is written from SQLAlchemy first
principles rather than copied:
  - `Index(name, *columns, unique=True, postgresql_where=<expr>)` is
    SQLAlchemy's documented way to emit a Postgres partial unique index
    (`CREATE UNIQUE INDEX ... WHERE <expr>`).
  - `sqlite_where=<expr>` is the SQLite-dialect equivalent kwarg (SQLite has
    supported `WHERE` on `CREATE INDEX` since 3.8.0, and SQLAlchemy exposes it
    the same way as `postgresql_where`) -- needed because this test suite and
    local dev both run against sqlite (see `tests/conftest.py`), so without it
    the partial condition would silently not apply there and every test in
    `tests/test_device_assignment.py` that proves the index is *partial* (a
    third row with the same vehicle_id but `unbound_at` set must NOT collide)
    would fail against sqlite even though the intent is correct.
  - Both kwargs are passed the same `text("unbound_at IS NULL")` clause since
    the condition is identical across dialects; `text()` is required (rather
    than a bound column expression) because `Index` here is declared in
    `__table_args__` using the mapped class's own not-yet-fully-built table.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin


class DeviceAssignment(Base, TimestampMixin, TenantScopedMixin):
    """One row per bind/unbind cycle of a Device to a Vehicle. `unbound_at IS
    NULL` means this assignment is the currently-active one; each partial
    unique index below is a hard DB-level guarantee that at most one row per
    (tenant, vehicle) and per (tenant, device) can ever be active at once --
    the actual create/close logic lives in a sibling work package's service
    layer, not here."""

    __tablename__ = "device_assignments"
    __table_args__ = (
        # One active meter per vehicle (per tenant).
        Index(
            "uq_device_assignments_one_active_per_vehicle",
            "tenant_id",
            "vehicle_id",
            unique=True,
            postgresql_where=text("unbound_at IS NULL"),
            sqlite_where=text("unbound_at IS NULL"),
        ),
        # A meter is in one car at a time (per tenant).
        Index(
            "uq_device_assignments_one_active_per_device",
            "tenant_id",
            "device_id",
            unique=True,
            postgresql_where=text("unbound_at IS NULL"),
            sqlite_where=text("unbound_at IS NULL"),
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    device_id: Mapped[str] = mapped_column(String(36), ForeignKey("devices.id"), nullable=False, index=True)
    vehicle_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("vehicles.id"), nullable=False, index=True
    )
    bound_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # NULL means this assignment is currently active -- see the partial
    # indexes above, which key off exactly this condition.
    unbound_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    bound_by_user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    # Nullable: the primary path is a device QR-pairing a code (see
    # app.models.fleet.DevicePairingCode), but an admin could in principle
    # bind a device to a vehicle directly via PATCH with no code involved.
    pairing_code_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("device_pairing_codes.id"), nullable=True
    )
    # e.g. "re-paired", "vehicle_retired", "manual_admin". Nullable: only set
    # once the assignment is closed.
    unbound_reason: Mapped[str | None] = mapped_column(String(50), nullable=True)