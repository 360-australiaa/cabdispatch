"""Dev/demo seed data.

Creates:
  - the platform tenant "TCT" (id = the PLATFORM_TENANT_ID sentinel in
    app.core.security), a global (tenant_id IS NULL) Fares Order reference
    tariff for both urban and country regions with the EXACT NSW Point to
    Point Transport (Fares) Order 2026 rates (re-derived from
    app.services.fare_engine's
    URBAN_TARIFF / COUNTRY_TARIFF constants — the same source of truth the
    golden fare-engine tests assert against, not re-guessed).
  - a demo tenant "Lilly Cabs" with its own urban rank/hail tariff, a copy of
    the reference rates (a tenant tariff must never exceed the Fares Order
    cap — see app.services.tariffs.validate_tariff_or_422).
  - one platform admin user (admin@cabdispatch.test, role owner, tenant TCT),
    one demo tenant admin (owner@lillycabs.test, role owner, tenant Lilly
    Cabs), and one demo driver (driver@lillycabs.test, role driver, tenant
    Lilly Cabs — this is what the meter/driver app's "Driver ID"/"PIN" fields
    map to, see app.domain.DriverAuthRepository on the Android side).

CREDENTIALS ARE NOT EMBEDDED IN THIS FILE, AND ARE NEVER PRINTED.

This script used to hardcode a shared demo password and a demo driver PIN at
module level, and echo both — plus the driver's minted driver_code — to
stdout on completion. docs/DEPLOY_UBUNTU.md told operators to run it on the
production server. That is exactly how a working production driver login
ended up quoted in three checked-in documents and compiled into a shipped
APK. So:

  * Staff passwords come from $SEED_OWNER_PASSWORD. Unset it and each staff
    account gets a fresh random password that is never displayed — the
    account exists but is unreachable until someone resets it. That is the
    intended safe default: you opt IN to a login you can use.
  * The demo driver's PIN comes from $SEED_DRIVER_PIN, with the same rule.
    For anything but a throwaway local database, don't set it — create a
    real driver on the dashboard's Fleet ▸ Drivers page, which mints the
    driver_code and PIN through the normal audited path.
  * Nothing below prints a password, a PIN, or a driver_code. If you need
    the driver_code of a seeded driver, read it off Fleet ▸ Drivers.

Refuses to run against ENV=production unless you pass
--i-know-this-is-production. Demo tenants, demo tariffs and a "Demo Driver"
do not belong in a production database; the flag exists for the one
legitimate case (bootstrapping the global Fares Order reference tariff on a
brand-new deployment) and makes it a deliberate act.

Idempotent: safe to re-run — every row is looked up by its natural key first
and only created if missing.

    uv run python scripts/seed.py
    SEED_OWNER_PASSWORD='...' uv run python scripts/seed.py   # usable staff login

Run `uv run python scripts/seed_toll_roads.py` as well (any order relative to
this script) to seed the real NSW toll-road registry (13 roads, 141
gantries) — split into its own script because it's a much larger, purely
reference-data (no tenant/user) dataset; see that script's own docstring.
"""
from __future__ import annotations

import argparse
import asyncio
import os
import secrets
import sys
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import select

import app.models  # noqa: F401 — populate Base.metadata before any query runs
from app.core.config import settings
from app.core.database import AsyncSessionLocal
from app.core.security import PLATFORM_TENANT_ID, hash_password
from app.models.geofence import GEOFENCE_KIND_REGION, Geofence
from app.models.tariffs import Tariff
from app.models.tenant import Tenant
from app.models.user import ROLE_DRIVER, ROLE_OWNER, User
from app.services import fare_engine as fe
from app.services.user import generate_unique_driver_code

# Env var names, not values. There is deliberately no default credential
# anywhere in this file -- see the module docstring for why.
OWNER_PASSWORD_ENV = "SEED_OWNER_PASSWORD"
DRIVER_PIN_ENV = "SEED_DRIVER_PIN"

PLATFORM_TENANT_NAME = "TCT"
DEMO_TENANT_NAME = "Lilly Cabs"

# NSW Point to Point Transport (Fares) Order 2026, effective 1 June 2026 —
# matches the golden fare-engine tests in tests/test_fare_engine_golden.py
# exactly.
FARES_ORDER_EFFECTIVE_FROM = datetime(2026, 6, 1, tzinfo=UTC)

# Rate fields shared 1:1 between fare_engine.Tariff and the DB Tariff model
# (see app.services.tariffs._FARE_ENGINE_FIELDS).
_RATE_FIELDS = (
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


def _rate_kwargs(engine_tariff: fe.Tariff) -> dict[str, Decimal]:
    return {f: getattr(engine_tariff, f) for f in _RATE_FIELDS}


# --- global (tenant_id IS NULL) reference geofences — blueprint 7.2.5 -------
#
# The 9 toll-kind entries this list used to carry for the M5 East, Sydney
# Harbour Bridge/Tunnel, Eastern Distributor, Cross City Tunnel, Lane Cove
# Tunnel, M2, WestConnex M4/M8, M7, and NorthConnex are SUPERSEDED, not
# duplicated here — see `app.models.toll` / `app.services.tolls` /
# `scripts/seed_toll_roads.py` for the real per-road, per-gantry,
# direction-aware, quarterly-price-versioned registry that replaces them
# (loaded by that separate idempotent script, run alongside this one), and
# `alembic/versions/a9c1f4e7d2b8_nsw_toll_road_registry.py` for the migration
# that deletes those 9 rows from any database that already ran the old
# version of this script (leaving both mechanisms live at once would
# double-charge every trip that crosses one of these 9 real roads).
#
# The one surviving entry below is a genuine `kind="region"` row (not
# pricing, not superseded by anything toll-related) — coordinates are still
# an APPROXIMATE real-world landmark location, same "near this landmark"
# convention as before.
GLOBAL_GEOFENCES: list[dict] = [
    {
        # A "region" example (not a toll) — the airport precinct fits blueprint
        # 7.2.5's tariff-zone use case (see app.models.trips.TRIP_TYPE_AIRPORT_FIXED)
        # more naturally than a toll-detection use case.
        "name": "Sydney (Kingsford Smith) Airport precinct (approx.)",
        "kind": GEOFENCE_KIND_REGION,
        "center_lat": -33.9399,
        "center_lng": 151.1753,
        "radius_m": 1500,
        "toll_amount": None,
    },
]


async def get_or_create_global_geofence(
    session, *, name: str, kind: str, center_lat: float, center_lng: float, radius_m: float, toll_amount: Decimal | None
) -> Geofence:
    result = await session.execute(
        select(Geofence).where(Geofence.tenant_id.is_(None), Geofence.name == name)
    )
    existing = result.scalar_one_or_none()
    if existing is not None:
        return existing

    geofence = Geofence(
        tenant_id=None,
        name=name,
        kind=kind,
        center_lat=center_lat,
        center_lng=center_lng,
        radius_m=radius_m,
        toll_amount=toll_amount,
    )
    session.add(geofence)
    await session.commit()
    await session.refresh(geofence)
    print(f"  created global geofence {name!r} (kind={kind}, {geofence.id})")
    return geofence


async def get_or_create_tenant(session, *, tenant_id: str | None, name: str, **extra) -> Tenant:
    if tenant_id is not None:
        result = await session.execute(select(Tenant).where(Tenant.id == tenant_id))
        existing = result.scalar_one_or_none()
        if existing is not None:
            return existing

    result = await session.execute(select(Tenant).where(Tenant.name == name))
    existing = result.scalar_one_or_none()
    if existing is not None:
        return existing

    tenant = Tenant(name=name, **extra)
    if tenant_id is not None:
        tenant.id = tenant_id
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    print(f"  created tenant {name!r} ({tenant.id})")
    return tenant


async def get_or_create_global_reference_tariff(session, *, region: str, engine_tariff: fe.Tariff) -> Tariff:
    result = await session.execute(
        select(Tariff).where(Tariff.tenant_id.is_(None), Tariff.region == region)
    )
    existing = result.scalar_one_or_none()
    if existing is not None:
        return existing

    tariff = Tariff(
        tenant_id=None,
        name=f"NSW Fares Order 2025 no.2 — {region} reference",
        region=region,
        effective_from=FARES_ORDER_EFFECTIVE_FROM,
        effective_to=None,
        booked=False,
        **_rate_kwargs(engine_tariff),
    )
    session.add(tariff)
    await session.commit()
    await session.refresh(tariff)
    print(f"  created global Fares Order reference tariff for region={region!r} ({tariff.id})")
    return tariff


async def get_or_create_tenant_tariff(
    session, *, tenant_id: str, name: str, region: str, engine_tariff: fe.Tariff
) -> Tariff:
    result = await session.execute(
        select(Tariff).where(Tariff.tenant_id == tenant_id, Tariff.region == region, Tariff.name == name)
    )
    existing = result.scalar_one_or_none()
    if existing is not None:
        return existing

    tariff = Tariff(
        tenant_id=tenant_id,
        name=name,
        region=region,
        effective_from=FARES_ORDER_EFFECTIVE_FROM,
        effective_to=None,
        booked=False,
        **_rate_kwargs(engine_tariff),
    )
    session.add(tariff)
    await session.commit()
    await session.refresh(tariff)
    print(f"  created tenant tariff {name!r} for region={region!r} ({tariff.id})")
    return tariff


async def get_or_create_user(
    session,
    *,
    email: str,
    tenant_id: str | None,
    role: str,
    name: str,
    password: str,
    driver_code: str | None = None,
) -> User:
    result = await session.execute(select(User).where(User.email == email))
    existing = result.scalar_one_or_none()
    if existing is not None:
        # Backfill driver_code onto a pre-existing seeded row from before
        # this column existed (re-running seed.py after a fresh migration
        # must not leave the demo driver without one — see
        # app/models/user.py::User.driver_code).
        if driver_code is not None and existing.driver_code is None:
            existing.driver_code = driver_code
            await session.commit()
            await session.refresh(existing)
        return existing

    user = User(
        tenant_id=tenant_id,
        role=role,
        name=name,
        email=email,
        pin_hash=hash_password(password),
        status="active",
        driver_code=driver_code,
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    print(f"  created user {email!r} (role={role}, tenant_id={tenant_id})")
    return user


# --- credentials -------------------------------------------------------
# Every one of these returns a value that is used and then forgotten. None of
# them is ever printed, logged, or written anywhere but the password hash
# column. See the module docstring.


def _staff_password() -> tuple[str, bool]:
    """The password to hash into every seeded staff (owner-role) account.

    Returns (password, operator_supplied). When $SEED_OWNER_PASSWORD is unset
    we mint a long random one and hash that -- the account is created, is
    valid, and is unusable by anybody, including whoever ran this script.
    That is the point: a seeded account nobody can log into cannot become a
    disclosed credential, and the operator who genuinely wants a working
    login opts in by setting the variable.
    """
    supplied = os.environ.get(OWNER_PASSWORD_ENV, "")
    if supplied:
        return supplied, True
    return secrets.token_urlsafe(32), False


def _driver_pin() -> tuple[str, bool]:
    """The PIN to hash into the demo driver account.

    Same contract as `_staff_password`, but numeric: the meter/driver app's
    PIN keypad is 0-9 only (no letters, no symbols), confirmed live against
    the real app 2026-08, so a urlsafe token is not typeable there. An
    unsupplied PIN is 6 random digits -- again, deliberately unknowable.

    `secrets.randbelow` rather than `random`: this value is a credential.
    """
    supplied = os.environ.get(DRIVER_PIN_ENV, "")
    if supplied:
        return supplied, True
    return f"{secrets.randbelow(1_000_000):06d}", False


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Seed dev/demo data. Refuses to run against ENV=production "
        "unless --i-know-this-is-production is passed.",
    )
    parser.add_argument(
        "--i-know-this-is-production",
        dest="allow_production",
        action="store_true",
        help="Permit running against ENV=production. This inserts a demo "
        "tenant, demo tariffs and a Demo Driver into your real database. "
        "Only legitimate use: bootstrapping the global Fares Order "
        "reference tariff on a brand-new deployment.",
    )
    return parser.parse_args(argv)


class ProductionSeedRefused(RuntimeError):
    """Raised when seed.py is invoked against ENV=production without the
    explicit acknowledgement flag -- see `assert_seed_allowed`."""


def assert_seed_allowed(*, is_production: bool, allow_production: bool) -> None:
    """Refuse to seed a production database by accident.

    docs/DEPLOY_UBUNTU.md instructed operators to run this script on the
    production server, which is how a live, working driver login came to
    exist and then be quoted in checked-in docs. The doc no longer says that,
    but a doc is not an enforcement mechanism -- this is.

    Takes its inputs as arguments rather than reading `settings` directly so
    the decision is testable without mutating the config singleton.
    """
    if is_production and not allow_production:
        raise ProductionSeedRefused(
            "Refusing to seed: ENV=production. This script inserts demo "
            "tenants, demo tariffs and a Demo Driver -- none of which belong "
            "in a production database, and a seeded driver account is exactly "
            "how a working production login was previously disclosed. Create "
            "real tenants and drivers through the dashboard instead "
            "(Fleet ▸ Drivers). If you genuinely need to bootstrap the "
            "global Fares Order reference tariff on a brand-new deployment, "
            "re-run with --i-know-this-is-production."
        )


async def seed() -> None:
    staff_password, staff_password_supplied = _staff_password()
    driver_pin, driver_pin_supplied = _driver_pin()

    async with AsyncSessionLocal() as session:
        print("Seeding platform tenant + global Fares Order reference tariffs...")
        platform_tenant = await get_or_create_tenant(
            session, tenant_id=PLATFORM_TENANT_ID, name=PLATFORM_TENANT_NAME, plan="platform"
        )
        await get_or_create_global_reference_tariff(session, region="urban", engine_tariff=fe.URBAN_TARIFF)
        await get_or_create_global_reference_tariff(session, region="country", engine_tariff=fe.COUNTRY_TARIFF)

        print("Seeding global region reference geofence (see scripts/seed_toll_roads.py for the real toll registry)...")
        for spec in GLOBAL_GEOFENCES:
            await get_or_create_global_geofence(session, **spec)

        print("Seeding demo tenant + tariff...")
        demo_tenant = await get_or_create_tenant(session, tenant_id=None, name=DEMO_TENANT_NAME, plan="standard")
        await get_or_create_tenant_tariff(
            session,
            tenant_id=demo_tenant.id,
            name=f"{DEMO_TENANT_NAME} urban rank/hail",
            region="urban",
            engine_tariff=fe.URBAN_TARIFF,
        )

        print("Seeding users...")
        await get_or_create_user(
            session,
            email="admin@cabdispatch.test",
            tenant_id=platform_tenant.id,
            role=ROLE_OWNER,
            name="Platform Admin",
            password=staff_password,
        )
        await get_or_create_user(
            session,
            email="owner@lillycabs.test",
            tenant_id=demo_tenant.id,
            role=ROLE_OWNER,
            name="Lilly Cabs Owner",
            password=staff_password,
        )
        # Only mint a random driver_code (app.services.user.generate_unique_driver_code
        # — same alphabet/length the real POST /v1/users auto-generation path
        # uses) if the demo driver doesn't already have one from a prior run;
        # get_or_create_user backfills it onto an existing pre-migration row too.
        existing_driver = (
            await session.execute(select(User).where(User.email == "driver@lillycabs.test"))
        ).scalar_one_or_none()
        demo_driver_code = (
            await generate_unique_driver_code(session, tenant_id=demo_tenant.id)
            if existing_driver is None or existing_driver.driver_code is None
            else None
        )
        demo_driver = await get_or_create_user(
            session,
            email="driver@lillycabs.test",
            tenant_id=demo_tenant.id,
            role=ROLE_DRIVER,
            name="Demo Driver",
            password=driver_pin,
            driver_code=demo_driver_code,
        )
        # Nothing below prints a password, a PIN, or a driver_code. `demo_driver`
        # is referenced only to assert it exists -- reading `.driver_code` here
        # and formatting it into stdout is the exact line that leaked a working
        # production login into three checked-in documents.
        assert demo_driver.id is not None

        print("\nSeed complete.")
        print(f"  Platform tenant : {platform_tenant.id} ({platform_tenant.name})")
        print(f"  Demo tenant     : {demo_tenant.id} ({demo_tenant.name})")
        print("  Accounts created (no credentials are printed, by design):")
        print("    admin@cabdispatch.test    (platform owner, cross-tenant)")
        print("    owner@lillycabs.test      (Lilly Cabs owner)")
        print("    driver@lillycabs.test     (Lilly Cabs driver, meter/app 'Driver ID' login)")
        print()
        if staff_password_supplied:
            print(
                f"  Staff password : the value you set in ${OWNER_PASSWORD_ENV}. "
                "Log in via POST /v1/auth/login."
            )
        else:
            print(
                "  Staff password : randomly generated and discarded. Re-run with "
                f"${OWNER_PASSWORD_ENV} set to choose one, or reset the account "
                "from the dashboard."
            )
        if driver_pin_supplied:
            print(f"  Driver PIN     : the value you set in ${DRIVER_PIN_ENV}.")
        else:
            print(
                "  Driver PIN     : randomly generated and discarded. For a real "
                "driver login, create a driver on Fleet > Drivers -- that path "
                "mints the driver_code and PIN with an audit trail. "
                f"(${DRIVER_PIN_ENV} exists for throwaway local databases only.)"
            )
        print("  Driver code    : see Fleet > Drivers on the dashboard.")


if __name__ == "__main__":
    args = _parse_args()
    try:
        assert_seed_allowed(
            is_production=settings.is_production,
            allow_production=args.allow_production,
        )
    except ProductionSeedRefused as exc:
        # Exit 2 (not 1) so a deploy script can tell "you pointed me at
        # production" apart from "the seed itself blew up".
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2) from None
    asyncio.run(seed())
