"""Fleet domain router: vehicles + devices CRUD, QR device-pairing, heartbeat,
and admin kiosk-lock / force-update flags.

Every query in this file is filtered by `tenant_id` resolved via
`get_current_tenant_id` — that is the sole multi-tenancy isolation mechanism in
this system (see app.core.security / app.core.database docstrings).
"""
from __future__ import annotations

from datetime import UTC, date, datetime

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.ratelimit import (
    DEVICE_REGISTER_PER_IP,
    VERIFY_ADMIN_PIN_LOCKOUT,
    VERIFY_ADMIN_PIN_PER_USER,
    enforce,
    limiter,
    peek_exhausted,
    register_failure,
)
from app.core.security import (
    get_current_tenant_id,
    get_current_user,
    get_optional_tenant_id,
    get_optional_token_payload,
    require_role,
)
from app.models.fleet import Device, Vehicle
from app.models.user import User
from app.schemas.fleet import (
    CommandAckRequest,
    ComplianceExpiryItem,
    DeviceCreate,
    DeviceHeartbeatRequest,
    DeviceRead,
    DeviceRegisterRequest,
    DeviceRotateSecretResponse,
    DeviceUpdate,
    FleetForceWipeRequest,
    FleetForceWipeResult,
    ForceUpdateRequest,
    KioskLockRequest,
    LocateRequest,
    LocateResponseRequest,
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


async def get_optional_admin(
    payload: dict | None = Depends(get_optional_token_payload),
    session: AsyncSession = Depends(get_session),
) -> User | None:
    """`_require_admin` where "no Authorization header at all" is a valid answer
    instead of a 401 — for the one route in this file that accepts an
    `X-Device-Secret` INSTEAD of a human token (`verify-admin-pin`).

    It weakens nothing: a header that IS present is validated and role-checked
    exactly as strictly as `_require_admin`, so a driver token still gets a 403
    rather than being downgraded to "anonymous". Only the total absence of a
    token becomes expressible, and the route itself must then find another
    credential or refuse.
    """
    if payload is None:
        return None

    user_id = payload.get("sub")
    user = None
    if user_id:
        result = await session.execute(select(User).where(User.id == user_id))
        user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not validate credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if user.role not in ("owner", "admin"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Requires one of roles: owner, admin"
        )
    return user


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
    rego_exact: str | None = Query(
        default=None,
        description="Exact rego match (case-insensitive). Returns 0 or 1 vehicle.",
    ),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """`rego_exact` is the meter's rego→UUID resolver and exists because this
    list is capped at 100 (backend audit §3 G6).

    `ApiService.listVehicles(limit = 100)` walks the whole page looking for one
    rego and has no paging loop, so on a tenant with a 101st vehicle that lookup
    silently fails for whoever sorts last — the meter simply cannot find its own
    car. The pre-existing `rego` filter is a case-insensitive PARTIAL match
    built for a dashboard search box; it cannot serve as a resolver because
    "AB12" also matches "AB123", so the client would still have to disambiguate.
    `rego_exact` is a separate parameter rather than a change to `rego` on
    purpose: tightening `rego` would break that search box, and the two have
    genuinely different jobs. Both may be supplied; they simply AND together.
    """
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
    if rego_exact is not None:
        # Regos are stored upper-cased (see VehicleBase's validator), so
        # upper-casing the input is the whole of the case-insensitivity and
        # keeps this an index-usable equality rather than a function call on the
        # column.
        stmt = stmt.where(Vehicle.rego == rego_exact.upper())
        count_stmt = count_stmt.where(Vehicle.rego == rego_exact.upper())

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


async def _with_tenant_slug(session: AsyncSession, device: Device) -> DeviceRead:
    """`DeviceRead` for a tablet, carrying its tenant's slug.

    Only the two routes a tablet can reach WITHOUT a bearer token use this --
    pairing and `devices/me` -- because they are the only points at which a
    device that cannot yet log anybody in needs to learn its operator. See
    `DeviceRead.tenant_slug` for why driver login is impossible without it.
    """
    response = DeviceRead.model_validate(device)
    tenant = await tenant_service.get_tenant_or_404(session, tenant_id=device.tenant_id)
    response.tenant_slug = tenant.slug
    return response


@router.get("/devices/me", response_model=DeviceRead)
async def get_own_device(
    x_device_secret: str = Header(alias="X-Device-Secret"),
    session: AsyncSession = Depends(get_session),
):
    """A tablet reading its OWN row, authenticated by nothing but its device
    secret — chiefly to answer "which vehicle am I bound to?".

    Both existing device-reading routes (`GET /devices` and
    `GET /devices/{id}`) are bearer-only, so a parked, logged-off tablet holding
    a stale vehicle binding could not correct itself until a human signed in
    (backend audit §3 G5) — and that is precisely the tablet an operator is
    trying to straighten out. The binding also goes stale without anyone doing
    anything wrong: deleting a vehicle nulls `Device.vehicle_id`
    (`fleet_service.unlink_devices_from_vehicle`) while the tablet keeps its
    old `vehicleUuid` indefinitely.

    **Declared above `GET /devices/{device_id}` deliberately.** FastAPI matches
    routes in declaration order, so with the parameterised route first, `me`
    would be swallowed as a device id and answered with a 404 from a bearer-only
    handler. Do not reorder these two.

    `X-Device-Secret` is REQUIRED here — no bearer fallback, unlike the
    heartbeat. There is no device id in this URL, so a bearer token alone could
    not identify a row to return; a tablet old enough to have no secret has
    `GET /devices/{id}` and its own stored id. 401 on an unknown or revoked
    secret, never 404: with no id in the request there is nothing for a 404 to
    be "not found" about, and a distinct answer would confirm which secrets
    exist.
    """
    try:
        device = await fleet_service.authenticate_device_by_secret(session, secret=x_device_secret)
        return await _with_tenant_slug(session, device)
    except fleet_service.DeviceAuthError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device secret"
        ) from exc


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
@limiter.limit(DEVICE_REGISTER_PER_IP)
async def register_device(
    request: Request,
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
    response = await _with_tenant_slug(session, device)
    response.device_secret = secret
    return response


@router.post("/devices/{device_id}/rotate-secret", response_model=DeviceRotateSecretResponse)
async def rotate_device_secret(
    device_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    admin: User = Depends(_require_admin),
):
    """Admin-only. Mints a fresh device secret, invalidating the old one, and
    returns it in plaintext exactly once — the second and last route in this
    system that ever does (the other is `POST /devices/register`).

    Before this, `fleet_service.mint_device_secret` had a single call site,
    `register_device`, so a device secret was immortal: the only way to change
    one was to physically visit the tablet with a fresh pairing code (backend
    audit §3 G2). The duress hardware in this same codebase has had
    `POST /duress-devices/{id}/rotate-secret` since it landed — the asymmetry
    was an oversight, not a decision.

    Rotation is NOT a re-pair, and the difference is the point: `vehicle_id`,
    `paired_at` and `revoked_at` are untouched. This says "the credential on
    this tablet may have leaked", not "this tablet moved car". It is also not a
    substitute for revocation — a rotated secret leaves the device active, so a
    genuinely lost tablet wants `PATCH /devices/{id}` with `revoked: true`.

    Refuses on a revoked device: handing a working credential to a tablet an
    operator has deliberately retired would quietly un-revoke it in every
    practical sense while the dashboard still showed it as retired.
    """
    try:
        device = await fleet_service.get_active_device_or_404(
            session, tenant_id=tenant_id, device_id=device_id
        )
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    device, secret = await fleet_service.rotate_device_secret(
        session, device, actor_user_id=admin.id
    )

    # Same one-shot handover as register_device: set on the response object, not
    # the ORM row -- device_secret is not a column.
    response = DeviceRotateSecretResponse.model_validate(
        {**DeviceRead.model_validate(device).model_dump(), "device_secret": secret}
    )
    return response


async def _authenticate_device_or_bearer(
    session: AsyncSession,
    *,
    device_id: str,
    secret: str | None,
    tenant_id: str | None,
) -> Device:
    """The device row for `device_id`, authenticated by its own secret or by a
    human bearer token — the shared front door for every route a TABLET calls.

    The secret is the one that matters: it lets a tablet with nobody logged into
    it heartbeat, answer a locate, and acknowledge a command. The bearer path
    stays because every tablet paired before device secrets existed has none,
    and would otherwise go silent the moment this deployed; those pick up a
    secret the next time they re-pair.

    `get_optional_tenant_id` authorises nothing by itself, so the final branch
    here is what actually keeps these routes closed.
    """
    if secret:
        try:
            return await fleet_service.authenticate_device(session, device_id=device_id, secret=secret)
        except fleet_service.DeviceAuthError as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device secret"
            ) from exc
        except fleet_service.FleetError as exc:
            raise _fleet_error_to_http(exc) from exc
    if tenant_id is not None:
        try:
            # `get_ACTIVE_device_or_404`, not `get_device_or_404`. This branch
            # used the latter, which does not look at `revoked_at` -- so
            # revoking a tablet stopped only its device-secret calls, and a
            # revoked, lost or stolen tablet with any valid human token on it
            # went right on heartbeating, answering locates and acknowledging
            # commands (backend audit §3 G3). Cutting a tablet off is the whole
            # purpose of the revoke button, and it had a hole in it exactly the
            # size of one driver login.
            return await fleet_service.get_active_device_or_404(
                session, tenant_id=tenant_id, device_id=device_id
            )
        except fleet_service.FleetError as exc:
            raise _fleet_error_to_http(exc) from exc
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="This endpoint requires an X-Device-Secret header or a bearer token",
        headers={"WWW-Authenticate": "Bearer"},
    )


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
    device = await _authenticate_device_or_bearer(
        session, device_id=device_id, secret=x_device_secret, tenant_id=tenant_id
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
    on its next heartbeat; the device answers on `/locate-response` below, and
    answering is what clears the flag again."""
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    return await fleet_service.set_locate_requested(session, device, enabled=payload.enabled)


@router.post("/devices/{device_id}/locate-response", response_model=DeviceRead)
async def device_locate_response(
    device_id: str,
    payload: LocateResponseRequest,
    x_device_secret: str | None = Header(default=None, alias="X-Device-Secret"),
    tenant_id: str | None = Depends(get_optional_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """A device answering the locate request above with its real fix, which also
    clears `locate_requested`.

    This route is the half that never existed. The flag had no clear path at
    all, so the dashboard read "Pending" forever whether or not the tablet had
    responded; and the tablet's answer went to `POST /v1/fleet/positions`, a
    VEHICLE endpoint needing a live driver session and a current vehicle
    binding. A parked, logged-off tablet has neither -- and that is the tablet
    someone reaching for "locate" is trying to find. One holding a binding to a
    since-deleted vehicle got a 404 instead, surfaced on the tablet as
    "Location request failed to send - HTTP 404 not found".

    Authenticates the same way the heartbeat does (device secret, or a bearer
    token for a tablet paired before secrets existed) so it works with nobody
    signed in.
    """
    device = await _authenticate_device_or_bearer(
        session, device_id=device_id, secret=x_device_secret, tenant_id=tenant_id
    )
    return await fleet_service.record_locate_response(
        session, device, lat=payload.lat, lng=payload.lng, accuracy_m=payload.accuracy_m
    )


@router.post("/devices/{device_id}/command-ack", response_model=DeviceRead)
async def device_command_ack(
    device_id: str,
    payload: CommandAckRequest,
    x_device_secret: str | None = Header(default=None, alias="X-Device-Secret"),
    tenant_id: str | None = Depends(get_optional_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """A device reporting that it acted on a queued command, clearing its flag.

    Same reason as `/locate-response`: without it an admin queues a restart and
    watches it say "Pending" for the life of the row, with no way to tell a
    tablet that restarted from one that never saw the request.
    """
    device = await _authenticate_device_or_bearer(
        session, device_id=device_id, secret=x_device_secret, tenant_id=tenant_id
    )
    return await fleet_service.record_command_ack(session, device, command=payload.command)


@router.post("/devices/{device_id}/reboot", response_model=DeviceRead)
async def set_device_reboot(
    device_id: str,
    payload: RebootRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
    _admin=Depends(_require_admin),
):
    """Admin-only. Queues a RESTART OF THE METER APP, which the device reads back
    on its next heartbeat and now actually carries out.

    HONESTY NOTE (blueprint 4.1.3/6.2.1), revised 2026-09-08: this still does
    not reboot the OS, and cannot -- that needs Device-Owner provisioning this
    fleet does not have (see `Device.reboot_requested`). What changed is that
    the flag is no longer inert: the app restarts its own process on seeing it
    and acknowledges via `POST /devices/{id}/command-ack`, which clears the flag.
    That covers the operational need this button exists for ("the meter is
    stuck, restart it") without claiming the thing it cannot do. The dashboard
    labels it "Restart app" for the same reason.
    """
    try:
        device = await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
    except fleet_service.FleetError as exc:
        raise _fleet_error_to_http(exc) from exc

    return await fleet_service.set_reboot_requested(session, device, enabled=payload.enabled)


@router.post("/devices/{device_id}/verify-admin-pin", response_model=VerifyAdminPinResponse)
async def verify_device_admin_pin(
    device_id: str,
    payload: VerifyAdminPinRequest,
    x_device_secret: str | None = Header(default=None, alias="X-Device-Secret"),
    user: User | None = Depends(get_optional_admin),
    tenant_id: str | None = Depends(get_optional_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """What a device calls before doing something destructive locally (e.g.
    the Android app's factory-reset flow — see
    au...SettingsViewModel.attemptFactoryReset) to check a PIN against the
    tenant's server-side admin_pin_hash, without the hash itself ever being
    sent to the device.

    ⚠ ROLE GATE (behaviour change). This route previously had NO role gate, and
    its docstring argued that was fine because "anyone can attempt an admin PIN
    on the physical device itself". That reasoning does not survive contact with
    a network endpoint: attempting a PIN on a tablet is one guess per physical
    interaction, whereas this is an unauthenticated-rate HTTP oracle that any
    tenant user — including a **driver** token, which is what the tablet holds —
    could query in a loop until the tenant's admin PIN fell out (backend audit
    §5). It is now `owner|admin` only.

    ✅ DEVICE-SECRET PATH (this is the replacement the note above promised).
    The tablet's factory-reset flow ran on a driver token, which the role gate
    correctly started refusing — and the right answer was never "give the tablet
    an admin token", which would hand every tablet in the fleet the power to
    administer the tenant. A tablet presenting a valid `X-Device-Secret`
    authenticates AS ITSELF: the tenant comes off its own row, so it can only
    ever check a PIN against the tenant it is enrolled in, and revoking the
    tablet closes this route to it along with everything else. No human token is
    involved in a factory reset any more.

    Exactly one of the two credentials is required. A device secret is checked
    first because it is the specific one; a bearer token still needs `owner` or
    `admin`, and a caller offering neither gets a 401.

    ⚠ RATE LIMIT + LOCKOUT. Keyed on the authenticated user (not the IP): the
    threat is a valid token grinding the PIN, and that token moves between IPs
    freely. VERIFY_ADMIN_PIN_PER_USER caps the attempt rate; independently, once
    VERIFY_ADMIN_PIN_LOCKOUT *failed* attempts accumulate the user is refused
    outright for the rest of that window. The lockout counter is advanced ONLY
    by a wrong PIN, and it is *checked* without being advanced, so a locked-out
    caller polling the endpoint cannot extend their own lockout indefinitely.

    `configured=False` (tenant has never set an admin PIN) is always
    accompanied by `valid=False`, but callers must check `configured`
    explicitly rather than inferring "not configured" from `valid=False`
    alone — that would be indistinguishable from "PIN set, but wrong"."""
    device: Device | None = None
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
        tenant_id = device.tenant_id
    elif user is None or tenant_id is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="This endpoint requires an X-Device-Secret header or an owner/admin bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    # The rate-limit and lockout key. Keyed on whichever principal actually
    # authenticated -- the threat is a valid credential grinding the PIN, and
    # both kinds of credential move between IPs freely. Keying a device on its
    # own id also means one compromised tablet cannot lock out an operator, and
    # vice versa.
    rate_key = f"device:{device.id}" if device is not None else user.id

    if peek_exhausted(VERIFY_ADMIN_PIN_LOCKOUT, "admin-pin-lock", rate_key):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many failed admin PIN attempts; locked out temporarily",
            headers={"Retry-After": "900"},
        )
    enforce(
        VERIFY_ADMIN_PIN_PER_USER,
        "admin-pin",
        rate_key,
        detail="Too many admin PIN attempts",
    )

    if device is None:
        try:
            await fleet_service.get_device_or_404(session, tenant_id=tenant_id, device_id=device_id)
        except fleet_service.FleetError as exc:
            raise _fleet_error_to_http(exc) from exc

    try:
        tenant = await tenant_service.check_admin_pin(session, tenant_id=tenant_id)
    except tenant_service.TenantError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found") from exc

    configured, valid = tenant_service.verify_admin_pin(tenant, pin=payload.pin)
    if not valid:
        register_failure(VERIFY_ADMIN_PIN_LOCKOUT, "admin-pin-lock", rate_key)
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
