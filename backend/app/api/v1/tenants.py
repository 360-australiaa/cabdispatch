"""Tenant domain router.

Currently owns exactly one thing: the per-tenant admin PIN that gates
destructive on-device actions (e.g. the Android app's factory-reset flow —
see au...SettingsViewModel.kt's ADMIN_PIN_PLACEHOLDER, which this replaces).

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
from app.schemas.tenant import AdminPinSetRequest, AdminPinSetResponse
from app.services import tenant as tenant_service

router = APIRouter(prefix="/v1/tenants", tags=["tenants"])

_require_owner = require_role("owner")


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


__all__ = ["router"]
