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

from fastapi import APIRouter, Depends, HTTPException, status
from jose import JWTError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import (
    TOKEN_TYPE_MFA,
    TOKEN_TYPE_REFRESH,
    create_access_token,
    create_mfa_pending_token,
    create_refresh_token,
    decode_token,
    generate_mfa_secret,
    get_current_user,
    mfa_provisioning_uri,
    revocation_store,
    verify_password,
    verify_totp_code,
)
from app.models.user import User
from app.schemas.auth import (
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

router = APIRouter(prefix="/v1/auth", tags=["auth"])

_INVALID_CREDENTIALS = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid email or password"
)
_INVALID_MFA_CODE = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid MFA code"
)


def _issue_tokens(user: User) -> TokenResponse:
    return TokenResponse(
        access_token=create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        refresh_token=create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        user=UserRead.model_validate(user),
    )


@router.post("/login", response_model=TokenResponse | MfaRequiredResponse)
async def login(
    body: LoginRequest, session: AsyncSession = Depends(get_session)
) -> TokenResponse | MfaRequiredResponse:
    result = await session.execute(select(User).where(User.email == body.email))
    user = result.scalar_one_or_none()

    if user is None or not user.pin_hash or not verify_password(body.password, user.pin_hash):
        raise _INVALID_CREDENTIALS
    if user.status != "active":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Account is not active")

    # MFA opt-in (blueprint 12.2): accounts that never enabled it get exactly
    # the pre-existing behavior below, unchanged — this branch only fires for
    # mfa_enabled=True accounts.
    if user.mfa_enabled:
        mfa_token = create_mfa_pending_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)
        return MfaRequiredResponse(mfa_token=mfa_token)

    return _issue_tokens(user)


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

    return RefreshResponse(
        access_token=create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
        refresh_token=create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role),
    )


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(user: User = Depends(get_current_user)) -> None:
    """Revokes nothing server-side beyond what a real jti-blacklist entry would
    need the raw token for (the dependency only decodes+validates it, it
    doesn't hand the jti back here) — clients should discard their tokens
    client-side. Kept as a named endpoint for API symmetry / future
    jti-revocation wiring rather than omitted entirely."""
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
