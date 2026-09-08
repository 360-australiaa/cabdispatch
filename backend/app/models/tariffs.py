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
    cleaning_fee_cap: Mapped[Decimal] = mapped_column(
        _RATE, nullable=False, default=Decimal("124.14")
    )

    # --- jurisdiction seam (X1) — per-tariff overrides of the region's
    # default night window / peak-eligible weekdays. NULL means "use the
    # tenant's FareRegion default" (app.services.regions.FareRegion.
    # night_start_hour/night_end_hour/peak_weekdays) rather than a
    # fabricated NSW value — see app.services.tariffs for the resolution
    # order. Every existing row is NULL after the accompanying migration,
    # so today's NSW behaviour (region default = NSW's own 22/6/{Fri,Sat})
    # is unchanged.
    night_start_hour: Mapped[int | None] = mapped_column(nullable=True)
    night_end_hour: Mapped[int | None] = mapped_column(nullable=True)
    # Comma-separated Python date.weekday() ints (e.g. "4,5" for Fri/Sat) —
    # a plain scalar column rather than a JSON array to match this table's
    # existing convention of simple column types only (see _RATE/_MONEY
    # above); parsed by app.services.tariffs when present.
    peak_weekdays: Mapped[str | None] = mapped_column(String(20), nullable=True)


class Extra(Base, TimestampMixin):
    __tablename__ = "tariff_extras"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    # ondelete="CASCADE" (found auditing every NOT NULL FK in this codebase
    # per the same task brief as the fleet vehicle/device delete fix — see
    # app.services.fleet's module docstring): an Extra is a line item
    # ("cleaning fee", "airport surcharge") that only exists in the context
    # of its parent tariff — deleting the tariff should take its extras with
    # it, same reasoning as the fleet domain's derived/ephemeral rows.
    tariff_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("tariffs.id", ondelete="CASCADE"), nullable=False, index=True
    )
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
    # ondelete="CASCADE" (a real, separate production bug -- not the one
    # reported live, but the identical class of it, surfaced by turning on
    # sqlite FK enforcement for this whole pass and running the existing
    # test suite against it, see app.core.database): `write_change_log`
    # unconditionally appends a row on every create INCLUDING the initial
    # one — so, unlike the audit/financial evidence app.services.user.
    # assert_user_deletable refuses to let through, every tariff structurally
    # has one of these from the moment it exists, meaning "refuse instead"
    # would make the delete endpoint permanently non-functional rather than
    # just usually blocked. This row's change history is meaningful only in
    # the context of a tariff that still exists (nothing reads change-log
    # rows for a tariff_id that isn't there) — cascading it away with its
    # tariff is the same "derived data, not independent evidence" reasoning
    # as the fleet domain's history tables.
    tariff_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("tariffs.id", ondelete="CASCADE"), nullable=False, index=True
    )
    # Denormalized from the tariff for direct tenant_id filtering, as on Extra.
    tenant_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("tenants.id"), nullable=True, index=True
    )
    # No ondelete= here (defaults to RESTRICT/NO ACTION) -- DELIBERATELY, in
    # contrast to tariff_id right above: this is WHO changed a fare rate,
    # not derived data -- fare-regulation accountability that must survive
    # that staff member's own account deletion. See
    # app.services.user.assert_user_deletable, which already refuses to
    # delete a user referenced here with a clean 409 rather than letting the
    # FK violation reach the database raw.
    actor_user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), nullable=False)
    before_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)  # None on the initial create
    after_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
