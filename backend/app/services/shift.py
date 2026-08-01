"""Shifts domain business logic: opening/closing a shift and recomputing its
trip aggregates.

Cross-domain note: `_recompute_trip_aggregates` reads the sibling `trips`
domain's `Trip` model (`app.models.trips`) to sum up a shift's trips at close
time. That table is owned by another agent's slice of this codebase; the import
is wrapped so this domain degrades gracefully (aggregates left at zero, with a
logged warning) rather than hard-crashing if `trips` is ever absent — e.g. if
this module is exercised standalone before the two domains are integrated.
"""
from __future__ import annotations

import logging
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.shift import Shift
from app.services.fare_engine import round_half_up

logger = logging.getLogger("cab_dispatch.shift")

# Payment methods treated as "cash" for the cash-vs-card reconciliation split.
# Everything else observed on a trip (tap_to_pay, link, cabcharge, ttss, ...) is
# counted into card_total. Mirrors the `payments.method` enum in the product spec.
_CASH_METHOD = "cash"


async def _recompute_trip_aggregates(
    session: AsyncSession, *, tenant_id: str, shift_id: str
) -> tuple[int, Decimal, Decimal, Decimal]:
    """Returns (trips_count, km_total, cash_total, card_total) computed fresh
    from the shift's own closed trips. Authoritative — never trusts client input."""
    try:
        from app.models.trips import Trip
    except ImportError:  # pragma: no cover - defensive only, see module docstring
        logger.warning(
            "app.models.trips.Trip not importable — leaving shift %s aggregates at "
            "zero. Expected only if this domain is exercised before the trips "
            "domain is present in the tree.",
            shift_id,
        )
        return 0, Decimal(0), Decimal(0), Decimal(0)

    base_filter = (Trip.tenant_id == tenant_id, Trip.shift_id == shift_id)

    trips_count = (
        await session.execute(select(func.count(Trip.id)).where(*base_filter))
    ).scalar_one() or 0

    distance_m_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.distance_m), 0)).where(*base_filter)
        )
    ).scalar_one() or 0
    km_total = (Decimal(distance_m_total) / Decimal(1000)).quantize(Decimal("0.001"))

    cash_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.total), 0)).where(
                *base_filter, Trip.payment_method == _CASH_METHOD
            )
        )
    ).scalar_one() or 0
    card_total = (
        await session.execute(
            select(func.coalesce(func.sum(Trip.total), 0)).where(
                *base_filter, Trip.payment_method != _CASH_METHOD
            )
        )
    ).scalar_one() or 0

    return (
        int(trips_count),
        km_total,
        round_half_up(Decimal(str(cash_total))),
        round_half_up(Decimal(str(card_total))),
    )


async def start_shift(
    session: AsyncSession,
    *,
    tenant_id: str,
    driver_id: str,
    vehicle_id: str,
    start_at: datetime | None,
    inspection_json: dict | None,
) -> Shift:
    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver_id,
        vehicle_id=vehicle_id,
        start_at=start_at or datetime.now(UTC),
        inspection_json=inspection_json,
    )
    session.add(shift)
    await session.commit()
    await session.refresh(shift)
    return shift


async def end_shift(
    session: AsyncSession,
    shift: Shift,
    *,
    end_at: datetime | None,
    psl_owed: Decimal,
    reconciled: bool,
) -> Shift:
    """Closes a shift: stamps `end_at`, recomputes the four trip-derived
    aggregates from that shift's own trips, and records the reconciliation
    figures supplied by the caller."""
    trips_count, km_total, cash_total, card_total = await _recompute_trip_aggregates(
        session, tenant_id=shift.tenant_id, shift_id=shift.id
    )

    shift.end_at = end_at or datetime.now(UTC)
    shift.trips_count = trips_count
    shift.km_total = km_total
    shift.cash_total = cash_total
    shift.card_total = card_total
    shift.psl_owed = psl_owed
    shift.reconciled = reconciled

    await session.commit()
    await session.refresh(shift)
    return shift


def build_report(shift: Shift) -> dict:
    """Builds the JSON summary payload for `GET /v1/shifts/{id}/report`.

    TODO(reporting): PDF/CSV export — no PDF library is available in this pass;
    a later pass can render this same dict via e.g. weasyprint (PDF) or stdlib
    `csv` (CSV) without changing this function's contract.
    """
    duration_minutes: float | None = None
    if shift.end_at is not None:
        duration_minutes = round((shift.end_at - shift.start_at).total_seconds() / 60, 2)

    return {
        "shift_id": shift.id,
        "tenant_id": shift.tenant_id,
        "driver_id": shift.driver_id,
        "vehicle_id": shift.vehicle_id,
        "start_at": shift.start_at,
        "end_at": shift.end_at,
        "duration_minutes": duration_minutes,
        "trips_count": shift.trips_count,
        "km_total": shift.km_total,
        "cash_total": shift.cash_total,
        "card_total": shift.card_total,
        "total_takings": round_half_up(shift.cash_total + shift.card_total),
        "psl_owed": shift.psl_owed,
        "reconciled": shift.reconciled,
        "inspection_json": shift.inspection_json,
        "generated_at": datetime.now(UTC),
    }
