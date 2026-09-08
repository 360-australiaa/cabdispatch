"""Auth router — `/v1/auth`.

Not one of the 12 domain slices; added at integration time because none of
them owned "how does a user actually get a bearer token", and every other
domain's tests/docs assume one already exists (`tests/conftest.py`'s
`auth_headers` helper mints tokens directly via `app.core.security` rather
than going through HTTP, precisely so each domain's own test suite didn't
need this endpoint to exist yet).

Password is stored on `User.pin_hash` (see app/models/user.py / the
conftest.py `auth_headers` helper for the existing precedent of hashing a
password into that column with `app.core.security.hash_password`).
"""
from __future__ import annotations

import time

from fastapi import APIRouter, Depends, HTTPException, Request, status
from jose import JWTError
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.ratelimit import (
    DRIVER_LOGIN_PER_CODE,
    DRIVER_LOGIN_PER_IP,
    LOGIN_PER_IP,
    MFA_LOGIN_PER_IP,
    enforce,
    limiter,
)
from app.core.security import (
    TOKEN_TYPE_MFA,
    TOKEN_TYPE_REFRESH,
    create_access_token,
    create_mfa_pending_token,
    create_refresh_token,
    decode_token,
    generate_mfa_secret,
    get_current_user,
    get_token_payload,
    mfa_provisioning_uri,
    revocation_store,
    verify_password,
    verify_totp_code,
)
from app.models.tenant import TENANT_STATUS_SUSPENDED, Tenant
from app.models.user import User
from app.schemas.auth import (
    DriverLoginRequest,
    LoginRequest,
    MfaDisableRequest,
    MfaLoginRequest,
    MfaRequiredResponse,
    MfaSetupResponse,
    MfaStatusResponse,
    MfaVerifyRequest,
    RefreshRequest,
    RefreshResponse,
    TokenResponse,
    UserRead,
)
from app.services.compliance_expiry import is_expired

router = APIRouter(prefix="/v1/auth", tags=["auth"])

_INVALID_CREDENTIALS = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid email or password"
)
_INVALID_DRIVER_CREDENTIALS = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid driver code or PIN"
)
_INVALID_MFA_CODE = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid MFA code"
)
_LICENSE_EXPIRED = HTTPException(
    status_code=status.HTTP_403_FORBIDDEN,
    detail="Driver license has expired — contact your operator to renew before logging in",
)


_TENANT_SUSPENDED = HTTPException(
    status_code=status.HTTP_403_FORBIDDEN,
    detail="This operator's account is suspended — contact support",
)


async def _assert_tenant_not_suspended(session: AsyncSession, user: User) -> None:
    """Enforces `Tenant.status == "suspended"` at the two places a session can
    begin or be extended (login and refresh).

    Until this existed, suspension was purely cosmetic: the platform owner
    could flip a tenant to `suspended` via PATCH /v1/platform/tenants/{id} and
    every one of that tenant's users kept logging in and working normally,
    because `_login_result` only ever checked `user.status`. Suspending an
    operator is a commercial/compliance action (non-payment, licence issue) —
    it has to actually stop them.

    A user with no `tenant_id` at all (there is no such row today, but the
    column is nullable) is not blocked here: there is no tenant to be
    suspended, and `get_current_tenant_id` already refuses to give such a token
    any tenant scope.
    """
    if not user.tenant_id:
        return

    result = await session.execute(select(Tenant).where(Tenant.id == user.tenant_id))
    tenant = result.scalar_one_or_none()
    if tenant is not None and tenant.status == TENANT_STATUS_SUSPENDED:
        raise _TENANT_SUSPENDED


async def _revoke_jti(payload: dict) -> None:
    """Adds a token's `jti` to the revocation store for exactly as long as the
    token would otherwise have remained valid — past its own `exp` there is
    nothing left to revoke, so a longer TTL would only grow the store.

    Mirrors the single-use burn `mfa_login` already does on the mfa_token."""
    jti = payload.get("jti")
    if not jti:
        return
    exp = payload.get("exp")
    ttl = max(int(exp - time.time()), 1) if exp else 1
    await revocation_store.revoke(jti, ttl)


def _issue_tokens(user: User) -> TokenResponse:
    return TokenResponse(
        access_token=create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        refresh_token=create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        user=UserRead.model_validate(user),
    )


def _login_result(user: User) -> TokenResponse | MfaRequiredResponse:
    """Shared second half of both POST /login and POST /driver-login, once
    each has independently verified its own credentials against
    `user.pin_hash`: active-status gate + MFA opt-in branch (blueprint 12.2).

    Accounts that never enabled MFA get exactly the pre-existing behavior
    below, unchanged — this branch only fires for mfa_enabled=True accounts,
    and is the SAME two-step contract for both login paths (driver-login
    doesn't fork it): POST /mfa/login is still the only way to exchange the
    resulting mfa_token for real tokens, regardless of which endpoint issued
    it.
    """
    if user.status != "active":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Account is not active")

    if user.mfa_enabled:
        mfa_token = create_mfa_pending_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)
        return MfaRequiredResponse(mfa_token=mfa_token)

    return _issue_tokens(user)


@router.post("/login", response_model=TokenResponse | MfaRequiredResponse)
@limiter.limit(LOGIN_PER_IP)
async def login(
    request: Request, body: LoginRequest, session: AsyncSession = Depends(get_session)
) -> TokenResponse | MfaRequiredResponse:
    """Rate limited to LOGIN_PER_IP. `request` is present solely because
    slowapi's decorator reads the limiter off it — the handler ignores it."""
    result = await session.execute(select(User).where(User.email == body.email))
    user = result.scalar_one_or_none()

    if user is None or not user.pin_hash or not verify_password(body.password, user.pin_hash):
        raise _INVALID_CREDENTIALS

    # After credentials verify, so a wrong password still 401s rather than
    # leaking which operators are suspended.
    await _assert_tenant_not_suspended(session, user)

    return _login_result(user)


@router.post("/driver-login", response_model=TokenResponse | MfaRequiredResponse)
@limiter.limit(DRIVER_LOGIN_PER_IP)
async def driver_login(
    request: Request, body: DriverLoginRequest, session: AsyncSession = Depends(get_session)
) -> TokenResponse | MfaRequiredResponse:
    """The real driver-facing counterpart to POST /login: Driver ID + PIN
    instead of email + password.

    ⚠ TENANT SCOPING (breaking change). This endpoint used to look the driver up
    with `select(User).where(User.driver_code == body.driver_code)` — no tenant
    filter at all. The comment that justified it claimed `driver_code` is
    "globally unique"; `app/services/user.py::assert_driver_code_available` in
    fact only guarantees uniqueness where it is called, and even perfect global
    uniqueness would not make a GLOBAL lookup safe: it meant every tenant on the
    platform shared one 6-digit-PIN credential space, so an attacker guessing
    PINs was guessing against the union of every operator's drivers at once
    (backend audit §5 ADDENDUM). `tenant_slug` is now required and the lookup is
    filtered by `User.tenant_id`.

    An unknown `tenant_slug` returns the SAME 401 as a wrong PIN, deliberately:
    a distinct 404 would turn this endpoint into an oracle enumerating which
    operators exist on the platform.

    ⚠ RATE LIMITING. Two limits apply, and BOTH must pass:
      * DRIVER_LOGIN_PER_IP, by the decorator above — stops one host spraying
        PINs at many driver codes.
      * DRIVER_LOGIN_PER_CODE, enforced imperatively below — stops a
        distributed/rotating-IP attack grinding a SINGLE driver's 6-digit PIN,
        which the per-IP limit alone does nothing about. It is keyed on
        tenant+code (not code alone) so one tenant cannot lock out an
        identically-numbered driver in another tenant.
    The per-code counter is consumed before credentials are checked, so a
    successful login costs a unit too — that is intentional: the limit is on
    attempts against a credential, not on failures, and 20/hour is far above any
    real driver's shift-start behaviour.

    Replaces the placeholder driverId->email / pin->password mapping
    documented on the Android side in
    domain/DriverAuthRepository.kt's NOTE(integration agent) comment.

    Also enforces the blueprint's Driver Authentication screen spec (5.2.1):
    a driver whose `driver_license_expiry` (app/models/user.py) is a real
    date strictly in the past is blocked with 403, checked via
    `app.services.compliance_expiry.is_expired` AFTER credentials verify (so
    a wrong-PIN attempt still 401s rather than leaking expiry status) but
    BEFORE MFA/token issuance. Fails OPEN on a null expiry (never set) or a
    still-current one — only an actually-expired date blocks login, matching
    this codebase's general fail-open-on-missing-compliance-data convention.
    `driver_authority_expiry` deliberately does NOT block login here — the
    blueprint reference for this login-block is license-specific; an expired
    authority only ever raises a FatigueAlert (see
    app.services.compliance_expiry.check_driver_authority_expiry, wired into
    PATCH /v1/trips/{id}/tick), it doesn't stop the driver logging in.
    """
    enforce(
        DRIVER_LOGIN_PER_CODE,
        "driver-login-code",
        body.tenant_slug,
        body.driver_code,
        detail="Too many login attempts for this driver code",
    )

    tenant_result = await session.execute(select(Tenant).where(Tenant.slug == body.tenant_slug))
    tenant = tenant_result.scalar_one_or_none()
    if tenant is None:
        raise _INVALID_DRIVER_CREDENTIALS

    result = await session.execute(
        select(User).where(
            User.driver_code == body.driver_code,
            User.tenant_id == tenant.id,
        )
    )
    user = result.scalar_one_or_none()

    if (
        user is None
        or user.role != "driver"
        or not user.pin_hash
        or not verify_password(body.pin, user.pin_hash)
    ):
        raise _INVALID_DRIVER_CREDENTIALS

    # The tenant row is already in hand here, so this is the same suspension
    # gate `login` applies via `_assert_tenant_not_suspended`, without a second
    # query. Checked after credentials verify, for the same reason.
    if tenant.status == TENANT_STATUS_SUSPENDED:
        raise _TENANT_SUSPENDED

    if is_expired(user.driver_license_expiry):
        raise _LICENSE_EXPIRED

    return _login_result(user)


@router.post("/mfa/login", response_model=TokenResponse)
@limiter.limit(MFA_LOGIN_PER_IP)
async def mfa_login(
    request: Request, body: MfaLoginRequest, session: AsyncSession = Depends(get_session)
) -> TokenResponse:
    """Second step of login for mfa_enabled accounts: exchanges the
    short-lived `mfa_token` from POST /v1/auth/login plus a 6-digit TOTP code
    for real access/refresh tokens."""
    try:
        payload = decode_token(body.mfa_token)
    except JWTError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or expired MFA token"
        ) from exc

    if payload.get("type") != TOKEN_TYPE_MFA:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not an MFA token")

    jti = payload.get("jti")
    if jti and await revocation_store.is_revoked(jti):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="MFA token already used")

    user_id = payload.get("sub")
    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None or user.status != "active" or not user.mfa_enabled or not user.mfa_secret:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or expired MFA token")

    if not verify_totp_code(secret=user.mfa_secret, code=body.code):
        raise _INVALID_MFA_CODE

    # Single-use: burn the mfa_token's jti so it can't be replayed for a
    # second exchange within its 5-minute window.
    if jti:
        exp = payload.get("exp")
        ttl = max(int(exp - time.time()), 1) if exp else 1
        await revocation_store.revoke(jti, ttl)

    return _issue_tokens(user)


@router.post("/refresh", response_model=RefreshResponse)
async def refresh(body: RefreshRequest, session: AsyncSession = Depends(get_session)) -> RefreshResponse:
    """Rotating refresh: the presented refresh token is REVOKED as part of
    issuing the new pair, so each refresh token is single-use.

    Without rotation (the previous behaviour) a leaked 14-day refresh token was
    a skeleton key — legitimate refreshes by the real user did nothing to
    invalidate it, and there was no other way to kill it. With rotation, the
    attacker and the real client race for each token, and whichever one loses
    is left holding a revoked token and gets a 401 — which is at least
    *detectable*, and self-heals the moment the real client refreshes once.
    """
    try:
        payload = decode_token(body.refresh_token)
    except JWTError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid refresh token"
        ) from exc

    if payload.get("type") != TOKEN_TYPE_REFRESH:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not a refresh token")

    jti = payload.get("jti")
    if jti and await revocation_store.is_revoked(jti):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Refresh token revoked")

    user_id = payload.get("sub")
    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None or user.status != "active":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User no longer active")

    await _assert_tenant_not_suspended(session, user)

    # Rotation: burn the presented jti BEFORE handing out the replacement, so
    # there is no window in which both the old and the new refresh token work.
    await _revoke_jti(payload)

    return RefreshResponse(
        access_token=create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        refresh_token=create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
    )


class LogoutRequest(BaseModel):
    """Optional body for POST /v1/auth/logout.

    Lives here rather than in `app/schemas/auth.py` only because this
    workstream owns this router and not that module; it should move there on
    the next pass through the schemas file.

    `refresh_token` is optional: a client that only holds an access token can
    still log out (the access jti is taken from the Authorization header), but
    a client that passes its refresh token gets the whole session killed rather
    than just the 30-minute half of it.
    """

    refresh_token: str | None = None


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    body: LogoutRequest | None = None,
    payload: dict = Depends(get_token_payload),
) -> None:
    """Revokes the caller's access token server-side, and the refresh token too
    if one is supplied in the body.

    This endpoint used to be a documented no-op: it depended on
    `get_current_user`, which never handed the jti back, so a stolen access
    token stayed valid for its full 30 minutes and a stolen refresh token for
    14 days after the user pressed "Log out". Both clients call this and
    reasonably assume it works. It now depends on `get_token_payload` instead —
    the same validated payload, with the `jti` still attached — and puts it in
    the revocation store the rest of the auth path already consults
    (`_payload_from_credentials` for HTTP, `authenticate_websocket_token` for
    websockets).

    A bad/expired/foreign refresh token in the body is ignored rather than
    fatal: logout must never fail in a way that leaves the client believing it
    is still logged in, and there is nothing an attacker gains by submitting a
    token they would like revoked.
    """
    await _revoke_jti(payload)

    if body is not None and body.refresh_token:
        try:
            refresh_payload = decode_token(body.refresh_token)
        except JWTError:
            return
        if refresh_payload.get("type") != TOKEN_TYPE_REFRESH:
            return
        # Only the same subject's refresh token — a valid token belonging to
        # somebody else is not this caller's to revoke.
        if refresh_payload.get("sub") != payload.get("sub"):
            return
        await _revoke_jti(refresh_payload)


@router.get("/me", response_model=UserRead)
async def me(user: User = Depends(get_current_user)) -> User:
    return user


# --- MFA (blueprint 12.2) ----------------------------------------------------
# Opt-in TOTP for admin/staff users. All three endpoints below act on the
# caller's OWN account only (no tenant/role gate beyond being authenticated —
# there's nothing cross-tenant here, same reasoning as /me above).


@router.post("/mfa/setup", response_model=MfaSetupResponse)
async def mfa_setup(
    user: User = Depends(get_current_user), session: AsyncSession = Depends(get_session)
) -> MfaSetupResponse:
    """Generates a new TOTP secret and stores it as *pending* on the user
    (mfa_enabled stays False until confirmed via POST /mfa/verify). Calling
    this again before verifying overwrites the previous pending secret — the
    old one, and any code scanned from it, simply stops working."""
    secret = generate_mfa_secret()
    user.mfa_secret = secret
    session.add(user)
    await session.commit()

    return MfaSetupResponse(
        secret=secret,
        otpauth_uri=mfa_provisioning_uri(secret=secret, email=user.email),
    )


@router.post("/mfa/verify", response_model=MfaStatusResponse)
async def mfa_verify(
    body: MfaVerifyRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> MfaStatusResponse:
    """Confirms a 6-digit code against the pending secret from /mfa/setup and
    flips mfa_enabled=True on success."""
    if not user.mfa_secret:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No MFA setup in progress — call POST /v1/auth/mfa/setup first",
        )

    if not verify_totp_code(secret=user.mfa_secret, code=body.code):
        raise _INVALID_MFA_CODE

    user.mfa_enabled = True
    session.add(user)
    await session.commit()

    return MfaStatusResponse(mfa_enabled=True)


@router.post("/mfa/disable", response_model=MfaStatusResponse)
async def mfa_disable(
    body: MfaDisableRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> MfaStatusResponse:
    """Requires re-entering the current password (not a TOTP code) so a
    hijacked-but-still-logged-in session can't silently strip MFA off an
    account."""
    if not user.pin_hash or not verify_password(body.password, user.pin_hash):
        raise _INVALID_CREDENTIALS

    user.mfa_enabled = False
    user.mfa_secret = None
    session.add(user)
    await session.commit()

    return MfaStatusResponse(mfa_enabled=False)


__all__ = ["router"]
