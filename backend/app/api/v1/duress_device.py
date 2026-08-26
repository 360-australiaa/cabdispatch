"""Device-path duress API -- POST /v1/devices/auth (the HMAC handshake),
POST /v1/devices/{id}/heartbeat, POST /v1/duress/device/alarm,
POST /v1/duress/device/{event_id}/gps, POST /v1/duress/device/{event_id}/audio,
plus admin CRUD for duress-devices themselves. See
docs/DURESS_DEVICE_INTEGRATION.md for the full wire contract and
app.api.v1.duress for the tablet-side sibling this domain correlates with.

Router prefix is bare "/v1" (not "/v1/devices") because routes span multiple
path families per the integration contract -- every route below spells out
its full path.

Role policy:
  - Admin CRUD on duress-devices (create/list/get/update/rotate-secret/
    delete): restricted to owner/admin/dispatcher, same _DISPATCH_ROLES gate
    as app.api.v1.duress -- provisioning/decommissioning physical hardware is
    a dispatch-side action, never a driver-facing one.
  - POST /v1/devices/auth: no auth dependency at all -- this IS how a device
    obtains its bearer token in the first place (see
    app.services.duress_device.authenticate_device).
  - Every other device-path route (heartbeat/alarm/gps/audio) is gated by
    Depends(get_current_device), a NEW dependency that decodes a
    duress_device-typed JWT via app.services.duress_device.decode_device_token.
    This is deliberately separate from app.core.security.get_current_user: a
    device token must never satisfy a human role check, and a human access
    token must never satisfy this one, by construction of the token "type"
    claim both sides check.
"""
from __future__ import annotations

from fastapi import (
    APIRouter,
    Depends,
    File,
    HTTPException,
    Query,
    Request,
    UploadFile,
    status,
)
from jose import JWTError
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import get_session
from app.core.security import get_current_tenant_id, get_current_user, require_role
from app.models.duress import DuressEvent
from app.models.duress_device import DuressDevice
from app.schemas.duress import DuressEventRead
from app.schemas.duress_device import (
    DeviceAlarmRequest,
    DeviceAlarmResponse,
    DeviceAuthRequest,
    DeviceAuthResponse,
    DeviceGpsBatch,
    DeviceHeartbeatRequest,
    DuressDeviceCreate,
    DuressDeviceListResponse,
    DuressDeviceRead,
    DuressDeviceRotateSecret,
    DuressDeviceUpdate,
)
from app.services.duress import DuressAudioError, save_duress_audio
from app.services.duress_device import (
    DeviceAuthError,
    DeviceCodeConflictError,
    authenticate_device,
    create_device,
    decode_device_token,
    ingest_device_gps,
    mint_device_token,
    open_or_attach_device_alarm,
    record_heartbeat,
    rotate_device_secret,
)

router = APIRouter(prefix="/v1", tags=["duress-device"])

# Same admin/dispatch-role gate as app.api.v1.duress._DISPATCH_ROLES, for the
# provisioning/decommissioning CRUD routes on duress-devices.
_DISPATCH_ROLES = ("owner", "admin", "dispatcher")


async def get_current_device(request: Request) -> dict:
    """Device-token counterpart to app.core.security.get_current_user.
    Reads the Authorization: Bearer header by hand (device firmware, not a
    browser, calls these routes, so there is no need for FastAPI's
    HTTPBearer security-scheme machinery) and decodes it via
    app.services.duress_device.decode_device_token. Raises 401 on a missing
    header, a malformed/expired JWT, or a token that is not duress_device
    typed -- this is deliberately NOT get_current_user: a human access token
    must never satisfy this dependency, and a device token must never satisfy
    a human require_role() check, by construction of the token type claim."""
    auth_header = request.headers.get("authorization")
    if not auth_header or not auth_header.lower().startswith("bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing device bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token = auth_header.split(" ", 1)[1]
    try:
        return decode_device_token(token)
    except (JWTError, DeviceAuthError):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired device token",
            headers={"WWW-Authenticate": "Bearer"},
        )


async def _get_owned_device(session: AsyncSession, *, tenant_id: str, device_id: str) -> DuressDevice:
    result = await session.execute(
        select(DuressDevice).where(DuressDevice.id == device_id, DuressDevice.tenant_id == tenant_id)
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Duress device not found")
    return device


# --- admin CRUD (owner/admin/dispatcher) --------------------------------------


@router.post("/duress-devices", response_model=DuressDeviceRead, status_code=status.HTTP_201_CREATED)
async def create_duress_device(
    body: DuressDeviceCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> DuressDevice:
    """Provisions a new physical CT-DPD-01 device row for this tenant.
    body.plaintext_secret (K_dev) is Fernet-encrypted at rest immediately and
    never returned by any read endpoint again -- copy it down now."""
    try:
        return await create_device(session, tenant_id=tenant_id, body=body)
    except DeviceCodeConflictError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc


@router.get("/duress-devices", response_model=DuressDeviceListResponse)
async def list_duress_devices(
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
) -> dict:
    """Tenant-scoped, paginated -- any authenticated user may list the
    tenant's provisioned devices (read-only visibility, unlike the
    provisioning/modification routes below which are dispatch-role gated)."""
    filters = [DuressDevice.tenant_id == tenant_id]

    total = (await session.execute(select(func.count(DuressDevice.id)).where(*filters))).scalar_one()

    result = await session.execute(
        select(DuressDevice)
        .where(*filters)
        .order_by(DuressDevice.created_at.desc())
        .limit(limit)
        .offset(offset)
    )
    items = result.scalars().all()

    return {"items": items, "total": total, "limit": limit, "offset": offset}


@router.get("/duress-devices/{device_id}", response_model=DuressDeviceRead)
async def get_duress_device(
    device_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> DuressDevice:
    return await _get_owned_device(session, tenant_id=tenant_id, device_id=device_id)


@router.patch("/duress-devices/{device_id}", response_model=DuressDeviceRead)
async def update_duress_device(
    device_id: str,
    body: DuressDeviceUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> DuressDevice:
    device = await _get_owned_device(session, tenant_id=tenant_id, device_id=device_id)
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(device, field, value)
    await session.commit()
    await session.refresh(device)
    return device


@router.post("/duress-devices/{device_id}/rotate-secret", response_model=DuressDeviceRead)
async def rotate_duress_device_secret(
    device_id: str,
    body: DuressDeviceRotateSecret,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> DuressDevice:
    """Re-provisioning flow when a device's firmware is re-flashed with a new
    K_dev -- replaces the stored (encrypted) shared secret."""
    device = await _get_owned_device(session, tenant_id=tenant_id, device_id=device_id)
    return await rotate_device_secret(session, device, body.plaintext_secret)


@router.delete("/duress-devices/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_duress_device(
    device_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
) -> None:
    device = await _get_owned_device(session, tenant_id=tenant_id, device_id=device_id)
    await session.delete(device)
    await session.commit()


# --- device auth handshake (no auth dependency -- this issues the token) -----


@router.post("/devices/auth", response_model=DeviceAuthResponse)
async def device_auth(
    body: DeviceAuthRequest,
    session: AsyncSession = Depends(get_session),
) -> dict:
    """The device proves it holds K_dev by HMAC-signing a nonce it generated
    itself (hmac_hex = HMAC-SHA256(K_dev, nonce).hexdigest()), without ever
    transmitting the secret. On success, mints a short-lived duress_device
    JWT the device then presents as a bearer token on every other route in
    this file. See app.services.duress_device.authenticate_device for the
    MVP/Phase-1 scope note on this handshake's replay-resistance model."""
    try:
        device = await authenticate_device(
            session,
            tenant_id=body.tenant_id,
            device_code=body.device_code,
            nonce=body.nonce,
            hmac_hex_supplied=body.hmac_hex,
        )
    except DeviceAuthError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device credentials"
        )

    token = mint_device_token(device_id=device.id, tenant_id=device.tenant_id)
    return {
        "device_token": token,
        "expires_in_minutes": settings.DURESS_DEVICE_JWT_EXPIRE_MINUTES,
        "device_id": device.id,
    }


@router.post("/devices/{device_id}/heartbeat", response_model=DuressDeviceRead)
async def device_heartbeat(
    device_id: str,
    body: DeviceHeartbeatRequest,
    device_payload: dict = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
) -> DuressDevice:
    """Idle-time health reporting from the device's own bearer token. The
    path device_id must match the token's device_id, and the device row must
    exist under the token's own tenant_id -- looked up as a 404 (not a 403)
    so as not to leak whether a device_id exists under a different tenant."""
    if device_payload.get("device_id") != device_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Duress device not found"
        )
    device = await _get_owned_device(
        session, tenant_id=device_payload.get("tenant_id"), device_id=device_id
    )
    return await record_heartbeat(session, device, body)


# --- device-path duress ingest (bearer: device_token) -------------------------


async def _get_authenticated_device_row(
    session: AsyncSession, device_payload: dict
) -> DuressDevice:
    """Resolves the DuressDevice row behind a device bearer token's claims,
    404ing if it is missing or has since been deactivated -- a deactivated
    device's still-live token must not be able to open new incidents."""
    result = await session.execute(
        select(DuressDevice).where(
            DuressDevice.id == device_payload.get("device_id"),
            DuressDevice.tenant_id == device_payload.get("tenant_id"),
            DuressDevice.active.is_(True),
        )
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Duress device not found")
    return device


@router.post("/duress/device/alarm", response_model=DeviceAlarmResponse)
async def device_alarm(
    body: DeviceAlarmRequest,
    device_payload: dict = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """The device's OWN alarm open, fully independent of the tablet. Either
    attaches to an already-open tablet-side event for the same vehicle,
    returns an event this same device already opened (idempotent retry), or
    opens a brand-new event -- see
    app.services.duress_device.open_or_attach_device_alarm."""
    device = await _get_authenticated_device_row(session, device_payload)
    event = await open_or_attach_device_alarm(
        session, tenant_id=device.tenant_id, device=device, body=body
    )
    return {"event_id": event.id, "source": event.source}


async def _get_own_device_event(
    session: AsyncSession, *, device_payload: dict, event_id: str
) -> DuressEvent:
    """Looks up the DuressEvent by (event_id, tenant_id from the device's own
    token) and verifies event.device_id matches the token's device_id -- a
    compromised device token must not be able to inject GPS/audio into
    another vehicle's incident, even one open in the same tenant."""
    result = await session.execute(
        select(DuressEvent).where(
            DuressEvent.id == event_id,
            DuressEvent.tenant_id == device_payload.get("tenant_id"),
        )
    )
    event = result.scalar_one_or_none()
    if event is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Duress event not found")
    if event.device_id != device_payload.get("device_id"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Not this device's event"
        )
    return event


@router.post("/duress/device/{event_id}/gps", status_code=status.HTTP_202_ACCEPTED)
async def device_gps(
    event_id: str,
    batch: DeviceGpsBatch,
    device_payload: dict = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Devices buffer offline and flush GPS fixes in batches on reconnect
    (unlike the tablet's one-point-per-call POST /v1/duress/{id}/gps).
    Broadcasts every point to WS /v1/duress/{event_id}/live tagged
    source="device" -- not persisted, same live-relay-only contract as the
    tablet path (see app.services.duress.GPSBroadcaster)."""
    event = await _get_own_device_event(session, device_payload=device_payload, event_id=event_id)
    delivered = await ingest_device_gps(event, batch.points)
    return {"delivered_to": delivered}


@router.post("/duress/device/{event_id}/audio", response_model=DuressEventRead)
async def device_audio(
    event_id: str,
    file: UploadFile = File(...),
    device_payload: dict = Depends(get_current_device),
    session: AsyncSession = Depends(get_session),
) -> DuressEvent:
    """Multipart upload of the device's OWN captured audio (its onboard mic,
    over its own SIM) -- stored on device_audio_ref, kept separate from the
    tablet's audio_ref (see that column's docstring in app.models.duress) so
    the Duress Desk can play either/both independently. Same local-disk-
    upload convention as the tablet's POST /v1/duress/{id}/audio (see
    app.services.duress.save_duress_audio)."""
    event = await _get_own_device_event(session, device_payload=device_payload, event_id=event_id)

    content = await file.read()
    try:
        relative_path = await save_duress_audio(
            tenant_id=event.tenant_id,
            event_id=event.id,
            original_filename=file.filename or "audio",
            content=content,
        )
    except DuressAudioError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    event.device_audio_ref = relative_path
    await session.commit()
    await session.refresh(event)
    return event
