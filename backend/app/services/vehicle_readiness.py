"""Vehicle operational-readiness gate (plan D-4, WP-23).

`assert_vehicle_operational` is the single authoritative "is this vehicle
legal to put a driver in right now" check the plan calls for -- a PURE
function (no DB session, every input passed in) so it is trivially
unit-testable and so later callers (this work package's own readiness
endpoint/activate transition, and Phase 3's `start_shift`) can reuse the
exact same logic without re-deriving it.

`get_vehicle_readiness` is the thin async wrapper that does the actual
querying (vehicle row, tenant settings, compliance documents, the device's
active assignment + calibration_due) and calls the pure function above.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.compliance import (
    DOC_TYPE_CAMERA_REGISTER,
    DOC_TYPE_DURESS_REGISTER,
    DOC_TYPE_TRACKING_REGISTER,
    ComplianceDocument,
)
from app.models.device_assignment import DeviceAssignment
from app.models.fleet import (
    OPERATING_AREA_COUNTRY,
    VEHICLE_STATUS_ACTIVE,
    VEHICLE_STATUS_PENDING_COMPLIANCE,
    Device,
    Vehicle,
)
from app.models.tenant_settings import TenantSettings
from app.services.fleet import get_vehicle_or_404
from app.services.tenant_settings import get_or_create_settings

# Operating areas where NSW P2P rules mandate an in-vehicle duress alarm and a
# tracking device (plan 2.4) -- every area except "country".
MANDATED_TRACKING_AREAS = frozenset(
    {"sydney", "newcastle", "wollongong", "central_coast"}
)


class VehicleNotOperationalError(Exception):
    """Raised by callers that need a hard failure (e.g. the activate
    transition) instead of a bool -- carries every failing reason, not just
    the first, same as the `reasons` list `assert_vehicle_operational`
    returns."""

    def __init__(self, reasons: list[str]) -> None:
        self.reasons = reasons
        super().__init__("; ".join(reasons))


def _is_expired(expiry: date | None, *, today: date) -> bool:
    return expiry is not None and expiry < today


def assert_vehicle_operational(
    vehicle: Vehicle,
    tenant_settings: TenantSettings,
    *,
    has_camera_register_doc: bool,
    has_duress_register_doc: bool,
    has_tracking_register_doc: bool,
    has_active_device_assignment: bool,
    device_calibration_due: date | None,
    today: date | None = None,
) -> list[str]:
    """Checks every gate in the plan D-4 table and returns the list of
    failing-reason strings (empty list == operational). Never raises --
    callers that want an exception (e.g. the activate transition below)
    wrap this and raise `VehicleNotOperationalError(reasons)` themselves.

    `tenant_settings` is accepted (per the task signature) even though no
    gate below currently reads a tenant_settings field -- the mandated-area
    list (2.4) is a fixed NSW-wide regulatory list, not a per-tenant
    override, so it lives as the module-level `MANDATED_TRACKING_AREAS`
    constant instead. Kept as a parameter anyway: (a) it matches the task's
    exact signature, and (b) a future tenant-level override of the mandated
    area list is a plausible extension and every call site already has the
    row in hand.

    JUDGMENT CALL -- unexpired-or-None date gates (registration/insurance/
    inspection/licence): every one of these columns is documented on the
    model as "null means unknown, fail-open, never blocks on its own" (see
    `Vehicle.registration_expiry`'s doc comment, and the identical
    convention on `Device.calibration_due`). This function follows that
    existing codebase-wide convention literally: a None expiry gate PASSES
    silently and is NOT listed as a reason (not even as a soft warning) --
    treating it as blocking, or even as a listed-but-non-blocking warning,
    would be a new behaviour this codebase has consistently avoided
    elsewhere (compliance_expiry, the DB column doc comments). If a future
    pass wants a distinct "warn but don't block" tier, that's a schema/UX
    decision (a third bucket in the response) beyond this function's scope.
    """
    reasons: list[str] = []
    today = today if today is not None else date.today()

    if vehicle.status != VEHICLE_STATUS_ACTIVE:
        reasons.append(f"status must be 'active' (currently '{vehicle.status}')")

    if _is_expired(vehicle.registration_expiry, today=today):
        reasons.append("registration_expiry has passed")

    if _is_expired(vehicle.insurance_expiry, today=today):
        reasons.append("insurance_expiry has passed")

    if _is_expired(vehicle.inspection_expiry, today=today):
        reasons.append("inspection_expiry has passed")

    if not vehicle.taxi_licence_no:
        reasons.append("taxi_licence_no is not set")
    if _is_expired(vehicle.licence_expiry, today=today):
        reasons.append("licence_expiry has passed")

    if not vehicle.camera_serial:
        reasons.append("camera_serial is not set")
    if not has_camera_register_doc:
        reasons.append("no camera_register compliance document on file")

    if vehicle.operating_area != OPERATING_AREA_COUNTRY and vehicle.operating_area in MANDATED_TRACKING_AREAS:
        if not vehicle.tracking_device_id:
            reasons.append("tracking_device_id is not set (mandated for this operating_area)")
        if not has_tracking_register_doc:
            reasons.append("no tracking_register compliance document on file (mandated for this operating_area)")
        if not has_duress_register_doc:
            reasons.append("no duress_register compliance document on file (mandated for this operating_area)")

    if not has_active_device_assignment:
        reasons.append("no meter (device) is currently assigned to this vehicle")
    elif _is_expired(device_calibration_due, today=today):
        reasons.append("assigned meter's calibration_due has passed")

    return reasons


@dataclass(frozen=True)
class VehicleReadiness:
    operational: bool
    reasons: list[str]


async def _gather_readiness_inputs(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> dict:
    """Shared querying used by both `get_vehicle_readiness` and
    `activate_vehicle` below -- everything `assert_vehicle_operational`
    needs except the `vehicle`/`tenant_settings` objects themselves (the
    caller already has those, and `activate_vehicle` needs to mutate the
    vehicle in-memory before the check, see its docstring)."""
    doc_result = await session.execute(
        select(ComplianceDocument.doc_type)
        .where(
            ComplianceDocument.tenant_id == tenant_id,
            ComplianceDocument.vehicle_id == vehicle_id,
            ComplianceDocument.doc_type.in_(
                (DOC_TYPE_CAMERA_REGISTER, DOC_TYPE_DURESS_REGISTER, DOC_TYPE_TRACKING_REGISTER)
            ),
        )
        .distinct()
    )
    doc_types_on_file = {row[0] for row in doc_result.all()}

    assignment_result = await session.execute(
        select(DeviceAssignment, Device)
        .join(Device, Device.id == DeviceAssignment.device_id)
        .where(
            DeviceAssignment.tenant_id == tenant_id,
            DeviceAssignment.vehicle_id == vehicle_id,
            DeviceAssignment.unbound_at.is_(None),
        )
    )
    row = assignment_result.first()
    has_active_device_assignment = row is not None
    device_calibration_due = row[1].calibration_due if row is not None else None

    return {
        "has_camera_register_doc": DOC_TYPE_CAMERA_REGISTER in doc_types_on_file,
        "has_duress_register_doc": DOC_TYPE_DURESS_REGISTER in doc_types_on_file,
        "has_tracking_register_doc": DOC_TYPE_TRACKING_REGISTER in doc_types_on_file,
        "has_active_device_assignment": has_active_device_assignment,
        "device_calibration_due": device_calibration_due,
    }


async def get_vehicle_readiness(
    session: AsyncSession, *, tenant_id: str, vehicle_id: str
) -> VehicleReadiness:
    """Does the real querying `assert_vehicle_operational` needs and calls
    it. Raises `VehicleNotFoundError` (same exception the rest of this
    domain already uses) if the vehicle doesn't exist for this tenant."""
    vehicle = await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    tenant_settings = await get_or_create_settings(session, tenant_id=tenant_id)
    inputs = await _gather_readiness_inputs(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    reasons = assert_vehicle_operational(vehicle, tenant_settings, **inputs)
    return VehicleReadiness(operational=not reasons, reasons=reasons)


async def activate_vehicle(session: AsyncSession, *, tenant_id: str, vehicle_id: str) -> Vehicle:
    """`POST /vehicles/{id}/activate` (plan D-4, WP-23): moves a vehicle from
    `pending_compliance` to `active`, but ONLY if it is (would be)
    operational -- gated by the exact same `assert_vehicle_operational`
    checklist as the readiness endpoint. `draft -> pending_compliance` has
    no gate (plain PATCH `status=` still handles that, and every other
    admin-override transition, e.g. suspending an active vehicle -- see
    that route's docstring); only THIS specific transition is gated.

    Raises `VehicleNotOperationalError(reasons)` (mapped to 409 by the
    router, body carries the reasons list) if the vehicle isn't ready, or
    if it isn't currently in `pending_compliance` status at all (that's a
    distinct, single-reason failure -- this endpoint is not a generic
    "set status=active", it specifically models the one lifecycle edge the
    plan names).

    JUDGMENT CALL: `assert_vehicle_operational`'s own first gate is
    `status == "active"` -- and the entire point of this function is to be
    the thing that flips status TO "active". Reusing `get_vehicle_readiness`
    unmodified here would therefore always report "status must be active"
    and make activation permanently impossible. Instead, once we've
    confirmed the vehicle is in `pending_compliance`, `vehicle.status` is
    set to `active` IN-MEMORY (not yet committed) before running the
    checklist, so every other gate is evaluated as it will actually be
    once activation succeeds; on failure the in-memory status is reverted
    before raising so nothing is persisted."""
    vehicle = await get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    if vehicle.status != VEHICLE_STATUS_PENDING_COMPLIANCE:
        raise VehicleNotOperationalError(
            [
                "vehicle must be in 'pending_compliance' status to activate "
                f"(currently '{vehicle.status}')"
            ]
        )

    tenant_settings = await get_or_create_settings(session, tenant_id=tenant_id)
    inputs = await _gather_readiness_inputs(session, tenant_id=tenant_id, vehicle_id=vehicle_id)

    vehicle.status = VEHICLE_STATUS_ACTIVE
    reasons = assert_vehicle_operational(vehicle, tenant_settings, **inputs)
    if reasons:
        vehicle.status = VEHICLE_STATUS_PENDING_COMPLIANCE
        raise VehicleNotOperationalError(reasons)

    await session.commit()
    await session.refresh(vehicle)
    return vehicle


__all__ = [
    "MANDATED_TRACKING_AREAS",
    "VehicleNotOperationalError",
    "VehicleReadiness",
    "activate_vehicle",
    "assert_vehicle_operational",
    "get_vehicle_readiness",
]
