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
# Same shape as TOKEN_TYPE_MFA, for the other case POST /v1/auth/login (and
# /driver-login) issue something other than a real access token: an account
# with User.must_change_password=True. Only POST /v1/auth/change-password
# accepts this type (see get_token_payload_allow_password_change below) --
# never get_current_user -- so a user who has not yet proven a new password
# cannot use it as a bearer credential anywhere else in the API.
TOKEN_TYPE_PASSWORD_CHANGE = "password_change_pending"
PASSWORD_CHANGE_TOKEN_EXPIRE_MINUTES = 15


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


def create_password_change_token(*, user_id: str, tenant_id: str | None, role: str) -> str:
    """Issued by POST /v1/auth/login (and /driver-login) in place of real
    tokens when user.must_change_password is True. Only POST
    /v1/auth/change-password accepts this token type -- see
    get_token_payload_allow_password_change below."""
    return _create_token(
        user_id=user_id,
        tenant_id=tenant_id,
        role=role,
        token_type=TOKEN_TYPE_PASSWORD_CHANGE,
        expires_delta=timedelta(minutes=PASSWORD_CHANGE_TOKEN_EXPIRE_MINUTES),
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

# --- Login attempt throttling (I-5) -----------------------------------------


class _LoginThrottleStore:
    """Tracks failed login attempts per credential identifier (email for
    POST /v1/auth/login, driver_code for POST /v1/auth/driver-login -- see
    call sites in app/api/v1/auth.py) and locks that identifier out for a
    fixed period once too many failures happen in a rolling window.

    Same Redis-first / in-memory-fallback shape as _RevocationStore above --
    mirrored deliberately rather than inventing a third pattern in this
    module. Redis storage: a counter key (login_fail_count:{key}) with a
    sliding-ish window via TTL-on-first-increment, plus a separate
    login_lockout:{key} key (existence == locked out) set with its own TTL
    once the counter crosses the threshold. In-memory fallback: a per-key
    timestamp list (pruned to the window on each check) and a per-key lockout
    expiry, same semantics.

    Numbers (docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md I-5, plan Part 4
    Phase 1 WP-16): configurable via app.core.config.settings --
    LOGIN_MAX_FAILED_ATTEMPTS (default 5) failures within
    LOGIN_ATTEMPT_WINDOW_MINUTES (default 15) locks the identifier out for
    LOGIN_LOCKOUT_MINUTES (default 15). This is a security-engineering
    judgment call, not a regulatory requirement -- a reasonable literal
    default, made configurable per this project standard for that case.
    """

    def __init__(self) -> None:
        self._redis = None
        self._redis_broken = False
        self._warned = False
        self._attempts: dict[str, list[float]] = {}
        self._lockouts: dict[str, float] = {}

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
                "Redis unreachable at %s -- falling back to in-memory login "
                "throttle store (not shared across processes/restarts).",
                settings.REDIS_URL,
            )
            self._warned = True

    @staticmethod
    def _key(identifier: str) -> str:
        return identifier.strip().lower()

    async def is_locked_out(self, identifier: str) -> bool:
        key = self._key(identifier)
        if self._redis is not None and not self._redis_broken:
            try:
                return bool(await self._redis.exists(f"login_lockout:{key}"))
            except Exception:
                self._redis_broken = True
                self._warn_fallback_once()

        now = time.time()
        until = self._lockouts.get(key)
        if until is None:
            return False
        if until <= now:
            del self._lockouts[key]
            return False
        return True

    async def record_failure(self, identifier: str) -> None:
        """Call once per failed credential check. May flip the identifier
        into a lockout state as a side effect once the threshold is crossed
        -- the caller does not need to check the count itself, only
        is_locked_out on the NEXT attempt."""
        key = self._key(identifier)
        window_seconds = settings.LOGIN_ATTEMPT_WINDOW_MINUTES * 60
        lockout_seconds = settings.LOGIN_LOCKOUT_MINUTES * 60

        if self._redis is not None and not self._redis_broken:
            try:
                count_key = f"login_fail_count:{key}"
                count = await self._redis.incr(count_key)
                if count == 1:
                    await self._redis.expire(count_key, window_seconds)
                if count >= settings.LOGIN_MAX_FAILED_ATTEMPTS:
                    await self._redis.setex(f"login_lockout:{key}", lockout_seconds, "1")
                return
            except Exception:
                self._redis_broken = True
                self._warn_fallback_once()

        now = time.time()
        window_start = now - window_seconds
        attempts = [t for t in self._attempts.get(key, []) if t > window_start]
        attempts.append(now)
        self._attempts[key] = attempts
        if len(attempts) >= settings.LOGIN_MAX_FAILED_ATTEMPTS:
            self._lockouts[key] = now + lockout_seconds
            self._attempts[key] = []

    async def reset(self, identifier: str) -> None:
        """Call on a SUCCESSFUL credential check to clear any accumulated
        failure count for this identifier (does not clear an active
        lockout -- a lockout must expire on its own even if the correct
        password is later supplied, otherwise lockout would be pointless
        against an attacker who eventually guesses right)."""
        key = self._key(identifier)
        if self._redis is not None and not self._redis_broken:
            try:
                await self._redis.delete(f"login_fail_count:{key}")
                return
            except Exception:
                self._redis_broken = True
                self._warn_fallback_once()
        self._attempts.pop(key, None)


login_throttle_store = _LoginThrottleStore()

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


async def get_token_payload_allow_password_change(
    credentials: HTTPAuthorizationCredentials = Depends(_bearer_scheme),
) -> dict[str, Any]:
    """Same contract as get_token_payload, except it ALSO accepts
    TOKEN_TYPE_PASSWORD_CHANGE -- the short-lived token POST /v1/auth/login
    issues instead of real tokens when the account has
    must_change_password=True. Used only by POST /v1/auth/change-password,
    so that call can be reached either by a normal already-authenticated
    user (self-service change) or by a user who has proven their OLD
    password at login but not yet a new one (forced change) -- never by an
    MFA-pending or refresh token, matching get_token_payload existing
    exclusions.
    """
    try:
        payload = decode_token(credentials.credentials)
    except JWTError:
        raise _CREDENTIALS_EXCEPTION

    if payload.get("type") not in (TOKEN_TYPE_ACCESS, TOKEN_TYPE_PASSWORD_CHANGE):
        raise _CREDENTIALS_EXCEPTION

    jti = payload.get("jti")
    if jti and await revocation_store.is_revoked(jti):
        raise _CREDENTIALS_EXCEPTION

    return payload


async def get_current_user_for_password_change(
    payload: dict[str, Any] = Depends(get_token_payload_allow_password_change),
    session: AsyncSession = Depends(get_session),
):
    """Loads the User row for either an access token or a
    password-change-pending token -- see
    get_token_payload_allow_password_change above."""
    from app.models.user import User  # local import: avoids import-order issues

    user_id = payload.get("sub")
    if not user_id:
        raise _CREDENTIALS_EXCEPTION

    result = await session.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise _CREDENTIALS_EXCEPTION
    return user


async def resolve_tenant_id(request, payload, session):
    """The core logic behind get_current_tenant_id, extracted so a route that
    cannot use the mandatory-bearer FastAPI dependency chain (e.g. a device
    presenting a device secret instead of a user bearer token, see
    get_token_payload_optional / POST /v1/fleet/devices/{id}/heartbeat) can
    still resolve a tenant_id from an already-decoded token payload when one
    is present. Pure extraction, no behaviour change -- get_current_tenant_id
    below is now a thin wrapper around this.

    - role == "owner" whose token tenant_id == PLATFORM_TENANT_ID (the "TCT"
      platform tenant, tenant 0) may pass ?tenant_id=<id> to act cross-tenant.
    - Everyone else is hard-locked to their own token tenant_id; a query-string
      tenant_id is silently ignored for them.

    I-3 fix (docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md, plan Part 4 Phase 1
    WP-14): the ?tenant_id= override used to be returned verbatim with zero
    validation. It is now checked against a real Tenant row -- 404 if no such
    tenant exists -- and every time the override is actually USED to view a
    DIFFERENT tenant than the token own, that access is written to the
    tamper-evident audit log (action="cross_tenant_access") via
    app.services.audit_log.record_audit.
    """
    token_tenant_id = payload.get("tenant_id")
    role = payload.get("role")

    if role == "owner" and token_tenant_id == PLATFORM_TENANT_ID:
        override = request.query_params.get("tenant_id")
        if override:
            from app.models.tenant import Tenant  # local import: avoids import-order issues

            result = await session.execute(select(Tenant.id).where(Tenant.id == override))
            if result.scalar_one_or_none() is None:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail="Tenant not found",
                )

            if override != token_tenant_id:
                from app.services.audit_log import record_audit  # local import: avoids circular import

                await record_audit(
                    session,
                    tenant_id=override,
                    actor_user_id=payload.get("sub"),
                    action="cross_tenant_access",
                    entity_type="tenant",
                    entity_id=override,
                    after={
                        "role": role,
                        "token_tenant_id": token_tenant_id,
                        "accessed_tenant_id": override,
                        "path": request.url.path,
                    },
                )
                await session.commit()

            return override
        return token_tenant_id

    if not token_tenant_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Token has no tenant scope",
        )
    return token_tenant_id


async def get_current_tenant_id(
    request: Request,
    payload: dict[str, Any] = Depends(get_token_payload),
    session: AsyncSession = Depends(get_session),
) -> str:
    """The row-level multi-tenancy mechanism every domain router MUST use to
    filter every query. See resolve_tenant_id above for the actual logic --
    this is the standard FastAPI-dependency-chain wrapper around it (a
    mandatory bearer token via get_token_payload)."""
    return await resolve_tenant_id(request, payload, session)


# Alias -- some call sites read more naturally as "require_tenant_scope".
require_tenant_scope = get_current_tenant_id


_optional_bearer_scheme = HTTPBearer(auto_error=False)


async def get_token_payload_optional(credentials=Depends(_optional_bearer_scheme)):
    """Same decode/type/revocation checks as get_token_payload, but never
    raises -- returns None if no Authorization header was sent at all, or if
    the token is missing/invalid/wrong-type/revoked. For routes that accept
    EITHER a user bearer token OR a different credential entirely (device
    secret -- see POST /v1/fleet/devices/{id}/heartbeat), where a mandatory
    bearer scheme would incorrectly 401 a legitimately-device-authenticated
    request before that route ever gets a chance to check the other
    credential. Every OTHER route in this system keeps using the strict
    get_token_payload / get_current_tenant_id / get_current_user chain
    unchanged -- this is a new, additive, narrowly-used alternative, not a
    replacement."""
    if credentials is None:
        return None
    try:
        payload = decode_token(credentials.credentials)
    except JWTError:
        return None
    if payload.get("type") != TOKEN_TYPE_ACCESS:
        return None
    jti = payload.get("jti")
    if jti and await revocation_store.is_revoked(jti):
        return None
    return payload


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
