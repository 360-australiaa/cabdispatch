"""Rate limiting — Redis-backed with an in-memory fallback.

Why this module exists
----------------------
Until this module landed there was **no rate limiting anywhere** in this
backend (backend audit §5, "Rate limiting"). That is a real, exploited-shaped
hole rather than a theoretical one: `POST /v1/auth/driver-login` authenticates
a driver with a **6-digit numeric PIN**, and a working credential for it was
compiled into a publicly distributed APK (audit §5 ADDENDUM). A 6-digit PIN
space is 10^6; unthrottled, that is minutes of work.

Design: one mechanism, one fallback
-----------------------------------
There are two *shapes* of limit we need, and it would be easy to end up with
two different implementations (and therefore two different failure modes):

1. **Per-IP limits on a whole route** — "login 10/min/IP". These are naturally
   expressed as `slowapi` decorators, because the key (the client IP) is
   derivable from the `Request` alone.
2. **Limits keyed on something inside the request body or on the authenticated
   user** — "driver-login 20/hour/**driver_code**", "verify-admin-pin
   5/min/**user**". `slowapi`'s `key_func` is synchronous and only receives the
   `Request`, so it cannot see a parsed Pydantic body. These have to be checked
   imperatively from inside the endpoint.

Both shapes are served here by the **same** `limits` storage backend and the
same fixed-window strategy, so they share one connection, one fallback
decision, and one observable behaviour. `slowapi` is handed the storage URI
that this module already resolved and proved reachable.

The Redis-with-in-memory-fallback behaviour deliberately mirrors
`app.core.security._RevocationStore`: try Redis if `REDIS_URL` is set, and if
it is unreachable (connection refused, timeout, whatever) fall back to
per-process memory for the lifetime of the process, log exactly ONE warning,
and never crash the app. The dev environment and the test suite do not run
Redis (`tests/conftest.py` deliberately points `REDIS_URL` at an unreachable
port), so the in-memory path is the *default* path, not an exotic one — and it
must really limit, not silently no-op. `tests/test_ratelimit.py` asserts
exactly that.

The in-memory fallback carries the same caveat as the revocation store: it is
per-process, so an N-worker deploy effectively multiplies every limit by N.
That is a deployment concern (run Redis in production), not a reason to have
no limiter in development.
"""
from __future__ import annotations

import logging
import time

from fastapi import HTTPException, Request, status
from limits import RateLimitItem, parse
from limits.storage import MemoryStorage, storage_from_string
from limits.strategies import FixedWindowRateLimiter
from slowapi import Limiter
from slowapi.errors import RateLimitExceeded
from slowapi.util import get_remote_address

from app.core.config import settings

logger = logging.getLogger(__name__)


# --- the limits themselves ---------------------------------------------------
# Every value here comes from the program plan's B4 workstream. They are module
# constants rather than inline strings so the tests can assert against the same
# source of truth the routes use (a test that hardcodes "5/minute" would still
# pass if the route quietly became 500/minute).

LOGIN_PER_IP = "10/minute"
DRIVER_LOGIN_PER_IP = "5/minute"
DRIVER_LOGIN_PER_CODE = "20/hour"
MFA_LOGIN_PER_IP = "10/minute"
DEVICE_REGISTER_PER_IP = "5/minute"
VERIFY_ADMIN_PIN_PER_USER = "5/minute"
# Lockout: after this many *failed* PIN attempts inside the window, the user is
# refused outright for the remainder of the window (15 minutes), regardless of
# the 5/minute attempt rate above. The two are independent: the rate limit
# stops a fast burst, the lockout stops a slow grind.
VERIFY_ADMIN_PIN_LOCKOUT = "10/15minute"


def _default_enabled() -> bool:
    """Rate limiting is ON everywhere except the test environment.

    Reason: the limits are deliberately tight (5 login attempts/minute/IP), and
    the whole test suite shares one ASGI transport, one client address, and one
    process — so a global limiter would make hundreds of unrelated tests fail
    depending on execution order, which is exactly the kind of flakiness that
    gets a limiter deleted. `tests/test_ratelimit.py` turns it back ON
    explicitly (see the `rate_limiting` fixture there) and exercises every
    limit for real against the in-memory backend.

    This must never become a silent production no-op, so it is keyed on the
    literal test environment only, and `tests/test_ratelimit.py` asserts that
    every other value of ENV yields True.
    """
    return settings.ENV != "test"


class _RateLimitBackend:
    """Resolves a `limits` storage backend once, with the Redis-then-memory
    fallback described in the module docstring."""

    def __init__(self) -> None:
        self._warned = False
        self.storage_uri = "memory://"
        self.storage = MemoryStorage()
        self.using_redis = False

        if settings.REDIS_URL:
            try:
                candidate = storage_from_string(
                    settings.REDIS_URL, connection_timeout=0.5, socket_timeout=0.5
                )
                # `check()` actually round-trips to the server (PING). Without
                # it, an unreachable Redis is only discovered on the first
                # limited request — i.e. as a 500 on someone's login.
                if candidate.check():
                    self.storage = candidate
                    self.storage_uri = settings.REDIS_URL
                    self.using_redis = True
                else:
                    self._warn_fallback_once()
            except Exception:
                self._warn_fallback_once()

        self.strategy = FixedWindowRateLimiter(self.storage)

    def _warn_fallback_once(self) -> None:
        if not self._warned:
            logger.warning(
                "Redis unreachable at %s — falling back to in-memory rate limiting "
                "(per-process; an N-worker deploy multiplies every limit by N).",
                settings.REDIS_URL,
            )
            self._warned = True

    def reset(self) -> None:
        """Drops all counters. Only used by the test suite between cases; there
        is no production caller and no route that exposes it."""
        self.storage.reset()


backend = _RateLimitBackend()

# `slowapi` builds its own storage instance, but from the URI this module has
# already resolved *and proved reachable* — so the decorator-based per-IP limits
# and the imperative body-keyed limits below always agree about whether Redis is
# in play. `enabled` is what makes the whole thing inert in the test env.
limiter = Limiter(
    key_func=get_remote_address,
    storage_uri=backend.storage_uri,
    enabled=_default_enabled(),
)


def reset_all() -> None:
    """Drops every counter, decorator-based and imperative alike.

    slowapi constructs its own storage instance from the URI above, so under the
    in-memory backend there are two distinct `MemoryStorage` objects to clear —
    hence this helper rather than `backend.reset()` alone. Test-only: no
    production caller, and no route exposes it.
    """
    backend.reset()
    slowapi_storage = getattr(limiter, "_storage", None)
    if slowapi_storage is not None:
        slowapi_storage.reset()


def set_enabled(enabled: bool) -> None:
    """Turns every limit in this module on or off together — decorator-based and
    imperative alike. Used by `tests/test_ratelimit.py` to switch the limiter ON
    for the tests that actually exercise it."""
    limiter.enabled = enabled


def is_enabled() -> bool:
    return limiter.enabled


def _item(spec: str) -> RateLimitItem:
    return parse(spec)


def _too_many(detail: str, reset_in: int) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_429_TOO_MANY_REQUESTS,
        detail=detail,
        headers={"Retry-After": str(max(reset_in, 1))},
    )


def enforce(spec: str, *identifiers: str, detail: str = "Too many requests") -> None:
    """Consumes one unit of `spec` against the composite key `identifiers`, and
    raises 429 if that exhausts the window.

    This is the imperative counterpart to the `@limiter.limit(...)` decorator,
    for the limits whose key is not derivable from the `Request` alone —
    `driver_code` (which lives in the parsed request body) and the authenticated
    user id. It shares `backend`'s storage and fallback, so it behaves
    identically to the decorators.
    """
    if not limiter.enabled:
        return
    item = _item(spec)
    if not backend.strategy.hit(item, *identifiers):
        reset_at, _ = backend.strategy.get_window_stats(item, *identifiers)
        raise _too_many(detail, int(reset_at - time.time()))


def peek_exhausted(spec: str, *identifiers: str) -> bool:
    """True if `spec` is already exhausted for this key, WITHOUT consuming a
    unit. Used for the verify-admin-pin lockout, where the counter must only be
    advanced by an actual *failed* PIN attempt — checking must not itself push a
    locked-out user deeper into the lockout."""
    if not limiter.enabled:
        return False
    return not backend.strategy.test(_item(spec), *identifiers)


def register_failure(spec: str, *identifiers: str) -> None:
    """Records one failed attempt against a lockout counter. Deliberately
    separate from `enforce` so the caller decides what counts as a failure."""
    if not limiter.enabled:
        return
    backend.strategy.hit(_item(spec), *identifiers)


def client_ip(request: Request) -> str:
    """The key the decorators use, exposed so imperative composite keys (e.g.
    per-IP *and* per-driver-code on the same request) can reuse it."""
    return get_remote_address(request) or "unknown"


__all__ = [
    "DEVICE_REGISTER_PER_IP",
    "DRIVER_LOGIN_PER_CODE",
    "DRIVER_LOGIN_PER_IP",
    "LOGIN_PER_IP",
    "MFA_LOGIN_PER_IP",
    "VERIFY_ADMIN_PIN_LOCKOUT",
    "VERIFY_ADMIN_PIN_PER_USER",
    "RateLimitExceeded",
    "backend",
    "client_ip",
    "enforce",
    "is_enabled",
    "limiter",
    "peek_exhausted",
    "register_failure",
    "reset_all",
    "set_enabled",
]
