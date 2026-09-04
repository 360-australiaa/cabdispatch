"""Announcements router -- operator CRUD over
app.models.driver_engagement.Announcement (the driver-tablet "Announcements"
tile). Same shape and role gate as app.api.v1.vouchers: list/get open to
any authenticated tenant user, create/update/delete owner/admin only.

This is the full (all rows, any window) operator list; the driver-facing
"what's live right now" list is GET /v1/me/announcements (app.api.v1.me).

Every query filters by tenant_id via `get_current_tenant_id` -- the sole
multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.driver_engagement import Announcement
from app.schemas.driver_engagement import (
    AnnouncementCreate,
    AnnouncementRead,
    AnnouncementUpdate,
    Page,
)
from app.services.driver_engagement import to_utc

router = APIRouter(prefix="/v1/announcements", tags=["announcements"])

_require_admin = require_role("owner", "admin")


async def _get_owned_announcement(
    session: AsyncSession, announcement_id: str, tenant_id: str
) -> Announcement:
    result = await session.execute(
        select(Announcement).where(
            Announcement.id == announcement_id, Announcement.tenant_id == tenant_id
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Announcement not found")
    return row


@router.get("", response_model=Page[AnnouncementRead])
async def list_announcements(
    active: bool | None = Query(default=None),
    kind: str | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(Announcement).where(Announcement.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Announcement).where(Announcement.tenant_id == tenant_id)
    if active is not None:
        stmt = stmt.where(Announcement.active.is_(active))
        count_stmt = count_stmt.where(Announcement.active.is_(active))
    if kind is not None:
        stmt = stmt.where(Announcement.kind == kind)
        count_stmt = count_stmt.where(Announcement.kind == kind)

    stmt = stmt.order_by(Announcement.starts_at.desc(), Announcement.id.desc()).offset(skip).limit(limit)
    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.post("", response_model=AnnouncementRead, status_code=status.HTTP_201_CREATED)
async def create_announcement(
    payload: AnnouncementCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = Announcement(tenant_id=tenant_id, **payload.model_dump())
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


@router.get("/{announcement_id}", response_model=AnnouncementRead)
async def get_announcement(
    announcement_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    return await _get_owned_announcement(session, announcement_id, tenant_id)


@router.patch("/{announcement_id}", response_model=AnnouncementRead)
async def update_announcement(
    announcement_id: str,
    payload: AnnouncementUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_announcement(session, announcement_id, tenant_id)
    changes = payload.model_dump(exclude_unset=True)
    # to_utc on both sides: sqlite hands back naive datetimes for existing
    # rows while the schema has already normalised incoming ones to aware UTC.
    new_starts = to_utc(changes.get("starts_at", row.starts_at))
    new_ends = changes.get("ends_at", row.ends_at)
    if new_ends is not None and to_utc(new_ends) <= new_starts:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="ends_at must be after starts_at"
        )
    for field, value in changes.items():
        setattr(row, field, value)
    await session.commit()
    await session.refresh(row)
    return row


@router.delete("/{announcement_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_announcement(
    announcement_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_announcement(session, announcement_id, tenant_id)
    await session.delete(row)
    await session.commit()
