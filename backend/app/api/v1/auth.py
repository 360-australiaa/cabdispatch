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

import logging
import time
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status
from jose import JWTError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import (
    TOKEN_TYPE_MFA,
    TOKEN_TYPE_PASSWORD_CHANGE,
    TOKEN_TYPE_REFRESH,
    create_access_token,
    create_mfa_pending_token,
    create_password_change_token,
    create_refresh_token,
    decode_token,
    generate_mfa_secret,
    get_current_user,
    get_current_user_for_password_change,
    get_token_payload_allow_password_change,
    hash_password,
    login_throttle_store,
    mfa_provisioning_uri,
    revocation_store,
    verify_password,
    verify_totp_code,
)
from app.models.tenant import Tenant
from app.models.user import User
from app.schemas.auth import (
    AcceptInviteRequest,
    ChangePasswordRequest,
    DriverLoginRequest,
    ForgotPasswordRequest,
    ForgotPasswordResponse,
    LoginRequest,
    LogoutRequest,
    MfaDisableRequest,
    MfaLoginRequest,
    MfaRequiredResponse,
    MfaSetupResponse,
    MfaStatusResponse,
    MfaVerifyRequest,
    MfaVerifyResponse,
    PasswordChangeRequiredResponse,
    RefreshRequest,
    RefreshResponse,
    ResetPasswordRequest,
    ResetPasswordResponse,
    TokenResponse,
    UserRead,
)
from app.models.user_invite import INVITE_PURPOSE_INVITE, INVITE_PURPOSE_PASSWORD_RESET
from app.services.compliance_expiry import is_expired
from app.services.mfa_recovery_codes import consume_recovery_code, generate_recovery_codes
from app.services.user_invites import (
    InvalidInviteTokenError,
    consume_invite,
    create_invite,
    send_reset_email,
)

router = APIRouter(prefix="/v1/auth", tags=["auth"])

logger = logging.getLogger("cab_dispatch.auth")

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


def _issue_tokens(user: User) -> TokenResponse:
    return TokenResponse(
        access_token=create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        refresh_token=create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        user=UserRead.model_validate(user),
    )


async def _assert_tenant_not_suspended(session: AsyncSession, tenant_id: str | None) -> None:
    """Tenant lifecycle (plan Part 4 Phase 1, WP-18): a suspended tenant
    blocks login for every one of its users, checked at the shared login
    gate below so every login path (login/driver-login/accept-invite) gets
    it for free. tenant_id can be None for platform-tenant-scoped staff
    edge cases (see app.models.user own module docstring) -- nothing to
    check in that case, so this is a no-op."""
    if tenant_id is None:
        return
    result = await session.execute(select(Tenant).where(Tenant.id == tenant_id))
    tenant = result.scalar_one_or_none()
    if tenant is not None and tenant.status == "suspended":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This operator account has been suspended. Contact support.",
        )


async def _login_result(
    user: User,
    session: AsyncSession,
) -> TokenResponse | MfaRequiredResponse | PasswordChangeRequiredResponse:
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
    await _assert_tenant_not_suspended(session, user.tenant_id)

    if user.status != "active":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Account is not active")

    if user.must_change_password:
        password_change_token = create_password_change_token(
            user_id=user.id, tenant_id=user.tenant_id, role=user.role
        )
        return PasswordChangeRequiredResponse(password_change_token=password_change_token)

    if user.mfa_enabled:
        mfa_token = create_mfa_pending_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)
        return MfaRequiredResponse(mfa_token=mfa_token)

    return _issue_tokens(user)


@router.post(
    "/login",
    response_model=TokenResponse | MfaRequiredResponse | PasswordChangeRequiredResponse,
)
async def login(
    body: LoginRequest, session: AsyncSession = Depends(get_session)
) -> TokenResponse | MfaRequiredResponse | PasswordChangeRequiredResponse:
    # I-5 fix (plan Part 4 Phase 1 WP-16): identifier is the email itself --
    # login resolves the user by email alone, with no tenant scope at this
    # stage (see app.models.user own module docstring), so throttling keys
    # on the same thing the lookup below keys on. The 401 raised below is
    # IDENTICAL whether the account is locked out or the password is simply
    # wrong -- a caller can never tell the two apart from the response; only
    # this log line distinguishes them for operators.
    if await login_throttle_store.is_locked_out(body.email):
        logger.info("Login attempt for %s rejected: locked out", body.email)
        raise _INVALID_CREDENTIALS

    result = await session.execute(select(User).where(User.email == body.email))
    user = result.scalar_one_or_none()

    if user is None or not user.pin_hash or not verify_password(body.password, user.pin_hash):
        await login_throttle_store.record_failure(body.email)
        raise _INVALID_CREDENTIALS

    await login_throttle_store.reset(body.email)
    return await _login_result(user, session)


@router.post(
    "/driver-login",
    response_model=TokenResponse | MfaRequiredResponse | PasswordChangeRequiredResponse,
)
async def driver_login(
    body: DriverLoginRequest, session: AsyncSession = Depends(get_session)
) -> TokenResponse | MfaRequiredResponse | PasswordChangeRequiredResponse:
    """The real driver-facing counterpart to POST /login: Driver ID + PIN
    instead of email + password. `driver_code` is globally unique (see
    app/services/user.py::assert_driver_code_available) so — same as
    email — the lookup needs no tenant context.

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
    if await login_throttle_store.is_locked_out(body.driver_code):
        logger.info("Driver login attempt for %s rejected: locked out", body.driver_code)
        raise _INVALID_DRIVER_CREDENTIALS

    result = await session.execute(select(User).where(User.driver_code == body.driver_code))
    user = result.scalar_one_or_none()

    if (
        user is None
        or user.role != "driver"
        or not user.pin_hash
        or not verify_password(body.pin, user.pin_hash)
    ):
        await login_throttle_store.record_failure(body.driver_code)
        raise _INVALID_DRIVER_CREDENTIALS

    await login_throttle_store.reset(body.driver_code)

    if is_expired(user.driver_license_expiry):
        raise _LICENSE_EXPIRED

    return await _login_result(user, session)


@router.post("/mfa/login", response_model=TokenResponse)
async def mfa_login(
    body: MfaLoginRequest, session: AsyncSession = Depends(get_session)
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
        # WP-16 (I-5): a valid, unused MFA recovery code is accepted as an
        # alternative to a TOTP code here -- lets a user who lost their
        # authenticator app still get in. Consumed single-use on success (see
        # app.services.mfa_recovery_codes.consume_recovery_code).
        if not await consume_recovery_code(session, user_id=user.id, code=body.code):
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

    # I-4 fix (plan Part 4 Phase 1 WP-15): rotation used to issue a new
    # refresh token without revoking the old one, leaving it replayable for
    # its full REFRESH_TOKEN_EXPIRE_DAYS lifetime even after this exchange.
    # Revoke the presented jti before handing back a new pair, same
    # single-use-token-burn pattern already used for the mfa_token /
    # password_change_token above.
    if jti:
        exp = payload.get("exp")
        ttl = max(int(exp - time.time()), 1) if exp else 1
        await revocation_store.revoke(jti, ttl)

    return RefreshResponse(
        access_token=create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        refresh_token=create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
    )


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    body: LogoutRequest | None = None,
    payload: dict = Depends(get_token_payload_allow_password_change),
    user: User = Depends(get_current_user),
) -> None:
    """I-4 fix (plan Part 4 Phase 1 WP-15): actually revokes the caller
    own access-token jti via revocation_store, and -- if the client sends
    one in the body -- the presented refresh_token jti too.

    body is entirely OPTIONAL: grepped every existing call site before
    changing this contract (android/.../ApiService.kt logout() and
    dashboard/src/lib/auth.tsx apiClient.post("/v1/auth/logout")) and both
    call with no body at all today, so this stays backward compatible --
    a caller that never sends refresh_token simply gets its access token
    revoked, same as before this fix except now that revocation actually
    happens.
    """
    jti = payload.get("jti")
    if jti:
        exp = payload.get("exp")
        ttl = max(int(exp - time.time()), 1) if exp else 1
        await revocation_store.revoke(jti, ttl)

    if body and body.refresh_token:
        try:
            refresh_payload = decode_token(body.refresh_token)
        except JWTError:
            refresh_payload = None

        if refresh_payload is not None and refresh_payload.get("type") == TOKEN_TYPE_REFRESH:
            refresh_jti = refresh_payload.get("jti")
            if refresh_jti:
                refresh_exp = refresh_payload.get("exp")
                refresh_ttl = max(int(refresh_exp - time.time()), 1) if refresh_exp else 1
                await revocation_store.revoke(refresh_jti, refresh_ttl)

    return


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


@router.post("/mfa/verify", response_model=MfaVerifyResponse)
async def mfa_verify(
    body: MfaVerifyRequest,
    user: User = Depends(get_current_user),
    session: AsyncSession = Depends(get_session),
) -> MfaVerifyResponse:
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

    # WP-16 (I-5): one-time backup codes, generated exactly here (the moment
    # mfa_enabled actually flips True) and returned ONCE -- see
    # app.services.mfa_recovery_codes for the storage/consumption contract.
    recovery_codes = await generate_recovery_codes(session, user=user)

    return MfaVerifyResponse(mfa_enabled=True, recovery_codes=recovery_codes)


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


# --- Password lifecycle: invite / forgot / reset / change (plan Part 4,
# Phase 1, WP-10/11/12) --------------------------------------------------


@router.post(
    "/accept-invite",
    response_model=TokenResponse | MfaRequiredResponse | PasswordChangeRequiredResponse,
)
async def accept_invite(
    body: AcceptInviteRequest, session: AsyncSession = Depends(get_session)
) -> TokenResponse | MfaRequiredResponse | PasswordChangeRequiredResponse:
    """Sets the password for a user created via the invite flow (pin_hash was
    None until now -- see app.models.user_invite's module docstring for why
    that alone already blocked login), then logs them straight in through
    the same _login_result gate every other login goes through (active
    check + must_change_password, which consume_invite always clears for
    this purpose, + MFA)."""
    try:
        user = await consume_invite(
            session,
            token=body.token,
            new_password=body.new_password,
            expected_purpose=INVITE_PURPOSE_INVITE,
        )
    except InvalidInviteTokenError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return await _login_result(user, session)


@router.post(
    "/forgot-password",
    response_model=ForgotPasswordResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def forgot_password(
    body: ForgotPasswordRequest, session: AsyncSession = Depends(get_session)
) -> ForgotPasswordResponse:
    """Always returns the exact same response body and status code, whether
    or not `email` matches a real, active account -- a deliberate
    anti-account-enumeration property (plan Part 4 Phase 1 WP-11, docs/
    ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md I-1..I-5 threat class). Only a
    genuine, active account actually gets a reset token issued and an email
    sent; a caller can never distinguish that from a no-op by anything this
    endpoint returns."""
    result = await session.execute(select(User).where(User.email == body.email))
    user = result.scalar_one_or_none()

    if user is not None and user.status == "active":
        _invite, _raw_token, link = await create_invite(
            session, user=user, purpose=INVITE_PURPOSE_PASSWORD_RESET
        )
        send_reset_email(to_email=user.email, name=user.name, link=link)

    return ForgotPasswordResponse()


@router.post("/reset-password", response_model=ResetPasswordResponse)
async def reset_password(
    body: ResetPasswordRequest, session: AsyncSession = Depends(get_session)
) -> ResetPasswordResponse:
    """Consumes a purpose="password_reset" token issued by
    POST /v1/auth/forgot-password. Unlike accept-invite, this does NOT log
    the user in -- a forgotten-password reset is a recovery action, not an
    onboarding step, so the natural next action is a normal login with the
    new password rather than an implicit session."""
    try:
        await consume_invite(
            session,
            token=body.token,
            new_password=body.new_password,
            expected_purpose=INVITE_PURPOSE_PASSWORD_RESET,
        )
    except InvalidInviteTokenError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    return ResetPasswordResponse()


@router.post("/change-password", response_model=TokenResponse)
async def change_password(
    body: ChangePasswordRequest,
    payload: dict = Depends(get_token_payload_allow_password_change),
    user: User = Depends(get_current_user_for_password_change),
    session: AsyncSession = Depends(get_session),
) -> TokenResponse:
    """Self-service password change (plan Part 4 Phase 1 WP-12) -- the
    endpoint this codebase never had (confirmed by grep before writing this:
    no password-change/self-service route existed anywhere in app/api/v1).

    Reachable two ways, both requiring the CURRENT password:
    - a normal already-authenticated user changing their password by
      choice (a plain access token, verified by
      get_token_payload_allow_password_change same as get_token_payload);
    - a user who just logged in with must_change_password=True and holds
      only the short-lived password_change_token _login_result issued them
      instead of real tokens.

    current_password is required and checked against the existing hash in
    BOTH cases -- even for the forced-change flow, where the caller already
    proved that same password seconds ago at login. This keeps one uniform
    implementation instead of two, and is strictly more defensive: a
    password_change_token that leaked (e.g. through a referrer header, a
    shared clipboard) still cannot be used to set a new password without
    also knowing the current one.
    """
    if not user.pin_hash or not verify_password(body.current_password, user.pin_hash):
        raise _INVALID_CREDENTIALS

    user.pin_hash = hash_password(body.new_password)
    user.must_change_password = False
    user.password_changed_at = datetime.now(UTC)
    session.add(user)
    await session.commit()
    await session.refresh(user)

    # Single-use burn of the password_change_token, if that's what was
    # presented -- mirrors POST /v1/auth/mfa/login's jti-burn of the
    # mfa_token below, same reasoning: a special-purpose token good for
    # exactly one call should not be replayable within its remaining TTL.
    if payload.get("type") == TOKEN_TYPE_PASSWORD_CHANGE:
        jti = payload.get("jti")
        if jti:
            exp = payload.get("exp")
            ttl = max(int(exp - time.time()), 1) if exp else 1
            await revocation_store.revoke(jti, ttl)

    return _issue_tokens(user)


__all__ = ["router"]
