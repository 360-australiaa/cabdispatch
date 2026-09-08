"""Business logic for the platform-owner admin console (/v1/platform/...).

Every endpoint this service backs is reachable ONLY through
app.api.v1.platform, whose router gates every route to
role == "owner" AND tenant_id == PLATFORM_TENANT_ID specifically (see that
module require_platform_owner dependency) - a normal tenant owner never
calls into this module. This closes a real gap: the platform tenant could
already technically act cross-tenant via get_current_tenant_id
tenant_id override (see app.core.security), but had no actual management
surface to do it through.

Tenant creation reuses app.models.tenant.Tenant directly - no new table.
The per-tenant/platform-wide counts intentionally do NOT filter by
get_current_tenant_id (unlike every other domain in this system) because
that dependency resolves to a single tenant scope and these rollups are
explicitly cross-tenant by design; each function takes the target tenant_id
(or none, for the platform-wide health rollup) as an explicit parameter
instead, and the router never lets a non-platform-owner caller reach these
functions at all.
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.billing import PLAN_PRICES_AUD, STATUS_ACTIVE, STATUS_TRIALING, Subscription
from app.models.duress import DURESS_TERMINAL_STATUSES, DuressEvent
from app.models.fleet import Vehicle
from app.models.tenant import Tenant
from app.models.trips import Trip
from app.models.user import ROLE_DRIVER, User

# Statuses counted as "revenue-live" for the platform-wide MRR rollup below.
# Deliberately broader than just STATUS_ACTIVE: a "trialing" subscription
# already has a real per-plan price attached (see
# app.services.billing.price_for_plan / create_subscription) and represents
# a provisioned, billable seat the same as "active" - only past_due /
# canceled / incomplete are excluded, since those are not currently
# generating recurring revenue.
ACTIVE_EQUIVALENT_SUBSCRIPTION_STATUSES = (STATUS_ACTIVE, STATUS_TRIALING)


class PlatformError(Exception):
    """Base class for platform-console errors; the router translates each
    subclass to the appropriate HTTP status."""


class TenantNameRequiredError(PlatformError):
    """Raised when a tenant-onboarding request has a blank name after
    stripping whitespace (Pydantic min_length=1 already rejects an empty
    string, but not one that is whitespace-only)."""


async def list_tenants(session: AsyncSession, *, skip: int, limit: int) -> tuple[list[Tenant], int]:
    """Every tenant on the platform - deliberately unscoped by
    get_current_tenant_id (see module docstring). Ordered newest-first
    (created_at.desc()) - the default first page (skip=0) must surface
    recently-onboarded tenants, not the oldest ones. Bug fixed here: an
    earlier ascending order meant that once the platform has more tenants
    than the default page size, a brand-new tenant would never appear on
    the default page at all, only many pages deep - caught by
    test_platform_owner_can_onboard_a_new_tenant against the real shared
    dev/test database rather than a small fixture-only one."""
    total = (await session.execute(select(func.count()).select_from(Tenant))).scalar_one()
    result = await session.execute(select(Tenant).order_by(Tenant.created_at.desc()).offset(skip).limit(limit))
    return list(result.scalars().all()), total


async def create_tenant(
    session: AsyncSession,
    *,
    name: str,
    abn: str | None,
    tsp_number: str | None,
    bsp_number: str | None,
    plan: str,
) -> Tenant:
    """The onboarding-flow entry point for a brand-new tenant on the
    platform. No uniqueness constraint on name exists at the DB layer
    (see app.models.tenant.Tenant), matching that model own convention -
    two tenants may share a display name."""
    clean_name = name.strip()
    if not clean_name:
        raise TenantNameRequiredError("Tenant name must not be blank")

    tenant = Tenant(name=clean_name, abn=abn, tsp_number=tsp_number, bsp_number=bsp_number, plan=plan)
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


async def get_tenant_counts(session: AsyncSession, *, tenant_id: str) -> dict[str, int]:
    """Vehicle count, driver count, trip count (last 30 days), and active
    (non-terminal) duress count for one tenant - the per-tenant
    health-at-a-glance rollup. Reuses the same count-query shape every
    sibling domain router already uses for its own list-endpoint totals
    (see e.g. app/api/v1/fleet.py list_vehicles) rather than introducing a
    new pattern."""
    thirty_days_ago = datetime.now(UTC) - timedelta(days=30)

    vehicle_count = (
        await session.execute(
            select(func.count()).select_from(Vehicle).where(Vehicle.tenant_id == tenant_id)
        )
    ).scalar_one()

    driver_count = (
        await session.execute(
            select(func.count())
            .select_from(User)
            .where(User.tenant_id == tenant_id, User.role == ROLE_DRIVER)
        )
    ).scalar_one()

    trip_count_last_30_days = (
        await session.execute(
            select(func.count())
            .select_from(Trip)
            .where(Trip.tenant_id == tenant_id, Trip.start_at >= thirty_days_ago)
        )
    ).scalar_one()

    active_duress_count = (
        await session.execute(
            select(func.count())
            .select_from(DuressEvent)
            .where(
                DuressEvent.tenant_id == tenant_id,
                DuressEvent.status.notin_(DURESS_TERMINAL_STATUSES),
            )
        )
    ).scalar_one()

    return {
        "vehicle_count": vehicle_count,
        "driver_count": driver_count,
        "trip_count_last_30_days": trip_count_last_30_days,
        "active_duress_count": active_duress_count,
    }


async def get_platform_health(session: AsyncSession) -> dict[str, int]:
    """Aggregate, platform-wide (every tenant): total tenants, total
    vehicles, total trips started today (UTC calendar day)."""
    start_of_today = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)

    total_tenants = (await session.execute(select(func.count()).select_from(Tenant))).scalar_one()
    total_vehicles = (await session.execute(select(func.count()).select_from(Vehicle))).scalar_one()
    total_trips_today = (
        await session.execute(
            select(func.count()).select_from(Trip).where(Trip.start_at >= start_of_today)
        )
    ).scalar_one()

    return {
        "total_tenants": total_tenants,
        "total_vehicles": total_vehicles,
        "total_trips_today": total_trips_today,
    }


async def get_platform_billing_summary(session: AsyncSession) -> dict:
    """Cross-tenant MRR rollup - deliberately unscoped by
    get_current_tenant_id (see module docstring), same pattern as
    get_platform_health. Loads every Subscription row on the platform (no
    tenant filter) and computes:

    - mrr_aud: sum of PLAN_PRICES_AUD[plan] for every subscription whose
      status is in ACTIVE_EQUIVALENT_SUBSCRIPTION_STATUSES. Always derived
      from the plan-price lookup table, never from a subscription's stored
      price_aud - that field can in principle drift (e.g. a historical
      price change) whereas PLAN_PRICES_AUD is the current, single source
      of truth for what a plan costs today (see
      app.services.billing.price_for_plan's own docstring).
    - plan_counts: count of active-equivalent subscriptions per plan.
    - status_counts: count of ALL subscriptions per status, active-equivalent
      or not - this is the only one of the three that also surfaces
      past_due/canceled/incomplete subscriptions, since it exists to show
      the full status mix, not just the billable slice.
    """
    result = await session.execute(select(Subscription))
    subscriptions = list(result.scalars().all())

    mrr_aud = Decimal("0")
    plan_counts: dict[str, int] = {}
    status_counts: dict[str, int] = {}

    for sub in subscriptions:
        status_counts[sub.status] = status_counts.get(sub.status, 0) + 1
        if sub.status in ACTIVE_EQUIVALENT_SUBSCRIPTION_STATUSES:
            mrr_aud += PLAN_PRICES_AUD.get(sub.plan, Decimal("0"))
            plan_counts[sub.plan] = plan_counts.get(sub.plan, 0) + 1

    return {
        "mrr_aud": mrr_aud,
        "plan_counts": plan_counts,
        "status_counts": status_counts,
    }


async def get_tenant_billing(session: AsyncSession, *, tenant_id: str) -> list[Subscription]:
    """One tenant's subscriptions, newest first - the support-triage view a
    platform-owner uses to review a network's billing without impersonating
    them (switching ?tenant_id= via get_current_tenant_id would still mean
    acting AS that tenant; this is read-only and cross-tenant by design, same
    bypass as every other function in this module)."""
    result = await session.execute(
        select(Subscription)
        .where(Subscription.tenant_id == tenant_id)
        .order_by(Subscription.created_at.desc())
    )
    return list(result.scalars().all())


async def update_tenant_status(session: AsyncSession, tenant: Tenant, *, status_value: str) -> Tenant:
    """Suspend/reactivate/trial-flag a tenant from the platform console
    (PATCH /v1/platform/tenants/{tenant_id}). Takes an already-loaded Tenant
    (the router loads it via tenant_service.get_tenant_or_404 first, same
    "load then mutate" shape as tenant_service.set_admin_pin /
    update_theme)."""
    tenant.status = status_value
    await session.commit()
    await session.refresh(tenant)
    return tenant


__all__ = [
    "ACTIVE_EQUIVALENT_SUBSCRIPTION_STATUSES",
    "PlatformError",
    "TenantNameRequiredError",
    "create_tenant",
    "get_platform_billing_summary",
    "get_platform_health",
    "get_tenant_billing",
    "get_tenant_counts",
    "list_tenants",
    "update_tenant_status",
]
