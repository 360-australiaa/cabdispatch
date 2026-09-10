"""One-off data fix: refresh the stale global (tenant_id IS NULL) Fares Order
reference tariffs to the current NSW Point to Point Transport (Fares) Order
2026 rates.

### The bug this fixes
`scripts/seed.py`'s `get_or_create_global_reference_tariff` is a strict
get-or-create: if a row already exists for a region, it is returned AS-IS,
never updated. That row was created once, on this deployment's first seed
run, using whatever `app.services.fare_engine.URBAN_TARIFF`/`COUNTRY_TARIFF`
held AT THAT TIME. Those constants have since been bumped in code to the
2026 Order's rates (see fare_engine.py's own module docstring — "NSW Point
to Point Transport (Fares) Order 2026, effective 1 June 2026"), but nothing
ever re-ran to bring the already-seeded DATABASE row along with them. The
result, live on production (2026-09-10): every tenant's rank/hail tariff
edit is validated against a global reference row still named "NSW Fares
Order 2025 no.2 — urban reference" and still capped at the OLD $2.56 peak
charge / 109.2c waiting rate, silently blocking a tenant from ever adopting
the CURRENT, correct $2.65 / 113.0c 2026 rates -- the exact numbers already
sitting in `fe.URBAN_TARIFF`/`fe.COUNTRY_TARIFF` this whole time.

### What this does
For each region (urban, country): finds the existing tenant_id IS NULL
reference `Tariff` row. If its rate fields already match
`fe.URBAN_TARIFF`/`fe.COUNTRY_TARIFF` exactly, does nothing (safe to re-run).
Otherwise updates every rate field in place to match, and renames it from
the stale "NSW Fares Order 2025 no.2 — {region} reference" to "NSW Fares
Order 2026 — {region} reference" -- but ONLY if the name is still exactly
that stale default (a platform admin who has since renamed it deliberately
is left alone). If no row exists yet for a region at all, creates one fresh
with the current rates, same shape `seed.py` would have produced on a new
deployment.

Never touches any tenant's own tariff rows -- only the two global reference
rows this validates against. Idempotent: safe to re-run.

    uv run python scripts/fix_fares_order_reference.py --i-know-this-is-production
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import select

import app.models  # noqa: F401 — populate Base.metadata before any query runs
from app.core.database import AsyncSessionLocal
from app.models.tariffs import Tariff
from app.services import fare_engine as fe

FARES_ORDER_EFFECTIVE_FROM = datetime(2026, 6, 1, tzinfo=UTC)

_STALE_NAME = "NSW Fares Order 2025 no.2 — {region} reference"
_CURRENT_NAME = "NSW Fares Order 2026 — {region} reference"

# Mirrors scripts/seed.py's _RATE_FIELDS exactly — the fields shared 1:1
# between fare_engine.Tariff and the DB Tariff model.
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


async def fix_region(session, *, region: str) -> None:
    engine_tariff = _ENGINE_TARIFF_BY_REGION[region]
    current_rates = _rate_kwargs(engine_tariff)

    result = await session.execute(
        select(Tariff).where(Tariff.tenant_id.is_(None), Tariff.region == region)
    )
    row = result.scalar_one_or_none()

    if row is None:
        row = Tariff(
            tenant_id=None,
            name=_CURRENT_NAME.format(region=region),
            region=region,
            effective_from=FARES_ORDER_EFFECTIVE_FROM,
            effective_to=None,
            booked=False,
            **current_rates,
        )
        session.add(row)
        await session.commit()
        print(
            f"  region={region!r}: no reference row existed — created fresh with 2026 rates ({row.id})"
        )
        return

    changed_fields = {
        f: (getattr(row, f), current_rates[f])
        for f in _RATE_FIELDS
        if getattr(row, f) != current_rates[f]
    }
    stale_name = row.name == _STALE_NAME.format(region=region)

    if not changed_fields and not stale_name:
        print(f"  region={region!r}: already matches the current 2026 rates ({row.id}) — no change")
        return

    for field, (before, after) in changed_fields.items():
        print(f"  region={region!r}: {field} {before} -> {after}")
        setattr(row, field, after)

    if stale_name:
        new_name = _CURRENT_NAME.format(region=region)
        print(f"  region={region!r}: name {row.name!r} -> {new_name!r}")
        row.name = new_name

    await session.commit()
    print(f"  region={region!r}: updated ({row.id})")


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Refresh the stale global Fares Order reference tariffs to the "
        "current 2026 rates. Writes to whichever database DATABASE_URL points at.",
    )
    parser.add_argument(
        "--i-know-this-is-production",
        dest="allow_production",
        action="store_true",
        help="Required acknowledgement — this updates live regulatory reference "
        "data every tenant's tariff edits are validated against.",
    )
    return parser.parse_args(argv)


async def main() -> None:
    args = _parse_args()
    if not args.allow_production:
        print(
            "Refusing to run without --i-know-this-is-production: this updates the "
            "global Fares Order reference rows every tenant's tariff edits are "
            "validated against. Re-run with that flag once you're sure this is the "
            "database you mean to fix.",
            file=sys.stderr,
        )
        sys.exit(1)

    async with AsyncSessionLocal() as session:
        print("Checking global Fares Order reference tariffs against the current 2026 rates...")
        await fix_region(session, region="urban")
        await fix_region(session, region="country")
        print("Done.")


if __name__ == "__main__":
    asyncio.run(main())
