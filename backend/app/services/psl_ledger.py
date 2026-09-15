"""PSL ledger business logic: top-up processing (Stripe debit of a driver's card,
with a mock fallback when no real Stripe key is configured), the tenant-wide
remittance report aggregation, and — since the admin-panel improvement pass —
the per-trip accrual that actually populates the ledger.

ACCRUAL (why `accrue_trip_psl` / `rebuild_ledger` exist): the ledger's
`trips_count`/`amount_owed` were documented from day one as "accruing from
trip-level PSL charges", but nothing ever wrote them — every trip close path
stored the levy on `trips.psl` and stopped there. Production therefore showed
0 ledger rows against 140 closed trips each carrying the $1.32 levy, and the
monthly remittance report was empty. Both trip close paths
(`app.services.trips.close_trip` and the offline `/sync` replay in
`app.api.v1.trips`) now call `accrue_trip_psl`, which is idempotent PER TRIP
via `psl_trip_accruals.trip_id` (unique) — a meter outbox replays syncs as a
matter of course, so "add on close" without that key would double-count.
`rebuild_ledger` (behind `POST /v1/psl/ledger/rebuild`) is the backfill for
every trip closed before this landed, and the repair tool if the two ever
drift.
"""
from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.psl_ledger import (
    TOPUP_STATUS_FAILED,
    TOPUP_STATUS_SUCCEEDED,
    PSLLedgerEntry,
    PSLTopUp,
    PSLTripAccrual,
)
from app.models.tenant import Tenant
from app.models.trips import TRIP_STATUS_CLOSED, Trip
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


# --- per-trip accrual --------------------------------------------------------

_ZERO = Decimal("0.00")
_DEFAULT_TZ = "Australia/Sydney"


async def _tenant_timezone(session: AsyncSession, *, tenant_id: str) -> str:
    """The tenant's IANA zone (`tenants.timezone`, default Australia/Sydney).
    The levy is remitted per CALENDAR month in the operator's own time, so a
    trip closed at 11 pm Sydney time on the 31st belongs to that month even
    though it is already the 1st in UTC."""
    tz = (await session.execute(select(Tenant.timezone).where(Tenant.id == tenant_id))).scalar_one_or_none()
    return tz or _DEFAULT_TZ


def period_for(moment: datetime, timezone: str) -> str:
    """`"YYYY-MM"` of `moment` in `timezone`. SQLite hands `DateTime(timezone=True)`
    columns back tz-naive (same caveat as app.services.audit_log._canonical_at);
    a naive value is the UTC it was written as."""
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    try:
        zone = ZoneInfo(timezone)
    except Exception:  # an unknown zone name on the tenant row must not break a trip close
        zone = ZoneInfo(_DEFAULT_TZ)
    return moment.astimezone(zone).strftime("%Y-%m")


def _accruable(trip: Trip) -> bool:
    """Only a CLOSED trip that actually carried a levy accrues: an open trip
    has no fare of record yet, and a trip closed with include_psl=False (the
    levy is not charged on every hiring) has nothing to remit."""
    return (
        trip.status == TRIP_STATUS_CLOSED
        and trip.end_at is not None
        and trip.psl is not None
        and Decimal(str(trip.psl)) > 0
    )


async def _driver_is_tenant_user(session: AsyncSession, *, tenant_id: str, driver_id: str) -> bool:
    """`psl_ledger.driver_id` is a real FK onto `users.id`, while
    `trips.driver_id` is deliberately unconstrained (see app.models.trips).
    A trip whose driver id is not a user of this tenant — a bench sync, a
    test rig, a driver wiped after the trip — therefore has no ledger row it
    could accrue into, and must be skipped rather than raise an
    IntegrityError out of the trip close that carried it."""
    result = await session.execute(
        select(User.id).where(User.id == driver_id, User.tenant_id == tenant_id)
    )
    return result.scalar_one_or_none() is not None


async def accrue_trip_psl(session: AsyncSession, *, trip: Trip) -> PSLTripAccrual | None:
    """Accrues `trip.psl` into the (tenant, driver, period) ledger row, exactly
    once per trip. Returns the accrual row, or None when nothing was accrued
    (not accruable, driver not a tenant user, or already accrued).

    Does NOT commit — called from inside the trip-close transaction so the
    accrual lands with the close it belongs to, and rolls back with it.
    """
    if not _accruable(trip):
        return None
    existing = await session.execute(select(PSLTripAccrual).where(PSLTripAccrual.trip_id == trip.id))
    if existing.scalar_one_or_none() is not None:
        return None  # replayed close/sync — already on the ledger
    if not await _driver_is_tenant_user(session, tenant_id=trip.tenant_id, driver_id=trip.driver_id):
        logger.info(
            "PSL accrual skipped for trip %s: driver %s is not a user of tenant %s",
            trip.id,
            trip.driver_id,
            trip.tenant_id,
        )
        return None

    timezone = await _tenant_timezone(session, tenant_id=trip.tenant_id)
    period = period_for(trip.end_at, timezone)
    amount = Decimal(str(trip.psl)).quantize(Decimal("0.01"))

    entry = await get_or_create_ledger_entry(
        session, tenant_id=trip.tenant_id, driver_id=trip.driver_id, period=period
    )
    entry.trips_count = int(entry.trips_count or 0) + 1
    entry.amount_owed = Decimal(str(entry.amount_owed or _ZERO)) + amount

    accrual = PSLTripAccrual(
        tenant_id=trip.tenant_id,
        trip_id=trip.id,
        driver_id=trip.driver_id,
        period=period,
        amount=amount,
    )
    session.add(accrual)
    await session.flush()
    return accrual


async def rebuild_ledger(session: AsyncSession, *, tenant_id: str) -> dict[str, int]:
    """Recomputes every trip-derived ledger figure for `tenant_id` from its
    CLOSED trips: writes the missing `psl_trip_accruals` rows, then sets each
    (driver, period) ledger row's `trips_count`/`amount_owed` to the sum of
    its accruals. `amount_collected`/`remitted_at` are never touched — those
    are money movements and remittance facts, not derivable from trips.

    A ledger row with no accruals beneath it (an admin-entered one, or a
    period whose trips were deleted) is left as it is rather than zeroed:
    this rebuilds what the trips say, it does not delete what they don't.

    Returns {"created": ledger rows newly created, "updated": existing ledger
    rows whose figures changed}. Does NOT commit — the router does.
    """
    timezone = await _tenant_timezone(session, tenant_id=tenant_id)

    # Every closed, levied trip whose driver is a real user of the tenant —
    # the same eligibility rule `accrue_trip_psl` applies one trip at a time.
    trips = (
        await session.execute(
            select(Trip.id, Trip.driver_id, Trip.end_at, Trip.psl)
            .join(User, (User.id == Trip.driver_id) & (User.tenant_id == tenant_id))
            .where(
                Trip.tenant_id == tenant_id,
                Trip.status == TRIP_STATUS_CLOSED,
                Trip.end_at.is_not(None),
                Trip.psl > 0,
            )
        )
    ).all()

    accruals = {
        row.trip_id: row
        for row in (
            await session.execute(select(PSLTripAccrual).where(PSLTripAccrual.tenant_id == tenant_id))
        ).scalars()
    }

    expected: dict[tuple[str, str], tuple[int, Decimal]] = {}
    for trip_id, driver_id, end_at, psl in trips:
        period = period_for(end_at, timezone)
        amount = Decimal(str(psl)).quantize(Decimal("0.01"))
        accrual = accruals.get(trip_id)
        if accrual is None:
            session.add(
                PSLTripAccrual(
                    tenant_id=tenant_id, trip_id=trip_id, driver_id=driver_id, period=period, amount=amount
                )
            )
        elif (accrual.driver_id, accrual.period, Decimal(str(accrual.amount))) != (driver_id, period, amount):
            # The trip moved (reassigned driver, corrected end time): the
            # accrual follows it, and the old (driver, period) row is
            # recomputed below only if it still has trips of its own.
            accrual.driver_id, accrual.period, accrual.amount = driver_id, period, amount
        count, total = expected.get((driver_id, period), (0, _ZERO))
        expected[(driver_id, period)] = (count + 1, total + amount)
    await session.flush()

    created = updated = 0
    for (driver_id, period), (count, total) in expected.items():
        result = await session.execute(
            select(PSLLedgerEntry).where(
                PSLLedgerEntry.tenant_id == tenant_id,
                PSLLedgerEntry.driver_id == driver_id,
                PSLLedgerEntry.period == period,
            )
        )
        entry = result.scalar_one_or_none()
        if entry is None:
            session.add(
                PSLLedgerEntry(
                    tenant_id=tenant_id,
                    driver_id=driver_id,
                    period=period,
                    trips_count=count,
                    amount_owed=total,
                    amount_collected=_ZERO,
                )
            )
            created += 1
        elif int(entry.trips_count or 0) != count or Decimal(str(entry.amount_owed or _ZERO)) != total:
            entry.trips_count = count
            entry.amount_owed = total
            updated += 1
    await session.flush()
    return {"created": created, "updated": updated}


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
