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
  - `WS /live`: any authenticated tenant user, but WHAT they hear depends on
    their role (2026-09-19). A driver hears only events addressed to their own
    user id, exactly as before. An `owner`/`admin`/`dispatcher` — who has no
    offers of their own and therefore used to sit on a silent socket forever —
    is subscribed to the tenant-wide channel instead and hears every job event
    in their tenant. See `live`'s docstring below and the module comment on
    `app.services.jobs.JobOfferBroadcaster`.
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
from pydantic import BaseModel, ConfigDict
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import (
    WebSocketAuth,
    WebSocketAuthError,
    authenticate_websocket_token,
    get_current_tenant_id,
    get_current_user,
    require_role,
    revocation_aware_pump,
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




# --- On-behalf actions (admin panel plan, 2026-09-15) --------------------------------------
# The dashboard's dispatch/zones pages act FOR a driver (accept an offer for a driver on the
# phone, plot a cab into a rank from the desk). The routes stay identity-scoped for a driver
# token -- a driver can never name another driver -- and only owner/admin/dispatcher may pass a
# `driver_id` that is not their own.

_ON_BEHALF_ROLES = ("owner", "admin", "dispatcher")


class OnBehalfBody(BaseModel):
    """Optional body: `driver_id` names the driver the action is performed for. Extra fields
    (the dashboard also sends shift/vehicle ids for its own bookkeeping) are ignored."""

    model_config = ConfigDict(extra="ignore")

    driver_id: str | None = None


def _acting_driver_id(user, payload: OnBehalfBody | None) -> str:
    target = payload.driver_id if payload is not None else None
    if not target or target == user.id:
        return user.id
    if user.role not in _ON_BEHALF_ROLES:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only an owner, admin or dispatcher may act on behalf of another driver",
        )
    return target


@router.post("/{job_id}/offers/{offer_id}/accept", response_model=JobOfferRead)
async def accept_offer(
    job_id: str,
    offer_id: str,
    payload: OnBehalfBody | None = None,
    tenant_id: str = Depends(get_current_tenant_id),
    user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await jobs_service.accept_offer(
            session,
            tenant_id=tenant_id,
            job_id=job_id,
            offer_id=offer_id,
            driver_id=_acting_driver_id(user, payload),
        )
    except jobs_service.JobsError as exc:
        raise _jobs_error_to_http(exc) from exc


@router.post("/{job_id}/offers/{offer_id}/decline", response_model=JobOfferRead)
async def decline_offer(
    job_id: str,
    offer_id: str,
    payload: OnBehalfBody | None = None,
    tenant_id: str = Depends(get_current_tenant_id),
    user=Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
):
    try:
        return await jobs_service.decline_offer(
            session,
            tenant_id=tenant_id,
            job_id=job_id,
            offer_id=offer_id,
            driver_id=_acting_driver_id(user, payload),
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


async def _authenticate_websocket(websocket: WebSocket) -> WebSocketAuth | None:
    """Applies `app.core.security.authenticate_websocket_token` — the one
    shared websocket auth rule (token present, type == access, jti not revoked,
    tenant resolved) — because `Depends()`-based HTTP auth doesn't apply on a
    `WebSocket` connection. This route previously checked neither the token
    type nor revocation, so a refresh token, a pre-TOTP `mfa_pending` token, or
    a token revoked by logout all opened a driver's job-offer feed.

    Returns the authenticated result, or `None` if the connection was rejected
    (a close frame has already been sent in that case — the caller must not
    call `.accept()`)."""
    try:
        return await authenticate_websocket_token(websocket)
    except WebSocketAuthError as exc:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason=exc.reason)
        return None


@router.websocket("/live")
async def live(websocket: WebSocket) -> None:
    """Live job feed, with two shapes behind one URL (2026-09-19).

    **Driver branch** (any role outside `_DISPATCH_ROLES`): subscribes to the
    connecting user's own id (`sub` from their token) and receives only
    events on offers addressed to them — the same channel, keyed the same
    way, as before the dispatch branch existed. A deployed tablet must never
    start hearing another driver's job traffic: the Android app applies no
    client-side filter and updates on the DRIVERS' schedule, so a
    server-side widening here would be a tenant-data leak on devices nobody
    can patch. `tests/test_jobs.py` pins it.

    The channel is only half of that promise, and the review of 2026-09-19
    caught the other half missing. The VOLUME on it matters just as much: a
    deployed tablet cannot decode this envelope, so it answers every single
    frame with a full `refresh()` — one jobs list plus one offers call per
    listed job. `app.services.jobs._publish_job_event` is therefore the place
    that decides who hears what, and it addresses a driver only for a new
    offer or for the server killing an offer of theirs that they did not act
    on. The echo of a driver's own accept/decline goes to the desk only, so
    no driver's frame count went up for a lifecycle they drive themselves.

    **Dispatch branch** (`owner`/`admin`/`dispatcher`): subscribes to the
    tenant channel and receives every job event in the tenant. These roles
    have no offers of their own, so under the old driver-only keying their
    socket connected and then stayed PERMANENTLY SILENT — the state
    `dashboard/src/pages/dispatch/useJobsLive.ts` describes in its own
    comment and keeps a safety poll for.

    The role gate is read straight off the token, the same by-hand check
    `app.api.v1.live_ops.live` and `app.api.v1.duress` make (a `WebSocket`
    route cannot take `Depends(require_role(...))`). Unlike those two this is
    a BRANCH, not a rejection: both kinds of user are still welcome here.

    Backed by the in-process pub/sub in
    `app.services.jobs.JobOfferBroadcaster` (see that class's docstring for
    the Redis swap-in path)."""
    auth = await _authenticate_websocket(websocket)
    if auth is None:
        # rejected + closed inside _authenticate_websocket — that now includes
        # the "token has no tenant scope" case, which the shared rule refuses.
        return

    is_dispatch = auth.payload.get("role") in _DISPATCH_ROLES

    subject = auth.payload.get("sub")
    if not subject:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Token has no subject")
        return

    await websocket.accept()

    broadcaster = jobs_service.job_offer_broadcaster
    if is_dispatch:
        queue = await broadcaster.subscribe_tenant(auth.tenant_id)
    else:
        queue = await broadcaster.subscribe(subject)
    try:
        # D10: rechecks revocation every poll tick for the life of the
        # connection, not just at the handshake — a driver logged out (or
        # "signed out everywhere") mid-shift no longer keeps a live job-offer
        # feed open indefinitely.
        await revocation_aware_pump(websocket, queue, auth.payload.get("jti"))
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        if is_dispatch:
            await broadcaster.unsubscribe_tenant(auth.tenant_id, queue)
        else:
            await broadcaster.unsubscribe(subject, queue)
