"""Duress domain API — `/v1/duress`.

Lifecycle actions (`/trigger`, `/{id}/cancel`, `/{id}/escalate`, `/{id}/close`),
the live GPS relay (`POST /{id}/gps` + `WS /{id}/live`), and standard CRUD.
Every query is filtered by the tenant_id resolved via `get_current_tenant_id` —
the sole multi-tenancy isolation boundary in this codebase, per the shared
foundation contract. This includes the websocket endpoint, which cannot use a
`Depends()`-based HTTP dependency (see `_authenticate_websocket` below) but
applies the identical tenant-scoping rule by hand.

Role policy:
  - `trigger` / `cancel` / `gps` / `audio` (upload + playback): any
    authenticated tenant user — these are the actions a driver's own device
    takes (panic button, self-cancel, location streaming, and audio capture,
    while in duress).
  - `escalate` / `close`: restricted to `owner`/`admin`/`dispatcher` — per the
    domain brief, escalation is "a background/dispatcher trigger", never the
    driver's own device. Reaching escalate's final stage also fires a real
    Twilio Voice automated call to the tenant's emergency contact (blueprint
    8.3) — see `app.services.duress.escalate_event`.
  - `WS /{id}/live`: restricted to `owner`/`admin`/`dispatcher` — this is the
    dispatch dashboard's live feed, not a driver-facing surface.
  - Generic `POST`/`PATCH`/`DELETE` (CRUD completeness/admin corrections):
    restricted to `owner`/`admin`/`dispatcher`. `DELETE` in particular is a
    safety-incident record — kept for admin data-hygiene (e.g. purging test
    events) but should be used sparingly in production; it is NOT how an event
    gets closed (use `/close`).
"""
from __future__ import annotations

from datetime import UTC, datetime

from fastapi import (
    APIRouter,
    Depends,
    File,
    HTTPException,
    Query,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
    status,
)
from fastapi.responses import FileResponse
from jose import JWTError
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import (
    PLATFORM_TENANT_ID,
    decode_token,
    get_current_tenant_id,
    get_current_user,
    require_role,
)
from app.models.duress import DuressEvent
from app.schemas.duress import (
    DuressCancelRequest,
    DuressCloseRequest,
    DuressEscalateRequest,
    DuressEventCreate,
    DuressEventListResponse,
    DuressEventRead,
    DuressEventUpdate,
    DuressGpsPoint,
    DuressTriggerRequest,
)
from app.services.duress import (
    DuressAudioError,
    cancel_event,
    close_event,
    escalate_event,
    gps_broadcaster,
    resolve_absolute_path,
    save_duress_audio,
    trigger_event,
)

router = APIRouter(prefix="/v1/duress", tags=["duress"])

# Roles permitted to escalate/close an event, run the dispatch live feed, and
# perform admin CRUD corrections. Drivers trigger/cancel/stream GPS on their
# own events but do not drive escalation.
_DISPATCH_ROLES = ("owner", "admin", "dispatcher")


async def _get_owned_event(session: AsyncSession, *, tenant_id: str, event_id: str) -> DuressEvent:
    result = await session.execute(
        select(DuressEvent).where(DuressEvent.id == event_id, DuressEvent.tenant_id == tenant_id)
    )
    event = result.scalar_one_or_none()
    if event is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Duress event not found")
    return event


# --- lifecycle actions -------------------------------------------------------


@router.post("/trigger", response_model=DuressEventRead, status_code=status.HTTP_201_CREATED)
async def trigger(
    body: DuressTriggerRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    """Opens a duress event and starts its 10-second cancel window."""
    return await trigger_event(
        session,
        tenant_id=tenant_id,
        vehicle_id=body.vehicle_id,
        driver_id=body.driver_id,
        trigger=body.trigger,
        gps_stream_ref=body.gps_stream_ref,
        audio_ref=body.audio_ref,
    )


@router.post("/{event_id}/cancel", response_model=DuressEventRead)
async def cancel(
    event_id: str,
    body: DuressCancelRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    """Cancels an event — only valid inside its cancel window (status must
    still be `open` and the wall-clock deadline recorded at trigger time must
    not have passed)."""
    event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)
    return await cancel_event(session, event, note=body.note)


@router.post("/{event_id}/escalate", response_model=DuressEventRead)
async def escalate(
    event_id: str,
    body: DuressEscalateRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    """Advances the escalation cascade by one stage (cancel_window_expired ->
    notify_dispatch -> sms_emergency_contacts -> present_000_call_script).
    Reaching the final stage also fires the real Twilio Voice automated
    escalation call — see `app.services.duress.escalate_event`."""
    event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)
    return await escalate_event(
        session, event, note=body.note, emergency_contact_phone=body.emergency_contact_phone
    )


@router.post("/{event_id}/close", response_model=DuressEventRead)
async def close(
    event_id: str,
    body: DuressCloseRequest,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    """Resolves/closes an event."""
    event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)
    return await close_event(session, event, note=body.note)


# --- live GPS relay -----------------------------------------------------------


@router.post("/{event_id}/gps", status_code=status.HTTP_202_ACCEPTED)
async def post_gps(
    event_id: str,
    point: DuressGpsPoint,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Broadcasts a GPS fix to every dashboard listener currently connected to
    `WS /v1/duress/{event_id}/live`. Not persisted (see
    `app.services.duress.GPSBroadcaster`) — this is a live relay, not a store."""
    event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)

    payload = point.model_dump(mode="json")
    payload["ts"] = payload.get("ts") or datetime.now(UTC).isoformat()
    payload["event_id"] = event.id

    delivered = await gps_broadcaster.publish(event.id, payload)
    return {"delivered_to": delivered}


# --- audio recording upload/playback -------------------------------------------


@router.post("/{event_id}/audio", response_model=DuressEventRead)
async def upload_audio(
    event_id: str,
    file: UploadFile = File(...),
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    """Multipart upload of a captured duress audio recording (e.g. an
    Android `MediaRecorder` `.m4a`/`.3gp` file) — a driver-device action, same
    role policy as `/trigger` / `/cancel` / `/gps`. Saves it to local disk
    under `uploads/{tenant_id}/duress/{event_id}/` (created if missing),
    following the EXACT same local-disk-upload convention as
    `app.services.compliance` / `app.services.receipts`, and stores the
    resulting *relative* path on `DuressEvent.audio_ref` — overwriting
    whatever placeholder ref (if any) was supplied at trigger time, since
    this is the real captured recording."""
    event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)

    content = await file.read()
    try:
        relative_path = await save_duress_audio(
            tenant_id=tenant_id,
            event_id=event.id,
            original_filename=file.filename or "audio",
            content=content,
        )
    except DuressAudioError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    event.audio_ref = relative_path
    await session.commit()
    await session.refresh(event)
    return event


@router.get("/{event_id}/audio")
async def get_audio(
    event_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """Streams back the duress audio recording uploaded via
    `POST /v1/duress/{event_id}/audio`, so e.g. the dashboard Safety/Duress
    Desk can play it back. 404s if no recording is on file, or if
    `audio_ref` points at a reference that was never actually uploaded here
    (e.g. a device-supplied placeholder from `/trigger`) rather than a local
    file."""
    event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)
    if not event.audio_ref:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No audio recording on file for this duress event",
        )

    try:
        absolute_path = resolve_absolute_path(event.audio_ref)
    except DuressAudioError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    if not absolute_path.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Duress event has an audio_ref but its file is missing on disk",
        )

    return FileResponse(path=absolute_path, filename=absolute_path.name)


async def _authenticate_websocket(websocket: WebSocket) -> dict | None:
    """Websocket connections can't use the HTTP-only `Depends(get_current_tenant_id)`
    chain (it's built on `Request`, not `WebSocket`), so this re-implements the
    same bearer-token decode + tenant-scoping rule by hand for the one websocket
    route in this domain. The token is accepted either as a `?token=` query
    param (browsers can't set custom headers on the websocket handshake) or an
    `Authorization: Bearer` header (non-browser clients).

    Returns the decoded JWT payload, or `None` if the connection was rejected
    (a close frame has already been sent in that case — the caller must not
    call `.accept()`)."""
    token = websocket.query_params.get("token")
    if not token:
        auth_header = websocket.headers.get("authorization")
        if auth_header and auth_header.lower().startswith("bearer "):
            token = auth_header.split(" ", 1)[1]

    if not token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Missing bearer token")
        return None

    try:
        payload = decode_token(token)
    except JWTError:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Invalid token")
        return None

    if payload.get("role") not in _DISPATCH_ROLES:
        await websocket.close(
            code=status.WS_1008_POLICY_VIOLATION, reason="Requires a dispatch-side role"
        )
        return None

    return payload


def _resolve_ws_tenant_id(websocket: WebSocket, payload: dict) -> str:
    """Mirrors `app.core.security.get_current_tenant_id`'s owner/PLATFORM_TENANT_ID
    cross-tenant rule, for the websocket route."""
    token_tenant_id = payload.get("tenant_id")
    role = payload.get("role")

    if role == "owner" and token_tenant_id == PLATFORM_TENANT_ID:
        override = websocket.query_params.get("tenant_id")
        return override or token_tenant_id

    return token_tenant_id


@router.websocket("/{event_id}/live")
async def live(
    websocket: WebSocket,
    event_id: str,
    session: AsyncSession = Depends(get_session),
) -> None:
    """Dashboard-side live feed: broadcasts every GPS point POSTed to
    `POST /v1/duress/{event_id}/gps` for this event to all connected listeners.
    Backed by the in-process pub/sub in `app.services.duress.GPSBroadcaster`
    (see that class's docstring for the Redis swap-in path)."""
    payload = await _authenticate_websocket(websocket)
    if payload is None:
        return  # rejected + closed inside _authenticate_websocket

    tenant_id = _resolve_ws_tenant_id(websocket, payload)
    if not tenant_id:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Token has no tenant scope")
        return

    try:
        event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)
    except HTTPException:
        # HTTPException's exception-handler middleware only applies to HTTP
        # routes; on a websocket route we must translate it to a close frame
        # ourselves before the handshake ever completes.
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Duress event not found")
        return

    await websocket.accept()
    queue = gps_broadcaster.subscribe(event.id)
    try:
        while True:
            point = await queue.get()
            await websocket.send_json(point)
    except WebSocketDisconnect:
        pass
    finally:
        gps_broadcaster.unsubscribe(event.id, queue)


# --- standard CRUD ------------------------------------------------------------


@router.get("", response_model=DuressEventListResponse)
async def list_events(
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
    driver_id: str | None = Query(default=None),
    vehicle_id: str | None = Query(default=None),
    status_filter: str | None = Query(default=None, alias="status"),
    trigger_filter: str | None = Query(default=None, alias="trigger"),
    open_only: bool = Query(
        default=False, description="If true, only return non-terminal (not resolved/cancelled) events."
    ),
    opened_at_from: datetime | None = Query(default=None),
    opened_at_to: datetime | None = Query(default=None),
) -> dict:
    filters = [DuressEvent.tenant_id == tenant_id]
    if driver_id is not None:
        filters.append(DuressEvent.driver_id == driver_id)
    if vehicle_id is not None:
        filters.append(DuressEvent.vehicle_id == vehicle_id)
    if status_filter is not None:
        filters.append(DuressEvent.status == status_filter)
    if trigger_filter is not None:
        filters.append(DuressEvent.trigger == trigger_filter)
    if open_only:
        filters.append(DuressEvent.status.notin_(["resolved", "cancelled"]))
    if opened_at_from is not None:
        filters.append(DuressEvent.opened_at >= opened_at_from)
    if opened_at_to is not None:
        filters.append(DuressEvent.opened_at <= opened_at_to)

    total = (await session.execute(select(func.count(DuressEvent.id)).where(*filters))).scalar_one()

    result = await session.execute(
        select(DuressEvent)
        .where(*filters)
        .order_by(DuressEvent.opened_at.desc())
        .limit(limit)
        .offset(offset)
    )
    items = result.scalars().all()

    return {"items": items, "total": total, "limit": limit, "offset": offset}


@router.post("", response_model=DuressEventRead, status_code=status.HTTP_201_CREATED)
async def create_event(
    body: DuressEventCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    """Generic create for CRUD completeness/admin backfill. The normal path for
    opening an event is `POST /v1/duress/trigger`."""
    data = body.model_dump()
    data["opened_at"] = data["opened_at"] or datetime.now(UTC)
    data["escalation_log_json"] = data["escalation_log_json"] or {
        "cancel_window_seconds": 10,
        "cancel_deadline_at": None,
        "next_stage_index": 0,
        "entries": [],
    }
    event = DuressEvent(tenant_id=tenant_id, **data)
    session.add(event)
    await session.commit()
    await session.refresh(event)
    return event


@router.get("/{event_id}", response_model=DuressEventRead)
async def get_event(
    event_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    return await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)


@router.patch("/{event_id}", response_model=DuressEventRead)
async def update_event(
    event_id: str,
    body: DuressEventUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(event, field, value)
    await session.commit()
    await session.refresh(event)
    return event


@router.delete("/{event_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_event(
    event_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> None:
    event = await _get_owned_event(session, tenant_id=tenant_id, event_id=event_id)
    await session.delete(event)
    await session.commit()
