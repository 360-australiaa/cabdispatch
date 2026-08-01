"""Tests for the messages domain (`/v1/messages`).

NOTE: as of writing, this domain's router is not yet registered in app.main —
that happens in a later integration step that wires all domain routers
together (same as every other domain slice in this codebase pre-integration,
e.g. app/api/v1/duress.py originally). These tests are written correctly
against the endpoints as built and will pass once that registration lands;
run today they will 404.
"""
from __future__ import annotations

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import security
from tests.conftest import auth_headers


def _user_id_from_headers(headers: dict) -> str:
    """Pulls the `sub` (user id) claim back out of a bearer token minted by
    `auth_headers`, since that helper only returns the header dict."""
    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["sub"]


def _tenant_id_from_headers(headers: dict) -> str:
    """Pulls the `tenant_id` claim back out of a bearer token minted by
    `auth_headers`. Needed whenever a test puts a driver and a dispatcher in
    the same conversation — both callers must share one tenant, or the
    app's tenant-scoped message list correctly (and silently) excludes the
    other tenant's rows, since tenant_id filtering is the sole multi-tenancy
    isolation mechanism in this system (see app.core.security's module
    docstring)."""
    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["tenant_id"]


# --- send + list a thread ------------------------------------------------------


async def test_driver_sends_message_and_appears_in_own_thread(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    driver_id = _user_id_from_headers(driver_headers)

    resp = await client.post("/v1/messages", json={"body": "Running 10 min late"}, headers=driver_headers)

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_id"] == driver_id
    assert body["thread_id"] == driver_id
    assert body["sender_type"] == "driver"
    assert body["sender_user_id"] == driver_id
    assert body["body"] == "Running 10 min late"
    assert body["read_at"] is None

    list_resp = await client.get("/v1/messages", params={"driver_id": driver_id}, headers=driver_headers)
    assert list_resp.status_code == 200
    listed = list_resp.json()
    assert listed["total"] == 1
    assert listed["items"][0]["id"] == body["id"]


async def test_driver_supplied_driver_id_is_ignored_in_favor_of_own_id(
    client: AsyncClient, session: AsyncSession
):
    driver_headers = await auth_headers(client, session, role="driver")
    driver_id = _user_id_from_headers(driver_headers)

    resp = await client.post(
        "/v1/messages",
        json={"body": "trying to spoof another thread", "driver_id": "not-my-id"},
        headers=driver_headers,
    )

    assert resp.status_code == 201
    assert resp.json()["driver_id"] == driver_id


async def test_dispatch_send_requires_driver_id(client: AsyncClient, session: AsyncSession):
    dispatcher_headers = await auth_headers(client, session, role="dispatcher")

    resp = await client.post("/v1/messages", json={"body": "hello?"}, headers=dispatcher_headers)

    assert resp.status_code == 400


async def test_dispatch_sends_into_named_driver_thread(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    driver_id = _user_id_from_headers(driver_headers)
    dispatcher_headers = await auth_headers(client, session, role="dispatcher")

    resp = await client.post(
        "/v1/messages",
        json={"body": "Pickup moved to gate 3", "driver_id": driver_id},
        headers=dispatcher_headers,
    )

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_id"] == driver_id
    assert body["thread_id"] == driver_id
    assert body["sender_type"] == "dispatch"


async def test_thread_lists_oldest_first_across_both_senders(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    driver_id = _user_id_from_headers(driver_headers)
    driver_tenant_id = _tenant_id_from_headers(driver_headers)
    dispatcher_headers = await auth_headers(client, session, role="admin", tenant_id=driver_tenant_id)

    await client.post(
        "/v1/messages", json={"body": "msg 1 from dispatch", "driver_id": driver_id}, headers=dispatcher_headers
    )
    await client.post("/v1/messages", json={"body": "msg 2 from driver"}, headers=driver_headers)
    await client.post(
        "/v1/messages", json={"body": "msg 3 from dispatch", "driver_id": driver_id}, headers=dispatcher_headers
    )

    resp = await client.get("/v1/messages", params={"driver_id": driver_id}, headers=dispatcher_headers)
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert [m["body"] for m in items] == ["msg 1 from dispatch", "msg 2 from driver", "msg 3 from dispatch"]


async def test_list_thread_pagination(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    driver_id = _user_id_from_headers(driver_headers)

    for i in range(5):
        await client.post("/v1/messages", json={"body": f"msg {i}"}, headers=driver_headers)

    resp = await client.get(
        "/v1/messages", params={"driver_id": driver_id, "limit": 2, "skip": 1}, headers=driver_headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 5
    assert len(body["items"]) == 2
    assert body["skip"] == 1
    assert body["limit"] == 2


# --- mark read -------------------------------------------------------------------


async def test_mark_read_is_idempotent(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    send_resp = await client.post("/v1/messages", json={"body": "read me"}, headers=driver_headers)
    message_id = send_resp.json()["id"]

    first = await client.post(f"/v1/messages/{message_id}/read", headers=driver_headers)
    assert first.status_code == 200
    first_read_at = first.json()["read_at"]
    assert first_read_at is not None

    second = await client.post(f"/v1/messages/{message_id}/read", headers=driver_headers)
    assert second.status_code == 200
    assert second.json()["read_at"] == first_read_at


async def test_mark_read_unknown_message_404s(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    resp = await client.post("/v1/messages/00000000-0000-0000-0000-000000000000/read", headers=driver_headers)
    assert resp.status_code == 404


# --- tenant isolation --------------------------------------------------------------


async def test_tenant_isolation_on_thread_listing(client: AsyncClient, session: AsyncSession):
    tenant_a_driver = await auth_headers(client, session, role="driver", tenant_name="Messages Tenant A")
    driver_id = _user_id_from_headers(tenant_a_driver)
    await client.post("/v1/messages", json={"body": "tenant A only"}, headers=tenant_a_driver)

    tenant_b_admin = await auth_headers(client, session, role="admin", tenant_name="Messages Tenant B")
    resp = await client.get("/v1/messages", params={"driver_id": driver_id}, headers=tenant_b_admin)

    assert resp.status_code == 200
    assert resp.json()["total"] == 0


async def test_tenant_isolation_on_mark_read(client: AsyncClient, session: AsyncSession):
    tenant_a_driver = await auth_headers(client, session, role="driver", tenant_name="Messages Tenant C")
    send_resp = await client.post("/v1/messages", json={"body": "tenant C only"}, headers=tenant_a_driver)
    message_id = send_resp.json()["id"]

    tenant_b_admin = await auth_headers(client, session, role="admin", tenant_name="Messages Tenant D")
    resp = await client.post(f"/v1/messages/{message_id}/read", headers=tenant_b_admin)

    assert resp.status_code == 404


# --- live websocket relay ----------------------------------------------------------


def test_messages_broadcast_to_live_websocket_listeners(app):
    """Uses starlette's synchronous TestClient (rather than the async `client`
    fixture) because httpx's ASGITransport does not support websocket upgrade
    — same approach as app/api/v1/duress.py's equivalent test. Builds its own
    tenant/users via direct DB access through a throwaway event loop since the
    `session`/`auth_headers` fixtures are async-only."""
    import asyncio
    import uuid

    from fastapi.testclient import TestClient

    from app.core.database import AsyncSessionLocal
    from app.models import Tenant, User

    async def _setup():
        async with AsyncSessionLocal() as db:
            tenant = Tenant(name=f"WS Messages Tenant {uuid.uuid4()}", plan="standard")
            db.add(tenant)
            await db.commit()
            await db.refresh(tenant)

            driver = User(
                tenant_id=tenant.id,
                role="driver",
                name="Test Driver",
                email=f"{uuid.uuid4()}@example.com",
                pin_hash=security.hash_password("Test-Passw0rd!"),
                status="active",
            )
            dispatcher = User(
                tenant_id=tenant.id,
                role="dispatcher",
                name="Test Dispatcher",
                email=f"{uuid.uuid4()}@example.com",
                pin_hash=security.hash_password("Test-Passw0rd!"),
                status="active",
            )
            db.add_all([driver, dispatcher])
            await db.commit()
            await db.refresh(driver)
            await db.refresh(dispatcher)

            driver_token = security.create_access_token(user_id=driver.id, tenant_id=tenant.id, role=driver.role)
            dispatcher_token = security.create_access_token(
                user_id=dispatcher.id, tenant_id=tenant.id, role=dispatcher.role
            )
        return driver.id, driver_token, dispatcher_token

    driver_id, driver_token, dispatcher_token = asyncio.run(_setup())

    with TestClient(app) as test_client:
        with test_client.websocket_connect(f"/v1/messages/live?driver_id={driver_id}&token={driver_token}") as ws:
            send_resp = test_client.post(
                "/v1/messages",
                json={"body": "live-delivered", "driver_id": driver_id},
                headers={"Authorization": f"Bearer {dispatcher_token}"},
            )
            assert send_resp.status_code == 201, send_resp.text

            received = ws.receive_json()
            assert received["body"] == "live-delivered"
            assert received["driver_id"] == driver_id
            assert received["sender_type"] == "dispatch"


def test_websocket_rejects_missing_token(app):
    from fastapi.testclient import TestClient
    from fastapi.websockets import WebSocketDisconnect as FastAPIWebSocketDisconnect

    with TestClient(app) as test_client:
        try:
            with test_client.websocket_connect("/v1/messages/live?driver_id=some-driver"):
                pass
            raised = False
        except FastAPIWebSocketDisconnect:
            raised = True
        assert raised


def test_websocket_rejects_driver_subscribing_to_another_drivers_thread(app):
    import asyncio
    import uuid

    from fastapi.testclient import TestClient
    from fastapi.websockets import WebSocketDisconnect as FastAPIWebSocketDisconnect

    from app.core.database import AsyncSessionLocal
    from app.models import Tenant, User

    async def _setup():
        async with AsyncSessionLocal() as db:
            tenant = Tenant(name=f"WS Messages Reject Tenant {uuid.uuid4()}", plan="standard")
            db.add(tenant)
            await db.commit()
            await db.refresh(tenant)

            driver = User(
                tenant_id=tenant.id,
                role="driver",
                name="Test Driver",
                email=f"{uuid.uuid4()}@example.com",
                pin_hash=security.hash_password("Test-Passw0rd!"),
                status="active",
            )
            db.add(driver)
            await db.commit()
            await db.refresh(driver)

            token = security.create_access_token(user_id=driver.id, tenant_id=tenant.id, role=driver.role)
        return token

    driver_token = asyncio.run(_setup())

    with TestClient(app) as test_client:
        try:
            with test_client.websocket_connect(
                f"/v1/messages/live?driver_id=some-other-driver&token={driver_token}"
            ):
                pass
            raised = False
        except FastAPIWebSocketDisconnect:
            raised = True
        assert raised
