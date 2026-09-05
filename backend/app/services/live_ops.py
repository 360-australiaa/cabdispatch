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

`speed_kmh`/`heading` (2026-09-05, real-time telemetry pass): same "ride
along on the existing position heartbeat" idea as battery/network above, but
purely live-cache-only -- see `publish_position`'s docstring for why there is
no paired-`Device` persistence for these two.

`live_status` priority-order fix (2026-09-05): before this pass,
`_compose_vehicle_live` trusted `live_position["status"]` unconditionally
whenever ANY position had ever been published for a vehicle, even one with a
currently-open trip. Since Android heartbeats constantly while on shift and
has, in practice, only ever sent the literal placeholder `"unknown"` for this
field, every on-shift vehicle showed `"unknown"` instead of `"on_trip"` --
directly contradicting `VehicleLiveRead.live_status`'s own docstring, which
has always promised `'on_trip'` as a fallback. `_compose_vehicle_live` now
checks `open_trip` FIRST, unconditionally, before ever looking at
`live_position["status"]` -- see its docstring for the full four-step
priority order, including the new `DriverAvailability`-backed `available`/
`break` step (`_driver_availability_by_id`) that fills the gap between "no
live status" and the fleet-domain fallback.

Durable position history + driving signals (2026-09-05, dispatcher-replay
pass): the paragraphs above describe why position publishing has always been
"in-process pub/sub only... nothing published here is persisted to the
database" -- that remains true of the `_FleetBroadcaster` cache itself, but
this pass adds a THIRD, independent best-effort side-write next to
`_persist_driver_position`/`_persist_device_telemetry`:
`_persist_position_history` appends one row to the new
`app.models.fleet.VehiclePositionHistory` table on every publish (see that
model's own docstring for the full rationale) so a dispatcher can scrub back
through a vehicle's last few hours of real positions -- something the
in-memory cache alone can never answer, since it only ever holds the single
latest position per vehicle and is wiped by a process restart. Reads go
through `get_position_history` below, exposed at
`GET /v1/vehicles/{vehicle_id}/position-history`, which also computes two
simple, HONESTLY-LABELED informational telematics signals (harsh-braking/
rapid-acceleration event counts) from consecutive recorded points -- see
`HARSH_EVENT_THRESHOLD_KMH_PER_S`'s doc comment for why these are explicitly
NOT presented as a certified/legal safety score. There is still no
scheduler/background-job infrastructure anywhere in this codebase (confirmed
zero APScheduler/cron/periodic-task code exists); retention is enforced the
same LAZY on-write way `app.services.jobs.expire_stale_offers` already
enforces offer expiry -- see `POSITION_HISTORY_RETENTION_HOURS` and
`_persist_position_history` below.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import Device, Vehicle, VehiclePositionHistory
from app.models.jobs import DriverAvailability
from app.models.shift import Shift
from app.models.trips import TRIP_STATUS_OPEN, Trip
from app.models.user import ROLE_DRIVER, User

logger = logging.getLogger("cab_dispatch.live_ops")

# Fallback live_status used when a vehicle has never had a position published,
# has no open trip, and its fleet-domain status is "active" (i.e. nothing else
# to report -- it's simply not known to be doing anything right now).
DEFAULT_LIVE_STATUS = "offline"

# How long a `VehiclePositionHistory` row is kept before `_persist_position_history`
# lazily prunes it on the next publish for that same vehicle. This is a
# TECHNICAL DEFAULT (3 days), not a decided data-retention policy -- picked
# because it comfortably covers "a dispatcher scrubs back through the last
# few hours" (this pass's actual brief) with headroom, nothing more. Flag
# this to the business owner before this table handles real driver location
# data at scale -- same open-item framing this codebase already applies to
# the duress-recordings retention question (docs/DURESS_DEVICE_INTEGRATION.md
# sec 8: "a default, not a decided policy").
POSITION_HISTORY_RETENTION_HOURS = 72

# Threshold used by `get_position_history` to flag a harsh-braking or
# rapid-acceleration event between two consecutive recorded points. 8 km/h
# per second is a commonly-cited fleet-telematics rule-of-thumb sensitivity
# (comparable to the deceleration a firm, controlled stop produces), NOT a
# certified/legal safety standard, and nothing in this codebase treats it as
# one -- `PositionHistoryPoint`'s exposed `threshold_kmh_per_s` field exists
# specifically so a consumer of this data always sees the exact number that
# produced the counts, rather than a black-box score. Tune this constant if
# the business wants a different sensitivity; it is a literal, easily
# discoverable module constant for exactly that reason.
HARSH_EVENT_THRESHOLD_KMH_PER_S = 8.0

# A gap between two consecutive recorded points wider than this is treated as
# "not really consecutive" for driving-signal purposes (e.g. a device that
# went offline for an hour and came back) -- a speed delta across a gap that
# large says nothing real about acceleration/braking and must not be counted
# as either. Same "explicit sanity ceiling, not a decided policy" framing as
# PositionPublishRequest.speed_kmh's own 300 km/h ceiling.
MAX_CONSECUTIVE_GAP_SECONDS = 60


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
    speed_kmh: float | None = None,
    heading: float | None = None,
) -> dict[str, Any]:
    return {
        "vehicle_id": vehicle_id,
        "lat": lat,
        "lng": lng,
        "status": status,
        "battery": battery,
        "network": network,
        "speed_kmh": speed_kmh,
        "heading": heading,
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


async def _persist_position_history(
    session: AsyncSession,
    *,
    tenant_id: str,
    vehicle_id: str,
    lat: float,
    lng: float,
    status: str,
    speed_kmh: float | None,
    heading: float | None,
) -> None:
    """Best-effort durable side-write: appends one row to
    `app.models.fleet.VehiclePositionHistory` for every position published,
    then lazily prunes this SAME vehicle's own rows older than
    `POSITION_HISTORY_RETENTION_HOURS` -- see that constant's doc comment for
    why the window is a technical default, not policy. See
    `VehiclePositionHistory`'s own docstring for why this table exists
    alongside (not instead of) the `_FleetBroadcaster` in-memory cache.

    The prune is scoped to `vehicle_id` (`WHERE vehicle_id = ... AND
    recorded_at < cutoff`), never a full-table scan -- cheap enough to run on
    every single write given `recorded_at`/`vehicle_id` are both indexed
    columns, and this is deliberately the SAME "lazy expiry performed inline
    on the next write" pattern this codebase already established for offer
    expiry (see `app.services.jobs.expire_stale_offers`'s own module-section
    comment: no scheduler/cron infrastructure exists anywhere in this
    backend, so a real background sweep is not an option without introducing
    a new kind of infra this pass has no mandate to add).

    Same broad-try/except-in-the-caller contract as `_persist_driver_position`/
    `_persist_device_telemetry` above -- a failure here (this insert, or the
    prune) must never raise past `publish_position`, since this is best-effort
    enrichment layered on top of the position broadcast, not a hard
    requirement of it."""
    row = VehiclePositionHistory(
        tenant_id=tenant_id,
        vehicle_id=vehicle_id,
        lat=lat,
        lng=lng,
        speed_kmh=speed_kmh,
        heading=heading,
        status=status,
        recorded_at=datetime.now(UTC),
    )
    session.add(row)

    cutoff = datetime.now(UTC) - timedelta(hours=POSITION_HISTORY_RETENTION_HOURS)
    await session.execute(
        delete(VehiclePositionHistory).where(
            VehiclePositionHistory.tenant_id == tenant_id,
            VehiclePositionHistory.vehicle_id == vehicle_id,
            VehiclePositionHistory.recorded_at < cutoff,
        )
    )
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
    speed_kmh: float | None = None,
    heading: float | None = None,
) -> dict[str, Any]:
    """Validates the vehicle belongs to the caller's tenant, then publishes
    the position. Returns the broadcast position dict plus subscriber_count.

    Also best-effort persists lat/lng onto the currently-assigned driver's
    `DriverAvailability` row (jobs domain, see `_persist_driver_position`) and
    `battery`/`network` (if given) onto the paired `Device` row (fleet
    domain, see `_persist_device_telemetry`) -- both additive enrichment
    layered on top of the existing broadcast above, never a replacement for
    it. A failure in either is logged and swallowed, never raised, so neither
    can break or slow down the broadcast this function already completed.

    `speed_kmh`/`heading` (2026-09-05, real-time telemetry pass) are, unlike
    battery/network, live-cache-only for the PAIRED-DEVICE enrichment above
    -- they describe THIS fix, not a slowly-changing property of the tablet,
    so there is no paired-Device persistence for them. They ARE, however,
    included in the durable `VehiclePositionHistory` row written by
    `_persist_position_history` (dispatcher-replay pass, same date) -- that
    table records a timeline of individual fixes, where a per-fix speed/
    heading is exactly the point, unlike the paired Device row which
    represents one slowly-changing "this tablet" snapshot."""
    await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    position = build_position(
        vehicle_id=vehicle_id,
        lat=lat,
        lng=lng,
        status=status,
        battery=battery,
        network=network,
        speed_kmh=speed_kmh,
        heading=heading,
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

    try:
        await _persist_position_history(
            session,
            tenant_id=tenant_id,
            vehicle_id=vehicle_id,
            lat=lat,
            lng=lng,
            status=status,
            speed_kmh=speed_kmh,
            heading=heading,
        )
    except Exception:
        logger.warning(
            "Live ops: failed to persist position history for vehicle %s (tenant %s), continuing",
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


async def _driver_availability_by_id(
    session: AsyncSession, *, tenant_id: str, driver_ids: set[str]
) -> dict[str, bool]:
    """Batch lookup of `DriverAvailability.is_available` for a set of driver
    ids -- sibling of `_driver_names_by_id` above (same batch-to-avoid-N+1
    rationale), added for the `live_status` priority-order fix (see
    `_compose_vehicle_live`'s docstring): when a vehicle has no real live-
    published status and no open trip, the on-shift driver's own dispatch-
    availability toggle (jobs domain, `POST /v1/jobs/availability`) is a
    better-than-nothing real signal for 'available' vs 'break', still ahead
    of the last-resort `vehicle.status` fallback.

    A driver with no `DriverAvailability` row yet (never toggled it) is
    simply absent from the returned dict -- `_compose_vehicle_live` treats
    that the same as `is_driver_available=None`, i.e. "unknown", not
    "unavailable"."""
    if not driver_ids:
        return {}
    result = await session.execute(
        select(DriverAvailability.driver_id, DriverAvailability.is_available).where(
            DriverAvailability.tenant_id == tenant_id, DriverAvailability.driver_id.in_(driver_ids)
        )
    )
    return {row.driver_id: row.is_available for row in result}


def _compose_vehicle_live(
    vehicle: Vehicle,
    *,
    device: Device | None,
    open_trip: Trip | None,
    live_position: dict[str, Any] | None,
    current_shift: Shift | None = None,
    current_driver_name: str | None = None,
    is_driver_available: bool | None = None,
) -> dict[str, Any]:
    """Composes one `GET /v1/vehicles` row. `live_status` priority order
    (2026-09-05 fix -- see `VehicleLiveRead.live_status`'s own docstring for
    the full contract this implements):

        1. `open_trip` is not None -> 'on_trip', unconditionally. This is a
           real signal from the trips domain and must win over EVERYTHING
           else, including a live-published position -- a device's own
           `status` field is client-set free text (see
           `PositionPublishRequest.status`'s "e.g." examples, not an enum)
           and, before this fix, was trusted unconditionally whenever any
           position had ever been published. Since Android heartbeats
           constantly while on shift (see module docstring's battery/network
           section) and has, in practice, only ever sent the literal
           placeholder "unknown" for this field, EVERY on-shift vehicle
           showed "unknown" even while genuinely mid-trip -- directly
           contradicting this same field's own docstring, which has always
           promised 'on_trip' as the fallback. Real bug, not a hypothetical.
        2. else, `live_position["status"]` if it's a real value (not None,
           not the literal placeholder "unknown") -- once (1) can no longer
           mask a genuinely-useful client-reported status (e.g. "available",
           "break"), it's still the freshest signal available.
        3. else, `is_driver_available` (the on-shift driver's
           `DriverAvailability.is_available` toggle, jobs domain) -> the
           `available`/`break` values `VehicleLiveRead.live_status`'s
           dashboard-side type already anticipated
           (`dashboard/src/pages/live-map/types.ts`) but nothing ever
           produced, until now. None (no shift, or shift'd driver never
           toggled availability) falls through to (4).
        4. else `vehicle.status` (fleet domain) as the last-resort fallback,
           unchanged from before this fix.
    """
    if open_trip is not None:
        live_status = "on_trip"
    elif live_position is not None and live_position["status"] not in (None, "unknown"):
        live_status = live_position["status"]
    elif is_driver_available is not None:
        live_status = "available" if is_driver_available else "break"
    elif vehicle.status != "active":
        live_status = vehicle.status
    else:
        live_status = DEFAULT_LIVE_STATUS

    if live_position is not None:
        lat, lng = live_position["lat"], live_position["lng"]
        position_updated_at = live_position["updated_at"]
        position_source = "live"
    elif open_trip is not None and open_trip.last_lat is not None and open_trip.last_lng is not None:
        lat, lng = open_trip.last_lat, open_trip.last_lng
        position_updated_at = open_trip.last_ts
        position_source = "trip"
    else:
        lat = lng = None
        position_updated_at = None
        position_source = "none"

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

    # speed_kmh/heading (2026-09-05): live-cache-only, no Device-row fallback
    # -- see publish_position's docstring for why. None (never reported, or
    # no live position at all) is the honest answer, not a fabricated 0.
    speed_kmh = (live_position or {}).get("speed_kmh")
    heading = (live_position or {}).get("heading")

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
        "speed_kmh": speed_kmh,
        "heading": heading,
        "lat": lat,
        "lng": lng,
        "live_status": live_status,
        "position_updated_at": position_updated_at,
        "position_source": position_source,
        "current_trip_id": open_trip.id if open_trip else None,
        # Driver-picked mid-trip destination (Live Map route-line pass), see
        # app.models.trips.Trip.planned_dest_lat/lng's doc comment (module
        # docstring deviation #7). None when there's no open trip at all, or
        # when there is one but its driver hasn't picked a destination yet
        # -- same "None if no open trip" pattern as current_trip_id above,
        # never a fabricated 0/0.
        "planned_dest_lat": open_trip.planned_dest_lat if open_trip else None,
        "planned_dest_lng": open_trip.planned_dest_lng if open_trip else None,
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
    driver_ids = {s.driver_id for s in open_shifts.values()}
    driver_names = await _driver_names_by_id(session, tenant_id=tenant_id, driver_ids=driver_ids)
    driver_availability = await _driver_availability_by_id(session, tenant_id=tenant_id, driver_ids=driver_ids)
    live_cache = fleet_broadcaster.get_all_latest(tenant_id)

    composed = [
        _compose_vehicle_live(
            v,
            device=devices.get(v.id),
            open_trip=open_trips.get(v.id),
            live_position=live_cache.get(v.id),
            current_shift=open_shifts.get(v.id),
            current_driver_name=driver_names.get(open_shifts[v.id].driver_id) if v.id in open_shifts else None,
            is_driver_available=(
                driver_availability.get(open_shifts[v.id].driver_id) if v.id in open_shifts else None
            ),
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
    shift_driver_ids = {current_shift.driver_id} if current_shift else set()
    driver_names = await _driver_names_by_id(session, tenant_id=tenant_id, driver_ids=shift_driver_ids)
    driver_availability = await _driver_availability_by_id(
        session, tenant_id=tenant_id, driver_ids=shift_driver_ids
    )
    live_position = fleet_broadcaster.get_latest(tenant_id, vehicle_id)
    return _compose_vehicle_live(
        vehicle,
        device=devices.get(vehicle_id),
        open_trip=open_trips.get(vehicle_id),
        live_position=live_position,
        current_shift=current_shift,
        current_driver_name=driver_names.get(current_shift.driver_id) if current_shift else None,
        is_driver_available=(
            driver_availability.get(current_shift.driver_id) if current_shift else None
        ),
    )


# ==================================================================================
# Position history + driving signals -- durable `VehiclePositionHistory` reads
# backing the dispatcher "scrub back through the last few hours" replay
# feature (GET /v1/vehicles/{vehicle_id}/position-history). See
# `_persist_position_history` above for how these rows get written.
# ==================================================================================


def _count_harsh_events(
    points: list[VehiclePositionHistory],
) -> tuple[int, int]:
    """Walks consecutive recorded points in chronological order and counts
    harsh-brake / rapid-accel events -- see `HARSH_EVENT_THRESHOLD_KMH_PER_S`'s
    doc comment for what these counts do (and explicitly do NOT) represent.

    A pair of consecutive points only counts toward either total when BOTH
    have a real (non-None) `speed_kmh` -- a device that never reports speed
    contributes nothing here rather than being silently treated as
    stationary (0 km/h is a real, meaningful speed; None is "we don't know")
    -- and the gap between them is a sane `0 < dt <= MAX_CONSECUTIVE_GAP_SECONDS`,
    so a stale fix separated by a long connectivity outage can never be
    misread as an implausible one-second deceleration/acceleration."""
    harsh_brake_events = 0
    rapid_accel_events = 0
    for previous, current in zip(points, points[1:]):
        if previous.speed_kmh is None or current.speed_kmh is None:
            continue
        dt_seconds = (current.recorded_at - previous.recorded_at).total_seconds()
        if dt_seconds <= 0 or dt_seconds > MAX_CONSECUTIVE_GAP_SECONDS:
            continue
        delta_kmh_per_s = (current.speed_kmh - previous.speed_kmh) / dt_seconds
        if delta_kmh_per_s <= -HARSH_EVENT_THRESHOLD_KMH_PER_S:
            harsh_brake_events += 1
        elif delta_kmh_per_s >= HARSH_EVENT_THRESHOLD_KMH_PER_S:
            rapid_accel_events += 1
    return harsh_brake_events, rapid_accel_events


async def get_position_history(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, since: datetime | None = None
) -> dict[str, Any]:
    """Returns this vehicle's recorded position history (tenant-scoped,
    ordered oldest-to-newest) plus the two informational driving-signal
    counts computed by `_count_harsh_events` above. 404s (via
    `get_vehicle_or_404`) if the vehicle doesn't belong to the caller's
    tenant -- checked even though `VehiclePositionHistory` rows are
    themselves tenant-scoped, for the same "confirm the resource exists in
    THIS tenant before answering anything about it" reason every other
    `get_*_or_404` call in this module exists.

    `since`, when given, narrows the window further; when omitted, this
    returns the vehicle's full remaining retention window (whatever
    `_persist_position_history`'s lazy prune hasn't yet expired -- see
    `POSITION_HISTORY_RETENTION_HOURS`), not an unbounded "entire history"
    query, since nothing older than that window is guaranteed to still
    exist."""
    await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    filters = [
        VehiclePositionHistory.tenant_id == tenant_id,
        VehiclePositionHistory.vehicle_id == vehicle_id,
    ]
    if since is not None:
        filters.append(VehiclePositionHistory.recorded_at >= since)

    result = await session.execute(
        select(VehiclePositionHistory).where(*filters).order_by(VehiclePositionHistory.recorded_at.asc())
    )
    points = list(result.scalars())
    harsh_brake_events, rapid_accel_events = _count_harsh_events(points)

    return {
        "items": [
            {
                "lat": point.lat,
                "lng": point.lng,
                "speed_kmh": point.speed_kmh,
                "heading": point.heading,
                "status": point.status,
                "recorded_at": point.recorded_at,
            }
            for point in points
        ],
        "harsh_brake_events": harsh_brake_events,
        "rapid_accel_events": rapid_accel_events,
        "threshold_kmh_per_s": HARSH_EVENT_THRESHOLD_KMH_PER_S,
    }


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