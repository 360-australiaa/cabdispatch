"""Fleet domain router: vehicles + devices CRUD, QR device-pairing, heartbeat,
and admin kiosk-lock / force-update flags.

Every query in this file is filtered by `tenant_id` resolved via
`get_current_tenant_id` — that is the sole multi-tenancy isolation mechanism in
this system (see app.core.security / app.core.database docstrings).
"""
from __future__ import annotations

from datetime import UTC, date, datetime

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Response, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import (
    get_current_tenant_id,
    get_current_user,
    get_optional_tenant_id,
    require_role,
)
from app.models.fleet import Device, Vehicle
from app.models.user import User
from app.schemas.fleet import (
    ComplianceExpiryItem,
    DeviceCreate,
    DeviceHeartbeatRequest,
    DeviceRead,
    DeviceRegisterRequest,
    DeviceUpdate,
    FleetForceWipeRequest,
    FleetForceWipeResult,
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
    VehicleShiftHistoryItem,
    VehicleUpdate,
    VerifyAdminPinRequest,
    VerifyAdminPinResponse,
)
from app.services import app_releases as app_releases_service
from app.services import compliance_expiry as compliance_expiry_service
from app.services import evidence_pack as evidence_pack_service
from app.services import fleet as fleet_service
from app.services import fleet_reports as fleet_reports_service
from app.services import fleet_wipe as fleet_wipe_service
from app.services import tenant as tenant_service
from app.services.reports import InvalidDateRangeError

router = APIRouter(prefix="/v1/fleet", tags=["fleet"])

# Admin-only dependency reused across the write/admin endpoints in this file.
_require_admin = require_role("owner", "admin")
# Owner-only: reserved for the single most destructive action in this file
# (force wipe, see the bottom of this router) -- same "highest-privilege
# action reserved for owner" precedent as POST /v1/tenants/{id}/admin-pin in
# app/api/v1/tenants.py.
_require_owner = require_role("owner")


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
    admin: User = Depends(_require_admin),
):
    try:
        vehicle = await fleet_service.get_vehicle_or_404(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
        # Devices survive vehicle deletion, just unbound — a device isn't
        # deleted just because its car was retired/sold. Any currently-OPEN
        # shift on this vehicle is also closed here (unreconciled,
        # audit-logged) rather than left dangling — see
        # fleet_service.prepare_vehicle_for_deletion's own docstring for the
        # full "close vs refuse" design decision (a real production bug: a
        # deleted vehicle's id was surviving forever on an open Shift row,
        # rendering as a raw UUID on the dashboard's drivers list). Must
        # flush before the DELETE below: SQLAlchemy's flush always runs
        # every pending UPDATE ahead of every pending DELETE within one
        # commit regardless of statement order or ORM relationships
        # (verified empirically for this exact unrelated-mapped-classes case
        # — there is no `relationship()` anywhere in this codebase's models,
        # see app.core.database's docstring), so this is safe even against
        # postgres's now-enforced FK.
        await fleet_service.prepare_vehicle_for_deletion(
            session, tenant_id=tenant_id, vehicle_id=vehicle_id, actor_user_id=admin.id
        )
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    # Position history, pairing codes (and, via delete_device below, version
    # history) cascade away at the DB layer -- see app.models.fleet's
    # ondelete= comments and app.services.fleet's module docstring for the
    # real production bug this fixes and the "cascade derived data" design
    # decision. No extra code needed here: it's the same single DELETE
    # statement as before, postgres/sqlite (PRAGMA foreign_keys=ON, see
    # app.core.database) do the rest.
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


@router.get("/vehicles/{vehicle_id}/shift-history", response_model=Page[VehicleShiftHistoryItem])
async def get_vehicle_shift_history(
    vehicle_id: str,
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Which drivers has this vehicle had -- past shifts (and the currently-
    open one, if any), newest-first. A real operational question: one vehicle
    often runs back-to-back 12-hour shifts across two+ drivers per day, and
    `current_driver_id`/`current_driver_name` on `GET /v1/vehicles` (the live
    ops domain) only ever answers "right now". See
    app.services.fleet.list_vehicle_shift_history.

    Any authenticated tenant user may fetch this -- same convention as
    GET /vehicles/{vehicle_id}/evidence-pack above (dispatchers/owners/admins
    all need this, not just admins)."""
    try:
        items, total = await fleet_service.list_vehicle_shift_history(
            session, tenant_id=tenant_id, vehicle_id=vehicle_id, skip=skip, limit=limit
        )
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    return Page[VehicleShiftHistoryItem](
        items=[VehicleShiftHistoryItem(**i) for i in items], total=total, skip=skip, limit=limit
    )


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

    # `revoked` is a boolean on the wire and a timestamp in the column -- the row
    # is an audit record, so it keeps WHEN a tablet was retired, not merely that
    # it was. Popped before the generic setattr loop below, which would otherwise
    # try to assign a bool to `Device.revoked`, a field that does not exist.
    if "revoked" in updates:
        revoked = updates.pop("revoked")
        device.revoked_at = datetime.now(UTC) if revoked else None

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

    # Version history cascades away and any device_pairing_codes row that
    # once recorded this device as its `used_by_device_id` has that
    # dangling back-reference nulled -- both at the DB layer, same
    # ondelete= mechanism (and same real production bug fix) as
    # delete_vehicle above. See app.models.fleet's ondelete= comments.
    await session.delete(device)
    await session.commit()


@router.post("/devices/register", response_model=DeviceRead)
async def register_device(
    payload: DeviceRegisterRequest,
    session: AsyncSession = Depends(get_session),
):
    """Pairing: the device presents its `android_id` plus the pairing code shown
    by `POST /vehicles/{id}/pairing-code`, and is bound to that code's vehicle.
    Responds with the device row plus, once and only here, its `device_secret`.

    **This is the one route in this file with no bearer requirement, and that is
    the point.** Registration became the gate a tablet must pass before anyone
    can log into the meter, so by definition there is nobody logged in when it
    is called and no token to take a tenant from. The pairing code IS the
    credential: admin-minted, tenant-scoped (the tenant is read off the code
    row, so a code from tenant A can only enrol into tenant A), single-use, and
    valid for `fleet_service.PAIRING_CODE_TTL_MINUTES` minutes. Eight characters
    of a 32-symbol alphabet is ~40 bits, which -- single-use and expiring in 15
    minutes -- is not a brute-force target worth defending beyond what the code
    itself provides. This backend has no rate limiting anywhere today; if that
    changes, this route should be among the first to get it.
    """
    try:
        device, secret = await fleet_service.register_device(
            session,
            android_id=payload.android_id,
            pairing_code=payload.pairing_code,
            model=payload.model,
            app_version=payload.app_version,
        )
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    # The single moment the plaintext secret exists outside the tablet. Set on
    # the response object rather than the ORM row -- DeviceRead.device_secret is
    # not a column, and every other read of this model leaves it None.
    response = DeviceRead.model_validate(device)
    response.device_secret = secret
    return response


@router.post("/devices/{device_id}/heartbeat", response_model=DeviceRead)
async def device_heartbeat(
    device_id: str,
    payload: DeviceHeartbeatRequest,
    x_device_secret: str | None = Header(default=None, alias="X-Device-Secret"),
    tenant_id: str | None = Depends(get_optional_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Updates last_seen_at/battery/network and returns the current device row —
    including `kiosk_locked` / `force_update_pending` / `locate_requested` /
    `reboot_requested`, which is how the device learns an admin has flagged it
    for kiosk-lock, a forced app update, a locate request, or a reboot request.

    Also stamps `latest_version_code` from the current
    `GET /v1/app-releases/latest` answer (None if no active release has ever
    been published) — a low-cost hint riding this existing 60s poll so a
    device can learn about an OTA update without a second network round-trip.
    The dedicated `GET /v1/app-releases/latest` endpoint still exists
    independently for a manual "check for updates" pull.

    **Authenticates on EITHER an `X-Device-Secret` header or a human bearer
    token.** The secret is the one that matters: it lets a tablet with nobody
    logged into it poll for commands, which is the whole reason a parked or
    logged-off tablet could not be located, kiosk-locked or told to update
    before. The bearer path is kept because every tablet paired before device
    secrets existed has none, and would otherwise stop reporting the moment this
    deployed; those acquire a secret the next time they re-pair. A device
    presenting a secret needs no tenant from a token -- its own row carries one.
    """
    if x_device_secret:
        try:
            device = await fleet_service.authenticate_device(
                session, device_id=device_id, secret=x_device_secret
            )
        except fleet_service.DeviceAuthError as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device secret"
            ) from exc
        except fleet_service.FleetError as exc:
            raise _fleet_error_to_http(exc) from exc
    elif tenant_id is not None:
        try:
            device = await fleet_service.get_device_or_404(
                session, tenant_id=tenant_id, device_id=device_id
            )
        except fleet_service.FleetError as exc:
            raise _fleet_error_to_http(exc) from exc
    else:
        # Neither credential. get_optional_tenant_id authorises nothing by
        # itself, so this branch is what actually keeps the route protected.
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Device heartbeat requires an X-Device-Secret header or a bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    device = await fleet_service.record_heartbeat(
        session,
        device,
        battery=payload.battery,
        network=payload.network,
        app_version=payload.app_version,
    )

    latest_release = await app_releases_service.get_latest_active_release(session)
    response = DeviceRead.model_validate(device)
    response.latest_version_code = latest_release.version_code if latest_release is not None else None
    return response


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


# ==================================================================================
# TEMPORARY force-wipe (see app.services.fleet_wipe's module docstring for full
# context, exactly what is/isn't destroyed, and the removal plan). This is
# deliberately a SEPARATE endpoint from DELETE /v1/fleet/vehicles/{id} etc, not
# a flag on them -- the ordinary per-row deletes must never gain a backdoor
# around app.services.user.assert_user_deletable's evidence-blocking.
# ==================================================================================


@router.post("/wipe-test-data/force", response_model=FleetForceWipeResult)
async def force_wipe_test_data(
    payload: FleetForceWipeRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    owner: User = Depends(_require_owner),
):
    """Owner-only. Deletes every vehicle, device, and driver on this tenant,
    additionally purging (for every driver about to be deleted) the exact
    evidence categories that would otherwise correctly block their deletion:
    PSL ledger entries + top-ups, wallet transactions, trip ratings,
    compliance documents, and tariff change-log entries. IRREVERSIBLE.

    Never deletes `AuditLog` rows, under any circumstance -- see
    app.services.fleet_wipe's module docstring for why the tamper-evident
    hash chain is left intact even here. A driver who has ever been recorded
    as an audit-log actor is reported in `failures` instead of being deleted
    or silently skipped.

    `confirm: true` is required on every call (see `FleetForceWipeRequest`) --
    this is the explicit, separately-chosen opt-in path the task brief calls
    for, distinct from (and never the default of) the ordinary per-row-loop
    wipe the dashboard already does client-side for the non-destructive-
    evidence case."""
    result = await fleet_wipe_service.force_wipe_tenant_fleet_data(
        session, tenant_id=tenant_id, actor_user_id=owner.id
    )
    await session.commit()
    return FleetForceWipeResult(
        vehicles_deleted=result.vehicles_deleted,
        devices_deleted=result.devices_deleted,
        drivers_deleted=result.drivers_deleted,
        evidence_rows_destroyed=result.evidence_rows_destroyed,
        failures=[
            {"kind": f.kind, "id": f.id, "reason": f.reason} for f in result.failures
        ],
    )
