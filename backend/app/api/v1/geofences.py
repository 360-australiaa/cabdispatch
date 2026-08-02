"""Geofences domain router — admin CRUD over the circular toll/region zones
used by GPS auto-detection (see `app/services/geofence.py` +
`app/services/trips.py::apply_tick`).

Visibility vs. ownership (mirrors the nullable-tenant-id-for-global-reference
pattern `app/api/v1/tariffs.py` already uses for the Fares Order reference
tariff): `list`/`get` surface a tenant's own geofences PLUS the platform-wide
(tenant_id IS NULL) reference set (e.g. the seeded Sydney toll landmarks) —
admins need to see those to understand what's auto-charging their trips.
`create`/`update`/`delete` only ever act on tenant-owned rows; the global
reference rows are seeded outside this API (see `scripts/seed.py`), same as
the Fares Order row.

Every query filters by tenant_id (or explicitly allows tenant_id IS NULL) via
`get_current_tenant_id` — the sole multi-tenancy enforcement mechanism in
this system.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.geofence import GEOFENCE_KINDS, Geofence
from app.schemas.geofence import GeofenceCreate, GeofenceRead, GeofenceUpdate, Page

router = APIRouter(prefix="/v1/geofences", tags=["geofences"])

# Admin-only dependency reused across the write endpoints below, same pattern
# as app.api.v1.fleet._require_admin.
_require_admin = require_role("owner", "admin")


async def _get_visible_geofence(session: AsyncSession, geofence_id: str, tenant_id: str) -> Geofence:
    """Tenant-owned OR global reference — for read (GET) endpoints."""
    result = await session.execute(
        select(Geofence).where(
            Geofence.id == geofence_id,
            or_(Geofence.tenant_id == tenant_id, Geofence.tenant_id.is_(None)),
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Geofence not found")
    return row


async def _get_owned_geofence(session: AsyncSession, geofence_id: str, tenant_id: str) -> Geofence:
    """Tenant-owned only — for write (PATCH/DELETE) endpoints. A global
    reference row 404s here even though it 200s via `_get_visible_geofence`,
    same as tariffs' Fares Order row cannot be PATCHed through the tenant API."""
    result = await session.execute(
        select(Geofence).where(Geofence.id == geofence_id, Geofence.tenant_id == tenant_id)
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Geofence not found")
    return row


@router.get("", response_model=Page[GeofenceRead])
async def list_geofences(
    kind: str | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    if kind is not None and kind not in GEOFENCE_KINDS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"kind must be one of {sorted(GEOFENCE_KINDS)}",
        )

    visibility = or_(Geofence.tenant_id == tenant_id, Geofence.tenant_id.is_(None))
    stmt = select(Geofence).where(visibility)
    count_stmt = select(func.count()).select_from(Geofence).where(visibility)
    if kind is not None:
        stmt = stmt.where(Geofence.kind == kind)
        count_stmt = count_stmt.where(Geofence.kind == kind)

    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt.order_by(Geofence.name).offset(skip).limit(limit))).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.post("", response_model=GeofenceRead, status_code=status.HTTP_201_CREATED)
async def create_geofence(
    payload: GeofenceCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = Geofence(tenant_id=tenant_id, **payload.model_dump())
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


@router.get("/{geofence_id}", response_model=GeofenceRead)
async def get_geofence(
    geofence_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    return await _get_visible_geofence(session, geofence_id, tenant_id)


@router.patch("/{geofence_id}", response_model=GeofenceRead)
async def update_geofence(
    geofence_id: str,
    payload: GeofenceUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_geofence(session, geofence_id, tenant_id)

    updates = payload.model_dump(exclude_unset=True)
    new_kind = updates.get("kind", row.kind)
    new_toll_amount = updates.get("toll_amount", row.toll_amount)
    if new_kind == "toll" and new_toll_amount is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="toll_amount is required when kind='toll'",
        )

    for field, value in updates.items():
        setattr(row, field, value)

    await session.commit()
    await session.refresh(row)
    return row


@router.delete("/{geofence_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_geofence(
    geofence_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_geofence(session, geofence_id, tenant_id)
    await session.delete(row)
    await session.commit()
