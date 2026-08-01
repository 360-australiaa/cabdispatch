"""Jobs domain API — `/v1/jobs`.

The in-house dispatch/job-offer system: `POST /` creates a `Job` and fans out
a 20-second `JobOffer` to every currently-available driver in the tenant (see
`app.services.jobs.available_driver_ids` for the eligibility rule), pushed
live over `WS /v1/jobs/live` to any of those drivers currently connected.
`POST /{id}/offers/{offer_id}/accept` is first-accept-wins — the first driver
to accept gets the job, every sibling pending offer for that job is expired
(see the docstring on `app.services.jobs.accept_offer` for the intentional
no-ranking v1 scope cut). `POST /v1/jobs/availability` is this domain's
driver-facing self-toggle backing the "available" half of that eligibility
rule (see `app.models.jobs.DriverAvailability`).

Every query in this file is filtered by `tenant_id` resolved via
`get_current_tenant_id` — the sole multi-tenancy isolation mechanism in this
system (see `app.core.security` / `app.core.database` docstrings). This
includes the websocket endpoint, which re-implements the same bearer-token
decode + tenant-scoping rule by hand (mirroring `app.api.v1.duress` /
`app.api.v1.live_ops`, since `Depends()`-based HTTP auth doesn't apply to a
`WebSocket` route).

Role policy:
  - `POST /` (create job), `GET /`, `GET /{id}`, `GET /{id}/offers`: any
    authenticated tenant user — job creation in this pass comes from
    whatever booking surface is calling this API (dispatcher console,
    IVR/booking integration, etc.), not restricted to a specific role.
  - `POST /v1/jobs/availability`: any authenticated tenant user — a driver
    toggling their own availability.
  - `POST .../accept` / `POST .../decline`: any authenticated tenant user,
    but scoped to their OWN driver identity — the offer must actually be
    addressed to the calling user's id, else 403 (see
    `app.services.jobs.OfferNotAssignedToDriverError`). This is the
    "driver-facing" restriction the domain brief calls for, enforced by
    identity match rather than by role, since nothing else in this system
    distinguishes "driver acting on their own offer" via role alone.
  - `DELETE /{id}` (cancel): restricted to `owner`/`admin`/`dispatcher`, per
    the task brief ("admin/dispatcher only").
  - `WS /live`: any authenticated tenant user — a driver connects to hear
    `job_offer` events addressed to their own user id.
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
    require_role,
)
from app.schemas.jobs import (
    DriverAvailabilityRead,
    DriverAvailabilityUpdate,
    JobCreate,
    JobOfferRead,
    JobRead,
    Page,
)
from app.services import jobs as jobs_service

router = APIRouter(prefix="/v1/jobs", tags=["jobs"])

# Roles permitted to cancel a job. Any other write (create, accept, decline,
# availability toggle) is open to any authenticated tenant user — see module
# docstring for the full role-policy rationale.
_DISPATCH_ROLES = ("owner", "admin", "dispatcher")


def _jobs_error_to_http(exc: jobs_service.JobsError) -> HTTPException:
    if isinstance(exc, jobs_service.JobNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Job not found")
    if isinstance(exc, jobs_service.OfferNotFoundError):
        return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Job offer not found")
    if isinstance(exc, jobs_service.OfferNotAssignedToDriverError):
        return HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="This offer is not addressed to you"
        )
    if isinstance(exc, jobs_service.OfferNotPendingError):
        return HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Offer is not pending (current status: '{exc}')",
        )
    if isinstance(exc, jobs_service.JobNotCancellableError):
        return HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Cannot cancel a job in status '{exc}'",
        )
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


# ==================================================================================
# Jobs
# ==================================================================================


@router.post("", response_model=JobRead, status_code=status.HTTP_201_CREATED)
async def create_job(
    payload: JobCreate,
    tenant_id: str = Depends(get_current_tenant_id),
    user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """Creates the job and immediately broadcasts a pending offer to every
    currently-available driver (see module docstring). Returns the job alone
    — `GET /{id}/offers` lists what was actually offered and to whom."""
    job, _offers = await jobs_service.create_job_and_broadcast(
        session,
        tenant_id=tenant_id,
        created_by_user_id=user.id,
        **payload.model_dump(),
    )
    return job


@router.get("", response_model=Page[JobRead])
async def list_jobs(
    skip: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
    status_filter: str | None = Query(default=None, alias="status"),
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    items, total = await jobs_service.list_jobs(
        session, tenant_id=tenant_id, status_filter=status_filter, skip=skip, limit=limit
    )
    return Page[JobRead](items=items, total=total, skip=skip, limit=limit)


@router.get("/{job_id}", response_model=JobRead)
async def get_job(
    job_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await jobs_service.get_job_or_404(session, tenant_id=tenant_id, job_id=job_id)
    except jobs_service.JobsError as exc:
        raise _jobs_error_to_http(exc) from exc


@router.get("/{job_id}/offers", response_model=list[JobOfferRead])
async def list_job_offers(
    job_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        await jobs_service.get_job_or_404(session, tenant_id=tenant_id, job_id=job_id)
    except jobs_service.JobsError as exc:
        raise _jobs_error_to_http(exc) from exc

    return await jobs_service.list_offers_for_job(session, tenant_id=tenant_id, job_id=job_id)


@router.delete("/{job_id}", response_model=JobRead)
async def cancel_job(
    job_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    _admin=Depends(require_role(*_DISPATCH_ROLES)),
    session: AsyncSession = Depends(get_session),
):
    """Admin/dispatcher only. Cancels a job and expires any outstanding
    pending offers for it (see `app.services.jobs.cancel_job`). Returns the
    cancelled job rather than 204, so the caller can see the final state
    without a follow-up GET — kept as `DELETE` per the task brief's literal
    endpoint list even though it's a soft-cancel, not a row deletion."""
    try:
        return await jobs_service.cancel_job(session, tenant_id=tenant_id, job_id=job_id)
    except jobs_service.JobsError as exc:
        raise _jobs_error_to_http(exc) from exc


# ==================================================================================
# Offer accept / decline (driver-facing — identity-scoped, see module docstring)
# ==================================================================================


@router.post("/{job_id}/offers/{offer_id}/accept", response_model=JobOfferRead)
async def accept_offer(
    job_id: str,
    offer_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await jobs_service.accept_offer(
            session, tenant_id=tenant_id, job_id=job_id, offer_id=offer_id, driver_id=user.id
        )
    except jobs_service.JobsError as exc:
        raise _jobs_error_to_http(exc) from exc


@router.post("/{job_id}/offers/{offer_id}/decline", response_model=JobOfferRead)
async def decline_offer(
    job_id: str,
    offer_id: str,
    tenant_id: str = Depends(get_current_tenant_id),
    user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await jobs_service.decline_offer(
            session, tenant_id=tenant_id, job_id=job_id, offer_id=offer_id, driver_id=user.id
        )
    except jobs_service.JobsError as exc:
        raise _jobs_error_to_http(exc) from exc


# ==================================================================================
# Driver availability toggle
# ==================================================================================


@router.post("/availability", response_model=DriverAvailabilityRead)
async def set_availability(
    payload: DriverAvailabilityUpdate,
    tenant_id: str = Depends(get_current_tenant_id),
    user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    """A driver's own self-toggle — not in the task brief's literal endpoint
    list, but the minimal write surface needed for the `is_available` half of
    the matching rule (see `app.models.jobs.DriverAvailability`'s module
    docstring) to be reachable/testable at all."""
    return await jobs_service.set_driver_availability(
        session, tenant_id=tenant_id, driver_id=user.id, is_available=payload.is_available
    )


# ==================================================================================
# WS /v1/jobs/live — driver-facing job_offer push feed
# ==================================================================================


async def _authenticate_websocket(websocket: WebSocket) -> dict | None:
    """Same bearer-token decode + tenant-scoping rule as every other domain's
    websocket route in this codebase (see `app.api.v1.duress._authenticate_websocket`
    / `app.api.v1.live_ops._authenticate_ws`), re-implemented here because
    `Depends()`-based HTTP auth doesn't apply on a `WebSocket` connection. The
    token travels as a `?token=` query param (browsers can't set custom
    headers on the websocket handshake) or an `Authorization: Bearer` header
    (non-browser clients).

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
    cross-tenant rule, for the websocket route."""
    token_tenant_id = payload.get("tenant_id")
    role = payload.get("role")

    if role == "owner" and token_tenant_id == PLATFORM_TENANT_ID:
        override = websocket.query_params.get("tenant_id")
        return override or token_tenant_id

    return token_tenant_id


@router.websocket("/live")
async def live(websocket: WebSocket) -> None:
    """Driver-side live feed: pushes every `job_offer` event addressed to the
    connecting user's own id (i.e. `sub` from their token — a driver only
    ever receives offers made to them, see
    `app.services.jobs.create_job_and_broadcast`). Backed by the in-process
    pub/sub in `app.services.jobs.JobOfferBroadcaster` (see that class's
    docstring for the Redis swap-in path)."""
    payload = await _authenticate_websocket(websocket)
    if payload is None:
        return  # rejected + closed inside _authenticate_websocket

    tenant_id = _resolve_ws_tenant_id(websocket, payload)
    if not tenant_id:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Token has no tenant scope")
        return

    driver_id = payload.get("sub")
    if not driver_id:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Token has no subject")
        return

    await websocket.accept()
    queue = await jobs_service.job_offer_broadcaster.subscribe(driver_id)
    try:
        while True:
            message = await queue.get()
            await websocket.send_json(message)
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        await jobs_service.job_offer_broadcaster.unsubscribe(driver_id, queue)
