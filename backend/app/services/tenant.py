"""Tenant domain business logic.

Currently owns exactly one concern: the per-tenant admin PIN that gates
destructive on-device actions (e.g. the Android app's factory-reset flow —
see au...SettingsViewModel.kt's ADMIN_PIN_PLACEHOLDER, which this replaces).
The PIN is hashed with the same scheme as User.pin_hash
(app.core.security.hash_password/verify_password, bcrypt via passlib) and
the hash never leaves the server — see app/api/v1/tenants.py (owner sets it)
and app/api/v1/fleet.py's verify-admin-pin endpoint (a device checks it).
"""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import hash_password, verify_password
from app.models.tenant import Tenant


class TenantError(Exception):
    """Base class for tenant-domain errors; callers translate each subclass
    to the appropriate HTTP status."""


class TenantNotFoundError(TenantError):
    pass


async def get_tenant_or_404(session: AsyncSession, *, tenant_id: str) -> Tenant:
    result = await session.execute(select(Tenant).where(Tenant.id == tenant_id))
    tenant = result.scalar_one_or_none()
    if tenant is None:
        raise TenantNotFoundError(tenant_id)
    return tenant


async def set_admin_pin(session: AsyncSession, tenant: Tenant, *, pin: str) -> Tenant:
    """Hashes and stores `pin`. Overwrites any previously-set PIN (set/update
    are the same operation — there's no separate "update" endpoint)."""
    tenant.admin_pin_hash = hash_password(pin)
    await session.commit()
    await session.refresh(tenant)
    return tenant


async def check_admin_pin(session: AsyncSession, *, tenant_id: str) -> Tenant:
    """Loads the tenant for a PIN check. Separate from get_tenant_or_404 only
    in name, kept distinct so call sites read clearly; raises
    TenantNotFoundError under the same conditions."""
    return await get_tenant_or_404(session, tenant_id=tenant_id)


def verify_admin_pin(tenant: Tenant, *, pin: str) -> tuple[bool, bool]:
    """Returns (configured, valid).

    configured=False means this tenant has never set an admin PIN
    (`admin_pin_hash IS NULL`) — callers MUST surface that distinctly rather
    than reporting valid=False, which would be indistinguishable from "a PIN
    is set but this one is wrong". When configured=False, valid is always
    False too (there is nothing to match against).
    """
    if tenant.admin_pin_hash is None:
        return False, False
    return True, verify_password(pin, tenant.admin_pin_hash)


__all__ = [
    "TenantError",
    "TenantNotFoundError",
    "check_admin_pin",
    "get_tenant_or_404",
    "set_admin_pin",
    "verify_admin_pin",
]
