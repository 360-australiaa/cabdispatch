"""Trips domain router — offline-sync-capable taxi-meter journey records.

Full CRUD (list/get/create/update/delete) plus three domain-specific
endpoints: PATCH .../tick (telemetry batch -> running totals), POST
.../close (finalize via the fare engine), and POST /sync (bulk
offline-replay upload with per-item idempotency + server-side fare
verification).

EVERY query in this file filters by tenant_id via `get_current_tenant_id` —
the sole multi-tenancy enforcement mechanism in this system.
"""
from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_STATUS_OPEN, TRIP_TYPES, Trip
from app.schemas.trips import (
    TripCloseRequest,
    TripCreate,
    TripListResponse,
    TripRead,
    TripSyncItem,
    TripSyncResponse,
    TripSyncResultItem,
    TripTickRequest,
    TripUpdate,
)
from app.services.trips import (
    CloseParams,
    UnknownTariffError,
    apply_tick,
    close_trip,
    compute_variance_pct,
    recompute_from_trace,
)

router = APIRouter(prefix="/v1/trips", tags=["trips"])


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
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=payload.client_uuid,
        vehicle_id=payload.vehicle_id,
        driver_id=payload.driver_id,
        shift_id=payload.shift_id,
        tariff_id=payload.tariff_id,
        type=payload.type,
        status=TRIP_STATUS_OPEN,
        time_class=payload.time_class,
        is_peak=payload.is_peak,
        maxi=payload.maxi,
        start_at=payload.start_at or datetime.now(UTC),
        start_lat=payload.start_lat,
        start_lng=payload.start_lng,
        payment_method=payload.payment_method,
        tolls=payload.tolls,
        extras=payload.extras,
        gps_trace_ref=payload.gps_trace_ref,
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

        try:
            breakdown, distance_m, moving_s, waiting_s = await recompute_from_trace(
                session,
                tenant_id=tenant_id,
                tariff_id=item.tariff_id,
                trip_type=item.type,
                time_class=item.time_class,
                is_peak=item.is_peak,
                maxi=item.maxi,
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
            )
        except UnknownTariffError as exc:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

        variance_pct = compute_variance_pct(breakdown.grand_total, item.device_total)

        trip = Trip(
            tenant_id=tenant_id,
            client_uuid=item.client_uuid,
            vehicle_id=item.vehicle_id,
            driver_id=item.driver_id,
            shift_id=item.shift_id,
            tariff_id=item.tariff_id,
            type=item.type,
            status=TRIP_STATUS_CLOSED,
            time_class=item.time_class,
            is_peak=item.is_peak,
            maxi=item.maxi,
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
            gps_trace_ref=item.gps_trace_ref,
            max_fare_check_passed=variance_pct <= 1.0,
            variance_pct=variance_pct,
            receipt_ref=item.receipt_ref or f"RCPT-SYNC-{item.client_uuid[:8].upper()}",
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
    trip = await _get_trip_or_404(trip_id, tenant_id, session)
    if trip.status != TRIP_STATUS_OPEN:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Trip is not open")

    try:
        await apply_tick(session, tenant_id=tenant_id, trip=trip, points=payload.points)
    except UnknownTariffError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

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
    )
    try:
        await close_trip(session, tenant_id=tenant_id, trip=trip, params=params)
    except UnknownTariffError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

    await session.commit()
    await session.refresh(trip)
    return trip
