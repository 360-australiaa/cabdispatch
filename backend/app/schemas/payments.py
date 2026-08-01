"""Pydantic v2 schemas for the payments domain."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

PaymentMethod = Literal["tap_to_pay", "link", "cash", "cabcharge", "ttss"]
PaymentStatus = Literal["pending", "requires_action", "succeeded", "failed", "refunded", "canceled"]


class PaymentBase(BaseModel):
    trip_id: str
    method: PaymentMethod
    amount: Decimal = Field(gt=0)
    surcharge: Decimal = Field(default=Decimal("0.00"), ge=0)


class PaymentCreate(PaymentBase):
    """Generic create shape. NOT exposed as its own `POST /v1/payments` route —
    every payment is created through one of the method-specific endpoints
    (tap-to-pay intent, payment link, cash, manual) so the right
    validation/state-machine for that rail always runs. Kept here for
    completeness / potential reuse by the service layer."""

    stripe_pi_id: str | None = None
    status: PaymentStatus = "pending"
    change_given: Decimal | None = None
    docket_number: str | None = None
    notes: str | None = None


class PaymentUpdate(BaseModel):
    """Partial update. amount/surcharge/method/trip_id are immutable after
    creation (financial audit trail) — only status transitions (typically
    webhook-driven), the Stripe reference, and free-text notes/docket
    corrections are patchable."""

    status: PaymentStatus | None = None
    stripe_pi_id: str | None = None
    captured_at: datetime | None = None
    notes: str | None = None
    docket_number: str | None = None


class PaymentRead(PaymentBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    stripe_pi_id: str | None = None
    status: PaymentStatus
    captured_at: datetime | None = None
    change_given: Decimal | None = None
    docket_number: str | None = None
    notes: str | None = None
    created_at: datetime
    updated_at: datetime


class PaymentListResponse(BaseModel):
    items: list[PaymentRead]
    total: int
    skip: int
    limit: int


# --- endpoint-specific request/response shapes -------------------------------


class TapToPayIntentRequest(BaseModel):
    trip_id: str
    amount: Decimal = Field(gt=0)
    surcharge: Decimal = Field(default=Decimal("0.00"), ge=0)


class TapToPayIntentResponse(BaseModel):
    payment: PaymentRead
    mock: bool
    client_secret: str | None = None
    stripe_status: str | None = None


class PaymentLinkRequest(BaseModel):
    trip_id: str
    amount: Decimal = Field(gt=0)
    surcharge: Decimal = Field(default=Decimal("0.00"), ge=0)


class PaymentLinkResponse(BaseModel):
    payment: PaymentRead
    mock: bool
    url: str | None = None


class CashPaymentRequest(BaseModel):
    trip_id: str
    amount: Decimal = Field(gt=0)
    tendered: Decimal = Field(gt=0)

    @model_validator(mode="after")
    def _tendered_must_cover_amount(self) -> CashPaymentRequest:
        if self.tendered < self.amount:
            raise ValueError("tendered amount must be >= the fare amount")
        return self


class ManualPaymentRequest(BaseModel):
    """CabCharge/TTSS docket entry — Phase 1 is manual entry, no live rail
    integration."""

    trip_id: str
    method: Literal["cabcharge", "ttss"]
    amount: Decimal = Field(gt=0)
    surcharge: Decimal = Field(default=Decimal("0.00"), ge=0)
    docket_number: str = Field(min_length=1, max_length=100)
    notes: str | None = None


class StripeWebhookResponse(BaseModel):
    received: bool
    payment_id: str | None = None
    status: str | None = None
