"""Business logic for the trips domain: tariff resolution, haversine distance,
and the glue between persisted `Trip` rows and the pure `app.services.fare_engine`.

Kept separate from the router so the fare-reconstruction logic (build a
`FareState` identical to the one that produced the currently-persisted running
totals) has exactly one implementation, used by both the online tick/close
endpoints and the offline sync recompute path.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from math import asin, cos, radians, sin, sqrt

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.tariffs import Tariff as TariffRow
from app.models.trips import TRIP_TYPE_AIRPORT_FIXED, Trip
from app.schemas.trips import TelemetryPoint
from app.services.fare_engine import (
    FareBreakdown,
    FareEngine,
    FareState,
    Tariff,
    TimeClass,
    airport_fixed_fare,
    round_half_up,
)
from app.services.tariffs import to_fare_engine_tariff

engine = FareEngine()

_EARTH_RADIUS_KM = Decimal("6371.0088")


class UnknownTariffError(ValueError):
    """Raised when tariff_id does not resolve to a fare-engine-usable Tariff
    row owned by the requesting tenant."""


async def resolve_tariff(session: AsyncSession, *, tenant_id: str, tariff_id: str) -> Tariff:
    """Looks `tariff_id` up in the sibling `tariffs` domain's table, scoped to
    the requesting tenant (never the platform-wide tenant_id=NULL reference
    row — that exists only for Fares Order rate-cap validation, not to be
    driven directly), and converts it to the pure fare_engine.Tariff the
    engine operates on."""
    result = await session.execute(
        select(TariffRow).where(TariffRow.id == tariff_id, TariffRow.tenant_id == tenant_id)
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise UnknownTariffError(
            f"tariff_id '{tariff_id}' does not match a tariff owned by this tenant"
        )
    try:
        return to_fare_engine_tariff(row)
    except ValueError as exc:
        raise UnknownTariffError(str(exc)) from exc


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> Decimal:
    """Great-circle distance between two lat/lng points, in kilometres."""
    phi1, phi2 = radians(lat1), radians(lat2)
    dphi = radians(lat2 - lat1)
    dlambda = radians(lng2 - lng1)
    a = sin(dphi / 2) ** 2 + cos(phi1) * cos(phi2) * sin(dlambda / 2) ** 2
    c = 2 * asin(sqrt(a))
    return _EARTH_RADIUS_KM * Decimal(str(c))


async def build_fare_state(session: AsyncSession, *, tenant_id: str, trip: Trip) -> FareState:
    """Reconstructs the FareState a fresh trip would have accrued to, from the
    trip row's currently-persisted running totals. Safe to call repeatedly —
    does not mutate `trip`."""
    tariff = await resolve_tariff(session, tenant_id=tenant_id, tariff_id=trip.tariff_id)
    fixed_fare = airport_fixed_fare(trip.maxi) if trip.type == TRIP_TYPE_AIRPORT_FIXED else None
    return FareState(
        tariff=tariff,
        time_class=TimeClass(trip.time_class),
        is_peak=trip.is_peak,
        maxi=trip.maxi,
        hired=True,
        cumulative_distance_km=Decimal(trip.distance_m) / Decimal(1000),
        accrued_distance_charge=trip.dist_amount,
        accrued_waiting_charge=trip.wait_amount,
        tolls=trip.tolls,
        extras=trip.extras,
        fixed_fare=fixed_fare,
    )


async def apply_tick(
    session: AsyncSession, *, tenant_id: str, trip: Trip, points: list[TelemetryPoint]
) -> Trip:
    """Feeds a batch of telemetry points through the fare engine sequentially,
    mutating `trip`'s running totals + tick-continuity anchor in place.
    Does NOT commit — caller owns the session/transaction."""
    state = await build_fare_state(session, tenant_id=tenant_id, trip=trip)

    prev_lat = trip.last_lat if trip.last_lat is not None else trip.start_lat
    prev_lng = trip.last_lng if trip.last_lng is not None else trip.start_lng
    prev_ts = trip.last_ts if trip.last_ts is not None else trip.start_at

    moving_delta = Decimal(0)
    waiting_delta = Decimal(0)

    for point in points:
        distance_km = haversine_km(prev_lat, prev_lng, point.lat, point.lng)
        elapsed_seconds = Decimal(0)
        if prev_ts is not None:
            ts_prev = prev_ts if prev_ts.tzinfo else prev_ts.replace(tzinfo=UTC)
            ts_point = point.ts if point.ts.tzinfo else point.ts.replace(tzinfo=UTC)
            elapsed_seconds = Decimal(str(max((ts_point - ts_prev).total_seconds(), 0)))

        state = engine.tick(
            state,
            speed_kmh=Decimal(str(point.speed_kmh)),
            distance_delta_km=distance_km,
            elapsed_seconds=elapsed_seconds,
        )

        if state.last_mode == "waiting":
            waiting_delta += elapsed_seconds
        else:
            moving_delta += elapsed_seconds

        prev_lat, prev_lng, prev_ts = point.lat, point.lng, point.ts

    trip.distance_m = round(state.cumulative_distance_km * Decimal(1000))
    trip.dist_amount = round_half_up(state.accrued_distance_charge)
    trip.wait_amount = round_half_up(state.accrued_waiting_charge)
    trip.moving_s += int(moving_delta)
    trip.waiting_s += int(waiting_delta)
    trip.last_lat, trip.last_lng, trip.last_ts = prev_lat, prev_lng, prev_ts

    return trip


@dataclass
class CloseParams:
    end_at: datetime
    end_lat: float | None
    end_lng: float | None
    payment_method: str
    surcharge_pct: Decimal | None
    cleaning_fee: Decimal
    include_psl: bool
    receipt_ref: str | None


async def close_trip(session: AsyncSession, *, tenant_id: str, trip: Trip, params: CloseParams) -> FareBreakdown:
    """Finalizes `trip` via the fare engine's close(), storing the breakdown.
    Does NOT commit — caller owns the session/transaction. Returns the
    breakdown for the caller to surface if desired."""
    # cleaning_fee has no dedicated column on Trip (not in the domain's field
    # list) — fold it into `extras` before building state so it flows through
    # engine.close() as a genuine dollar amount rather than being dropped.
    if params.cleaning_fee:
        trip.extras = (trip.extras or Decimal(0)) + params.cleaning_fee

    state = await build_fare_state(session, tenant_id=tenant_id, trip=trip)
    breakdown = engine.close(
        state,
        payment_method=params.payment_method,
        surcharge_pct=params.surcharge_pct,
        cleaning_fee=Decimal(0),
        include_psl=params.include_psl,
    )

    trip.flag_fall = breakdown.flag_fall
    trip.peak_amount = breakdown.peak_charge
    trip.dist_amount = breakdown.distance_charge
    trip.wait_amount = breakdown.waiting_charge
    trip.tolls = breakdown.tolls
    trip.psl = breakdown.psl
    trip.extras = breakdown.extras
    trip.subtotal = breakdown.fare_total
    trip.surcharge = breakdown.surcharge
    trip.total = breakdown.grand_total
    trip.gst_component = breakdown.gst_component
    trip.payment_method = params.payment_method
    trip.status = "closed"
    trip.end_at = params.end_at
    trip.end_lat = params.end_lat
    trip.end_lng = params.end_lng
    trip.max_fare_check_passed = True  # no device_total to compare for online closes
    trip.receipt_ref = params.receipt_ref or f"RCPT-{trip.id[:8].upper()}"

    return breakdown


async def recompute_from_trace(
    session: AsyncSession,
    *,
    tenant_id: str,
    tariff_id: str,
    trip_type: str,
    time_class: str,
    is_peak: bool,
    maxi: bool,
    tolls: Decimal,
    extras: Decimal,
    cleaning_fee: Decimal,
    start_lat: float,
    start_lng: float,
    start_at: datetime,
    gps_trace: list[TelemetryPoint],
    payment_method: str,
    surcharge_pct: Decimal | None,
    include_psl: bool,
) -> tuple[FareBreakdown, int, int, int]:
    """Server-side canonical recompute of a fare from a submitted raw GPS
    trace, used by POST /v1/trips/sync to validate a device's own total.
    Returns (breakdown, distance_m, moving_s, waiting_s)."""
    tariff = await resolve_tariff(session, tenant_id=tenant_id, tariff_id=tariff_id)
    fixed_fare = airport_fixed_fare(maxi) if trip_type == TRIP_TYPE_AIRPORT_FIXED else None

    state = FareState(
        tariff=tariff,
        time_class=TimeClass(time_class),
        is_peak=is_peak,
        maxi=maxi,
        hired=True,
        tolls=tolls,
        extras=extras + cleaning_fee,
        fixed_fare=fixed_fare,
    )

    prev_lat, prev_lng, prev_ts = start_lat, start_lng, start_at
    moving_s = 0
    waiting_s = 0

    for point in gps_trace:
        distance_km = haversine_km(prev_lat, prev_lng, point.lat, point.lng)
        ts_prev = prev_ts if prev_ts.tzinfo else prev_ts.replace(tzinfo=UTC)
        ts_point = point.ts if point.ts.tzinfo else point.ts.replace(tzinfo=UTC)
        elapsed_seconds = Decimal(str(max((ts_point - ts_prev).total_seconds(), 0)))

        state = engine.tick(
            state,
            speed_kmh=Decimal(str(point.speed_kmh)),
            distance_delta_km=distance_km,
            elapsed_seconds=elapsed_seconds,
        )
        if state.last_mode == "waiting":
            waiting_s += int(elapsed_seconds)
        else:
            moving_s += int(elapsed_seconds)

        prev_lat, prev_lng, prev_ts = point.lat, point.lng, point.ts

    breakdown = engine.close(
        state,
        payment_method=payment_method,
        surcharge_pct=surcharge_pct,
        cleaning_fee=Decimal(0),
        include_psl=include_psl,
    )
    distance_m = round(state.cumulative_distance_km * Decimal(1000))
    return breakdown, distance_m, moving_s, waiting_s


def compute_variance_pct(recomputed_total: Decimal, device_total: Decimal) -> Decimal:
    """abs(recomputed - device) / recomputed * 100, half-up to 2dp. If the
    recomputed total is exactly zero, treat any nonzero device total as 100%
    variance (avoids a ZeroDivisionError) and a matching zero as 0%."""
    if recomputed_total == 0:
        return Decimal("100.00") if device_total != 0 else Decimal("0.00")
    variance = abs(recomputed_total - device_total) / recomputed_total * Decimal(100)
    return round_half_up(variance)
