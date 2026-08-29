"""Tenant-settings domain business logic (WP-01, D-7 in
docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md). Kept in its own module rather than
folded into app.services.tenant -- that module owns the admin-PIN/white-label
concerns; this one owns the per-tenant override values that used to be
deployment-wide env vars in app.core.config (fatigue threshold, compliance-expiry
warning window, duress escalation numbers).

Primary path: the WP-04 integrator migration backfills one TenantSettings row per
existing tenant directly in its upgrade(), and new tenants get one created alongside
them (see app.services.platform, WP-17). get_or_create_settings below is a
DEFENSIVE FALLBACK for that primary path, not the intended way rows come into
existence -- it exists so this endpoint still behaves correctly (returns the
documented defaults) for any tenant that somehow has no row yet, e.g. in tests that
create a tenant directly via the ORM without going through the full onboarding flow.
"""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.tenant_settings import (
    DEFAULT_COMPLIANCE_EXPIRY_WARNING_DAYS,
    DEFAULT_FATIGUE_SHIFT_DURATION_LIMIT_HOURS,
    TenantSettings,
)


async def get_or_create_settings(session: AsyncSession, *, tenant_id: str) -> TenantSettings:
    """Loads the tenant's settings row, creating one with documented defaults if it
    does not exist yet (defensive fallback -- see module docstring). Uses the same
    defaults as the model's own server_default values, so a defensively-created row
    is indistinguishable from a migration-backfilled one."""
    result = await session.execute(
        select(TenantSettings).where(TenantSettings.tenant_id == tenant_id)
    )
    settings_row = result.scalar_one_or_none()
    if settings_row is not None:
        return settings_row

    settings_row = TenantSettings(
        tenant_id=tenant_id,
        fatigue_shift_duration_limit_hours=DEFAULT_FATIGUE_SHIFT_DURATION_LIMIT_HOURS,
        compliance_expiry_warning_days=DEFAULT_COMPLIANCE_EXPIRY_WARNING_DAYS,
    )
    session.add(settings_row)
    await session.commit()
    await session.refresh(settings_row)
    return settings_row


async def update_settings(
    session: AsyncSession, settings_row: TenantSettings, *, changes: dict
) -> TenantSettings:
    """Applies only the explicitly-supplied fields in `changes` (the router passes
    `payload.model_dump(exclude_unset=True)`) -- an omitted field is left untouched,
    an explicit `null` for one of the two nullable phone fields clears it."""
    for field, value in changes.items():
        setattr(settings_row, field, value)
    await session.commit()
    await session.refresh(settings_row)
    return settings_row


__all__ = ["get_or_create_settings", "update_settings"]
