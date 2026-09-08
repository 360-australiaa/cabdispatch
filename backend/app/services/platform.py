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

import hashlib
import secrets
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import hash_password
from app.models.billing import PLAN_PRICES_AUD, STATUS_ACTIVE, STATUS_TRIALING, Subscription
from app.models.duress import DURESS_TERMINAL_STATUSES, DuressEvent
from app.models.fleet import Vehicle
from app.models.tariffs import REGION_URBAN, Tariff
from app.models.tenant import Tenant
from app.models.tenant_invite import TenantInvite
from app.models.trips import Trip
from app.models.user import ROLE_DRIVER, ROLE_OWNER, User
from app.services import fare_engine as fe

# Rate fields shared 1:1 between fare_engine.Tariff (the pure dataclass) and
# the DB's Tariff model — same list app/services/tariffs.py's
# `_FARE_ENGINE_FIELDS` already uses; duplicated here rather than imported
# because that module's copy is private (`_`-prefixed) and this is the only
# other place that ever needs to convert one of these end to end.
_TARIFF_RATE_FIELDS = (
    "flag_fall",
    "peak_charge",
    "dist_rate_1",
    "dist_rate_2",
    "night_rate_1",
    "night_rate_2",
    "holiday_rate_1",
    "holiday_rate_2",
    "waiting_rate_per_min",
    "dist_km_threshold",
    "speed_threshold_kmh",
    "maxi_multiplier",
    "multi_hire_pct",
    "psl_amount",
    "surcharge_pct_cap",
    "cleaning_fee_cap",
)

# How long a freshly-minted owner invite stays acceptable before the platform
# owner has to onboard the tenant again. A week: long enough that "send the
# link to the new operator" isn't a same-day race, short enough that a token
# sitting unused isn't a permanent standing credential.
INVITE_TOKEN_TTL = timedelta(days=7)

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


class OwnerEmailRequiredError(PlatformError):
    """Raised when a tenant-onboarding request has a blank owner email after
    stripping whitespace — same reasoning as TenantNameRequiredError."""


class DuplicateOwnerEmailError(PlatformError):
    """Raised when the requested owner email is already in use by another
    user on the platform (User.email is globally unique — see
    app.services.user.assert_email_available, which this mirrors rather than
    imports, to keep this module's onboarding transaction self-contained)."""


class InvalidInviteTokenError(PlatformError):
    """Raised by accept_owner_invite for an unknown, already-used, or expired
    token — deliberately ONE error/message for all three (see
    app/api/v1/platform.py's route), so a guesser at this endpoint learns
    nothing about which case they hit, same "don't leak which half of the
    check failed" convention as auth.py's _INVALID_DRIVER_CREDENTIALS."""


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


def _hash_invite_token(raw_token: str) -> str:
    """SHA-256 hex digest — see app.models.tenant_invite.TenantInvite's module
    docstring for why only the digest, never the raw token, is stored. Plain
    SHA-256 (not bcrypt/PBKDF2) is appropriate here, unlike a user-chosen PIN
    or password: `raw_token` is `secrets.token_urlsafe(32)`, i.e. 256 bits of
    real entropy from a CSPRNG, not a low-entropy human-guessable secret —
    there is nothing for a slow, salted hash to defend against that a fast
    hash doesn't already defend against equally well at this entropy."""
    return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()


async def create_tenant(
    session: AsyncSession,
    *,
    name: str,
    abn: str | None,
    tsp_number: str | None,
    bsp_number: str | None,
    plan: str,
    owner_email: str,
    owner_name: str,
) -> tuple[Tenant, User, str, datetime]:
    """The onboarding-flow entry point for a brand-new tenant on the
    platform — creates the Tenant row, its owner user, and a default urban
    tariff (from the same rate card `scripts/seed.py` uses for a new tenant,
    `fare_engine.URBAN_TARIFF`) in ONE transaction, then mints a one-time
    owner-invite token.

    Before this, `POST /v1/platform/tenants` created a bare `Tenant` row only
    — no owner, no tariff — so a platform operator had to impersonate the
    new tenant via `?tenant_id=` and call `POST /v1/users` and the tariffs
    API by hand before the tenant could do anything at all (backend audit
    §2 "Tenant onboarding"). That is exactly the "developer runs SQL" gap
    this workstream exists to close.

    CREDENTIAL HANDLING: the owner user is created with `pin_hash=None` —
    it cannot log in via `POST /v1/auth/login` until it has one (that
    endpoint 401s on a null pin_hash the same as a wrong password — see
    `app.api.v1.auth.login`). No password is minted, printed, or logged by
    this function or anywhere it calls, per this workstream's hard
    constraint. Instead, a random `secrets.token_urlsafe(32)` invite token is
    generated, its SHA-256 digest is the only thing persisted
    (`TenantInvite.token_hash`), and the RAW token is returned to the caller
    (the platform-owner console) exactly once, in this function's return
    value — never written to a log, and never re-derivable from the stored
    digest. The owner exchanges it for a password THEY choose via
    `POST /v1/platform/invites/accept`, which is the only place a password
    for this account is ever set.

    Returns `(tenant, owner_user, raw_invite_token, invite_expires_at)`.
    """
    clean_name = name.strip()
    if not clean_name:
        raise TenantNameRequiredError("Tenant name must not be blank")

    clean_owner_email = owner_email.strip()
    if not clean_owner_email:
        raise OwnerEmailRequiredError("Owner email must not be blank")

    existing = (
        await session.execute(select(func.count()).select_from(User).where(User.email == clean_owner_email))
    ).scalar_one()
    if existing > 0:
        raise DuplicateOwnerEmailError(clean_owner_email)

    tenant = Tenant(name=clean_name, abn=abn, tsp_number=tsp_number, bsp_number=bsp_number, plan=plan)
    session.add(tenant)
    await session.flush()  # assigns tenant.id (and its before_insert-generated slug) without committing yet

    owner = User(
        tenant_id=tenant.id,
        role=ROLE_OWNER,
        name=owner_name.strip() or "Owner",
        email=clean_owner_email,
        pin_hash=None,
        status="active",
    )
    session.add(owner)
    await session.flush()  # assigns owner.id, needed below by TenantInvite.user_id

    default_tariff = Tariff(
        tenant_id=tenant.id,
        name=f"{clean_name} urban rank/hail",
        region=REGION_URBAN,
        effective_from=datetime.now(UTC),
        effective_to=None,
        booked=False,
        **{field: getattr(fe.URBAN_TARIFF, field) for field in _TARIFF_RATE_FIELDS},
    )
    session.add(default_tariff)

    raw_token = secrets.token_urlsafe(32)
    invite = TenantInvite(
        tenant_id=tenant.id,
        user_id=owner.id,  # populated by the flush right after session.add(owner) above
        token_hash=_hash_invite_token(raw_token),
        expires_at=datetime.now(UTC) + INVITE_TOKEN_TTL,
    )
    session.add(invite)

    await session.commit()
    await session.refresh(tenant)
    await session.refresh(owner)

    return tenant, owner, raw_token, invite.expires_at


async def accept_owner_invite(session: AsyncSession, *, raw_token: str, password: str) -> User:
    """Exchanges a one-time owner-invite token for a password the owner
    chooses themselves — the other half of `create_tenant`'s onboarding
    transaction (see that function's docstring). Public/token-based: the
    owner holds no bearer token at this point, so this is intentionally NOT
    gated by `require_platform_owner` or any other role dependency (the
    router route for this lives outside that gate — see
    app/api/v1/platform.py).

    Looks the invite up by the SHA-256 digest of the presented token (never
    by scanning plaintext — there is no plaintext stored to scan), same
    "hash first, then equality-compare the digest" shape as every password
    check in this codebase, just without a slow KDF (see
    `_hash_invite_token`'s own docstring for why that's fine at this
    token's entropy)."""
    digest = _hash_invite_token(raw_token)
    result = await session.execute(select(TenantInvite).where(TenantInvite.token_hash == digest))
    invite = result.scalar_one_or_none()

    if invite is None:
        raise InvalidInviteTokenError("Invite token is invalid")
    if invite.used_at is not None:
        raise InvalidInviteTokenError("Invite token has already been used")
    # SQLite (this test suite's default, and any small dev deployment) does not
    # actually persist tz-awareness on a DateTime(timezone=True) column — a
    # round-tripped value comes back naive — so treat a naive read as UTC,
    # matching this codebase's existing convention (see e.g.
    # app.services.compliance_expiry / app.services.duress for the same
    # tzinfo-is-None-means-UTC normalization).
    expires_at = invite.expires_at
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=UTC)
    if expires_at <= datetime.now(UTC):
        raise InvalidInviteTokenError("Invite token has expired")

    user_result = await session.execute(select(User).where(User.id == invite.user_id))
    user = user_result.scalar_one_or_none()
    if user is None:
        # Defensive only — the FK to users.id guarantees this can't happen in
        # practice; a deleted owner user would already have cascaded/blocked
        # long before this code path (see app.services.user.assert_user_deletable).
        raise InvalidInviteTokenError("Invite token is invalid")

    user.pin_hash = hash_password(password)
    invite.used_at = datetime.now(UTC)
    await session.commit()
    await session.refresh(user)
    return user


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

    mrr_aud = Decimal(0)
    plan_counts: dict[str, int] = {}
    status_counts: dict[str, int] = {}

    for sub in subscriptions:
        status_counts[sub.status] = status_counts.get(sub.status, 0) + 1
        if sub.status in ACTIVE_EQUIVALENT_SUBSCRIPTION_STATUSES:
            mrr_aud += PLAN_PRICES_AUD.get(sub.plan, Decimal(0))
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
    "INVITE_TOKEN_TTL",
    "DuplicateOwnerEmailError",
    "InvalidInviteTokenError",
    "OwnerEmailRequiredError",
    "PlatformError",
    "TenantNameRequiredError",
    "accept_owner_invite",
    "create_tenant",
    "get_platform_billing_summary",
    "get_platform_health",
    "get_tenant_billing",
    "get_tenant_counts",
    "list_tenants",
    "update_tenant_status",
]
