"""Shifts domain API — `/v1/shifts`.

Full CRUD plus the two domain-specific lifecycle actions (`/start`, `/{id}/end`)
and the report endpoint. Every query is filtered by the tenant_id resolved via
`get_current_tenant_id` — this is the sole multi-tenancy isolation boundary in
this codebase, per the shared foundation contract.

Role policy: any authenticated user of the tenant (including `driver`, who owns
the device app that opens/closes their own shifts) may start/end a shift and
read shift data. Direct field-level corrections (`PATCH`) and deletion are
restricted to `owner`/`admin`/`dispatcher` — a driver should not be able to
rewrite their own reconciliation figures after the fact.
"""
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.models.shift import Shift
from app.models.user import User
from app.schemas.shift import (
    ShiftCreate,
    ShiftEnd,
    ShiftListResponse,
    ShiftRead,
    ShiftReport,
    ShiftStart,
    ShiftUpdate,
)
from app.services.shift import ShiftConflictError, build_report, end_shift, start_shift

router = APIRouter(prefix="/v1/shifts", tags=["shifts"])


async def _get_owned_shift(session: AsyncSession, *, tenant_id: str, shift_id: str) -> Shift:
    result = await session.execute(
        select(Shift).where(Shift.id == shift_id, Shift.tenant_id == tenant_id)
    )
    shift = result.scalar_one_or_none()
    if shift is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Shift not found")
    return shift


# --- lifecycle actions -------------------------------------------------------


@router.post("/start", response_model=ShiftRead, status_code=status.HTTP_201_CREATED)
async def start(
    body: ShiftStart,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Shift:
    """Opens a new shift, capturing the pre-shift inspection checklist.

    Guards against two drivers ever simultaneously "having" the same
    vehicle on paper (see app.services.shift.start_shift's own docstring):
    a driver's own dangling open shift is auto-closed, but a DIFFERENT
    driver's open shift on this vehicle blocks the request with 409 unless
    `force_handover: true` is set — a real shift changeover, not a silent
    takeover.
    """
    try:
        return await start_shift(
            session,
            tenant_id=tenant_id,
            driver_id=body.driver_id,
            vehicle_id=body.vehicle_id,
            start_at=body.start_at,
            inspection_json=body.inspection_json,
            force_handover=body.force_handover,
        )
    except ShiftConflictError as exc:
        conflicting = exc.conflicting_shift
        driver_name_result = await session.execute(
            select(User.name).where(User.id == conflicting.driver_id, User.tenant_id == tenant_id)
        )
        driver_name = driver_name_result.scalar_one_or_none() or conflicting.driver_id
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "message": (
                    f"This vehicle already has an open shift for {driver_name}, "
                    f"started {conflicting.start_at.isoformat()}. Set force_handover=true "
                    "to end their shift and start yours."
                ),
                "conflicting_shift_id": conflicting.id,
                "conflicting_driver_id": conflicting.driver_id,
                "conflicting_driver_name": driver_name,
                "conflicting_shift_start_at": conflicting.start_at.isoformat(),
            },
        ) from exc


@router.post("/{shift_id}/end", response_model=ShiftRead)
async def end(
    shift_id: str,
    body: ShiftEnd,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Shift:
    """Closes a shift: recomputes trips_count/km_total/cash_total/card_total by
    aggregating the shift's own trips, and records the supplied reconciliation
    figures (psl_owed, reconciled)."""
    shift = await _get_owned_shift(session, tenant_id=tenant_id, shift_id=shift_id)
    if shift.end_at is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Shift is already closed"
        )
    return await end_shift(
        session,
        shift,
        end_at=body.end_at,
        psl_owed=body.psl_owed,
        reconciled=body.reconciled,
    )


@router.get("/{shift_id}/report", response_model=ShiftReport)
async def report(
    shift_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """JSON summary of the shift. TODO(reporting): PDF/CSV export — see
    app.services.shift.build_report docstring; no PDF library in this pass."""
    shift = await _get_owned_shift(session, tenant_id=tenant_id, shift_id=shift_id)
    return build_report(shift)


# --- standard CRUD ------------------------------------------------------------


@router.get("", response_model=ShiftListResponse)
async def list_shifts(
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    driver_id: str | None = Query(default=None),
    vehicle_id: str | None = Query(default=None),
    reconciled: bool | None = Query(default=None),
    active_only: bool = Query(
        default=False, description="If true, only return shifts with end_at IS NULL."
    ),
    start_at_from: datetime | None = Query(default=None),
    start_at_to: datetime | None = Query(default=None),
) -> dict:
    filters = [Shift.tenant_id == tenant_id]
    if driver_id is not None:
        filters.append(Shift.driver_id == driver_id)
    if vehicle_id is not None:
        filters.append(Shift.vehicle_id == vehicle_id)
    if reconciled is not None:
        filters.append(Shift.reconciled == reconciled)
    if active_only:
        filters.append(Shift.end_at.is_(None))
    if start_at_from is not None:
        filters.append(Shift.start_at >= start_at_from)
    if start_at_to is not None:
        filters.append(Shift.start_at <= start_at_to)

    total = (
        await session.execute(select(func.count(Shift.id)).where(*filters))
    ).scalar_one()

    result = await session.execute(
        select(Shift)
        .where(*filters)
        .order_by(Shift.start_at.desc())
        .limit(limit)
        .offset(offset)
    )
    items = result.scalars().all()

    return {"items": items, "total": total, "limit": limit, "offset": offset}


@router.post("", response_model=ShiftRead, status_code=status.HTTP_201_CREATED)
async def create_shift(
    body: ShiftCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role("owner", "admin", "dispatcher")),
    session: AsyncSession = Depends(get_session),
) -> Shift:
    """Generic create for CRUD completeness/admin backfill. The normal path for
    opening a shift is `POST /v1/shifts/start`."""
    shift = Shift(tenant_id=tenant_id, **body.model_dump())
    session.add(shift)
    await session.commit()
    await session.refresh(shift)
    return shift


@router.get("/{shift_id}", response_model=ShiftRead)
async def get_shift(
    shift_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Shift:
    return await _get_owned_shift(session, tenant_id=tenant_id, shift_id=shift_id)


@router.patch("/{shift_id}", response_model=ShiftRead)
async def update_shift(
    shift_id: str,
    body: ShiftUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role("owner", "admin", "dispatcher")),
    session: AsyncSession = Depends(get_session),
) -> Shift:
    shift = await _get_owned_shift(session, tenant_id=tenant_id, shift_id=shift_id)
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(shift, field, value)
    await session.commit()
    await session.refresh(shift)
    return shift


@router.delete("/{shift_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_shift(
    shift_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role("owner", "admin", "dispatcher")),
    session: AsyncSession = Depends(get_session),
) -> None:
    shift = await _get_owned_shift(session, tenant_id=tenant_id, shift_id=shift_id)
    await session.delete(shift)
    await session.commit()
