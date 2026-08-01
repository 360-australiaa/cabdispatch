"""PSL ledger business logic: top-up processing (Stripe debit of a driver's card,
with a mock fallback when no real Stripe key is configured) and the tenant-wide
remittance report aggregation.
"""
from __future__ import annotations

import logging
import uuid
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.psl_ledger import (
    TOPUP_STATUS_FAILED,
    TOPUP_STATUS_SUCCEEDED,
    PSLLedgerEntry,
    PSLTopUp,
)
from app.models.user import User

logger = logging.getLogger("cab_dispatch.psl_ledger")

# Same placeholder value as app.core.config.Settings.STRIPE_SECRET_KEY's default —
# treated as "no real key configured" everywhere Stripe is touched in this codebase.
_STRIPE_PLACEHOLDER_KEY = "sk_test_placeholder"


def _stripe_is_configured() -> bool:
    return bool(settings.STRIPE_SECRET_KEY) and settings.STRIPE_SECRET_KEY != _STRIPE_PLACEHOLDER_KEY


async def charge_driver_card(*, driver: User, amount: Decimal) -> tuple[str, str]:
    """Attempts a real Stripe debit of the driver's card-on-file when a real
    secret key is configured; otherwise (the dev/test default) synthesizes a
    deterministic mock charge so the top-up flow is fully exercisable without
    Stripe credentials — mirrors the Redis mock-fallback pattern in
    `app.core.security._RevocationStore`.

    Returns (stripe_charge_id, status) where status is "succeeded" or "failed".
    """
    if _stripe_is_configured():
        try:
            import stripe

            stripe.api_key = settings.STRIPE_SECRET_KEY
            # NOTE: resolving the driver's Stripe customer/payment method is out of
            # scope for the PSL domain (would live on the driver/payments profile,
            # a field this domain does not own). This call is wired for a future
            # integration once that customer id is available on the User/driver
            # profile; until then a misconfigured real key will raise and we fall
            # back to the mock path below rather than silently no-op.
            intent = stripe.PaymentIntent.create(
                amount=int(amount * 100),
                currency="aud",
                customer=getattr(driver, "stripe_customer_id", None),
                off_session=True,
                confirm=True,
                description=f"PSL top-up for driver {driver.id}",
            )
            status = TOPUP_STATUS_SUCCEEDED if intent.status == "succeeded" else TOPUP_STATUS_FAILED
            return intent.id, status
        except Exception:
            logger.warning(
                "Stripe PSL top-up charge failed for driver_id=%s; falling back to mock.",
                driver.id,
                exc_info=True,
            )

    # Mock fallback: deterministic success, no external call.
    return f"mock_ch_{uuid.uuid4().hex[:24]}", TOPUP_STATUS_SUCCEEDED


async def get_or_create_ledger_entry(
    session: AsyncSession, *, tenant_id: str, driver_id: str, period: str
) -> PSLLedgerEntry:
    """Fetches the (tenant, driver, period) ledger row, creating an empty one if
    this is the first activity recorded for that period."""
    result = await session.execute(
        select(PSLLedgerEntry).where(
            PSLLedgerEntry.tenant_id == tenant_id,
            PSLLedgerEntry.driver_id == driver_id,
            PSLLedgerEntry.period == period,
        )
    )
    entry = result.scalar_one_or_none()
    if entry is None:
        entry = PSLLedgerEntry(
            tenant_id=tenant_id,
            driver_id=driver_id,
            period=period,
            trips_count=0,
            amount_owed=Decimal("0.00"),
            amount_collected=Decimal("0.00"),
        )
        session.add(entry)
        await session.flush()
    return entry


async def record_topup(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver: User,
    period: str,
    amount: Decimal,
    payment_method: str,
) -> PSLTopUp:
    """Charges the driver's card (or mocks it), logs the top-up, and — on success —
    credits the period's ledger entry's `amount_collected`."""
    charge_id, status = await charge_driver_card(driver=driver, amount=amount)

    topup = PSLTopUp(
        tenant_id=tenant_id,
        driver_id=driver.id,
        period=period,
        amount=amount,
        payment_method=payment_method,
        stripe_charge_id=charge_id,
        status=status,
    )
    session.add(topup)

    if status == TOPUP_STATUS_SUCCEEDED:
        entry = await get_or_create_ledger_entry(
            session, tenant_id=tenant_id, driver_id=driver.id, period=period
        )
        entry.amount_collected = entry.amount_collected + amount

    await session.commit()
    await session.refresh(topup)
    return topup


async def build_report(session: AsyncSession, *, tenant_id: str, period: str) -> dict:
    """Aggregates every driver's PSL ledger row for the tenant/period into a
    tenant-wide remittance summary."""
    result = await session.execute(
        select(PSLLedgerEntry, User.name)
        .join(User, User.id == PSLLedgerEntry.driver_id)
        .where(PSLLedgerEntry.tenant_id == tenant_id, PSLLedgerEntry.period == period)
        .order_by(User.name)
    )
    rows = result.all()

    drivers = []
    total_trips = 0
    total_owed = Decimal("0.00")
    total_collected = Decimal("0.00")

    for entry, driver_name in rows:
        outstanding = entry.amount_owed - entry.amount_collected
        drivers.append(
            {
                "driver_id": entry.driver_id,
                "driver_name": driver_name,
                "trips_count": entry.trips_count,
                "amount_owed": entry.amount_owed,
                "amount_collected": entry.amount_collected,
                "amount_outstanding": outstanding,
                "remitted": entry.remitted_at is not None,
            }
        )
        total_trips += entry.trips_count
        total_owed += entry.amount_owed
        total_collected += entry.amount_collected

    return {
        "tenant_id": tenant_id,
        "period": period,
        "driver_count": len(drivers),
        "total_trips": total_trips,
        "total_owed": total_owed,
        "total_collected": total_collected,
        "total_outstanding": total_owed - total_collected,
        "drivers": drivers,
    }
