"""Live Ops domain router.

Read-only fleet + driver rollups (`GET /v1/vehicles`, `GET /v1/drivers`)
joined from sibling domains' tables (fleet's `Vehicle`/`Device`, trips'
`Trip`, shift's `Shift`, user's `User`) — this domain owns no table of its
own (see the domain brief and `app.services.live_ops`'s module docstring), so
there is intentionally no create/update/delete for either resource. A later
integration step still wires this router in alongside its 11 siblings.

Plus the live-position pipeline:
  - `POST /v1/fleet/positions` — a device/tick handler publishes one vehicle's
    current lat/lng/status. Not persisted to the database (in-process pub/sub
    only, see `app.services.live_ops`) — the closest CRUD-shape analogy is an
    append-only broadcast: there's a create (`POST`) and reads (`GET .../` to
    list every vehicle's latest cached position for the tenant, and
    `GET .../{vehicle_id}` for one), but no update (a new `POST` simply
    supersedes the previous cached value for that vehicle — there's nothing to
    PATCH) and no delete (nothing durable exists to delete; the cache entry is
    just superseded or, on process restart, gone).
  - `WS /v1/fleet/live` — a dispatcher dashboard subscribes here and receives
    every position subsequently published for its own tenant, in real time,
    as JSON `{vehicle_id, lat, lng, status, updated_at}` messages.

CAUTION on endpoint paths: this domain's brief specifies literal paths
(`/v1/vehicles`, `/v1/drivers`, `/v1/fleet/live`, `/v1/fleet/positions`) that
do NOT follow the general "router prefix /v1/<domain>" convention in the
foundation contract. They're honored exactly as specified in the brief, since
an explicit, itemized endpoint list is more specific than the general
fallback rule. This does not collide with the sibling `fleet` domain's own
router (which owns `/v1/fleet/vehicles` and `/v1/fleet/devices` for actual
CRUD of those tables) — `/v1/fleet/live` and `/v1/fleet/positions` are
distinct path segments under the same `/v1/fleet` prefix, and `/v1/vehicles`
/ `/v1/drivers` (no `/fleet` segment) are different paths entirely. Verified
by inspecting `app/api/v1/fleet.py` before writing this file.

Every query in this file is filtered by `tenant_id` resolved via
`get_current_tenant_id` — the sole multi-tenancy isolation mechanism in this
system (see `app.core.security` / `app.core.database` docstrings).
"""
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from jose import JWTError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import (
    PLATFORM_TENANT_ID,
    decode_token,
    get_current_tenant_id,
    revocation_store,
)
from app.schemas.live_ops import (
    DriverLiveRead,
    Page,
    PositionPublishRequest,
    PositionPublishResponse,
    PositionRead,
    VehicleLiveRead,
    VehiclePositionHistoryRead,
)
from app.services import live_ops as live_ops_service

router = APIRouter(tags=["live-ops"])


def _live_ops_error_to_http(exc: live_ops_service.LiveOpsError) -> HTTPException:
    if isinstance(exc, live_ops_service.VehicleNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Vehicle not found")
    if isinstance(exc, live_ops_service.DriverNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Driver not found")
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


# ==================================================================================
# Vehicles (read-only rollup — see module docstring)
# ==================================================================================


@router.get("/v1/vehicles", response_model=Page[VehicleLiveRead])
async def list_vehicles(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    status_filter: str | None = Query(default=None, alias="status", description="Vehicle.status"),
    vehicle_class: str | None = Query(default=None),
    rego: str | None = Query(default=None, description="Case-insensitive partial match"),
    live_status: str | None = Query(default=None, description="e.g. available | on_trip | offline"),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    items, total = await live_ops_service.list_vehicles_live(
        session,
        tenant_id=tenant_id,
        status_filter=status_filter,
        vehicle_class=vehicle_class,
        rego=rego,
        live_status=live_status,
        skip=skip,
        limit=limit,
    )
    return Page[VehicleLiveRead](items=items, total=total, skip=skip, limit=limit)


@router.get("/v1/vehicles/{vehicle_id}", response_model=VehicleLiveRead)
async def get_vehicle(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await live_ops_service.get_vehicle_live(session, tenant_id=tenant_id, vehicle_id=vehicle_id)
    except live_ops_service.LiveOpsError as exc:
        raise _live_ops_error_to_http(exc) from exc


@router.get("/v1/vehicles/{vehicle_id}/position-history", response_model=VehiclePositionHistoryRead)
async def get_vehicle_position_history(
    vehicle_id: str,
    since: datetime | None = Query(
        default=None,
        description="ISO datetime -- only return points recorded at/after this time. Omit for the "
        "vehicle's full remaining retention window (see "
        "app.services.live_ops.POSITION_HISTORY_RETENTION_HOURS).",
    ),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Durable position history for one vehicle -- the dispatcher "scrub back
    through the last few hours" replay feature. Backed by the new
    `app.models.fleet.VehiclePositionHistory` table (see its own docstring
    and `app.services.live_ops`'s module docstring), NOT the ephemeral
    in-memory `_FleetBroadcaster` cache that every other endpoint in this
    file reads from -- this is durable history, not "what's the latest".
    404s if `vehicle_id` doesn't belong to the caller's tenant, same
    `get_vehicle_or_404` pattern as every other vehicle-scoped endpoint here.

    Also returns `harsh_brake_events`/`rapid_accel_events` -- see
    `VehiclePositionHistoryRead`'s own docstring for why these are
    informational telematics signals, not a certified/legal safety score."""
    try:
        return await live_ops_service.get_position_history(
            session, tenant_id=tenant_id, vehicle_id=vehicle_id, since=since
        )
    except live_ops_service.LiveOpsError as exc:
        raise _live_ops_error_to_http(exc) from exc


# ==================================================================================
# Drivers (read-only rollup — see module docstring)
# ==================================================================================


@router.get("/v1/drivers", response_model=Page[DriverLiveRead])
async def list_drivers(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    status_filter: str | None = Query(default=None, alias="status", description="User.status"),
    on_shift: bool | None = Query(default=None),
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    items, total = await live_ops_service.list_drivers_live(
        session,
        tenant_id=tenant_id,
        status_filter=status_filter,
        on_shift=on_shift,
        skip=skip,
        limit=limit,
    )
    return Page[DriverLiveRead](items=items, total=total, skip=skip, limit=limit)


@router.get("/v1/drivers/{driver_id}", response_model=DriverLiveRead)
async def get_driver(
    driver_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await live_ops_service.get_driver_live(session, tenant_id=tenant_id, driver_id=driver_id)
    except live_ops_service.LiveOpsError as exc:
        raise _live_ops_error_to_http(exc) from exc


# ==================================================================================
# Live positions (ephemeral pub/sub — see module docstring for the CRUD-shape note)
# ==================================================================================


@router.post("/v1/fleet/positions", response_model=PositionPublishResponse, status_code=status.HTTP_201_CREATED)
async def publish_position(
    payload: PositionPublishRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    session: AsyncSession = Depends(get_session),
):
    """Publishes one vehicle's current position/status: updates the in-memory
    latest-position cache (enriches `GET /v1/vehicles`) and fans the update
    out to every `WS /v1/fleet/live` listener currently connected for this
    tenant. 404s if `vehicle_id` doesn't belong to the caller's tenant."""
    try:
        return await live_ops_service.publish_position(
            session,
            tenant_id=tenant_id,
            vehicle_id=payload.vehicle_id,
            lat=payload.lat,
            lng=payload.lng,
            status=payload.status,
            battery=payload.battery,
            network=payload.network,
            speed_kmh=payload.speed_kmh,
            heading=payload.heading,
        )
    except live_ops_service.LiveOpsError as exc:
        raise _live_ops_error_to_http(exc) from exc


@router.get("/v1/fleet/positions", response_model=list[PositionRead])
async def list_positions(
    tenant_id: str = Depends(get_current_tenant_id),
):
    """Every vehicle's latest cached position for this tenant. In-memory
    only — empty after a process restart until the next publish."""
    latest = live_ops_service.fleet_broadcaster.get_all_latest(tenant_id)
    return list(latest.values())


@router.get("/v1/fleet/positions/{vehicle_id}", response_model=PositionRead)
async def get_position(
    vehicle_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
):
    """The latest cached position for one vehicle. 404s if nothing has ever
    been published for it (this endpoint does not fall back to trip data —
    that richer fallback is what `GET /v1/vehicles/{vehicle_id}` is for)."""
    position = live_ops_service.fleet_broadcaster.get_latest(tenant_id, vehicle_id)
    if position is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No cached position for this vehicle")
    return position


# ==================================================================================
# WS /v1/fleet/live
# ==================================================================================


async def _authenticate_ws(websocket: WebSocket) -> str:
    """Resolves tenant_id for a websocket connection.

    Browser websocket clients can't set an `Authorization` header, so the
    access token travels as a `?token=` query param instead — same
    claims/expiry/revocation rules as the HTTP bearer flow in
    `app.core.security.get_token_payload`, just a different transport. Mirrors
    `get_current_tenant_id`'s owner/PLATFORM_TENANT_ID cross-tenant override,
    via a `tenant_id` query param instead of `Request.query_params` (there is
    no `Request` object on a websocket connection).
    """
    token = websocket.query_params.get("token")
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")

    try:
        payload = decode_token(token)
    except JWTError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token") from exc

    jti = payload.get("jti")
    if jti and await revocation_store.is_revoked(jti):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token revoked")

    token_tenant_id = payload.get("tenant_id")
    role = payload.get("role")

    if role == "owner" and token_tenant_id == PLATFORM_TENANT_ID:
        override = websocket.query_params.get("tenant_id")
        return override or token_tenant_id

    if not token_tenant_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Token has no tenant scope")
    return token_tenant_id


@router.websocket("/v1/fleet/live")
async def fleet_live(websocket: WebSocket):
    """Streams every position published via `POST /v1/fleet/positions` for the
    connecting client's own tenant, as JSON
    `{vehicle_id, lat, lng, status, updated_at}` messages, for as long as the
    connection stays open.

    This is a send-only feed from the server's perspective — the handler does
    not read/act on any client-sent messages, it only relays broadcaster
    output. Disconnect is detected when a send against a closed socket raises
    (`WebSocketDisconnect` or `RuntimeError`, depending on how the close was
    observed by Starlette); either way the subscriber queue is always cleaned
    up in the `finally` block so a dropped client never leaks a queue entry in
    `_FleetBroadcaster._subscribers`.
    """
    try:
        tenant_id = await _authenticate_ws(websocket)
    except HTTPException as exc:
        code = 4401 if exc.status_code == status.HTTP_401_UNAUTHORIZED else 4403
        await websocket.close(code=code, reason=exc.detail)
        return

    await websocket.accept()
    queue = await live_ops_service.fleet_broadcaster.subscribe(tenant_id)
    try:
        while True:
            position = await queue.get()
            await websocket.send_json(position)
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        await live_ops_service.fleet_broadcaster.unsubscribe(tenant_id, queue)
