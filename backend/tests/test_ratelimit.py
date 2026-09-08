"""Tests for rate limiting (app/core/ratelimit.py) and the tenant scoping /
role gate that landed alongside it.

The point of every test here is that a limit **actually trips** — that a real
HTTP request gets a real 429 — rather than that a decorator is present or a
constant has the right string in it. A limiter that is configured but inert is
the failure mode this workstream exists to prevent (backend audit §5: "Rate
limiting — None, anywhere").

Two mechanics worth understanding before reading:

1. **The limiter is off by default in the test environment** (see
   `app.core.ratelimit._default_enabled`), because a global 5-per-minute login
   cap would make hundreds of unrelated tests fail depending on execution
   order. The `rate_limiting` fixture below turns it ON for this module and
   resets every counter around each test, so these tests exercise the real
   thing. `test_limiter_is_enabled_outside_the_test_environment` asserts the
   off-switch cannot leak into a real deployment.

2. **Per-IP limits are keyed on the client address**, which httpx's
   `ASGITransport` lets us set. `_client_from_ip` gives each request a distinct
   source IP, which is what makes it possible to prove the per-`driver_code`
   limit trips *independently* of the per-IP one: 20 attempts from 20 different
   addresses never touch the 5/min/IP cap, yet still exhaust the
   20/hour/driver_code cap.
"""
from __future__ import annotations

import uuid

import pytest
from httpx import ASGITransport, AsyncClient

from app.core import ratelimit
from app.core.ratelimit import (
    DEVICE_REGISTER_PER_IP,
    DRIVER_LOGIN_PER_CODE,
    DRIVER_LOGIN_PER_IP,
    LOGIN_PER_IP,
    VERIFY_ADMIN_PIN_LOCKOUT,
    VERIFY_ADMIN_PIN_PER_USER,
)
from app.core.security import decode_token
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

_PASSWORD = "Driver-Pass1!"


@pytest.fixture(autouse=True)
def rate_limiting():
    """Turns the limiter ON for this module only, with a clean slate before and
    after every test — otherwise one test's exhausted window would 429 the
    next one, and the module's own results would depend on ordering."""
    ratelimit.reset_all()
    ratelimit.set_enabled(True)
    yield
    ratelimit.set_enabled(False)
    ratelimit.reset_all()


def _client_from_ip(app, ip: str) -> AsyncClient:
    """An httpx client whose requests appear to come from `ip`.

    slowapi keys per-IP limits on `request.client.host`, which `ASGITransport`
    takes from its `client` argument. Without this, every test request shares
    one address and the per-IP and per-key limits are indistinguishable.
    """
    return AsyncClient(
        transport=ASGITransport(app=app, client=(ip, 12345)),
        base_url="http://testserver",
    )


def _unique_email(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}@example.com"


async def _tenant_slug(session, tenant_id: str) -> str:
    from app.models.tenant import Tenant

    tenant = await session.get(Tenant, tenant_id)
    return tenant.slug


async def _make_driver(client, session, *, tenant_name: str):
    """Creates an admin + a driver in a fresh tenant, and returns everything the
    driver-login tests need: the tenant slug, the driver code, and the PIN."""
    headers = await auth_headers(client, session, role="admin", tenant_name=tenant_name)
    tenant_id = decode_token(headers["Authorization"].removeprefix("Bearer "))["tenant_id"]
    resp = await client.post(
        "/v1/users",
        json={
            "name": "Rate Limited Driver",
            "email": _unique_email("ratelimit-driver"),
            "password": _PASSWORD,
            "role": "driver",
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    return {
        "headers": headers,
        "tenant_id": tenant_id,
        "slug": await _tenant_slug(session, tenant_id),
        "driver_code": resp.json()["driver_code"],
    }


# --- the fallback itself ------------------------------------------------------


async def test_in_memory_fallback_is_the_active_backend_and_really_limits():
    """The dev/default path must LIMIT, not silently no-op.

    `tests/conftest.py` points REDIS_URL at an unreachable port on purpose, so
    this asserts the module took the in-memory branch — and then drives a limit
    to exhaustion through that backend to prove the fallback counts. A fallback
    that degraded to "allow everything" would be worse than no limiter, because
    it would look protected.
    """
    from limits.storage import MemoryStorage

    assert ratelimit.backend.using_redis is False
    assert isinstance(ratelimit.backend.storage, MemoryStorage)
    assert ratelimit.backend.storage_uri == "memory://"

    key = f"fallback-probe-{uuid.uuid4()}"
    # "3/minute": three calls succeed, the fourth must raise 429.
    for _ in range(3):
        ratelimit.enforce("3/minute", key)
    with pytest.raises(Exception) as exc:
        ratelimit.enforce("3/minute", key)
    assert getattr(exc.value, "status_code", None) == 429


async def test_limiter_is_enabled_outside_the_test_environment(monkeypatch):
    """The test-env off-switch must never become a production no-op."""
    from app.core.config import settings

    for env in ("production", "development", "staging"):
        monkeypatch.setattr(settings, "ENV", env)
        assert ratelimit._default_enabled() is True
    monkeypatch.setattr(settings, "ENV", "test")
    assert ratelimit._default_enabled() is False


async def test_peek_does_not_consume_but_register_failure_does():
    """The admin-PIN lockout depends on this asymmetry: checking whether a user
    is locked out must not itself push them further into the lockout."""
    key = f"peek-probe-{uuid.uuid4()}"
    for _ in range(50):
        assert ratelimit.peek_exhausted("2/minute", key) is False

    ratelimit.register_failure("2/minute", key)
    assert ratelimit.peek_exhausted("2/minute", key) is False
    ratelimit.register_failure("2/minute", key)
    assert ratelimit.peek_exhausted("2/minute", key) is True


# --- POST /v1/auth/login ------------------------------------------------------


async def test_login_per_ip_limit_trips(app):
    """LOGIN_PER_IP is 10/minute: the 11th attempt from one IP gets a 429.

    Deliberately uses wrong credentials — a limit that only counted successful
    logins would be useless against password guessing.
    """
    assert LOGIN_PER_IP == "10/minute"
    async with _client_from_ip(app, "10.99.0.1") as c:
        for i in range(10):
            resp = await c.post(
                "/v1/auth/login", json={"email": "nobody@example.com", "password": "nope"}
            )
            assert resp.status_code == 401, f"attempt {i} should still be allowed through"

        blocked = await c.post(
            "/v1/auth/login", json={"email": "nobody@example.com", "password": "nope"}
        )
    assert blocked.status_code == 429


async def test_login_limit_is_per_ip_not_global(app):
    """A second IP must be unaffected by the first IP's exhausted window,
    otherwise one noisy client locks every other operator out of the platform."""
    async with _client_from_ip(app, "10.99.0.2") as c:
        for _ in range(10):
            await c.post("/v1/auth/login", json={"email": "a@example.com", "password": "nope"})
        assert (
            await c.post("/v1/auth/login", json={"email": "a@example.com", "password": "nope"})
        ).status_code == 429

    async with _client_from_ip(app, "10.99.0.3") as other:
        resp = await other.post(
            "/v1/auth/login", json={"email": "a@example.com", "password": "nope"}
        )
    assert resp.status_code == 401


# --- POST /v1/auth/driver-login ----------------------------------------------


async def test_driver_login_per_ip_limit_trips(app, client, session):
    """DRIVER_LOGIN_PER_IP is 5/minute — the tight one, because the credential
    behind it is a 6-digit PIN."""
    assert DRIVER_LOGIN_PER_IP == "5/minute"
    ctx = await _make_driver(client, session, tenant_name="RL Driver IP Tenant")
    body = {"tenant_slug": ctx["slug"], "driver_code": ctx["driver_code"], "pin": "000000"}

    async with _client_from_ip(app, "10.99.1.1") as c:
        for i in range(5):
            resp = await c.post("/v1/auth/driver-login", json=body)
            assert resp.status_code == 401, f"attempt {i} should still be allowed through"
        blocked = await c.post("/v1/auth/driver-login", json=body)
    assert blocked.status_code == 429


async def test_driver_login_per_driver_code_limit_trips_independently_of_the_ip_limit(
    app, client, session
):
    """The distributed-attack case, and the reason BOTH limits exist.

    Every one of these 20 attempts comes from a DIFFERENT source address, so the
    5/minute/IP cap is never approached (each IP spends 1 of its 5). The
    21st attempt still gets a 429 — proving the per-`driver_code` counter is
    doing the work on its own. Without it, an attacker with 200,000 IPs walks
    the entire 6-digit PIN space untouched.
    """
    assert DRIVER_LOGIN_PER_CODE == "20/hour"
    ctx = await _make_driver(client, session, tenant_name="RL Driver Code Tenant")
    body = {"tenant_slug": ctx["slug"], "driver_code": ctx["driver_code"], "pin": "000000"}

    for i in range(20):
        async with _client_from_ip(app, f"10.99.2.{i + 1}") as c:
            resp = await c.post("/v1/auth/driver-login", json=body)
        assert resp.status_code == 401, f"attempt {i} from a fresh IP should not be IP-limited"

    async with _client_from_ip(app, "10.99.2.200") as c:
        blocked = await c.post("/v1/auth/driver-login", json=body)
    assert blocked.status_code == 429
    assert "driver code" in blocked.json()["detail"].lower()


async def test_driver_code_limit_key_includes_the_tenant(app, client, session):
    """The per-code counter is keyed on tenant+code, not code alone.

    Why it matters: `driver_code` is *currently* unique platform-wide at the DB
    level (see `test_driver_code_is_globally_unique_today` below), but that
    constraint is the wrong shape for a multi-tenant platform and is expected to
    become per-tenant. The moment it does, a counter keyed on the bare code
    would let tenant A lock out tenant B's identically-numbered driver — a
    cross-tenant denial of service. Keying on tenant+code today costs nothing
    and makes that change safe.

    Asserted at the `enforce` layer because the DB constraint makes the same
    code in two tenants unconstructible over HTTP right now.
    """
    a = await _make_driver(client, session, tenant_name="RL Code Tenant A")
    b = await _make_driver(client, session, tenant_name="RL Code Tenant B")
    shared_code = "SHARE1"

    for _ in range(20):
        ratelimit.enforce(DRIVER_LOGIN_PER_CODE, "driver-login-code", a["slug"], shared_code)
    with pytest.raises(Exception) as exc:
        ratelimit.enforce(DRIVER_LOGIN_PER_CODE, "driver-login-code", a["slug"], shared_code)
    assert getattr(exc.value, "status_code", None) == 429

    # Same code, different tenant: a fresh window, not a shared one.
    assert (
        ratelimit.peek_exhausted(DRIVER_LOGIN_PER_CODE, "driver-login-code", b["slug"], shared_code)
        is False
    )


async def test_driver_code_is_globally_unique_today(client, session):
    """Documents the constraint the tests above have to work around, so the next
    reader does not mistake it for an oversight.

    `users.driver_code` has a UNIQUE index with no tenant component, so two
    tenants cannot currently hold the same driver code at all. That is a
    *separate* defect from the one this workstream fixes: it means one tenant's
    choice of driver numbering silently constrains every other tenant's. It does
    NOT make the old global lookup safe — a globally-unique code still meant a
    single 6-digit-PIN credential space shared by the whole platform, which is
    exactly what the per-tenant scoping and the per-code limit address.
    """
    from sqlalchemy.exc import IntegrityError

    from app.models.user import User

    a = await _make_driver(client, session, tenant_name="Unique Code Tenant A")
    b = await _make_driver(client, session, tenant_name="Unique Code Tenant B")

    from sqlalchemy import select

    user_b = (
        await session.execute(
            select(User).where(
                User.tenant_id == b["tenant_id"], User.driver_code == b["driver_code"]
            )
        )
    ).scalar_one()
    user_b.driver_code = a["driver_code"]
    with pytest.raises(IntegrityError):
        await session.commit()
    await session.rollback()


# --- tenant scoping -----------------------------------------------------------


async def test_driver_login_never_authenticates_another_tenants_driver(client, session):
    """The core of the ADDENDUM fix: tenant A's slug must never authenticate
    tenant B's user.

    Two tenants, two drivers, the SAME PIN. Every combination is checked:
      * A's slug + A's code  -> A's user (and specifically NOT B's)
      * B's slug + B's code  -> B's user
      * A's slug + B's code  -> 401
      * B's slug + A's code  -> 401

    Before this change the lookup was `where(User.driver_code == ...)` with no
    tenant filter, so both cross-tenant cases above would have succeeded and
    handed back a token scoped to the *other* tenant.

    NOTE: the ideal version of this test gives both tenants the same
    `driver_code`. That is not constructible today — `users.driver_code` carries
    a platform-wide UNIQUE index (asserted in
    `test_driver_code_is_globally_unique_today`). The cross-slug 401s below are
    the strongest available proof that the tenant filter is load-bearing: with
    the old unfiltered query, A's slug + B's code returns B's user and passes
    credential verification.
    """
    ratelimit.set_enabled(False)  # this test is about scoping, not throttling
    a = await _make_driver(client, session, tenant_name="Scope Tenant A")
    b = await _make_driver(client, session, tenant_name="Scope Tenant B")

    from sqlalchemy import select

    from app.models.user import User

    async def _user_id(ctx) -> str:
        return (
            await session.execute(
                select(User.id).where(
                    User.tenant_id == ctx["tenant_id"], User.driver_code == ctx["driver_code"]
                )
            )
        ).scalar_one()

    id_a, id_b = await _user_id(a), await _user_id(b)
    assert id_a != id_b

    login_a = await client.post(
        "/v1/auth/driver-login",
        json={"tenant_slug": a["slug"], "driver_code": a["driver_code"], "pin": _PASSWORD},
    )
    assert login_a.status_code == 200
    assert login_a.json()["user"]["id"] == id_a
    assert login_a.json()["user"]["id"] != id_b
    assert login_a.json()["user"]["tenant_id"] == a["tenant_id"]

    login_b = await client.post(
        "/v1/auth/driver-login",
        json={"tenant_slug": b["slug"], "driver_code": b["driver_code"], "pin": _PASSWORD},
    )
    assert login_b.status_code == 200
    assert login_b.json()["user"]["id"] == id_b
    assert login_b.json()["user"]["tenant_id"] == b["tenant_id"]

    # The two cross-tenant combinations: valid code, valid PIN, WRONG tenant.
    # These are the requests that used to succeed.
    cross_ab = await client.post(
        "/v1/auth/driver-login",
        json={"tenant_slug": a["slug"], "driver_code": b["driver_code"], "pin": _PASSWORD},
    )
    assert cross_ab.status_code == 401

    cross_ba = await client.post(
        "/v1/auth/driver-login",
        json={"tenant_slug": b["slug"], "driver_code": a["driver_code"], "pin": _PASSWORD},
    )
    assert cross_ba.status_code == 401


async def test_every_tenant_gets_a_unique_slug(client, session):
    """Two tenants created with the same display name must not collide on the
    unique slug index — the `before_insert` listener appends a UUID fragment."""
    a = await _make_driver(client, session, tenant_name="Identical Name Ltd")
    b = await _make_driver(client, session, tenant_name="Identical Name Ltd")

    assert a["slug"] and b["slug"]
    assert a["slug"] != b["slug"]
    assert a["slug"].startswith("identical-name-ltd-")
    assert b["slug"].startswith("identical-name-ltd-")


async def test_slugify_never_returns_empty():
    from app.models.tenant import slugify_tenant_name

    assert slugify_tenant_name("City Cabs (NSW) Pty Ltd") == "city-cabs-nsw-pty-ltd"
    # A name with no ASCII residue would otherwise produce "", which would
    # match nothing and collide with the next such tenant.
    assert slugify_tenant_name("東京タクシー", fallback="abc-123") == "abc-123"
    assert slugify_tenant_name("---", fallback="fallback-slug") == "fallback-slug"


# --- POST /v1/fleet/devices/register -----------------------------------------


async def test_device_register_per_ip_limit_trips(app):
    """The pairing endpoint is unauthenticated by design (the pairing code IS
    the credential), which makes it exactly the sort of thing that must not be
    callable in a loop."""
    assert DEVICE_REGISTER_PER_IP == "5/minute"
    body = {"android_id": "rl-android", "pairing_code": "BADCODE1"}

    async with _client_from_ip(app, "10.99.4.1") as c:
        for i in range(5):
            resp = await c.post("/v1/fleet/devices/register", json=body)
            assert resp.status_code != 429, f"attempt {i} should still be allowed through"
        blocked = await c.post("/v1/fleet/devices/register", json=body)
    assert blocked.status_code == 429


# --- POST /v1/fleet/devices/{id}/verify-admin-pin -----------------------------


async def _admin_pin_fixture(client, session, *, role: str, tenant_name: str):
    owner = await auth_headers(client, session, role="owner", tenant_name=tenant_name)
    tenant_id = decode_token(owner["Authorization"].removeprefix("Bearer "))["tenant_id"]
    assert (
        await client.post(
            f"/v1/tenants/{tenant_id}/admin-pin", json={"pin": "913572"}, headers=owner
        )
    ).status_code == 200
    device = await client.post(
        "/v1/fleet/devices", json={"android_id": f"rl-{uuid.uuid4()}"}, headers=owner
    )
    assert device.status_code == 201
    caller = (
        owner
        if role == "owner"
        else await auth_headers(client, session, role=role, tenant_id=tenant_id)
    )
    return caller, device.json()["id"]


async def test_verify_admin_pin_refuses_a_driver_token(client, session):
    """The gate this route never had.

    A driver token is what the tablet holds, and this endpoint is a
    yes/no oracle for the tenant's admin PIN. Anything short of 403 here means
    any driver on the fleet can grind that PIN over the network.
    """
    driver_headers, device_id = await _admin_pin_fixture(
        client, session, role="driver", tenant_name="AdminPin Driver Tenant"
    )
    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin",
        json={"pin": "913572"},
        headers=driver_headers,
    )
    assert resp.status_code == 403
    # And the refusal must not leak whether the PIN was right.
    assert "913572" not in resp.text
    assert "valid" not in resp.json()


@pytest.mark.parametrize("role", ["dispatcher", "accountant"])
async def test_verify_admin_pin_refuses_other_non_admin_roles(client, session, role):
    headers, device_id = await _admin_pin_fixture(
        client, session, role=role, tenant_name=f"AdminPin {role} Tenant"
    )
    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin", json={"pin": "913572"}, headers=headers
    )
    assert resp.status_code == 403


async def test_verify_admin_pin_rate_limit_trips_per_user(client, session):
    """VERIFY_ADMIN_PIN_PER_USER is 5/minute, keyed on the authenticated USER
    rather than the IP — the threat here is a valid token, and a token moves
    between addresses freely."""
    assert VERIFY_ADMIN_PIN_PER_USER == "5/minute"
    headers, device_id = await _admin_pin_fixture(
        client, session, role="owner", tenant_name="AdminPin RL Tenant"
    )
    url = f"/v1/fleet/devices/{device_id}/verify-admin-pin"

    for i in range(5):
        resp = await client.post(url, json={"pin": "913572"}, headers=headers)
        assert resp.status_code == 200, f"attempt {i} should still be allowed through"
    blocked = await client.post(url, json={"pin": "913572"}, headers=headers)
    assert blocked.status_code == 429


async def test_verify_admin_pin_locks_out_after_ten_failures(client, session):
    """VERIFY_ADMIN_PIN_LOCKOUT: 10 *failed* attempts inside 15 minutes and the
    user is refused outright — even with the CORRECT PIN, which is the whole
    point of a lockout as distinct from a rate limit.

    The 5/minute rate limit is disabled around the failure loop so this test
    proves the lockout specifically, rather than accidentally re-testing the
    rate limit (which would trip first, at attempt 6).
    """
    assert VERIFY_ADMIN_PIN_LOCKOUT == "10/15minute"
    headers, device_id = await _admin_pin_fixture(
        client, session, role="owner", tenant_name="AdminPin Lockout Tenant"
    )
    url = f"/v1/fleet/devices/{device_id}/verify-admin-pin"
    user_id = decode_token(headers["Authorization"].removeprefix("Bearer "))["sub"]

    # Ten wrong PINs, fed straight into the lockout counter the route uses.
    for _ in range(10):
        ratelimit.register_failure(VERIFY_ADMIN_PIN_LOCKOUT, "admin-pin-lock", user_id)

    blocked = await client.post(url, json={"pin": "913572"}, headers=headers)
    assert blocked.status_code == 429
    assert "locked out" in blocked.json()["detail"].lower()
    assert blocked.headers["Retry-After"] == "900"


async def test_wrong_pins_through_the_route_really_advance_the_lockout(client, session):
    """The same lockout, driven end-to-end through HTTP rather than by poking
    the counter — nine wrong PINs leave the user working, the tenth locks them
    out. Nine, not ten, because the 5/minute rate limit would otherwise trip
    first; it is reset between bursts here, the lockout counter deliberately is
    not."""
    headers, device_id = await _admin_pin_fixture(
        client, session, role="owner", tenant_name="AdminPin E2E Lockout Tenant"
    )
    url = f"/v1/fleet/devices/{device_id}/verify-admin-pin"
    user_id = decode_token(headers["Authorization"].removeprefix("Bearer "))["sub"]

    seen_valid_false = 0
    for i in range(9):
        if i % 4 == 0:
            # Clear only the per-minute attempt counter, so this test measures
            # the lockout and not the rate limit.
            ratelimit.backend.storage.clear(
                ratelimit._item(VERIFY_ADMIN_PIN_PER_USER).key_for("admin-pin", user_id)
            )
        resp = await client.post(url, json={"pin": "000000"}, headers=headers)
        assert resp.status_code == 200, resp.text
        assert resp.json()["valid"] is False
        seen_valid_false += 1
    assert seen_valid_false == 9

    ratelimit.backend.storage.clear(
        ratelimit._item(VERIFY_ADMIN_PIN_PER_USER).key_for("admin-pin", user_id)
    )
    tenth = await client.post(url, json={"pin": "000000"}, headers=headers)
    assert tenth.status_code == 200
    assert tenth.json()["valid"] is False

    # Tenth failure recorded — now even the CORRECT PIN is refused.
    ratelimit.backend.storage.clear(
        ratelimit._item(VERIFY_ADMIN_PIN_PER_USER).key_for("admin-pin", user_id)
    )
    locked = await client.post(url, json={"pin": "913572"}, headers=headers)
    assert locked.status_code == 429


async def test_correct_pin_does_not_advance_the_lockout(client, session):
    """Only FAILURES count. An owner who verifies a correct PIN twenty times
    over a shift must never lock themselves out."""
    headers, device_id = await _admin_pin_fixture(
        client, session, role="owner", tenant_name="AdminPin No False Lock Tenant"
    )
    url = f"/v1/fleet/devices/{device_id}/verify-admin-pin"
    user_id = decode_token(headers["Authorization"].removeprefix("Bearer "))["sub"]

    for _ in range(20):
        ratelimit.backend.storage.clear(
            ratelimit._item(VERIFY_ADMIN_PIN_PER_USER).key_for("admin-pin", user_id)
        )
        resp = await client.post(url, json={"pin": "913572"}, headers=headers)
        assert resp.status_code == 200
        assert resp.json()["valid"] is True

    assert ratelimit.peek_exhausted(VERIFY_ADMIN_PIN_LOCKOUT, "admin-pin-lock", user_id) is False
