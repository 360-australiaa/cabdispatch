"""Trips domain router — offline-sync-capable taxi-meter journey records.

Full CRUD (list/get/create/update/delete) plus domain-specific endpoints:
PATCH .../tick (telemetry batch -> running totals), POST .../close (finalize
via the fare engine), POST /sync (bulk offline-replay upload with per-item
idempotency + server-side fare verification), and PATCH .../flag (blueprint
5.2.5 "Dispute" button — flag/clear a closed trip for operator review).

EVERY query in this file filters by tenant_id via `get_current_tenant_id` —
the sole multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user
from app.models.fleet import Vehicle
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_STATUS_OPEN, TRIP_TYPES, Trip
from app.models.user import User
from app.schemas.trips import (
    ReceiptEmailRequest,
    ReceiptEmailResponse,
    ReceiptSmsRequest,
    ReceiptSmsResponse,
    TripCloseRequest,
    TripCreate,
    TripFlagRequest,
    TripListResponse,
    TripRead,
    TripSyncItem,
    TripSyncResponse,
    TripSyncResultItem,
    TripTickRequest,
    TripUpdate,
)
from app.services import compliance_expiry as compliance_expiry_service
from app.services import fatigue as fatigue_service
from app.services import receipts as receipts_service
from app.services import payments as payments_service
from app.services.fare_engine import URBAN_TARIFF, resolve_time_class_and_peak, round_half_up
from app.services.payments import InvalidAccountReferenceError, InvalidVoucherCodeError
from app.services.trips import (
    CloseParams,
    DisputeReasonRequiredError,
    SplitPaymentMismatchError,
    TripNotClosedError,
    UnknownTariffError,
    apply_tick,
    close_trip,
    compute_variance_pct,
    flag_trip_for_review,
    recompute_from_trace,
    resolve_is_maxi_vehicle,
    resolve_tariff,
)

router = APIRouter(prefix="/v1/trips", tags=["trips"])

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


# --- Create -------------------------------------------------------------


@router.post("", response_model=TripRead, status_code=status.HTTP_201_CREATED)
async def create_trip(
    payload: TripCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Trip:
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

    for item in items:
        existing = await session.execute(
            select(Trip).where(
                Trip.tenant_id == tenant_id, Trip.client_uuid == item.client_uuid
            )
        )
        trip = existing.scalar_one_or_none()
        if trip is not None:
            results.append(TripSyncResultItem(client_uuid=item.client_uuid, duplicate=True, trip=trip))
            continue

        is_maxi_vehicle = await resolve_is_maxi_vehicle(
            session, tenant_id=tenant_id, vehicle_id=item.vehicle_id
        )
        try:
            # time_class/is_peak deliberately NOT passed through from
            # item.time_class/item.is_peak here — see recompute_from_trace's
            # own doc comment; it resolves both authoritatively itself, from
            # the tariff it looks up and item.start_at, and hands the
            # resolved values back below for the Trip row.
            breakdown, distance_m, moving_s, waiting_s, time_class, is_peak = await recompute_from_trace(
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
            )
        except UnknownTariffError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

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
        if not fare_check_passed:
            auto_flag_reason = (
                f"Auto-flagged: fare variance {variance_pct}% exceeds 1% tolerance "
                f"(device reported {item.device_total}, server recomputed {breakdown.grand_total})"
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
            try:
                await payments_service.redeem_voucher(
                    session, tenant_id=tenant_id, voucher_code=item.voucher_code or "", trip_id=new_trip_id
                )
            except InvalidVoucherCodeError as exc:
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        elif item.payment_method == "account":
            try:
                await payments_service.validate_account_reference(
                    session, tenant_id=tenant_id, account_reference=item.account_reference or ""
                )
            except InvalidAccountReferenceError as exc:
                raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc
        elif item.payment_method == "split_fare":
            subtotal = sum(
                (Decimal(str(leg.amount)) for leg in (item.split_payments or [])), Decimal(0)
            )
            if round_half_up(subtotal) != round_half_up(breakdown.grand_total):
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail=f"split_payments sum to {subtotal} but trip total is {breakdown.grand_total}",
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
            flag_fall=breakdown.flag_fall,
            dist_amount=breakdown.distance_charge,
            wait_amount=breakdown.waiting_charge,
            peak_amount=breakdown.peak_charge,
            tolls=breakdown.tolls,
            psl=breakdown.psl,
            extras=breakdown.extras,
            subtotal=breakdown.fare_total,
            surcharge=breakdown.surcharge,
            total=breakdown.grand_total,
            gst_component=breakdown.gst_component,
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
        try:
            await session.flush()
        except IntegrityError:
            # Lost a race against another concurrent sync of the same
            # client_uuid — treat as duplicate rather than failing the batch.
            await session.rollback()
            existing = await session.execute(
                select(Trip).where(
                    Trip.tenant_id == tenant_id, Trip.client_uuid == item.client_uuid
                )
            )
            trip = existing.scalar_one()
            results.append(TripSyncResultItem(client_uuid=item.client_uuid, duplicate=True, trip=trip))
            continue

        newly_created.append(trip)
        results.append(TripSyncResultItem(client_uuid=item.client_uuid, duplicate=False, trip=trip))

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
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=200),
) -> TripListResponse:
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

    total_result = await session.execute(select(func.count()).select_from(Trip).where(*filters))
    total = total_result.scalar_one()

    result = await session.execute(
        select(Trip).where(*filters).order_by(Trip.start_at.desc()).offset(skip).limit(limit)
    )
    items = list(result.scalars().all())

    return TripListResponse(items=items, total=total, skip=skip, limit=limit)


# --- Get by id ----------------------------------------------------------


@router.get("/{trip_id}", response_model=TripRead)
async def get_trip(
    trip_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    return await _get_trip_or_404(trip_id, tenant_id, session)


# --- Update (partial; pre-close mutable fields) --------------------------


@router.patch("/{trip_id}", response_model=TripRead)
async def update_trip(
    trip_id: str,
    payload: TripUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    trip = await _get_trip_or_404(trip_id, tenant_id, session)

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
    session: AsyncSession = Depends(get_session),
) -> None:
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
    if trip.status == TRIP_STATUS_CLOSED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Cannot delete a closed trip — it is a financial record",
        )
    await session.delete(trip)
    await session.commit()


# --- Tick -------------------------------------------------------------------


@router.patch("/{trip_id}/tick", response_model=TripRead)
async def tick_trip(
    trip_id: str,
    payload: TripTickRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    """Also runs driver-fatigue checks (blueprint 12.3) as a side effect of
    every tick, via `app.services.fatigue`: a per-point speed_exceeded check
    against each telemetry point in this batch, and (if the trip is attached
    to a shift) a shift_duration_exceeded check against that shift's elapsed
    open time. Both write FatigueAlert rows into the same transaction as the
    tick itself — see that module for thresholds/simplifications and
    `app/api/v1/fatigue_alerts.py` for how they're surfaced/acknowledged.

    Also runs driver-license/authority and vehicle-registration/insurance
    compliance-expiry checks (blueprint 7.2.3/7.2.4/10.1) via
    `app.services.compliance_expiry`, in the same transaction — this is the
    "wherever fatigue checks are already triggered" call site for that pass.
    Fails open (silently skips) if `trip.driver_id`/`trip.vehicle_id` don't
    resolve to a real row, same reasoning as the shift lookup above (Trip's
    cross-domain refs are unconstrained — see app.models.trips)."""
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
    if trip.status != TRIP_STATUS_OPEN:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Trip is not open")

    try:
        await apply_tick(session, tenant_id=tenant_id, trip=trip, points=payload.points)
    except UnknownTariffError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

    for point in payload.points:
        await fatigue_service.check_speed(
            session,
            tenant_id=tenant_id,
            driver_id=trip.driver_id,
            shift_id=trip.shift_id,
            speed_kmh=point.speed_kmh,
            ts=point.ts,
        )

    if trip.shift_id is not None:
        shift = await fatigue_service.get_shift_or_none(session, tenant_id=tenant_id, shift_id=trip.shift_id)
        if shift is not None:
            await fatigue_service.check_shift_duration(session, tenant_id=tenant_id, shift=shift)

    driver_result = await session.execute(
        select(User).where(User.id == trip.driver_id, User.tenant_id == tenant_id)
    )
    driver = driver_result.scalar_one_or_none()
    if driver is not None:
        await compliance_expiry_service.run_driver_compliance_checks(session, tenant_id=tenant_id, driver=driver)

    vehicle_result = await session.execute(
        select(Vehicle).where(Vehicle.id == trip.vehicle_id, Vehicle.tenant_id == tenant_id)
    )
    vehicle = vehicle_result.scalar_one_or_none()
    if vehicle is not None:
        await compliance_expiry_service.run_vehicle_compliance_checks(session, tenant_id=tenant_id, vehicle=vehicle)

    await session.commit()
    await session.refresh(trip)
    return trip


# --- Close --------------------------------------------------------------


@router.post("/{trip_id}/close", response_model=TripRead)
async def close_trip_endpoint(
    trip_id: str,
    payload: TripCloseRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> Trip:
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
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
