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

from fastapi import APIRouter, Depends, HTTPException, status
from jose import JWTError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_session
from app.core.security import (
    TOKEN_TYPE_REFRESH,
    create_access_token,
    create_refresh_token,
    decode_token,
    get_current_user,
    revocation_store,
    verify_password,
)
from app.models.user import User
from app.schemas.auth import LoginRequest, RefreshRequest, RefreshResponse, TokenResponse, UserRead

router = APIRouter(prefix="/v1/auth", tags=["auth"])

_INVALID_CREDENTIALS = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid email or password"
)


@router.post("/login", response_model=TokenResponse)
async def login(body: LoginRequest, session: AsyncSession = Depends(get_session)) -> TokenResponse:
    result = await session.execute(select(User).where(User.email == body.email))
    user = result.scalar_one_or_none()

    if user is None or not user.pin_hash or not verify_password(body.password, user.pin_hash):
        raise _INVALID_CREDENTIALS
    if user.status != "active":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Account is not active")

    access_token = create_access_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)
    refresh_token = create_refresh_token(user_id=user.id, tenant_id=user.tenant_id, role=user.role)

    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token,
        user=UserRead.model_validate(user),
    )


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


__all__ = ["router"]
