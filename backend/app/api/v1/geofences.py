"""Geofences domain router — admin CRUD over the circular toll/airport/region
zones used by GPS auto-detection (see `app/services/geofence.py`,
`app/services/trips.py::apply_tick` for `kind="toll"` and
`app/services/trips.py::apply_airport_access_fee_at_start` for
`kind="airport"`).

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

Pricing is platform-admin-only (product decision, 2026 — same rule as
`app/api/v1/tariffs.py`): a `kind="toll"` row carries `toll_amount`, which is
pricing the dashboard's Tariff Studio "Toll Zones" tab manages, so writing
one (create with `kind="toll"`, or update/delete of an existing toll row)
requires `require_platform_owner` (`app/api/v1/platform.py`), not just
owner/admin. `kind="airport"` rows are pricing too — `toll_amount` is the
Sydney Airport ground-transport access fee ($6.43 GST-inclusive per pickup
at the T1/T2/T3 ranks, which the NSW Fares Order lets the driver add to the
fare) — so they sit behind exactly the same gate
(`GEOFENCE_PRICING_KINDS`). `kind="region"` rows are NOT pricing — reserved
for future tariff-zone auto-selection and not yet consumed by any service
(see `app/models/geofence.py`) — so they stay tenant owner/admin-writable,
same as before this pass.

`GET /v1/geofences/presets/airport` (any authenticated user) returns the
three Sydney Airport terminal rank circles from
`app.services.regions.nsw.SYDNEY_AIRPORT_TERMINAL_ZONES` priced at the NSW
region's `airport_access_fee`, so the dashboard's one-click "Add Sydney
Airport terminals" button and the tablet's fallback both read the same
numbers. Production is never seeded with these rows (scripts/seed.py
deliberately omits them): the platform owner creates them through this API.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.v1.platform import require_platform_owner
from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.models.geofence import GEOFENCE_KINDS, GEOFENCE_PRICING_KINDS, Geofence
from app.models.user import User
from app.schemas.geofence import (
    AirportZonePreset,
    GeofenceCreate,
    GeofenceRead,
    GeofenceUpdate,
    Page,
)
from app.services.regions import get_region
from app.services.regions.nsw import SYDNEY_AIRPORT_TERMINAL_ZONES

router = APIRouter(prefix="/v1/geofences", tags=["geofences"])

# Admin-only dependency reused across the write endpoints below, same pattern
# as app.api.v1.fleet._require_admin. Pricing-kind (toll/airport) writes
# additionally require platform-owner — see _require_platform_owner_for_pricing
# below.
_require_admin = require_role("owner", "admin")

# Same two-step composition app/api/v1/platform.py itself uses for every
# /v1/platform/... route (`_require_owner = require_role("owner")`, then
# `require_platform_owner(user=Depends(_require_owner))`) — reused here
# programmatically (not via Depends()) because whether a given write needs
# it depends on the row's/payload's `kind`, which isn't known until the
# request body (create) or the existing row (update/delete) has been read.
_require_owner_role = require_role("owner")


async def _require_platform_owner_for_pricing(user: User) -> None:
    """Toll and airport geofences carry pricing (`toll_amount`) — same
    platform-owner-only write rule as tariffs (see module docstring)."""
    await _require_owner_role(user=user)
    await require_platform_owner(user=user)


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


@router.get("/presets/airport", response_model=list[AirportZonePreset])
async def airport_zone_presets(
    _user: User = Depends(get_current_user),
):
    """Read-only Sydney Airport terminal rank presets (module docstring).
    Declared before the `/{geofence_id}` routes so "presets" is never
    mistaken for a geofence id. Any authenticated user may read it — the
    tablet's offline fallback needs it as much as the dashboard does."""
    fee = get_region("NSW").airport_access_fee
    return [AirportZonePreset(toll_amount=fee, **zone) for zone in SYDNEY_AIRPORT_TERMINAL_ZONES]


@router.post("", response_model=GeofenceRead, status_code=status.HTTP_201_CREATED)
async def create_geofence(
    payload: GeofenceCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    user: User = Depends(_require_admin),
):
    # Toll/airport pricing is platform-admin-only (module docstring); a plain
    # tenant owner/admin may still create a region-kind geofence.
    if payload.kind in GEOFENCE_PRICING_KINDS:
        await _require_platform_owner_for_pricing(user)

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
    user: User = Depends(_require_admin),
):
    row = await _get_owned_geofence(session, geofence_id, tenant_id)

    updates = payload.model_dump(exclude_unset=True)
    new_kind = updates.get("kind", row.kind)
    new_toll_amount = updates.get("toll_amount", row.toll_amount)
    if new_kind in GEOFENCE_PRICING_KINDS and new_toll_amount is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"toll_amount is required when kind='{new_kind}'",
        )

    # Toll/airport pricing is platform-admin-only (module docstring). Gate on
    # EITHER side of the change so a tenant admin can't dodge it by
    # converting an existing toll/airport row to "region" (or a region row
    # into a new priced row) -- either direction touches pricing.
    if row.kind in GEOFENCE_PRICING_KINDS or new_kind in GEOFENCE_PRICING_KINDS:
        await _require_platform_owner_for_pricing(user)

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
    user: User = Depends(_require_admin),
):
    row = await _get_owned_geofence(session, geofence_id, tenant_id)
    if row.kind in GEOFENCE_PRICING_KINDS:
        await _require_platform_owner_for_pricing(user)
    await session.delete(row)
    await session.commit()
