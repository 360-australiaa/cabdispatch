"""Messages domain API — `/v1/messages`.

Dispatch<->driver threaded messaging: `POST /` sends a message (either side),
`GET /?driver_id=` lists a driver's thread (paginated, oldest-first), `POST
/{id}/read` marks a message read, and `WS /live?driver_id=` is the real-time
delivery channel a driver's device (or a dispatch dashboard) connects to.
Reuses the exact in-process pub/sub pattern from `app.api.v1.duress` /
`app.services.duress.GPSBroadcaster` — no Redis, see
`app.services.messages.MessageBroadcaster`.

Also: `GET /templates` lists the fixed canned-template menu (driver-side
quick-request codes like "no_job"/"recall"/"job_query"/"other", and
dispatch-side quick-status codes like "check_in"/"return_to_depot"), and
`POST /templates/{code}` sends one — it's a thin body-composition step in
front of the exact same `send_message` service path `POST /` uses, not a
parallel message-creation path. See `app.services.messages.MESSAGE_TEMPLATES`.

Threading model: a "thread" is just every `Message` sharing a `thread_id` for
one driver's ongoing conversation with dispatch. Per the domain brief this is
intentionally NOT a generic multi-thread system — `thread_id` always equals
the driver's own id (one thread per driver); see `app.models.messages`'s
module docstring.

Every query is filtered by the tenant_id resolved via `get_current_tenant_id`
— the sole multi-tenancy isolation mechanism in this system (see
app.core.security / app.core.database docstrings). This includes the
websocket endpoint, which cannot use a `Depends()`-based HTTP dependency (see
`_authenticate_websocket` below, mirrored from `app.api.v1.duress`) but
applies the identical tenant-scoping rule by hand.

Role policy:
  - `POST /`: any authenticated tenant user (dispatch or driver). `sender_type`
    and `sender_user_id` are always derived from the caller's own
    identity/role, never trusted from the request body — a `driver`-role
    caller's messages are always attributed to (and threaded under) their own
    id; anyone else (owner/admin/dispatcher — "dispatch") must supply
    `driver_id` in the body to say which driver's thread they're posting to.
  - `GET /`, `POST /{id}/read`: any authenticated tenant user — both sides of
    a conversation need to read/ack it. No ownership check beyond tenant
    scoping (kept simple, per the domain brief) — any tenant user can list
    any driver's thread or mark any message in it read.
  - `WS /live`: any authenticated tenant user, but a `driver`-role caller may
    only subscribe to their own `driver_id` (owner/admin/dispatcher may
    subscribe to any driver in their tenant — a dispatch dashboard's-eye
    view).

NOTE: this router is not yet registered in `app.main` — that happens in a
later integration step that wires all domain routers together, same as every
other domain slice in this codebase pre-integration. Tests below are written
correctly against the endpoints as built and will 404 until that lands.
"""
from __future__ import annotations

from fastapi import (
    APIRouter,
    Depends,
    HTTPException,
    Query,
    WebSocket,
    WebSocketDisconnect,
    status,
)
from jose import JWTError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import (
    PLATFORM_TENANT_ID,
    decode_token,
    get_current_tenant_id,
    get_current_user,
)
from app.schemas.messages import (
    MessageCreate,
    MessageRead,
    MessageTemplateRead,
    Page,
    TemplateMessageCreate,
)
from app.services import messages as messages_service

router = APIRouter(prefix="/v1/messages", tags=["messages"])

_DRIVER_ROLE = "driver"


def _messages_error_to_http(exc: messages_service.MessagesError) -> HTTPException:
    if isinstance(exc, messages_service.MessageNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Message not found")
    if isinstance(exc, messages_service.DriverIdRequiredError):
        return HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="driver_id is required when sending as dispatch",
        )
    if isinstance(exc, messages_service.TemplateNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Template not found")
    if isinstance(exc, messages_service.TemplateNotAllowedForSenderError):
        return HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="This template is not available to your role",
        )
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


@router.post("", response_model=MessageRead, status_code=status.HTTP_201_CREATED)
async def send_message(
    payload: MessageCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """A `driver`-role caller always sends as themselves (their own id is
    used as `driver_id`, ignoring anything in the body); every other role
    sends as "dispatch" into the `driver_id` thread named in the body."""
    sender_type = "driver" if current_user.role == _DRIVER_ROLE else "dispatch"
    driver_id = current_user.id if sender_type == "driver" else payload.driver_id

    try:
        message = await messages_service.send_message(
            session,
            tenant_id=tenant_id,
            driver_id=driver_id,
            sender_type=sender_type,
            sender_user_id=current_user.id,
            body=payload.body,
        )
    except messages_service.MessagesError as exc:
        raise _messages_error_to_http(exc) from exc

    ws_payload = MessageRead.model_validate(message).model_dump(mode="json")
    await messages_service.message_broadcaster.publish(tenant_id, driver_id, ws_payload)

    return message


@router.get("", response_model=Page[MessageRead])
async def list_thread(
    driver_id: str = Query(...),
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=200),
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    items, total = await messages_service.list_thread(
        session, tenant_id=tenant_id, driver_id=driver_id, skip=skip, limit=limit
    )
    return Page[MessageRead](items=items, total=total, skip=skip, limit=limit)


@router.post("/{message_id}/read", response_model=MessageRead)
async def mark_read(
    message_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        message = await messages_service.get_message_or_404(
            session, tenant_id=tenant_id, message_id=message_id
        )
    except messages_service.MessagesError as exc:
        raise _messages_error_to_http(exc) from exc

    return await messages_service.mark_read(session, message)


# --- canned templates --------------------------------------------------------


@router.get("/templates", response_model=list[MessageTemplateRead])
async def list_templates(
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
):
    """Lists every available canned template (both driver-side quick-request
    codes and dispatch-side quick-status codes) so the client doesn't need to
    hardcode the menu. `tenant_id`/`_user` are required only to enforce this
    is an authenticated tenant call, same as every other endpoint in this
    router -- the template list itself is not tenant-specific."""
    return messages_service.list_templates()


@router.post("/templates/{code}", response_model=MessageRead, status_code=status.HTTP_201_CREATED)
async def send_template_message(
    code: str,
    payload: TemplateMessageCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    current_user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """Quick-tap equivalent of `POST /v1/messages`: resolves `code` to a
    canned template and creates a real `Message` row through the exact same
    `app.services.messages.send_message` path (via `send_template_message`),
    so it shows up in the thread and over the live websocket identically to
    a free-text send. Sender attribution follows the same rule as the
    free-text endpoint above -- a `driver`-role caller always sends as
    themselves; anyone else must supply `driver_id` and can only use
    dispatch-side template codes."""
    sender_type = "driver" if current_user.role == _DRIVER_ROLE else "dispatch"
    driver_id = current_user.id if sender_type == "driver" else payload.driver_id

    try:
        message = await messages_service.send_template_message(
            session,
            tenant_id=tenant_id,
            driver_id=driver_id,
            sender_type=sender_type,
            sender_user_id=current_user.id,
            code=code,
            note=payload.note,
        )
    except messages_service.MessagesError as exc:
        raise _messages_error_to_http(exc) from exc

    ws_payload = MessageRead.model_validate(message).model_dump(mode="json")
    await messages_service.message_broadcaster.publish(tenant_id, driver_id, ws_payload)

    return message


# --- live websocket relay ------------------------------------------------------


async def _authenticate_websocket(websocket: WebSocket) -> dict | None:
    """Mirrors `app.api.v1.duress._authenticate_websocket` exactly, minus
    that domain's dispatch-only role restriction — see module docstring for
    who may connect here and the extra per-driver check applied after this
    returns. The token is accepted either as a `?token=` query param
    (browsers can't set custom headers on the websocket handshake) or an
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

    return payload


def _resolve_ws_tenant_id(websocket: WebSocket, payload: dict) -> str | None:
    """Mirrors `app.core.security.get_current_tenant_id`'s owner/PLATFORM_TENANT_ID
    cross-tenant rule, for the websocket route (same as `app.api.v1.duress`)."""
    token_tenant_id = payload.get("tenant_id")
    role = payload.get("role")

    if role == "owner" and token_tenant_id == PLATFORM_TENANT_ID:
        override = websocket.query_params.get("tenant_id")
        return override or token_tenant_id

    return token_tenant_id


@router.websocket("/live")
async def live(websocket: WebSocket, driver_id: str) -> None:
    """Real-time delivery channel: broadcasts every message sent via
    `POST /v1/messages` for this driver's thread to all connected listeners.
    Backed by the in-process pub/sub in
    `app.services.messages.message_broadcaster` (see that class's docstring
    for the Redis swap-in path)."""
    payload = await _authenticate_websocket(websocket)
    if payload is None:
        return  # rejected + closed inside _authenticate_websocket

    tenant_id = _resolve_ws_tenant_id(websocket, payload)
    if not tenant_id:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Token has no tenant scope")
        return

    if payload.get("role") == _DRIVER_ROLE and payload.get("sub") != driver_id:
        await websocket.close(
            code=status.WS_1008_POLICY_VIOLATION,
            reason="Drivers may only subscribe to their own thread",
        )
        return

    await websocket.accept()
    queue = messages_service.message_broadcaster.subscribe(tenant_id, driver_id)
    try:
        while True:
            message = await queue.get()
            await websocket.send_json(message)
    except WebSocketDisconnect:
        pass
    finally:
        messages_service.message_broadcaster.unsubscribe(tenant_id, driver_id, queue)
