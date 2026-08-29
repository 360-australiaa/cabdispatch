"""Tenant domain router.

Owns the per-tenant admin PIN that gates destructive on-device actions (e.g. the Android app's
factory-reset flow — see au...SettingsViewModel.kt's ADMIN_PIN_PLACEHOLDER, which this replaces),
plus a "current tenant" self-service surface (`GET`/`PATCH /v1/tenants/me`) for reading tenant
identity and updating white-label branding (blueprint 7.2.10/9.1/13.1) — the dashboard's
White-label Settings page (`dashboard/src/pages/settings/white-label/`) has called these exact
paths since it was first built, but the endpoints themselves never landed until now (see that
page's own `useWhite-labelSettings.ts` doc comment, which explicitly flagged the 404 this closes).

Only an owner can set/update the PIN, and only for their own tenant (path
tenant_id is checked against the token-resolved tenant scope from
get_current_tenant_id, same as every other domain router — see
app.core.security). The hash itself is never returned to any caller,
including the owner who just set it — a device checks a PIN via
`POST /v1/fleet/devices/{id}/verify-admin-pin` (see app/api/v1/fleet.py)
instead, which returns only a bool.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, require_role
from app.schemas.tenant import AdminPinSetRequest, AdminPinSetResponse, TenantRead, TenantThemeUpdate
from app.schemas.tenant_settings import TenantSettingsRead, TenantSettingsUpdate
from app.services import tenant as tenant_service
from app.services import tenant_settings as tenant_settings_service

router = APIRouter(prefix="/v1/tenants", tags=["tenants"])

_require_owner = require_role("owner")
_require_owner_or_admin = require_role("owner", "admin")


@router.get("/me", response_model=TenantRead)
async def get_my_tenant(
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Any authenticated tenant user (view-only for non-owner/admin roles — the dashboard's own
    White-label page gates editing client-side on `role === "owner" || "admin"`, matching
    `_require_owner_or_admin`'s PATCH gate below)."""
    try:
        return await tenant_service.get_tenant_or_404(session, tenant_id=tenant_id)
    except tenant_service.TenantError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found") from exc


@router.patch("/me", response_model=TenantRead)
async def update_my_tenant_theme(
    payload: TenantThemeUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _owner_or_admin=Depends(_require_owner_or_admin),
):
    """Owner/admin-only. Updates white-label branding — see `TenantThemeUpdate`'s own doc for why
    this always overwrites `theme_json` wholesale (including resetting it to `null`) rather than
    merging a partial update."""
    try:
        tenant = await tenant_service.get_tenant_or_404(session, tenant_id=tenant_id)
    except tenant_service.TenantError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found") from exc

    theme_json = payload.theme_json.model_dump() if payload.theme_json is not None else None
    return await tenant_service.update_theme(session, tenant, theme_json=theme_json)


@router.post("/{tenant_id}/admin-pin", response_model=AdminPinSetResponse)
async def set_admin_pin(
    tenant_id: str,
    payload: AdminPinSetRequest,
    resolved_tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _owner=Depends(_require_owner),
):
    """Owner-only. Sets/updates the tenant's admin PIN, hashed with the same
    scheme as User.pin_hash (see app.core.security.hash_password). Setting it
    again overwrites the previous PIN — there is no separate update route."""
    if tenant_id != resolved_tenant_id:
        # Defense in depth: get_current_tenant_id already hard-locks non-
        # platform-owner tokens to their own tenant_id, so this only fires
        # for a mismatched path id (or a platform-owner token that didn't
        # pass ?tenant_id= to select this tenant). Either way, refuse rather
        # than silently acting on the resolved tenant instead of the one in
        # the URL.
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Cannot set the admin PIN for a different tenant",
        )

    try:
        tenant = await tenant_service.get_tenant_or_404(session, tenant_id=tenant_id)
    except tenant_service.TenantError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found") from exc

    tenant = await tenant_service.set_admin_pin(session, tenant, pin=payload.pin)
    return AdminPinSetResponse(tenant_id=tenant.id, admin_pin_configured=True)


@router.get("/me/settings", response_model=TenantSettingsRead)
async def get_my_tenant_settings(
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Any authenticated tenant user (view-only) -- same role-gating precedent as
    `GET /v1/tenants/me` above. Returns the tenant's row if one exists (the normal
    case -- see WP-04's migration backfill and WP-17's tenant-creation flow), or a
    defensively-created one with documented defaults for a tenant that predates
    both (see app.services.tenant_settings' module doc)."""
    settings_row = await tenant_settings_service.get_or_create_settings(session, tenant_id=tenant_id)
    return settings_row


@router.patch("/me/settings", response_model=TenantSettingsRead)
async def update_my_tenant_settings(
    payload: TenantSettingsUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _owner_or_admin=Depends(_require_owner_or_admin),
):
    """Owner/admin-only -- same role-gating precedent as `PATCH /v1/tenants/me`
    above. Partial update: only fields present in the request body are changed."""
    settings_row = await tenant_settings_service.get_or_create_settings(session, tenant_id=tenant_id)
    changes = payload.model_dump(exclude_unset=True)
    return await tenant_settings_service.update_settings(session, settings_row, changes=changes)


__all__ = ["router"]
