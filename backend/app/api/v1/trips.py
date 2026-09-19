"""Trips domain router — offline-sync-capable taxi-meter journey records.

Full CRUD (list/get/create/update/delete) plus domain-specific endpoints:
PATCH .../tick (telemetry batch -> running totals), POST .../close (finalize
via the fare engine), POST /sync (bulk offline-replay upload with per-item
idempotency + server-side fare verification), PATCH .../flag (blueprint
5.2.5 "Dispute" button — flag/clear a closed trip for operator review), and
GET .../gps-trace (dedicated fetch for the durable GPS trace persisted by
/sync — see app.models.trips.TripGpsTrace — deliberately kept out of the
list/detail payload above).

EVERY query in this file filters by tenant_id via `get_current_tenant_id` —
the sole multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import AsyncSessionLocal, get_session
from app.core.security import get_current_tenant_id, get_current_user
from app.models.fleet import Vehicle
from app.models.shift import Shift
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_STATUS_OPEN, TRIP_TYPES, Trip, TripGpsTrace
from app.models.user import User
from app.schemas.trips import (
    DriverEarningsTodayRead,
    ReceiptEmailRequest,
    ReceiptEmailResponse,
    ReceiptSmsRequest,
    ReceiptSmsResponse,
    TelemetryPoint,
    TripCloseRequest,
    TripCreate,
    TripFareCorrectionRequest,
    TripFlagRequest,
    TripGpsTraceRead,
    TripListResponse,
    TripRead,
    TripSyncItem,
    TripSyncResponse,
    TripSyncResultItem,
    TripTickRequest,
    TripUpdate,
)
from app.services import compliance_expiry as compliance_expiry_service
from app.services import driver_engagement as driver_engagement_service
from app.services import fatigue as fatigue_service
from app.services import payments as payments_service
from app.services import psl_ledger as psl_ledger_service
from app.services import receipts as receipts_service
from app.services import shift as shift_service
from app.services.audit_log import record_audit
from app.services.fare_engine import URBAN_TARIFF, resolve_time_class_and_peak, round_half_up
from app.services.payments import InvalidAccountReferenceError, InvalidVoucherCodeError
from app.services.tolls import validate_device_reported_tolled_roads
from app.services.trips import (
    CloseParams,
    DisputeReasonRequiredError,
    SplitPaymentMismatchError,
    TripNotClosedError,
    UnknownTariffError,
    accepted_tick_points,
    apply_airport_access_fee_at_start,
    apply_tick,
    build_gps_trace_row,
    close_trip,
    compute_variance_pct,
    driver_earnings_today,
    flag_trip_for_review,
    is_replayed_tick,
    recompute_from_trace,
    reconcile_gps_blackout_segments,
    resolve_is_maxi_vehicle,
    resolve_tariff,
)

router = APIRouter(prefix="/v1/trips", tags=["trips"])

logger = logging.getLogger("cab_dispatch.trips")

# Roles permitted to clear ANY trip's review flag and to flag a trip they
# don't themselves drive, mirroring the _DISPATCH_ROLES convention used by
# app.api.v1.duress / app.api.v1.fatigue_alerts / app.api.v1.jobs. A
# `driver`-role caller may additionally flag (but not clear) a trip where
# they are the trip's own driver — see flag_trip below.
_DISPATCH_ROLES = ("owner", "admin", "dispatcher")


async def _get_trip_or_404(trip_id: str, tenant_id: str, session: AsyncSession) -> Trip:
    result = await session.execute(
        select(Trip).where(Trip.id == trip_id, Trip.tenant_id == tenant_id)
    )
    trip = result.scalar_one_or_none()
    if trip is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Trip not found")
    return trip


def _require_trip_write_access(current_user: User, *, driver_id: str | None, action: str) -> None:
    """Every write to a trip goes through here (backend audit §4: "No
    role/ownership check on any trip write ... any driver token can tick or
    close any other driver's trip"). Until now `create`, `tick`, `close`,
    `PATCH` and `DELETE` took only `get_current_tenant_id`, so tenant
    membership was the whole authorisation story: any driver in the tenant
    could tick another driver's running meter, close their trip out from
    under them, repoint it at a different vehicle, or delete it.

    The rule mirrors `flag_trip` below, which was the only endpoint in this
    file that ever checked identity: a `driver`-role caller may only write
    trips where they are the trip's own driver; the staff roles
    (`_DISPATCH_ROLES` — owner/admin/dispatcher) are unrestricted, because
    correcting and closing out a driver's trip from the dashboard is exactly
    their job.

    A trip with `driver_id` set to None or to a driver who no longer exists
    is NOT writable by a driver-role caller — an unattributed trip is a
    dispatcher's problem, not something any driver may claim by writing to
    it."""
    if current_user.role in _DISPATCH_ROLES:
        return
    if driver_id is not None and driver_id == current_user.id:
        return
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=f"A driver may only {action} their own trips",
    )


# --- Create -------------------------------------------------------------


@router.post("", response_model=TripRead, status_code=status.HTTP_201_CREATED)
async def create_trip(
    payload: TripCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    # A driver may only open a trip in their own name — see
    # _require_trip_write_access. Checked against the REQUESTED driver_id
    # (there is no trip row yet), which is what stops a driver attributing a
    # trip, and its takings, to somebody else.
    _require_trip_write_access(current_user, driver_id=payload.driver_id, action="create")
    # Authoritative: resolved server-side from the vehicle's real
    # vehicle_class, never taken from payload.maxi (accepted but ignored for
    # billing — see TripCreate.maxi's doc comment).
    is_maxi_vehicle = await resolve_is_maxi_vehicle(
        session, tenant_id=tenant_id, vehicle_id=payload.vehicle_id
    )
    start_at = payload.start_at or datetime.now(UTC)
    # Authoritative: resolved server-side from the tariff's own night/peak-
    # window + public-holiday-calendar definitions and the trip's real
    # start_at, never taken from payload.time_class/payload.is_peak (accepted
    # but ignored for billing — see TripCreate.time_class/is_peak's doc
    # comment, same pattern as payload.maxi above).
    #
    # An unknown/foreign tariff_id isn't this endpoint's concern to reject —
    # exactly like resolve_is_maxi_vehicle's unknown-vehicle fallback above,
    # trip creation itself never 422s on a bad tariff_id today (only the
    # later tick/close calls do, via UnknownTariffError — see
    # test_tick_unknown_tariff_is_422), so fall back to URBAN_TARIFF purely
    # so classification never raises here. Whatever gets stored below in that
    # edge case is moot: the trip's real bill fails regardless the moment a
    # genuine fare recompute is attempted against that same bad tariff_id.
    try:
        tariff = await resolve_tariff(session, tenant_id=tenant_id, tariff_id=payload.tariff_id)
    except UnknownTariffError:
        tariff = URBAN_TARIFF
    time_class, is_peak = resolve_time_class_and_peak(tariff=tariff, occurred_at=start_at)
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=payload.client_uuid,
        vehicle_id=payload.vehicle_id,
        driver_id=payload.driver_id,
        shift_id=payload.shift_id,
        tariff_id=payload.tariff_id,
        type=payload.type,
        status=TRIP_STATUS_OPEN,
        time_class=time_class.value,
        is_peak=is_peak,
        maxi=is_maxi_vehicle,
        passenger_count=payload.passenger_count,
        wheelchair_hiring=payload.wheelchair_hiring,
        airport_rank_requested_maxi=payload.airport_rank_requested_maxi,
        start_at=start_at,
        start_lat=payload.start_lat,
        start_lng=payload.start_lng,
        payment_method=payload.payment_method,
        voucher_code=payload.voucher_code,
        account_reference=payload.account_reference,
        tolls=payload.tolls,
        extras=payload.extras,
        gps_trace_ref=payload.gps_trace_ref,
        negotiated_total=payload.negotiated_total,
    )
    # Sydney Airport access fee — charged ONCE here, at the pickup, when the
    # start position lies inside a kind="airport" geofence; never on the tick
    # path (a drop-off), never on an airport_fixed trip, and never when the
    # caller already sent its own toll ledger (payload.tolls > 0) — see that
    # function's docstring for the full double-count rule.
    await apply_airport_access_fee_at_start(
        session, tenant_id=tenant_id, trip=trip, client_tolls=payload.tolls
    )
    session.add(trip)
    try:
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A trip with this client_uuid already exists for this tenant",
        ) from exc
    await session.refresh(trip)
    return trip


# --- Sync (offline bulk replay) — must be declared before /{trip_id} routes -


@router.post("/sync", response_model=TripSyncResponse)
async def sync_trips(
    items: list[TripSyncItem],
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> TripSyncResponse:
    results: list[TripSyncResultItem] = []
    newly_created: list[Trip] = []

    # Each item runs in its OWN SAVEPOINT (backend audit §4, "the batch-abort
    # bug" — the highest-severity backend finding). This loop used to flush
    # per item but commit once at the very end, while four separate
    # validation failures (unknown tariff, invalid voucher, invalid account
    # reference, split-payment mismatch) raised HTTPException from inside the
    # loop. FastAPI unwound the request without ever reaching that commit, so
    # ONE poisoned item silently discarded every already-flushed good trip in
    # the same batch — and the Android meter posts a whole shift's queued
    # offline trips as a single batch, so one bad voucher lost a shift's
    # takings. `session.begin_nested()` gives each item its own rollback
    # boundary: a failed item's partial work (its Trip row, its GPS trace, a
    # voucher it had already redeemed) is undone, its siblings' work is not,
    # and the failure is reported back per item as
    # TripSyncResultItem(status="failed", reason=...) rather than as a status
    # code for the whole request.
    #
    # The batch as a whole therefore no longer fails on one bad item: a
    # caller must read `status` per result. A malformed *request* (bad JSON,
    # a field pydantic rejects) is still a 422 for the batch — that is a
    # client bug, not one trip's data being unacceptable.
    for item in items:
        existing = await session.execute(
            select(Trip).where(
                Trip.tenant_id == tenant_id, Trip.client_uuid == item.client_uuid
            )
        )
        trip = existing.scalar_one_or_none()
        if trip is not None:
            results.append(
                TripSyncResultItem(
                    client_uuid=item.client_uuid, duplicate=True, status="duplicate", trip=trip
                )
            )
            continue

        try:
            async with session.begin_nested():
                is_maxi_vehicle = await resolve_is_maxi_vehicle(
                    session, tenant_id=tenant_id, vehicle_id=item.vehicle_id
                )
                # time_class/is_peak deliberately NOT passed through from
                # item.time_class/item.is_peak here — see recompute_from_trace's
                # own doc comment; it resolves both authoritatively itself, from
                # the tariff it looks up and item.start_at, and hands the
                # resolved values back below for the Trip row.
                breakdown, distance_m, moving_s, waiting_s, time_class, is_peak, gps_blackout_events, stopped_s = await recompute_from_trace(
                    session,
                    tenant_id=tenant_id,
                    tariff_id=item.tariff_id,
                    trip_type=item.type,
                    is_maxi_vehicle=is_maxi_vehicle,
                    passenger_count=item.passenger_count,
                    wheelchair_hiring=item.wheelchair_hiring,
                    airport_rank_requested_maxi=item.airport_rank_requested_maxi,
                    tolls=item.tolls,
                    extras=item.extras,
                    cleaning_fee=item.cleaning_fee,
                    start_lat=item.start_lat,
                    start_lng=item.start_lng,
                    start_at=item.start_at,
                    gps_trace=item.gps_trace,
                    payment_method=item.payment_method,
                    surcharge_pct=item.surcharge_pct,
                    include_psl=item.include_psl,
                    negotiated_total=item.negotiated_total,
                    # The device's own account of every GPS blackout on this trip -- the
                    # recompute bills a trace gap with it when one matches (see
                    # recompute_from_trace's gap branch, 2026-09-14).
                    device_segments=item.gps_blackout_segments,
                )

                # GPS-blackout / STOPPED reconciliation (B-W1) -- purely an audit-trail
                # cross-check between the device's OWN account of its blackouts
                # (item.gps_blackout_segments) and this server's own independent
                # recompute above (gps_blackout_events); see
                # reconcile_gps_blackout_segments's own doc for exactly what is
                # compared and why neither side is ever auto-corrected by the other.
                blackout_reconciliation = await reconcile_gps_blackout_segments(
                    session,
                    device_segments=item.gps_blackout_segments,
                    server_events=gps_blackout_events,
                    device_stopped_s=item.stopped_s,
                    server_stopped_s=stopped_s,
                )

                # Device-reported toll evidence (2026-09-16 fix) -- ONLY ever the dispute-evidence
                # breakdown (Trip.auto_tolled_roads), NEVER trip.tolls/trip.total itself (those are
                # `tolls`/`stored_total` above, already trusted from the device the same way
                # `device_total` is for the grand total). Gated on this trip actually reporting a
                # GPS blackout at all: a trip with none had every real toll available to the
                # server's own trace-based apply_toll_detection sweep inside recompute_from_trace,
                # so a device claim beyond that would be unexplained rather than the "the road was
                # underground" gap this exists to close -- kept out rather than silently trusted.
                device_tolled_roads = (
                    await validate_device_reported_tolled_roads(session, item.auto_tolled_roads)
                    if item.gps_blackout_segments
                    else {}
                )

                variance_pct = compute_variance_pct(breakdown.grand_total, item.device_total)

                # Real gap found live (2026-08-27, first-ever real device sync against a real
                # deployed backend): a trip failing its own max-fare-variance check (device_total
                # vs the server's independent gps_trace-based recomputation) was silently recorded
                # in variance_pct/max_fare_check_passed with flagged_for_review left False -- those
                # two columns existed purely for a human to separately query by hand, never actually
                # surfacing on the dashboard's flagged-trips view (GET /v1/trips?flagged_for_review=true)
                # the way a genuine dispute (PATCH /v1/trips/{id}/flag) does. A real trip synced during
                # that test failed the check by 19% (nineteen times the 1% tolerance) and was never
                # flagged. Auto-flag here instead of only via the manual Dispute button -- this is
                # exactly the kind of fare-accuracy signal the NSW cl.14 self-certification story this
                # whole Compliance Vault module exists for needs to actually be visible, not silent.
                fare_check_passed = variance_pct <= 1.0
                auto_flag_reason: str | None = None
                # The fare OF RECORD is what the meter charged the passenger. The server's
                # trace-based recompute is a fraud/bug cross-check that flags a trip for a human;
                # it must never quietly replace the charged amount on the dashboard, receipts,
                # shift cash-up and GST reporting with a number nobody paid. Until 2026-09-14 it
                # did exactly that: a real tunnel trip the meter charged $59.03 for was stored
                # -- and shown to the owner -- as $32.52. So: a trip that fails the check keeps
                # the device's total (and a proportional GST component), is flagged, and the
                # note carries the server's figure for the review.
                stored_total = breakdown.grand_total
                stored_gst = breakdown.gst_component
                if not fare_check_passed:
                    auto_flag_reason = (
                        f"Auto-flagged: fare variance {variance_pct}% exceeds 1% tolerance "
                        f"(device reported {item.device_total}, server recomputed {breakdown.grand_total}). "
                        f"Stored total is the device's charged fare; the server figure is kept here for review."
                    )
                    stored_total = round_half_up(item.device_total)
                    stored_gst = (
                        round_half_up(item.device_total * breakdown.gst_component / breakdown.grand_total)
                        if breakdown.grand_total > 0
                        else round_half_up(item.device_total / Decimal(11))
                    )

                # New payment methods (blueprint 5.2.5), same validate-before-persist contract as
                # close_trip (app/services/trips.py) — voucher/account/split_fare are validated against
                # the just-recomputed breakdown BEFORE any Trip row is constructed for this item, so a
                # bad item raises before touching the session, same as UnknownTariffError above. This
                # closes the real gap the sync-item schema had until now: voucher_code/account_reference/
                # split_payments used to round-trip through TripSyncItemDto on the Android side but were
                # silently dropped here since this schema didn't declare them.
                # Generated up front (rather than left to Trip's default factory) so a
                # voucher redemption below can record the real id of the row that's
                # about to be inserted on Voucher.redeemed_by_trip_id.
                new_trip_id = str(uuid.uuid4())
                split_payments_to_store: list[dict] | None = None
                if item.payment_method == "voucher":
                    await payments_service.redeem_voucher(
                        session, tenant_id=tenant_id, voucher_code=item.voucher_code or "", trip_id=new_trip_id
                    )
                elif item.payment_method == "account":
                    await payments_service.validate_account_reference(
                        session, tenant_id=tenant_id, account_reference=item.account_reference or ""
                    )
                elif item.payment_method == "split_fare":
                    subtotal = sum(
                        (Decimal(str(leg.amount)) for leg in (item.split_payments or [])), Decimal(0)
                    )
                    if round_half_up(subtotal) != round_half_up(breakdown.grand_total):
                        raise SplitPaymentMismatchError(
                            f"split_payments sum to {subtotal} but trip total is {breakdown.grand_total}"
                        )
                    split_payments_to_store = [{"method": leg.method, "amount": str(leg.amount)} for leg in item.split_payments]

                trip = Trip(
                    id=new_trip_id,
                    tenant_id=tenant_id,
                    client_uuid=item.client_uuid,
                    vehicle_id=item.vehicle_id,
                    driver_id=item.driver_id,
                    shift_id=item.shift_id,
                    tariff_id=item.tariff_id,
                    type=item.type,
                    # Carried straight through from the device -- see Trip.simulated. The
                    # server cannot detect a simulated trip on its own (the trace replays
                    # cleanly, which is the whole problem), so the honest flag is the one
                    # the device that fabricated the fixes sends.
                    simulated=item.simulated,
                    status=TRIP_STATUS_CLOSED,
                    time_class=time_class.value,
                    is_peak=is_peak,
                    maxi=is_maxi_vehicle,
                    passenger_count=item.passenger_count,
                    wheelchair_hiring=item.wheelchair_hiring,
                    airport_rank_requested_maxi=item.airport_rank_requested_maxi,
                    start_at=item.start_at,
                    end_at=item.end_at,
                    start_lat=item.start_lat,
                    start_lng=item.start_lng,
                    end_lat=item.end_lat,
                    end_lng=item.end_lng,
                    distance_m=distance_m,
                    moving_s=moving_s,
                    waiting_s=waiting_s,
                    stopped_s=stopped_s,
                    gps_blackout_events=gps_blackout_events or None,
                    device_gps_blackout_segments=(
                        [segment.model_dump(mode="json") for segment in item.gps_blackout_segments]
                        or None
                    ),
                    blackout_reconciliation=blackout_reconciliation or None,
                    auto_tolled_roads=device_tolled_roads or None,
                    flag_fall=breakdown.flag_fall,
                    dist_amount=breakdown.distance_charge,
                    wait_amount=breakdown.waiting_charge,
                    peak_amount=breakdown.peak_charge,
                    tolls=breakdown.tolls,
                    psl=breakdown.psl,
                    extras=breakdown.extras,
                    subtotal=breakdown.fare_total,
                    surcharge=breakdown.surcharge,
                    total=stored_total,
                    gst_component=stored_gst,
                    payment_method=item.payment_method,
                    voucher_code=item.voucher_code,
                    account_reference=item.account_reference,
                    split_payments=split_payments_to_store,
                    gps_trace_ref=item.gps_trace_ref,
                    max_fare_check_passed=fare_check_passed,
                    variance_pct=variance_pct,
                    flagged_for_review=not fare_check_passed,
                    review_notes=auto_flag_reason,
                    receipt_ref=item.receipt_ref or f"RCPT-SYNC-{item.client_uuid[:8].upper()}",
                    negotiated_total=item.negotiated_total,
                    # Driver tip (Close & Pay "tips" pass) — see Trip.tip_amount's doc (deviation #6).
                    # This is the ONLY network path this app's offline-first close flow actually makes
                    # (see TripSyncItemDto's own doc comment, ApiService.kt), so a tip entered on-device
                    # must round-trip here, not only through the direct (no real call site) /close
                    # endpoint above — never folded into breakdown/device_total either side.
                    tip_amount=item.tip_amount,
                )
                session.add(trip)

                # Durable GPS-trace persistence (see app.models.trips.TripGpsTrace's
                # module docstring for the full design rationale) — added to the SAME
                # flush as `trip` above, not a separate one, so a racing duplicate
                # client_uuid (the IntegrityError branch below) rolls the trace back
                # together with the trip it belongs to rather than orphaning it.
                # `build_gps_trace_row` itself returns None (no row at all) for an
                # empty gps_trace — see that function's own doc comment for why this
                # must never create a junk "recorded but empty" row.
                trace_row = build_gps_trace_row(
                    tenant_id=tenant_id,
                    trip_id=new_trip_id,
                    gps_trace=item.gps_trace,
                    recorded_at=item.end_at,
                )
                if trace_row is not None:
                    session.add(trace_row)

                # Raises IntegrityError if we lost a race against another concurrent
                # sync of the same client_uuid; caught OUTSIDE the savepoint below,
                # because rolling that back is the savepoint's job, not this line's.
                await session.flush()
                # Same per-trip PSL accrual the online close path makes
                # (app.services.trips.close_trip) — inside this item's
                # savepoint, so a failed item never leaves a levy on the
                # ledger for a trip that was not stored.
                await psl_ledger_service.accrue_trip_psl(session, trip=trip)
                created = trip
        except (
            UnknownTariffError,
            InvalidVoucherCodeError,
            InvalidAccountReferenceError,
            SplitPaymentMismatchError,
        ) as exc:
            # This one item's data is unacceptable. Its savepoint is already
            # rolled back by the context manager; every sibling stands.
            results.append(
                TripSyncResultItem(
                    client_uuid=item.client_uuid,
                    duplicate=False,
                    status="failed",
                    reason=str(exc),
                    trip=None,
                )
            )
            continue
        except IntegrityError:
            # Lost a race against another concurrent sync of the same
            # client_uuid — treat as duplicate rather than failing the item.
            # Only this item's savepoint was rolled back, so the re-read
            # below still sees the batch's earlier work.
            existing = await session.execute(
                select(Trip).where(
                    Trip.tenant_id == tenant_id, Trip.client_uuid == item.client_uuid
                )
            )
            trip = existing.scalar_one()
            results.append(
                TripSyncResultItem(
                    client_uuid=item.client_uuid, duplicate=True, status="duplicate", trip=trip
                )
            )
            continue

        newly_created.append(created)
        results.append(
            TripSyncResultItem(
                client_uuid=item.client_uuid, duplicate=False, status="synced", trip=created
            )
        )

    await session.commit()
    for trip in newly_created:
        await session.refresh(trip)

    return TripSyncResponse(results=results)


# --- List -----------------------------------------------------------------


@router.get("", response_model=TripListResponse)
async def list_trips(
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    status_filter: str | None = Query(None, alias="status"),
    type_filter: str | None = Query(None, alias="type"),
    vehicle_id: str | None = None,
    driver_id: str | None = None,
    flagged_for_review: bool | None = Query(
        None, description="Filter to trips flagged (or not) for operator review — blueprint 5.2.5 dashboard 'flagged' view"
    ),
    start_from: datetime | None = Query(
        None, description="Trips whose start_at is on or after this timestamp (inclusive)."
    ),
    start_to: datetime | None = Query(
        None, description="Trips whose start_at is on or before this timestamp (inclusive)."
    ),
    end_from: datetime | None = Query(
        None,
        description=(
            "Trips whose end_at is on or after this timestamp (inclusive). An open trip has "
            "end_at=None and never matches this filter — use it with status=closed."
        ),
    ),
    end_to: datetime | None = Query(
        None, description="Trips whose end_at is on or before this timestamp (inclusive)."
    ),
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=200),
) -> TripListResponse:
    """Date-range filters (`start_from`/`start_to`/`end_from`/`end_to`) were
    added so a caller counting trips inside a fixed window — e.g. an
    incentive's `[starts_at, ends_at)` — can get the server's real `total`
    for that window directly, rather than fetching the most recent `limit`
    closed trips and hoping the window is fully covered (see
    `dashboard/src/pages/driver-engagement/hooks.ts`'s
    `useIncentiveProgressQuery`, which documents exactly this gap and the
    "maybe incomplete" floor it has to report as a result)."""
    if status_filter is not None and status_filter not in {TRIP_STATUS_OPEN, TRIP_STATUS_CLOSED}:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid status filter")
    if type_filter is not None and type_filter not in TRIP_TYPES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid type filter")

    filters = [Trip.tenant_id == tenant_id]
    if status_filter is not None:
        filters.append(Trip.status == status_filter)
    if type_filter is not None:
        filters.append(Trip.type == type_filter)
    if vehicle_id is not None:
        filters.append(Trip.vehicle_id == vehicle_id)
    if driver_id is not None:
        filters.append(Trip.driver_id == driver_id)
    if flagged_for_review is not None:
        filters.append(Trip.flagged_for_review == flagged_for_review)
    if start_from is not None:
        filters.append(Trip.start_at >= start_from)
    if start_to is not None:
        filters.append(Trip.start_at <= start_to)
    if end_from is not None:
        filters.append(Trip.end_at >= end_from)
    if end_to is not None:
        filters.append(Trip.end_at <= end_to)

    total_result = await session.execute(select(func.count()).select_from(Trip).where(*filters))
    total = total_result.scalar_one()

    result = await session.execute(
        select(Trip).where(*filters).order_by(Trip.start_at.desc()).offset(skip).limit(limit)
    )
    items = list(result.scalars().all())

    return TripListResponse(items=items, total=total, skip=skip, limit=limit)


# --- Earnings today (dashboard tiles) ------------------------------------


@router.get("/earnings/today", response_model=DriverEarningsTodayRead)
async def earnings_today(
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DriverEarningsTodayRead:
    """`/earnings/today` is two path segments, so it can never collide with
    `GET /{trip_id}` below (a single segment) regardless of registration
    order — kept above it anyway, next to `list_trips`, since both are
    collection-level reads.

    Caller-scoped, matching `app.api.v1.me`'s convention: always the
    authenticated caller's own driver id
    (`app.services.driver_engagement.resolve_driver_id`), never a query
    param — a driver cannot read another driver's earnings through this
    route. See `app.services.trips.driver_earnings_today` for the real
    aggregate this returns and its "today" convention.
    """
    driver_id = driver_engagement_service.resolve_driver_id(current_user)
    result = await driver_earnings_today(session, tenant_id=tenant_id, driver_id=driver_id)
    return DriverEarningsTodayRead(
        driver_id=result.driver_id,
        date=result.today.isoformat(),
        today_total=result.today_total,
        yesterday_total=result.yesterday_total,
        pct_change=result.pct_change,
        trips_completed_today=result.trips_completed_today,
        flagged_count=result.flagged_count,
    )


# --- Get by id ----------------------------------------------------------


@router.get("/{trip_id}", response_model=TripRead)
async def get_trip(
    trip_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    return await _get_trip_or_404(trip_id, tenant_id, session)


# --- GPS trace (dashboard trip-detail route map) -----------------------------


@router.get("/{trip_id}/gps-trace", response_model=TripGpsTraceRead)
async def get_trip_gps_trace(
    trip_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> TripGpsTraceRead:
    """Dedicated fetch for the durable trace `app.models.trips.TripGpsTrace`
    stores — deliberately NOT part of `GET /v1/trips`/`GET /v1/trips/{id}`
    (see that model's own docstring for why a paged list response must never
    carry one of these). The dashboard's trip-detail modal calls this only
    when it actually opens (see `dashboard/src/hooks/useTrips.ts`'s
    `useTripGpsTraceQuery`), not for every row in the trips table.

    `/{trip_id}/gps-trace` is two path segments, so it can never collide with
    the single-segment `GET /{trip_id}` above regardless of registration
    order — same reasoning as `earnings_today`'s own doc comment.

    Two distinct "nothing here" outcomes, deliberately not conflated:
      * 404 — `trip_id` doesn't resolve to a trip owned by this tenant at all
        (via `_get_trip_or_404`, same tenant-scoping as every other endpoint
        in this file — a trip belonging to a different tenant 404s exactly
        like one that doesn't exist, never leaking whether it belongs to
        someone else).
      * 200 with `points: []`/`point_count: 0` — the trip is real, but no
        trace was ever stored for it: either it was opened+closed through the
        online create/tick/close flow (which never carries a raw trace to
        persist in the first place), or it was synced with an empty
        `gps_trace` (today's Android-bug reality — see
        `app.schemas.trips.TripSyncItem.gps_trace`'s doc comment). This is the
        honest "no route recorded" state `TripRouteMap` already degrades to
        (A/B pins + labelled straight-line stand-in) — never a fabricated
        route.
    """
    await _get_trip_or_404(trip_id, tenant_id, session)

    result = await session.execute(
        select(TripGpsTrace).where(
            TripGpsTrace.tenant_id == tenant_id, TripGpsTrace.trip_id == trip_id
        )
    )
    trace = result.scalar_one_or_none()
    if trace is None:
        return TripGpsTraceRead(trip_id=trip_id, points=[], point_count=0)

    points = [TelemetryPoint(**point) for point in trace.points]
    return TripGpsTraceRead(trip_id=trip_id, points=points, point_count=len(points))


# --- Update (partial; pre-close mutable fields) --------------------------


@router.patch("/{trip_id}", response_model=TripRead)
async def update_trip(
    trip_id: str,
    payload: TripUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
    _require_trip_write_access(current_user, driver_id=trip.driver_id, action="update")
    # Reassigning a trip to a different driver is a staff action: a driver
    # who could set driver_id would be able to hand their own trip away, or
    # (having passed the check above on a trip that is genuinely theirs)
    # walk it onto another driver's sheet.
    if payload.driver_id is not None and payload.driver_id != trip.driver_id:
        _require_trip_write_access(current_user, driver_id=None, action="reassign")

    for field, value in payload.model_dump(exclude_unset=True).items():
        if field == "split_payments" and value is not None:
            # JSON column — SQLAlchemy's JSON type serializes via json.dumps,
            # which can't handle Decimal, so stringify amounts (same
            # convention app.services.trips.close_trip uses when it stores
            # this column).
            value = [{"method": item["method"], "amount": str(item["amount"])} for item in value]
        setattr(trip, field, value)

    await session.commit()
    await session.refresh(trip)
    return trip


# --- Delete (only while still open — closed trips are financial records) -


@router.delete("/{trip_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_trip(
    trip_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> None:
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
    _require_trip_write_access(current_user, driver_id=trip.driver_id, action="delete")
    if trip.status == TRIP_STATUS_CLOSED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Cannot delete a closed trip — it is a financial record",
        )
    await session.delete(trip)
    await session.commit()


# --- Tick -------------------------------------------------------------------


async def _run_tick_advisory_checks(
    *,
    tenant_id: str,
    driver_id: str,
    vehicle_id: str,
    shift_id: str | None,
    points: list[TelemetryPoint],
) -> None:
    """The fatigue + compliance-expiry checks that hang off a trip tick, run
    AFTER the tick's own fare-bearing commit and on a session of their own.
    Cannot fail the tick endpoint, by contract — same contract, and the same
    reasoning, as `app.services.lazy_maintenance` (read its NEVER RAISES and
    ISOLATED SESSION sections; this is that module's pattern applied to the
    one hook that predates it).

    WHY THE FARE OUTRANKS THE ALERT. These checks are advisory: they raise
    `FatigueAlert` rows a dispatcher looks at later. The tick is metrology —
    distance and waiting time accrued by the fare engine against a regulated
    meter, which cannot be re-derived once lost because the client will not
    re-send those points (the replay guard in `tick_trip` deliberately
    no-ops a retry of an already-anchored batch). An advisory check must
    never be able to delete a measurement.

    THE TRADE, EXPLICITLY: the alert row and the tick are no longer atomic.
    There are now two commits where there was one, and a crash (or a failed
    check) between them loses the alert while keeping the fare. That is
    intended, but the cost is NOT uniform across the checks, and an earlier
    version of this note overstated it. Precisely:

      * shift_duration_exceeded and no_break_taken DO recover. Both are a
        pure function of how long the shift has been open, both dedup per
        shift, and both are re-run off `POST /v1/fleet/positions` (~every 5s
        per on-shift tablet) and off `GET /v1/shifts` — see
        `app.services.lazy_maintenance._run_shift_checks`. A row lost here is
        re-raised on the next beat, seconds later.
      * The driver-licence/authority and vehicle-registration/insurance
        expiry alerts DO recover, for the same reason: the condition is a
        date fact that stays true until the document is renewed, the dedup is
        "an unacknowledged alert of this kind already exists", and
        `lazy_maintenance` re-runs both passes off those same two endpoints.
      * speed_exceeded does NOT recover. It is LOST FOR GOOD. It is
        deliberately neither deduped nor idempotent — it is evidence about
        one individual telemetry point ("every telemetry point over threshold
        raises its own alert", `app.services.fatigue`) — and nothing else in
        the system will ever re-raise it: `lazy_maintenance` explicitly does
        NOT run `check_speed` off the position heartbeat (its WHAT IS
        DELIBERATELY *NOT* CHECKED HERE section says why), no shift read runs
        it, and the tablet cannot re-send the points because `tick_trip`'s
        replay guard answers a retry of an already-anchored batch as a no-op.
        A crash in the window below therefore permanently loses the record
        that this vehicle was doing >120km/h at that instant.

    That last one is a real, accepted loss, not a deferred one. It is
    accepted because the alternative is strictly worse on both axes: under
    the old ordering the SAME failure lost the overspeed alert *as well as*
    the fare, since the check that raised took the whole transaction with it.
    Nothing recovers a lost speed alert, but a lost half-kilometre of billed
    distance is both destroyed metrology and money the operator cannot
    invoice. Between two unrecoverable losses, keep the one that is regulated
    measurement.

    EACH CHECK IS INDIVIDUALLY NON-FATAL, AND EACH RUNS IN ITS OWN SAVEPOINT.
    One failing check must not suppress the others, so each is wrapped on its
    own rather than the whole block being wrapped once. A bare `try/except`
    is not enough to deliver that, though: a check that fails on a FLUSH
    (`IntegrityError` against the partial unique index on `fatigue_alerts`, a
    NOT NULL, a FK) leaves the whole transaction in a must-rollback state, so
    every later check raises `PendingRollbackError` on first use and the final
    `commit()` fails too -- "one failure cannot suppress the others" would
    hold only for the benign errors that leave the transaction usable. Each
    check therefore runs inside `begin_nested()`: the failure rolls back to
    that check's SAVEPOINT only, the transaction is usable again immediately,
    and alerts already added by EARLIER checks in this tick survive. Same
    idiom, and the same reason, as
    `app.services.fatigue._insert_alert_if_new`. Failures are logged at
    ERROR, never swallowed silently.

    POOL HEADROOM (checked 2026-09-19). Holding this session while the
    request session is still open means a tick occupies TWO pooled
    connections, not one. `app.core.database` builds the engine on
    SQLAlchemy's defaults, i.e. `AsyncAdaptedQueuePool` with pool_size=5 and
    max_overflow=10 -- a hard ceiling of 15 connections -- in ONE uvicorn
    worker (docker-compose pins the worker count to one, deliberately and
    permanently; see the comment there). So the ceiling on genuinely
    concurrent ticks drops from 15 to 7, and the position heartbeat
    (`app.services.lazy_maintenance`) and live-traffic refresh
    (`app.services.live_traffic`) already take a second connection the same
    way on their own hot paths. Two things keep that comfortable: this
    session is opened only AFTER the fare commit, so it never overlaps the
    fare-bearing transaction's critical section, and it is short-lived (a
    handful of small statements, no external I/O). If this path is ever
    measured against pool exhaustion, the fix is to size the pool explicitly
    at the engine rather than to move these checks back inside the fare
    transaction -- that ordering is what this function exists to prevent.

    OWN SESSION, and only plain scalars passed in. Everything this function
    needs arrives as a `str`/`None`/`TelemetryPoint` local snapshotted by the
    caller from the already-loaded `Trip` — no ORM object bound to the
    request session crosses this boundary. That matters: a failure in here
    ends with an uncommitted session being closed, and had that been the
    request's own session the implicit rollback would have EXPIRED `trip`,
    whose attributes FastAPI is about to read synchronously while
    serializing `TripRead` — a lazy reload outside the greenlet context,
    i.e. `MissingGreenlet`, i.e. a 500 on the exact path this function exists
    to protect. That is not a hypothetical either: it is the production
    incident recorded in `app.services.lazy_maintenance`'s docstring
    (request_id cd2486bb63374e4384e20cdde6dc3558).
    """
    try:
        async with AsyncSessionLocal() as check_session:
            for point in points:
                try:
                    # SAVEPOINT per check -- see EACH CHECK IS INDIVIDUALLY
                    # NON-FATAL above. Rolling back to it is what makes the
                    # `except` below survivable for a failure that has
                    # invalidated the transaction.
                    async with check_session.begin_nested():
                        await fatigue_service.check_speed(
                            check_session,
                            tenant_id=tenant_id,
                            driver_id=driver_id,
                            shift_id=shift_id,
                            speed_kmh=point.speed_kmh,
                            ts=point.ts,
                        )
                except Exception:  # broad on purpose — see this function's docstring
                    logger.exception(
                        "tick advisory check_speed failed for driver %s (tenant %s); the "
                        "tick's fare is already committed and is unaffected",
                        driver_id,
                        tenant_id,
                    )

            if shift_id is not None:
                try:
                    async with check_session.begin_nested():
                        shift = await fatigue_service.get_shift_or_none(
                            check_session, tenant_id=tenant_id, shift_id=shift_id
                        )
                except Exception:  # broad on purpose — see this function's docstring
                    logger.exception(
                        "tick advisory shift lookup failed for shift %s (tenant %s)",
                        shift_id,
                        tenant_id,
                    )
                    shift = None
                if shift is not None:
                    try:
                        async with check_session.begin_nested():
                            await fatigue_service.check_shift_duration(
                                check_session, tenant_id=tenant_id, shift=shift
                            )
                    except Exception:  # broad on purpose — see this function's docstring
                        logger.exception(
                            "tick advisory check_shift_duration failed for shift %s (tenant %s)",
                            shift_id,
                            tenant_id,
                        )
                    try:
                        async with check_session.begin_nested():
                            await fatigue_service.check_no_break_taken(
                                check_session, tenant_id=tenant_id, shift=shift
                            )
                    except Exception:  # broad on purpose — see this function's docstring
                        logger.exception(
                            "tick advisory check_no_break_taken failed for shift %s (tenant %s)",
                            shift_id,
                            tenant_id,
                        )

            try:
                async with check_session.begin_nested():
                    driver_result = await check_session.execute(
                        select(User).where(User.id == driver_id, User.tenant_id == tenant_id)
                    )
                    driver = driver_result.scalar_one_or_none()
                    if driver is not None:
                        await compliance_expiry_service.run_driver_compliance_checks(
                            check_session, tenant_id=tenant_id, driver=driver
                        )
            except Exception:  # broad on purpose — see this function's docstring
                logger.exception(
                    "tick advisory driver compliance checks failed for driver %s (tenant %s)",
                    driver_id,
                    tenant_id,
                )

            try:
                async with check_session.begin_nested():
                    vehicle_result = await check_session.execute(
                        select(Vehicle).where(Vehicle.id == vehicle_id, Vehicle.tenant_id == tenant_id)
                    )
                    vehicle = vehicle_result.scalar_one_or_none()
                    if vehicle is not None:
                        await compliance_expiry_service.run_vehicle_compliance_checks(
                            check_session, tenant_id=tenant_id, vehicle=vehicle
                        )
            except Exception:  # broad on purpose — see this function's docstring
                logger.exception(
                    "tick advisory vehicle compliance checks failed for vehicle %s (tenant %s)",
                    vehicle_id,
                    tenant_id,
                )

            await check_session.commit()
    except Exception:  # broad on purpose — see this function's docstring
        # Outer net: session open/commit itself failing, or anything the
        # per-check wrappers above did not sit around. The tick's fare is
        # already committed and stays committed; only the alerts are lost.
        logger.exception(
            "tick advisory checks failed wholesale for trip driver %s / vehicle %s (tenant %s); "
            "the tick's fare is already committed and is unaffected, but no alert was raised "
            "on this tick",
            driver_id,
            vehicle_id,
            tenant_id,
        )
        # Nothing to roll back on the REQUEST session here -- the checks above
        # ran entirely on `check_session`, which this function opened, owns,
        # and whose uncommitted work is discarded when the `async with` block
        # exits. Same reasoning, spelled out the same way, as the except block
        # in `app.services.lazy_maintenance.run_checks_for_vehicle`. Rolling
        # the request session back would be actively harmful: it would expire
        # `trip`, which FastAPI is about to serialize.


@router.patch("/{trip_id}/tick", response_model=TripRead)
async def tick_trip(
    trip_id: str,
    payload: TripTickRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    """Also runs driver-fatigue checks (blueprint 12.3) as a side effect of
    every tick, via `app.services.fatigue`: a per-point speed_exceeded check
    against each telemetry point in this batch, and (if the trip is attached
    to a shift) a shift_duration_exceeded check against that shift's elapsed
    open time. Both write FatigueAlert rows on a SEPARATE session, committed
    AFTER — and never in the same transaction as — the tick's own commit
    (2026-09-19; it used to be one transaction, and that is precisely the bug
    `_run_tick_advisory_checks` below exists to fix). See that module for
    thresholds/simplifications and `app/api/v1/fatigue_alerts.py` for how
    they're surfaced/acknowledged.

    Also runs driver-license/authority and vehicle-registration/insurance
    compliance-expiry checks (blueprint 7.2.3/7.2.4/10.1) via
    `app.services.compliance_expiry` — this is the "wherever fatigue checks
    are already triggered" call site for that pass. Fails open (silently
    skips) if `trip.driver_id`/`trip.vehicle_id` don't resolve to a real row,
    same reasoning as the shift lookup above (Trip's cross-domain refs are
    unconstrained — see app.models.trips).

    All of those checks now run AFTER the tick's own commit and on their own
    session — see `_run_tick_advisory_checks` below for why, and for the
    atomicity trade that buys."""
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
    _require_trip_write_access(current_user, driver_id=trip.driver_id, action="tick")
    if trip.status != TRIP_STATUS_OPEN:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Trip is not open")

    # --- replay guard (backend audit §4) -------------------------------
    # A retried tick used to re-bill its distance. Answer a replay as a 200
    # no-op with the trip exactly as it stands: the retrying client gets the
    # same success it would have got had its first attempt's response
    # arrived, and stops retrying. Deliberately returned BEFORE the fatigue
    # and compliance-expiry side effects below as well as before apply_tick,
    # so a replay cannot raise a duplicate speed-exceeded alert on points
    # that were already checked.
    if is_replayed_tick(trip, payload.tick_seq):
        return trip
    accepted_points = accepted_tick_points(trip, payload.points)
    if not accepted_points:
        # Every point in this batch is at or before the trip's continuity
        # anchor, i.e. already billed — a replay by a client that sends no
        # tick_seq. Still record the sequence high-water mark if one came
        # with it, so the next attempt is caught by the cheaper check above.
        if payload.tick_seq is not None:
            trip.last_tick_seq = max(payload.tick_seq, trip.last_tick_seq or 0)
            await session.commit()
            await session.refresh(trip)
        return trip

    try:
        await apply_tick(
            session,
            tenant_id=tenant_id,
            trip=trip,
            points=accepted_points,
            dest_lat=payload.dest_lat,
            dest_lng=payload.dest_lng,
            tick_seq=payload.tick_seq,
        )
    except UnknownTariffError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

    # --- FARE COMMIT BOUNDARY (2026-09-19) -----------------------------
    # This commit used to sit BELOW the advisory fatigue/compliance checks,
    # which meant every one of those checks ran inside the same, still-open
    # transaction as the tick. Any exception out of any of them unwound the
    # request and rolled the tick back with it, so the distance and waiting
    # time `apply_tick` had just accrued were silently destroyed — on a
    # metrology-regulated meter that is destroyed measurement data, and the
    # tablet had already moved on past those telemetry points (they are at or
    # before the trip's continuity anchor now, so the client's retry is
    # answered by the replay guard above and never re-bills them). It was not
    # hypothetical: `app.services.compliance_expiry._unacknowledged_alert_exists`
    # used `scalar_one_or_none()` over a filter with no uniqueness guard, so a
    # driver or vehicle with two unacknowledged alerts of one kind raised
    # `MultipleResultsFound` from inside `run_*_compliance_checks` and took
    # that tick's fare with it (that query is fixed too, but being ONE bug
    # away from losing fare is not an acceptable structure for this path).
    #
    # Fare first, advisory second. Nothing between `apply_tick` and this line.
    await session.commit()
    await session.refresh(trip)

    await _run_tick_advisory_checks(
        tenant_id=tenant_id,
        driver_id=trip.driver_id,
        vehicle_id=trip.vehicle_id,
        shift_id=trip.shift_id,
        points=list(accepted_points),
    )
    return trip


# --- Close --------------------------------------------------------------


@router.post("/{trip_id}/close", response_model=TripRead)
async def close_trip_endpoint(
    trip_id: str,
    payload: TripCloseRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
    _require_trip_write_access(current_user, driver_id=trip.driver_id, action="close")
    if trip.status != TRIP_STATUS_OPEN:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Trip is already closed")

    params = CloseParams(
        end_at=payload.end_at or datetime.now(UTC),
        end_lat=payload.end_lat if payload.end_lat is not None else trip.last_lat,
        end_lng=payload.end_lng if payload.end_lng is not None else trip.last_lng,
        payment_method=payload.payment_method or trip.payment_method,
        surcharge_pct=payload.surcharge_pct,
        cleaning_fee=payload.cleaning_fee,
        include_psl=payload.include_psl,
        receipt_ref=payload.receipt_ref,
        device_total=payload.device_total,
        voucher_code=payload.voucher_code,
        account_reference=payload.account_reference,
        split_payments=(
            [item.model_dump() for item in payload.split_payments] if payload.split_payments else None
        ),
        tip_amount=payload.tip_amount,
    )
    try:
        await close_trip(session, tenant_id=tenant_id, trip=trip, params=params)
    except UnknownTariffError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
    except SplitPaymentMismatchError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
    except InvalidVoucherCodeError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
    except InvalidAccountReferenceError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

    await session.commit()
    await session.refresh(trip)
    return trip


# --- Dispute flagging (blueprint 5.2.5 "Dispute" button / 6.1.3 schema) ------


@router.patch("/{trip_id}/flag", response_model=TripRead)
async def flag_trip(
    trip_id: str,
    payload: TripFlagRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    """Blueprint 5.2.5's "Dispute" button: the trip's own driver, or a staff
    role (`_DISPATCH_ROLES`), may flag a *closed* trip for operator review
    with a reason (`flagged=True`, the default — see `TripFlagRequest`). Only
    a staff role may clear an existing flag (`flagged=False`) — a driver
    cannot resolve their own dispute."""
    trip = await _get_trip_or_404(trip_id, tenant_id, session)

    is_staff = current_user.role in _DISPATCH_ROLES
    is_own_trip = current_user.id == trip.driver_id

    if payload.flagged:
        if not (is_staff or is_own_trip):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the trip's own driver or a staff role may flag it for review",
            )
    else:
        if not is_staff:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only a staff role may clear a trip's review flag",
            )

    try:
        flag_trip_for_review(trip=trip, flagged=payload.flagged, reason=payload.reason)
    except TripNotClosedError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except DisputeReasonRequiredError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

    await session.commit()
    await session.refresh(trip)
    return trip


# --- Receipt delivery (blueprint 5.2.6/8.5) ----------------------------------
# Real PDF generation (app.services.receipts) + email/SMS delivery, each with
# the same mock-fallback contract as the Stripe integration in
# app.services.payments — see that module's docstring for the pattern this
# mirrors. Both endpoints require the trip to be closed (the fare breakdown
# columns this renders from are only final after POST .../close).


@router.post("/{trip_id}/receipt/email", response_model=ReceiptEmailResponse)
async def email_receipt(
    trip_id: str,
    payload: ReceiptEmailRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> ReceiptEmailResponse:
    trip = await _get_trip_or_404(trip_id, tenant_id, session)

    try:
        absolute_path, relative_path, generated_now = await receipts_service.ensure_receipt_pdf(
            session, tenant_id=tenant_id, trip=trip
        )
    except receipts_service.TripNotClosedError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    pdf_bytes = absolute_path.read_bytes()
    result = receipts_service.send_receipt_email(
        to_email=payload.to_email,
        trip=trip,
        pdf_bytes=pdf_bytes,
        pdf_filename=absolute_path.name,
    )

    return ReceiptEmailResponse(
        mock=result["mock"],
        would_send_to=result.get("would_send_to"),
        to_email=result.get("to_email"),
        sendgrid_status_code=result.get("sendgrid_status_code"),
        receipt_ref=trip.receipt_ref,
        pdf_relative_path=relative_path,
        pdf_generated_now=generated_now,
    )


@router.post("/{trip_id}/receipt/sms", response_model=ReceiptSmsResponse)
async def sms_receipt(
    trip_id: str,
    payload: ReceiptSmsRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> ReceiptSmsResponse:
    trip = await _get_trip_or_404(trip_id, tenant_id, session)

    try:
        _absolute_path, relative_path, generated_now = await receipts_service.ensure_receipt_pdf(
            session, tenant_id=tenant_id, trip=trip
        )
    except receipts_service.TripNotClosedError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    result = receipts_service.send_receipt_sms(to_phone=payload.to_phone, trip=trip)

    return ReceiptSmsResponse(
        mock=result["mock"],
        would_send_to=result.get("would_send_to"),
        to_phone=result.get("to_phone"),
        twilio_sid=result.get("twilio_sid"),
        message=result.get("message"),
        receipt_ref=trip.receipt_ref,
        pdf_relative_path=relative_path,
        pdf_generated_now=generated_now,
    )


# --- Owner fare correction (2026-09-14) ---------------------------------------------


@router.post("/{trip_id}/fare-correction", response_model=TripRead)
async def correct_trip_fare(
    trip_id: str,
    payload: TripFareCorrectionRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    """Sets a CLOSED trip's stored total (and a proportional GST component) to
    `payload.total`, appending `payload.reason` to `review_notes`. Owner/admin
    only. See `TripFareCorrectionRequest`'s doc for the one situation this is
    for; it is deliberately NOT a general "edit the fare" tool -- the flag is
    left as it was, so a corrected trip still surfaces on the review list
    with the full story in its notes.

    Two things follow the correction, both found missing on the production
    dashboard (admin-panel plan, items 1.2/1.3):

    * an audit-log entry (`action="fare_correction"`, before/after totals,
      the caller as actor) in the same transaction — `review_notes` alone is
      not tamper-evident, and the Audit Log page had no trace of a fare of
      record being changed;
    * the trip's shift, if it has one, gets its stored cash/card aggregates
      recomputed and is flipped back to unreconciled with a note — those
      aggregates were frozen at cash-up, so a shift kept showing $32.52 and
      "Reconciled: Yes" after its one trip was corrected to $59.03.
    """
    if current_user.role not in ("owner", "admin"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Only an owner or admin may correct a fare of record"
        )
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
    if trip.status != TRIP_STATUS_CLOSED:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Only a closed trip's fare can be corrected")

    previous_total = trip.total
    new_total = round_half_up(payload.total)
    if previous_total and previous_total > 0:
        trip.gst_component = round_half_up(new_total * trip.gst_component / previous_total)
    else:
        trip.gst_component = round_half_up(new_total / Decimal(11))
    trip.total = new_total
    note = (
        f"Fare correction by {current_user.role} {current_user.id} on "
        f"{datetime.now(UTC).isoformat(timespec='seconds')}: total {previous_total} -> {new_total}. "
        f"{payload.reason.strip()}"
    )
    trip.review_notes = f"{trip.review_notes} | {note}" if trip.review_notes else note

    await record_audit(
        session,
        tenant_id=tenant_id,
        actor_user_id=current_user.id,
        action="fare_correction",
        entity_type="trip",
        entity_id=trip.id,
        before={"total": str(previous_total)},
        after={"total": str(new_total), "reason": payload.reason.strip()},
    )

    if trip.shift_id:
        shift = (
            await session.execute(
                select(Shift).where(Shift.id == trip.shift_id, Shift.tenant_id == tenant_id)
            )
        ).scalar_one_or_none()
        if shift is not None:
            await shift_service.refresh_trip_aggregates(session, shift)
            shift_service.mark_needs_reconciliation(
                shift,
                reason=(
                    f"fare corrected on {datetime.now(UTC).date().isoformat()} "
                    f"(trip {trip.id}: {previous_total} -> {new_total}); re-reconcile"
                ),
            )

    await session.commit()
    await session.refresh(trip)
    return trip
