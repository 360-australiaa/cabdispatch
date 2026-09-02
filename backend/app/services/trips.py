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

from app.models.fleet import Vehicle
from app.models.geofence import GEOFENCE_KIND_TOLL
from app.models.tariffs import Tariff as TariffRow
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_TYPE_AIRPORT_FIXED, Trip
from app.schemas.trips import TelemetryPoint
from app.services import payments as payments_service
from app.services.fare_engine import (
    FareBreakdown,
    FareEngine,
    FareState,
    Tariff,
    TimeClass,
    airport_fixed_fare,
    resolve_time_class_and_peak,
    round_half_up,
)
from app.services.geofence import detect_geofences
from app.services.tariffs import to_fare_engine_tariff

engine = FareEngine()

_EARTH_RADIUS_KM = Decimal("6371.0088")


class UnknownTariffError(ValueError):
    """Raised when tariff_id does not resolve to a fare-engine-usable Tariff
    row owned by the requesting tenant."""


class SplitPaymentMismatchError(ValueError):
    """Raised when a split_fare trip's sub-payments (blueprint 5.2.5) don't
    sum, to the cent, to the trip's total."""


class TripNotClosedError(Exception):
    """Raised when flagging a trip for review (blueprint 5.2.5's "Dispute"
    button) that hasn't been closed yet."""


class DisputeReasonRequiredError(Exception):
    """Raised when flagging a trip for review without supplying a non-empty
    reason."""


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


async def resolve_is_maxi_vehicle(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> bool:
    """The authoritative source of "is this vehicle a maxi-cab" — the real
    `Vehicle.vehicle_class` row, scoped to the requesting tenant. Deliberately
    never derived from a client-supplied boolean: a device claiming
    `maxi=true` must not be able to unlock the 150% rate on its own say-so,
    since that field feeds directly into FareState.is_maxi_vehicle and,
    combined with passenger_count, whether the maxi rate legally applies. An
    unknown/foreign vehicle_id resolves to False (not a maxi) rather than
    raising — trip creation's own vehicle_id validation (if any) is a
    separate concern from fare classification."""
    result = await session.execute(
        select(Vehicle).where(Vehicle.id == vehicle_id, Vehicle.tenant_id == tenant_id)
    )
    vehicle = result.scalar_one_or_none()
    return vehicle is not None and vehicle.vehicle_class == "maxi"


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
    state = FareState(
        tariff=tariff,
        # trip.time_class/trip.is_peak: read back VERBATIM as persisted, never
        # re-derived here via resolve_time_class_and_peak — for two
        # independent reasons, either one alone would be sufficient:
        #   1. Legal: fare_engine's own module docstring is explicit that
        #      time_class/is_peak "are fixed at journey commencement and do
        #      not change mid-trip even if the clock crosses a boundary".
        #      build_fare_state is called on every apply_tick (an open,
        #      in-progress trip) and every close_trip call — re-deriving from
        #      "now" (or from trip.start_at, hours after the fact) on each of
        #      those calls would silently reclassify an in-flight trip's
        #      rate mid-journey, which the Fares Order itself forbids.
        #   2. Trust: these columns were already resolved authoritatively,
        #      server-side, at trip-creation time (see create_trip/sync_trips
        #      in app.api.v1.trips, both of which call
        #      resolve_time_class_and_peak against the trip's real start_at
        #      before ever constructing the Trip row) — exactly the same
        #      "safe to read back as-is, never taken from a raw
        #      client-supplied flag" contract trip.maxi already has below.
        time_class=TimeClass(trip.time_class),
        is_peak=trip.is_peak,
        # trip.maxi was resolved authoritatively from the vehicle's real
        # vehicle_class at trip-creation time (see create_trip/sync_trips in
        # app.api.v1.trips) — safe to read back as-is here, it was never
        # taken from a raw client-supplied flag.
        is_maxi_vehicle=trip.maxi,
        passenger_count=trip.passenger_count,
        wheelchair_hiring=trip.wheelchair_hiring,
        airport_rank_requested_maxi=trip.airport_rank_requested_maxi,
        hired=True,
        cumulative_distance_km=Decimal(trip.distance_m) / Decimal(1000),
        accrued_distance_charge=trip.dist_amount,
        accrued_waiting_charge=trip.wait_amount,
        tolls=trip.tolls,
        extras=trip.extras,
        negotiated_total=trip.negotiated_total,
    )
    if trip.type == TRIP_TYPE_AIRPORT_FIXED:
        state.fixed_fare = airport_fixed_fare(state.maxi_applied)
    return state


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
    # Geofence ids whose toll has already been folded into trip.tolls, seeded
    # from what earlier tick() calls already persisted so a vehicle lingering
    # in (or re-entering) the same toll zone across multiple PATCH .../tick
    # requests is never double-charged (blueprint 5.2.4).
    applied_toll_geofence_ids: set[str] = set(trip.auto_tolls_applied or [])

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

        # --- toll geofence auto-detection (blueprint 5.2.4) --------------
        entered_tolls = await detect_geofences(
            session, tenant_id=tenant_id, lat=point.lat, lng=point.lng, kind=GEOFENCE_KIND_TOLL
        )
        for geofence in entered_tolls:
            if geofence.id in applied_toll_geofence_ids or geofence.toll_amount is None:
                continue
            trip.tolls = (trip.tolls or Decimal(0)) + geofence.toll_amount
            applied_toll_geofence_ids.add(geofence.id)

    trip.distance_m = round(state.cumulative_distance_km * Decimal(1000))
    trip.dist_amount = round_half_up(state.accrued_distance_charge)
    trip.wait_amount = round_half_up(state.accrued_waiting_charge)
    trip.moving_s += int(moving_delta)
    trip.waiting_s += int(waiting_delta)
    trip.last_lat, trip.last_lng, trip.last_ts = prev_lat, prev_lng, prev_ts
    # Reassign (not mutate in place) so SQLAlchemy's change-tracking on this
    # plain JSON column reliably marks it dirty for the flush.
    trip.auto_tolls_applied = list(applied_toll_geofence_ids)

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
    # --- new payment methods (blueprint 5.2.5) — all optional, only acted on
    # when `payment_method` resolves to "voucher" / "account" / "split_fare"
    # respectively; see close_trip below. voucher_code/account_reference
    # default to None meaning "keep whatever's already on the trip row" (they
    # may have been set at create/update time instead of re-supplied here).
    voucher_code: str | None = None
    account_reference: str | None = None
    split_payments: list[dict] | None = None


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

    # --- new payment methods (blueprint 5.2.5): voucher / account / split_fare.
    # Validated BEFORE any trip.* field is mutated below, using breakdown.
    # grand_total directly (trip.total isn't assigned yet) — so a validation
    # failure here (e.g. a split_payments mismatch) leaves `trip` completely
    # untouched rather than half-mutated, matching UnknownTariffError's
    # already-established "raise before mutating anything" contract in this
    # function's callers (they never commit on an exception, but not
    # mutating `trip` in the first place is still the safer invariant to hold).
    resolved_voucher_code = params.voucher_code if params.voucher_code is not None else trip.voucher_code
    resolved_account_reference = (
        params.account_reference if params.account_reference is not None else trip.account_reference
    )
    split_payments_to_store: list[dict] | None = None
    if params.payment_method == "voucher":
        payments_service.redeem_voucher(voucher_code=resolved_voucher_code or "")
    elif params.payment_method == "account":
        payments_service.validate_account_reference(account_reference=resolved_account_reference or "")
    elif params.payment_method == "split_fare":
        if not params.split_payments:
            raise SplitPaymentMismatchError("split_fare requires at least one sub-payment in split_payments")
        subtotal = sum((Decimal(str(item["amount"])) for item in params.split_payments), Decimal(0))
        if round_half_up(subtotal) != round_half_up(breakdown.grand_total):
            raise SplitPaymentMismatchError(
                f"split_payments sum to {subtotal} but trip total is {breakdown.grand_total}"
            )
        split_payments_to_store = [
            {"method": item["method"], "amount": str(item["amount"])} for item in params.split_payments
        ]

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
    trip.voucher_code = resolved_voucher_code
    trip.account_reference = resolved_account_reference
    trip.split_payments = split_payments_to_store
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
    is_maxi_vehicle: bool,
    passenger_count: int,
    wheelchair_hiring: bool,
    airport_rank_requested_maxi: bool,
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
    negotiated_total: Decimal | None = None,
) -> tuple[FareBreakdown, int, int, int, TimeClass, bool]:
    """Server-side canonical recompute of a fare from a submitted raw GPS
    trace, used by POST /v1/trips/sync to validate a device's own total.
    Returns (breakdown, distance_m, moving_s, waiting_s, time_class, is_peak).

    No `time_class`/`is_peak` parameters here (unlike the trip-type/passenger/
    maxi ones) — deliberately: this is the offline-sync path's own version of
    `resolve_is_maxi_vehicle` never trusting `payload.maxi`. A synced item's
    `time_class`/`is_peak` are advisory-only (see
    app.schemas.trips.TripSyncItem's doc comment) and are always resolved
    HERE, deterministically, from the tariff just looked up above and the
    trip's own real `start_at` — see `resolve_time_class_and_peak`. The
    resolved values are returned so the caller (app.api.v1.trips.sync_trips)
    persists the same authoritative values onto the Trip row that were
    actually used to compute `breakdown` below, rather than the client's
    claim."""
    tariff = await resolve_tariff(session, tenant_id=tenant_id, tariff_id=tariff_id)
    time_class, is_peak = resolve_time_class_and_peak(tariff=tariff, occurred_at=start_at)

    state = FareState(
        tariff=tariff,
        time_class=time_class,
        is_peak=is_peak,
        is_maxi_vehicle=is_maxi_vehicle,
        passenger_count=passenger_count,
        wheelchair_hiring=wheelchair_hiring,
        airport_rank_requested_maxi=airport_rank_requested_maxi,
        hired=True,
        tolls=tolls,
        extras=extras + cleaning_fee,
        negotiated_total=negotiated_total,
    )
    if trip_type == TRIP_TYPE_AIRPORT_FIXED:
        state.fixed_fare = airport_fixed_fare(state.maxi_applied)

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
    return breakdown, distance_m, moving_s, waiting_s, time_class, is_peak


# Trip.variance_pct is Numeric(6, 2) -- max representable value 9999.99. Real bug
# found live (2026-08-27): a Postgres-only NumericValueOutOfRange crash (500, whole
# sync batch aborted) the moment a device_total is wildly off from the server's
# recomputation -- e.g. a corrupted/garbage value, a driver typo, or a currency-unit
# mistake, not just an adversarial test. SQLite's loose NUMERIC affinity silently
# accepted any value regardless of declared precision, so this was invisible until
# tested against real Postgres. A wildly-wrong device_total should still close the
# trip and flag it for review (see the sync router's auto-flag on max_fare_check_passed)
# -- it must never crash the request.
_MAX_VARIANCE_PCT = Decimal("9999.99")


def compute_variance_pct(recomputed_total: Decimal, device_total: Decimal) -> Decimal:
    """abs(recomputed - device) / recomputed * 100, half-up to 2dp, clamped to
    _MAX_VARIANCE_PCT (see that constant's doc -- the column cannot store more, and an
    already-catastrophic variance doesn't need more precision than "it's capped-out
    bad" to trigger max_fare_check_passed=False downstream). If the recomputed total
    is exactly zero, treat any nonzero device total as 100% variance (avoids a
    ZeroDivisionError) and a matching zero as 0%."""
    if recomputed_total == 0:
        return Decimal("100.00") if device_total != 0 else Decimal("0.00")
    variance = abs(recomputed_total - device_total) / recomputed_total * Decimal(100)
    return min(round_half_up(variance), _MAX_VARIANCE_PCT)


# --- Dispute flagging (blueprint 5.2.5 "Dispute" button / 6.1.3 schema) ------


def flag_trip_for_review(*, trip: Trip, flagged: bool, reason: str | None) -> Trip:
    """Flags (or clears the flag on) a trip for operator review.

    Caller-identity authorization — the trip's own driver or a staff role may
    set flagged=True; only a staff role may clear it with flagged=False — is
    the router's job (see `app.api.v1.trips.flag_trip`'s docstring for the
    exact rule); this function only enforces the domain rules that don't
    depend on who's calling: only a *closed* trip can be flagged, and a
    non-empty reason is required to flag one. Does NOT commit — caller owns
    the session/transaction."""
    if flagged:
        if trip.status != TRIP_STATUS_CLOSED:
            raise TripNotClosedError("Only a closed trip can be flagged for review")
        if not reason or not reason.strip():
            raise DisputeReasonRequiredError("A non-empty reason is required to flag a trip for review")
        trip.flagged_for_review = True
        trip.review_notes = reason.strip()
    else:
        trip.flagged_for_review = False
    return trip
