"""Zones domain business logic: zone CRUD helpers, "plot into a zone"
(driver actively waiting in a specific zone, distinct from just being on
shift -- stored on the driver's current open `Shift`, see
`app.models.shift.Shift`'s DEVIATION note), and the live per-zone demand
stats aggregation backing `GET /v1/zones/stats` -- matching a screen on a
real competitor taxi meter (MTI).

Cross-domain reads (read-only, this domain owns only the `zones` table):
  - `app.models.shift.Shift` -- for "who is plotted where" (open shifts
    with `plotted_zone_id` set).
  - `app.services.live_ops.list_vehicles_live` -- reused verbatim for the
    live vehicle positions / on-trip status that `vacant_vehicles` /
    `busy_vehicles` are derived from, per the task instruction not to
    reinvent the live-position cache.
  - `app.models.jobs.Job` -- for `jobs_holding`.
  - `app.models.trips.Trip` -- for `bookings_last_hour` / `street_hails_last_hour`.

Documented simplifications in the stats aggregation (flagged per task
instructions, see `compute_zone_stats`'s docstring for the full list):
  - `jobs_holding` matches a Job to a zone by its `origin_lat`/`origin_lng`
    pickup point only (not by route or destination).
  - `vacant_vehicles`/`busy_vehicles` only count vehicles with a KNOWN
    position (live-published or from an open trip's last tick, per
    `app.services.live_ops._compose_vehicle_live`) that falls inside the
    zone's circle. A vehicle with no known position is not counted in either
    bucket -- there being no honest way to say which zone (if any) it's in.
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.jobs import JOB_STATUS_OFFERED, JOB_STATUS_QUEUED, Job
from app.models.shift import Shift
from app.models.trips import TRIP_TYPE_BOOKED, TRIP_TYPE_RANK_HAIL, Trip
from app.models.zones import Zone
from app.services.geofence import point_in_geofence
from app.services.live_ops import list_vehicles_live


class ZonesError(Exception):
    """Base class for zones-domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class ZoneNotFoundError(ZonesError):
    pass


class ZoneNumberTakenError(ZonesError):
    pass


class NoActiveShiftError(ZonesError):
    """Raised by plot/unplot when the calling driver has no currently-open
    shift (per `app.models.shift.Shift`'s DEVIATION note: a plot is always
    scoped to the driver's current open shift, there being nothing else for
    it to mean)."""


# ==================================================================================
# CRUD
# ==================================================================================


async def get_zone_or_404(session: AsyncSession, *, tenant_id: str, zone_id: str) -> Zone:
    result = await session.execute(select(Zone).where(Zone.id == zone_id, Zone.tenant_id == tenant_id))
    zone = result.scalar_one_or_none()
    if zone is None:
        raise ZoneNotFoundError(zone_id)
    return zone


async def list_zones(
    session: AsyncSession, *, tenant_id: str, skip: int = 0, limit: int = 50
) -> tuple[list[Zone], int]:
    result = await session.execute(
        select(Zone).where(Zone.tenant_id == tenant_id).order_by(Zone.number)
    )
    rows = result.scalars().all()
    total = len(rows)
    return list(rows[skip : skip + limit]), total


async def _number_taken(
    session: AsyncSession, *, tenant_id: str, number: str, exclude_zone_id: str | None = None
) -> bool:
    stmt = select(Zone.id).where(Zone.tenant_id == tenant_id, Zone.number == number)
    if exclude_zone_id is not None:
        stmt = stmt.where(Zone.id != exclude_zone_id)
    result = await session.execute(stmt)
    return result.scalar_one_or_none() is not None


async def create_zone(
    session: AsyncSession,
    *,
    tenant_id: str,
    name: str,
    number: str,
    center_lat: float,
    center_lng: float,
    radius_m: float,
) -> Zone:
    if await _number_taken(session, tenant_id=tenant_id, number=number):
        raise ZoneNumberTakenError(number)

    zone = Zone(
        tenant_id=tenant_id,
        name=name,
        number=number,
        center_lat=center_lat,
        center_lng=center_lng,
        radius_m=radius_m,
    )
    session.add(zone)
    await session.commit()
    await session.refresh(zone)
    return zone


async def update_zone(
    session: AsyncSession,
    zone: Zone,
    *,
    name: str,
    number: str,
    center_lat: float,
    center_lng: float,
    radius_m: float,
) -> Zone:
    """Full replace, matching the router's `PUT` semantics (per the task
    brief's literal `GET/POST/PUT/DELETE` endpoint list -- every other domain
    in this tree uses partial `PATCH` for updates, but zones deliberately
    follows the brief's `PUT` verb instead)."""
    if number != zone.number and await _number_taken(
        session, tenant_id=zone.tenant_id, number=number, exclude_zone_id=zone.id
    ):
        raise ZoneNumberTakenError(number)

    zone.name = name
    zone.number = number
    zone.center_lat = center_lat
    zone.center_lng = center_lng
    zone.radius_m = radius_m
    await session.commit()
    await session.refresh(zone)
    return zone


async def delete_zone(session: AsyncSession, zone: Zone) -> None:
    await session.delete(zone)
    await session.commit()


# ==================================================================================
# Plot / unplot
# ==================================================================================


async def _get_open_shift_for_driver(session: AsyncSession, *, tenant_id: str, driver_id: str) -> Shift:
    """The driver's current session, per the same "end_at IS NULL == active"
    convention `app.services.live_ops._open_shifts_by_driver` already relies
    on. Most-recently-started if more than one somehow exists."""
    result = await session.execute(
        select(Shift)
        .where(Shift.tenant_id == tenant_id, Shift.driver_id == driver_id, Shift.end_at.is_(None))
        .order_by(Shift.start_at.desc())
    )
    shift = result.scalars().first()
    if shift is None:
        raise NoActiveShiftError(driver_id)
    return shift


async def plot_into_zone(session: AsyncSession, *, tenant_id: str, driver_id: str, zone_id: str) -> Shift:
    """Marks the driver's current open shift as plotted into `zone_id`.
    Plotting into a new zone silently clears any previous plot -- there is
    only ever one `plotted_zone_id` on the one open shift a driver can have
    at a time, so "clears the old one" falls out of simply overwriting the
    column rather than needing separate bookkeeping."""
    zone = await get_zone_or_404(session, tenant_id=tenant_id, zone_id=zone_id)
    shift = await _get_open_shift_for_driver(session, tenant_id=tenant_id, driver_id=driver_id)
    shift.plotted_zone_id = zone.id
    shift.plotted_at = datetime.now(UTC)
    await session.commit()
    await session.refresh(shift)
    return shift


async def unplot(session: AsyncSession, *, tenant_id: str, driver_id: str) -> Shift:
    shift = await _get_open_shift_for_driver(session, tenant_id=tenant_id, driver_id=driver_id)
    shift.plotted_zone_id = None
    shift.plotted_at = None
    await session.commit()
    await session.refresh(shift)
    return shift


# ==================================================================================
# Live per-zone stats
# ==================================================================================


def _in_zone(lat: float | None, lng: float | None, zone: Zone) -> bool:
    if lat is None or lng is None:
        return False
    # point_in_geofence is duck-typed on .center_lat/.center_lng/.radius_m --
    # Zone carries the exact same attribute names as Geofence, so it's reused
    # verbatim rather than re-implementing the haversine check (per task
    # instruction).
    return point_in_geofence(lat, lng, zone)


async def compute_zone_stats(session: AsyncSession, *, tenant_id: str) -> list[dict[str, Any]]:
    """Per-zone live demand snapshot, matching a real screen on a competitor
    taxi meter (MTI). Documented simplifications (per task instructions --
    return 0 with a reason rather than fabricate, everywhere one is needed):

      - `jobs_holding` matches each open `Job` to a zone by its
        `origin_lat`/`origin_lng` pickup point falling inside the zone's
        circle. It does NOT consider the job's destination, nor any notion
        of "closest zone" for a job whose pickup falls outside every zone
        (that job simply doesn't count toward any zone's `jobs_holding`) --
        a job with a genuinely zone-less pickup has no honest zone to
        attribute it to.
      - `vacant_vehicles` / `busy_vehicles` only count vehicles with a KNOWN
        live position (from `app.services.live_ops.list_vehicles_live`,
        itself the existing fleet_broadcaster cache with an open-trip
        fallback) that falls inside the zone's circle. A vehicle with no
        known position (never published, no open trip) contributes to
        neither bucket, rather than being guessed into one.
      - `bookings_last_hour` / `street_hails_last_hour` count `Trip` rows of
        the matching `type`, scoped to the tenant, whose `start_at` falls in
        `[now - 1h, now]` AND whose `start_lat`/`start_lng` falls inside the
        zone's circle. Trips that started outside every zone don't count
        toward any zone's total.
    """
    zones_result = await session.execute(select(Zone).where(Zone.tenant_id == tenant_id).order_by(Zone.number))
    zones = zones_result.scalars().all()
    if not zones:
        return []

    now = datetime.now(UTC)
    one_hour_ago = now - timedelta(hours=1)

    # --- plotted_vehicles: open shifts currently plotted into a zone -------
    plotted_result = await session.execute(
        select(Shift).where(
            Shift.tenant_id == tenant_id, Shift.end_at.is_(None), Shift.plotted_zone_id.is_not(None)
        )
    )
    plotted_vehicle_ids_by_zone: dict[str, set[str]] = {}
    for shift in plotted_result.scalars():
        plotted_vehicle_ids_by_zone.setdefault(shift.plotted_zone_id, set()).add(shift.vehicle_id)

    # --- vacant_vehicles / busy_vehicles: reuse live_ops's existing rollup --
    vehicles_live, _total = await list_vehicles_live(session, tenant_id=tenant_id, skip=0, limit=1_000_000)

    # --- jobs_holding: open (not-yet-accepted) jobs, matched by pickup point --
    holding_jobs_result = await session.execute(
        select(Job).where(Job.tenant_id == tenant_id, Job.status.in_((JOB_STATUS_QUEUED, JOB_STATUS_OFFERED)))
    )
    holding_jobs = holding_jobs_result.scalars().all()

    # --- bookings_last_hour / street_hails_last_hour ------------------------
    recent_trips_result = await session.execute(
        select(Trip).where(
            Trip.tenant_id == tenant_id,
            Trip.start_at >= one_hour_ago,
            Trip.start_at <= now,
            Trip.type.in_((TRIP_TYPE_BOOKED, TRIP_TYPE_RANK_HAIL)),
        )
    )
    recent_trips = recent_trips_result.scalars().all()

    stats: list[dict[str, Any]] = []
    for zone in zones:
        vacant_vehicles = 0
        busy_vehicles = 0
        for vehicle in vehicles_live:
            if not _in_zone(vehicle["lat"], vehicle["lng"], zone):
                continue
            if vehicle["current_trip_id"] is not None or vehicle["live_status"] == "on_trip":
                busy_vehicles += 1
            else:
                vacant_vehicles += 1

        jobs_holding = sum(1 for job in holding_jobs if _in_zone(job.origin_lat, job.origin_lng, zone))

        bookings_last_hour = sum(
            1
            for trip in recent_trips
            if trip.type == TRIP_TYPE_BOOKED and _in_zone(trip.start_lat, trip.start_lng, zone)
        )
        street_hails_last_hour = sum(
            1
            for trip in recent_trips
            if trip.type == TRIP_TYPE_RANK_HAIL and _in_zone(trip.start_lat, trip.start_lng, zone)
        )

        stats.append(
            {
                "zone_id": zone.id,
                "zone_name": zone.name,
                "zone_number": zone.number,
                "plotted_vehicles": len(plotted_vehicle_ids_by_zone.get(zone.id, ())),
                "vacant_vehicles": vacant_vehicles,
                "busy_vehicles": busy_vehicles,
                "jobs_holding": jobs_holding,
                "bookings_last_hour": bookings_last_hour,
                "street_hails_last_hour": street_hails_last_hour,
            }
        )
    return stats