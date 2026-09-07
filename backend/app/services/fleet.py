"""Fleet domain business logic: vehicle rego uniqueness, QR-pairing-code
issuance/consumption, device heartbeat, and the admin kiosk-lock/force-update
flags. Kept out of the router so the state-machine bits (pairing code
validation, find-or-create-on-register) aren't tangled up with HTTP concerns.

DELETE-SAFETY BUG (found live on production, reproduced by hand through the
admin UI's own per-row Delete button — not a concurrency issue, a single
isolated request fails every time): `DELETE /v1/fleet/vehicles/{id}` and
`DELETE /v1/fleet/devices/{id}` failed against postgres (the real production
database — see docker-compose.yml) for any vehicle/device that had ever sent
a heartbeat or position update — practically every real one, since that's
routine operation, not an edge case.

ROOT CAUSE: postgres enforces every foreign key by default. Three tables
carried a NOT NULL FK to `vehicles.id`/`devices.id` with no `ondelete=`
action, i.e. an implicit `NO ACTION`/`RESTRICT`: `device_version_history
.device_id`, `vehicle_position_history.vehicle_id`, and
`device_pairing_codes.vehicle_id` (`device_pairing_codes.used_by_device_id`
is nullable but has the same implicit-RESTRICT problem). sqlite — this
project's dev/test DATABASE_URL default (app/core/config.py) and the whole
test suite's DB (tests/conftest.py) — silently does NOT enforce FKs unless a
connection explicitly runs `PRAGMA foreign_keys=ON`, which nothing in this
codebase did before this same fix pass (see app.core.database). That is
exactly why 649 tests could pass while this failed 100% of the time in
production: the test suite's database was never able to exercise the
constraint that was rejecting the delete.

THE OBSERVED "503": production reportedly returned HTTP 503 with no CORS
headers (so the browser only ever showed a bare "Network Error"), not the
500 an unhandled `IntegrityError` propagating out of a FastAPI endpoint
would normally produce (this codebase registers no exception handlers
anywhere — see app/main.py — so Starlette's own default handler is what
would run, and it returns 500, not 503; nothing in this repo emits 503 at
all, grepped exhaustively). This could NOT be independently confirmed or
explained from the code in this repository — there is no postgres instance
available in this sandbox to reproduce the live failure against, and nothing
here (uvicorn is run with no `--limit-concurrency`, no gunicorn, no reverse
proxy in docker-compose.yml in front of the backend's published port) can
produce a 503. It is likely coming from a piece of the real production
deployment topology not represented in this repo (e.g. a load balancer/
reverse proxy fronting the backend that returns its own 503 on an abruptly
reset connection) — flagged explicitly rather than guessed at further.

THE FIX has two halves, split by what a dependent row actually MEANS —
see app.models.fleet's per-column comments for exactly which columns get
which treatment, and app.services.user.assert_user_deletable's docstring
for the mirror-image decision on `DELETE /v1/users/{id}`:

  * Derived/ephemeral operational telemetry (device version history,
    vehicle position history, pairing codes) has nothing left to be
    evidence FOR once its parent vehicle/device is gone — cascaded away at
    the DB layer via `ondelete="CASCADE"`/`"SET NULL"` (see the migration
    for the exact per-column mapping). No service-layer code change was
    needed for this half; the DB does it automatically as part of the same
    `DELETE` statement the router already issues.
  * Audit/financial evidence (compliance documents, PSL ledger, wallet
    ledger lines, tariff change log, the tamper-evidence audit trail) must
    never be silently destroyed just to let a delete through — see
    app.services.user.assert_user_deletable, which refuses those deletes
    with a specific, actionable 409 instead.

DANGLING-OPEN-SHIFT BUG (found live, separate from the above): deleting a
vehicle with an open Shift on it (`Shift.vehicle_id` — a plain unconstrained
String column, see app/models/shift.py's own DEVIATION note, so the DB never
rejected this) left that shift open forever, pointing at a vehicle_id that no
longer resolves to anything — surfacing on the dashboard's drivers list as a
raw UUID where a vehicle rego should be. Fixed by `prepare_vehicle_for_deletion`
below, called from `DELETE /v1/fleet/vehicles/{id}`: CLOSES the open shift
(unreconciled, audit-logged) rather than refusing the vehicle delete — see
`app.services.shift.close_open_shifts_for_vehicle_deletion`'s own docstring
for the full "close vs refuse" reasoning.
"""
from __future__ import annotations

import secrets
import string
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import Device, DevicePairingCode, DeviceVersionHistory, Vehicle
from app.models.shift import Shift
from app.models.user import User
from app.services.shift import close_open_shifts_for_vehicle_deletion

# Codes exclude visually-ambiguous characters (0/O, 1/I) since a driver may need
# to key one in by hand if the QR scan fails.
_PAIRING_CODE_ALPHABET = "".join(c for c in string.ascii_uppercase + string.digits if c not in "01OI")
PAIRING_CODE_LENGTH = 8
PAIRING_CODE_TTL_MINUTES = 15


class FleetError(Exception):
    """Base class for fleet-domain errors; the router translates each subclass
    to the appropriate HTTP status."""


class VehicleNotFoundError(FleetError):
    pass


class DeviceNotFoundError(FleetError):
    pass


class DuplicateRegoError(FleetError):
    pass


class InvalidPairingCodeError(FleetError):
    pass


def _is_expired(expires_at: datetime) -> bool:
    """SQLite (used in dev/test) round-trips `DateTime(timezone=True)` columns
    as naive datetimes — the tzinfo doesn't survive storage — even though every
    value this domain writes is UTC-aware at write time. Compare against a
    same-"awareness" `now` so this works on both sqlite and postgres."""
    now = datetime.now(UTC)
    if expires_at.tzinfo is None:
        now = now.replace(tzinfo=None)
    return expires_at < now


# --- lookups (tenant-scoped — every caller already has tenant_id from
# get_current_tenant_id) ------------------------------------------------------


async def get_vehicle_or_404(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> Vehicle:
    result = await session.execute(
        select(Vehicle).where(Vehicle.id == vehicle_id, Vehicle.tenant_id == tenant_id)
    )
    vehicle = result.scalar_one_or_none()
    if vehicle is None:
        raise VehicleNotFoundError(vehicle_id)
    return vehicle


async def get_device_or_404(session: AsyncSession, *, tenant_id: str, device_id: str) -> Device:
    result = await session.execute(
        select(Device).where(Device.id == device_id, Device.tenant_id == tenant_id)
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise DeviceNotFoundError(device_id)
    return device


async def _rego_taken(
    session: AsyncSession, *, tenant_id: str, rego: str, exclude_vehicle_id: str | None = None
) -> bool:
    stmt = select(Vehicle.id).where(Vehicle.tenant_id == tenant_id, Vehicle.rego == rego)
    if exclude_vehicle_id is not None:
        stmt = stmt.where(Vehicle.id != exclude_vehicle_id)
    result = await session.execute(stmt)
    return result.scalar_one_or_none() is not None


async def assert_rego_available(
    session: AsyncSession, *, tenant_id: str, rego: str, exclude_vehicle_id: str | None = None
) -> None:
    if await _rego_taken(session, tenant_id=tenant_id, rego=rego, exclude_vehicle_id=exclude_vehicle_id):
        raise DuplicateRegoError(rego)


async def unlink_devices_from_vehicle(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> None:
    """Called before a vehicle is deleted so devices aren't left pointing at a
    dangling FK — devices survive vehicle deletion, just unbound."""
    result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.vehicle_id == vehicle_id)
    )
    for device in result.scalars():
        device.vehicle_id = None


async def prepare_vehicle_for_deletion(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, actor_user_id: str | None
) -> None:
    """Everything that must happen BEFORE a vehicle row is actually deleted,
    beyond what the DB's own `ondelete=` cascades handle automatically (see
    this module's docstring). Single call site for both `DELETE
    /v1/fleet/vehicles/{id}` (app/api/v1/fleet.py) and the TEMPORARY force-wipe
    tool (app.services.fleet_wipe) so the two paths can never drift apart on
    this:

    1. Unlink any devices still paired to this vehicle (see
       `unlink_devices_from_vehicle` above) — devices survive, just unbound.
    2. Close any currently-OPEN shift on this vehicle (see
       `app.services.shift.close_open_shifts_for_vehicle_deletion` for the
       full "close, don't refuse" design decision) — a dangling open shift
       pointing at a since-deleted vehicle_id was the real production bug
       this fixes (raw UUIDs rendering on the dashboard's drivers list where
       a rego should be).
    """
    await unlink_devices_from_vehicle(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    await close_open_shifts_for_vehicle_deletion(
        session, tenant_id=tenant_id, vehicle_id=vehicle_id, actor_user_id=actor_user_id
    )


# --- pairing-code issuance + consumption -------------------------------------


async def generate_pairing_code(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> DevicePairingCode:
    await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    code = "".join(secrets.choice(_PAIRING_CODE_ALPHABET) for _ in range(PAIRING_CODE_LENGTH))
    pairing = DevicePairingCode(
        tenant_id=tenant_id,
        vehicle_id=vehicle_id,
        code=code,
        expires_at=datetime.now(UTC) + timedelta(minutes=PAIRING_CODE_TTL_MINUTES),
    )
    session.add(pairing)
    await session.commit()
    await session.refresh(pairing)
    return pairing


async def register_device(
    session: AsyncSession,
    *,
    tenant_id: str,
    android_id: str,
    pairing_code: str,
    model: str | None,
    app_version: str | None,
) -> Device:
    """Consumes a not-yet-used, not-expired pairing code and binds (creating if
    necessary) the device identified by `android_id` to that code's vehicle.

    Re-registering an already-known android_id (e.g. a device swapped to a
    different car) is allowed — it just re-binds the existing Device row rather
    than erroring, since that's the realistic "device gets moved" case.
    """
    result = await session.execute(
        select(DevicePairingCode).where(
            DevicePairingCode.tenant_id == tenant_id,
            DevicePairingCode.code == pairing_code,
            DevicePairingCode.used_at.is_(None),
        )
    )
    pairing = result.scalar_one_or_none()
    if pairing is None:
        raise InvalidPairingCodeError("Pairing code not found or already used")
    if _is_expired(pairing.expires_at):
        raise InvalidPairingCodeError("Pairing code has expired")

    result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.android_id == android_id)
    )
    device = result.scalar_one_or_none()
    if device is None:
        device = Device(tenant_id=tenant_id, android_id=android_id)
        session.add(device)

    device.vehicle_id = pairing.vehicle_id
    if model is not None:
        device.model = model
    if app_version is not None:
        device.app_version = app_version
    device.last_seen_at = datetime.now(UTC)

    pairing.used_at = datetime.now(UTC)
    await session.flush()  # so device.id is populated before we reference it below
    pairing.used_by_device_id = device.id

    await session.commit()
    await session.refresh(device)
    return device


# --- heartbeat + admin flags --------------------------------------------------


async def record_heartbeat(
    session: AsyncSession,
    device: Device,
    *,
    battery: int | None,
    network: str | None,
    app_version: str | None,
) -> Device:
    """Updates last_seen_at/battery/network/app_version.

    Also appends a `DeviceVersionHistory` row whenever the incoming
    `app_version` differs from what is currently stored on `device.app_version`
    (including the very first time a version is ever recorded, i.e. going
    from None to a real value) -- this is the sole write path onto that
    table, feeding the per-vehicle evidence pack
    (app.services.evidence_pack) with a real firmware/app-version timeline
    instead of just a current snapshot. Does NOT duplicate the heartbeat
    endpoint -- this extends the existing one, per the evidence-pack task
    brief."""
    device.last_seen_at = datetime.now(UTC)
    if battery is not None:
        device.battery = battery
    if network is not None:
        device.network = network
    if app_version is not None and app_version != device.app_version:
        session.add(
            DeviceVersionHistory(
                tenant_id=device.tenant_id,
                device_id=device.id,
                app_version=app_version,
                recorded_at=datetime.now(UTC),
            )
        )
        device.app_version = app_version
    await session.commit()
    await session.refresh(device)
    return device


async def set_kiosk_lock(session: AsyncSession, device: Device, *, enabled: bool) -> Device:
    device.kiosk_locked = enabled
    await session.commit()
    await session.refresh(device)
    return device


async def set_force_update(session: AsyncSession, device: Device, *, enabled: bool) -> Device:
    device.force_update_pending = enabled
    await session.commit()
    await session.refresh(device)
    return device


async def set_locate_requested(session: AsyncSession, device: Device, *, enabled: bool) -> Device:
    device.locate_requested = enabled
    await session.commit()
    await session.refresh(device)
    return device


async def set_reboot_requested(session: AsyncSession, device: Device, *, enabled: bool) -> Device:
    """See the HONESTY NOTE on `Device.reboot_requested` — this only flips the
    flag the device reads back on heartbeat; nothing here reboots anything."""
    device.reboot_requested = enabled
    await session.commit()
    await session.refresh(device)
    return device


# --- Shift history (past-shifts-per-vehicle pass) ----------------------------
# `app.services.live_ops._open_shifts_by_vehicle` already answers "who has
# this vehicle checked out RIGHT NOW" (derived live, never a cached pointer —
# see that function's docstring). What's missing is the past: a real fleet
# commonly runs one vehicle across two 12h shifts/day under two different
# drivers, and nothing before this pass could answer "which drivers has this
# vehicle had". This is additive, read-only history — it does not touch
# live_ops.py's live/current-state logic at all.


async def _driver_names_by_id(
    session: AsyncSession, *, tenant_id: str, driver_ids: set[str]
) -> dict[str, str]:
    """Batch name lookup for a set of driver ids — avoids an N+1 query when
    composing a page of shifts, each potentially needing its driver's display
    name. Same batching pattern as `app.services.live_ops._driver_names_by_id`
    (kept as a separate, domain-local copy rather than importing that
    underscore-prefixed helper across domains)."""
    if not driver_ids:
        return {}
    result = await session.execute(
        select(User.id, User.name).where(User.tenant_id == tenant_id, User.id.in_(driver_ids))
    )
    return {row.id: row.name for row in result}


async def list_vehicle_shift_history(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, skip: int = 0, limit: int = 20
) -> tuple[list[dict], int]:
    """Every `Shift` (past, and the currently-open one if any) ever run on
    this vehicle, newest-first by `start_at`, tenant-scoped and paginated
    (same `{items, total, skip, limit}` `Page[T]` contract as every other list
    endpoint in this file). Raises `VehicleNotFoundError` if `vehicle_id`
    doesn't belong to this tenant — same 404 convention as every other
    per-vehicle lookup here.

    Returns composed dicts (driver_name joined in via `_driver_names_by_id`,
    `fare_total` = cash_total + card_total), not bare ORM rows — mirrors
    `app.services.live_ops.list_vehicles_live`'s own "compose a dict per row"
    shape, since the response needs a field (driver_name) that isn't a column
    on `Shift` itself.
    """
    await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    count_stmt = select(func.count()).select_from(Shift).where(
        Shift.tenant_id == tenant_id, Shift.vehicle_id == vehicle_id
    )
    total = (await session.execute(count_stmt)).scalar_one()

    result = await session.execute(
        select(Shift)
        .where(Shift.tenant_id == tenant_id, Shift.vehicle_id == vehicle_id)
        .order_by(Shift.start_at.desc())
        .offset(skip)
        .limit(limit)
    )
    shifts = result.scalars().all()

    driver_names = await _driver_names_by_id(
        session, tenant_id=tenant_id, driver_ids={s.driver_id for s in shifts}
    )

    items = [
        {
            "shift_id": s.id,
            "driver_id": s.driver_id,
            "driver_name": driver_names.get(s.driver_id),
            "start_at": s.start_at,
            "end_at": s.end_at,
            "distance_km": s.km_total,
            "fare_total": s.cash_total + s.card_total,
        }
        for s in shifts
    ]
    return items, total
