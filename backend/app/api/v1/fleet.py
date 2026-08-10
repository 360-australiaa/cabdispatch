"""Fleet domain router: vehicles + devices CRUD, QR device-pairing, heartbeat,
and admin kiosk-lock / force-update flags.

Every query in this file is filtered by `tenant_id` resolved via
`get_current_tenant_id` — that is the sole multi-tenancy isolation mechanism in
this system (see app.core.security / app.core.database docstrings).
"""
from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.models.fleet import Device, Vehicle
from app.models.user import User
from app.schemas.fleet import (
    ComplianceExpiryItem,
    DeviceCreate,
    DeviceHeartbeatRequest,
    DeviceRead,
    DeviceRegisterRequest,
    DeviceUpdate,
    ForceUpdateRequest,
    KioskLockRequest,
    LocateRequest,
    Page,
    PairingCodeRead,
    RebootRequest,
    VehicleCreate,
    VehicleLifetimeTotals,
    VehiclePilotReport,
    VehicleRead,
    VehicleUpdate,
    VerifyAdminPinRequest,
    VerifyAdminPinResponse,
)
from app.services import compliance_expiry as compliance_expiry_service
from app.services import evidence_pack as evidence_pack_service
from app.services import fleet as fleet_service
from app.services import fleet_reports as fleet_reports_service
from app.services import tenant as tenant_service
from app.services.reports import InvalidDateRangeError

router = APIRouter(prefix="/v1/fleet", tags=["fleet"])

# Admin-only dependency reused across the write/admin endpoints in this file.
_require_admin = require_role("owner", "admin")


def _fleet_error_to_http(exc: fleet_service.FleetError) -> HTTPException:
    if isinstance(exc, fleet_service.VehicleNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Vehicle not found")
    if isinstance(exc, fleet_service.DeviceNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")
    if isinstance(exc, fleet_service.DuplicateRegoError):
        return HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=f"rego already in use: {exc}"
        )
    if isinstance(exc, fleet_service.InvalidPairingCodeError):
        return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


# ==================================================================================
# Vehicles
# ==================================================================================


@router.get("/vehicles", response_model=Page[VehicleRead])
async def list_vehicles(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    status_filter: str | None = Query(default=None, alias="status"),
    vehicle_class: str | None = Query(default=None),
    rego: str | None = Query(default=None, description="Case-insensitive partial match"),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(Vehicle).where(Vehicle.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Vehicle).where(Vehicle.tenant_id == tenant_id)

    if status_filter is not None:
        stmt = stmt.where(Vehicle.status == status_filter)
        count_stmt = count_stmt.where(Vehicle.status == status_filter)
    if vehicle_class is not None:
        stmt = stmt.where(Vehicle.vehicle_class == vehicle_class)
        count_stmt = count_stmt.where(Vehicle.vehicle_class == vehicle_class)
    if rego is not None:
        pattern = f"%{rego.upper()}%"
        stmt = stmt.where(Vehicle.rego.like(pattern))
        count_stmt = count_stmt.where(Vehicle.rego.like(pattern))

    total = (await session.execute(count_stmt)).scalar_one()
    result = await session.execute(stmt.order_by(Vehicle.rego).offset(skip).limit(limit))
    items = result.scalars().all()
    return Page[VehicleRead](items=items, total=total, skip=skip, limit=limit)


@router.post("/vehicles", response_model=VehicleRead, status_code=status.HTTP_201_CREATED)
async def create_vehicle(
    payload: VehicleCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        await fleet_service.assert_rego_available(session, tenant_id=tenant_id, rego=payload.rego)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    vehicle = Vehicle(tenant_id=tenant_id, **payload.model_dump())
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


@router.get("/vehicles/{vehicle_id}", response_model=VehicleRead)
async def get_vehicle(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await fleet_service.get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc


@router.patch("/vehicles/{vehicle_id}", response_model=VehicleRead)
async def update_vehicle(
    vehicle_id: str,
    payload: VehicleUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        vehicle = await fleet_service.get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
        updates = payload.model_dump(exclude_unset=True)
        if "rego" in updates and updates["rego"] != vehicle.rego:
            await fleet_service.assert_rego_available(
                session, tenant_id=tenant_id, rego=updates["rego"], exclude_vehicle_id=vehicle_id
            )
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    for field, value in updates.items():
        setattr(vehicle, field, value)

    await session.commit()
    await session.refresh(vehicle)
    return vehicle


@router.delete("/vehicles/{vehicle_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_vehicle(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        vehicle = await fleet_service.get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
        # Devices survive vehicle deletion, just unbound — a device isn't
        # deleted just because its car was retired/sold.
        await fleet_service.unlink_devices_from_vehicle(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    await session.delete(vehicle)
    await session.commit()


@router.post("/vehicles/{vehicle_id}/pairing-code", response_model=PairingCodeRead)
async def create_pairing_code(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Mints a fresh, single-use, 15-minute QR-pairing code for this vehicle.
    Encode the returned `code` in a QR code the device scans (or a staff member
    keys in by hand) to drive `POST /v1/fleet/devices/register`."""
    try:
        return await fleet_service.generate_pairing_code(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc


@router.get("/vehicles/{vehicle_id}/evidence-pack")
async def get_vehicle_evidence_pack(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """One-click per-vehicle compliance evidence pack: a single ZIP bundling
    the vehicle's compliance-vault documents, its tenant's tariff version
    history, its paired device(s) firmware/app-version history, a
    tamper/event-log (audit-log) extract for the vehicle, and an
    installation-record placeholder. See app.services.evidence_pack module
    docstring for exactly what each category contains and how an empty
    category is represented (never silently omitted).

    Any authenticated tenant user may fetch this -- same "any authenticated
    tenant user may read" convention as GET /v1/compliance/vehicles/{id}/dossier.
    """
    try:
        vehicle = await fleet_service.get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    zip_bytes = await evidence_pack_service.build_evidence_pack(session, tenant_id=tenant_id, vehicle=vehicle)
    filename = evidence_pack_service.evidence_pack_filename(vehicle)
    return Response(
        content=zip_bytes,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get("/vehicles/{vehicle_id}/lifetime-totals", response_model=VehicleLifetimeTotals)
async def get_vehicle_lifetime_totals(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Per-vehicle lifetime cumulative-totals register (operations-cycle
    tracking pass): a read-only SUM aggregation across every CLOSED Trip ever
    recorded for this vehicle. Mirrors the classic statutory cumulative-
    totals register a physical taxi meter keeps -- exactly the evidence a
    cl 14 compliance pack wants. Not paginated, no filters -- a single
    lifetime snapshot, computed fresh on every request from the `trips`
    table (no new storage). See app.services.fleet_reports for the exact
    aggregation and the documented gap on `total_tips` (no such field exists
    on Trip in this codebase).

    Any authenticated tenant user may fetch this -- same convention as
    GET /vehicles/{vehicle_id}/evidence-pack above."""
    try:
        return await fleet_reports_service.vehicle_lifetime_totals(
            session, tenant_id=tenant_id, vehicle_id=vehicle_id
        )
    except fleet_reports_service.VehicleNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Vehicle not found") from exc


@router.get("/vehicles/{vehicle_id}/pilot-report", response_model=VehiclePilotReport)
async def get_vehicle_pilot_report(
    vehicle_id: str,
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """60-day-pilot-report evidence pack for a single vehicle over
    [from_date, to_date] (inclusive calendar days, same convention as
    GET /v1/reports/revenue): average fare-accuracy variance_pct, a coarse
    device-uptime estimate, duress-event counts, and flagged-for-review trip
    counts. See app.services.fleet_reports.vehicle_pilot_report for the
    documented simplifications (device uptime) and gaps
    (duress_test_activation_count -- no such field exists on DuressEvent in
    this codebase as of this pass).

    Any authenticated tenant user may fetch this -- same convention as
    GET /vehicles/{vehicle_id}/evidence-pack above."""
    try:
        return await fleet_reports_service.vehicle_pilot_report(
            session, tenant_id=tenant_id, vehicle_id=vehicle_id, from_date=from_date, to_date=to_date
        )
    except fleet_reports_service.VehicleNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Vehicle not found") from exc
    except InvalidDateRangeError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc


# ==================================================================================
# Compliance expiry (blueprint 7.2.3/7.2.4/10.1)
# ==================================================================================


@router.get("/compliance-expiry", response_model=Page[ComplianceExpiryItem])
async def list_compliance_expiry(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    within_days: int = Query(
        30, ge=1, le=365, description="Include items expiring within this many days (or already expired)"
    ),
    entity_type: str | None = Query(default=None, description="Filter to 'driver', 'vehicle', or 'device'"),
    status_filter: str | None = Query(
        default=None, alias="status", description="Filter to 'expiring_soon' or 'expired'"
    ),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Dashboard-facing list of every driver license/authority, vehicle
    registration/insurance, and device meter-calibration-due field that is
    either already expired or expiring within `within_days`. Not paginated at
    the DB level (the underlying query is a full tenant-scoped scan of
    Users + Vehicles + Devices, same cost as any of this domain's
    `Page[T]`-returning list endpoints on a tenant's realistic
    fleet/driver-roster size) — `skip`/`limit` slice the already-computed,
    already-sorted (soonest-expiring first) in-memory list, same Page[T]
    contract as every other list endpoint in this file.

    No extra role restriction beyond authentication + tenant scope — same
    convention already used by `GET /vehicles` and `GET /devices` above."""
    if entity_type is not None and entity_type not in ("driver", "vehicle", "device"):
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid entity_type filter")
    if status_filter is not None and status_filter not in ("expiring_soon", "expired"):
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid status filter")

    items = await compliance_expiry_service.list_compliance_expiry(
        session, tenant_id=tenant_id, within_days=within_days
    )
    if entity_type is not None:
        items = [i for i in items if i["entity_type"] == entity_type]
    if status_filter is not None:
        items = [i for i in items if i["status"] == status_filter]

    total = len(items)
    page_items = items[skip : skip + limit]
    return Page[ComplianceExpiryItem](
        items=[ComplianceExpiryItem(**i) for i in page_items], total=total, skip=skip, limit=limit
    )


# ==================================================================================
# Devices
# ==================================================================================


@router.get("/devices", response_model=Page[DeviceRead])
async def list_devices(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    vehicle_id: str | None = Query(default=None),
    kiosk_locked: bool | None = Query(default=None),
    android_id: str | None = Query(default=None),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(Device).where(Device.tenant_id == tenant_id)
    count_stmt = select(func.count()).select_from(Device).where(Device.tenant_id == tenant_id)

    if vehicle_id is not None:
        stmt = stmt.where(Device.vehicle_id == vehicle_id)
        count_stmt = count_stmt.where(Device.vehicle_id == vehicle_id)
    if kiosk_locked is not None:
        stmt = stmt.where(Device.kiosk_locked == kiosk_locked)
        count_stmt = count_stmt.where(Device.kiosk_locked == kiosk_locked)
    if android_id is not None:
        stmt = stmt.where(Device.android_id == android_id)
        count_stmt = count_stmt.where(Device.android_id == android_id)

    total = (await session.execute(count_stmt)).scalar_one()
    result = await session.execute(stmt.order_by(Device.created_at).offset(skip).limit(limit))
    items = result.scalars().all()
    return Page[DeviceRead](items=items, total=total, skip=skip, limit=limit)


@router.post("/devices", response_model=DeviceRead, status_code=status.HTTP_201_CREATED)
async def create_device(
    payload: DeviceCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Manual admin provisioning of a device row ahead of physical pairing.
    Most devices arrive via `POST /devices/register` instead."""
    if payload.vehicle_id is not None:
        try:
            await fleet_service.get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=payload.vehicle_id)
        except fleet_service.FleetError as exc:
            raise _fleet_error_to_http(exc) from exc

    device = Device(tenant_id=tenant_id, **payload.model_dump())
    session.add(device)
    await session.commit()
    await session.refresh(device)
    return device


@router.get("/devices/{device_id}", response_model=DeviceRead)
async def get_device(
    device_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc


@router.patch("/devices/{device_id}", response_model=DeviceRead)
async def update_device(
    device_id: str,
    payload: DeviceUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
        updates = payload.model_dump(exclude_unset=True)
        if updates.get("vehicle_id") is not None:
            await fleet_service.get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=updates["vehicle_id"])
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    for field, value in updates.items():
        setattr(device, field, value)

    await session.commit()
    await session.refresh(device)
    return device


@router.delete("/devices/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_device(
    device_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    await session.delete(device)
    await session.commit()


@router.post("/devices/register", response_model=DeviceRead)
async def register_device(
    payload: DeviceRegisterRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """QR-pairing: the device presents its `android_id` plus the pairing code
    shown/scanned from `POST /vehicles/{id}/pairing-code`, and is bound to that
    code's vehicle. Requires auth like every endpoint here (the person setting
    up the kiosk is logged into the app) — see the domain summary for why this
    doesn't use a separate device-credential scheme."""
    try:
        return await fleet_service.register_device(
            session,
            tenant_id=tenant_id,
            android_id=payload.android_id,
            pairing_code=payload.pairing_code,
            model=payload.model,
            app_version=payload.app_version,
        )
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc


@router.post("/devices/{device_id}/heartbeat", response_model=DeviceRead)
async def device_heartbeat(
    device_id: str,
    payload: DeviceHeartbeatRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Updates last_seen_at/battery/network and returns the current device row —
    including `kiosk_locked` / `force_update_pending` / `locate_requested` /
    `reboot_requested`, which is how the device learns an admin has flagged it
    for kiosk-lock, a forced app update, a locate request, or a reboot request."""
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    return await fleet_service.record_heartbeat(
        session,
        device,
        battery=payload.battery,
        network=payload.network,
        app_version=payload.app_version,
    )


@router.post("/devices/{device_id}/kiosk-lock", response_model=DeviceRead)
async def set_device_kiosk_lock(
    device_id: str,
    payload: KioskLockRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Admin-only. Sets/clears the kiosk_locked flag the device reads back on
    its next heartbeat."""
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    return await fleet_service.set_kiosk_lock(session, device, enabled=payload.enabled)


@router.post("/devices/{device_id}/force-update", response_model=DeviceRead)
async def set_device_force_update(
    device_id: str,
    payload: ForceUpdateRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Admin-only. Sets/clears the force_update_pending flag the device reads
    back on its next heartbeat."""
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    return await fleet_service.set_force_update(session, device, enabled=payload.enabled)


@router.post("/devices/{device_id}/locate", response_model=DeviceRead)
async def set_device_locate(
    device_id: str,
    payload: LocateRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Admin-only. Sets/clears the locate_requested flag the device reads back
    on its next heartbeat — the on-device app is expected to respond to a set
    flag by reporting a fresh location fix out of band; building that
    reporting path is the mobile app's responsibility, not this endpoint's."""
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    return await fleet_service.set_locate_requested(session, device, enabled=payload.enabled)


@router.post("/devices/{device_id}/reboot", response_model=DeviceRead)
async def set_device_reboot(
    device_id: str,
    payload: RebootRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Admin-only. Sets/clears the reboot_requested flag the device reads back
    on its next heartbeat.

    HONESTY NOTE (blueprint 4.1.3/6.2.1): this is a real command QUEUE, not a
    claim that the device actually reboots. See `Device.reboot_requested`'s
    doc comment — actually rebooting the OS needs device-owner-level Android
    permissions this codebase does not provision, so nothing currently acts
    on this flag on the device side. It is still useful as-is: an admin can
    queue the request and see it pending, ready for a future device-owner-
    aware app build to consume."""
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    return await fleet_service.set_reboot_requested(session, device, enabled=payload.enabled)


@router.post("/devices/{device_id}/verify-admin-pin", response_model=VerifyAdminPinResponse)
async def verify_device_admin_pin(
    device_id: str,
    payload: VerifyAdminPinRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """What a device calls before doing something destructive locally (e.g.
    the Android app's factory-reset flow — see
    au...SettingsViewModel.attemptFactoryReset) to check a PIN against the
    tenant's server-side admin_pin_hash, without the hash itself ever being
    sent to the device. No admin-role gate: this is the device-facing check
    endpoint, not the set endpoint (see POST /v1/tenants/{id}/admin-pin,
    owner-only, in app/api/v1/tenants.py) — any authenticated user of this
    tenant's device can attempt a PIN, same as anyone can attempt an admin
    PIN on the physical device itself.

    `configured=False` (tenant has never set an admin PIN) is always
    accompanied by `valid=False`, but callers must check `configured`
    explicitly rather than inferring "not configured" from `valid=False`
    alone — that would be indistinguishable from "PIN set, but wrong"."""
    try:
        await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    try:
        tenant = await tenant_service.check_admin_pin(session, tenant_id=tenant_id)
    except tenant_service.TenantError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found") from exc

    configured, valid = tenant_service.verify_admin_pin(tenant, pin=payload.pin)
    return VerifyAdminPinResponse(valid=valid, configured=configured)
