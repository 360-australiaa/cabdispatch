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


class ShiftConflictError(Exception):
    """Raised by start_shift() when the target vehicle already has an open
    shift under a DIFFERENT driver and the caller didn't pass
    force_handover=True. Carries the conflicting shift so the API layer can
    build a helpful 409 message (which driver, since when) rather than a bare
    refusal — a dispatcher needs to know who to actually call."""

    def __init__(self, conflicting_shift: Shift):
        self.conflicting_shift = conflicting_shift
        super().__init__(
            f"Vehicle {conflicting_shift.vehicle_id} already has an open shift "
            f"({conflicting_shift.id}) for driver {conflicting_shift.driver_id}"
        )


async def _find_open_shift(
    session: AsyncSession, *, tenant_id: str, driver_id: str | None = None, vehicle_id: str | None = None
) -> Shift | None:
    """The most recently started open (end_at IS NULL) shift matching the
    given filters, or None. This IS the "who is currently driving this
    vehicle" / "is this driver already on shift somewhere" query — the
    system has no separate denormalized "current driver" field anywhere;
    it is always derived live from the shifts table, so it can never drift
    out of sync with reality the way a cached pointer could."""
    filters = [Shift.tenant_id == tenant_id, Shift.end_at.is_(None)]
    if driver_id is not None:
        filters.append(Shift.driver_id == driver_id)
    if vehicle_id is not None:
        filters.append(Shift.vehicle_id == vehicle_id)
    result = await session.execute(
        select(Shift).where(*filters).order_by(Shift.start_at.desc()).limit(1)
    )
    return result.scalar_one_or_none()


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
    force_handover: bool = False,
) -> Shift:
    """Opens a new shift, guarding against the two ways "who is currently
    driving this vehicle" can otherwise go ambiguous on a real fleet where
    one vehicle runs back-to-back 12-hour shifts across two+ drivers:

    1. The SAME driver already has a dangling open shift (they forgot to tap
       "End Shift" last time, or the app crashed). This is common and
       harmless to auto-recover from — a person cannot literally be driving
       two shifts at once, so a fresh start unambiguously means their old
       session is over. Auto-closed at this shift's start_at, aggregates
       recomputed normally via end_shift(), no data lost.

    2. The vehicle already has an open shift under a DIFFERENT driver — e.g.
       driver A's 12-hour shift is still showing open (they forgot to end
       it, or the handover call didn't happen yet) when driver B tries to
       start theirs on the same car. This is NOT auto-resolved: it raises
       ShiftConflictError so the caller sees exactly who the vehicle is
       currently assigned to, unless the caller explicitly passes
       force_handover=True (the real "shift changeover" action — a
       dispatcher confirming the handover, or the outgoing driver having
       just ended their own shift moments before). This is the guard that
       stops two drivers from ever simultaneously "having" the same vehicle
       on paper, which would otherwise make trip attribution and incident
       liability ambiguous.
    """
    effective_start_at = start_at or datetime.now(UTC)

    own_dangling_shift = await _find_open_shift(session, tenant_id=tenant_id, driver_id=driver_id)
    if own_dangling_shift is not None:
        logger.info(
            "start_shift: driver %s had a dangling open shift %s (vehicle %s) — "
            "auto-closing it at this shift's start_at before opening the new one.",
            driver_id,
            own_dangling_shift.id,
            own_dangling_shift.vehicle_id,
        )
        await end_shift(
            session,
            own_dangling_shift,
            end_at=effective_start_at,
            psl_owed=Decimal(0),
            reconciled=False,
        )

    vehicle_conflict = await _find_open_shift(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    if vehicle_conflict is not None and vehicle_conflict.driver_id != driver_id:
        if not force_handover:
            raise ShiftConflictError(vehicle_conflict)
        logger.info(
            "start_shift: vehicle %s handed over from driver %s (shift %s) to driver %s "
            "via force_handover.",
            vehicle_id,
            vehicle_conflict.driver_id,
            vehicle_conflict.id,
            driver_id,
        )
        await end_shift(
            session,
            vehicle_conflict,
            end_at=effective_start_at,
            psl_owed=Decimal(0),
            reconciled=False,
        )

    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver_id,
        vehicle_id=vehicle_id,
        start_at=effective_start_at,
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
