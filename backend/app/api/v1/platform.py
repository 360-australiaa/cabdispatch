"""Platform-owner admin console router (/v1/platform/...).

Closes a real management-surface gap: get_current_tenant_id
(app.core.security) already lets role == "owner" tokens scoped to
PLATFORM_TENANT_ID pass ?tenant_id=<id> to act cross-tenant on every
existing per-tenant endpoint, but the platform tenant had no actual
console of its own - no way to list every tenant, onboard a new one, or
see a cross-tenant health rollup. This router is that console.

Gating: every route here requires require_platform_owner below, which is
stricter than plain require_role("owner") - it also checks that the
authenticated user row itself belongs to PLATFORM_TENANT_ID (tenant "0" /
"TCT", the real seeded platform tenant row - see scripts/seed.py). An
ordinary tenant owner authenticates fine against require_role("owner") but
is refused here with 403, same as any other role.

These routes intentionally do NOT depend on get_current_tenant_id - unlike
every other domain router in this system, they are not scoped to "the
callers own tenant" at all. Each route instead takes the target tenant_id
(where relevant) as an explicit path parameter or lists across every tenant,
per app.services.platform's own module docstring.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import PLATFORM_TENANT_ID, require_role
from app.schemas.platform import (
    Page,
    PlatformBillingSummary,
    PlatformHealth,
    PlatformTenantCreate,
    PlatformTenantRead,
    TenantStatusUpdate,
    TenantSubscriptionRead,
    TenantSummary,
)
from app.services import platform as platform_service
from app.services import tenant as tenant_service

router = APIRouter(prefix="/v1/platform", tags=["platform"])

_require_owner = require_role("owner")


async def require_platform_owner(user=Depends(_require_owner)):
    """role == "owner" is necessary but not sufficient here - the user row
    must also belong to the platform tenant itself (tenant_id ==
    PLATFORM_TENANT_ID), matching get_current_tenant_id own cross-tenant
    override check in app.core.security. A normal tenant owner role ==
    "owner" but tenant_id != PLATFORM_TENANT_ID is refused with 403."""
    if user.tenant_id != PLATFORM_TENANT_ID:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Platform-owner access required",
        )
    return user


@router.get("/tenants", response_model=Page[PlatformTenantRead])
async def list_platform_tenants(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Every tenant on the platform - id/name/plan/created_at."""
    tenants, total = await platform_service.list_tenants(session, skip=skip, limit=limit)
    return Page[PlatformTenantRead](items=tenants, total=total, skip=skip, limit=limit)


@router.post("/tenants", response_model=PlatformTenantRead, status_code=status.HTTP_201_CREATED)
async def create_platform_tenant(
    payload: PlatformTenantCreate,
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Onboarding-flow entry point: creates a brand-new tenant on the
    platform."""
    try:
        tenant = await platform_service.create_tenant(
            session,
            name=payload.name,
            abn=payload.abn,
            tsp_number=payload.tsp_number,
            bsp_number=payload.bsp_number,
            plan=payload.plan,
        )
    except platform_service.TenantNameRequiredError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
    return tenant


@router.get("/tenants/{tenant_id}/summary", response_model=TenantSummary)
async def get_platform_tenant_summary(
    tenant_id: str,
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Health-at-a-glance rollup for one tenant: vehicle count, driver
    count, trip count over the last 30 days, and active (non-terminal)
    duress count. Reuses tenant_service.get_tenant_or_404 (the same lookup
    GET/PATCH /v1/tenants/me already uses) rather than duplicating tenant
    existence-checking logic."""
    try:
        tenant = await tenant_service.get_tenant_or_404(session, tenant_id=tenant_id)
    except tenant_service.TenantError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found") from exc

    counts = await platform_service.get_tenant_counts(session, tenant_id=tenant_id)
    return TenantSummary(tenant_id=tenant.id, tenant_name=tenant.name, **counts)


@router.get("/health", response_model=PlatformHealth)
async def get_platform_health(
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Aggregate, platform-wide: total tenants, total vehicles, total trips
    started today (UTC calendar day) - across every tenant."""
    health = await platform_service.get_platform_health(session)
    return PlatformHealth(**health)


@router.patch("/tenants/{tenant_id}", response_model=PlatformTenantRead)
async def update_platform_tenant(
    tenant_id: str,
    payload: TenantStatusUpdate,
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Suspend/reactivate/trial-flag a tenant from the platform console.
    Reuses tenant_service.get_tenant_or_404, same as the /summary route
    above."""
    try:
        tenant = await tenant_service.get_tenant_or_404(session, tenant_id=tenant_id)
    except tenant_service.TenantError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found") from exc

    return await platform_service.update_tenant_status(session, tenant, status_value=payload.status)


@router.get("/billing/summary", response_model=PlatformBillingSummary)
async def get_platform_billing_summary(
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Cross-tenant MRR rollup: monthly recurring revenue, plan-tier and
    status breakdowns across every tenant's subscriptions - always
    server-computed (see app.services.platform.get_platform_billing_summary),
    never trusts any client-supplied figure."""
    summary = await platform_service.get_platform_billing_summary(session)
    return PlatformBillingSummary(**summary)


@router.get("/tenants/{tenant_id}/billing", response_model=list[TenantSubscriptionRead])
async def get_platform_tenant_billing(
    tenant_id: str,
    session: AsyncSession = Depends(get_session),
    _owner=Depends(require_platform_owner),
):
    """Support-triage view: one tenant's subscriptions (vehicle/plan/status/
    stripe id), for platform staff reviewing a network's billing without
    impersonating them via the ?tenant_id= override on every other endpoint
    (see app.core.security.get_current_tenant_id)."""
    try:
        await tenant_service.get_tenant_or_404(session, tenant_id=tenant_id)
    except tenant_service.TenantError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found") from exc

    return await platform_service.get_tenant_billing(session, tenant_id=tenant_id)


__all__ = ["router"]
