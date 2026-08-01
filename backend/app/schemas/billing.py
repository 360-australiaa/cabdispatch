"""Pydantic v2 schemas for the billing domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Plan = Literal["basic", "pro", "enterprise"]
SubscriptionStatus = Literal["trialing", "active", "past_due", "canceled", "incomplete"]


class SubscriptionBase(BaseModel):
    vehicle_id: str
    plan: Plan


class SubscriptionCreate(SubscriptionBase):
    """price_aud/status/stripe_subscription_id are deliberately NOT
    client-settable on create — price is always derived server-side from
    `plan` (see app.services.billing.price_for_plan) so it can never drift
    from the plan actually billed, and status/stripe id come back from the
    (mock-fallback) Stripe call."""


class SubscriptionUpdate(BaseModel):
    """Plan change only. `vehicle_id` is immutable after creation (create a
    new subscription for a different vehicle instead); `price_aud` is
    re-derived server-side from the new plan, never client-supplied."""

    plan: Plan | None = None
    status: SubscriptionStatus | None = None


class SubscriptionRead(SubscriptionBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    stripe_subscription_id: str | None = None
    status: SubscriptionStatus
    price_aud: Decimal
    created_at: datetime
    updated_at: datetime


class SubscriptionListResponse(BaseModel):
    items: list[SubscriptionRead]
    total: int
    skip: int
    limit: int


# --- invoices (mock list if no live Stripe connection) -----------------------


class InvoiceRead(BaseModel):
    id: str
    subscription_id: str
    stripe_invoice_id: str | None = None
    amount_aud: Decimal
    status: Literal["draft", "open", "paid", "void", "uncollectible"]
    period_start: datetime
    period_end: datetime
    mock: bool = Field(description="True when this row is synthesized, not read from live Stripe.")


class InvoiceListResponse(BaseModel):
    items: list[InvoiceRead]
    total: int
    mock: bool


# --- Stripe Connect onboarding -------------------------------------------------


class ConnectOnboardResponse(BaseModel):
    mock: bool
    url: str
    stripe_account_id: str | None = None
