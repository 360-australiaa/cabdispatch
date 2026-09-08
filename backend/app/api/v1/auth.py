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
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Request, status
from jose import JWTError
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import get_session
from app.core.ratelimit import (
    DRIVER_LOGIN_PER_CODE,
    DRIVER_LOGIN_PER_IP,
    LOGIN_PER_IP,
    MFA_LOGIN_PER_IP,
    PASSWORD_RESET_PER_EMAIL,
    PASSWORD_RESET_PER_IP,
    RECOVERY_CODE_GENERATE_PER_USER,
    enforce,
    limiter,
)
from app.core.security import (
    TOKEN_TYPE_MFA,
    TOKEN_TYPE_PASSWORD_RESET,
    TOKEN_TYPE_REFRESH,
    create_access_token,
    create_mfa_pending_token,
    create_password_reset_token,
    create_refresh_token,
    decode_token,
    generate_mfa_secret,
    generate_recovery_codes,
    get_current_user,
    get_token_payload,
    hash_password,
    hash_recovery_code,
    mfa_provisioning_uri,
    revocation_store,
    verify_password,
    verify_recovery_code,
    verify_totp_code,
)
from app.models.recovery_code import RecoveryCode
from app.models.tenant import TENANT_STATUS_SUSPENDED, Tenant
from app.models.user import User
from app.models.user_session import UserSession
from app.schemas.auth import (
    DriverLoginRequest,
    LoginRequest,
    MfaDisableRequest,
    MfaLoginRequest,
    MfaRequiredResponse,
    MfaSetupResponse,
    MfaStatusResponse,
    MfaVerifyRequest,
    PasswordChangeRequest,
    PasswordResetConfirmRequest,
    PasswordResetRequest,
    RecoveryCodesResponse,
    RecoveryCodeStatus,
    RefreshRequest,
    RefreshResponse,
    SessionListResponse,
    SessionRead,
    TokenResponse,
    UserRead,
)
from app.services.compliance_expiry import is_expired
from app.services.password_reset import send_password_reset_email

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


async def _revoke_session_jtis(row: UserSession) -> None:
    """Revokes a `UserSession` row's current access AND refresh jti directly
    against `revocation_store`, with the TTL set to that token TYPE's own
    max lifetime — NOT via `_revoke_jti(payload)`, which needs a real `exp`
    claim to compute a TTL and would otherwise fall back to its 1-second
    default (fine for a payload just decoded off a live token, wrong here:
    these jtis come only from the `UserSession` row, with no `exp` in hand,
    and a 1-second revocation would let an attacker's already-open
    websocket, or a not-yet-expired access token, become valid again one
    second after the user clicked "sign out everywhere").
    """
    await revocation_store.revoke(row.current_access_jti, settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60)
    await revocation_store.revoke(row.current_refresh_jti, settings.REFRESH_TOKEN_EXPIRE_DAYS * 86400)


async def _consume_recovery_code(session: AsyncSession, *, user_id: str, code: str) -> bool:
    """Checks `code` against every un-consumed recovery-code hash for
    `user_id` and, on a match, marks that ONE row consumed (single-use —
    `tests/test_auth_security_settings.py::test_recovery_code_is_single_use`
    proves a second attempt with the same code fails).

    There is no way to look a code up by value (only hashes are stored), so
    this necessarily checks against every remaining row rather than an
    indexed lookup — bounded by RECOVERY_CODE_COUNT (10) per user, so the
    cost is trivial.
    """
    result = await session.execute(
        select(RecoveryCode).where(
            RecoveryCode.user_id == user_id,
            RecoveryCode.consumed_at.is_(None),
        )
    )
    for row in result.scalars().all():
        if verify_recovery_code(code, row.code_hash):
            row.consumed_at = datetime.now(UTC)
            session.add(row)
            await session.commit()
            return True
    return False


async def _issue_tokens(user: User, *, session: AsyncSession, request: Request | None) -> TokenResponse:
    """Issues a fresh access/refresh pair AND records a `UserSession` row for
    it (D10: session list + "sign out everywhere") — one row per login,
    updated in place by `refresh` below rather than growing without bound.

    `request` is optional only so this stays callable from contexts with no
    HTTP request in hand (there are none today, but the parameter is kept
    honest about what it actually needs rather than requiring a fake one);
    when absent, `user_agent`/`ip_address` are simply left null.
    """
    access_token = create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)
    refresh_token = create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)

    user_agent = request.headers.get("user-agent") if request is not None else None
    ip_address = request.client.host if (request is not None and request.client) else None

    now = datetime.now(UTC)
    row = UserSession(
        user_id=user.id,
        tenant_id=user.tenant_id,
        current_access_jti=decode_token(access_token)["jti"],
        current_refresh_jti=decode_token(refresh_token)["jti"],
        user_agent=user_agent,
        ip_address=ip_address,
        last_seen_at=now,
    )
    session.add(row)
    await session.commit()

    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token,
        user=UserRead.model_validate(user),
    )


async def _login_result(
    user: User, *, session: AsyncSession, request: Request | None
) -> TokenResponse | MfaRequiredResponse:
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

    return await _issue_tokens(user, session=session, request=request)


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

    return await _login_result(user, session=session, request=request)


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

    return await _login_result(user, session=session, request=request)


@router.post("/mfa/login", response_model=TokenResponse)
@limiter.limit(MFA_LOGIN_PER_IP)
async def mfa_login(
    request: Request, body: MfaLoginRequest, session: AsyncSession = Depends(get_session)
) -> TokenResponse:
    """Second step of login for mfa_enabled accounts: exchanges the
    short-lived `mfa_token` from POST /v1/auth/login plus EITHER a 6-digit
    TOTP code OR one of the account's ten single-use recovery codes
    (D10 — the "I lost my authenticator" path; see
    app/models/recovery_code.py) for real access/refresh tokens.

    Exactly one of `code`/`recovery_code` must be supplied; supplying both,
    or neither, is a 400 rather than silently preferring one — a client that
    thinks it sent a TOTP code should never be quietly authenticated by a
    recovery code it didn't mean to send (or vice versa).
    """
    if bool(body.code) == bool(body.recovery_code):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Supply exactly one of code or recovery_code",
        )

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

    if body.code:
        if not verify_totp_code(secret=user.mfa_secret, code=body.code):
            raise _INVALID_MFA_CODE
    else:
        if not await _consume_recovery_code(session, user_id=user.id, code=body.recovery_code):
            raise _INVALID_MFA_CODE

    # Single-use: burn the mfa_token's jti so it can't be replayed for a
    # second exchange within its 5-minute window.
    if jti:
        exp = payload.get("exp")
        ttl = max(int(exp - time.time()), 1) if exp else 1
        await revocation_store.revoke(jti, ttl)

    return await _issue_tokens(user, session=session, request=request)


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

    new_access = create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)
    new_refresh = create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)

    # D10: keep this login's UserSession row pointed at the new jti pair
    # rather than the ones just burned, so the session survives its own
    # refresh in the dashboard's session list — a login that appeared once,
    # then vanished from the list on its first token refresh, would look like
    # a bug even though nothing was actually revoked. Matched by the OLD
    # refresh jti; if no row matches (e.g. a token minted directly by
    # `create_refresh_token` in a test, bypassing login), rotation still
    # succeeds — session tracking degrades gracefully, it never blocks auth.
    session_result = await session.execute(
        select(UserSession).where(
            UserSession.current_refresh_jti == jti,
            UserSession.revoked_at.is_(None),
        )
    )
    session_row = session_result.scalar_one_or_none()
    if session_row is not None:
        session_row.current_access_jti = decode_token(new_access)["jti"]
        session_row.current_refresh_jti = decode_token(new_refresh)["jti"]
        session_row.last_seen_at = datetime.now(UTC)
        session.add(session_row)
        await session.commit()

    return RefreshResponse(access_token=new_access, refresh_token=new_refresh)


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


async def _mark_session_revoked_by_jti(session: AsyncSession, jti: str | None) -> None:
    """D10: marks the UserSession row owning `jti` (as either its current
    access or refresh jti) revoked, so it drops off the session list the
    moment its owner logs out — mirrors `_revoke_jti`'s jti-store write, kept
    separate because not every jti-revoking call site (e.g. MFA-token burns)
    has a session row to update."""
    if not jti:
        return
    result = await session.execute(
        select(UserSession).where(
            (UserSession.current_access_jti == jti) | (UserSession.current_refresh_jti == jti),
            UserSession.revoked_at.is_(None),
        )
    )
    row = result.scalar_one_or_none()
    if row is not None:
        row.revoked_at = datetime.now(UTC)
        session.add(row)
        await session.commit()


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    body: LogoutRequest | None = None,
    payload: dict = Depends(get_token_payload),
    session: AsyncSession = Depends(get_session),
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
    await _mark_session_revoked_by_jti(session, payload.get("jti"))

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
        await _mark_session_revoked_by_jti(session, refresh_payload.get("jti"))


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


# --- D10: password change ----------------------------------------------------


@router.post("/password/change", status_code=status.HTTP_204_NO_CONTENT)
async def change_password(
    body: PasswordChangeRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> None:
    """Requires the CURRENT password (not just an active session) before
    accepting a new one — same rationale as `mfa_disable` above: a
    hijacked-but-still-logged-in access token alone must not be enough to
    lock the real owner out by changing their password.

    Uses the SAME `hash_password` (passlib bcrypt `CryptContext`) this
    account's password was already hashed with — see
    `app.core.security.hash_password`/`verify_password` — no second hashing
    scheme is introduced here.
    """
    if not user.pin_hash or not verify_password(body.current_password, user.pin_hash):
        raise _INVALID_CREDENTIALS

    user.pin_hash = hash_password(body.new_password)
    session.add(user)
    await session.commit()


# --- D10: password reset by email --------------------------------------------
# Deliberately does NOT reveal whether `email` belongs to an account: both
# branches below return the exact same 202 with the exact same body, and the
# "user not found" branch does the same amount of hashing-shaped work
# (`hash_password` is not called on the no-such-user path since there is
# nothing to hash into, but no DB write happens on the success path either
# until the token is actually confirmed) — see
# tests/test_auth_security_settings.py::test_reset_request_does_not_leak_account_existence
# for the exact assertion (same status, same JSON body, both branches).


@router.post("/password/reset/request", status_code=status.HTTP_202_ACCEPTED)
@limiter.limit(PASSWORD_RESET_PER_IP)
async def request_password_reset(
    request: Request, body: PasswordResetRequest, session: AsyncSession = Depends(get_session)
) -> dict:
    """Rate limited two ways, same split as driver-login: PASSWORD_RESET_PER_IP
    (decorator) stops one host spraying emails; PASSWORD_RESET_PER_EMAIL
    (imperative, keyed on the submitted email — checked regardless of whether
    that email exists, so the rate-limit check itself leaks nothing) stops a
    slow grind against one target's inbox."""
    enforce(
        PASSWORD_RESET_PER_EMAIL,
        "password-reset-email",
        body.email.strip().lower(),
        detail="Too many reset requests for this email",
    )

    result = await session.execute(select(User).where(User.email == body.email))
    user = result.scalar_one_or_none()

    if user is not None and user.status == "active":
        reset_token = create_password_reset_token(user_id=user.id)
        reset_url = f"{settings.DASHBOARD_BASE_URL}/reset-password?token={reset_token}"
        send_password_reset_email(to_email=user.email, reset_url=reset_url)

    # SAME response whether or not `user` was found/active — an attacker
    # cannot distinguish "sent" from "no such account" from the outside.
    return {"detail": "If that email is registered, a reset link has been sent."}


@router.post("/password/reset/confirm", status_code=status.HTTP_204_NO_CONTENT)
async def confirm_password_reset(
    body: PasswordResetConfirmRequest, session: AsyncSession = Depends(get_session)
) -> None:
    """Exchanges a `reset_token` from `POST /password/reset/request` for a new
    password. The token is single-use (burned via the same `revocation_store`
    every other one-shot token in this file uses) and only ever accepted here
    — it carries no `tenant_id`/`role` and so cannot authenticate anything
    else.

    On success, EVERY existing session for the account is revoked (D10's
    "sign out everywhere" machinery, reused rather than duplicated) — a
    password reset is exactly the moment an attacker holding a stolen session
    should be kicked out.
    """
    try:
        payload = decode_token(body.reset_token)
    except JWTError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid or expired reset link"
        ) from exc

    if payload.get("type") != TOKEN_TYPE_PASSWORD_RESET:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid reset link")

    jti = payload.get("jti")
    if jti and await revocation_store.is_revoked(jti):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Reset link already used")

    user_id = payload.get("sub")
    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None or user.status != "active":
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid or expired reset link")

    user.pin_hash = hash_password(body.new_password)
    session.add(user)

    await _revoke_jti(payload)
    await _revoke_all_sessions(session, user_id=user.id)

    await session.commit()


# --- D10: MFA recovery codes --------------------------------------------------


@router.post("/mfa/recovery-codes/generate", response_model=RecoveryCodesResponse)
async def generate_recovery_codes_endpoint(
    request: Request,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> RecoveryCodesResponse:
    """Generates ten fresh recovery codes (stdlib `secrets` CSPRNG, see
    `app.core.security.generate_recovery_codes`), returns them ONCE in this
    response, and persists only their bcrypt hashes.

    Calling this again invalidates every code from the previous batch —
    exactly like `mfa_setup` invalidating the previous pending secret — so a
    user who suspects an old batch leaked can simply generate a new one.

    Requires `mfa_enabled` (recovery codes are only meaningful as a fallback
    for a second factor that actually exists).
    """
    enforce(
        RECOVERY_CODE_GENERATE_PER_USER,
        "recovery-codes-generate",
        user.id,
        detail="Too many recovery-code regenerations — try again later",
    )

    if not user.mfa_enabled:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Enable MFA first — recovery codes are a fallback for TOTP",
        )

    existing = await session.execute(select(RecoveryCode).where(RecoveryCode.user_id == user.id))
    for row in existing.scalars().all():
        await session.delete(row)

    codes = generate_recovery_codes()
    for code in codes:
        session.add(RecoveryCode(user_id=user.id, code_hash=hash_recovery_code(code)))
    await session.commit()

    return RecoveryCodesResponse(codes=codes)


@router.get("/mfa/recovery-codes/status", response_model=RecoveryCodeStatus)
async def recovery_codes_status(
    user: User = Depends(get_current_user), session: AsyncSession = Depends(get_session)
) -> RecoveryCodeStatus:
    """How many of the current batch are still unused — enough for the
    dashboard to nudge "regenerate your recovery codes" without ever being
    able to show a code's value again."""
    result = await session.execute(
        select(RecoveryCode).where(
            RecoveryCode.user_id == user.id,
            RecoveryCode.consumed_at.is_(None),
        )
    )
    return RecoveryCodeStatus(remaining=len(result.scalars().all()))


# --- D10: sessions ("sign out everywhere") -----------------------------------
# Built entirely on the SAME `revocation_store` every other revocation path in
# this file uses (logout, refresh rotation, MFA-token burns) — `UserSession`
# is only an INDEX onto that store (see app/models/user_session.py), not a
# second revocation mechanism. Revoking a session here revokes its jtis
# there, which is what `get_token_payload`/`authenticate_websocket_token`
# actually check on every subsequent request AND — via
# `app.core.security.revocation_aware_pump` — on every already-open
# websocket connection (see tests/test_auth_security_settings.py's websocket
# case for the proof).


async def _revoke_all_sessions(session: AsyncSession, *, user_id: str, keep_session_id: str | None = None) -> None:
    """Revokes every jti (access + refresh) belonging to every non-revoked
    session for `user_id`, except `keep_session_id` if given, and marks each
    such row revoked."""
    result = await session.execute(
        select(UserSession).where(
            UserSession.user_id == user_id,
            UserSession.revoked_at.is_(None),
        )
    )
    now = datetime.now(UTC)
    for row in result.scalars().all():
        if keep_session_id is not None and row.id == keep_session_id:
            continue
        await _revoke_session_jtis(row)
        row.revoked_at = now
        session.add(row)


@router.get("/sessions", response_model=SessionListResponse)
async def list_sessions(
    payload: dict = Depends(get_token_payload),
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> SessionListResponse:
    """Every currently-live session for the caller's OWN account — never
    another user's, there is no admin override here (a compromised session
    is dealt with by that account revoking it, or by an admin disabling the
    account entirely via the existing user-management endpoints)."""
    result = await session.execute(
        select(UserSession)
        .where(UserSession.user_id == user.id, UserSession.revoked_at.is_(None))
        .order_by(UserSession.last_seen_at.desc())
    )
    current_jti = payload.get("jti")
    rows = [
        SessionRead(
            id=row.id,
            user_agent=row.user_agent,
            ip_address=row.ip_address,
            created_at=row.created_at,
            last_seen_at=row.last_seen_at,
            is_current=(row.current_access_jti == current_jti),
        )
        for row in result.scalars().all()
    ]
    return SessionListResponse(sessions=rows)


@router.post("/sessions/{session_id}/revoke", status_code=status.HTTP_204_NO_CONTENT)
async def revoke_session(
    session_id: str,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> None:
    """Revokes ONE session by id — must belong to the caller. Kills that
    session's current access AND refresh jti via the shared revocation
    store, so an already-open websocket authenticated with that access jti
    dies within one `revocation_aware_pump` poll tick, and any future use of
    either token 401s immediately."""
    result = await session.execute(
        select(UserSession).where(
            UserSession.id == session_id,
            UserSession.user_id == user.id,
            UserSession.revoked_at.is_(None),
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")

    await _revoke_session_jtis(row)
    row.revoked_at = datetime.now(UTC)
    session.add(row)
    await session.commit()


@router.post("/sessions/revoke-all", status_code=status.HTTP_204_NO_CONTENT)
async def revoke_all_sessions(
    keep_current: bool = True,
    payload: dict = Depends(get_token_payload),
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> None:
    """"Sign out everywhere": revokes every OTHER live session for the caller
    by default (`keep_current=True`, so the button that triggered this
    doesn't also log the caller themselves out mid-click); pass
    `keep_current=false` to also kill the session making this call."""
    result = await session.execute(
        select(UserSession).where(UserSession.user_id == user.id, UserSession.revoked_at.is_(None))
    )
    current_jti = payload.get("jti")
    now = datetime.now(UTC)
    for row in result.scalars().all():
        if keep_current and row.current_access_jti == current_jti:
            continue
        await _revoke_session_jtis(row)
        row.revoked_at = now
        session.add(row)
    await session.commit()


__all__ = ["router"]
