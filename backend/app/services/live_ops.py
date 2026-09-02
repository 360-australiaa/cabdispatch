"""Live Ops domain: read-only fleet/driver rollups joined from the fleet,
trips, shift, and user domains' tables, plus an in-process pub/sub position
broadcaster backing `WS /v1/fleet/live` and `POST /v1/fleet/positions`.

This domain owns NO tables of its own (per the domain brief: "No new
persisted table"). The models imported below -- `Vehicle`/`Device` (fleet
domain), `Trip` (trips domain), `Shift` (shift domain), `User` (user domain)
-- all belong to sibling domains and are used strictly READ-ONLY, with two
narrow, explicitly-scoped exceptions -- see `_persist_driver_position` and
`_persist_device_telemetry` below, both best-effort enrichments written on
top of the position-publish broadcast, never in place of it.

Position broadcasting is a pure in-process, in-memory pub/sub keyed by
tenant_id (the same "in-process pub/sub dict" pattern used by the sibling
`duress` domain's live GPS broadcaster, just keyed by tenant_id here instead
of by duress-event id -- see `app.schemas.duress.DuressGpsPoint`'s docstring
referencing `app.services.duress.GPSBroadcaster`). Nothing published here is
persisted to the database or shared across worker processes: a process
restart, or running more than one uvicorn worker, loses all live-position
state. That's an accepted tradeoff for this pass (flagged again in the domain
summary) -- `GET /v1/vehicles` degrades gracefully by falling back to each
vehicle's open trip's last known tick position (persisted, from the trips
domain) when nothing has been published live.

`battery`/`network` (2026-08-28, real-time telemetry pass): `PositionPublishRequest`
gained these two optional fields so a device's existing periodic position
heartbeat can carry tablet battery/connectivity in the SAME call, rather than
needing a second call to `POST /v1/fleet/devices/{id}/heartbeat` (which this
codebase's own Android side has only ever called reactively, when the driver
happens to open Settings -- see `android/HANDOFF.md`'s standing "locate only
answered when S6 opens" gap). When present, `publish_position` both includes
them in the live broadcast/cache (freshest, but ephemeral) AND best-effort
persists them onto the paired `Device` row (durable, survives a broadcaster
restart, also visible via `GET /v1/fleet/devices`) -- see
`_persist_device_telemetry`. `_compose_vehicle_live` prefers the live cache
value when present, falling back to the Device row's last-persisted value
otherwise, so `GET /v1/vehicles` always shows the best information available
regardless of which path most recently reported it.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import Device, Vehicle
from app.models.jobs import DriverAvailability
from app.models.shift import Shift
from app.models.trips import TRIP_STATUS_OPEN, Trip
from app.models.user import ROLE_DRIVER, User

logger = logging.getLogger("cab_dispatch.live_ops")

# Fallback live_status used when a vehicle has never had a position published,
# has no open trip, and its fleet-domain status is "active" (i.e. nothing else
# to report -- it's simply not known to be doing anything right now).
DEFAULT_LIVE_STATUS = "offline"


class LiveOpsError(Exception):
    """Base class for live-ops domain errors; the router translates each
    subclass to the appropriate HTTP status."""


class VehicleNotFoundError(LiveOpsError):
    pass


class DriverNotFoundError(LiveOpsError):
    pass


# ==================================================================================
# In-process pub/sub, keyed by tenant_id
# ==================================================================================


class _FleetBroadcaster:
    """In-memory fan-out of position updates to every `WS /v1/fleet/live`
    listener currently connected for a tenant, plus a last-known-position
    cache used to enrich `GET /v1/vehicles` even when nobody is connected.

    Not Redis-backed (unlike `app.core.security`'s JWT revocation store) --
    position broadcast is inherently best-effort/ephemeral for this pass, and
    every consumer (the live vehicle list, the WS feed) tolerates a gap. A
    later pass could move the fan-out to Redis pub/sub for multi-worker
    deployments without changing this class's public interface.
    """

    def __init__(self) -> None:
        self._subscribers: dict[str, set[asyncio.Queue]] = {}
        self._latest: dict[str, dict[str, dict[str, Any]]] = {}  # tenant_id -> vehicle_id -> position
        self._lock = asyncio.Lock()

    async def subscribe(self, tenant_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=100)
        async with self._lock:
            self._subscribers.setdefault(tenant_id, set()).add(queue)
        return queue

    async def unsubscribe(self, tenant_id: str, queue: asyncio.Queue) -> None:
        async with self._lock:
            subs = self._subscribers.get(tenant_id)
            if subs is not None:
                subs.discard(queue)
                if not subs:
                    self._subscribers.pop(tenant_id, None)

    async def publish(self, tenant_id: str, position: dict[str, Any]) -> int:
        """Updates the last-known-position cache and fans the update out to
        every currently-connected subscriber for this tenant. Returns the
        number of subscribers the update was delivered to (0 is normal -- most
        publishes happen with no dispatcher dashboard currently watching)."""
        async with self._lock:
            self._latest.setdefault(tenant_id, {})[position["vehicle_id"]] = position
            subs = list(self._subscribers.get(tenant_id, ()))

        delivered = 0
        for queue in subs:
            try:
                queue.put_nowait(position)
                delivered += 1
            except asyncio.QueueFull:
                # A slow/stalled consumer must not block or crash the publisher
                # (a device's tick handler). Drop the update for that one
                # listener; it will simply get the next one.
                pass
        return delivered

    def get_latest(self, tenant_id: str, vehicle_id: str) -> dict[str, Any] | None:
        return self._latest.get(tenant_id, {}).get(vehicle_id)

    def get_all_latest(self, tenant_id: str) -> dict[str, dict[str, Any]]:
        return dict(self._latest.get(tenant_id, {}))


# Process-wide singleton -- every request/connection shares this one instance
# for the lifetime of the process (same pattern as `app.core.security`'s
# module-level `revocation_store`).
fleet_broadcaster = _FleetBroadcaster()


def build_position(
    *,
    vehicle_id: str,
    lat: float,
    lng: float,
    status: str,
    battery: int | None = None,
    network: str | None = None,
) -> dict[str, Any]:
    return {
        "vehicle_id": vehicle_id,
        "lat": lat,
        "lng": lng,
        "status": status,
        "battery": battery,
        "network": network,
        "updated_at": datetime.now(UTC).isoformat(),
    }


async def _persist_driver_position(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, lat: float, lng: float
) -> None:
    """Best-effort enrichment for the jobs domain's proximity-ranked offer
    matching (see `app.services.jobs.create_job_and_broadcast`). Resolves
    vehicle_id -> the driver currently on an open `Shift` in that vehicle
    (`Shift.end_at IS NULL`, the same "open shift IS the driver's current
    session" convention already used by `_open_shifts_by_driver` /
    `_open_trips_by_vehicle` above), then, if that driver already has a
    `DriverAvailability` row (jobs domain; created only via
    `POST /v1/jobs/availability`, never here), updates its
    last_lat/last_lng/last_position_at.

    Silently does nothing if there's no on-shift driver for this vehicle, or
    no existing `DriverAvailability` row for them -- this only enriches an
    existing row, it never creates one. Called from `publish_position` inside
    a broad try/except: a failure in here must never raise past the caller,
    since this is best-effort enrichment layered on top of the position
    broadcast, not a hard requirement of it."""
    shift_result = await session.execute(
        select(Shift.driver_id).where(
            Shift.tenant_id == tenant_id,
            Shift.vehicle_id == vehicle_id,
            Shift.end_at.is_(None),
        )
    )
    driver_id = shift_result.scalars().first()
    if driver_id is None:
        return

    availability_result = await session.execute(
        select(DriverAvailability).where(
            DriverAvailability.tenant_id == tenant_id,
            DriverAvailability.driver_id == driver_id,
        )
    )
    row = availability_result.scalar_one_or_none()
    if row is None:
        return

    row.last_lat = lat
    row.last_lng = lng
    row.last_position_at = datetime.now(UTC)
    await session.commit()


async def _persist_device_telemetry(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, battery: int | None, network: str | None
) -> None:
    """Best-effort enrichment: writes `battery`/`network` (and refreshes
    `last_seen_at`, since this call IS evidence the device is alive) onto the
    `Device` row currently paired to `vehicle_id`, when the position-publish
    payload carried either field. See module docstring's "battery/network"
    section for why -- this lets an existing periodic position heartbeat also
    keep `Device.battery`/`Device.network` fresh, not just
    `POST /v1/fleet/devices/{id}/heartbeat` (which this app's Android side has
    only ever called reactively, per `android/HANDOFF.md`).

    No-ops (does not even query) when both `battery` and `network` are `None`
    -- most publishers won't send them yet, and a plain lat/lng/status publish
    must not touch `last_seen_at`/overwrite a real value with nothing merely
    because this call fired. Silently does nothing if no `Device` is currently
    paired to this vehicle. Same broad-try/except-in-the-caller contract as
    `_persist_driver_position` -- a failure here must never raise past
    `publish_position`."""
    if battery is None and network is None:
        return

    result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.vehicle_id == vehicle_id)
    )
    device = result.scalars().first()
    if device is None:
        return

    if battery is not None:
        device.battery = battery
    if network is not None:
        device.network = network
    device.last_seen_at = datetime.now(UTC)
    await session.commit()


async def publish_position(
    session: AsyncSession,
    *,
    tenant_id: str,
    vehicle_id: str,
    lat: float,
    lng: float,
    status: str,
    battery: int | None = None,
    network: str | None = None,
) -> dict[str, Any]:
    """Validates the vehicle belongs to the caller's tenant, then publishes
    the position. Returns the broadcast position dict plus subscriber_count.

    Also best-effort persists lat/lng onto the currently-assigned driver's
    `DriverAvailability` row (jobs domain, see `_persist_driver_position`) and
    `battery`/`network` (if given) onto the paired `Device` row (fleet
    domain, see `_persist_device_telemetry`) -- both additive enrichment
    layered on top of the existing broadcast above, never a replacement for
    it. A failure in either is logged and swallowed, never raised, so neither
    can break or slow down the broadcast this function already completed."""
    await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    position = build_position(
        vehicle_id=vehicle_id, lat=lat, lng=lng, status=status, battery=battery, network=network
    )
    delivered = await fleet_broadcaster.publish(tenant_id, position)

    try:
        await _persist_driver_position(session, tenant_id=tenant_id, vehicle_id=vehicle_id, lat=lat, lng=lng)
    except Exception:
        logger.warning(
            "Live ops: failed to persist last-known position for vehicle %s (tenant %s), continuing",
            vehicle_id,
            tenant_id,
            exc_info=True,
        )

    try:
        await _persist_device_telemetry(
            session, tenant_id=tenant_id, vehicle_id=vehicle_id, battery=battery, network=network
        )
    except Exception:
        logger.warning(
            "Live ops: failed to persist device telemetry for vehicle %s (tenant %s), continuing",
            vehicle_id,
            tenant_id,
            exc_info=True,
        )

    return {**position, "subscriber_count": delivered}


# ==================================================================================
# Vehicles: read-only rollup joining fleet (Vehicle, Device) + trips (Trip) +
# the live-position cache
# ==================================================================================


async def get_vehicle_or_404(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> Vehicle:
    result = await session.execute(
        select(Vehicle).where(Vehicle.id == vehicle_id, Vehicle.tenant_id == tenant_id)
    )
    vehicle = result.scalar_one_or_none()
    if vehicle is None:
        raise VehicleNotFoundError(vehicle_id)
    return vehicle


async def _devices_by_vehicle(session: AsyncSession, *, tenant_id: str) -> dict[str, Device]:
    result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.vehicle_id.is_not(None))
    )
    by_vehicle: dict[str, Device] = {}
    for device in result.scalars():
        by_vehicle[device.vehicle_id] = device
    return by_vehicle


async def _open_trips_by_vehicle(session: AsyncSession, *, tenant_id: str) -> dict[str, Trip]:
    """One open trip per vehicle_id. There should never be more than one open
    trip for the same vehicle at a time; if data is ever inconsistent, this
    keeps whichever one started most recently."""
    result = await session.execute(
        select(Trip)
        .where(Trip.tenant_id == tenant_id, Trip.status == TRIP_STATUS_OPEN)
        .order_by(Trip.start_at.desc())
    )
    by_vehicle: dict[str, Trip] = {}
    for trip in result.scalars():
        by_vehicle.setdefault(trip.vehicle_id, trip)
    return by_vehicle


async def _open_shifts_by_vehicle(session: AsyncSession, *, tenant_id: str) -> dict[str, Shift]:
    """One open shift per vehicle_id -- "who currently has this vehicle
    checked out", the answer to a real operational question (one vehicle
    often runs back-to-back shifts across two+ drivers, e.g. a 12h/12h
    double-shift) that nothing in this codebase surfaced before this pass.
    Same "Shift.end_at IS NULL == currently active" convention as
    `_open_shifts_by_driver` above; see `app.services.shift.start_shift`'s
    own docstring for the guarantee that at most one open shift ever exists
    per vehicle at a time."""
    result = await session.execute(
        select(Shift)
        .where(Shift.tenant_id == tenant_id, Shift.end_at.is_(None))
        .order_by(Shift.start_at.desc())
    )
    by_vehicle: dict[str, Shift] = {}
    for shift in result.scalars():
        by_vehicle.setdefault(shift.vehicle_id, shift)
    return by_vehicle


async def _driver_names_by_id(
    session: AsyncSession, *, tenant_id: str, driver_ids: set[str]
) -> dict[str, str]:
    """Batch name lookup for a set of driver ids -- avoids an N+1 query when
    composing a page of vehicles, each potentially needing its current
    driver's display name."""
    if not driver_ids:
        return {}
    result = await session.execute(
        select(User.id, User.name).where(User.tenant_id == tenant_id, User.id.in_(driver_ids))
    )
    return {row.id: row.name for row in result}


def _compose_vehicle_live(
    vehicle: Vehicle,
    *,
    device: Device | None,
    open_trip: Trip | None,
    live_position: dict[str, Any] | None,
    current_shift: Shift | None = None,
    current_driver_name: str | None = None,
) -> dict[str, Any]:
    if live_position is not None:
        lat, lng = live_position["lat"], live_position["lng"]
        live_status = live_position["status"]
        position_updated_at = live_position["updated_at"]
        position_source = "live"
    elif open_trip is not None and open_trip.last_lat is not None and open_trip.last_lng is not None:
        lat, lng = open_trip.last_lat, open_trip.last_lng
        live_status = "on_trip"
        position_updated_at = open_trip.last_ts
        position_source = "trip"
    else:
        lat = lng = None
        position_updated_at = None
        position_source = "none"
        if open_trip is not None:
            live_status = "on_trip"
        elif vehicle.status != "active":
            live_status = vehicle.status
        else:
            live_status = DEFAULT_LIVE_STATUS

    # Prefer whatever the live position publish carried (freshest -- an
    # ephemeral in-memory value, gone after a process restart) over the
    # Device row's own last-persisted value (durable, but can lag behind by
    # up to one heartbeat interval). Either can be None; either can also have
    # been reported without the other (e.g. a plain lat/lng/status publish
    # carries neither).
    battery = (live_position or {}).get("battery")
    if battery is None and device is not None:
        battery = device.battery
    network = (live_position or {}).get("network")
    if network is None and device is not None:
        network = device.network

    return {
        "id": vehicle.id,
        "tenant_id": vehicle.tenant_id,
        "rego": vehicle.rego,
        "vehicle_class": vehicle.vehicle_class,
        "vehicle_status": vehicle.status,
        "device_id": device.id if device else None,
        "device_last_seen_at": device.last_seen_at if device else None,
        "battery": battery,
        "network": network,
        "lat": lat,
        "lng": lng,
        "live_status": live_status,
        "position_updated_at": position_updated_at,
        "position_source": position_source,
        "current_trip_id": open_trip.id if open_trip else None,
        "current_driver_id": current_shift.driver_id if current_shift else None,
        "current_driver_name": current_driver_name if current_shift else None,
        "current_shift_id": current_shift.id if current_shift else None,
        "current_shift_start_at": current_shift.start_at if current_shift else None,
    }


async def list_vehicles_live(
    session: AsyncSession,
    *,
    tenant_id: str,
    status_filter: str | None = None,
    vehicle_class: str | None = None,
    rego: str | None = None,
    live_status: str | None = None,
    skip: int = 0,
    limit: int = 20,
) -> tuple[list[dict[str, Any]], int]:
    """Filters that map directly onto Vehicle columns (status, vehicle_class,
    rego) are pushed down to SQL. `live_status` is a derived field (joined
    from the live-position cache / open trips) so it's applied in Python after
    composing each row -- fleets are small enough per-tenant that this is fine,
    and it keeps the join logic in one place (`_compose_vehicle_live`) instead
    of duplicated as a second SQL-side implementation."""
    stmt = select(Vehicle).where(Vehicle.tenant_id == tenant_id)
    if status_filter is not None:
        stmt = stmt.where(Vehicle.status == status_filter)
    if vehicle_class is not None:
        stmt = stmt.where(Vehicle.vehicle_class == vehicle_class)
    if rego is not None:
        stmt = stmt.where(Vehicle.rego.like(f"%{rego.upper()}%"))

    result = await session.execute(stmt.order_by(Vehicle.rego))
    vehicles = result.scalars().all()

    devices = await _devices_by_vehicle(session, tenant_id=tenant_id)
    open_trips = await _open_trips_by_vehicle(session, tenant_id=tenant_id)
    open_shifts = await _open_shifts_by_vehicle(session, tenant_id=tenant_id)
    driver_names = await _driver_names_by_id(
        session, tenant_id=tenant_id, driver_ids={s.driver_id for s in open_shifts.values()}
    )
    live_cache = fleet_broadcaster.get_all_latest(tenant_id)

    composed = [
        _compose_vehicle_live(
            v,
            device=devices.get(v.id),
            open_trip=open_trips.get(v.id),
            live_position=live_cache.get(v.id),
            current_shift=open_shifts.get(v.id),
            current_driver_name=driver_names.get(open_shifts[v.id].driver_id) if v.id in open_shifts else None,
        )
        for v in vehicles
    ]

    if live_status is not None:
        composed = [c for c in composed if c["live_status"] == live_status]

    total = len(composed)
    return composed[skip : skip + limit], total


async def get_vehicle_live(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> dict[str, Any]:
    vehicle = await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    devices = await _devices_by_vehicle(session, tenant_id=tenant_id)
    open_trips = await _open_trips_by_vehicle(session, tenant_id=tenant_id)
    open_shifts = await _open_shifts_by_vehicle(session, tenant_id=tenant_id)
    current_shift = open_shifts.get(vehicle_id)
    driver_names = await _driver_names_by_id(
        session, tenant_id=tenant_id, driver_ids={current_shift.driver_id} if current_shift else set()
    )
    live_position = fleet_broadcaster.get_latest(tenant_id, vehicle_id)
    return _compose_vehicle_live(
        vehicle,
        device=devices.get(vehicle_id),
        open_trip=open_trips.get(vehicle_id),
        live_position=live_position,
        current_shift=current_shift,
        current_driver_name=driver_names.get(current_shift.driver_id) if current_shift else None,
    )


# ==================================================================================
# Drivers: read-only rollup joining user (User) + shift (Shift) + trips (Trip)
# ==================================================================================


async def get_driver_or_404(session: AsyncSession, *, tenant_id: str, driver_id: str) -> User:
    result = await session.execute(
        select(User).where(User.id == driver_id, User.tenant_id == tenant_id, User.role == ROLE_DRIVER)
    )
    driver = result.scalar_one_or_none()
    if driver is None:
        raise DriverNotFoundError(driver_id)
    return driver


async def _open_shifts_by_driver(session: AsyncSession, *, tenant_id: str) -> dict[str, Shift]:
    """A shift with end_at IS NULL is, by the shift domain's own convention
    (see `app.models.shift.Shift`), the driver's currently-active shift."""
    result = await session.execute(
        select(Shift)
        .where(Shift.tenant_id == tenant_id, Shift.end_at.is_(None))
        .order_by(Shift.start_at.desc())
    )
    by_driver: dict[str, Shift] = {}
    for shift in result.scalars():
        by_driver.setdefault(shift.driver_id, shift)
    return by_driver


async def _open_trips_by_driver(session: AsyncSession, *, tenant_id: str) -> dict[str, Trip]:
    result = await session.execute(
        select(Trip)
        .where(Trip.tenant_id == tenant_id, Trip.status == TRIP_STATUS_OPEN)
        .order_by(Trip.start_at.desc())
    )
    by_driver: dict[str, Trip] = {}
    for trip in result.scalars():
        by_driver.setdefault(trip.driver_id, trip)
    return by_driver


def _compose_driver_live(user: User, *, shift: Shift | None, trip: Trip | None) -> dict[str, Any]:
    return {
        "id": user.id,
        "tenant_id": user.tenant_id,
        "name": user.name,
        "phone": user.phone,
        "user_status": user.status,
        "on_shift": shift is not None,
        "shift_id": shift.id if shift else None,
        "vehicle_id": shift.vehicle_id if shift else None,
        "shift_start_at": shift.start_at if shift else None,
        "current_trip_id": trip.id if trip else None,
    }


async def list_drivers_live(
    session: AsyncSession,
    *,
    tenant_id: str,
    status_filter: str | None = None,
    on_shift: bool | None = None,
    skip: int = 0,
    limit: int = 20,
) -> tuple[list[dict[str, Any]], int]:
    stmt = select(User).where(User.tenant_id == tenant_id, User.role == ROLE_DRIVER)
    if status_filter is not None:
        stmt = stmt.where(User.status == status_filter)

    result = await session.execute(stmt.order_by(User.name))
    drivers = result.scalars().all()

    shifts = await _open_shifts_by_driver(session, tenant_id=tenant_id)
    trips = await _open_trips_by_driver(session, tenant_id=tenant_id)

    composed = [_compose_driver_live(d, shift=shifts.get(d.id), trip=trips.get(d.id)) for d in drivers]

    if on_shift is not None:
        composed = [c for c in composed if c["on_shift"] == on_shift]

    total = len(composed)
    return composed[skip : skip + limit], total


async def get_driver_live(session: AsyncSession, *, tenant_id: str, driver_id: str) -> dict[str, Any]:
    driver = await get_driver_or_404(session, tenant_id=tenant_id, driver_id=driver_id)
    shifts = await _open_shifts_by_driver(session, tenant_id=tenant_id)
    trips = await _open_trips_by_driver(session, tenant_id=tenant_id)
    return _compose_driver_live(driver, shift=shifts.get(driver_id), trip=trips.get(driver_id))