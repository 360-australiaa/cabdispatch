"""Pydantic v2 schemas for the physical duress device (CT-DPD-01) domain --
device provisioning/admin CRUD, the device auth handshake, and the
device-path ingest endpoints (alarm/gps/heartbeat). See
docs/DURESS_DEVICE_INTEGRATION.md Section 3 for the full wire contract this
mirrors, and app.schemas.duress for the tablet-side DuressEvent schemas this
domain correlates into.
"""
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

# --- admin provisioning / CRUD (owner/admin/dispatcher only) ----------------


class DuressDeviceCreate(BaseModel):
    """Body for POST /v1/duress-devices -- provisions a new device row.
    plaintext_secret is the K_dev shared secret already burned into the
    unit's firmware at manufacture; it is Fernet-encrypted at rest (see
    app.core.crypto) and never returned by any read endpoint again after
    this call -- copy it down now."""

    device_code: str = Field(min_length=1, max_length=64)
    vehicle_id: str | None = None
    phone_number: str | None = Field(default=None, max_length=32)
    plaintext_secret: str = Field(
        min_length=16,
        max_length=200,
        description="The device's shared secret (K_dev), as provisioned into firmware. "
        "Encrypted at rest immediately; not retrievable after creation.",
    )


class DuressDeviceUpdate(BaseModel):
    vehicle_id: str | None = None
    phone_number: str | None = Field(default=None, max_length=32)
    active: bool | None = None


class DuressDeviceRotateSecret(BaseModel):
    """Body for POST /v1/duress-devices/{id}/rotate-secret -- re-provisioning
    flow when a device's firmware is re-flashed with a new K_dev."""

    plaintext_secret: str = Field(min_length=16, max_length=200)


class DuressDeviceRead(BaseModel):
    """Never includes the secret (encrypted or plaintext) -- see module docstring."""

    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    device_code: str
    vehicle_id: str | None
    phone_number: str | None
    battery_pct: int | None
    on_battery: bool
    gnss_fix: bool
    signal_csq: int | None
    firmware_version: str | None
    last_seen_at: datetime | None
    active: bool
    created_at: datetime
    updated_at: datetime


class DuressDeviceListResponse(BaseModel):
    items: list[DuressDeviceRead]
    total: int
    limit: int
    offset: int


# --- device auth handshake (POST /v1/devices/auth) ---------------------------


class DeviceAuthRequest(BaseModel):
    """Body for POST /v1/devices/auth -- the device proves it holds K_dev by
    HMAC-signing a nonce it generated itself, without ever transmitting the
    secret. hmac_hex = HMAC-SHA256(K_dev, nonce).hexdigest(). See
    app.services.duress_device.authenticate_device."""

    device_code: str
    tenant_id: str
    nonce: str = Field(min_length=8, max_length=64)
    hmac_hex: str = Field(min_length=64, max_length=64)


class DeviceAuthResponse(BaseModel):
    device_token: str
    expires_in_minutes: int
    device_id: str


# --- device-path duress ingest (bearer: device_token) -------------------------


class DeviceAlarmRequest(BaseModel):
    """Body for POST /v1/duress/device/alarm -- the device's OWN alarm open,
    fully independent of the tablet. See
    app.services.duress_device.open_or_attach_device_alarm for how this
    correlates with (or opens) the shared DuressEvent."""

    vehicle_id: str
    driver_id: str | None = Field(
        default=None,
        description="May be unknown to the device itself; if omitted, the "
        "correlation logic fills it in from a matching tablet-side event, or "
        "leaves it blank for dispatch to confirm.",
    )
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    battery_pct: int | None = Field(default=None, ge=0, le=100)
    trigger_source: str = Field(default="button", description="button | tamper | man_down")
    device_event_id: str | None = Field(
        default=None, description="The device's own local incident counter, for its BLE Panic payload to reference."
    )


class DeviceAlarmResponse(BaseModel):
    event_id: str
    source: str


class DeviceGpsFix(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    speed_kmh: float | None = Field(default=None, ge=0)
    accuracy_m: float | None = Field(default=None, ge=0)
    ts: datetime | None = None


class DeviceGpsBatch(BaseModel):
    """Body for POST /v1/duress/device/{event_id}/gps -- devices buffer
    offline and flush in batches on reconnect (see integration contract
    Section 3.3), unlike the tablet's one-point-per-call
    POST /v1/duress/{event_id}/gps."""

    points: list[DeviceGpsFix] = Field(min_length=1, max_length=500)


class DeviceHeartbeatRequest(BaseModel):
    """Body for POST /v1/devices/{device_id}/heartbeat -- idle-time health
    reporting, independent of any open duress event."""

    battery_pct: int | None = Field(default=None, ge=0, le=100)
    on_battery: bool = False
    gnss_fix: bool = False
    signal_csq: int | None = Field(default=None, ge=0, le=31)
    firmware_version: str | None = Field(default=None, max_length=30)