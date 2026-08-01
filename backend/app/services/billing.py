"""Business logic for the billing domain.

Covers: plan -> price derivation (Decimal, never client-supplied), Stripe
Billing Subscription create/update/cancel with the same automatic mock
fallback pattern as `app.services.payments` (so endpoints are testable
without live Stripe credentials or network access), a mock invoice list for
tenants without a live Stripe connection, and Stripe Connect onboarding link
creation (also mock-fallback).
"""
from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import stripe

from app.core.config import settings
from app.models.billing import PLAN_PRICES_AUD, STATUS_ACTIVE, STATUS_CANCELED

logger = logging.getLogger("cab_dispatch.billing")


class InvalidPlan(ValueError):
    """Raised when a plan outside {basic, pro, enterprise} is requested."""


def price_for_plan(plan: str) -> Decimal:
    """The ONLY source of truth for plan pricing — endpoints must never accept
    a client-supplied price_aud. Raises InvalidPlan for anything not in
    PLAN_PRICES_AUD (defense in depth; the Pydantic Literal already restricts
    the wire shape)."""
    try:
        return PLAN_PRICES_AUD[plan]
    except KeyError as exc:
        raise InvalidPlan(f"unknown plan {plan!r}") from exc


# --- Stripe integration with mock fallback ------------------------------------
# Same pattern as app.services.payments._stripe_configured /
# create_tap_to_pay_intent: a placeholder/missing key, OR any StripeError at
# call time (auth error, no network in dev/CI, etc.), falls back to a
# clearly-labeled mock response so every endpoint below is testable without
# real Stripe credentials.

_PLACEHOLDER_KEYS = {"", "sk_test_placeholder"}


def _stripe_configured() -> bool:
    key = settings.STRIPE_SECRET_KEY
    return bool(key) and key not in _PLACEHOLDER_KEYS


def create_subscription(*, plan: str, vehicle_id: str) -> dict:
    """Returns a dict describing either a real Stripe Subscription or a mock
    one. Price is always derived from `plan` via price_for_plan — this
    function never takes a price argument."""
    price = price_for_plan(plan)

    if _stripe_configured():
        try:
            stripe.api_key = settings.STRIPE_SECRET_KEY
            price_obj = stripe.Price.create(
                currency="aud",
                unit_amount=int((price * 100).to_integral_value()),
                recurring={"interval": "month"},
                product_data={"name": f"Cab Dispatch {plan} plan — vehicle {vehicle_id}"},
            )
            # No live Stripe Customer management in this domain slice (owned
            # by a sibling billing-account/tenant-onboarding concern, out of
            # scope here) — subscriptions are created against a throwaway
            # customer so the mock-fallback contract still holds end to end
            # if a real key is ever configured in this environment.
            customer = stripe.Customer.create(description=f"vehicle:{vehicle_id}")
            sub = stripe.Subscription.create(
                customer=customer["id"],
                items=[{"price": price_obj["id"]}],
            )
            return {
                "mock": False,
                "stripe_subscription_id": sub["id"],
                "status": sub["status"],
                "price_aud": price,
            }
        except stripe.error.StripeError as exc:
            logger.warning("Stripe Subscription.create failed (%s) — returning mock subscription.", exc)

    mock_id = f"mock_sub_{uuid.uuid4().hex[:24]}"
    return {
        "mock": True,
        "stripe_subscription_id": mock_id,
        "status": STATUS_ACTIVE,
        "price_aud": price,
    }


def change_subscription_plan(*, stripe_subscription_id: str | None, new_plan: str) -> dict:
    """Same mock-fallback pattern. Returns the new price and, when a real
    stripe_subscription_id and live key are both present, attempts to update
    the Stripe-side subscription's price too."""
    new_price = price_for_plan(new_plan)

    if _stripe_configured() and stripe_subscription_id and not stripe_subscription_id.startswith("mock_"):
        try:
            stripe.api_key = settings.STRIPE_SECRET_KEY
            price_obj = stripe.Price.create(
                currency="aud",
                unit_amount=int((new_price * 100).to_integral_value()),
                recurring={"interval": "month"},
                product_data={"name": f"Cab Dispatch {new_plan} plan"},
            )
            sub = stripe.Subscription.retrieve(stripe_subscription_id)
            item_id = sub["items"]["data"][0]["id"]
            stripe.Subscription.modify(
                stripe_subscription_id,
                items=[{"id": item_id, "price": price_obj["id"]}],
            )
            return {"mock": False, "price_aud": new_price}
        except stripe.error.StripeError as exc:
            logger.warning("Stripe Subscription.modify failed (%s) — updating local record only.", exc)

    return {"mock": True, "price_aud": new_price}


def cancel_subscription(*, stripe_subscription_id: str | None) -> dict:
    """Same mock-fallback pattern. Cancels Stripe-side if configured and the
    id looks real; always returns the local status to transition to."""
    if _stripe_configured() and stripe_subscription_id and not stripe_subscription_id.startswith("mock_"):
        try:
            stripe.api_key = settings.STRIPE_SECRET_KEY
            stripe.Subscription.delete(stripe_subscription_id)
            return {"mock": False, "status": STATUS_CANCELED}
        except stripe.error.StripeError as exc:
            logger.warning("Stripe Subscription.delete failed (%s) — canceling local record only.", exc)

    return {"mock": True, "status": STATUS_CANCELED}


# --- Invoices (mock list if no live Stripe connection) ------------------------


def list_invoices_for_subscription(*, stripe_subscription_id: str | None, price_aud: Decimal, subscription_id: str) -> dict:
    """Returns a dict with `mock` + `items` (each item shaped for
    InvoiceRead minus subscription_id, which the caller fills in). Live
    Stripe invoice history is used only when Stripe is configured AND the
    subscription has a real (non-mock) stripe_subscription_id; otherwise a
    single synthetic "current period" invoice is synthesized so the endpoint
    is always usable in dev/CI."""
    if (
        _stripe_configured()
        and stripe_subscription_id
        and not stripe_subscription_id.startswith("mock_")
    ):
        try:
            stripe.api_key = settings.STRIPE_SECRET_KEY
            invoices = stripe.Invoice.list(subscription=stripe_subscription_id, limit=100)
            items = [
                {
                    "id": inv["id"],
                    "stripe_invoice_id": inv["id"],
                    "amount_aud": Decimal(inv["amount_due"]) / Decimal(100),
                    "status": inv["status"],
                    "period_start": datetime.fromtimestamp(inv["period_start"], tz=UTC),
                    "period_end": datetime.fromtimestamp(inv["period_end"], tz=UTC),
                    "mock": False,
                }
                for inv in invoices["data"]
            ]
            return {"mock": False, "items": items}
        except stripe.error.StripeError as exc:
            logger.warning("Stripe Invoice.list failed (%s) — returning mock invoice list.", exc)

    now = datetime.now(UTC)
    period_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    period_end = period_start + timedelta(days=30)
    mock_invoice = {
        "id": f"mock_inv_{uuid.uuid4().hex[:24]}",
        "stripe_invoice_id": None,
        "amount_aud": price_aud,
        "status": "open",
        "period_start": period_start,
        "period_end": period_end,
        "mock": True,
    }
    return {"mock": True, "items": [mock_invoice]}


# --- Stripe Connect onboarding -------------------------------------------------


def create_connect_onboarding_link(*, tenant_id: str, return_url: str, refresh_url: str) -> dict:
    """Same mock-fallback pattern. Creates a Stripe Connect Express account +
    account link for a tenant (used so tenants can receive payouts).
    Returns a dict with mock/url/stripe_account_id."""
    if _stripe_configured():
        try:
            stripe.api_key = settings.STRIPE_SECRET_KEY
            account = stripe.Account.create(
                type="express",
                country="AU",
                metadata={"tenant_id": tenant_id},
            )
            link = stripe.AccountLink.create(
                account=account["id"],
                refresh_url=refresh_url,
                return_url=return_url,
                type="account_onboarding",
            )
            return {"mock": False, "url": link["url"], "stripe_account_id": account["id"]}
        except stripe.error.StripeError as exc:
            logger.warning("Stripe Connect onboarding failed (%s) — returning mock link.", exc)

    mock_account_id = f"mock_acct_{uuid.uuid4().hex[:24]}"
    return {
        "mock": True,
        "url": f"https://connect.mock.stripe.com/onboard/{mock_account_id}",
        "stripe_account_id": mock_account_id,
    }
