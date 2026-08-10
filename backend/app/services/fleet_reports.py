"""Per-vehicle aggregation reporting (operations-cycle tracking pass):

1. vehicle_lifetime_totals -- a read-only SUM aggregation across every
   CLOSED Trip ever recorded for a vehicle. Mirrors the classic statutory
   cumulative-totals register every physical taxi meter keeps (cl 14-style
   evidence) -- exactly the kind of number a compliance pack wants. No new
   storage: this is entirely computed from the existing trips table on
   every request, same "aggregate in SQL, never Python-side over a fetched
   list" discipline as app.services.reports.revenue_report.

2. vehicle_pilot_report -- a date-ranged evidence pack for a 60-day pilot
   report: average fare-accuracy variance, a device-uptime estimate, duress
   activation counts, and flagged-for-review trip counts. Reuses
   app.services.reports.date_range_bounds / InvalidDateRangeError rather
   than reimplementing date-range handling -- same [from_date, to_date]
   inclusive-calendar-day semantics as GET /v1/reports/revenue and the
   other /v1/reports endpoints.

Every function here takes tenant_id and filters by it -- the sole
multi-tenancy enforcement mechanism in this system (see
app.core.security.get_current_tenant_id docstring). Money stays Decimal
end-to-end via SQL SUM() over Numeric columns, never floats.
"""
from __future__ import annotations

from datetime import UTC, date, datetime
from decimal import Decimal

from sqlalchemy import Integer, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.duress import DuressEvent
from app.models.fleet import Device, Vehicle
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.services.reports import InvalidDateRangeError, date_range_bounds

UPTIME_STALENESS_HOURS = 24


class FleetReportsError(Exception):
    pass


class VehicleNotFoundError(FleetReportsError):
    pass


async def _get_vehicle_or_404(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> Vehicle:
    result = await session.execute(
        select(Vehicle).where(Vehicle.id == vehicle_id, Vehicle.tenant_id == tenant_id)
    )
    vehicle = result.scalar_one_or_none()
    if vehicle is None:
        raise VehicleNotFoundError(vehicle_id)
    return vehicle


async def vehicle_lifetime_totals(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> dict:
    await _get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    stmt = select(
        func.count(Trip.id).label("trip_count"),
        func.sum(Trip.total).label("total_fares"),
        func.sum(Trip.psl).label("total_psl"),
        func.sum(Trip.tolls).label("total_tolls"),
        func.sum(Trip.distance_m).label("total_distance_m"),
    ).where(
        Trip.tenant_id == tenant_id,
        Trip.vehicle_id == vehicle_id,
        Trip.status == TRIP_STATUS_CLOSED,
    )
    row = (await session.execute(stmt)).one()

    total_distance_m = row.total_distance_m or 0
    return {
        "vehicle_id": vehicle_id,
        "trip_count": row.trip_count or 0,
        "total_fares": row.total_fares or Decimal("0.00"),
        "total_psl": row.total_psl or Decimal("0.00"),
        "total_tolls": row.total_tolls or Decimal("0.00"),
        "total_tips": None,
        "total_km": (Decimal(total_distance_m) / Decimal(1000)).quantize(Decimal("0.001")),
        "generated_at": datetime.now(UTC),
    }


async def vehicle_pilot_report(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, from_date: date, to_date: date
) -> dict:
    await _get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    if to_date < from_date:
        raise InvalidDateRangeError("to must not be earlier than from")
    start, end = date_range_bounds(from_date, to_date)

    trip_filters = [
        Trip.tenant_id == tenant_id,
        Trip.vehicle_id == vehicle_id,
        Trip.start_at >= start,
        Trip.start_at < end,
    ]

    trip_stmt = select(
        func.count(Trip.id).label("trip_count"),
        func.avg(Trip.variance_pct).label("avg_variance_pct"),
        func.sum(func.cast(Trip.flagged_for_review, Integer)).label("flagged_count"),
    ).where(*trip_filters)
    trip_row = (await session.execute(trip_stmt)).one()

    duress_filters = [
        DuressEvent.tenant_id == tenant_id,
        DuressEvent.vehicle_id == vehicle_id,
        DuressEvent.opened_at >= start,
        DuressEvent.opened_at < end,
    ]
    duress_total = (
        await session.execute(select(func.count(DuressEvent.id)).where(*duress_filters))
    ).scalar_one()

    device_result = await session.execute(
        select(Device)
        .where(Device.tenant_id == tenant_id, Device.vehicle_id == vehicle_id)
        .order_by(Device.last_seen_at.desc().nullslast())
    )
    most_recent_device = device_result.scalars().first()
    uptime_estimate_pct = None
    if most_recent_device is not None and most_recent_device.last_seen_at is not None:
        last_seen = most_recent_device.last_seen_at
        window_end = end
        if last_seen.tzinfo is None:
            window_end = window_end.replace(tzinfo=None)
        staleness = window_end - last_seen
        if staleness.total_seconds() <= UPTIME_STALENESS_HOURS * 3600:
            uptime_estimate_pct = Decimal("100")
        else:
            uptime_estimate_pct = Decimal("0")

    avg_variance = trip_row.avg_variance_pct
    return {
        "vehicle_id": vehicle_id,
        "from_date": from_date,
        "to_date": to_date,
        "trip_count": trip_row.trip_count or 0,
        "avg_fare_accuracy_variance_pct": (
            Decimal(str(avg_variance)).quantize(Decimal("0.01")) if avg_variance is not None else None
        ),
        "device_uptime_estimate_pct": uptime_estimate_pct,
        "duress_test_activation_count": None,
        "duress_event_count_total": duress_total or 0,
        "flagged_for_review_count": trip_row.flagged_count or 0,
        "generated_at": datetime.now(UTC),
    }
