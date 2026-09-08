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


# --- D10: password reset by email -------------------------------------------
# Same single-purpose, single-use, short-lived pattern as TOKEN_TYPE_MFA above:
# a distinct `type` claim so this token can never be replayed anywhere a
# regular access/refresh token is accepted, a short TTL, and a burn-on-use via
# the same `revocation_store` every other one-shot token already uses.

TOKEN_TYPE_PASSWORD_RESET = "password_reset"
PASSWORD_RESET_TOKEN_EXPIRE_MINUTES = 30


def create_password_reset_token(*, user_id: str) -> str:
    """No `tenant_id`/`role` claims — this token only ever proves "the holder
    controls this account's registered email", it is never exchanged for a
    session, so it carries nothing `get_current_user`/`get_current_tenant_id`
    could accidentally be persuaded to accept."""
    return _create_token(
        user_id=user_id,
        tenant_id=None,
        role="",
        token_type=TOKEN_TYPE_PASSWORD_RESET,
        expires_delta=timedelta(minutes=PASSWORD_RESET_TOKEN_EXPIRE_MINUTES),
    )


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


# --- D10: MFA recovery codes -------------------------------------------------
# Generated at RUNTIME via the stdlib `secrets` CSPRNG — never hardcoded,
# never a fixture value, never logged. Shown to the user exactly once (by the
# caller, immediately after generation); only the bcrypt HASH of each code is
# ever persisted (see app.models.recovery_code.RecoveryCode), through the
# SAME `hash_password`/`verify_password` pair used for the account password —
# this is deliberately not a second hashing scheme.

RECOVERY_CODE_COUNT = 10
# XXXX-XXXX-XXXX, base32-alphabet (no 0/O/1/I ambiguity), 12 chars of entropy.
_RECOVERY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def generate_recovery_codes(count: int = RECOVERY_CODE_COUNT) -> list[str]:
    import secrets

    def _one() -> str:
        raw = "".join(secrets.choice(_RECOVERY_CODE_ALPHABET) for _ in range(12))
        return f"{raw[0:4]}-{raw[4:8]}-{raw[8:12]}"

    return [_one() for _ in range(count)]


def hash_recovery_code(code: str) -> str:
    """Normalises case/whitespace before hashing so a user typing a code back
    in lowercase (or with the dashes copy-pasted differently) still matches —
    the codes themselves are the secret, not their exact rendering."""
    return hash_password(code.strip().upper())


def verify_recovery_code(code: str, code_hash: str) -> bool:
    return verify_password(code.strip().upper(), code_hash)


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


# The same scheme with auto_error off, for the one route that accepts EITHER a
# human bearer token or a device credential (POST /v1/fleet/devices/{id}/
# heartbeat). auto_error=True would reject a perfectly valid device request
# before the route ever saw its X-Device-Secret header.
_optional_bearer_scheme = HTTPBearer(auto_error=False)


async def _payload_from_credentials(
    credentials: HTTPAuthorizationCredentials,
) -> dict[str, Any]:
    """The decode/verify body shared by the required and optional bearer
    dependencies below, so both apply exactly the same rules -- an optional
    credential that skipped the revocation or token-type checks would be a hole,
    not a convenience."""
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
    return await _payload_from_credentials(credentials)


async def get_optional_token_payload(
    credentials: HTTPAuthorizationCredentials | None = Depends(_optional_bearer_scheme),
) -> dict[str, Any] | None:
    """[get_token_payload] for a route that may legitimately be called with no
    Authorization header at all. Absent header -> None; a header that IS present
    is validated exactly as strictly as the required dependency, so this weakens
    nothing -- it only makes "no credential" expressible instead of fatal."""
    if credentials is None:
        return None
    return await _payload_from_credentials(credentials)


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


def _tenant_from_payload(request: Request, payload: dict[str, Any]) -> str:
    """The tenant-resolution rule itself, shared by the required and optional
    dependencies below so there is exactly one definition of it."""
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
    return _tenant_from_payload(request, payload)


async def get_optional_tenant_id(
    request: Request,
    payload: dict[str, Any] | None = Depends(get_optional_token_payload),
) -> str | None:
    """[get_current_tenant_id] where "no credential was presented" is a valid
    answer rather than a 403. For routes that accept a non-human credential
    instead -- today only the device heartbeat, which a tablet with nobody
    logged into it must be able to call. A route using this MUST reject `None`
    itself unless it has another credential to fall back on; this dependency
    authorises nothing on its own."""
    if payload is None:
        return None
    return _tenant_from_payload(request, payload)


# Alias — some call sites read more naturally as "require_tenant_scope".
require_tenant_scope = get_current_tenant_id


# --- WebSocket authentication ------------------------------------------------
# Websocket routes cannot use the `Depends()` chain above: it is built on
# `Request`, and a websocket handshake gives Starlette a `WebSocket` instead.
# Every websocket route in this codebase therefore used to hand-roll its own
# decode + tenant-resolution block, and the four copies had drifted apart:
# NONE of them asserted the token TYPE, so a `mfa_pending` token — issued
# BEFORE the second factor is verified — opened a live socket, defeating MFA
# for every realtime surface, and a 14-day refresh token worked just as well.
# Two of the four (jobs, messages) also skipped the revocation check entirely,
# so a logged-out token still connected. There is now exactly one
# implementation, here, next to the HTTP rules it must stay identical to
# (`_payload_from_credentials` + `_tenant_from_payload`).


class WebSocketAuthError(Exception):
    """Raised by `authenticate_websocket_token` when a connection must be
    refused. Carries the HTTP-equivalent status (401 vs 403) so a caller that
    wants distinct websocket close codes can map it, and a short `reason`
    suitable for a close frame.

    Nothing is closed by the raiser — the calling route owns the close frame,
    because the four routes deliberately differ there: `/v1/fleet/live` uses
    the 4401/4403 application close codes its dashboard client already
    understands, while the other three use `WS_1008_POLICY_VIOLATION`.
    """

    def __init__(self, reason: str, status_code: int = status.HTTP_401_UNAUTHORIZED) -> None:
        super().__init__(reason)
        self.reason = reason
        self.status_code = status_code


class WebSocketAuth:
    """The result of a successful websocket authentication: the decoded JWT
    payload plus the tenant the connection is scoped to. Routes that need the
    subject or the role read them off `payload`, exactly as they did when each
    had its own auth block."""

    __slots__ = ("payload", "tenant_id")

    def __init__(self, payload: dict[str, Any], tenant_id: str) -> None:
        self.payload = payload
        self.tenant_id = tenant_id


def _websocket_token(websocket) -> str | None:
    """The token for a websocket connection, from either transport: a
    `?token=` query param (browsers cannot set custom headers on a websocket
    handshake) or an `Authorization: Bearer` header (non-browser clients)."""
    token = websocket.query_params.get("token")
    if not token:
        auth_header = websocket.headers.get("authorization")
        if auth_header and auth_header.lower().startswith("bearer "):
            token = auth_header.split(" ", 1)[1]
    return token or None


async def authenticate_websocket_token(websocket) -> WebSocketAuth:
    """THE websocket auth rule. Applies the same checks the HTTP bearer
    dependencies apply, in the same order:

    1. a token is present (query param or bearer header),
    2. its signature and expiry verify,
    3. its `type` is `access` — NOT `refresh`, NOT `mfa_pending`,
    4. its `jti` has not been revoked (logout, refresh rotation, MFA burn),
    5. a tenant resolves, honouring the platform-owner cross-tenant override
       via a `tenant_id` query param (the websocket equivalent of
       `_tenant_from_payload`'s `Request.query_params` lookup).

    Raises `WebSocketAuthError` on every failure; the caller sends the close
    frame. Returns a `WebSocketAuth` on success.
    """
    token = _websocket_token(websocket)
    if not token:
        raise WebSocketAuthError("Missing bearer token")

    try:
        payload = decode_token(token)
    except JWTError as exc:
        raise WebSocketAuthError("Invalid token") from exc

    if payload.get("type") != TOKEN_TYPE_ACCESS:
        # The MFA case is the dangerous one: POST /v1/auth/login hands out a
        # TOKEN_TYPE_MFA token to an mfa_enabled account BEFORE the TOTP step,
        # so accepting it here would let anyone holding only the password open
        # a live feed for its 5-minute lifetime.
        raise WebSocketAuthError("Not an access token")

    jti = payload.get("jti")
    if jti and await revocation_store.is_revoked(jti):
        raise WebSocketAuthError("Token revoked")

    token_tenant_id = payload.get("tenant_id")
    if payload.get("role") == "owner" and token_tenant_id == PLATFORM_TENANT_ID:
        override = websocket.query_params.get("tenant_id")
        return WebSocketAuth(payload, override or token_tenant_id)

    if not token_tenant_id:
        raise WebSocketAuthError("Token has no tenant scope", status.HTTP_403_FORBIDDEN)

    return WebSocketAuth(payload, token_tenant_id)


WS_REVOCATION_POLL_SECONDS = 2.0


async def revocation_aware_pump(
    websocket,
    queue,
    jti: str | None,
    *,
    poll_interval: float | None = None,
    close_code: int = 4401,
) -> None:
    """Relays items off `queue` to `websocket` via `send_json`, exactly like the
    four websocket routes' previous `while True: send_json(await queue.get())`
    loop — except it never blocks on `queue.get()` for longer than
    `poll_interval` seconds without rechecking whether `jti` has since been
    revoked (logout, refresh rotation, MFA-token burn, or D10's "sign out
    everywhere").

    Without this, `authenticate_websocket_token` only ever ran once, at the
    handshake: a session revoked five minutes into an hour-long dashboard
    connection (or a driver's job-offer feed) stayed live and kept receiving
    real tenant data for the rest of that connection's natural life, because
    nothing on the send-only path ever looked at the revocation store again.
    That is the same class of bug this project's earlier waves fixed for
    `mfa_pending`/refresh tokens at the handshake — this closes the same hole
    for the *lifetime* of the connection, not just its start.

    A blocked `queue.get()` is cancelled and retried on each timeout tick
    rather than raced via `asyncio.wait`, so a message that arrives exactly at
    a poll boundary is simply picked up on the next `get()` call one
    `poll_interval` later at worst — an acceptable latency for a revocation
    check, and far simpler than resuming a partially-consumed `wait`.
    """
    import asyncio

    interval = poll_interval if poll_interval is not None else WS_REVOCATION_POLL_SECONDS

    while True:
        if jti and await revocation_store.is_revoked(jti):
            await websocket.close(code=close_code, reason="Session revoked")
            return
        try:
            item = await asyncio.wait_for(queue.get(), timeout=interval)
        except TimeoutError:
            continue
        await websocket.send_json(item)


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
