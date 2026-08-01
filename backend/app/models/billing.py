"""Billing domain model: `Subscription` — one row per (tenant, vehicle) Stripe
Billing subscription for the taxi-meter SaaS product.

`vehicle_id` is a plain indexed String(36) column with NO ForeignKey constraint,
for the same reason `app/models/payment.py`'s `trip_id` is unconstrained: the
`fleet` domain (which owns `Vehicle`) is built by a sibling agent, this domain
must not edit `app/models/__init__.py` to wire up cross-domain relationships,
and a hard FK here would make table-creation order depend on a table this
domain doesn't own. Tenant isolation and vehicle-existence validation both
happen at the application layer via `get_current_tenant_id` / the billing
service, not via DB-level FK enforcement.
"""
from __future__ import annotations

import uuid
from decimal import Decimal

from sqlalchemy import Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from app.core.database import Base, TenantScopedMixin, TimestampMixin

# --- plan / status enums (plain strings, sqlite/postgres portable — same
# convention as app.models.payment's METHOD_*/STATUS_* constants) ------------

PLAN_BASIC = "basic"
PLAN_PRO = "pro"
PLAN_ENTERPRISE = "enterprise"
VALID_PLANS = (PLAN_BASIC, PLAN_PRO, PLAN_ENTERPRISE)

# Fixed AUD monthly price per plan, per the domain brief. Deliberately a
# literal lookup table, not a config/settings value — pricing changes are a
# product decision, not an environment concern, and keeping it here means the
# price charged always matches the plan requested (see
# app/services/billing.py::price_for_plan).
PLAN_PRICES_AUD: dict[str, Decimal] = {
    PLAN_BASIC: Decimal("29.00"),
    PLAN_PRO: Decimal("49.00"),
    PLAN_ENTERPRISE: Decimal("79.00"),
}

STATUS_TRIALING = "trialing"
STATUS_ACTIVE = "active"
STATUS_PAST_DUE = "past_due"
STATUS_CANCELED = "canceled"
STATUS_INCOMPLETE = "incomplete"
VALID_SUBSCRIPTION_STATUSES = (
    STATUS_TRIALING,
    STATUS_ACTIVE,
    STATUS_PAST_DUE,
    STATUS_CANCELED,
    STATUS_INCOMPLETE,
)


class Subscription(Base, TenantScopedMixin, TimestampMixin):
    __tablename__ = "subscriptions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    vehicle_id: Mapped[str] = mapped_column(String(36), nullable=False, index=True)

    plan: Mapped[str] = mapped_column(String(20), nullable=False)
    stripe_subscription_id: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=STATUS_INCOMPLETE)
    price_aud: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)
