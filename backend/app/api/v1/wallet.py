"""Wallet router -- operator side of the driver wallet ledger
(app.models.driver_engagement.WalletTransaction).

owner/admin only (mirrors app.api.v1.vouchers' `require_role("owner",
"admin")` gate for money-affecting writes; here even the reads are gated,
because they expose *another* driver's money -- a driver reads their own
via GET /v1/me/wallet in app.api.v1.me).

Balances are never stored: `GET /v1/wallet/drivers/{driver_id}` derives
the balance as SUM(amount_aud) on every read -- see
app.services.driver_engagement.wallet_balance.

Every query filters by tenant_id via `get_current_tenant_id` -- the sole
multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.driver_engagement import WalletTransaction
from app.models.user import User
from app.schemas.driver_engagement import (
    Page,
    WalletRead,
    WalletTransactionCreate,
    WalletTransactionRead,
)
from app.services import driver_engagement as svc

router = APIRouter(prefix="/v1/wallet", tags=["wallet"])

_require_admin = require_role("owner", "admin")


async def _get_tenant_driver_or_404(session: AsyncSession, driver_id: str, tenant_id: str) -> User:
    """A wallet line may only be posted against a user that belongs to the
    caller's tenant -- otherwise a tenant admin could credit/debit a driver
    of a different network by guessing their user id."""
    result = await session.execute(
        select(User).where(User.id == driver_id, User.tenant_id == tenant_id)
    )
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Driver not found")
    return user


@router.get("/transactions", response_model=Page[WalletTransactionRead])
async def list_wallet_transactions(
    driver_id: str | None = Query(default=None),
    kind: str | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    stmt = select(WalletTransaction).where(WalletTransaction.tenant_id == tenant_id)
    count_stmt = (
        select(func.count())
        .select_from(WalletTransaction)
        .where(WalletTransaction.tenant_id == tenant_id)
    )
    if driver_id is not None:
        stmt = stmt.where(WalletTransaction.driver_id == driver_id)
        count_stmt = count_stmt.where(WalletTransaction.driver_id == driver_id)
    if kind is not None:
        stmt = stmt.where(WalletTransaction.kind == kind)
        count_stmt = count_stmt.where(WalletTransaction.kind == kind)

    stmt = (
        stmt.order_by(WalletTransaction.created_at.desc(), WalletTransaction.id.desc())
        .offset(skip)
        .limit(limit)
    )
    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.post(
    "/transactions", response_model=WalletTransactionRead, status_code=status.HTTP_201_CREATED
)
async def create_wallet_transaction(
    payload: WalletTransactionCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    admin: User = Depends(_require_admin),
):
    """Post a top_up / adjustment / payout line to a driver's ledger. The
    sign rules live on WalletTransactionCreate; `trip_earning` is reserved
    for system-generated rows and is rejected here by the schema's Literal."""
    await _get_tenant_driver_or_404(session, payload.driver_id, tenant_id)
    row = WalletTransaction(
        tenant_id=tenant_id,
        driver_id=payload.driver_id,
        amount_aud=payload.amount_aud,
        kind=payload.kind,
        reference=payload.reference,
        note=payload.note,
        created_by_user_id=admin.id,
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


@router.get("/drivers/{driver_id}", response_model=WalletRead)
async def get_driver_wallet(
    driver_id: str,
    limit: int = Query(default=50, ge=1, le=200, description="How many recent ledger lines"),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Operator view of one driver's wallet: derived balance + recent lines.
    Same shape as GET /v1/me/wallet."""
    await _get_tenant_driver_or_404(session, driver_id, tenant_id)
    balance = await svc.wallet_balance(session, tenant_id=tenant_id, driver_id=driver_id)
    recent = await svc.recent_wallet_transactions(
        session, tenant_id=tenant_id, driver_id=driver_id, limit=limit
    )
    return WalletRead(driver_id=driver_id, balance_aud=balance, recent=recent)
