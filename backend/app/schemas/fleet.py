"""Pydantic v2 schemas for the fleet domain (vehicles + devices)."""
from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
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
    make: str | None = Field(default=None, max_length=60)
    model: str | None = Field(default=None, max_length=60)
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
    make: str | None = None
    model: str | None = None
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
    # Meter re-verification due-date (operations-cycle tracking pass). Null
    # means "unknown", not "expired" — see app.models.fleet.Device's doc
    # comment for the fail-open convention and
    # app.services.compliance_expiry for the alerting logic.
    calibration_due: date | None = Field(default=None)


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
    calibration_due: date | None = None


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
    calibration_due: date | None
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

ComplianceExpiryEntityType = Literal["driver", "vehicle", "device"]
ComplianceExpiryField = Literal[
    "driver_license_expiry",
    "driver_authority_expiry",
    "registration_expiry",
    "insurance_expiry",
    "calibration_due",
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


# --- Lifetime cumulative-totals register (operations-cycle tracking pass) ---
# Response shape for `GET /v1/fleet/vehicles/{id}/lifetime-totals` — a
# read-only SUM aggregation across every CLOSED Trip ever recorded against
# this vehicle, mirroring the classic statutory cumulative-totals register a
# physical taxi meter keeps (cl 14-style evidence). No new storage; this is
# entirely computed from the existing `trips` table on every request. See
# app.services.fleet_reports.vehicle_lifetime_totals.


class VehicleLifetimeTotals(BaseModel):
    vehicle_id: str
    trip_count: int
    total_fares: Decimal
    total_psl: Decimal
    total_tolls: Decimal
    # GAP (flagged per task instructions): `app.models.trips.Trip` has no
    # tips column anywhere in this codebase as of this pass — there is no
    # real number to sum. Always null rather than a fabricated 0.00, so a
    # consumer can tell "no tips field exists yet" apart from "tips exist and
    # total zero". Wire this up for real the moment a tips field lands on
    # Trip.
    total_tips: Decimal | None
    total_km: Decimal
    generated_at: datetime


# --- Shift history (past-shifts-per-vehicle pass) ---------------------------
# Response shape for `GET /v1/fleet/vehicles/{id}/shift-history` -- "which
# drivers has this vehicle had", not just the live current one (that's
# app.schemas.live_ops.VehicleLiveRead.current_driver_*, derived the same
# "no cached pointer, always live off the shifts table" way). See
# app.services.fleet.list_vehicle_shift_history.


class VehicleShiftHistoryItem(BaseModel):
    """One row of `GET /v1/fleet/vehicles/{id}/shift-history` -- a past (or
    currently open) `Shift` (owned by the sibling shift domain) run on this
    vehicle, with the driver's display name joined in so a dashboard doesn't
    need a second lookup. Newest-first (start_at DESC)."""

    shift_id: str
    driver_id: str
    driver_name: str | None = Field(
        default=None, description="Display name for driver_id -- None only if the driver's User row is gone."
    )
    start_at: datetime
    end_at: datetime | None = Field(default=None, description="None means this shift is still open.")
    distance_km: Decimal = Field(description="Shift.km_total -- recomputed server-side at shift close.")
    fare_total: Decimal = Field(
        description="Shift.cash_total + Shift.card_total -- total takings recorded for this shift."
    )


# --- Pilot-report evidence pack (operations-cycle tracking pass) ------------
# Response shape for `GET /v1/fleet/vehicles/{id}/pilot-report`. See
# app.services.fleet_reports.vehicle_pilot_report for the exact
# simplifications (documented per-field below and in that function's
# docstring).


class VehiclePilotReport(BaseModel):
    vehicle_id: str
    from_date: date
    to_date: date
    trip_count: int
    # None when zero trips in range carry a non-null variance_pct (nothing to
    # average).
    avg_fare_accuracy_variance_pct: Decimal | None
    # SIMPLIFICATION (flagged per task instructions): `Device` persists only
    # a single, overwritten `last_seen_at` timestamp — there is no heartbeat
    # log to compute a true "% of the requested window the device was
    # reachable" from. This is a coarse recency proxy instead: 100 if the
    # vehicle's most-recently-seen paired device last heartbeat within
    # `UPTIME_STALENESS_HOURS` of `to_date`'s end, else 0. None if the
    # vehicle has no paired device at all, or that device has never sent a
    # heartbeat. See app.services.fleet_reports for the exact constant and
    # reasoning — do not read this as a real uptime percentage.
    device_uptime_estimate_pct: Decimal | None
    # GAP (flagged per task instructions): `app.models.duress.DuressEvent`
    # has no test_activation (or equivalent drill/test-mode) field as of this
    # pass — checked directly, it is genuinely absent, not just unwired.
    # Always null rather than a fabricated count.
    duress_test_activation_count: int | None
    # Real, non-fabricated bonus context (not part of the original spec):
    # total duress events of ANY kind for this vehicle in range, since that
    # number IS available even though the "test activation" subset isn't.
    duress_event_count_total: int
    flagged_for_review_count: int
    generated_at: datetime
