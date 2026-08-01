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
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import JSON, Boolean, DateTime, Integer, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin


class Shift(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "shifts"

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
