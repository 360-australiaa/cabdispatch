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
from app.schemas.driver_engagement import IncentiveCreate, IncentiveRead, IncentiveUpdate, Page
from app.services.driver_engagement import to_utc

router = APIRouter(prefix="/v1/incentives", tags=["incentives"])

_require_admin = require_role("owner", "admin")


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
