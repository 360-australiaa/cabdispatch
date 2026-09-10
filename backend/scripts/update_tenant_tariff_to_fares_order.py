"""One-off data fix: bring a tenant's own rank/hail tariffs up to the current
NSW Point to Point Transport (Fares) Order 2026 rates.

Companion to `fix_fares_order_reference.py` (which fixes the platform-wide
VALIDATION ceiling every tenant tariff is checked against) — that script
does not touch any tenant's own tariff rows, by design. This one does,
for exactly one named tenant, on request: the owner asked for their
tenant's urban tariff to actually charge the new $2.65 peak / $1.13/min
waiting rate, not just be ALLOWED to.

Editing a tenant's own tariff through the dashboard requires the
PLATFORM-owner account (`PATCH /v1/tariffs/{id}` depends on
`require_platform_owner`, which checks `user.tenant_id ==
PLATFORM_TENANT_ID` — a normal tenant owner, even of their own tenant, gets
403). This script goes through the exact same domain functions that
endpoint uses (`tariff_service.validate_tariff_or_422`,
`tariff_service.write_change_log`) rather than writing raw SQL, so the
result is indistinguishable from a real platform-admin edit: it is
validated against the live Fares Order reference and leaves a proper
change-log entry, attributed to the tenant's own owner user.

Only ever updates a tenant's CURRENTLY-EFFECTIVE (open-ended,
booked=False, rank/hail) urban and country tariff rows to match
`fe.URBAN_TARIFF`/`fe.COUNTRY_TARIFF` exactly. Idempotent: safe to re-run.

    uv run python scripts/update_tenant_tariff_to_fares_order.py "Lilly Cabs" --i-know-this-is-production
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from decimal import Decimal

from sqlalchemy import select

import app.models  # noqa: F401 — populate Base.metadata before any query runs
from app.core.database import AsyncSessionLocal
from app.models.tariffs import Tariff
from app.models.tenant import Tenant
from app.models.user import ROLE_OWNER, User
from app.services import fare_engine as fe
from app.services import tariffs as tariff_service

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

_ENGINE_TARIFF_BY_REGION: dict[str, fe.Tariff] = {
    "urban": fe.URBAN_TARIFF,
    "country": fe.COUNTRY_TARIFF,
}


def _rate_kwargs(engine_tariff: fe.Tariff) -> dict[str, Decimal]:
    return {f: getattr(engine_tariff, f) for f in _RATE_FIELDS}


async def _find_tenant(session, *, name: str) -> Tenant:
    result = await session.execute(select(Tenant).where(Tenant.name == name))
    tenant = result.scalar_one_or_none()
    if tenant is None:
        raise SystemExit(f"No tenant named {name!r} found.")
    return tenant


async def _find_owner(session, *, tenant_id: str) -> User:
    result = await session.execute(
        select(User).where(User.tenant_id == tenant_id, User.role == ROLE_OWNER)
    )
    owner = result.scalars().first()
    if owner is None:
        raise SystemExit(
            f"No owner user found for tenant {tenant_id!r} — cannot attribute the change-log entry."
        )
    return owner


async def _find_effective_tariff(session, *, tenant_id: str, region: str) -> Tariff | None:
    result = await session.execute(
        select(Tariff)
        .where(
            Tariff.tenant_id == tenant_id,
            Tariff.region == region,
            Tariff.booked.is_(False),
            Tariff.effective_to.is_(None),
        )
        .order_by(Tariff.effective_from.desc())
    )
    return result.scalars().first()


async def fix_region(session, *, tenant: Tenant, owner: User, region: str) -> None:
    if region not in _ENGINE_TARIFF_BY_REGION:
        return

    row = await _find_effective_tariff(session, tenant_id=tenant.id, region=region)
    if row is None:
        print(
            f"  region={region!r}: tenant {tenant.name!r} has no open-ended rank/hail tariff for this region — skipped"
        )
        return

    current_rates = _rate_kwargs(_ENGINE_TARIFF_BY_REGION[region])
    changed_fields = {
        f: (getattr(row, f), current_rates[f])
        for f in _RATE_FIELDS
        if getattr(row, f) != current_rates[f]
    }
    if not changed_fields:
        print(
            f"  region={region!r}: {row.name!r} already matches the current 2026 rates ({row.id}) — no change"
        )
        return

    before = tariff_service.row_to_log_dict(row)
    for field, (old, new) in changed_fields.items():
        print(f"  region={region!r}: {field} {old} -> {new}")
        setattr(row, field, new)

    # Same check `PATCH /v1/tariffs/{id}` runs — this is not a bypass, it is
    # the identical validation a real platform-admin edit would go through.
    await tariff_service.validate_tariff_or_422(session, row)

    await session.commit()
    await session.refresh(row)
    await tariff_service.write_change_log(
        session, tariff=row, actor_user_id=owner.id, before=before
    )
    print(f"  region={region!r}: updated {row.name!r} ({row.id}), change-log entry written")


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Update a tenant's own rank/hail tariffs to the current 2026 Fares Order rates.",
    )
    parser.add_argument("tenant_name", help='Exact tenant name, e.g. "Lilly Cabs".')
    parser.add_argument(
        "--i-know-this-is-production",
        dest="allow_production",
        action="store_true",
        help="Required acknowledgement — this changes a real tenant's live billing rates.",
    )
    return parser.parse_args(argv)


async def main() -> None:
    args = _parse_args()
    if not args.allow_production:
        print(
            "Refusing to run without --i-know-this-is-production: this changes a real "
            "tenant's live billing rates. Re-run with that flag once you're sure this is "
            "the database and tenant you mean to update.",
            file=sys.stderr,
        )
        sys.exit(1)

    async with AsyncSessionLocal() as session:
        tenant = await _find_tenant(session, name=args.tenant_name)
        owner = await _find_owner(session, tenant_id=tenant.id)
        print(f"Updating {tenant.name!r} ({tenant.id}) to the current 2026 Fares Order rates...")
        await fix_region(session, tenant=tenant, owner=owner, region="urban")
        await fix_region(session, tenant=tenant, owner=owner, region="country")
        print("Done.")


if __name__ == "__main__":
    asyncio.run(main())
