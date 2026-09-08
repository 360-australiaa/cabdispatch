"""Driver-engagement service -- the DERIVED reads behind the four driver-
tablet dashboard tiles (see app/models/driver_engagement.py's module
docstring for the "never store a derivable number" rule).

Every function here takes an explicit `tenant_id` and filters by it -- the
routers (app.api.v1.me / wallet / ratings / announcements / incentives)
resolve it via `get_current_tenant_id`, the sole multi-tenancy mechanism in
this system, and additionally scope `/v1/me/*` reads to the *caller's own*
user id (see `resolve_driver_id`), so a driver can never read another
driver's wallet/rating/progress.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.driver_engagement import (
    Announcement,
    Incentive,
    TripRating,
    WalletTransaction,
)
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import User

_ZERO = Decimal("0.00")
_CENTS = Decimal("0.01")


def resolve_driver_id(user: User) -> str:
    """The one place `/v1/me/*` turns "the authenticated caller" into a
    driver id. Trips, shifts and ratings all key `driver_id` on `users.id`
    (see app.api.v1.trips.flag_trip's `current_user.id == trip.driver_id`),
    so the driver id *is* the user id -- never a query param."""
    return user.id


def utc_now() -> datetime:
    return datetime.now(UTC)


def to_utc(value: datetime) -> datetime:
    """Normalises an incoming API datetime to UTC (naive => assumed UTC).
    Windowed columns (announcement/incentive starts_at/ends_at) must all be
    stored in one zone so the sqlite string comparison in the window queries
    below is correct as well as the postgres timestamptz one."""
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


# --- wallet -------------------------------------------------------------------


async def wallet_balance(session: AsyncSession, *, tenant_id: str, driver_id: str) -> Decimal:
    """SUM of the driver's ledger lines, as a Decimal quantised to cents.
    Zero rows => 0.00 (never None)."""
    stmt = select(func.coalesce(func.sum(WalletTransaction.amount_aud), 0)).where(
        WalletTransaction.tenant_id == tenant_id,
        WalletTransaction.driver_id == driver_id,
    )
    raw = (await session.execute(stmt)).scalar_one()
    return Decimal(str(raw)).quantize(_CENTS, rounding=ROUND_HALF_UP)


async def recent_wallet_transactions(
    session: AsyncSession, *, tenant_id: str, driver_id: str, limit: int = 20
) -> list[WalletTransaction]:
    stmt = (
        select(WalletTransaction)
        .where(
            WalletTransaction.tenant_id == tenant_id,
            WalletTransaction.driver_id == driver_id,
        )
        .order_by(WalletTransaction.created_at.desc(), WalletTransaction.id.desc())
        .limit(limit)
    )
    return list((await session.execute(stmt)).scalars().all())


# --- rating -------------------------------------------------------------------


@dataclass(frozen=True)
class RatingSummary:
    average_stars: Decimal | None  # None when the driver has no ratings yet
    rating_count: int


async def driver_rating(session: AsyncSession, *, tenant_id: str, driver_id: str) -> RatingSummary:
    stmt = select(func.avg(TripRating.stars), func.count(TripRating.id)).where(
        TripRating.tenant_id == tenant_id,
        TripRating.driver_id == driver_id,
    )
    avg_raw, count = (await session.execute(stmt)).one()
    if not count:
        return RatingSummary(average_stars=None, rating_count=0)
    average = Decimal(str(avg_raw)).quantize(_CENTS, rounding=ROUND_HALF_UP)
    return RatingSummary(average_stars=average, rating_count=int(count))


async def recent_trip_ratings(
    session: AsyncSession, *, tenant_id: str, driver_id: str, limit: int = 10
) -> list[TripRating]:
    stmt = (
        select(TripRating)
        .where(TripRating.tenant_id == tenant_id, TripRating.driver_id == driver_id)
        .order_by(TripRating.created_at.desc(), TripRating.id.desc())
        .limit(limit)
    )
    return list((await session.execute(stmt)).scalars().all())


# --- announcements ------------------------------------------------------------


async def list_live_announcements(
    session: AsyncSession, *, tenant_id: str, now: datetime | None = None
) -> list[Announcement]:
    """Driver-facing list: active AND starts_at <= now AND (ends_at IS NULL
    OR ends_at > now). Newest-starting first."""
    now = to_utc(now or utc_now())
    stmt = (
        select(Announcement)
        .where(
            Announcement.tenant_id == tenant_id,
            Announcement.active.is_(True),
            Announcement.starts_at <= now,
            (Announcement.ends_at.is_(None)) | (Announcement.ends_at > now),
        )
        .order_by(Announcement.starts_at.desc(), Announcement.id.desc())
    )
    return list((await session.execute(stmt)).scalars().all())


# --- incentives ---------------------------------------------------------------


async def list_live_incentives(
    session: AsyncSession, *, tenant_id: str, now: datetime | None = None
) -> list[Incentive]:
    """Driver-facing list: active AND starts_at <= now < ends_at."""
    now = to_utc(now or utc_now())
    stmt = (
        select(Incentive)
        .where(
            Incentive.tenant_id == tenant_id,
            Incentive.active.is_(True),
            Incentive.starts_at <= now,
            Incentive.ends_at > now,
        )
        .order_by(Incentive.ends_at.asc(), Incentive.id.asc())
    )
    return list((await session.execute(stmt)).scalars().all())


async def count_completed_trips_in_window(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str,
    starts_at: datetime,
    ends_at: datetime,
) -> int:
    """The real number from the trips table: this driver's CLOSED trips whose
    `end_at` (the moment the meter was closed) falls inside [starts_at,
    ends_at). Open trips never count, nor do trips closed outside the window."""
    stmt = select(func.count(Trip.id)).where(
        Trip.tenant_id == tenant_id,
        Trip.driver_id == driver_id,
        Trip.status == TRIP_STATUS_CLOSED,
        Trip.end_at.is_not(None),
        Trip.end_at >= to_utc(starts_at),
        Trip.end_at < to_utc(ends_at),
    )
    return int((await session.execute(stmt)).scalar_one())


@dataclass(frozen=True)
class IncentiveProgress:
    incentive: Incentive
    completed_trips: int
    target_trips: int
    remaining_trips: int
    progress_pct: int  # 0..100, integer, capped at 100
    achieved: bool


async def incentive_progress_for_driver(
    session: AsyncSession, *, tenant_id: str, driver_id: str, incentive: Incentive
) -> IncentiveProgress:
    completed = await count_completed_trips_in_window(
        session,
        tenant_id=tenant_id,
        driver_id=driver_id,
        starts_at=incentive.starts_at,
        ends_at=incentive.ends_at,
    )
    target = int(incentive.target_trips)
    remaining = max(target - completed, 0)
    pct = min(100, (completed * 100) // target) if target > 0 else 0
    return IncentiveProgress(
        incentive=incentive,
        completed_trips=completed,
        target_trips=target,
        remaining_trips=remaining,
        progress_pct=pct,
        achieved=completed >= target,
    )
