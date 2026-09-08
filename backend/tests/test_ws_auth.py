"""Websocket authentication — the one rule, applied by all four WS routes.

Backend audit §5 ("Token lifetimes & session handling") found that all four
websocket handlers decoded the JWT and then never asserted its `type`. Two of
them (`jobs`, `messages`) did not consult the revocation store either. The
practical consequences, each of which this file pins down as a test:

  * a **`mfa_pending`** token — issued by POST /v1/auth/login BEFORE the TOTP
    second factor is verified — opened a live socket. That defeats MFA
    entirely for the realtime surfaces: password alone was enough.
  * a **refresh** token, valid for 14 days, worked as a live-socket credential
    even though it is never supposed to authenticate anything but
    POST /v1/auth/refresh.
  * a **revoked** token (logout, refresh rotation, a burnt MFA jti) still
    connected on `jobs` and `messages`.

Every test below asserts the connection is REFUSED — i.e. entering the
`websocket_connect` context manager raises `WebSocketDisconnect` — rather than
merely that "an error appears somewhere". A handler that accepted the socket
and then failed would pass a weaker assertion but still be a live, authorised
feed for an unauthorised token.

The four routes are covered by one parametrised matrix (4 routes × 3 rejected
token kinds = 12 cases) plus a positive control per route, so a route that
rejects EVERYTHING — which would also pass the twelve — is caught.
"""
from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.core import security
from app.models import Tenant, User

pytestmark = pytest.mark.asyncio


async def _tenant_and_admin(session) -> tuple[str, str]:
    """A tenant plus an `admin` user in it. Admin is used for every route
    because it is the only role accepted by all four: `duress` requires a
    dispatch-side role, and `messages`/`jobs` accept a dispatcher fine."""
    tenant = Tenant(name=f"WS Auth {uuid.uuid4().hex[:8]}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    user = User(
        tenant_id=tenant.id,
        role="admin",
        name="WS Auth Admin",
        email=f"{uuid.uuid4()}@example.com",
        pin_hash=security.hash_password("Test-Passw0rd!"),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)

    return tenant.id, user.id


def _routes(user_id: str) -> dict[str, str]:
    """The four websocket routes, as URL templates with a `{token}` slot.

    `duress` uses a random event id on purpose: authentication runs BEFORE the
    event lookup, so a rejected token must never get far enough for the id to
    matter — and if some future refactor reordered them, the positive-control
    test for duress (which uses a real event) would catch it.
    """
    return {
        "fleet": "/v1/fleet/live?token={token}",
        "duress": f"/v1/duress/{uuid.uuid4()}/live?token={{token}}",
        "jobs": "/v1/jobs/live?token={token}",
        "messages": f"/v1/messages/live?driver_id={user_id}&token={{token}}",
    }


ROUTE_NAMES = ["fleet", "duress", "jobs", "messages"]
BAD_TOKEN_KINDS = ["refresh", "mfa_pending", "revoked"]


async def _make_bad_token(kind: str, *, user_id: str, tenant_id: str) -> str:
    if kind == "refresh":
        return security.create_refresh_token(user_id=user_id, tenant_id=tenant_id, role="admin")
    if kind == "mfa_pending":
        return security.create_mfa_pending_token(user_id=user_id, tenant_id=tenant_id, role="admin")
    if kind == "revoked":
        # A perfectly valid, unexpired ACCESS token whose jti has been revoked
        # — exactly the state POST /v1/auth/logout now leaves a token in.
        token = security.create_access_token(user_id=user_id, tenant_id=tenant_id, role="admin")
        await security.revocation_store.revoke(security.decode_token(token)["jti"], 300)
        return token
    raise AssertionError(f"unknown token kind {kind}")


@pytest.mark.parametrize("route_name", ROUTE_NAMES)
@pytest.mark.parametrize("kind", BAD_TOKEN_KINDS)
async def test_websocket_refuses_non_access_and_revoked_tokens(app, session, route_name, kind):
    """The 12-case matrix. Each case must be REFUSED at the handshake."""
    tenant_id, user_id = await _tenant_and_admin(session)
    token = await _make_bad_token(kind, user_id=user_id, tenant_id=tenant_id)
    url = _routes(user_id)[route_name].format(token=token)

    # Reaching the body at all means the server accepted the handshake for a
    # token it must not have accepted.
    with TestClient(app) as tc, pytest.raises(WebSocketDisconnect), tc.websocket_connect(url):
        pytest.fail(f"{route_name} accepted a {kind} token")


@pytest.mark.parametrize("route_name", ["fleet", "jobs", "messages"])
async def test_websocket_accepts_a_real_access_token(app, session, route_name):
    """Positive control for three of the four routes: the same matrix above
    would pass trivially if a route rejected every token, so prove a genuine
    access token still connects.

    `duress` is excluded only because its route additionally requires the event
    to exist; that path is already covered end-to-end by
    `tests/test_duress.py::test_websocket_broadcasts_gps_points`, which
    connects with a real access token and receives a real GPS point.
    """
    tenant_id, user_id = await _tenant_and_admin(session)
    token = security.create_access_token(user_id=user_id, tenant_id=tenant_id, role="admin")
    url = _routes(user_id)[route_name].format(token=token)

    with TestClient(app) as tc, tc.websocket_connect(url):
        pass  # a completed handshake is the whole assertion


async def test_websocket_refuses_a_token_with_no_tenant_scope(app, session):
    """The shared rule also refuses a token carrying no tenant at all — the
    websocket equivalent of `get_current_tenant_id`'s 403. Previously `jobs`
    and `messages` closed on this themselves; now it comes from one place, so
    it is worth pinning that the behaviour survived the consolidation."""
    token = security.create_access_token(user_id=str(uuid.uuid4()), tenant_id=None, role="admin")

    url = f"/v1/fleet/live?token={token}"
    with TestClient(app) as tc, pytest.raises(WebSocketDisconnect), tc.websocket_connect(url):
        pytest.fail("fleet accepted a token with no tenant scope")
