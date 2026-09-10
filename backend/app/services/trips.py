"""Business logic for the trips domain: tariff resolution, haversine distance,
and the glue between persisted `Trip` rows and the pure `app.services.fare_engine`.

Kept separate from the router so the fare-reconstruction logic (build a
`FareState` identical to the one that produced the currently-persisted running
totals) has exactly one implementation, used by both the online tick/close
endpoints and the offline sync recompute path.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from math import asin, cos, radians, sin, sqrt

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import Vehicle
from app.models.geofence import GEOFENCE_KIND_AIRPORT, GEOFENCE_KIND_TOLL, Geofence
from app.models.tariffs import Tariff as TariffRow
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_TYPE_AIRPORT_FIXED, Trip, TripGpsTrace
from app.schemas.trips import TelemetryPoint
from app.services import payments as payments_service
from app.services.fare_engine import (
    NSW_FARE_ZONE,
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
from app.services.tolls import apply_toll_detection

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


# The fastest a real NSW-taxi trip could plausibly cover ground between two consecutive GPS
# fixes -- well above the fastest posted motorway limit (110 km/h) to leave real headroom for
# GPS jitter/lag, but nowhere near what a single corrupt fix implies. Chosen, not derived --
# same flagging convention this codebase uses elsewhere for a business/engineering judgement
# call rather than a physical constant.
MAX_PLAUSIBLE_SPEED_KMH = Decimal(300)


def plausible_distance_km(
    prev_lat: float, prev_lng: float, lat: float, lng: float, elapsed_seconds: Decimal
) -> Decimal:
    """[haversine_km] between two consecutive GPS fixes, clamped to what a real vehicle could
    plausibly have covered in `elapsed_seconds` -- the defence [apply_tick] and
    [recompute_from_trace] both need against a single corrupt/glitched fix (a stale location-
    provider result on a cold start, a real-GPS/simulated-GPS handoff, a coordinate
    wraparound) implying thousands of km of "travel" between one point and the next. Real bug,
    found live (2026-09-10): a GPS glitch during testing produced two trips billed -- and
    persisted -- against an 11,000+ km distance from a single bad trace point. The fare-
    variance auto-flag caught the resulting trip as suspicious, but flagging is a REVIEW
    signal, not a correction: the bad distance/total were still what got written to `Trip.
    distance_m`/`Trip.total` and summed into the dashboard's earnings-today aggregate. This
    stops the impossible number from ever being computed, rather than only flagging it after
    the fact.

    Elapsed-time-aware rather than a flat per-point cap: a fix with a long gap since the
    previous one (a real intercity dispatch between rare polls, or a device that slept) can
    legitimately cover more ground than one a second later. `elapsed_seconds <= 0` (a
    duplicate timestamp, or two points that raced) can never plausibly carry real distance
    regardless of what the raw haversine says -- treated as zero rather than dividing by zero.
    """
    raw_km = haversine_km(prev_lat, prev_lng, lat, lng)
    if elapsed_seconds <= 0:
        return Decimal(0)
    implied_speed_kmh = raw_km / (elapsed_seconds / Decimal(3600))
    if implied_speed_kmh <= MAX_PLAUSIBLE_SPEED_KMH:
        return raw_km
    return MAX_PLAUSIBLE_SPEED_KMH * (elapsed_seconds / Decimal(3600))


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


# --- /tick idempotency (backend audit §4, "/tick has no idempotency") -------
#
# A tick batch was never idempotent. apply_tick walks forward from
# trip.last_ts and ACCUMULATES haversine distance; waiting time was already
# safe (elapsed clamps to 0 on backwards time, see the loop below) but
# distance was not, so a client retry after a mobile timeout, or a
# double-tapped resend, billed the same kilometres twice. Nothing downstream
# caught it either — the online-close path recorded max_fare_check_passed =
# True by construction. Two independent defences, both applied on every tick:
#
# 1. `tick_seq` (is_replayed_tick) — the client's own monotonic per-trip
#    counter against Trip.last_tick_seq. Catches an entire replayed request
#    up front, before any fare state is rebuilt and before the fatigue and
#    compliance side effects in the router run a second time. Requires a
#    meter build that sends the field.
# 2. `ts` (accepted_tick_points) — drop every point that is not strictly
#    after Trip.last_ts. Needs no client cooperation at all, so it protects
#    the legacy clients defence 1 cannot, and it also handles the partial
#    case defence 1 cannot see: a batch that genuinely overlaps the previous
#    one, replaying some already-billed points alongside new ones.
#
# Both are silent no-ops, never errors: a client retrying because it never
# saw our first response must be able to stop retrying, and a 409 would only
# make it retry harder.


def _as_utc(value: datetime) -> datetime:
    """Every timestamp comparison in the tick path goes through here.

    SQLite does not store a timezone offset: SQLAlchemy renders a
    `DateTime(timezone=True)` column from the datetime's naive components and
    drops the tzinfo, so an aware `2026-07-15 14:00+10:00` is written as
    `14:00` and read back NAIVE. Reading that back and assuming UTC (which is
    what the elapsed-seconds arithmetic below has always done) puts the
    anchor ten hours ahead of the instant it actually represents — harmless
    while the only consumer was an elapsed that clamps to 0, but fatal to a
    `ts <= last_ts` replay comparison, which would then discard every
    genuinely new point of a NSW-local trip.

    So: a naive value is taken as UTC (the assumption the whole module
    already makes), and an aware one is CONVERTED to UTC rather than having
    its offset ignored. Paired with `apply_tick` normalising `trip.last_ts`
    to UTC before it is persisted, both sides of every comparison are then in
    the same frame no matter what offset the device sent."""
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def is_replayed_tick(trip: Trip, tick_seq: int | None) -> bool:
    """True when this request's tick_seq has already been applied to `trip`.
    False when either side has no sequence (a legacy client, or the trip's
    first sequenced tick) — the timestamp defence covers that case."""
    if tick_seq is None or trip.last_tick_seq is None:
        return False
    return tick_seq <= trip.last_tick_seq


def accepted_tick_points(trip: Trip, points: list[TelemetryPoint]) -> list[TelemetryPoint]:
    """`points` with every already-billed sample removed — i.e. those whose
    `ts` is not strictly after the trip's tick-continuity anchor
    (`Trip.last_ts`). Order is preserved; nothing is re-sorted, because a
    device's own recording order is the only ordering the fare replay can
    honestly assume.

    A point at EXACTLY last_ts is dropped as well as one before it: last_ts
    is the timestamp of the last point already fed through the engine, so an
    equal timestamp is that same point coming round again, and re-feeding it
    would add its haversine leg a second time for zero elapsed seconds.

    Returns everything when the trip has no anchor yet (its first tick)."""
    if trip.last_ts is None:
        return list(points)
    anchor = _as_utc(trip.last_ts)
    return [point for point in points if _as_utc(point.ts) > anchor]


async def apply_tick(
    session: AsyncSession,
    *,
    tenant_id: str,
    trip: Trip,
    points: list[TelemetryPoint],
    dest_lat: float | None = None,
    dest_lng: float | None = None,
    tick_seq: int | None = None,
) -> Trip:
    """Feeds a batch of telemetry points through the fare engine sequentially,
    mutating `trip`'s running totals + tick-continuity anchor in place.
    Does NOT commit — caller owns the session/transaction.

    `dest_lat`/`dest_lng` (Live Map route-line pass, see
    `app.models.trips.Trip.planned_dest_lat`/`planned_dest_lng`'s doc
    comment, module docstring deviation #7) are a driver-picked mid-trip
    destination, not a telemetry point — written onto the trip row only
    when BOTH are not None. When either is None (a tick that carries no
    destination update, the common case), the trip's existing
    planned_dest_lat/lng are left exactly as they are: a driver who already
    picked a destination does not need to keep resending it on every
    subsequent tick, and this must never silently clear a value some
    earlier tick already set.

    `points` is filtered through `accepted_tick_points` here as well as (not
    instead of) at the router — this function is the one that actually bills
    distance, so the replay guard belongs on it unconditionally rather than
    depending on every future caller remembering to filter first. Passing an
    already-filtered list is therefore free: the second filter removes
    nothing. `tick_seq`, when given, is recorded as the high-water mark on
    Trip.last_tick_seq so the next replay of this same request is caught by
    `is_replayed_tick` before it ever reaches here."""
    if dest_lat is not None and dest_lng is not None:
        trip.planned_dest_lat = dest_lat
        trip.planned_dest_lng = dest_lng

    points = accepted_tick_points(trip, points)
    if tick_seq is not None:
        trip.last_tick_seq = max(tick_seq, trip.last_tick_seq or 0)
    if not points:
        # Every point in this batch was already billed. The destination
        # update above (if any) still stands — it is a state assignment, not
        # an accumulation, so replaying it is harmless — but nothing else on
        # the trip may move.
        return trip

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
        # Captured before prev_lat/prev_lng are advanced below -- this is the
        # position the vehicle was travelling FROM, needed by the NSW
        # toll-registry detection's bearing classifier (app.services.tolls)
        # to tell which way a directional road was crossed.
        bearing_prev_lat, bearing_prev_lng = prev_lat, prev_lng

        elapsed_seconds = Decimal(0)
        if prev_ts is not None:
            elapsed_seconds = Decimal(
                str(max((_as_utc(point.ts) - _as_utc(prev_ts)).total_seconds(), 0))
            )
        distance_km = plausible_distance_km(
            prev_lat, prev_lng, point.lat, point.lng, elapsed_seconds
        )

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
        # ONLY kind="toll" here, deliberately. kind="airport" zones are a
        # pickup fee, charged once at trip creation
        # (apply_airport_access_fee_at_start) — a vehicle ticking INTO the
        # airport mid-trip is dropping a passenger off, and a drop-off never
        # attracts the access fee.
        entered_tolls = await detect_geofences(
            session, tenant_id=tenant_id, lat=point.lat, lng=point.lng, kind=GEOFENCE_KIND_TOLL
        )
        for geofence in entered_tolls:
            if geofence.id in applied_toll_geofence_ids or geofence.toll_amount is None:
                continue
            trip.tolls = (trip.tolls or Decimal(0)) + geofence.toll_amount
            applied_toll_geofence_ids.add(geofence.id)

        # --- NSW toll-registry auto-detection (app.services.tolls) --------
        # Additive to, and independent of, the ad hoc-geofence block above:
        # this is the real 13-road/141-gantry registry, charged ONCE PER ROAD
        # (never per gantry), direction-aware, and covering flat/zone_flat/
        # distance/time_of_day pricing -- see that module's docstring.
        await apply_toll_detection(
            session,
            trip=trip,
            prev_lat=bearing_prev_lat,
            prev_lng=bearing_prev_lng,
            lat=point.lat,
            lng=point.lng,
            ts=point.ts,
            cumulative_distance_km=state.cumulative_distance_km,
        )

    trip.distance_m = round(state.cumulative_distance_km * Decimal(1000))
    trip.dist_amount = round_half_up(state.accrued_distance_charge)
    trip.wait_amount = round_half_up(state.accrued_waiting_charge)
    trip.moving_s += int(moving_delta)
    trip.waiting_s += int(waiting_delta)
    # Normalised to UTC before persisting — see _as_utc. SQLite drops the
    # offset on write, so storing the device's own local-offset wall clock
    # here is what made the anchor unusable for the replay comparison.
    trip.last_lat, trip.last_lng = prev_lat, prev_lng
    trip.last_ts = _as_utc(prev_ts) if prev_ts is not None else None
    # Reassign (not mutate in place) so SQLAlchemy's change-tracking on this
    # plain JSON column reliably marks it dirty for the flush.
    trip.auto_tolls_applied = list(applied_toll_geofence_ids)

    return trip


async def apply_airport_access_fee_at_start(
    session: AsyncSession,
    *,
    tenant_id: str,
    trip: Trip,
    client_tolls: Decimal | None = None,
) -> Geofence | None:
    """Sydney Airport ground-transport access fee (NSW Fares Order; $6.43
    GST-inclusive per taxi pickup at the T1/T2/T3 ranks, carried as
    `toll_amount` on a `kind="airport"` geofence — see app.models.geofence).

    Charged ONCE, when the hiring STARTS inside an airport zone: called from
    `create_trip` with the freshly-built (not yet flushed) Trip row, using its
    `start_lat`/`start_lng`. Never called from the tick path — driving into
    the airport mid-trip is a drop-off, not a pickup — and never charges a
    `TRIP_TYPE_AIRPORT_FIXED` trip, whose $60/$80 fixed fare already includes
    it. Where several airport zones contain the pickup point (the T2/T3
    circles overlap) only the smallest-radius one is charged. Visibility is
    the usual tenant-owned-or-global rule (`detect_geofences`). Mutates
    `trip.tolls` and `trip.auto_tolls_applied` in place and returns the zone
    charged (None when nothing was); does NOT commit.

    DOUBLE-COUNT RULE (checked against app/api/v1/trips.py and the tablet's
    ApiService/TripRepository):
      * `POST /v1/trips/sync` — the ONLY network path the Android meter's
        offline-first close flow actually takes — never comes through here.
        It builds the Trip row straight from `item.tolls`, the tablet's own
        ledger (which already contains the fee the tablet auto-applied at
        trip start, see android FareEngine.startTrip), via
        `recompute_from_trace`. The tablet-computed value wins outright.
      * `POST /v1/trips/{id}/close` carries no `tolls` field
        (`TripCloseRequest`); `close_trip` assigns `trip.tolls =
        breakdown.tolls`, which is a pass-through of the server ledger
        `build_fare_state` read off the row. So the fee applied here survives
        an online close unchanged and is never re-added.
      * `PATCH /v1/trips/{id}` with `tolls` REPLACES the server ledger
        wholesale (`update_trip` does a plain setattr). A client that pushes
        its own ledger total takes ownership of `tolls`; the value applied
        here is replaced, not added to, so that path cannot double count
        either.
      * `POST /v1/trips` itself: `client_tolls` is the create payload's
        `tolls`. A NON-ZERO opening ledger means the caller is already
        running its own toll ledger (the tablet's TripCreateDto sends its
        ledger, and the tablet auto-applies this very fee at start), so the
        server-side charge is SKIPPED — the client's figure wins, exactly as
        it does on /sync. Server-side application therefore only ever
        matters for server-metered trips (payload.tolls == 0), which is the
        only case where nobody else is keeping the ledger.
    """
    if trip.type == TRIP_TYPE_AIRPORT_FIXED:
        return None
    if client_tolls is not None and client_tolls > 0:
        return None
    if trip.start_lat is None or trip.start_lng is None:
        return None

    zones = await detect_geofences(
        session,
        tenant_id=tenant_id,
        lat=trip.start_lat,
        lng=trip.start_lng,
        kind=GEOFENCE_KIND_AIRPORT,
    )
    zones = [z for z in zones if z.toll_amount is not None]
    if not zones:
        return None

    already_applied = set(trip.auto_tolls_applied or [])
    if already_applied & {z.id for z in zones}:
        # Belt and braces: a row that somehow already carries one of these
        # ids was charged by an earlier call; "once" means once.
        return None

    zone = min(zones, key=lambda z: (z.radius_m, z.name, z.id))
    trip.tolls = (trip.tolls or Decimal(0)) + zone.toll_amount
    # Reassign (not mutate in place) — plain JSON column, same reasoning as
    # apply_tick's own assignment.
    trip.auto_tolls_applied = [*already_applied, zone.id]
    return zone


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
    # The device meter's own grand total for this trip, when the client knows
    # it (see TripCloseRequest.device_total). None means "nothing to compare"
    # — see close_trip's max_fare_check_passed assignment.
    device_total: Decimal | None = None
    # Driver tip (Close & Pay "tips" pass) — see Trip.tip_amount's doc (module docstring
    # deviation #6). Deliberately NOT passed to engine.close() below; assigned straight onto
    # the trip row so it can never influence fare_total/surcharge/total/gst_component.
    tip_amount: Decimal | None = None


async def close_trip(session: AsyncSession, *, tenant_id: str, trip: Trip, params: CloseParams) -> FareBreakdown:
    """Finalizes `trip` via the fare engine's close(), storing the breakdown.
    Does NOT commit — caller owns the session/transaction. Returns the
    breakdown for the caller to surface if desired."""
    # cleaning_fee has no dedicated column on Trip (not in the domain's field
    # list). For an ORDINARY metered trip it is folded into `extras` before
    # building state so it flows through engine.close() as a genuine dollar
    # amount rather than being dropped — `extras` is fully additive on that
    # branch, so the sum is identical either way.
    #
    # A negotiated ("Set Price") or Sydney Airport Fixed fare is different:
    # engine.close()'s negotiated_total/fixed_fare branches deliberately
    # EXCLUDE `extras` from what's billed (tolls/PSL/extras are absorbed into
    # the agreed price — see that module's docstrings), so folding
    # cleaning_fee into the SAME bucket would silently absorb it too — and a
    # cleaning fee must never be absorbed, even on a fixed/negotiated fare
    # (2026-09 product ruling: soiling is discovered after the price was
    # agreed). So for those two fare types, cleaning_fee is left out of
    # `extras` and passed straight through to engine.close()'s own
    # `cleaning_fee` parameter instead, which both branches always add on top.
    is_all_inclusive_fare = trip.type == TRIP_TYPE_AIRPORT_FIXED or trip.negotiated_total is not None
    if params.cleaning_fee and not is_all_inclusive_fare:
        trip.extras = (trip.extras or Decimal(0)) + params.cleaning_fee

    state = await build_fare_state(session, tenant_id=tenant_id, trip=trip)
    breakdown = engine.close(
        state,
        payment_method=params.payment_method,
        surcharge_pct=params.surcharge_pct,
        cleaning_fee=params.cleaning_fee if is_all_inclusive_fare else Decimal(0),
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
        await payments_service.redeem_voucher(
            session, tenant_id=tenant_id, voucher_code=resolved_voucher_code or "", trip_id=trip.id
        )
    elif params.payment_method == "account":
        await payments_service.validate_account_reference(
            session, tenant_id=tenant_id, account_reference=resolved_account_reference or ""
        )
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
    # --- fare verification on the ONLINE close path (backend audit §4) ------
    # This used to be an unconditional `True`, commented "no device_total to
    # compare for online closes" — which meant every trip closed online
    # carried an unverified fare BY CONSTRUCTION, and the flagged-trips view
    # could only ever show offline-replayed (/sync) trips. It also meant the
    # /tick double-count overcharge had nothing downstream that could catch
    # it. When the client supplies its own meter total, run exactly the same
    # check /sync runs on a synced trip: same compute_variance_pct, same 1%
    # tolerance, same auto-flag with the same reason text, so a dashboard
    # operator cannot tell (and should not need to tell) whether a flagged
    # trip arrived online or offline.
    #
    # With no device_total there is genuinely nothing to compare against, and
    # inventing a variance would be worse than admitting none was measured:
    # the check is recorded as passed and variance_pct left at its default,
    # which is the pre-existing behaviour for every client that does not yet
    # send the field.
    if params.device_total is not None:
        variance_pct = compute_variance_pct(breakdown.grand_total, params.device_total)
        trip.variance_pct = variance_pct
        trip.max_fare_check_passed = variance_pct <= 1.0
        if not trip.max_fare_check_passed:
            trip.flagged_for_review = True
            trip.review_notes = (
                f"Auto-flagged: fare variance {variance_pct}% exceeds 1% tolerance "
                f"(device reported {params.device_total}, server recomputed "
                f"{breakdown.grand_total})"
            )
    else:
        trip.max_fare_check_passed = True
    trip.receipt_ref = params.receipt_ref or f"RCPT-{trip.id[:8].upper()}"
    # Assigned straight from params, never derived from `breakdown` — a tip is not part of
    # the fare-engine's output (see Trip.tip_amount's doc, deviation #6 above).
    trip.tip_amount = params.tip_amount

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

    # See close_trip's identical comment: a negotiated/airport-fixed fare
    # excludes `extras` from what's billed, so cleaning_fee must NOT be
    # folded into it here (that would silently absorb it) — it goes straight
    # to engine.close()'s own cleaning_fee parameter instead, below.
    is_all_inclusive_fare = trip_type == TRIP_TYPE_AIRPORT_FIXED or negotiated_total is not None

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
        extras=extras if is_all_inclusive_fare else extras + cleaning_fee,
        negotiated_total=negotiated_total,
    )
    if trip_type == TRIP_TYPE_AIRPORT_FIXED:
        state.fixed_fare = airport_fixed_fare(state.maxi_applied)

    prev_lat, prev_lng, prev_ts = start_lat, start_lng, start_at
    moving_s = 0
    waiting_s = 0

    for point in gps_trace:
        ts_prev = prev_ts if prev_ts.tzinfo else prev_ts.replace(tzinfo=UTC)
        ts_point = point.ts if point.ts.tzinfo else point.ts.replace(tzinfo=UTC)
        elapsed_seconds = Decimal(str(max((ts_point - ts_prev).total_seconds(), 0)))
        distance_km = plausible_distance_km(
            prev_lat, prev_lng, point.lat, point.lng, elapsed_seconds
        )

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
        cleaning_fee=cleaning_fee if is_all_inclusive_fare else Decimal(0),
        include_psl=include_psl,
    )
    distance_m = round(state.cumulative_distance_km * Decimal(1000))
    return breakdown, distance_m, moving_s, waiting_s, time_class, is_peak


def build_gps_trace_row(
    *, tenant_id: str, trip_id: str, gps_trace: list[TelemetryPoint], recorded_at: datetime
) -> TripGpsTrace | None:
    """Builds the durable `TripGpsTrace` row for a just-synced trip's raw
    telemetry, or `None` when `gps_trace` is empty.

    Returning `None` (rather than a row with `points: []`) for an empty trace
    is deliberate -- see `TripGpsTrace`'s own "EMPTY TRACE" doc section: every
    synced trip arrives with `gps_trace: []` today (a parallel workstream is
    fixing the on-device bug that causes this), and a junk all-empty row per
    trip would both waste a row and give `GET /v1/trips/{id}/gps-trace` a
    false "recorded, but empty" state to report instead of the honest
    "nothing stored" one.

    Does NOT add the row to any session or commit -- caller (`sync_trips`)
    owns adding/flushing it in the same transaction as the `Trip` row it
    belongs to, so a failed insert (e.g. a racing duplicate client_uuid) rolls
    both back together and never orphans a trace for a trip that itself
    didn't get created (see `TripGpsTrace`'s own "IDEMPOTENCY" doc section).

    `points` are serialized via each `TelemetryPoint`'s own
    `.model_dump(mode="json")` -- JSON has no native datetime type, so `ts` is
    written out as an ISO-8601 string; `get_trip_gps_trace`
    (`app/api/v1/trips.py`) reconstructs `TelemetryPoint` instances straight
    back from these dicts on read, which pydantic parses just as happily from
    the ISO string as from a real `datetime`.
    """
    if not gps_trace:
        return None
    return TripGpsTrace(
        tenant_id=tenant_id,
        trip_id=trip_id,
        points=[point.model_dump(mode="json") for point in gps_trace],
        point_count=len(gps_trace),
        recorded_at=recorded_at,
    )


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


# --- Driver earnings today (dashboard tiles, GET /v1/trips/earnings/today) --


@dataclass
class DriverEarningsToday:
    driver_id: str
    today: date
    today_total: Decimal
    yesterday_total: Decimal
    trips_completed_today: int
    # Closed-but-flagged_for_review trips for today, EXCLUDED from today_total/
    # trips_completed_today above (and from yesterday_total) rather than folded in at face
    # value — deliberately different from app.services.fleet_reports' own admin-facing gross
    # revenue sum, which does include a flagged trip's total alongside a separate flagged
    # count: that is a fleet-wide accounting report where an admin wants the true gross with a
    # caveat, this is a DRIVER's own personal earnings tile, and a fare still under review has
    # no confirmed total yet to show as settled income. Real bug, found live (2026-09-10): a
    # single corrupt GPS trace point (see plausible_distance_km's own doc) produced a trip
    # billed at an ~11,000km distance; it was correctly auto-flagged, but this tile still
    # summed its wild total at face value, showing a driver "$25,231 today" for an ordinary
    # shift. Reported as a count (not silently dropped) so a driver never wonders where a trip
    # went — see DriverEarningsTodayRead.flagged_count's own doc for how this surfaces.
    flagged_count: int = 0

    @property
    def pct_change(self) -> float | None:
        """`None` whenever there's no non-zero yesterday baseline to compare
        against — callers must render "no comparison available", never a
        fabricated 0%/100%."""
        if self.yesterday_total == 0:
            return None
        return round(float((self.today_total - self.yesterday_total) / self.yesterday_total) * 100, 1)


async def driver_earnings_today(
    session: AsyncSession, *, tenant_id: str, driver_id: str, now: datetime | None = None
) -> DriverEarningsToday:
    """The calling driver's real completed-trip earnings for "today".

    "Today" is the NSW-local calendar day (`app.services.fare_engine.
    NSW_FARE_ZONE`, `Australia/Sydney`) — the same midnight every fare-affecting
    classification in this codebase already breaks on (see that module's own
    doc, and the Android client's identically-named, identically-pinned
    constant). This function used to use the UTC calendar day instead, on the
    stated reasoning that "there is no Sydney-timezone helper anywhere in this
    codebase" — which was simply wrong by the time this ran live: a real
    tablet whose system clock sits far from Sydney (this one physically in
    Karachi, UTC+5) showed a materially different "today" on this tile than on
    every other screen reading the same NSW-pinned local Room data, with
    nothing on either screen to explain the mismatch — the exact class of bug
    `TripPeriod`'s own F12 doc (Android, `data/local/dao/TripDao.kt`) already
    fixed everywhere else. This is the one place that fix never reached.

    Bucketed by `Trip.start_at`, like every other date-bucketed money
    aggregate in this codebase (app.services.reports.revenue_report,
    app.services.platform.get_platform_health) — deliberately NOT
    `Trip.end_at`. Only `status == "closed"` trips are counted (an open
    trip has no final `total` yet, same rule `revenue_report` already
    applies), and — unlike those two, and unlike `app.services.fleet_reports`'
    own admin-facing gross revenue sum — a `flagged_for_review` trip's total
    is EXCLUDED from `today_total`/`yesterday_total` and from
    `trips_completed_today`, surfaced instead as `flagged_count`; see
    `DriverEarningsToday.flagged_count`'s own doc for why this tile's
    convention deliberately differs from the admin report's. All aggregation
    (SUM/COUNT) runs in SQL, never summed Python-side over a fetched trip
    list, matching app.services.reports' own "no float for money" rule;
    `today_total`/`yesterday_total` stay `Decimal` all the way out.
    """
    now = now or datetime.now(UTC)
    today = now.astimezone(NSW_FARE_ZONE).date()
    start_of_today = datetime.combine(today, time.min, tzinfo=NSW_FARE_ZONE)
    start_of_tomorrow = start_of_today + timedelta(days=1)
    start_of_yesterday = start_of_today - timedelta(days=1)

    base_filters = (
        Trip.tenant_id == tenant_id,
        Trip.driver_id == driver_id,
        Trip.status == TRIP_STATUS_CLOSED,
    )
    not_flagged = Trip.flagged_for_review.is_(False)

    today_total, trips_completed_today = (
        await session.execute(
            select(func.sum(Trip.total), func.count(Trip.id)).where(
                *base_filters,
                not_flagged,
                Trip.start_at >= start_of_today,
                Trip.start_at < start_of_tomorrow,
            )
        )
    ).one()

    (yesterday_total,) = (
        await session.execute(
            select(func.sum(Trip.total)).where(
                *base_filters,
                not_flagged,
                Trip.start_at >= start_of_yesterday,
                Trip.start_at < start_of_today,
            )
        )
    ).one()

    (flagged_count,) = (
        await session.execute(
            select(func.count(Trip.id)).where(
                *base_filters,
                Trip.flagged_for_review.is_(True),
                Trip.start_at >= start_of_today,
                Trip.start_at < start_of_tomorrow,
            )
        )
    ).one()

    return DriverEarningsToday(
        driver_id=driver_id,
        today=today,
        today_total=today_total or Decimal("0.00"),
        yesterday_total=yesterday_total or Decimal("0.00"),
        trips_completed_today=trips_completed_today or 0,
        flagged_count=flagged_count or 0,
    )
