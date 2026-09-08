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

import hashlib
import hmac
import secrets
import string
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import Device, DevicePairingCode, DeviceVersionHistory, Vehicle
from app.models.shift import Shift
from app.models.user import User
from app.services.audit_log import record_audit
from app.services.shift import close_open_shifts_for_vehicle_deletion

# Codes exclude visually-ambiguous characters (0/O, 1/I) since a driver may need
# to key one in by hand if the QR scan fails.
_PAIRING_CODE_ALPHABET = "".join(c for c in string.ascii_uppercase + string.digits if c not in "01OI")
PAIRING_CODE_LENGTH = 8
PAIRING_CODE_TTL_MINUTES = 15

# How long a CONSUMED pairing code is kept before `generate_pairing_code` prunes
# it. Its `used_by_device_id` is the only surviving record of which admin-issued
# code enrolled which tablet, so it is worth keeping while a commissioning is
# recent enough for someone to still be asking about it -- but not forever, and
# the AuditLog `device_registered` / `device_repaired` row written by
# `register_device` is the durable record either way. Expired-but-unused codes
# carry no such history and are pruned as soon as they expire.
_PAIRING_CODE_RETENTION_DAYS = 30


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


class DeviceAuthError(FleetError):
    """A device presented a `X-Device-Secret` that does not match. Kept distinct
    from DeviceNotFoundError so the router can answer 401 (wrong credential)
    rather than 404 (no such device) -- a revoked or deleted device is a 404,
    which is what the Android client turns into a sticky `deviceRejected`."""


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


async def get_active_device_or_404(
    session: AsyncSession, *, tenant_id: str, device_id: str
) -> Device:
    """`get_device_or_404`, but a REVOKED device is treated as absent.

    This is the lookup every route a TABLET calls must use, and it exists
    because `get_device_or_404` deliberately still returns revoked rows -- admin
    CRUD needs them (that is how `PATCH /devices/{id}` un-revokes one, and how a
    revoked tablet stays visible on the dashboard's device list instead of
    vanishing). Revocation was therefore bypassable: `authenticate_device` below
    checks `revoked_at`, but the bearer FALLBACK on the same routes went through
    `get_device_or_404`, which does not -- so a revoked, lost or stolen tablet
    with any valid human token on it kept heartbeating, answering locates and
    acknowledging commands (backend audit §3 G3). Cutting a tablet off is the
    entire point of the revoke button.

    Raises DeviceNotFoundError for both unknown and revoked, exactly as
    `authenticate_device` does, so the two paths give a revoked tablet the same
    404 the Android client already reads as a sticky `deviceRejected`
    (DeviceCommandHeartbeat.pollOnce).
    """
    device = await get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    if device.revoked_at is not None:
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


# --- device credential --------------------------------------------------------
#
# Every other route in this domain authenticates a HUMAN access token, so a
# tablet with nobody logged into it cannot reach this backend at all -- which is
# exactly why a parked or logged-off tablet cannot be located, kiosk-locked or
# told to update today. A device secret makes the tablet a caller in its own
# right. Same shape app.services.duress_device already uses for duress hardware:
# a random secret handed over once, only its hash retained, constant-time
# compared on every use.


def _hash_device_secret(secret: str) -> str:
    """SHA-256 hex of a device secret.

    A plain hash, not a password KDF, and deliberately so: this is a 256-bit
    random value we generated ourselves (`secrets.token_urlsafe(32)`), not a
    human-chosen password. There is no dictionary to attack and no work factor
    worth paying on a request that runs on every 60-second heartbeat from every
    tablet in the fleet.
    """
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def mint_device_secret() -> tuple[str, str]:
    """A fresh (plaintext, hash) pair. The plaintext is returned to the tablet
    exactly once, at registration, and never recoverable afterwards -- losing it
    means re-pairing, which is the correct and intended recovery."""
    secret = secrets.token_urlsafe(32)
    return secret, _hash_device_secret(secret)


async def authenticate_device(session: AsyncSession, *, device_id: str, secret: str) -> Device:
    """The device row for `device_id`, if `secret` is its credential.

    Raises DeviceNotFoundError for an unknown OR revoked device -- deliberately
    the same answer for both, so a revoked tablet gets the 404 the Android
    client already reads as "this device is no longer registered"
    (DeviceCommandHeartbeat.pollOnce turns a heartbeat 404 into a sticky
    `deviceRejected`). Raises DeviceAuthError for a real device presenting the
    wrong secret, and for one that has no secret at all -- a device paired
    before secrets existed must fall back to bearer auth, not be waved through
    on an empty credential.
    """
    result = await session.execute(select(Device).where(Device.id == device_id))
    device = result.scalar_one_or_none()
    if device is None or device.revoked_at is not None:
        raise DeviceNotFoundError(device_id)
    if not device.device_secret_hash:
        raise DeviceAuthError("Device has no credential -- re-pair it")
    if not hmac.compare_digest(device.device_secret_hash, _hash_device_secret(secret)):
        raise DeviceAuthError("Device secret mismatch")
    return device


async def authenticate_device_by_secret(session: AsyncSession, *, secret: str) -> Device:
    """The device row that owns `secret`, with no device id supplied.

    `authenticate_device` above needs the caller to already know which row it
    is; that is fine for the heartbeat, whose URL carries the id, but it cannot
    answer the one question a freshly-booted tablet actually has -- "which
    vehicle am I in?" (backend audit §3 G5). Both device-reading routes were
    bearer-only, so a parked, logged-off tablet holding a stale binding could
    not self-heal without a human signing in first, and that is precisely the
    tablet an operator is trying to straighten out.

    A lookup BY the credential is safe here only because the stored value is a
    deterministic SHA-256 of a 256-bit random secret we minted ourselves: the
    hash is an exact-match index probe, not a guessable one. It would be
    unsound for a password KDF, which is why this shape is confined to device
    secrets. See `_hash_device_secret` for the rest of that reasoning.

    Raises DeviceAuthError -- never DeviceNotFoundError -- for an unknown or
    revoked secret. Without a device id in the request there is nothing for a
    404 to be "not found" about, and answering "no such device" to a guess would
    confirm which secrets exist.
    """
    result = await session.execute(
        select(Device).where(Device.device_secret_hash == _hash_device_secret(secret))
    )
    device = result.scalar_one_or_none()
    if device is None or device.revoked_at is not None:
        raise DeviceAuthError("Device secret not recognised")
    return device


async def rotate_device_secret(
    session: AsyncSession, device: Device, *, actor_user_id: str | None
) -> tuple[Device, str]:
    """Mints a fresh secret for an already-paired device, invalidating the old
    one immediately, and returns the plaintext exactly once.

    Until this existed, `mint_device_secret` had a single call site --
    `register_device` -- so a device secret was effectively immortal and the
    only way to change one was to physically visit the tablet with a new pairing
    code (backend audit §3 G2). The duress hardware in this same codebase has
    had `POST /duress-devices/{id}/rotate-secret` since it landed
    (app/api/v1/duress_device.py); the asymmetry was an oversight, not a design.

    Rotation is deliberately NOT a re-pair: `vehicle_id`, `paired_at` and
    `revoked_at` are all left exactly as they are. This is "the credential on
    this tablet may have leaked", not "this tablet moved car", and conflating
    the two is how a routine security action would silently rebind a vehicle.

    Audit-logged, because an operator who later finds a tablet has stopped
    working needs to be able to see that someone rotated its secret and when.
    """
    secret, secret_hash = mint_device_secret()
    device.device_secret_hash = secret_hash

    await record_audit(
        session,
        tenant_id=device.tenant_id,
        actor_user_id=actor_user_id,
        action="device_secret_rotated",
        entity_type="device",
        entity_id=device.id,
        after={"android_id": device.android_id, "vehicle_id": device.vehicle_id},
    )

    await session.commit()
    await session.refresh(device)
    return device, secret


# --- pairing-code issuance + consumption -------------------------------------


async def generate_pairing_code(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> DevicePairingCode:
    await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    # Lazy garbage collection of spent codes, on the same lazy-maintenance
    # pattern the rest of this backend uses rather than a background timer (see
    # app.services.lazy_maintenance): a pairing code that has been consumed or
    # has expired can never be presented successfully again, so keeping it is
    # pure accumulation -- one dead row per tablet per re-pair, forever
    # (backend audit §3 G10). Minting is the natural moment because it is
    # admin-triggered, already writing, and low-frequency.
    #
    # Consumed rows are kept for `_PAIRING_CODE_RETENTION_DAYS` rather than
    # dropped the instant they are used: `used_by_device_id` is the only record
    # of which admin-issued code enrolled which tablet, and that is worth having
    # while a commissioning is still fresh enough for someone to ask about it.
    # Expired-but-never-used rows carry no such history and go immediately.
    now = datetime.now(UTC)
    await session.execute(
        delete(DevicePairingCode).where(
            DevicePairingCode.tenant_id == tenant_id,
            or_(
                DevicePairingCode.used_at < now - timedelta(days=_PAIRING_CODE_RETENTION_DAYS),
                and_(DevicePairingCode.used_at.is_(None), DevicePairingCode.expires_at < now),
            ),
        )
    )

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
    android_id: str,
    pairing_code: str,
    model: str | None,
    app_version: str | None,
) -> tuple[Device, str]:
    """Consumes a not-yet-used, not-expired pairing code and binds (creating if
    necessary) the device identified by `android_id` to that code's vehicle.
    Returns the device and the plaintext secret it must keep.

    Re-registering an already-known android_id (e.g. a device swapped to a
    different car) is allowed — it just re-binds the existing Device row rather
    than erroring, since that's the realistic "device gets moved" case. It also
    mints a FRESH secret, invalidating the old one: re-pairing is the documented
    recovery for a tablet that has lost its credential, and it would not be a
    recovery if the old secret kept working.

    The tenant comes from the pairing-code row, NOT from a caller's token. This
    changed when device registration became the gate a tablet must pass before
    anyone can log into the meter: a tablet that has never been paired has
    nobody logged into it, so there is no token to take a tenant from, and the
    code -- admin-minted, tenant-scoped, single-use, 15-minute TTL -- is the
    credential. Callers therefore no longer supply `tenant_id`; a code from
    tenant A can only ever enrol a device into tenant A.

    Registering also clears `revoked_at`: an operator handing out a fresh
    pairing code for a tablet they previously retired is un-retiring it, and
    leaving the row revoked would silently 404 every subsequent heartbeat.
    """
    result = await session.execute(
        select(DevicePairingCode).where(
            DevicePairingCode.code == pairing_code,
            DevicePairingCode.used_at.is_(None),
        )
    )
    pairing = result.scalar_one_or_none()
    if pairing is None:
        raise InvalidPairingCodeError("Pairing code not found or already used")
    if _is_expired(pairing.expires_at):
        raise InvalidPairingCodeError("Pairing code has expired")

    tenant_id = pairing.tenant_id
    result = await session.execute(
        select(Device).where(Device.tenant_id == tenant_id, Device.android_id == android_id)
    )
    device = result.scalar_one_or_none()
    is_new = device is None
    if device is None:
        device = Device(tenant_id=tenant_id, android_id=android_id)
        session.add(device)

    # Captured BEFORE the rebind below, because it is the whole content of the
    # audit record: which car this tablet was in when the pairing code was
    # burned. `is_new` distinguishes a first enrolment from a re-pair; a
    # re-pair onto a DIFFERENT vehicle is the one that matters.
    previous_vehicle_id = device.vehicle_id
    was_revoked = device.revoked_at is not None

    device.vehicle_id = pairing.vehicle_id
    if model is not None:
        device.model = model
    if app_version is not None:
        device.app_version = app_version
    now = datetime.now(UTC)
    device.last_seen_at = now
    device.paired_at = now
    device.revoked_at = None

    secret, secret_hash = mint_device_secret()
    device.device_secret_hash = secret_hash

    pairing.used_at = now
    await session.flush()  # so device.id is populated before we reference it below
    pairing.used_by_device_id = device.id

    # AUDIT (backend audit §3 G1). Re-registration silently reassigned
    # `vehicle_id` and wrote nothing anywhere: a tablet could be moved from car
    # to car leaving no trace at all, and the only thing that ever noticed was
    # the ADVISORY, non-blocking cross-check at shift start
    # (app.services.shift, `shift_device_vehicle_mismatch`) -- which by
    # construction fires only if someone starts a shift on the OLD car. Pairing
    # is a commissioning event; it belongs in the tamper-evident chain like
    # every other one.
    #
    # `actor_user_id` is None and that is honest rather than lazy: this route
    # has no bearer token by design (the pairing code IS the credential), so
    # there is no authenticated human to name. The admin who minted the code is
    # recoverable from the DevicePairingCode row this references.
    await record_audit(
        session,
        tenant_id=tenant_id,
        actor_user_id=None,
        action="device_registered" if is_new else "device_repaired",
        entity_type="device",
        entity_id=device.id,
        before=None if is_new else {"vehicle_id": previous_vehicle_id, "revoked": was_revoked},
        after={
            "android_id": android_id,
            "vehicle_id": pairing.vehicle_id,
            "pairing_code_id": pairing.id,
            # Called out explicitly rather than left to be inferred by diffing
            # the two dicts, so a reviewer scanning the log for tablets that
            # changed car can filter on one boolean.
            "vehicle_changed": (not is_new) and previous_vehicle_id != pairing.vehicle_id,
            "secret_rotated": True,
        },
    )

    await session.commit()
    await session.refresh(device)
    return device, secret


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


async def record_locate_response(
    session: AsyncSession,
    device: Device,
    *,
    lat: float,
    lng: float,
    accuracy_m: float | None,
) -> Device:
    """Stores a device's answer to a locate request, and CLEARS the flag.

    The clearing is the part that was missing. `locate_requested` was set by an
    admin and read back by the tablet, but nothing anywhere ever set it back to
    false -- so the dashboard's badge said "Pending" for the life of the row,
    whether the device had answered or not, and an operator had no way to tell a
    tablet that reported from one that was in a drawer with a flat battery.

    Recorded on the DEVICE, not on a vehicle. The old answer path published a
    vehicle position, which needs a live driver session and a current vehicle
    binding -- neither of which a parked, logged-off tablet has, and that tablet
    is precisely what someone reaching for "locate" is trying to find.
    """
    device.last_locate_lat = lat
    device.last_locate_lng = lng
    device.last_locate_accuracy_m = accuracy_m
    device.last_locate_at = datetime.now(UTC)
    device.locate_requested = False
    await session.commit()
    await session.refresh(device)
    return device


async def record_command_ack(session: AsyncSession, device: Device, *, command: str) -> Device:
    """Records that a device acted on a queued command, and clears its flag.

    Same missing-clear problem `locate` and `reboot` were already fixed for: an
    admin could queue a command and watch it read "Pending" forever, with no way
    to know whether the tablet had ever seen it. This function understood only
    `restart`, so `force_update_pending` and `kiosk_locked` -- half the commands
    an admin can issue -- still had no ack path at all (backend audit §3 G9).

    Two shapes of command, and the difference is deliberate:

    * `restart` and `force_update` are ONE-SHOT REQUESTS. Their flags mean "do
      this thing", so the acknowledgement clears them -- otherwise the device
      would restart or re-install on every subsequent heartbeat forever.
    * `kiosk_lock` is a DESIRED STATE. `kiosk_locked=True` means "this tablet
      should be in kiosk mode", which stays true after the tablet enters it, so
      acknowledging must NOT clear it -- doing so would unlock every tablet the
      moment it confirmed it had locked. The ack is recorded on
      `last_acked_command` / `command_acked_at` instead, which is what lets a
      dashboard distinguish "locked, tablet confirmed" from "locked, tablet
      never came back".

    `last_acked_command` exists because a bare `command_acked_at` was
    unambiguous only while `restart` was the sole ack-able command; with three
    of them sharing one timestamp, an admin queuing a force-update would read a
    restart acked an hour earlier as "the update landed".
    """
    if command == "restart":
        device.reboot_requested = False
    elif command == "force_update":
        device.force_update_pending = False
    device.last_acked_command = command
    device.command_acked_at = datetime.now(UTC)
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
