"""Tariffs domain API.

Two routers are exported because the domain's endpoint list spans two path
prefixes:

- `router` — prefix "/v1/tariffs": full CRUD on tariffs, their extras, and
  their (read-only) change log.
- `fares_order_router` — prefix "/v1/fares-order": the single
  `GET /current` endpoint that surfaces the platform-wide (tenant_id IS NULL)
  Fares Order reference tariff.

The integration step must `app.include_router()` BOTH of these in app.main —
flagged in this domain's handoff summary.

Multi-tenancy: every query in `router` filters by `tenant_id` via
`get_current_tenant_id` (Depends), per the foundation contract. The one
deliberate exception is `fares_order_router`, which by definition reads the
tenant_id IS NULL global row and only requires the caller to be authenticated
(`get_current_user`) — there is no tenant to scope by for platform-wide
reference data.

Tariff signing (Ed25519, blueprint B6 anti-tamper): `GET /active` returns a
`signature` field over the tariff's canonical rate payload, verifiable with
the key from `GET /signing-public-key` — the second deliberate exception to
the tenant-scoping note above, since a public key isn't tenant data and
isn't secret; it has no auth dependency at all. See
app.services.tariff_signing's module docstring for the full key-management
story and wire format.
"""
from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user
from app.models.tariffs import VALID_REGIONS, Extra, Tariff, TariffChangeLog
from app.models.user import User
from app.schemas.tariffs import (
    ExtraCreate,
    ExtraRead,
    ExtraUpdate,
    Page,
    SignedTariffRead,
    TariffChangeLogRead,
    TariffCreate,
    TariffRead,
    TariffSigningPublicKeyRead,
    TariffUpdate,
)
from app.services import tariff_signing
from app.services import tariffs as tariff_service

router = APIRouter(prefix="/v1/tariffs", tags=["tariffs"])
fares_order_router = APIRouter(prefix="/v1/fares-order", tags=["tariffs"])


# --- helpers ------------------------------------------------------------------


async def _get_owned_tariff(session: AsyncSession, tariff_id: str, tenant_id: str) -> Tariff:
    result = await session.execute(
        select(Tariff).where(Tariff.id == tariff_id, Tariff.tenant_id == tenant_id)
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tariff not found")
    return row


async def _get_owned_extra(session: AsyncSession, tariff_id: str, extra_id: str, tenant_id: str) -> Extra:
    result = await session.execute(
        select(Extra).where(
            Extra.id == extra_id, Extra.tariff_id == tariff_id, Extra.tenant_id == tenant_id
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Extra not found")
    return row


# --- Tariffs: list / active / create -------------------------------------------


@router.get("", response_model=Page[TariffRead])
async def list_tariffs(
    region: str | None = Query(default=None),
    booked: bool | None = Query(default=None),
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(Tariff).where(Tariff.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Tariff).where(Tariff.tenant_id == tenant_id)

    if region is not None:
        stmt = stmt.where(Tariff.region == region)
        count_stmt = count_stmt.where(Tariff.region == region)
    if booked is not None:
        stmt = stmt.where(Tariff.booked == booked)
        count_stmt = count_stmt.where(Tariff.booked == booked)

    stmt = stmt.order_by(Tariff.effective_from.desc()).offset(skip).limit(limit)

    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.get("/active", response_model=SignedTariffRead)
async def get_active_tariff(
    region: str = Query(...),
    at: datetime | None = Query(default=None, description="ISO8601 instant; defaults to now"),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Resolves the tenant's currently-effective tariff for `region` at `at`
    (defaults to now). NOTE: registered before `/{tariff_id}` so "active"
    is never captured as a path parameter.

    The response includes an Ed25519 `signature` over the tariff's canonical
    rate payload (see app.services.tariff_signing) — this is the endpoint
    devices poll to refresh their cached tariff, so it's the one response in
    this domain that needs anti-tamper protection for the on-device
    verifier. Pair with `GET /signing-public-key` to verify it.
    """
    if region not in VALID_REGIONS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"region must be one of {VALID_REGIONS}",
        )
    resolved_at = at or datetime.now(UTC)
    row = await tariff_service.resolve_active_tariff(
        session, tenant_id=tenant_id, region=region, at=resolved_at
    )
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No effective '{region}' tariff for tenant at {resolved_at.isoformat()}",
        )
    signed_fields = TariffRead.model_validate(row).model_dump()
    return SignedTariffRead(**signed_fields, signature=tariff_signing.sign_tariff(row))


@router.get("/signing-public-key", response_model=TariffSigningPublicKeyRead)
async def get_tariff_signing_public_key():
    """The Ed25519 public key that verifies `GET /active`'s `signature`
    field (X.509 SubjectPublicKeyInfo DER, base64-encoded — see
    app.services.tariff_signing's module docstring for the full wire
    format). Deliberately NO auth dependency: public keys aren't secret, and
    devices need to be able to fetch this independent of (or before) being
    otherwise authenticated. NOTE: registered before `/{tariff_id}` so
    "signing-public-key" is never captured as a path parameter, same reason
    as `/active` above.
    """
    return TariffSigningPublicKeyRead(public_key=tariff_signing.get_signing_public_key_b64())


@router.post("", response_model=TariffRead, status_code=status.HTTP_201_CREATED)
async def create_tariff(
    payload: TariffCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    await tariff_service.validate_tariff_or_422(session, payload)

    row = Tariff(tenant_id=tenant_id, **payload.model_dump())
    session.add(row)
    await session.commit()
    await session.refresh(row)

    await tariff_service.write_change_log(session, tariff=row, actor_user_id=user.id, before=None)
    return row


# --- Tariffs: get / update / delete by id --------------------------------------


@router.get("/{tariff_id}", response_model=TariffRead)
async def get_tariff(
    tariff_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    return await _get_owned_tariff(session, tariff_id, tenant_id)


@router.patch("/{tariff_id}", response_model=TariffRead)
async def update_tariff(
    tariff_id: str,
    payload: TariffUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    row = await _get_owned_tariff(session, tariff_id, tenant_id)
    before = tariff_service.row_to_log_dict(row)  # snapshot pre-mutation

    tariff_service.apply_update(row, payload)
    await tariff_service.validate_tariff_or_422(session, row)

    await session.commit()
    await session.refresh(row)

    await tariff_service.write_change_log(session, tariff=row, actor_user_id=user.id, before=before)
    return row


@router.delete("/{tariff_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tariff(
    tariff_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    row = await _get_owned_tariff(session, tariff_id, tenant_id)
    await session.delete(row)
    await session.commit()


# --- Extras (nested under a tariff) --------------------------------------------


@router.get("/{tariff_id}/extras", response_model=Page[ExtraRead])
async def list_extras(
    tariff_id: str,
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    await _get_owned_tariff(session, tariff_id, tenant_id)  # 404s if not owned

    stmt = (
        select(Extra)
        .where(Extra.tariff_id == tariff_id, Extra.tenant_id == tenant_id)
        .order_by(Extra.name)
        .offset(skip)
        .limit(limit)
    )
    count_stmt = (
        select(func.count())
        .select_from(Extra)
        .where(Extra.tariff_id == tariff_id, Extra.tenant_id == tenant_id)
    )
    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.post("/{tariff_id}/extras", response_model=ExtraRead, status_code=status.HTTP_201_CREATED)
async def create_extra(
    tariff_id: str,
    payload: ExtraCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    await _get_owned_tariff(session, tariff_id, tenant_id)  # 404s if not owned

    row = Extra(tariff_id=tariff_id, tenant_id=tenant_id, **payload.model_dump())
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


@router.get("/{tariff_id}/extras/{extra_id}", response_model=ExtraRead)
async def get_extra(
    tariff_id: str,
    extra_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    return await _get_owned_extra(session, tariff_id, extra_id, tenant_id)


@router.patch("/{tariff_id}/extras/{extra_id}", response_model=ExtraRead)
async def update_extra(
    tariff_id: str,
    extra_id: str,
    payload: ExtraUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    row = await _get_owned_extra(session, tariff_id, extra_id, tenant_id)
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(row, field, value)
    await session.commit()
    await session.refresh(row)
    return row


@router.delete("/{tariff_id}/extras/{extra_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_extra(
    tariff_id: str,
    extra_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    row = await _get_owned_extra(session, tariff_id, extra_id, tenant_id)
    await session.delete(row)
    await session.commit()


# --- Change log: append-only, list/get only, no update/delete endpoints -------


@router.get("/{tariff_id}/change-log", response_model=Page[TariffChangeLogRead])
async def list_tariff_change_log(
    tariff_id: str,
    skip: int = Query(default=0, ge=0),
    limit: int = Query(default=50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    await _get_owned_tariff(session, tariff_id, tenant_id)  # 404s if not owned

    stmt = (
        select(TariffChangeLog)
        .where(TariffChangeLog.tariff_id == tariff_id, TariffChangeLog.tenant_id == tenant_id)
        .order_by(TariffChangeLog.at.desc())
        .offset(skip)
        .limit(limit)
    )
    count_stmt = (
        select(func.count())
        .select_from(TariffChangeLog)
        .where(TariffChangeLog.tariff_id == tariff_id, TariffChangeLog.tenant_id == tenant_id)
    )
    total = (await session.execute(count_stmt)).scalar_one()
    rows = (await session.execute(stmt)).scalars().all()
    return Page(items=list(rows), total=total, skip=skip, limit=limit)


@router.get("/{tariff_id}/change-log/{log_id}", response_model=TariffChangeLogRead)
async def get_tariff_change_log_entry(
    tariff_id: str,
    log_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    result = await session.execute(
        select(TariffChangeLog).where(
            TariffChangeLog.id == log_id,
            TariffChangeLog.tariff_id == tariff_id,
            TariffChangeLog.tenant_id == tenant_id,
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Change log entry not found")
    return row


# --- Fares Order reference (separate prefix, see module docstring) ------------


@fares_order_router.get("/current", response_model=TariffRead)
async def get_current_fares_order(
    region: str = Query(default="urban"),
    at: datetime | None = Query(default=None, description="ISO8601 instant; defaults to now"),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """Returns the platform-wide (tenant_id IS NULL) Fares Order reference
    tariff currently effective for `region`. 404s until the integration step
    seeds the global reference rows — expected in isolation."""
    if region not in VALID_REGIONS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"region must be one of {VALID_REGIONS}",
        )
    resolved_at = at or datetime.now(UTC)
    row = await tariff_service.resolve_global_reference_row(session, region=region, at=resolved_at)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No global Fares Order reference tariff seeded for region '{region}' yet",
        )
    return row
