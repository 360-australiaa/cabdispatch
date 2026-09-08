"""Vouchers domain router — CRUD over the Voucher ledger
(app.models.vouchers.Voucher) backing the trips domain's "voucher"
Trip.payment_method (see app.services.payments.redeem_voucher).

Pydantic schemas (and the local `Page[T]` pagination generic) are defined
inline in this file rather than in a separate app/schemas/vouchers.py --
deliberately: this pass's task brief scopes edits to an explicit file list
that does not include a new schemas file. Every other domain in this tree
keeps schemas in their own app/schemas/<domain>.py (see
app/schemas/tariffs.py) and duplicates its own small `Page[T]` rather than
importing a sibling domain's; a later integration pass can split this file
the same way without changing this router's behavior.

Role gate: create/update/delete are owner/admin-only, matching
app.api.v1.tariffs's `_require_admin` convention for money-affecting writes.
List/get stay open to any authenticated tenant user, same as tariffs.

Every query filters by tenant_id via `get_current_tenant_id` — the sole
multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Generic, TypeVar

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.vouchers import Voucher

router = APIRouter(prefix="/v1/vouchers", tags=["vouchers"])

_require_admin = require_role("owner", "admin")

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- schemas --------------------------------------------------------------


class VoucherCreate(BaseModel):
    code: str = Field(min_length=1, max_length=50)
    value_aud: Decimal = Field(gt=0)
    expires_at: datetime | None = None


class VoucherUpdate(BaseModel):
    """Partial update. `code` is immutable after creation (redemption looks
    it up as the tenant-unique identity) — create a new voucher instead."""

    value_aud: Decimal | None = Field(default=None, gt=0)
    expires_at: datetime | None = None


class VoucherRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    code: str
    value_aud: Decimal
    expires_at: datetime | None
    redeemed_at: datetime | None
    redeemed_by_trip_id: str | None
    created_at: datetime
    updated_at: datetime


# --- helpers ----------------------------------------------------------------


async def _get_owned_voucher(session: AsyncSession, voucher_id: str, tenant_id: str) -> Voucher:
    result = await session.execute(
        select(Voucher).where(Voucher.id == voucher_id, Voucher.tenant_id == tenant_id)
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Voucher not found")
    return row


# --- CRUD ---------------------------------------------------------------------


@router.get("", response_model=Page[VoucherRead])
async def list_vouchers(
    redeemed: bool | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(Voucher).where(Voucher.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Voucher).where(Voucher.tenant_id == tenant_id)

    if redeemed is not None:
        condition = Voucher.redeemed_at.is_not(None) if redeemed else Voucher.redeemed_at.is_(None)
        stmt = stmt.where(condition)
        count_stmt = count_stmt.where(condition)

    stmt = stmt.order_by(Voucher.created_at.desc()).offset(skip).limit(limit)

    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.post("", response_model=VoucherRead, status_code=status.HTTP_201_CREATED)
async def create_voucher(
    payload: VoucherCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = Voucher(tenant_id=tenant_id, **payload.model_dump())
    session.add(row)
    try:
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A voucher with this code already exists for this tenant",
        ) from exc
    await session.refresh(row)
    return row


@router.get("/{voucher_id}", response_model=VoucherRead)
async def get_voucher(
    voucher_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    return await _get_owned_voucher(session, voucher_id, tenant_id)


@router.patch("/{voucher_id}", response_model=VoucherRead)
async def update_voucher(
    voucher_id: str,
    payload: VoucherUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_voucher(session, voucher_id, tenant_id)
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(row, field, value)
    await session.commit()
    await session.refresh(row)
    return row


@router.delete("/{voucher_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_voucher(
    voucher_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_voucher(session, voucher_id, tenant_id)
    await session.delete(row)
    await session.commit()
