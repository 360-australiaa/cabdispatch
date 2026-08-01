"""PSL (Passenger Service Levy) ledger endpoints.

Every query is filtered by `tenant_id` via `get_current_tenant_id` — the sole
multi-tenancy isolation boundary in this system.

Resources:
  - `/v1/psl/ledger` — full CRUD over the per-driver/per-period ledger rows.
  - `/v1/psl/topup` / `/v1/psl/topups` — append-only top-up transaction log:
    create + list only, no update/delete (see app.models.psl_ledger.PSLTopUp).
  - `/v1/psl/report` — read-only tenant-wide remittance aggregate for a period.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.models.psl_ledger import PSLLedgerEntry, PSLTopUp
from app.models.user import User
from app.schemas.psl_ledger import (
    PSLLedgerCreate,
    PSLLedgerRead,
    PSLLedgerUpdate,
    PSLReport,
    PSLTopUpCreate,
    PSLTopUpRead,
)
from app.services.psl_ledger import build_report, record_topup

router = APIRouter(prefix="/v1/psl", tags=["psl"])

# Roles permitted to create/edit/delete ledger rows and initiate top-ups. Any
# authenticated tenant user may read (list/get/report) — drivers included, so
# they can see their own PSL standing.
_WRITE_ROLES = ("owner", "admin", "dispatcher")


# --- PSL ledger CRUD ----------------------------------------------------------


@router.get("/ledger", response_model=list[PSLLedgerRead])
async def list_ledger_entries(
    driver_id: str | None = Query(default=None),
    period: str | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(PSLLedgerEntry).where(PSLLedgerEntry.tenant_id == tenant_id)
    if driver_id is not None:
        stmt = stmt.where(PSLLedgerEntry.driver_id == driver_id)
    if period is not None:
        stmt = stmt.where(PSLLedgerEntry.period == period)
    stmt = stmt.order_by(PSLLedgerEntry.period.desc(), PSLLedgerEntry.driver_id).offset(skip).limit(limit)

    result = await session.execute(stmt)
    return list(result.scalars().all())


@router.post("/ledger", response_model=PSLLedgerRead, status_code=status.HTTP_201_CREATED)
async def create_ledger_entry(
    payload: PSLLedgerCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(require_role(*_WRITE_ROLES)),
    session: AsyncSession = Depends(get_session),
):
    existing = await session.execute(
        select(PSLLedgerEntry).where(
            PSLLedgerEntry.tenant_id == tenant_id,
            PSLLedgerEntry.driver_id == payload.driver_id,
            PSLLedgerEntry.period == payload.period,
        )
    )
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"A PSL ledger entry already exists for driver {payload.driver_id} in {payload.period}",
        )

    entry = PSLLedgerEntry(tenant_id=tenant_id, **payload.model_dump())
    session.add(entry)
    await session.commit()
    await session.refresh(entry)
    return entry


@router.get("/ledger/{entry_id}", response_model=PSLLedgerRead)
async def get_ledger_entry(
    entry_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    entry = await _get_ledger_entry_or_404(session, entry_id=entry_id, tenant_id=tenant_id)
    return entry


@router.patch("/ledger/{entry_id}", response_model=PSLLedgerRead)
async def update_ledger_entry(
    entry_id: str,
    payload: PSLLedgerUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(require_role(*_WRITE_ROLES)),
    session: AsyncSession = Depends(get_session),
):
    entry = await _get_ledger_entry_or_404(session, entry_id=entry_id, tenant_id=tenant_id)
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(entry, field, value)
    await session.commit()
    await session.refresh(entry)
    return entry


@router.delete("/ledger/{entry_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_ledger_entry(
    entry_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(require_role(*_WRITE_ROLES)),
    session: AsyncSession = Depends(get_session),
):
    entry = await _get_ledger_entry_or_404(session, entry_id=entry_id, tenant_id=tenant_id)
    await session.delete(entry)
    await session.commit()


async def _get_ledger_entry_or_404(session: AsyncSession, *, entry_id: str, tenant_id: str) -> PSLLedgerEntry:
    result = await session.execute(
        select(PSLLedgerEntry).where(PSLLedgerEntry.id == entry_id, PSLLedgerEntry.tenant_id == tenant_id)
    )
    entry = result.scalar_one_or_none()
    if entry is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="PSL ledger entry not found")
    return entry


# --- PSL top-ups (append-only: create + list only) ---------------------------


@router.post("/topup", response_model=PSLTopUpRead, status_code=status.HTTP_201_CREATED)
async def create_topup(
    payload: PSLTopUpCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(require_role(*_WRITE_ROLES)),
    session: AsyncSession = Depends(get_session),
):
    """Records (and, with a real Stripe key configured, actually attempts) a debit
    of the driver's card for a PSL top-up. Falls back to a mock charge when no
    real Stripe key is configured (see app.services.psl_ledger.charge_driver_card).
    On a successful charge, credits the period's ledger entry's amount_collected.
    """
    driver_result = await session.execute(
        select(User).where(User.id == payload.driver_id, User.tenant_id == tenant_id)
    )
    driver = driver_result.scalar_one_or_none()
    if driver is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Driver not found in this tenant")

    topup = await record_topup(
        session,
        tenant_id=tenant_id,
        driver=driver,
        period=payload.period,
        amount=payload.amount,
        payment_method=payload.payment_method,
    )
    return topup


@router.get("/topups", response_model=list[PSLTopUpRead])
async def list_topups(
    driver_id: str | None = Query(default=None),
    period: str | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(PSLTopUp).where(PSLTopUp.tenant_id == tenant_id)
    if driver_id is not None:
        stmt = stmt.where(PSLTopUp.driver_id == driver_id)
    if period is not None:
        stmt = stmt.where(PSLTopUp.period == period)
    stmt = stmt.order_by(PSLTopUp.created_at.desc()).offset(skip).limit(limit)

    result = await session.execute(stmt)
    return list(result.scalars().all())


# --- PSL remittance report ----------------------------------------------------


@router.get("/report", response_model=PSLReport)
async def get_report(
    period: str = Query(..., pattern=r"^\d{4}-(0[1-9]|1[0-2])$"),
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    return await build_report(session, tenant_id=tenant_id, period=period)
