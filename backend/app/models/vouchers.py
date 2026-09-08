"""Voucher / CorporateAccount models -- the real backing tables for the
"voucher" and "account" Trip.payment_method values (blueprint 5.2.5),
replacing the non-empty-string-only stub validation that used to live
entirely in app.services.payments (see that module's updated docstring).

Split into their own file rather than app/models/payment.py deliberately,
mirroring this codebase's existing per-domain model/service split (e.g.
app/models/tariffs.py vs app/services/tariffs.py): Payment (a money-
collection-*attempt* ledger row keyed by trip_id, no FK) is a conceptually
different table from these two -- a Voucher is a promo-code/prepaid balance
row redeemed *into* a trip, and a CorporateAccount is a standing pay-later
reference, not a payment attempt.

Unlike app.models.payment.Payment.trip_id (deliberately unconstrained --
that domain was built in isolation, before the full tree was integrated),
Voucher.redeemed_by_trip_id uses a real `ForeignKey("trips.id")`: this pass
runs against the fully-integrated tree where `trips` already exists on
Base.metadata, so there's no table-creation-order risk.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import Boolean, DateTime, ForeignKey, Numeric, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

_MONEY = Numeric(10, 2)


class Voucher(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "vouchers"
    __table_args__ = (
        UniqueConstraint("tenant_id", "code", name="uq_vouchers_tenant_code"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    code: Mapped[str] = mapped_column(String(50), nullable=False, index=True)
    value_aud: Mapped[Decimal] = mapped_column(_MONEY, nullable=False)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    redeemed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # Real FK (see module docstring) -- trips already exists on Base.metadata
    # by the time this domain is wired in, unlike app.models.payment.Payment's
    # deliberately-unconstrained trip_id.
    redeemed_by_trip_id: Mapped[str | None] = mapped_column(
        String(36), ForeignKey("trips.id"), nullable=True
    )


class CorporateAccount(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "corporate_accounts"
    __table_args__ = (
        UniqueConstraint("tenant_id", "reference", name="uq_corporate_accounts_tenant_reference"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    reference: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    company_name: Mapped[str] = mapped_column(String(255), nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
