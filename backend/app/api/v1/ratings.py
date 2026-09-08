"""Ratings router -- the passenger's 1-5 star rating of the driver for one
closed trip (app.models.driver_engagement.TripRating).

Two surfaces:

* `POST /v1/trips/{trip_id}/rating` -- the hook Close & Pay's "rating" step
  posts to on the driver tablet, *after* `POST /v1/trips/{id}/close` has
  returned. Deliberately a separate post-close call rather than a field on
  TripCloseRequest: the tablet's Close & Pay flow settles payment first and
  only then hands the tablet to the passenger for the rating, and keeping
  it out of close_trip means the existing close behaviour (fare engine,
  voucher/account/split validation, receipt) is untouched. Declared here as
  a literal `/v1/trips/...` path on this router (same "literal path owned
  by a sibling router" precedent as app.api.v1.live_ops) so app/api/v1/
  trips.py needs no edit at all. Allowed for the trip's own driver or a
  staff role; the trip must be closed; one rating per trip (409 on repeat).
* `GET /v1/ratings` -- owner/admin paged list (filter by driver_id/stars,
  real `total` via SELECT count(*)) for the dashboard's row-level table. A
  driver reads their own via GET /v1/me/rating.
* `GET /v1/ratings/summary` -- owner/admin fleet-wide (or single-driver)
  rollup: total, average, star distribution, per-driver average -- computed
  as a real SQL aggregate over every matching row, not derived client-side
  from a capped page of `GET /v1/ratings` the way `RatingsPage.tsx` used to.

Every query filters by tenant_id via `get_current_tenant_id` -- the sole
multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.models.driver_engagement import RATING_MAX_STARS, RATING_MIN_STARS, TripRating
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import User
from app.schemas.driver_engagement import (
    Page,
    RatingsSummary,
    RatingsSummaryDriverLine,
    TripRatingCreate,
    TripRatingRead,
)

router = APIRouter(tags=["ratings"])

_require_admin = require_role("owner", "admin")

# Same staff-role set app.api.v1.trips.flag_trip uses for "may act on a trip
# they don't themselves drive".
_STAFF_ROLES = ("owner", "admin", "dispatcher")


@router.post(
    "/v1/trips/{trip_id}/rating",
    response_model=TripRatingRead,
    status_code=status.HTTP_201_CREATED,
)
async def rate_trip(
    trip_id: str,
    payload: TripRatingCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    result = await session.execute(
        select(Trip).where(Trip.id == trip_id, Trip.tenant_id == tenant_id)
    )
    trip = result.scalar_one_or_none()
    if trip is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Trip not found")

    if current_user.role not in _STAFF_ROLES and current_user.id != trip.driver_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the trip's own driver or a staff role may submit a rating for it",
        )
    if trip.status != TRIP_STATUS_CLOSED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A trip can only be rated after it has been closed",
        )

    row = TripRating(
        tenant_id=tenant_id,
        trip_id=trip.id,
        driver_id=trip.driver_id,
        stars=payload.stars,
        comment=payload.comment,
    )
    session.add(row)
    try:
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="This trip has already been rated"
        ) from exc
    await session.refresh(row)
    return row


@router.get("/v1/ratings", response_model=Page[TripRatingRead])
async def list_ratings(
    driver_id: str | None = Query(default=None),
    stars: int | None = Query(default=None, ge=RATING_MIN_STARS, le=RATING_MAX_STARS),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """`stars` (added alongside the pagination/summary pass) filters
    server-side rather than requiring the caller to fetch a page and filter
    it client-side — the latter used to silently disagree with `total`,
    since `total` counted every rating while the client-side filter only
    ever ran over the current page."""
    filters = [TripRating.tenant_id == tenant_id]
    if driver_id is not None:
        filters.append(TripRating.driver_id == driver_id)
    if stars is not None:
        filters.append(TripRating.stars == stars)

    count_stmt = select(func.count()).select_from(TripRating).where(*filters)
    stmt = (
        select(TripRating)
        .where(*filters)
        .order_by(TripRating.created_at.desc(), TripRating.id.desc())
        .offset(skip)
        .limit(limit)
    )
    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.get("/v1/ratings/summary", response_model=RatingsSummary)
async def ratings_summary(
    driver_id: str | None = Query(default=None),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Fleet-wide (or, with `driver_id`, single-driver) rating rollup computed
    as a real SQL aggregate over every matching row for this tenant — not the
    "compute it client-side from whatever page happened to load" convention
    `RatingsPage.tsx` used before this endpoint existed (capped at
    `GET /v1/ratings`'s own `limit<=200`, so a fleet with more than 200
    ratings had a silently wrong average and distribution). Declared as its
    own literal path ABOVE this router has no other `/v1/ratings/{...}`
    routes to collide with, but kept ordered before nothing in particular —
    unlike `app/api/v1/trips.py`'s `/earnings/today` vs `/{trip_id}`, there is
    no single-segment `GET /v1/ratings/{rating_id}` in this file for
    `/summary` to be mistaken for."""
    base_filters = [TripRating.tenant_id == tenant_id]
    if driver_id is not None:
        base_filters.append(TripRating.driver_id == driver_id)

    total_result = await session.execute(
        select(func.count(), func.avg(TripRating.stars)).where(*base_filters)
    )
    total, avg_stars = total_result.one()

    distribution_result = await session.execute(
        select(TripRating.stars, func.count())
        .where(*base_filters)
        .group_by(TripRating.stars)
    )
    distribution = {stars: 0 for stars in range(RATING_MIN_STARS, RATING_MAX_STARS + 1)}
    for stars, count in distribution_result.all():
        distribution[stars] = count

    per_driver_result = await session.execute(
        select(TripRating.driver_id, func.count(), func.avg(TripRating.stars))
        .where(*base_filters)
        .group_by(TripRating.driver_id)
    )
    by_driver = [
        RatingsSummaryDriverLine(
            driver_id=row_driver_id,
            count=count,
            average=round(float(average), 2) if average is not None else 0.0,
        )
        for row_driver_id, count, average in per_driver_result.all()
    ]
    by_driver.sort(key=lambda line: line.average)

    return RatingsSummary(
        total=total,
        average=round(float(avg_stars), 2) if avg_stars is not None else None,
        distribution=distribution,
        by_driver=by_driver,
    )
