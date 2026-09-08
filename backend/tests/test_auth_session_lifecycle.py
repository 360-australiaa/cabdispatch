"""Session lifecycle: logout actually revokes, refresh actually rotates, and a
suspended tenant actually stops working.

Backend audit §5 recorded three findings this file pins down, each of which was
a *silent* failure — the endpoint returned success and did nothing:

  * `POST /v1/auth/logout` was a documented no-op. Its own docstring said it
    "revokes nothing server-side". Both clients call it on sign-out, so a
    stolen access token stayed live for its full 30 minutes and a stolen
    refresh token for 14 days after the user believed they had logged out.
  * `POST /v1/auth/refresh` issued a new pair without revoking the presented
    refresh jti, so a leaked refresh token was a 14-day skeleton key that no
    amount of legitimate refreshing invalidated.
  * Login checked `user.status` but never `Tenant.status`, so suspending an
    operator from the platform console changed nothing for its users.

Every assertion here is on an HTTP status from a real request through the app —
"the token no longer works" rather than "the store contains an entry".
"""
from __future__ import annotations

import uuid

import pytest

from app.core import security
from app.models import Tenant, User
from app.models.tenant import TENANT_STATUS_SUSPENDED

pytestmark = pytest.mark.asyncio

_PASSWORD = "Test-Passw0rd!"


async def _tenant_and_user(session, *, tenant_status: str = "active", role: str = "admin"):
    tenant = Tenant(name=f"Session {uuid.uuid4().hex[:8]}", plan="standard", status=tenant_status)
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    user = User(
        tenant_id=tenant.id,
        role=role,
        name="Session User",
        email=f"{uuid.uuid4()}@example.com",
        pin_hash=security.hash_password(_PASSWORD),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return tenant, user


async def _login(client, user) -> dict:
    resp = await client.post("/v1/auth/login", json={"email": user.email, "password": _PASSWORD})
    assert resp.status_code == 200, resp.text
    return resp.json()


# --- logout -------------------------------------------------------------------


async def test_logout_revokes_the_access_token(client, session):
    """The headline fix: after logout, the very same bearer token that worked a
    moment ago is refused by an ordinary protected endpoint."""
    _, user = await _tenant_and_user(session)
    tokens = await _login(client, user)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    before = await client.get("/v1/auth/me", headers=headers)
    assert before.status_code == 200, before.text

    logout = await client.post("/v1/auth/logout", headers=headers)
    assert logout.status_code == 204, logout.text

    after = await client.get("/v1/auth/me", headers=headers)
    assert after.status_code == 401, after.text


async def test_logout_revokes_the_refresh_token_when_it_is_supplied(client, session):
    """A client that hands over its refresh token on logout gets the whole
    session killed, not just the 30-minute half of it."""
    _, user = await _tenant_and_user(session)
    tokens = await _login(client, user)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    logout = await client.post(
        "/v1/auth/logout", headers=headers, json={"refresh_token": tokens["refresh_token"]}
    )
    assert logout.status_code == 204, logout.text

    reused = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert reused.status_code == 401, reused.text


async def test_logout_without_a_body_still_works(client, session):
    """The body is optional — an older client that posts nothing must not get a
    422 and be left believing it failed to log out."""
    _, user = await _tenant_and_user(session)
    tokens = await _login(client, user)
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    logout = await client.post("/v1/auth/logout", headers=headers)
    assert logout.status_code == 204, logout.text


async def test_logout_ignores_another_users_refresh_token(client, session):
    """A caller may only revoke their own refresh token. Passing somebody
    else's must not log that person out — and must not fail either."""
    _, victim = await _tenant_and_user(session)
    _, attacker = await _tenant_and_user(session)
    victim_tokens = await _login(client, victim)
    attacker_tokens = await _login(client, attacker)

    logout = await client.post(
        "/v1/auth/logout",
        headers={"Authorization": f"Bearer {attacker_tokens['access_token']}"},
        json={"refresh_token": victim_tokens["refresh_token"]},
    )
    assert logout.status_code == 204, logout.text

    # The victim's session is untouched.
    still_good = await client.post(
        "/v1/auth/refresh", json={"refresh_token": victim_tokens["refresh_token"]}
    )
    assert still_good.status_code == 200, still_good.text


async def test_logout_tolerates_a_garbage_refresh_token(client, session):
    """Logout must never fail in a way that leaves a client thinking it is
    still signed in — a malformed refresh token is ignored, not fatal."""
    _, user = await _tenant_and_user(session)
    tokens = await _login(client, user)

    logout = await client.post(
        "/v1/auth/logout",
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
        json={"refresh_token": "not-a-jwt"},
    )
    assert logout.status_code == 204, logout.text


# --- refresh rotation ---------------------------------------------------------


async def test_refresh_rotates_and_the_old_token_stops_working(client, session):
    """Single-use refresh tokens: the presented one is burnt as the new pair is
    issued, so replaying it — which used to work for the full 14 days — 401s."""
    _, user = await _tenant_and_user(session)
    tokens = await _login(client, user)

    first = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert first.status_code == 200, first.text

    replay = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert replay.status_code == 401, replay.text


async def test_the_rotated_refresh_token_is_usable_exactly_once(client, session):
    """The replacement token works (rotation is not a one-way trip that breaks
    the client) and is itself single-use."""
    _, user = await _tenant_and_user(session)
    tokens = await _login(client, user)

    first = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert first.status_code == 200, first.text
    rotated = first.json()["refresh_token"]

    second = await client.post("/v1/auth/refresh", json={"refresh_token": rotated})
    assert second.status_code == 200, second.text

    replay = await client.post("/v1/auth/refresh", json={"refresh_token": rotated})
    assert replay.status_code == 401, replay.text


async def test_the_access_token_from_a_refresh_is_a_working_credential(client, session):
    """Guards against a rotation bug that revoked more than it should — the
    freshly-minted access token must still authenticate."""
    _, user = await _tenant_and_user(session)
    tokens = await _login(client, user)

    refreshed = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert refreshed.status_code == 200, refreshed.text

    me = await client.get(
        "/v1/auth/me", headers={"Authorization": f"Bearer {refreshed.json()['access_token']}"}
    )
    assert me.status_code == 200, me.text


# --- suspended tenants --------------------------------------------------------


async def test_login_is_refused_for_a_suspended_tenant(client, session):
    _, user = await _tenant_and_user(session, tenant_status=TENANT_STATUS_SUSPENDED)

    resp = await client.post("/v1/auth/login", json={"email": user.email, "password": _PASSWORD})
    assert resp.status_code == 403, resp.text


async def test_driver_login_is_refused_for_a_suspended_tenant(client, session):
    tenant, user = await _tenant_and_user(session, tenant_status=TENANT_STATUS_SUSPENDED, role="driver")
    user.driver_code = uuid.uuid4().hex[:8]
    session.add(user)
    await session.commit()

    resp = await client.post(
        "/v1/auth/driver-login",
        json={"tenant_slug": tenant.slug, "driver_code": user.driver_code, "pin": _PASSWORD},
    )
    assert resp.status_code == 403, resp.text


async def test_refresh_is_refused_once_the_tenant_is_suspended(client, session):
    """The case that made suspension cosmetic in practice: a user already
    holding tokens when the tenant is suspended could refresh forever."""
    tenant, user = await _tenant_and_user(session)
    tokens = await _login(client, user)

    tenant.status = TENANT_STATUS_SUSPENDED
    session.add(tenant)
    await session.commit()

    resp = await client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert resp.status_code == 403, resp.text
