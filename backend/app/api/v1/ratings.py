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
* `GET /v1/ratings` -- owner/admin list (filter by driver_id) for the
  dashboard. A driver reads their own via GET /v1/me/rating.

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
from app.models.driver_engagement import TripRating
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import User
from app.schemas.driver_engagement import Page, TripRatingCreate, TripRatingRead

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
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    stmt = select(TripRating).where(TripRating.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(TripRating).where(TripRating.tenant_id == tenant_id)
    if driver_id is not None:
        stmt = stmt.where(TripRating.driver_id == driver_id)
        count_stmt = count_stmt.where(TripRating.driver_id == driver_id)

    stmt = stmt.order_by(TripRating.created_at.desc(), TripRating.id.desc()).offset(skip).limit(limit)
    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)
