"""Payment model — one row per money-collection attempt/record against a trip,
across all supported rails: Stripe Tap to Pay, Stripe payment link, cash, and
manual CabCharge/TTSS docket entry.

`trip_id` is a plain indexed String(36) column with NO ForeignKey constraint,
for the same reason `app/models/trips.py` leaves `vehicle_id`/`driver_id`/etc.
unconstrained: the `trips` domain is owned by a sibling agent, this domain must
not edit `app/models/__init__.py` to wire up cross-domain relationships, and a
hard FK here would make table-creation order depend on a table this domain
doesn't own. Tenant isolation and trip-existence validation both happen at the
application layer via `get_current_tenant_id` / the payments service, not via
DB-level FK enforcement.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import DateTime, Numeric, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

# --- method / status enums (plain strings, sqlite/postgres portable) --------

METHOD_TAP_TO_PAY = "tap_to_pay"
METHOD_LINK = "link"
METHOD_CASH = "cash"
METHOD_CABCHARGE = "cabcharge"
METHOD_TTSS = "ttss"
PAYMENT_METHODS = (METHOD_TAP_TO_PAY, METHOD_LINK, METHOD_CASH, METHOD_CABCHARGE, METHOD_TTSS)

STATUS_PENDING = "pending"
STATUS_REQUIRES_ACTION = "requires_action"
STATUS_SUCCEEDED = "succeeded"
STATUS_FAILED = "failed"
STATUS_REFUNDED = "refunded"
STATUS_CANCELED = "canceled"
PAYMENT_STATUSES = (
    STATUS_PENDING,
    STATUS_REQUIRES_ACTION,
    STATUS_SUCCEEDED,
    STATUS_FAILED,
    STATUS_REFUNDED,
    STATUS_CANCELED,
)


class Payment(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "payments"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    trip_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    stripe_pi_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    amount: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)
    surcharge: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False, default=Decimal("0.00"))
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=STATUS_PENDING)
    captured_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # --- cash-specific (POST /v1/payments/cash) ---
    change_given: Mapped[Decimal | None] = mapped_column(Numeric(10, 2), nullable=True)

    # --- cabcharge/ttss-specific (POST /v1/payments/manual, .../cabcharge/authorize,
    # .../ttss/claim) ---
    docket_number: Mapped[str | None] = mapped_column(String(100), nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)

    # --- ttss-specific (POST /v1/payments/ttss/claim) ---
    # subsidy_amount: TTSS contribution, 50% of the fare capped at $60.00
    # (blueprint 11.1). passenger_paid_amount: the remainder the passenger
    # owes (amount - subsidy_amount). Both NULL for every other method.
    subsidy_amount: Mapped[Decimal | None] = mapped_column(Numeric(10, 2), nullable=True)
    passenger_paid_amount: Mapped[Decimal | None] = mapped_column(Numeric(10, 2), nullable=True)
