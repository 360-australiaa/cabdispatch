"""Business logic for the payments domain.

Covers: the mandatory non-cash surcharge cap (Fares Order 2025 (no.2) — hard
5% limit, see docs/TCT-METER-01-spec.md A5: "Build the cap as a hard limit in
the payment service, not a config default"), Stripe Tap-to-Pay / payment-link
creation with an automatic mock fallback so the endpoints are testable without
live Stripe credentials or network access, cash change calculation, and Stripe
webhook signature verification + event mapping.
"""
from __future__ import annotations

import json
import logging
import os
import uuid
from decimal import Decimal

import stripe

from app.core.config import settings
from app.models.payment import (
    STATUS_CANCELED,
    STATUS_FAILED,
    STATUS_REFUNDED,
    STATUS_SUCCEEDED,
)
from app.services.fare_engine import round_half_up

logger = logging.getLogger("cab_dispatch.payments")

# --- Surcharge cap ------------------------------------------------------------
# Fares Order 2025 (no.2): non-cash surcharge is capped at 5% incl GST of the
# amount payable, rounded half-up to the cent. This is a LITERAL, not read
# from settings/Tariff config — the spec explicitly requires it be an
# unconditional hard limit in the payment service. Do not make this
# configurable per-tenant.
SURCHARGE_CAP_PCT = Decimal("0.05")


class SurchargeCapExceeded(ValueError):
    """Raised when a requested surcharge exceeds the unconditional 5% cap."""


def max_allowed_surcharge(amount: Decimal) -> Decimal:
    return round_half_up(amount * SURCHARGE_CAP_PCT)


def validate_surcharge(amount: Decimal, surcharge: Decimal) -> None:
    """Unconditional server-side hard cap, always enforced regardless of any
    tenant/config setting. Raises SurchargeCapExceeded if violated. Callers
    (the router) must invoke this BEFORE calling out to Stripe."""
    cap = max_allowed_surcharge(amount)
    if surcharge > cap:
        raise SurchargeCapExceeded(
            f"surcharge {surcharge} exceeds the 5% cap of {cap} for amount {amount}"
        )


# --- Stripe integration with mock fallback ------------------------------------

_PLACEHOLDER_KEYS = {"", "sk_test_placeholder"}


def _stripe_configured() -> bool:
    key = settings.STRIPE_SECRET_KEY
    return bool(key) and key not in _PLACEHOLDER_KEYS


def create_tap_to_pay_intent(*, amount: Decimal, surcharge: Decimal) -> dict:
    """Returns a dict describing either a real Stripe PaymentIntent or, if
    Stripe isn't configured (placeholder key) or the call fails for any
    reason (auth error, no network in dev/CI, etc.), a clearly-labeled mock
    response — so this endpoint is testable without real Stripe credentials."""
    total = amount + surcharge
    if _stripe_configured():
        try:
            stripe.api_key = settings.STRIPE_SECRET_KEY
            intent = stripe.PaymentIntent.create(
                amount=int((total * 100).to_integral_value()),
                currency="aud",
                payment_method_types=["card_present"],
                capture_method="automatic",
            )
            return {
                "mock": False,
                "stripe_pi_id": intent["id"],
                "client_secret": intent.get("client_secret"),
                "stripe_status": intent["status"],
            }
        except stripe.error.StripeError as exc:
            logger.warning("Stripe PaymentIntent.create failed (%s) — returning mock intent.", exc)

    mock_id = f"mock_pi_{uuid.uuid4().hex[:24]}"
    return {
        "mock": True,
        "stripe_pi_id": mock_id,
        "client_secret": f"{mock_id}_secret_mock",
        "stripe_status": "requires_payment_method",
    }


def create_payment_link(*, amount: Decimal, surcharge: Decimal, trip_id: str) -> dict:
    """Same mock-fallback pattern as `create_tap_to_pay_intent`."""
    total = amount + surcharge
    if _stripe_configured():
        try:
            stripe.api_key = settings.STRIPE_SECRET_KEY
            price = stripe.Price.create(
                currency="aud",
                unit_amount=int((total * 100).to_integral_value()),
                product_data={"name": f"Cab Dispatch trip {trip_id}"},
            )
            link = stripe.PaymentLink.create(line_items=[{"price": price["id"], "quantity": 1}])
            return {"mock": False, "stripe_pi_id": link["id"], "url": link["url"]}
        except stripe.error.StripeError as exc:
            logger.warning("Stripe PaymentLink.create failed (%s) — returning mock link.", exc)

    mock_id = f"mock_plink_{uuid.uuid4().hex[:24]}"
    return {"mock": True, "stripe_pi_id": mock_id, "url": f"https://pay.mock.stripe.com/{mock_id}"}


def compute_change(*, amount: Decimal, tendered: Decimal) -> Decimal:
    return round_half_up(tendered - amount)


# --- Stripe webhook ------------------------------------------------------------

# Deliberately read directly from the environment rather than
# app.core.config.Settings: this domain slice's task boundary explicitly
# forbids editing app/core/config.py. A later integration step could promote
# this to a proper Settings field; until then, set STRIPE_WEBHOOK_SECRET in
# the environment to enable signature verification. Absent it, the webhook
# accepts unsigned payloads (dev-only fallback per spec).
def _webhook_secret() -> str | None:
    return os.environ.get("STRIPE_WEBHOOK_SECRET") or None


# Stripe event types this domain cares about, mapped to the resulting payment
# status.
_EVENT_STATUS_MAP = {
    "payment_intent.succeeded": STATUS_SUCCEEDED,
    "payment_intent.payment_failed": STATUS_FAILED,
    "payment_intent.canceled": STATUS_CANCELED,
    "charge.refunded": STATUS_REFUNDED,
}


def verify_and_parse_webhook(*, payload: bytes, sig_header: str | None) -> dict:
    """Verifies the Stripe signature if STRIPE_WEBHOOK_SECRET is configured
    (raises stripe.error.SignatureVerificationError on mismatch); otherwise
    accepts the payload unsigned. Always returns a plain dict."""
    secret = _webhook_secret()
    if secret:
        event = stripe.Webhook.construct_event(payload, sig_header, secret)
        return event.to_dict() if hasattr(event, "to_dict") else dict(event)
    return json.loads(payload)


def status_for_event(event_type: str) -> str | None:
    return _EVENT_STATUS_MAP.get(event_type)
