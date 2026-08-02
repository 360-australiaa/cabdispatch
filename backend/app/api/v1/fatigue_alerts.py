"""Fatigue-alerts domain router — `/v1/fatigue-alerts` (blueprint 12.3).

List + acknowledge only — alerts themselves are raised server-side by
`app.services.fatigue`, wired into `PATCH /v1/trips/{id}/tick` (see that
router), never created directly through this API.

Every query is filtered by the tenant_id resolved via `get_current_tenant_id`
— the sole multi-tenancy isolation boundary in this codebase.

Role policy: list/acknowledge are restricted to `owner`/`admin`/`dispatcher`
(same "dispatch-side roles" set used by the sibling `duress` domain) — a
driver being fatigued or speeding is an ops/safety concern for the operator
to see and act on, not something the driver's own device needs to read back.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.fatigue_alert import FATIGUE_ALERT_KINDS, FatigueAlert
from app.schemas.fatigue_alert import FatigueAlertAcknowledge, FatigueAlertRead, Page

router = APIRouter(prefix="/v1/fatigue-alerts", tags=["fatigue-alerts"])

# Roles permitted to view/acknowledge fatigue alerts — mirrors app.api.v1.duress's
# _DISPATCH_ROLES.
_DISPATCH_ROLES = ("owner", "admin", "dispatcher")
_require_dispatch = require_role(*_DISPATCH_ROLES)


async def _get_alert_or_404(session: AsyncSession, *, tenant_id: str, alert_id: str) -> FatigueAlert:
    result = await session.execute(
        select(FatigueAlert).where(FatigueAlert.id == alert_id, FatigueAlert.tenant_id == tenant_id)
    )
    alert = result.scalar_one_or_none()
    if alert is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Fatigue alert not found")
    return alert


@router.get("", response_model=Page[FatigueAlertRead])
async def list_fatigue_alerts(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    driver_id: str | None = Query(default=None),
    shift_id: str | None = Query(default=None),
    kind: str | None = Query(default=None),
    acknowledged: bool | None = Query(default=None),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _dispatch=Depends(_require_dispatch),
):
    if kind is not None and kind not in FATIGUE_ALERT_KINDS:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid kind filter")

    stmt = select(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id)

    if driver_id is not None:
        stmt = stmt.where(FatigueAlert.driver_id == driver_id)
        count_stmt = count_stmt.where(FatigueAlert.driver_id == driver_id)
    if shift_id is not None:
        stmt = stmt.where(FatigueAlert.shift_id == shift_id)
        count_stmt = count_stmt.where(FatigueAlert.shift_id == shift_id)
    if kind is not None:
        stmt = stmt.where(FatigueAlert.kind == kind)
        count_stmt = count_stmt.where(FatigueAlert.kind == kind)
    if acknowledged is not None:
        stmt = stmt.where(FatigueAlert.acknowledged == acknowledged)
        count_stmt = count_stmt.where(FatigueAlert.acknowledged == acknowledged)

    total = (await session.execute(count_stmt)).scalar_one()
    result = await session.execute(stmt.order_by(FatigueAlert.triggered_at.desc()).offset(skip).limit(limit))
    items = result.scalars().all()
    return Page[FatigueAlertRead](items=items, total=total, skip=skip, limit=limit)


@router.get("/{alert_id}", response_model=FatigueAlertRead)
async def get_fatigue_alert(
    alert_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _dispatch=Depends(_require_dispatch),
):
    return await _get_alert_or_404(session, tenant_id=tenant_id, alert_id=alert_id)


@router.post("/{alert_id}/acknowledge", response_model=FatigueAlertRead)
async def acknowledge_fatigue_alert(
    alert_id: str,
    payload: FatigueAlertAcknowledge,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _dispatch=Depends(_require_dispatch),
):
    alert = await _get_alert_or_404(session, tenant_id=tenant_id, alert_id=alert_id)
    alert.acknowledged = payload.acknowledged
    await session.commit()
    await session.refresh(alert)
    return alert
