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


def _plausible_expiry(value: date | None) -> date | None:
    """Reject dates no document can carry (WRITE side only).

    Seen on the tablet, 2026-09-08: a compliance card reading "EXPIRED
    0028-02-09". `date` happily holds year 28, the API accepted it, and every
    reader since faithfully displayed it. Years outside 1900-2200 are a typing
    slip, not a document, and are refused at the one place they can be fixed.

    Deliberately NOT on the Read models: a row already saved with a bad year
    must still be readable (and therefore correctable), never turned into a
    500 on every list that includes it -- which is exactly what putting this on
    the shared Base did on first attempt.
    """
    if value is not None and not (1900 <= value.year <= 2200):
        raise ValueError(f"expiry year {value.year} is not plausible; use a four-digit year between 1900 and 2200")
    return value


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

    @field_validator("registration_expiry", "insurance_expiry", mode="after")
    @classmethod
    def _check_expiry(cls, value: date | None) -> date | None:
        return _plausible_expiry(value)


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

    @field_validator("registration_expiry", "insurance_expiry", mode="after")
    @classmethod
    def _check_expiry(cls, value: date | None) -> date | None:
        return _plausible_expiry(value)


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
    # Retire (or un-retire) a tablet without deleting its history. A revoked
    # device's heartbeat 404s, which the meter app already reads as "this tablet
    # is no longer registered" -- so revoking is how an operator takes a tablet
    # out of service and puts it back behind the readiness gate. Re-pairing it
    # with a fresh code clears the flag (see fleet_service.register_device).
    revoked: bool | None = None


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
    # When this tablet last completed a real pairing-code enrolment, and when an
    # operator retired it. `paired_at` is NOT interchangeable with
    # `last_seen_at`: a manually-provisioned row has never paired, and a paired
    # tablet switched off for a week still has. `None` on a row predating these
    # columns means "not recorded", not "never paired".
    paired_at: datetime | None = None
    revoked_at: datetime | None = None
    # The device's own last reported position, and when it last acted on a
    # queued command. Both are how a dashboard can show that a remote command
    # was actually carried out instead of a permanent "Pending".
    last_locate_lat: float | None = None
    last_locate_lng: float | None = None
    last_locate_accuracy_m: float | None = None
    last_locate_at: datetime | None = None
    command_acked_at: datetime | None = None
    # WHICH command that acknowledgement was for -- "restart", "force_update" or
    # "kiosk_lock". Read it together with `command_acked_at`: a timestamp on its
    # own cannot say which of three commands landed, and showing a force-update
    # as confirmed because a restart was acked an hour ago is exactly the kind
    # of quietly-wrong status this field exists to prevent. `None` means no
    # acknowledgement has been recorded since this field existed.
    last_acked_command: str | None = None
    created_at: datetime
    updated_at: datetime
    # Not a Device column -- populated only by POST /devices/{id}/heartbeat
    # (see app/api/v1/fleet.py's device_heartbeat), which sets it from the
    # current GET /v1/app-releases/latest answer so a device learns about an
    # available update on its existing 60s poll without a second network
    # round-trip. `None` on every other DeviceRead response (plain CRUD
    # reads never populate it) and also `None` here if no active release has
    # ever been published -- never treat `None` as "you are up to date",
    # only as "no hint was computed this response".
    latest_version_code: int | None = None

    # NOT a Device column either -- populated only by POST /devices/{id}/heartbeat, joined from
    # `vehicle_id`'s own Vehicle row. Real bug found live (2026-09-09): the Android client's
    # `decideVehicleRebind` self-heal (see that file's doc) used to adopt this row's `vehicle_id`
    # into the driver's session the moment it disagreed with the session's current binding, with
    # no way to tell "the depot re-seeded the same car under a new uuid" (heal it) apart from "the
    # driver deliberately bound to a different vehicle than this tablet's admin-configured pairing"
    # (leave it alone -- that is the exact "Check tablet placement" scenario, and overwriting it
    # silently reattributed a whole shift's trips to the wrong car). The rego lets the client make
    # that distinction itself without a second roster round-trip. `None` whenever `vehicle_id` is
    # unset, on every non-heartbeat DeviceRead response, or if the vehicle row has since been
    # deleted (same honest-null posture as every other joined-in field on this schema).
    vehicle_rego: str | None = None

    # NOT a Device column either. The tenant's slug, resolved from `tenant_id`
    # and set on the response by the pairing routes -- same technique as
    # `device_secret` below.
    #
    # It exists because `POST /v1/auth/driver-login` REQUIRES `tenant_slug`, and
    # nothing told a tablet what its tenant's slug was: `tenant_id` is a uuid,
    # `GET /v1/tenants/me` needs a bearer token the driver does not have yet,
    # and driver codes stopped being unique platform-wide the moment X2 made
    # them per-tenant. The observed result on the test tablet (2026-09-08) was
    # `422 Field required: tenant_slug` for every driver -- login was
    # impossible, not merely awkward. Pairing is the right place to learn it:
    # the pairing code is already tenant-scoped, so a tablet finds out which
    # operator it belongs to at exactly the moment it is bound to one.
    tenant_slug: str | None = None

    # Also not a Device column, and the one field here that is a SECRET.
    # Populated only by POST /devices/register, which mints it and hands it over
    # exactly once -- the server keeps a hash and can never return it again.
    # `None` on every other response, including every heartbeat and every list
    # read, so a device credential is never exposed to a dashboard user or to
    # anything that merely reads the fleet.
    device_secret: str | None = None


class DeviceRotateSecretResponse(DeviceRead):
    """`POST /v1/fleet/devices/{id}/rotate-secret`.

    Structurally a `DeviceRead`, but `device_secret` is guaranteed present
    rather than optional -- this and `POST /devices/register` are the only two
    responses in this system that carry a device credential in plaintext, and
    each does so exactly once. A separate model so that is visible in the
    OpenAPI schema instead of being a `None`-by-default field a reader has to
    know about.
    """

    device_secret: str


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


class LocateResponseRequest(BaseModel):
    """A device answering an admin's locate request with its real fix.

    `accuracy_m` is optional and never invented: a device that cannot say how
    accurate its fix is sends nothing rather than a guess, and the dashboard
    shows the position without a precision claim."""

    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    accuracy_m: float | None = Field(default=None, ge=0)


class CommandAckRequest(BaseModel):
    """A device reporting that it has acted on a queued command."""

    # The three commands an admin can queue that a device can report acting on.
    # `restart` and `force_update` are one-shot requests whose flags the ack
    # CLEARS; `kiosk_lock` is a desired state, so acknowledging it records that
    # the tablet applied it without unlocking anything. See
    # app.services.fleet.record_command_ack for why that difference matters.
    command: Literal["restart", "force_update", "kiosk_lock"]


class KioskLockRequest(BaseModel):
    enabled: bool = True


class ForceUpdateRequest(BaseModel):
    enabled: bool = True


class LocateRequest(BaseModel):
    enabled: bool = True


class RebootRequest(BaseModel):
    """Queues a RESTART OF THE METER APP on the device — not an OS reboot.

    Rebooting Android needs Device-Owner provisioning this fleet does not have
    (see `Device.reboot_requested`). What the app can genuinely do, and now
    does, is restart itself, which is what the operational need behind this
    button actually is: "the meter is stuck, restart it". The device clears the
    flag via `POST /devices/{id}/command-ack` once it has acted, so an admin
    sees it carried out rather than permanently pending."""

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


# --- TEMPORARY force-wipe (see app.services.fleet_wipe's module docstring for
# full context; removed along with the rest of this tooling once onboarding/
# pairing testing is done) ----------------------------------------------------


class FleetForceWipeRequest(BaseModel):
    confirm: Literal[True] = Field(
        description="Must be explicitly `true` on every call. This endpoint purges audit/"
        "financial evidence (PSL ledger, wallet transactions, trip ratings, compliance "
        "documents, tariff change-log entries) for every driver on the tenant and is "
        "irreversible -- there is no default/implicit form of this request."
    )


class FleetForceWipeFailure(BaseModel):
    kind: Literal["vehicle", "device", "driver"]
    id: str
    reason: str


class FleetForceWipeResult(BaseModel):
    vehicles_deleted: int
    devices_deleted: int
    drivers_deleted: int
    evidence_rows_destroyed: dict[str, int] = Field(
        description="Evidence category -> row count PERMANENTLY destroyed by this call (PSL "
        "ledger entries/top-ups, wallet transactions, trip ratings, compliance documents, "
        "tariff change-log entries). Zero counts are included for every category this "
        "endpoint is capable of purging, not just ones with rows this run."
    )
    audit_log_preserved: Literal[True] = Field(
        default=True,
        description="Always true: this force wipe never deletes AuditLog rows, under any "
        "circumstance -- the tamper-evident hash chain (app.models.audit_log) is left intact "
        "even for tenants/drivers otherwise fully wiped. See app.services.fleet_wipe's module "
        "docstring for the full reasoning. A driver who has ever been recorded as an audit-log "
        "actor is reported in `failures` below instead of being silently skipped.",
    )
    failures: list[FleetForceWipeFailure]
