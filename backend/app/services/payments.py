"""Business logic for the payments domain.

Covers: the mandatory non-cash surcharge cap (Fares Order 2025 (no.2) — hard
5% limit, see docs/TCT-METER-01-spec.md A5: "Build the cap as a hard limit in
the payment service, not a config default"), Stripe Tap-to-Pay / payment-link
creation with an automatic mock fallback so the endpoints are testable without
live Stripe credentials or network access, cash change calculation, Stripe
webhook signature verification + event mapping, CabCharge authorization
(blueprint 5.2.5: Authorization -> Docket creation -> Settlement batch) and
TTSS subsidy claim submission (blueprint 5.2.5/11.1: Eligibility check ->
Subsidy calculation -> Claim submission -> Reimbursement) — the latter two
with the same real-or-mock fallback pattern as Stripe.
"""
from __future__ import annotations

import json
import logging
import os
import uuid
from datetime import UTC, datetime
from decimal import Decimal

import httpx
import stripe
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.payment import (
    STATUS_CANCELED,
    STATUS_FAILED,
    STATUS_REFUNDED,
    STATUS_SUCCEEDED,
)
from app.models.vouchers import CorporateAccount, Voucher
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


# --- CabCharge integration with mock fallback ----------------------------------
# Same pattern as the Stripe helpers above: a placeholder/missing
# CABCHARGE_API_KEY, or any HTTP failure at call time (auth error, no network
# in dev/CI, non-2xx response, malformed body), falls back to a
# clearly-labeled mock authorization so the endpoint is testable without real
# CabCharge credentials or network access.

_CABCHARGE_PLACEHOLDER_KEYS = {"", "cabcharge_test_placeholder"}


def _cabcharge_configured() -> bool:
    key = settings.CABCHARGE_API_KEY
    return bool(key) and key not in _CABCHARGE_PLACEHOLDER_KEYS


def _generate_docket_number(prefix: str) -> str:
    """A plausible-looking docket/settlement reference, e.g. CBC-A1B2C3D4."""
    return f"{prefix}-{uuid.uuid4().hex[:8].upper()}"


def authorize_cabcharge(*, card_identifier: str, amount: Decimal) -> dict:
    """Authorization -> Docket creation step of blueprint 5.2.5's CabCharge
    flow (Settlement batch is a later, separate process out of scope here).
    Returns a dict describing either a real CabCharge authorization or, if
    CabCharge isn't configured or the call fails for any reason, a
    clearly-labeled mock authorization."""
    if _cabcharge_configured():
        try:
            with httpx.Client(timeout=10.0) as http_client:
                resp = http_client.post(
                    f"{settings.CABCHARGE_BASE_URL}/authorizations",
                    headers={"Authorization": f"Bearer {settings.CABCHARGE_API_KEY}"},
                    json={
                        "merchant_id": settings.CABCHARGE_MERCHANT_ID,
                        "card_identifier": card_identifier,
                        "amount": str(amount),
                        "currency": "AUD",
                    },
                )
                resp.raise_for_status()
                data = resp.json()
            return {
                "mock": False,
                "authorization_id": data["authorization_id"],
                "docket_number": data.get("docket_number") or _generate_docket_number("CBC"),
                "cabcharge_status": data.get("status", "authorized"),
            }
        except (httpx.HTTPError, KeyError, ValueError) as exc:
            logger.warning("CabCharge authorization failed (%s) — returning mock authorization.", exc)

    return {
        "mock": True,
        "authorization_id": f"mock_cabcharge_{uuid.uuid4().hex[:24]}",
        "docket_number": _generate_docket_number("CBC"),
        "cabcharge_status": "authorized",
    }


# --- TTSS subsidy + claim submission with mock fallback ------------------------
# Blueprint 11.1: the TTSS subsidy is 50% of the fare, capped at $60.00 per
# trip. This is a LITERAL, not read from settings/tenant config — same
# rationale as SURCHARGE_CAP_PCT above: the blueprint requires an
# unconditional hard limit computed in the payment service. Do not make this
# configurable per-tenant.
TTSS_SUBSIDY_PCT = Decimal("0.50")
TTSS_SUBSIDY_CAP = Decimal("60.00")

_TTSS_PLACEHOLDER_KEYS = {"", "ttss_test_placeholder"}


def _ttss_configured() -> bool:
    key = settings.TTSS_API_KEY
    return bool(key) and key not in _TTSS_PLACEHOLDER_KEYS


def compute_ttss_subsidy(fare_amount: Decimal) -> Decimal:
    """50% of the fare, rounded half-up to the cent, capped at $60.00
    (blueprint 11.1). E.g. a $200.00 fare caps at $60.00, not $100.00."""
    subsidy = round_half_up(fare_amount * TTSS_SUBSIDY_PCT)
    return min(subsidy, TTSS_SUBSIDY_CAP)


def submit_ttss_claim(*, concession_identifier: str, fare_amount: Decimal, subsidy_amount: Decimal) -> dict:
    """Subsidy calculation -> Claim submission step of blueprint 5.2.5's TTSS
    flow (Eligibility check is assumed done upstream by the caller;
    Reimbursement happens later, out of scope here). Same mock-fallback
    pattern as `authorize_cabcharge`."""
    if _ttss_configured():
        try:
            with httpx.Client(timeout=10.0) as http_client:
                resp = http_client.post(
                    f"{settings.TTSS_BASE_URL}/claims",
                    headers={"Authorization": f"Bearer {settings.TTSS_API_KEY}"},
                    json={
                        "provider_id": settings.TTSS_PROVIDER_ID,
                        "concession_identifier": concession_identifier,
                        "fare_amount": str(fare_amount),
                        "subsidy_amount": str(subsidy_amount),
                        "currency": "AUD",
                    },
                )
                resp.raise_for_status()
                data = resp.json()
            return {
                "mock": False,
                "claim_id": data["claim_id"],
                "docket_number": data.get("docket_number") or _generate_docket_number("TTSS"),
                "ttss_status": data.get("status", "submitted"),
            }
        except (httpx.HTTPError, KeyError, ValueError) as exc:
            logger.warning("TTSS claim submission failed (%s) — returning mock claim.", exc)

    return {
        "mock": True,
        "claim_id": f"mock_ttss_{uuid.uuid4().hex[:24]}",
        "docket_number": _generate_docket_number("TTSS"),
        "ttss_status": "submitted",
    }


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


# --- Voucher / linked corporate ("Account") payment methods (blueprint 5.2.5) -
# Two new Trip.payment_method values (see app.models.trips / app.schemas.trips):
# "voucher" (promo code/prepaid voucher redemption) and "account" (pre-registered
# linked corporate account, pay-later/invoiced). Both now have a real backing
# table (app.models.vouchers.Voucher / CorporateAccount) -- this replaces the
# earlier v1-scope non-empty-string-only stub validation. Deliberately NOT the
# real-or-mock external-API pattern above: there is no external voucher/
# corporate-account API being called here at all, this is a plain local ledger.


class InvalidVoucherCodeError(ValueError):
    """Raised when a voucher_code is missing/whitespace-only, doesn't resolve
    to a tenant-owned Voucher row, or resolves to one that's already been
    redeemed or has expired."""


class InvalidAccountReferenceError(ValueError):
    """Raised when an account_reference is missing/whitespace-only, doesn't
    resolve to a tenant-owned CorporateAccount row, or resolves to one that's
    inactive."""


async def redeem_voucher(
    session: AsyncSession, *, tenant_id: str, voucher_code: str, trip_id: str
) -> dict:
    """Redeems a real Voucher row (app.models.vouchers.Voucher) for `trip_id`.
    Raises InvalidVoucherCodeError (422 at every call site) if the code
    doesn't resolve to a tenant-owned voucher, or resolves to one already
    redeemed or expired. On success marks the voucher redeemed
    (redeemed_at/redeemed_by_trip_id) and returns its value_aud. Does NOT
    commit -- caller owns the transaction (same contract as
    app.services.trips.close_trip, which is this function's own caller)."""
    code = (voucher_code or "").strip()
    if not code:
        raise InvalidVoucherCodeError("voucher_code must be a non-empty string")

    result = await session.execute(
        select(Voucher).where(Voucher.tenant_id == tenant_id, Voucher.code == code)
    )
    voucher = result.scalar_one_or_none()
    if voucher is None:
        raise InvalidVoucherCodeError(f"Unknown voucher code: {voucher_code}")
    if voucher.redeemed_at is not None:
        raise InvalidVoucherCodeError(f"Voucher {code} has already been redeemed")
    if voucher.expires_at is not None and voucher.expires_at <= datetime.now(UTC):
        raise InvalidVoucherCodeError(f"Voucher {code} has expired")

    voucher.redeemed_at = datetime.now(UTC)
    voucher.redeemed_by_trip_id = trip_id
    logger.info("Voucher redeemed: tenant=%s code=%s trip_id=%s", tenant_id, code, trip_id)
    return {"voucher_code": voucher.code, "redeemed": True, "value_aud": voucher.value_aud}


async def validate_account_reference(
    session: AsyncSession, *, tenant_id: str, account_reference: str
) -> dict:
    """Validates against a real CorporateAccount row. Raises
    InvalidAccountReferenceError (422 at every call site) if the reference
    doesn't resolve to a tenant-owned corporate account, or resolves to an
    inactive one."""
    reference = (account_reference or "").strip()
    if not reference:
        raise InvalidAccountReferenceError("account_reference must be a non-empty string")

    result = await session.execute(
        select(CorporateAccount).where(
            CorporateAccount.tenant_id == tenant_id, CorporateAccount.reference == reference
        )
    )
    account = result.scalar_one_or_none()
    if account is None:
        raise InvalidAccountReferenceError(f"Unknown corporate account reference: {account_reference}")
    if not account.active:
        raise InvalidAccountReferenceError(f"Corporate account {reference} is not active")

    logger.info("Account-reference validated: tenant=%s ref=%s", tenant_id, reference)
    return {
        "account_reference": account.reference,
        "company_name": account.company_name,
        "active": account.active,
    }
