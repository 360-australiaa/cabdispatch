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

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.duress import DURESS_TERMINAL_STATUSES, DuressEvent
from app.models.fleet import Vehicle
from app.models.tenant import Tenant
from app.models.trips import Trip
from app.models.user import ROLE_DRIVER, ROLE_OWNER, User
from app.models.user_invite import INVITE_PURPOSE_INVITE
from app.services import user as user_service
from app.services import user_invites as user_invites_service
from app.services.tenant_settings import get_or_create_settings


class PlatformError(Exception):
    """Base class for platform-console errors; the router translates each
    subclass to the appropriate HTTP status."""


class TenantNameRequiredError(PlatformError):
    """Raised when a tenant-onboarding request has a blank name after
    stripping whitespace (Pydantic min_length=1 already rejects an empty
    string, but not one that is whitespace-only)."""


class OwnerEmailInUseError(PlatformError):
    """Raised when owner_email already belongs to an existing user --
    User.email is globally unique across the whole platform, see
    app.services.user.assert_email_available."""


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
    owner_name: str,
    owner_email: str,
) -> dict:
    """The onboarding-flow entry point for a brand-new tenant on the
    platform (plan Part 5, Q1 answer -- WP-17). No uniqueness constraint
    on name exists at the DB layer (see app.models.tenant.Tenant),
    matching that model own convention - two tenants may share a
    display name.

    Not one single DB transaction end-to-end -- get_or_create_settings
    and create_invite (the WP-01 / WP-10 sibling services this function
    deliberately reuses rather than forking) each commit internally.
    What IS guaranteed: the tenant row is flushed (not yet committed)
    before get_or_create_settings runs, so tenant+settings commit
    together in the same call; the owner user row is flushed (not yet
    committed) before create_invite runs, so user+invite commit
    together in the same call. Known gap, documented rather than
    silently claimed away: if the process dies between those two commit
    points, a tenant+settings row can exist with no owner/invite yet.
    """
    clean_name = name.strip()
    if not clean_name:
        raise TenantNameRequiredError("Tenant name must not be blank")

    try:
        await user_service.assert_email_available(session, email=owner_email)
    except user_service.DuplicateEmailError as exc:
        raise OwnerEmailInUseError(str(exc)) from exc

    tenant = Tenant(
        name=clean_name, abn=abn, tsp_number=tsp_number, bsp_number=bsp_number, plan=plan
    )
    session.add(tenant)
    await session.flush()

    await get_or_create_settings(session, tenant_id=tenant.id)

    owner = User(
        tenant_id=tenant.id,
        role=ROLE_OWNER,
        name=owner_name,
        email=owner_email,
        pin_hash=None,
        status="active",
    )
    session.add(owner)
    await session.flush()

    _invite, _raw_token, invite_link = await user_invites_service.create_invite(
        session, user=owner, purpose=INVITE_PURPOSE_INVITE
    )
    user_invites_service.send_invite_email(to_email=owner.email, name=owner.name, link=invite_link)

    await session.refresh(tenant)
    return {
        "tenant": tenant,
        "owner_user_id": owner.id,
        "owner_email": owner.email,
        "invite_link": invite_link,
    }


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


async def update_tenant_lifecycle(
    session: AsyncSession, tenant: Tenant, *, changes: dict
) -> Tenant:
    """Applies status and/or plan changes for PATCH
    /v1/platform/tenants/{id} (plan Part 4 Phase 1, WP-18). Only the
    explicitly-supplied fields in changes (router passes
    payload.model_dump(exclude_unset=True)) are touched -- same
    partial-update convention as
    app.services.tenant_settings.update_settings."""
    for field, value in changes.items():
        setattr(tenant, field, value)
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


__all__ = [
    "OwnerEmailInUseError",
    "PlatformError",
    "TenantNameRequiredError",
    "create_tenant",
    "get_platform_health",
    "get_tenant_counts",
    "list_tenants",
    "update_tenant_lifecycle",
]
