"""Device-path duress domain business logic: the device auth handshake
(HMAC challenge -> short-lived device JWT), the alarm-open/correlation logic
that merges a physical CT-DPD-01 device's own alarm with a tablet-side
`DuressEvent` (or opens a brand-new one), device-path GPS ingest into the
same `GPSBroadcaster` the tablet path uses, and device heartbeat/admin-CRUD
helpers. See `docs/DURESS_DEVICE_INTEGRATION.md` for the full wire contract
and `app.services.duress` for the tablet-side state machine this domain
correlates into (imported directly below, never re-implemented).
"""
from __future__ import annotations

import hashlib
import hmac
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import uuid4

from jose import jwt
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.crypto import SecretDecryptionError, decrypt_secret, encrypt_secret
from app.models.duress import DURESS_TERMINAL_STATUSES, DURESS_TRIGGER_BUTTON, DuressEvent
from app.models.duress_device import DuressDevice
from app.schemas.duress_device import (
    DeviceAlarmRequest,
    DeviceGpsFix,
    DeviceHeartbeatRequest,
    DuressDeviceCreate,
)
from app.services.duress import gps_broadcaster, trigger_event

DEVICE_TOKEN_TYPE = "duress_device"


class DeviceAuthError(Exception):
    """Raised on a bad device_code, unknown/inactive device, tenant mismatch,
    HMAC mismatch, or a token that is not a duress_device-typed token. The
    router translates every one of these to an HTTP 401 without distinguishing
    the cause, so as not to leak which part of the handshake failed."""


class DeviceCodeConflictError(Exception):
    """Raised by create_device when device_code is already in use for this
    tenant (uq_duress_devices_tenant_device_code) -- the router translates
    this to an HTTP 409. Found as a real bug during live verification: with
    no constraint, two devices sharing a device_code made
    authenticate_device raise MultipleResultsFound (a 500) instead of this
    clean, actionable conflict at creation time."""


def hmac_hex(secret: str, nonce: str) -> str:
    """HMAC-SHA256(secret, nonce), hex-encoded -- the same computation the
    device firmware performs with its own copy of K_dev (see
    DeviceAuthRequest's docstring in app.schemas.duress_device)."""
    return hmac.new(secret.encode("utf-8"), nonce.encode("utf-8"), hashlib.sha256).hexdigest()


async def authenticate_device(
    session: AsyncSession,
    *,
    tenant_id: str,
    device_code: str,
    nonce: str,
    hmac_hex_supplied: str,
) -> DuressDevice:
    """Verifies a device's POST /v1/devices/auth HMAC challenge and returns
    its DuressDevice row.

    *** MVP / Phase-1 server-side check ONLY. *** This is deliberately a
    simplified HTTP-layer auth, NOT the full BLE-side replay-protected
    HMAC+counter scheme documented in docs/DURESS_DEVICE_INTEGRATION.md for
    the device<->tablet BLE GATT link -- that is a separate, device-firmware-
    facing protocol this function does not implement or claim to implement.
    Here, replay resistance comes from the nonce being caller-supplied fresh
    on every attempt (a replayed nonce+hmac pair only ever proves the same
    fact twice) plus ordinary TLS transport security protecting the request
    in flight. There is no server-side nonce/counter ledger in this pass --
    that is an intentional, documented simplification, not an oversight.
    """
    result = await session.execute(
        select(DuressDevice).where(
            DuressDevice.tenant_id == tenant_id,
            DuressDevice.device_code == device_code,
            DuressDevice.active.is_(True),
        )
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise DeviceAuthError("Unknown, inactive, or wrong-tenant device_code")

    try:
        secret = decrypt_secret(device.secret_encrypted)
    except SecretDecryptionError as exc:
        raise DeviceAuthError("Could not verify device secret") from exc

    expected = hmac_hex(secret, nonce)
    if not hmac.compare_digest(expected, hmac_hex_supplied):
        raise DeviceAuthError("HMAC mismatch")

    return device


def mint_device_token(*, device_id: str, tenant_id: str) -> str:
    """Issues a short-lived duress_device-typed JWT (see DEVICE_TOKEN_TYPE),
    signed with the same settings.JWT_SECRET/JWT_ALGORITHM as human tokens --
    following TOKEN_TYPE_MFA's precedent in app.core.security of using the
    "type" claim, not a separate key, to keep token families apart."""
    now = datetime.now(UTC)
    payload: dict[str, Any] = {
        "device_id": device_id,
        "tenant_id": tenant_id,
        "type": DEVICE_TOKEN_TYPE,
        "iat": now,
        "exp": now + timedelta(minutes=settings.DURESS_DEVICE_JWT_EXPIRE_MINUTES),
        "jti": str(uuid4()),
    }
    return jwt.encode(payload, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)


def decode_device_token(token: str) -> dict[str, Any]:
    """Decodes + verifies a device bearer token. Raises jose.JWTError on a
    bad signature/expiry (left to propagate -- the router catches it), and
    DeviceAuthError if the token decodes fine but is not duress_device-typed
    (e.g. a human access token presented here) -- a human token must NEVER
    satisfy this check, by construction of this type-claim assertion."""
    payload = jwt.decode(token, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM])
    if payload.get("type") != DEVICE_TOKEN_TYPE:
        raise DeviceAuthError("Not a device token")
    return payload


# --- alarm open / correlation -------------------------------------------------


def _append_device_log_entry(event: DuressEvent, entry: dict) -> None:
    """Same replace-the-dict-wholesale pattern as
    app.services.duress._append_log_entry (private there, so replicated here
    rather than imported -- see that function's docstring for why a fresh
    dict is required on every write for SQLAlchemy's change tracking to pick
    it up)."""
    current = event.escalation_log_json or {}
    event.escalation_log_json = {
        **current,
        "entries": [*current.get("entries", []), entry],
    }


async def open_or_attach_device_alarm(
    session: AsyncSession,
    *,
    tenant_id: str,
    device: DuressDevice,
    body: DeviceAlarmRequest,
) -> DuressEvent:
    """Correlates a device-side alarm with the shared per-vehicle DuressEvent.

    No "recent" time window is applied -- any currently-open (non-terminal)
    event for this vehicle is treated as the same incident, however long it
    has been open. This is the simplest correct version for this pass: a
    vehicle should only ever have one open duress incident at a time in
    practice, so there is no ambiguity a time window would need to resolve.

    Three cases, in order:
      a. This device already attached to (or opened) an open event for this
         vehicle -> return it unchanged (idempotent retry).
      b. A tablet already opened an open event for this vehicle with no
         device attached yet -> attach this device, flip source to "both".
      c. Otherwise -> open a brand-new event via trigger_event, sourced
         from this device.
    """
    result = await session.execute(
        select(DuressEvent).where(
            DuressEvent.tenant_id == tenant_id,
            DuressEvent.vehicle_id == body.vehicle_id,
            DuressEvent.device_id == device.id,
            DuressEvent.status.notin_(DURESS_TERMINAL_STATUSES),
        )
    )
    existing_for_device = result.scalar_one_or_none()
    if existing_for_device is not None:
        return existing_for_device

    result = await session.execute(
        select(DuressEvent).where(
            DuressEvent.tenant_id == tenant_id,
            DuressEvent.vehicle_id == body.vehicle_id,
            DuressEvent.device_id.is_(None),
            DuressEvent.status.notin_(DURESS_TERMINAL_STATUSES),
        )
    )
    tablet_opened = result.scalar_one_or_none()
    now = datetime.now(UTC)

    if tablet_opened is not None:
        tablet_opened.device_id = device.id
        tablet_opened.source = "both"
        _append_device_log_entry(
            tablet_opened,
            {
                "stage": "device_attached",
                "at": now.isoformat(),
                "detail": f"device {device.device_code} attached to existing tablet-opened event",
                "device_event_id": body.device_event_id,
            },
        )
        await session.commit()
        await session.refresh(tablet_opened)
        return tablet_opened

    event = await trigger_event(
        session,
        tenant_id=tenant_id,
        vehicle_id=body.vehicle_id,
        driver_id=body.driver_id or "unknown",
        trigger=DURESS_TRIGGER_BUTTON,
        gps_stream_ref=None,
        audio_ref=None,
    )
    event.device_id = device.id
    event.source = "device"
    _append_device_log_entry(
        event,
        {
            "stage": "device_opened",
            "at": now.isoformat(),
            "detail": f"device {device.device_code} opened this event directly",
            "device_event_id": body.device_event_id,
        },
    )
    await session.commit()
    await session.refresh(event)
    return event


# --- GPS ingest ----------------------------------------------------------------


async def ingest_device_gps(event: DuressEvent, points: list[DeviceGpsFix]) -> int:
    """Publishes each buffered fix to the same GPSBroadcaster the tablet path
    uses, tagged source="device" (mirroring POST /v1/duress/{id}/gps's
    source="tablet" tag in app.api.v1.duress) so a listener on
    WS /v1/duress/{event_id}/live can tell the two traces apart on one map.
    Returns the total delivered-to count summed across all points."""
    delivered_total = 0
    for point in points:
        payload = point.model_dump(mode="json")
        payload["ts"] = payload.get("ts") or datetime.now(UTC).isoformat()
        payload["event_id"] = event.id
        payload["source"] = "device"
        delivered_total += await gps_broadcaster.publish(event.id, payload)
    return delivered_total


# --- heartbeat -----------------------------------------------------------------


async def record_heartbeat(
    session: AsyncSession, device: DuressDevice, body: DeviceHeartbeatRequest
) -> DuressDevice:
    """Idle-time health snapshot, independent of any open duress event.
    on_battery/gnss_fix are plain non-optional bools on the schema (default
    False), so they are always set; the remaining fields only overwrite the
    stored value when the device actually reported one."""
    if body.battery_pct is not None:
        device.battery_pct = body.battery_pct
    device.on_battery = body.on_battery
    device.gnss_fix = body.gnss_fix
    if body.signal_csq is not None:
        device.signal_csq = body.signal_csq
    if body.firmware_version is not None:
        device.firmware_version = body.firmware_version
    device.last_seen_at = datetime.now(UTC)

    await session.commit()
    await session.refresh(device)
    return device


# --- admin CRUD ------------------------------------------------------------


async def create_device(
    session: AsyncSession, *, tenant_id: str, body: DuressDeviceCreate
) -> DuressDevice:
    """Provisions a new device row. body.plaintext_secret is Fernet-encrypted
    immediately (see app.core.crypto) -- never stored or logged in plaintext.
    Raises DeviceCodeConflictError (-> HTTP 409 at the router) if device_code
    is already in use for this tenant, rather than letting the IntegrityError
    bubble up as an unhandled 500 -- same try/commit/except IntegrityError
    pattern as app.api.v1.trips's client_uuid conflict handling."""
    device = DuressDevice(
        tenant_id=tenant_id,
        device_code=body.device_code,
        vehicle_id=body.vehicle_id,
        phone_number=body.phone_number,
        secret_encrypted=encrypt_secret(body.plaintext_secret),
    )
    session.add(device)
    try:
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise DeviceCodeConflictError(
            f"device_code {body.device_code!r} already in use for this tenant"
        ) from exc
    await session.refresh(device)
    return device


async def rotate_device_secret(
    session: AsyncSession, device: DuressDevice, plaintext_secret: str
) -> DuressDevice:
    """Re-provisioning flow: replaces the stored (encrypted) shared secret
    when a device's firmware is re-flashed with a new K_dev."""
    device.secret_encrypted = encrypt_secret(plaintext_secret)
    await session.commit()
    await session.refresh(device)
    return device
