"""Fleet domain business logic: vehicle rego uniqueness, QR-pairing-code
issuance/consumption, device heartbeat, and the admin kiosk-lock/force-update
flags. Kept out of the router so the state-machine bits (pairing code
validation, find-or-create-on-register) aren't tangled up with HTTP concerns.
"""
from __future__ import annotations

import secrets
import string
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import Device, DevicePairingCode, DeviceVersionHistory, Vehicle

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
