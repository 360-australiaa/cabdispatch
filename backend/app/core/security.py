"""Password hashing, JWT issuance/verification, token revocation, and the
FastAPI auth/RBAC dependencies every domain router in this project depends on.

Multi-tenancy note: row-level tenant isolation in this whole system is enforced
ENTIRELY at the application layer via `get_current_tenant_id`. Every domain router
MUST filter every query it runs by the tenant_id this dependency returns. There is
no database-level RLS.
"""
from __future__ import annotations

import logging
import time
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pyotp
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.database import get_session

logger = logging.getLogger("cab_dispatch.security")

# Tenant "0" — the platform operator ("TCT") — the only tenant_id value
# permitted to act cross-tenant, and only for role == "owner". This is a real
# row in the `tenants` table (see scripts/seed.py), so it uses the same
# UUID-shaped id convention as every other tenant_id in the system rather than
# a special-cased literal string.
PLATFORM_TENANT_ID = "00000000-0000-0000-0000-000000000000"

# --- Password hashing -------------------------------------------------------

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_password(plain_password: str) -> str:
    return pwd_context.hash(plain_password)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    return pwd_context.verify(plain_password, hashed_password)


# --- JWT create / verify ----------------------------------------------------

TOKEN_TYPE_ACCESS = "access"
TOKEN_TYPE_REFRESH = "refresh"
# Short-lived, single-purpose token type issued by POST /v1/auth/login when
# the account has mfa_enabled=True: proves email+password already succeeded,
# but is only accepted by POST /v1/auth/mfa/login (never by the regular
# get_current_user dependency — see get_current_user's docstring below) and
# only good for MFA_TOKEN_EXPIRE_MINUTES.
TOKEN_TYPE_MFA = "mfa_pending"
MFA_TOKEN_EXPIRE_MINUTES = 5


def _create_token(
    *, user_id: str, tenant_id: str | None, role: str, token_type: str, expires_delta: timedelta
) -> str:
    now = datetime.now(UTC)
    payload: dict[str, Any] = {
        "sub": user_id,
        "tenant_id": tenant_id,
        "role": role,
        "jti": str(uuid.uuid4()),
        "type": token_type,
        "iat": int(now.timestamp()),
        "exp": now + expires_delta,
    }
    return jwt.encode(payload, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)


def create_access_token(*, user_id: str, tenant_id: str | None, role: str) -> str:
    return _create_token(
        user_id=user_id,
        tenant_id=tenant_id,
        role=role,
        token_type=TOKEN_TYPE_ACCESS,
        expires_delta=timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES),
    )


def create_refresh_token(*, user_id: str, tenant_id: str | None, role: str) -> str:
    return _create_token(
        user_id=user_id,
        tenant_id=tenant_id,
        role=role,
        token_type=TOKEN_TYPE_REFRESH,
        expires_delta=timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS),
    )


def create_mfa_pending_token(*, user_id: str, tenant_id: str | None, role: str) -> str:
    """Issued by POST /v1/auth/login in place of real tokens when the account
    has mfa_enabled=True. Only POST /v1/auth/mfa/login accepts this token
    type; see get_token_payload's docstring for why it's rejected everywhere
    else."""
    return _create_token(
        user_id=user_id,
        tenant_id=tenant_id,
        role=role,
        token_type=TOKEN_TYPE_MFA,
        expires_delta=timedelta(minutes=MFA_TOKEN_EXPIRE_MINUTES),
    )


def decode_token(token: str) -> dict[str, Any]:
    """Raises jose.JWTError on invalid signature/expiry."""
    return jwt.decode(token, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM])


# --- TOTP MFA (blueprint 12.2) ----------------------------------------------
# Local computation only (pyotp), no external API — the payments.py-style
# real-vs-mock credential fallback doesn't apply here, there's nothing to call
# out to.

MFA_ISSUER_NAME = "Cab Dispatch"


def generate_mfa_secret() -> str:
    """A new base32 TOTP secret, suitable for pyotp.TOTP(secret)."""
    return pyotp.random_base32()


def mfa_provisioning_uri(*, secret: str, email: str) -> str:
    """An otpauth:// URI for the frontend to render as a QR code (or show as
    manual-entry text) in an authenticator app."""
    return pyotp.totp.TOTP(secret).provisioning_uri(name=email, issuer_name=MFA_ISSUER_NAME)


def verify_totp_code(*, secret: str, code: str) -> bool:
    """valid_window=1 tolerates one 30s step of clock drift either side,
    matching the reference pattern used elsewhere in this workspace
    (captaindash/backend's app.core.security.verify_mfa)."""
    return pyotp.TOTP(secret).verify(code, valid_window=1)


# --- jti revocation set: Redis-backed with in-memory fallback ---------------


class _RevocationStore:
    """Tracks revoked JWT `jti` values.

    Tries Redis first; if Redis is unreachable (connection refused, timeout,
    whatever) it falls back to an in-memory dict for the lifetime of the process,
    logs ONE warning, and never crashes the app. This matters because this dev
    environment does not run Redis.
    """

    def __init__(self) -> None:
        self._redis = None
        self._redis_broken = False
        self._warned = False
        self._memory: dict[str, float] = {}  # jti -> expiry unix ts

        if settings.REDIS_URL:
            try:
                import redis.asyncio as redis_asyncio

                self._redis = redis_asyncio.from_url(
                    settings.REDIS_URL, socket_connect_timeout=0.5, socket_timeout=0.5
                )
            except Exception:
                self._redis = None
                self._redis_broken = True

    def _warn_fallback_once(self) -> None:
        if not self._warned:
            logger.warning(
                "Redis unreachable at %s — falling back to in-memory JWT revocation "
                "store (not shared across processes/restarts).",
                settings.REDIS_URL,
            )
            self._warned = True

    def _memory_gc(self) -> None:
        now = time.time()
        expired = [k for k, exp in self._memory.items() if exp <= now]
        for k in expired:
            del self._memory[k]

    async def revoke(self, jti: str, ttl_seconds: int) -> None:
        if self._redis is not None and not self._redis_broken:
            try:
                await self._redis.setex(f"revoked_jti:{jti}", max(ttl_seconds, 1), "1")
                return
            except Exception:
                self._redis_broken = True
                self._warn_fallback_once()
        self._memory_gc()
        self._memory[jti] = time.time() + max(ttl_seconds, 1)

    async def is_revoked(self, jti: str) -> bool:
        if self._redis is not None and not self._redis_broken:
            try:
                return bool(await self._redis.exists(f"revoked_jti:{jti}"))
            except Exception:
                self._redis_broken = True
                self._warn_fallback_once()
        self._memory_gc()
        return jti in self._memory


revocation_store = _RevocationStore()

# --- FastAPI auth dependencies ----------------------------------------------

_bearer_scheme = HTTPBearer(auto_error=True)

_CREDENTIALS_EXCEPTION = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED,
    detail="Could not validate credentials",
    headers={"WWW-Authenticate": "Bearer"},
)


async def get_token_payload(
    credentials: HTTPAuthorizationCredentials = Depends(_bearer_scheme),
) -> dict[str, Any]:
    """Decodes + verifies the bearer token, rejects revoked jtis, and rejects
    anything that isn't a full access token.

    That last check matters for MFA (blueprint 12.2): POST /v1/auth/login
    issues a TOKEN_TYPE_MFA token to accounts with mfa_enabled=True instead of
    a real access token — without this check, that short-lived token would
    work as a bearer credential on every protected endpoint below, letting
    anyone who knows the password skip the TOTP step entirely for its 5-minute
    lifetime. (POST /v1/auth/refresh already self-checks TOKEN_TYPE_REFRESH
    before this dependency ever sees a refresh token, so it's unaffected.)
    """
    try:
        payload = decode_token(credentials.credentials)
    except JWTError:
        raise _CREDENTIALS_EXCEPTION

    if payload.get("type") != TOKEN_TYPE_ACCESS:
        raise _CREDENTIALS_EXCEPTION

    jti = payload.get("jti")
    if jti and await revocation_store.is_revoked(jti):
        raise _CREDENTIALS_EXCEPTION

    return payload


async def get_current_user(
    payload: dict[str, Any] = Depends(get_token_payload),
    session: AsyncSession = Depends(get_session),
):
    """Loads the User row referenced by the token's `sub` claim."""
    from app.models.user import User  # local import: avoids import-order issues

    user_id = payload.get("sub")
    if not user_id:
        raise _CREDENTIALS_EXCEPTION

    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise _CREDENTIALS_EXCEPTION
    return user


async def get_current_tenant_id(
    request: Request,
    payload: dict[str, Any] = Depends(get_token_payload),
) -> str:
    """The row-level multi-tenancy mechanism every domain router MUST use to
    filter every query.

    - role == "owner" whose token tenant_id == PLATFORM_TENANT_ID (the "TCT"
      platform tenant, tenant 0) may pass `?tenant_id=<id>` to act cross-tenant.
    - Everyone else is hard-locked to their own token's tenant_id; a query-string
      tenant_id is silently ignored for them.
    """
    token_tenant_id = payload.get("tenant_id")
    role = payload.get("role")

    if role == "owner" and token_tenant_id == PLATFORM_TENANT_ID:
        override = request.query_params.get("tenant_id")
        if override:
            return override
        return token_tenant_id

    if not token_tenant_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Token has no tenant scope",
        )
    return token_tenant_id


# Alias — some call sites read more naturally as "require_tenant_scope".
require_tenant_scope = get_current_tenant_id


def require_role(*roles: str):
    """Dependency factory for RBAC. Usage: Depends(require_role("owner", "admin"))."""

    async def _dependency(user=Depends(get_current_user)):
        if user.role not in roles:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Requires one of roles: {', '.join(roles)}",
            )
        return user

    return _dependency
