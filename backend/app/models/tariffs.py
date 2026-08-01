"""Tariff domain models: `Tariff` (a versioned rate card), `Extra` (fixed/
passthrough surcharges attached to a tariff), and `TariffChangeLog` (an
append-only audit trail written automatically whenever a tariff is created or
updated).

Multi-tenancy note: `Tariff.tenant_id` (and the `tenant_id` denormalized onto
`Extra`/`TariffChangeLog` for direct query filtering) is NULLABLE — unlike the
standard `TenantScopedMixin` pattern — because exactly one row per region
(tenant_id IS NULL) is the platform-wide NSW Fares Order reference tariff that
every tenant's rank/hail tariffs are validated against. That global row is
seeded by a later integration step, not created through the normal tenant-
scoped API in this file. Every other tariff/extra/change-log row created via
`app/api/v1/tariffs.py` always carries a real tenant_id (taken from
`get_current_tenant_id`) — the nullable column exists solely for that one
global reference row.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Numeric, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TimestampMixin

# region values — "exempt" tariffs (e.g. wedding cars, special-purpose hire) sit
# outside NSW Fares Order jurisdiction entirely and are never rate-capped.
REGION_URBAN = "urban"
REGION_COUNTRY = "country"
REGION_EXEMPT = "exempt"
VALID_REGIONS = (REGION_URBAN, REGION_COUNTRY, REGION_EXEMPT)

EXTRA_TYPE_FIXED = "fixed"
EXTRA_TYPE_PASSTHROUGH = "passthrough"
VALID_EXTRA_TYPES = (EXTRA_TYPE_FIXED, EXTRA_TYPE_PASSTHROUGH)

# Decimal column precision: 4 dp comfortably covers every rate in the fare
# engine contract (e.g. waiting_rate_per_min = 1.092) without ever needing
# float. Money-only columns (Extra.amount) use 2 dp.
_RATE = Numeric(10, 4)
_MONEY = Numeric(10, 2)


class Tariff(Base, TimestampMixin):
    __tablename__ = "tariffs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    # Nullable: see module docstring — NULL marks the single global Fares Order
    # reference row per region, seeded outside the normal tenant-scoped API.
    tenant_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=True, index=True
    )

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    region: Mapped[str] = mapped_column(String(20), nullable=False, index=True)  # urban|country|exempt
    effective_from: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    effective_to: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # booked=True => unregulated (private-hire/booked-only tariff), skips Fares
    # Order rate-cap validation entirely (see services.tariffs / fare_engine).
    booked: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    # --- every rate field on the fare_engine.Tariff dataclass, exactly -------
    flag_fall: Mapped[Decimal] = mapped_column(_RATE, nullable=False)
    peak_charge: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal(0))
    dist_rate_1: Mapped[Decimal] = mapped_column(_RATE, nullable=False)
    dist_rate_2: Mapped[Decimal] = mapped_column(_RATE, nullable=False)
    night_rate_1: Mapped[Decimal] = mapped_column(_RATE, nullable=False)
    night_rate_2: Mapped[Decimal] = mapped_column(_RATE, nullable=False)
    holiday_rate_1: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal(0))
    holiday_rate_2: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal(0))
    waiting_rate_per_min: Mapped[Decimal] = mapped_column(_RATE, nullable=False)
    dist_km_threshold: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal(12))
    speed_threshold_kmh: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal(26))
    maxi_multiplier: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal("1.5"))
    multi_hire_pct: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal("0.75"))
    psl_amount: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal("1.32"))
    surcharge_pct_cap: Mapped[Decimal] = mapped_column(_RATE, nullable=False, default=Decimal("5.0"))


class Extra(Base, TimestampMixin):
    __tablename__ = "tariff_extras"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    tariff_id: Mapped[str] = mapped_column(String(36), ForeignKey("tariffs.id"), nullable=False, index=True)
    # Denormalized from the parent tariff at creation time so this table can be
    # filtered by tenant_id directly (the sole multi-tenancy mechanism) without
    # a join. Nullable for the same reason as Tariff.tenant_id above.
    tenant_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=True, index=True
    )

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    amount: Mapped[Decimal] = mapped_column(_MONEY, nullable=False)
    type: Mapped[str] = mapped_column(String(20), nullable=False)  # fixed|passthrough


class TariffChangeLog(Base):
    """Append-only audit trail. Written automatically by the service layer on
    every tariff create/update — never exposed for direct write via the API
    (create+list/get only, no update/delete endpoints)."""

    __tablename__ = "tariff_change_log"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    tariff_id: Mapped[str] = mapped_column(String(36), ForeignKey("tariffs.id"), nullable=False, index=True)
    # Denormalized from the tariff for direct tenant_id filtering, as on Extra.
    tenant_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=True, index=True
    )
    actor_user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    before_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)  # None on the initial create
    after_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
