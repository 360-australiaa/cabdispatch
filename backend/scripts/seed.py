"""Dev/demo seed data.

Creates:
  - the platform tenant "TCT" (id = the PLATFORM_TENANT_ID sentinel in
    app.core.security), a global (tenant_id IS NULL) Fares Order reference
    tariff for both urban and country regions with the EXACT NSW Fares Order
    2025 (no.2) rates (re-derived from app.services.fare_engine's
    URBAN_TARIFF / COUNTRY_TARIFF constants — the same source of truth the
    golden fare-engine tests assert against, not re-guessed).
  - a demo tenant "Lilly Cabs" with its own urban rank/hail tariff, a copy of
    the reference rates (a tenant tariff must never exceed the Fares Order
    cap — see app.services.tariffs.validate_tariff_or_422).
  - one platform admin user (admin@cabdispatch.test / ChangeMe123!, role
    owner, tenant TCT), one demo tenant admin (owner@lillycabs.test /
    ChangeMe123!, role owner, tenant Lilly Cabs), and one demo driver
    (driver@lillycabs.test / ChangeMe123!, role driver, tenant Lilly Cabs —
    this is what the meter/driver app's "Driver ID"/"PIN" fields map to,
    see app.domain.DriverAuthRepository on the Android side) so the API,
    dashboard, and driver app can all be logged into afterward via
    POST /v1/auth/login.

Idempotent: safe to re-run — every row is looked up by its natural key first
and only created if missing.

    uv run python scripts/seed.py
"""
from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import select

import app.models  # noqa: F401 — populate Base.metadata before any query runs
from app.core.database import AsyncSessionLocal
from app.core.security import PLATFORM_TENANT_ID, hash_password
from app.models.geofence import GEOFENCE_KIND_REGION, GEOFENCE_KIND_TOLL, Geofence
from app.models.tariffs import Tariff
from app.models.tenant import Tenant
from app.models.user import ROLE_DRIVER, ROLE_OWNER, User
from app.services import fare_engine as fe
from app.services.user import generate_unique_driver_code

DEMO_PASSWORD = "ChangeMe123!"
PLATFORM_TENANT_NAME = "TCT"
DEMO_TENANT_NAME = "Lilly Cabs"

# NSW Fares Order 2025 (no.2), effective 3 Nov 2025 — matches the golden
# fare-engine tests in tests/test_fare_engine_golden.py exactly.
FARES_ORDER_EFFECTIVE_FROM = datetime(2025, 11, 3, tzinfo=UTC)

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
)


def _rate_kwargs(engine_tariff: fe.Tariff) -> dict[str, Decimal]:
    return {f: getattr(engine_tariff, f) for f in _RATE_FIELDS}


# --- global (tenant_id IS NULL) reference geofences — blueprint 5.2.4/7.2.5 --
#
# Coordinates are APPROXIMATE real-world landmark locations (a few hundred
# metres of slop is expected/acceptable — this is dev/demo seed data for a
# "near this landmark" circle check, not a survey-grade toll-gantry position),
# hand-picked from public knowledge of each location, not from any live
# tolling-authority feed. `toll_amount` values are similarly ILLUSTRATIVE
# round figures for dev/demo purposes, not live Transurban/tolling-authority
# rates — a real deployment would source both from an authoritative feed
# before going live.
GLOBAL_GEOFENCES: list[dict] = [
    {
        "name": "M5 East Motorway — Sydney entry (approx.)",
        "kind": GEOFENCE_KIND_TOLL,
        "center_lat": -33.9333,
        "center_lng": 151.0900,
        "radius_m": 300,
        "toll_amount": Decimal("3.21"),
    },
    {
        "name": "Sydney Harbour Bridge / Tunnel (approx.)",
        "kind": GEOFENCE_KIND_TOLL,
        "center_lat": -33.8523,
        "center_lng": 151.2108,
        "radius_m": 400,
        "toll_amount": Decimal("4.82"),
    },
    {
        # A "region" example (not a toll) — the airport precinct fits blueprint
        # 7.2.5's tariff-zone use case (see app.models.trips.TRIP_TYPE_AIRPORT_FIXED)
        # more naturally than 5.2.4's toll-detection use case.
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


async def seed() -> None:
    async with AsyncSessionLocal() as session:
        print("Seeding platform tenant + global Fares Order reference tariffs...")
        platform_tenant = await get_or_create_tenant(
            session, tenant_id=PLATFORM_TENANT_ID, name=PLATFORM_TENANT_NAME, plan="platform"
        )
        await get_or_create_global_reference_tariff(session, region="urban", engine_tariff=fe.URBAN_TARIFF)
        await get_or_create_global_reference_tariff(session, region="country", engine_tariff=fe.COUNTRY_TARIFF)

        print("Seeding global toll/region reference geofences...")
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
            password=DEMO_PASSWORD,
        )
        await get_or_create_user(
            session,
            email="owner@lillycabs.test",
            tenant_id=demo_tenant.id,
            role=ROLE_OWNER,
            name="Lilly Cabs Owner",
            password=DEMO_PASSWORD,
        )
        # Only mint a random driver_code (app.services.user.generate_unique_driver_code
        # — same alphabet/length the real POST /v1/users auto-generation path
        # uses) if the demo driver doesn't already have one from a prior run;
        # get_or_create_user backfills it onto an existing pre-migration row too.
        existing_driver = (
            await session.execute(select(User).where(User.email == "driver@lillycabs.test"))
        ).scalar_one_or_none()
        demo_driver_code = (
            await generate_unique_driver_code(session)
            if existing_driver is None or existing_driver.driver_code is None
            else None
        )
        demo_driver = await get_or_create_user(
            session,
            email="driver@lillycabs.test",
            tenant_id=demo_tenant.id,
            role=ROLE_DRIVER,
            name="Demo Driver",
            password=DEMO_PASSWORD,
            driver_code=demo_driver_code,
        )

        print("\nSeed complete.")
        print(f"  Platform tenant : {platform_tenant.id} ({platform_tenant.name})")
        print(f"  Demo tenant     : {demo_tenant.id} ({demo_tenant.name})")
        print("  Login as any of these via POST /v1/auth/login with password 'ChangeMe123!':")
        print("    admin@cabdispatch.test    (platform owner, cross-tenant)")
        print("    owner@lillycabs.test      (Lilly Cabs owner)")
        print("    driver@lillycabs.test     (Lilly Cabs driver — meter/app 'Driver ID' login)")
        print(
            f"  Demo driver_code: {demo_driver.driver_code}  "
            "(POST /v1/auth/driver-login with pin 'ChangeMe123!')"
        )


if __name__ == "__main__":
    asyncio.run(seed())
