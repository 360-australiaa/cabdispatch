"""Driver-facing "me" router -- the four driver-tablet dashboard tiles:
Wallet Balance, Driver Rating, Announcements, Incentive Progress.

Open to any authenticated tenant user, but every read is scoped to the
CALLER'S OWN driver id, resolved from the bearer token's user via
`app.services.driver_engagement.resolve_driver_id` -- never a query param --
so a driver cannot read another driver's wallet/rating/progress. Operators
wanting another driver's view use the owner/admin routers instead
(app.api.v1.wallet / ratings).

Every query additionally filters by tenant_id via `get_current_tenant_id`
-- the sole multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user
from app.models.user import User
from app.schemas.driver_engagement import (
    AnnouncementListRead,
    IncentiveProgressListRead,
    IncentiveProgressRead,
    RatingRead,
    WalletRead,
)
from app.services import driver_engagement as svc

router = APIRouter(prefix="/v1/me", tags=["me"])


@router.get("/wallet", response_model=WalletRead)
async def my_wallet(
    limit: int = Query(default=20, ge=1, le=100, description="How many recent ledger lines"),
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    driver_id = svc.resolve_driver_id(current_user)
    balance = await svc.wallet_balance(session, tenant_id=tenant_id, driver_id=driver_id)
    recent = await svc.recent_wallet_transactions(
        session, tenant_id=tenant_id, driver_id=driver_id, limit=limit
    )
    return WalletRead(driver_id=driver_id, balance_aud=balance, recent=recent)


@router.get("/rating", response_model=RatingRead)
async def my_rating(
    limit: int = Query(default=10, ge=1, le=100, description="How many recent ratings"),
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    driver_id = svc.resolve_driver_id(current_user)
    summary = await svc.driver_rating(session, tenant_id=tenant_id, driver_id=driver_id)
    recent = await svc.recent_trip_ratings(session, tenant_id=tenant_id, driver_id=driver_id, limit=limit)
    return RatingRead(
        driver_id=driver_id,
        average_stars=summary.average_stars,
        rating_count=summary.rating_count,
        recent=recent,
    )


@router.get("/announcements", response_model=AnnouncementListRead)
async def my_announcements(
    tenant_id: str = Depends(get_current_tenant_id),
    _current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    items = await svc.list_live_announcements(session, tenant_id=tenant_id)
    return AnnouncementListRead(items=items)


@router.get("/incentives", response_model=IncentiveProgressListRead)
async def my_incentives(
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    driver_id = svc.resolve_driver_id(current_user)
    live = await svc.list_live_incentives(session, tenant_id=tenant_id)
    items: list[IncentiveProgressRead] = []
    for incentive in live:
        progress = await svc.incentive_progress_for_driver(
            session, tenant_id=tenant_id, driver_id=driver_id, incentive=incentive
        )
        items.append(
            IncentiveProgressRead(
                id=incentive.id,
                tenant_id=incentive.tenant_id,
                title=incentive.title,
                description=incentive.description,
                target_trips=incentive.target_trips,
                reward_aud=incentive.reward_aud,
                starts_at=incentive.starts_at,
                ends_at=incentive.ends_at,
                active=incentive.active,
                created_at=incentive.created_at,
                updated_at=incentive.updated_at,
                completed_trips=progress.completed_trips,
                remaining_trips=progress.remaining_trips,
                progress_pct=progress.progress_pct,
                achieved=progress.achieved,
            )
        )
    return IncentiveProgressListRead(items=items)
