"""Pydantic v2 schemas for the fleet domain (vehicles + devices)."""
from __future__ import annotations

from datetime import date, datetime
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator

VehicleClass = Literal["standard", "premium", "maxi", "wat"]
VehicleStatus = Literal["active", "maintenance", "suspended", "retired"]


# --- Pagination (local to this domain, same shape as app.schemas.tariffs.Page,
# until a shared one exists in app.core) -----------------------------------------

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    total: int
    skip: int
    limit: int


# --- Vehicle ----------------------------------------------------------------------


class VehicleBase(BaseModel):
    rego: str = Field(min_length=1, max_length=20)
    vin: str | None = Field(default=None, max_length=32)
    vehicle_class: VehicleClass = "standard"
    camera_serial: str | None = Field(default=None, max_length=100)
    tracking_device_id: str | None = Field(default=None, max_length=100)
    meter_device_id: str | None = Field(default=None, max_length=100)
    status: VehicleStatus = "active"
    # Compliance-expiry tracking (blueprint 7.2.4/10.1). Null means "unknown,
    # not expired" — see app.models.fleet.Vehicle's doc comment for the
    # fail-open convention and app.services.compliance_expiry for the
    # alerting logic and GET /v1/fleet/compliance-expiry for the dashboard
    # listing.
    registration_expiry: date | None = Field(default=None)
    insurance_expiry: date | None = Field(default=None)

    @field_validator("rego")
    @classmethod
    def _normalize_rego(cls, v: str) -> str:
        v = v.strip().upper()
        if not v:
            raise ValueError("rego must not be blank")
        return v


class VehicleCreate(VehicleBase):
    pass


class VehicleUpdate(BaseModel):
    """Partial update — every field optional."""

    rego: str | None = Field(default=None, min_length=1, max_length=20)
    vin: str | None = None
    vehicle_class: VehicleClass | None = None
    camera_serial: str | None = None
    tracking_device_id: str | None = None
    meter_device_id: str | None = None
    status: VehicleStatus | None = None
    registration_expiry: date | None = None
    insurance_expiry: date | None = None

    @field_validator("rego")
    @classmethod
    def _normalize_rego(cls, v: str | None) -> str | None:
        if v is None:
            return v
        v = v.strip().upper()
        if not v:
            raise ValueError("rego must not be blank")
        return v


class VehicleRead(VehicleBase):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    created_at: datetime
    updated_at: datetime


# --- Device -----------------------------------------------------------------------


class DeviceBase(BaseModel):
    android_id: str = Field(min_length=1, max_length=100)
    model: str | None = Field(default=None, max_length=100)
    app_version: str | None = Field(default=None, max_length=30)
    vehicle_id: str | None = None
    kiosk_locked: bool = False


class DeviceCreate(DeviceBase):
    pass


class DeviceUpdate(BaseModel):
    """Partial update — every field optional. `android_id` is not changeable
    after creation (it identifies the physical unit); re-pair via
    `POST /v1/fleet/devices/register` instead."""

    model: str | None = None
    app_version: str | None = None
    vehicle_id: str | None = None
    kiosk_locked: bool | None = None


class DeviceRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    tenant_id: str
    android_id: str
    model: str | None
    app_version: str | None
    vehicle_id: str | None
    kiosk_locked: bool
    force_update_pending: bool
    locate_requested: bool
    reboot_requested: bool
    last_seen_at: datetime | None
    battery: int | None
    network: str | None
    created_at: datetime
    updated_at: datetime


# --- Device pairing / heartbeat / admin flag endpoints -----------------------------


class PairingCodeRead(BaseModel):
    """Response for `POST /v1/fleet/vehicles/{id}/pairing-code` — encode this
    (or a QR of it) for the device to scan/enter."""

    model_config = ConfigDict(from_attributes=True)

    code: str
    vehicle_id: str
    tenant_id: str
    expires_at: datetime


class DeviceRegisterRequest(BaseModel):
    """What the device presents to bind itself to a vehicle via QR-pairing."""

    android_id: str = Field(min_length=1, max_length=100)
    pairing_code: str = Field(min_length=4, max_length=12)
    model: str | None = Field(default=None, max_length=100)
    app_version: str | None = Field(default=None, max_length=30)


class DeviceHeartbeatRequest(BaseModel):
    battery: int | None = Field(default=None, ge=0, le=100)
    network: str | None = Field(default=None, max_length=20)
    app_version: str | None = Field(default=None, max_length=30)


class KioskLockRequest(BaseModel):
    enabled: bool = True


class ForceUpdateRequest(BaseModel):
    enabled: bool = True


class LocateRequest(BaseModel):
    enabled: bool = True


class RebootRequest(BaseModel):
    """See the HONESTY NOTE on `Device.reboot_requested` — setting `enabled`
    queues a reboot request the device can read back; it does not itself
    reboot anything."""

    enabled: bool = True


class VerifyAdminPinRequest(BaseModel):
    """Body for `POST /v1/fleet/devices/{id}/verify-admin-pin` — same PIN
    shape as `app.schemas.tenant.AdminPinSetRequest`."""

    pin: str = Field(min_length=4, max_length=8, pattern=r"^\d{4,8}$")


class VerifyAdminPinResponse(BaseModel):
    """`configured=False` means the tenant has never set an admin PIN — kept
    distinct from `valid=False` (a PIN is set but this one is wrong) so a
    device can tell "nothing set up yet" from "wrong PIN" instead of treating
    both the same way. See app.services.tenant.verify_admin_pin."""

    valid: bool
    configured: bool


# --- Compliance expiry (blueprint 7.2.3/7.2.4/10.1) --------------------------
# Response shape for `GET /v1/fleet/compliance-expiry` — see
# app.services.compliance_expiry.list_compliance_expiry for how this is built.
# Not a direct read of any one ORM row: each item represents ONE expiring/
# expired field on either a driver (app.models.user.User) or a vehicle
# (app.models.fleet.Vehicle), so a driver/vehicle with two lapsed fields (e.g.
# both licence and authority) produces two separate items.

ComplianceExpiryEntityType = Literal["driver", "vehicle"]
ComplianceExpiryField = Literal[
    "driver_license_expiry", "driver_authority_expiry", "registration_expiry", "insurance_expiry"
]
ComplianceExpiryStatus = Literal["expiring_soon", "expired"]


class ComplianceExpiryItem(BaseModel):
    entity_type: ComplianceExpiryEntityType
    entity_id: str
    # Driver name or vehicle rego — for the dashboard to render without a
    # second lookup.
    label: str
    field: ComplianceExpiryField
    expiry_date: date
    status: ComplianceExpiryStatus
    # Negative once past expiry_date (e.g. -5 means "expired 5 days ago").
    days_remaining: int
