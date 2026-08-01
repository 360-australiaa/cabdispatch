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
    owner, tenant TCT) and one demo tenant admin (owner@lillycabs.test /
    ChangeMe123!, role owner, tenant Lilly Cabs) so both the API and the
    dashboard can be logged into afterward via POST /v1/auth/login.

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
from app.models.tariffs import Tariff
from app.models.tenant import Tenant
from app.models.user import ROLE_OWNER, User
from app.services import fare_engine as fe

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
    session, *, email: str, tenant_id: str | None, role: str, name: str, password: str
) -> User:
    result = await session.execute(select(User).where(User.email == email))
    existing = result.scalar_one_or_none()
    if existing is not None:
        return existing

    user = User(
        tenant_id=tenant_id,
        role=role,
        name=name,
        email=email,
        pin_hash=hash_password(password),
        status="active",
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

        print("\nSeed complete.")
        print(f"  Platform tenant : {platform_tenant.id} ({platform_tenant.name})")
        print(f"  Demo tenant     : {demo_tenant.id} ({demo_tenant.name})")
        print("  Login as either user via POST /v1/auth/login with password 'ChangeMe123!':")
        print("    admin@cabdispatch.test    (platform owner, cross-tenant)")
        print("    owner@lillycabs.test      (Lilly Cabs owner)")


if __name__ == "__main__":
    asyncio.run(seed())
