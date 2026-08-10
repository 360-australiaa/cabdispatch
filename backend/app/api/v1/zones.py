"""Zones domain API -- `/v1/zones`.

Named dispatch zones with a driver-facing short code, "plot into a zone"
(a driver marking themselves as actively waiting in a specific zone, distinct
from just being on shift), and a live per-zone demand-stats screen -- all
matching a screen on a real competitor taxi meter (MTI). See
`app.services.zones` for the full business logic and its documented
simplifications.

Every query is filtered by tenant_id resolved via `get_current_tenant_id` --
the sole multi-tenancy isolation mechanism in this system.

Role policy:
  - `GET /`, `GET /{id}`, `GET /stats`: any authenticated tenant user --
    dispatchers AND drivers both need to see the zone list/stats (a driver
    picks a zone to plot into from this same list).
  - `POST /`, `PUT /{id}`, `DELETE /{id}`: `owner`/`admin` only, same
    role restriction as the sibling `geofences` domain's admin CRUD.
  - `POST /{id}/plot`, `POST /unplot`: any authenticated tenant user -- a
    driver acting on their own current shift (identity-scoped via
    `get_current_user`, not restricted by role), same pattern as
    `POST /v1/jobs/availability`.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.schemas.zones import (
    Page,
    ZoneCreate,
    ZonePlotRead,
    ZoneRead,
    ZoneStats,
    ZoneUpdate,
)
from app.services import zones as zones_service

router = APIRouter(prefix="/v1/zones", tags=["zones"])

# Admin-only dependency reused across the write endpoints below, same pattern
# as app.api.v1.geofences._require_admin.
_require_admin = require_role("owner", "admin")


def _zones_error_to_http(exc: zones_service.ZonesError) -> HTTPException:
    if isinstance(exc, zones_service.ZoneNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Zone not found")
    if isinstance(exc, zones_service.ZoneNumberTakenError):
        return HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Zone number '{exc}' is already in use for this tenant",
        )
    if isinstance(exc, zones_service.NoActiveShiftError):
        return HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="You have no currently-open shift to plot",
        )
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


def _shift_to_plot_read(shift) -> ZonePlotRead:
    return ZonePlotRead(
        shift_id=shift.id,
        driver_id=shift.driver_id,
        vehicle_id=shift.vehicle_id,
        plotted_zone_id=shift.plotted_zone_id,
        plotted_at=shift.plotted_at,
    )


# ==================================================================================
# Live stats -- registered before "/{zone_id}" so "stats" doesn't get captured
# as a zone_id path param.
# ==================================================================================


@router.get("/stats", response_model=list[ZoneStats])
async def get_zone_stats(
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    return await zones_service.compute_zone_stats(session, tenant_id=tenant_id)


# ==================================================================================
# Plot / unplot -- registered before "/{zone_id}/..." collision is fine since
# "/unplot" has no zone_id segment, but keep both here together for readability.
# ==================================================================================


@router.post("/unplot", response_model=ZonePlotRead)
async def unplot(
    tenant_id: str = Depends(get_current_tenant_id),
    user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        shift = await zones_service.unplot(session, tenant_id=tenant_id, driver_id=user.id)
    except zones_service.ZonesError as exc:
        raise _zones_error_to_http(exc) from exc
    return _shift_to_plot_read(shift)


@router.post("/{zone_id}/plot", response_model=ZonePlotRead)
async def plot(
    zone_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        shift = await zones_service.plot_into_zone(
            session, tenant_id=tenant_id, driver_id=user.id, zone_id=zone_id
        )
    except zones_service.ZonesError as exc:
        raise _zones_error_to_http(exc) from exc
    return _shift_to_plot_read(shift)


# ==================================================================================
# CRUD
# ==================================================================================


@router.get("", response_model=Page[ZoneRead])
async def list_zones(
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    items, total = await zones_service.list_zones(session, tenant_id=tenant_id, skip=skip, limit=limit)
    return Page[ZoneRead](items=items, total=total, skip=skip, limit=limit)


@router.post("", response_model=ZoneRead, status_code=status.HTTP_201_CREATED)
async def create_zone(
    payload: ZoneCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        return await zones_service.create_zone(session, tenant_id=tenant_id, **payload.model_dump())
    except zones_service.ZonesError as exc:
        raise _zones_error_to_http(exc) from exc


@router.get("/{zone_id}", response_model=ZoneRead)
async def get_zone(
    zone_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await zones_service.get_zone_or_404(session, tenant_id=tenant_id, zone_id=zone_id)
    except zones_service.ZonesError as exc:
        raise _zones_error_to_http(exc) from exc


@router.put("/{zone_id}", response_model=ZoneRead)
async def update_zone(
    zone_id: str,
    payload: ZoneUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        zone = await zones_service.get_zone_or_404(session, tenant_id=tenant_id, zone_id=zone_id)
        return await zones_service.update_zone(session, zone, **payload.model_dump())
    except zones_service.ZonesError as exc:
        raise _zones_error_to_http(exc) from exc


@router.delete("/{zone_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_zone(
    zone_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        zone = await zones_service.get_zone_or_404(session, tenant_id=tenant_id, zone_id=zone_id)
    except zones_service.ZonesError as exc:
        raise _zones_error_to_http(exc) from exc
    await zones_service.delete_zone(session, zone)