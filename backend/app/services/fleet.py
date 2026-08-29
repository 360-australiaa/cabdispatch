"""Fleet domain business logic: vehicle rego uniqueness, QR-pairing-code
issuance/consumption, device heartbeat, and the admin kiosk-lock/force-update
flags. Kept out of the router so the state-machine bits (pairing code
validation, find-or-create-on-register) aren't tangled up with HTTP concerns.
"""
from __future__ import annotations

import hashlib
import hmac
import secrets
import string
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.device_assignment import DeviceAssignment
from app.models.fleet import Device, DevicePairingCode, DeviceVersionHistory, Vehicle
from app.models.shift import Shift
from app.models.user import ROLE_DRIVER, User
from app.models.vehicle_assignment import VehicleAssignment
from app.services.audit_log import record_audit

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


class VehicleShiftInProgressError(FleetError):
    """Raised by `register_device` when the device being re-paired is
    currently actively assigned (via `DeviceAssignment`, `unbound_at IS
    NULL`) to a vehicle that has an open shift (a `Shift` row with `end_at
    IS NULL`) right now. Swapping the meter out from under a driver
    mid-shift is never legitimate (plan D-3) -- the router maps this to
    409."""

    pass


class VehicleAlreadyHasActiveDeviceError(FleetError):
    """Raised by `create_device` when manually pre-provisioning a device with
    `vehicle_id` set, if that vehicle already has a DIFFERENT device actively
    assigned to it (`DeviceAssignment.unbound_at IS NULL`). Without this
    check, `create_device` inserting a second active `DeviceAssignment` row
    for the same vehicle would hit the DB-level partial unique index
    (`uq_device_assignments_one_active_per_vehicle`) and surface as an
    unhandled `IntegrityError` (500) instead of a clean 409 -- the router
    maps this to 409 with a message pointing at the real fix: re-pair via
    `POST /devices/register`, which correctly closes the old assignment
    first."""

    pass


class DriverNotFoundError(FleetError):
    """Raised by `authorise_driver` when `driver_id` doesn't resolve to a
    User row in this tenant."""

    pass


class InvalidDriverRoleError(FleetError):
    """Raised by `authorise_driver` when the target user's `role` is not
    `"driver"` -- the roster only ever authorises drivers, never
    admins/dispatchers/owners, even though nothing at the DB layer would
    stop a bad FK-only insert."""

    pass


class DuplicateAuthorisationError(FleetError):
    """Raised by `authorise_driver` when an active (revoked_at IS NULL)
    VehicleAssignment already exists for this exact (vehicle_id, driver_id)
    pair -- the router maps this to 409. Mirrors the partial-unique-index
    guarantee at the model layer (see app.models.vehicle_assignment), but
    checked explicitly here first so the caller gets a clean domain error
    instead of relying on the DB to reject the insert with an
    IntegrityError."""

    pass


class VehicleAssignmentNotFoundError(FleetError):
    """Raised by `revoke_authorisation` when there is no currently-active
    VehicleAssignment for the given (vehicle_id, driver_id) pair to
    revoke."""

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


async def unlink_devices_from_vehicle(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str, actor_user_id: str | None = None
) -> None:
    """Called before a vehicle is deleted so devices aren't left pointing at a
    dangling FK — devices survive vehicle deletion, just unbound.

    Device.vehicle_id is a derived/denormalised pointer (plan D-3) -- the
    durable record of a bind/unbind is the DeviceAssignment row. So this
    also closes each affected device's currently-active assignment (if any)
    in the same pass, not just nulling the column, and audit-logs the close
    (action="device_unassigned", same convention as the close-side of a
    re-pair in register_device). actor_user_id is optional because this is
    also reachable from contexts with no authenticated user handy; None is
    a valid actor_user_id for record_audit (see that module).
    """
    result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.vehicle_id == vehicle_id)
    )
    devices = list(result.scalars())
    if not devices:
        return

    device_ids = [d.id for d in devices]
    assignment_result = await session.execute(
        select(DeviceAssignment).where(
            DeviceAssignment.tenant_id == tenant_id,
            DeviceAssignment.device_id.in_(device_ids),
            DeviceAssignment.unbound_at.is_(None),
        )
    )
    now = datetime.now(UTC)
    for assignment in assignment_result.scalars():
        assignment.unbound_at = now
        assignment.unbound_reason = "vehicle_deleted"
        await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=actor_user_id,
            action="device_unassigned",
            entity_type="device_assignment",
            entity_id=assignment.id,
            before={"device_id": assignment.device_id, "vehicle_id": assignment.vehicle_id, "unbound_at": None},
            after={
                "device_id": assignment.device_id,
                "vehicle_id": assignment.vehicle_id,
                "unbound_at": now.isoformat(),
                "unbound_reason": "vehicle_deleted",
            },
        )

    for device in devices:
        device.vehicle_id = None


async def create_device(
    session: AsyncSession,
    *,
    tenant_id: str,
    android_id: str,
    model: str | None,
    app_version: str | None,
    vehicle_id: str | None,
    kiosk_locked: bool,
    calibration_due,
    actor_user_id: str,
) -> tuple[Device, str]:
    """Manual admin pre-provisioning of a device row ahead of physical
    pairing (POST /devices) -- for the realistic case of unboxing a batch
    of tablets and giving each one a row (and optionally an initial vehicle)
    before it is ever physically paired via POST /devices/register.

    CLOSES A REAL GAP (plan D-3, flagged after WP-21/22): that rewrite made
    register_device the one place that opens a DeviceAssignment row, but
    left this route free to set Device.vehicle_id directly with no
    assignment at all -- so a manually pre-provisioned device with
    vehicle_id set would show as bound on the Device row while
    assert_vehicle_operational is "a meter is currently assigned" gate
    (which reads DeviceAssignment, not Device.vehicle_id) would never see
    it, and the one-active-meter-per-vehicle guarantee would not hold for
    this creation path. Fixed here the same way register_device does it:
    when vehicle_id is provided, a real DeviceAssignment row is opened in
    the same transaction (pairing_code_id=None -- there is no pairing code
    in this path, this is a legitimate NULL, not a placeholder) and
    audit-logged (action="device_assigned"), so Device.vehicle_id stays a
    pointer DERIVED from a real assignment even on this path, never
    independent.

    If the target vehicle already has a DIFFERENT device actively assigned,
    raises VehicleAlreadyHasActiveDeviceError (mapped to 409) rather than
    letting the DB partial unique index reject it as a raw IntegrityError
    -- the fix in that case is to re-pair via POST /devices/register
    (which correctly closes the old assignment first), not this endpoint.
    """
    if vehicle_id is not None:
        active_result = await session.execute(
            select(DeviceAssignment).where(
                DeviceAssignment.tenant_id == tenant_id,
                DeviceAssignment.vehicle_id == vehicle_id,
                DeviceAssignment.unbound_at.is_(None),
            )
        )
        if active_result.scalar_one_or_none() is not None:
            raise VehicleAlreadyHasActiveDeviceError(
                "This vehicle already has an actively assigned device. "
                "Re-pair via POST /devices/register instead."
            )

    device = Device(
        tenant_id=tenant_id,
        android_id=android_id,
        model=model,
        app_version=app_version,
        vehicle_id=vehicle_id,
        kiosk_locked=kiosk_locked,
        calibration_due=calibration_due,
    )
    session.add(device)
    await session.flush()  # populate device.id before it is referenced below

    if vehicle_id is not None:
        now = datetime.now(UTC)
        assignment = DeviceAssignment(
            tenant_id=tenant_id,
            device_id=device.id,
            vehicle_id=vehicle_id,
            bound_at=now,
            bound_by_user_id=actor_user_id,
            pairing_code_id=None,
        )
        session.add(assignment)
        await session.flush()  # populate assignment.id for the audit row below
        await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=actor_user_id,
            action="device_assigned",
            entity_type="device_assignment",
            entity_id=assignment.id,
            before=None,
            after={
                "device_id": assignment.device_id,
                "vehicle_id": assignment.vehicle_id,
                "bound_at": now.isoformat(),
                "pairing_code_id": None,
                "source": "manual_provisioning",
            },
        )

    await session.commit()
    await session.refresh(device)
    return device


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


def _generate_device_secret() -> str:
    return secrets.token_urlsafe(32)


def _hash_device_secret(raw: str) -> str:
    # Plain sha256, not bcrypt -- same reasoning as UserInvite.token_hash
    # (app/services/user_invites.py): the raw value is a 32-byte
    # secrets.token_urlsafe output, already high-entropy, so a deliberately
    # slow KDF buys nothing and would tax every single heartbeat request
    # (this runs far more often than an invite is ever consumed).
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


async def register_device(
    session: AsyncSession,
    *,
    tenant_id: str,
    android_id: str,
    pairing_code: str,
    model: str | None,
    app_version: str | None,
    actor_user_id: str,
) -> Device:
    """Consumes a not-yet-used, not-expired pairing code and binds (creating if
    necessary) the device identified by `android_id` to that code's vehicle.

    Re-registering an already-known android_id (e.g. a device swapped to a
    different car) is allowed -- it just re-binds the existing Device row
    rather than erroring, since that's the realistic "device gets moved"
    case.

    BINDING MODEL (plan D-3, WP-21/22 rewrite): a bind is now a
    `DeviceAssignment` row, not a bare `Device.vehicle_id` overwrite.
    Re-pairing to a different vehicle CLOSES the device's current active
    assignment (`unbound_at` set, `unbound_reason="re-paired"`) and OPENS a
    fresh one for the new vehicle, both audit-logged
    ("device_unassigned" / "device_assigned"). `Device.vehicle_id` is still
    written here as a denormalised convenience pointer for cheap reads
    elsewhere in this domain, but it is now strictly DERIVED from the
    assignment write below -- this is the only place in this service that
    still sets it directly (see `unlink_devices_from_vehicle` for the other
    legitimate write site, which closes the assignment the same way).

    `actor_user_id` / `bound_by_user_id` judgment call: pairing codes are
    presented by the physical device itself (androidId + code), not by a
    "caller" acting on the pairing code's behalf -- but `POST
    /devices/register` still requires a valid bearer token today (see that
    route's docstring: "the person setting up the kiosk is logged into the
    app"), so there IS a real authenticated user attached to every call.
    `bound_by_user_id` is set to THAT caller -- the person physically
    performing the pairing right now -- not `DevicePairingCode`'s creator.
    This is deliberately different from "whoever generated the code": an
    admin can mint a code and hand it to on-site staff, who may be a
    different logged-in user than the one who ends up scanning it in:
    `bound_by_user_id` should record who actually did the bind, which is
    exactly what the router's already-authenticated caller represents,
    without needing a new `DevicePairingCode.created_by_user_id` column.

    SHIFT GUARD (plan D-3): before touching anything, if this device
    currently has an active assignment, that assignment's vehicle must NOT
    have an open shift (a `Shift` row with `end_at IS NULL`) right now --
    otherwise `VehicleShiftInProgressError` is raised and NOTHING is
    changed (pairing code stays unused, no assignment opened/closed). This
    check runs whenever an active assignment exists, even if the pairing
    code targets the SAME vehicle the device is already on -- there is
    never a legitimate reason to run this endpoint against a vehicle with a
    driver mid-shift.
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
        await session.flush()  # populate device.id before it's referenced below

    active_assignment_result = await session.execute(
        select(DeviceAssignment).where(
            DeviceAssignment.tenant_id == tenant_id,
            DeviceAssignment.device_id == device.id,
            DeviceAssignment.unbound_at.is_(None),
        )
    )
    active_assignment = active_assignment_result.scalar_one_or_none()

    if active_assignment is not None:
        open_shift_result = await session.execute(
            select(Shift.id).where(
                Shift.tenant_id == tenant_id,
                Shift.vehicle_id == active_assignment.vehicle_id,
                Shift.end_at.is_(None),
            )
        )
        if open_shift_result.scalar_one_or_none() is not None:
            raise VehicleShiftInProgressError(
                "Cannot re-pair meter: vehicle has an open shift."
            )

    now = datetime.now(UTC)

    if active_assignment is not None and active_assignment.vehicle_id != pairing.vehicle_id:
        active_assignment.unbound_at = now
        active_assignment.unbound_reason = "re-paired"
        await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=actor_user_id,
            action="device_unassigned",
            entity_type="device_assignment",
            entity_id=active_assignment.id,
            before={
                "device_id": active_assignment.device_id,
                "vehicle_id": active_assignment.vehicle_id,
                "unbound_at": None,
            },
            after={
                "device_id": active_assignment.device_id,
                "vehicle_id": active_assignment.vehicle_id,
                "unbound_at": now.isoformat(),
                "unbound_reason": "re-paired",
            },
        )
        active_assignment = None

    if active_assignment is None:
        new_assignment = DeviceAssignment(
            tenant_id=tenant_id,
            device_id=device.id,
            vehicle_id=pairing.vehicle_id,
            bound_at=now,
            bound_by_user_id=actor_user_id,
            pairing_code_id=pairing.id,
        )
        session.add(new_assignment)
        await session.flush()  # populate new_assignment.id for the audit row below
        await record_audit(
            session,
            tenant_id=tenant_id,
            actor_user_id=actor_user_id,
            action="device_assigned",
            entity_type="device_assignment",
            entity_id=new_assignment.id,
            before=None,
            after={
                "device_id": new_assignment.device_id,
                "vehicle_id": new_assignment.vehicle_id,
                "bound_at": now.isoformat(),
                "pairing_code_id": new_assignment.pairing_code_id,
            },
        )

    device.vehicle_id = pairing.vehicle_id
    if model is not None:
        device.model = model
    if app_version is not None:
        device.app_version = app_version
    device.last_seen_at = now

    # Issue a fresh device secret on every successful register_device call --
    # fresh pair AND re-pair. Re-pairing rotates it, so an old physical unit's
    # secret stops working the moment the meter is moved to another vehicle --
    # the same "swapping invalidates the old credential" property D-3 already
    # gives DeviceAssignment history. Returned ONCE, in the router's response,
    # never echoed back on any GET/PATCH.
    raw_device_secret = _generate_device_secret()
    device.device_secret_hash = _hash_device_secret(raw_device_secret)

    pairing.used_at = now
    pairing.used_by_device_id = device.id

    await session.commit()
    await session.refresh(device)
    return device, raw_device_secret


# --- heartbeat + admin flags --------------------------------------------------


async def verify_device_secret(session: AsyncSession, *, device_id: str, raw_secret: str) -> Device | None:
    """Looks up a Device by id ALONE (no tenant scoping -- there is no bearer
    token yet in this path, that is the whole point) and returns it only if
    raw_secret's hash matches device.device_secret_hash via a constant-time
    comparison. Returns None on any mismatch (unknown device_id, no secret
    set yet, or wrong secret) -- the caller maps that to a generic 401,
    never distinguishing which case it was, same anti-enumeration spirit as
    the Phase 1 forgot-password work. device_id is an unguessable UUID and
    the secret itself is 32 bytes of entropy, so this is safe without a
    tenant_id pre-filter."""
    result = await session.execute(select(Device).where(Device.id == device_id))
    device = result.scalar_one_or_none()
    if device is None or device.device_secret_hash is None:
        return None
    if not hmac.compare_digest(device.device_secret_hash, _hash_device_secret(raw_secret)):
        return None
    return device


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



# --- vehicle roster (WP-24 endpoint half) ------------------------------------
# authorise_driver / revoke_authorisation manage VehicleAssignment rows -- the
# driver allow-list for a vehicle. See app.models.vehicle_assignment for the
# full design rationale (partial unique index, many-to-many by design, not a
# live-occupancy lock -- that is Shift's job). Consumed later by WP-30's
# start_shift validation (not built yet -- do not assume its shape here).


async def authorise_driver(
    session: AsyncSession,
    *,
    tenant_id: str,
    vehicle_id: str,
    driver_id: str,
    actor_user_id: str,
) -> VehicleAssignment:
    """Adds `driver_id` to `vehicle_id`'s roster (allow-list).

    Validates, in order:
    - the vehicle exists in this tenant (VehicleNotFoundError, 404)
    - the driver exists in this tenant (DriverNotFoundError, 404)
    - the driver's role is actually "driver" (InvalidDriverRoleError, 422) --
      the roster is never meant to authorise admins/dispatchers/owners
    - no active (revoked_at IS NULL) authorisation already exists for this
      exact (vehicle_id, driver_id) pair (DuplicateAuthorisationError, 409)
      -- checked explicitly here so the caller gets a clean domain error;
      the model's partial unique index is the real backstop against a race,
      but this check is what turns the common case into a friendly 409
      instead of a raw IntegrityError bubbling up.

    `authorised_by_user_id` is the caller (`actor_user_id`) and
    `authorised_at` is the server clock -- neither is client-settable, same
    convention as `register_device`'s `bound_by_user_id`/`bound_at`.
    """
    await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    driver_result = await session.execute(
        select(User).where(User.id == driver_id, User.tenant_id == tenant_id)
    )
    driver = driver_result.scalar_one_or_none()
    if driver is None:
        raise DriverNotFoundError(driver_id)
    if driver.role != ROLE_DRIVER:
        raise InvalidDriverRoleError(
            f"User {driver_id} has role \'{driver.role}\', not \'driver\' -- cannot be added to a vehicle roster"
        )

    active_result = await session.execute(
        select(VehicleAssignment.id).where(
            VehicleAssignment.tenant_id == tenant_id,
            VehicleAssignment.vehicle_id == vehicle_id,
            VehicleAssignment.driver_id == driver_id,
            VehicleAssignment.revoked_at.is_(None),
        )
    )
    if active_result.scalar_one_or_none() is not None:
        raise DuplicateAuthorisationError(
            f"Driver {driver_id} already has an active authorisation on vehicle {vehicle_id}"
        )

    now = datetime.now(UTC)
    assignment = VehicleAssignment(
        tenant_id=tenant_id,
        vehicle_id=vehicle_id,
        driver_id=driver_id,
        authorised_by_user_id=actor_user_id,
        authorised_at=now,
    )
    session.add(assignment)
    await session.flush()  # populate assignment.id before the audit row below
    await record_audit(
        session,
        tenant_id=tenant_id,
        actor_user_id=actor_user_id,
        action="vehicle_roster_authorised",
        entity_type="vehicle_assignment",
        entity_id=assignment.id,
        before=None,
        after={
            "vehicle_id": vehicle_id,
            "driver_id": driver_id,
            "authorised_at": now.isoformat(),
        },
    )
    await session.commit()
    await session.refresh(assignment)
    return assignment


async def list_active_roster(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> list[VehicleAssignment]:
    """Currently-active (revoked_at IS NULL) authorisations for a vehicle.
    Does not itself 404 on an unknown vehicle_id -- callers that need that
    (the router) should call `get_vehicle_or_404` first; this just returns
    an empty list for a vehicle with no active roster entries."""
    result = await session.execute(
        select(VehicleAssignment)
        .where(
            VehicleAssignment.tenant_id == tenant_id,
            VehicleAssignment.vehicle_id == vehicle_id,
            VehicleAssignment.revoked_at.is_(None),
        )
        .order_by(VehicleAssignment.authorised_at)
    )
    return list(result.scalars().all())


async def revoke_authorisation(
    session: AsyncSession,
    *,
    tenant_id: str,
    vehicle_id: str,
    driver_id: str,
    reason: str | None,
    actor_user_id: str,
) -> VehicleAssignment:
    """Closes the currently-active VehicleAssignment for (vehicle_id,
    driver_id) -- sets `revoked_at`/`revoked_reason`, never deletes the row
    (append-only history, same convention as DeviceAssignment.unbound_at).
    Raises VehicleAssignmentNotFoundError (404) if there is no active
    authorisation for this pair."""
    result = await session.execute(
        select(VehicleAssignment).where(
            VehicleAssignment.tenant_id == tenant_id,
            VehicleAssignment.vehicle_id == vehicle_id,
            VehicleAssignment.driver_id == driver_id,
            VehicleAssignment.revoked_at.is_(None),
        )
    )
    assignment = result.scalar_one_or_none()
    if assignment is None:
        raise VehicleAssignmentNotFoundError(
            f"No active authorisation for driver {driver_id} on vehicle {vehicle_id}"
        )

    now = datetime.now(UTC)
    assignment.revoked_at = now
    assignment.revoked_reason = reason
    await record_audit(
        session,
        tenant_id=tenant_id,
        actor_user_id=actor_user_id,
        action="vehicle_roster_revoked",
        entity_type="vehicle_assignment",
        entity_id=assignment.id,
        before={"vehicle_id": vehicle_id, "driver_id": driver_id, "revoked_at": None},
        after={
            "vehicle_id": vehicle_id,
            "driver_id": driver_id,
            "revoked_at": now.isoformat(),
            "revoked_reason": reason,
        },
    )
    await session.commit()
    await session.refresh(assignment)
    return assignment
