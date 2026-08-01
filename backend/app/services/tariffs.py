"""Business logic for the tariffs domain: converting a DB `Tariff` row into the
fare engine's pure `Tariff` dataclass, resolving the currently-effective
tariff for a region/instant, running Fares Order rate-cap validation on
create/update, and writing the append-only change log.
"""
from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.tariffs import REGION_COUNTRY, REGION_EXEMPT, REGION_URBAN, Tariff, TariffChangeLog
from app.schemas.tariffs import TariffCreate, TariffUpdate
from app.services import fare_engine as fe

# Rate fields shared 1:1 between the DB model and fare_engine.Tariff.
_FARE_ENGINE_FIELDS = (
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

_AREA_BY_REGION = {
    REGION_URBAN: fe.AreaClass.URBAN,
    REGION_COUNTRY: fe.AreaClass.COUNTRY,
}

# Hardcoded Fares Order 2025 (no.2) fallback references, used to validate a
# rank/hail tariff when no DB-seeded global (tenant_id IS NULL) reference row
# exists yet for the region — this domain does not depend on the integration
# step's seed having run for POST/PATCH validation to work correctly.
_HARDCODED_REFERENCE = {
    REGION_URBAN: fe.URBAN_TARIFF,
    REGION_COUNTRY: fe.COUNTRY_TARIFF,
}


def to_fare_engine_tariff(row: Tariff) -> fe.Tariff:
    """Maps a DB `Tariff` row (region urban|country) to the pure
    `fare_engine.Tariff` dataclass. Raises ValueError for region="exempt" —
    exempt tariffs have no fare-engine/Fares Order equivalent."""
    if row.region not in _AREA_BY_REGION:
        raise ValueError(f"region '{row.region}' has no fare_engine.Tariff equivalent")

    kwargs: dict[str, Any] = {f: getattr(row, f) for f in _FARE_ENGINE_FIELDS}
    return fe.Tariff(name=row.name, area=_AREA_BY_REGION[row.region], **kwargs)


async def _resolve_effective_row(
    session: AsyncSession, *, tenant_id: str | None, region: str, at: datetime
) -> Tariff | None:
    """Shared resolution logic: the tariff for (tenant_id, region) whose
    [effective_from, effective_to) window contains `at`, most recent
    effective_from wins if more than one somehow matches."""
    stmt = (
        select(Tariff)
        .where(Tariff.region == region)
        .where(Tariff.effective_from <= at)
        .where((Tariff.effective_to.is_(None)) | (Tariff.effective_to > at))
        .order_by(Tariff.effective_from.desc())
        .limit(1)
    )
    stmt = stmt.where(Tariff.tenant_id == tenant_id) if tenant_id is not None else stmt.where(
        Tariff.tenant_id.is_(None)
    )
    result = await session.execute(stmt)
    return result.scalar_one_or_none()


async def resolve_active_tariff(
    session: AsyncSession, *, tenant_id: str, region: str, at: datetime
) -> Tariff | None:
    """Resolves the tenant's currently-effective tariff for a region at `at`."""
    return await _resolve_effective_row(session, tenant_id=tenant_id, region=region, at=at)


async def resolve_global_reference_row(
    session: AsyncSession, *, region: str, at: datetime
) -> Tariff | None:
    """Resolves the platform-wide (tenant_id IS NULL) Fares Order reference row
    for a region at `at`. Returns None until the integration step seeds it."""
    return await _resolve_effective_row(session, tenant_id=None, region=region, at=at)


async def resolve_fares_order_reference(
    session: AsyncSession, *, region: str, at: datetime
) -> fe.Tariff | None:
    """The reference used for POST/PATCH validation: prefer the DB-seeded
    global row for the region if one is currently effective, else fall back to
    the hardcoded fare_engine constants. Returns None for region="exempt"
    (no reference applies) or if `region` isn't urban/country."""
    if region not in _AREA_BY_REGION:
        return None

    row = await resolve_global_reference_row(session, region=region, at=at)
    if row is not None:
        return to_fare_engine_tariff(row)
    return _HARDCODED_REFERENCE.get(region)


async def validate_tariff_or_422(
    session: AsyncSession, tariff_data: TariffCreate | Tariff, *, at: datetime | None = None
) -> None:
    """Runs `fare_engine.validate_against_fares_order` for rank/hail (booked=
    False) urban/country tariffs and raises HTTP 422 on violation. No-ops for
    booked=True (unregulated) or region="exempt" (outside Fares Order
    jurisdiction) tariffs."""
    region = tariff_data.region
    booked = tariff_data.booked

    if booked or region == REGION_EXEMPT:
        return
    if region not in _AREA_BY_REGION:
        return

    # Build a throwaway fare_engine.Tariff from the candidate data without
    # requiring it to already be a persisted row.
    candidate = fe.Tariff(
        name=tariff_data.name,
        area=_AREA_BY_REGION[region],
        **{f: getattr(tariff_data, f) for f in _FARE_ENGINE_FIELDS},
    )

    reference = await resolve_fares_order_reference(session, region=region, at=at or _utcnow())
    if reference is None:
        # No reference available at all (region has no hardcoded fallback,
        # e.g. would only happen for a future unrecognized region) — nothing
        # to validate against, let it through.
        return

    try:
        fe.validate_against_fares_order(candidate, reference, booked=False)
    except fe.FaresOrderViolation as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc


def _utcnow() -> datetime:
    return datetime.now(UTC)


def row_to_log_dict(row: Tariff) -> dict[str, Any]:
    """JSON-safe snapshot of a tariff row for the change log (Decimal -> str,
    datetime -> isoformat)."""

    def _coerce(value: Any) -> Any:
        if isinstance(value, Decimal):
            return str(value)
        if isinstance(value, datetime):
            return value.isoformat()
        return value

    fields = (
        "id",
        "tenant_id",
        "name",
        "region",
        "effective_from",
        "effective_to",
        "booked",
        *_FARE_ENGINE_FIELDS,
    )
    return {f: _coerce(getattr(row, f)) for f in fields}


async def write_change_log(
    session: AsyncSession,
    *,
    tariff: Tariff,
    actor_user_id: str,
    before: dict[str, Any] | None,
) -> TariffChangeLog:
    """Writes one immutable change-log row. Caller is responsible for having
    already flushed/committed `tariff` so `after` reflects the persisted
    state."""
    entry = TariffChangeLog(
        tariff_id=tariff.id,
        tenant_id=tariff.tenant_id,
        actor_user_id=actor_user_id,
        before_json=before,
        after_json=row_to_log_dict(tariff),
    )
    session.add(entry)
    await session.commit()
    await session.refresh(entry)
    return entry


def apply_update(row: Tariff, patch: TariffUpdate) -> None:
    """Mutates `row` in place with the non-None fields of `patch`."""
    for field, value in patch.model_dump(exclude_unset=True).items():
        setattr(row, field, value)


__all__ = [
    "apply_update",
    "resolve_active_tariff",
    "resolve_fares_order_reference",
    "resolve_global_reference_row",
    "row_to_log_dict",
    "to_fare_engine_tariff",
    "validate_tariff_or_422",
    "write_change_log",
]
