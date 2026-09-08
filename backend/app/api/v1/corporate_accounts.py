"""Corporate accounts domain router — CRUD over the CorporateAccount ledger
(app.models.vouchers.CorporateAccount) backing the trips domain's "account"
Trip.payment_method (see app.services.payments.validate_account_reference).

Same conventions as the sibling `app/api/v1/vouchers.py` router — see that
file's module docstring for why schemas/Page are defined inline here rather
than in a separate app/schemas/corporate_accounts.py.

Role gate: create/update/delete are owner/admin-only (app.api.v1.tariffs's
`_require_admin` convention). List/get stay open to any authenticated tenant
user. Every query filters by tenant_id via `get_current_tenant_id` — the
sole multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

from datetime import datetime
from typing import Generic, TypeVar

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.models.vouchers import CorporateAccount

router = APIRouter(prefix="/v1/corporate-accounts", tags=["corporate-accounts"])

_require_admin = require_role("owner", "admin")

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- schemas --------------------------------------------------------------


class CorporateAccountCreate(BaseModel):
    reference: str = Field(min_length=1, max_length=100)
    company_name: str = Field(min_length=1, max_length=255)
    active: bool = True


class CorporateAccountUpdate(BaseModel):
    """Partial update. `reference` is immutable after creation (payment
    validation looks it up as the tenant-unique identity) — create a new
    corporate account instead."""

    company_name: str | None = Field(default=None, min_length=1, max_length=255)
    active: bool | None = None


class CorporateAccountRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    reference: str
    company_name: str
    active: bool
    created_at: datetime
    updated_at: datetime


# --- helpers ----------------------------------------------------------------


async def _get_owned_account(session: AsyncSession, account_id: str, tenant_id: str) -> CorporateAccount:
    result = await session.execute(
        select(CorporateAccount).where(
            CorporateAccount.id == account_id, CorporateAccount.tenant_id == tenant_id
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Corporate account not found")
    return row


# --- CRUD ---------------------------------------------------------------------


@router.get("", response_model=Page[CorporateAccountRead])
async def list_corporate_accounts(
    active: bool | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(CorporateAccount).where(CorporateAccount.tenant_id == tenant_id)
    count_stmt = (
        select(func.count()).select_from(CorporateAccount).where(CorporateAccount.tenant_id == tenant_id)
    )

    if active is not None:
        stmt = stmt.where(CorporateAccount.active == active)
        count_stmt = count_stmt.where(CorporateAccount.active == active)

    stmt = stmt.order_by(CorporateAccount.company_name).offset(skip).limit(limit)

    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.post("", response_model=CorporateAccountRead, status_code=status.HTTP_201_CREATED)
async def create_corporate_account(
    payload: CorporateAccountCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = CorporateAccount(tenant_id=tenant_id, **payload.model_dump())
    session.add(row)
    try:
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A corporate account with this reference already exists for this tenant",
        ) from exc
    await session.refresh(row)
    return row


@router.get("/{account_id}", response_model=CorporateAccountRead)
async def get_corporate_account(
    account_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    return await _get_owned_account(session, account_id, tenant_id)


@router.patch("/{account_id}", response_model=CorporateAccountRead)
async def update_corporate_account(
    account_id: str,
    payload: CorporateAccountUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_account(session, account_id, tenant_id)
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(row, field, value)
    await session.commit()
    await session.refresh(row)
    return row


@router.delete("/{account_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_corporate_account(
    account_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    row = await _get_owned_account(session, account_id, tenant_id)
    await session.delete(row)
    await session.commit()
