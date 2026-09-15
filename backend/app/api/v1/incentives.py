"""Incentives router -- operator CRUD over
app.models.driver_engagement.Incentive (the driver-tablet "Incentive
Progress" tile). Same shape and role gate as app.api.v1.vouchers: list/get
open to any authenticated tenant user, create/update/delete owner/admin only.

Progress is never stored on the incentive row: the driver-facing
GET /v1/me/incentives (app.api.v1.me) derives it from the trips table on
every read (app.services.driver_engagement.count_completed_trips_in_window).

Every query filters by tenant_id via `get_current_tenant_id` -- the sole
multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.driver_engagement import Incentive
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import User
from app.schemas.driver_engagement import (
    IncentiveCreate,
    IncentiveDriverProgressRead,
    IncentiveProgressListResponse,
    IncentiveRead,
    IncentiveUpdate,
    Page,
)
from app.services.driver_engagement import to_utc

router = APIRouter(prefix="/v1/incentives", tags=["incentives"])

_require_admin = require_role("owner", "admin")
_require_desk = require_role("owner", "admin", "dispatcher")


async def _get_owned_incentive(session: AsyncSession, incentive_id: str, tenant_id: str) -> Incentive:
    result = await session.execute(
        select(Incentive).where(Incentive.id == incentive_id, Incentive.tenant_id == tenant_id)
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Incentive not found")
    return row


@router.get("", response_model=Page[IncentiveRead])
async def list_incentives(
    active: bool | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(Incentive).where(Incentive.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Incentive).where(Incentive.tenant_id == tenant_id)
    if active is not None:
        stmt = stmt.where(Incentive.active.is_(active))
        count_stmt = count_stmt.where(Incentive.active.is_(active))

    stmt = stmt.order_by(Incentive.starts_at.desc(), Incentive.id.desc()).offset(skip).limit(limit)
    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.post("", response_model=IncentiveRead, status_code=status.HTTP_201_CREATED)
async def create_incentive(
    payload: IncentiveCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = Incentive(tenant_id=tenant_id, **payload.model_dump())
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


@router.get("/{incentive_id}", response_model=IncentiveRead)
async def get_incentive(
    incentive_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    return await _get_owned_incentive(session, incentive_id, tenant_id)


@router.get("/{incentive_id}/progress", response_model=IncentiveProgressListResponse)
async def incentive_progress(
    incentive_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _desk=Depends(_require_desk),
):
    """Every driver's progress on one incentive, computed server-side in one
    query with the SAME rule the driver tablet's GET /v1/me/incentives uses
    (app.services.driver_engagement.count_completed_trips_in_window: CLOSED
    trips whose `end_at` falls inside [starts_at, ends_at)). The dashboard
    used to re-derive this in the browser from a capped `GET /v1/trips`
    page. Every `role == "driver"` user of the tenant is listed (LEFT JOIN),
    so a driver on zero trips still appears at 0 — a leaderboard with the
    laggards missing is not one."""
    incentive = await _get_owned_incentive(session, incentive_id, tenant_id)
    starts_at = to_utc(incentive.starts_at)
    ends_at = to_utc(incentive.ends_at)
    stmt = (
        select(User.id, User.name, func.count(Trip.id))
        .outerjoin(
            Trip,
            (Trip.driver_id == User.id)
            & (Trip.tenant_id == tenant_id)
            & (Trip.status == TRIP_STATUS_CLOSED)
            & (Trip.end_at.is_not(None))
            & (Trip.end_at >= starts_at)
            & (Trip.end_at < ends_at),
        )
        .where(User.tenant_id == tenant_id, User.role == "driver")
        .group_by(User.id, User.name)
        .order_by(func.count(Trip.id).desc(), User.name, User.id)
    )
    rows = (await session.execute(stmt)).all()
    target = int(incentive.target_trips)
    return IncentiveProgressListResponse(
        items=[
            IncentiveDriverProgressRead(
                driver_id=driver_id,
                driver_name=name,
                completed_trips=int(completed),
                target_trips=target,
                earned=int(completed) >= target,
                progress_pct=min(100, (int(completed) * 100) // target) if target > 0 else 0,
                reward_aud=incentive.reward_aud,
            )
            for driver_id, name, completed in rows
        ]
    )


@router.patch("/{incentive_id}", response_model=IncentiveRead)
async def update_incentive(
    incentive_id: str,
    payload: IncentiveUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_incentive(session, incentive_id, tenant_id)
    changes = payload.model_dump(exclude_unset=True)
    # to_utc on both sides: sqlite hands back naive datetimes for existing
    # rows while the schema has already normalised incoming ones to aware UTC.
    new_starts = to_utc(changes.get("starts_at", row.starts_at))
    new_ends = to_utc(changes.get("ends_at", row.ends_at))
    if new_ends <= new_starts:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="ends_at must be after starts_at"
        )
    for field, value in changes.items():
        setattr(row, field, value)
    await session.commit()
    await session.refresh(row)
    return row


@router.delete("/{incentive_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_incentive(
    incentive_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_incentive(session, incentive_id, tenant_id)
    await session.delete(row)
    await session.commit()
