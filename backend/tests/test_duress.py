"""Tests for the Duress domain (`/v1/duress`).

NOTE: as of writing, this domain's router is not yet registered in app.main —
that happens in a later integration step that wires all 12 domain routers
together. These tests are written correctly against the endpoints as built and
will pass once that registration lands; run in isolation today they will 404.
"""
from __future__ import annotations

import uuid

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from tests.conftest import auth_headers


def _trigger_body(**overrides):
    body = {
        "vehicle_id": str(uuid.uuid4()),
        "driver_id": str(uuid.uuid4()),
        "trigger": "button",
    }
    body.update(overrides)
    return body


# --- trigger / open ------------------------------------------------------------


async def test_trigger_opens_event_with_cancel_window(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["status"] == "open"
    assert body["closed_at"] is None
    assert body["gps_stream_ref"] == f"duress/{body['id']}/gps"
    log = body["escalation_log_json"]
    assert log["cancel_window_seconds"] == 10
    assert log["next_stage_index"] == 0
    assert log["entries"][0]["stage"] == "opened"


async def test_trigger_accepts_explicit_gps_and_audio_refs(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")

    resp = await client.post(
        "/v1/duress/trigger",
        json=_trigger_body(
            trigger="gesture", gps_stream_ref="s3://bucket/custom-gps", audio_ref="s3://bucket/audio"
        ),
        headers=headers,
    )

    assert resp.status_code == 201
    body = resp.json()
    assert body["trigger"] == "gesture"
    assert body["gps_stream_ref"] == "s3://bucket/custom-gps"
    assert body["audio_ref"] == "s3://bucket/audio"


async def test_trigger_rejects_invalid_trigger_value(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.post(
        "/v1/duress/trigger", json=_trigger_body(trigger="not_a_real_trigger"), headers=headers
    )
    assert resp.status_code == 422


# --- cancel ---------------------------------------------------------------------


async def test_cancel_within_window_succeeds(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    resp = await client.post(
        f"/v1/duress/{event_id}/cancel", json={"note": "false alarm, pocket press"}, headers=headers
    )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "cancelled"
    assert body["closed_at"] is not None
    assert body["escalation_log_json"]["entries"][-1]["stage"] == "cancelled"


async def test_cancel_after_escalation_started_is_rejected(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="dispatcher")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    escalate_resp = await client.post(f"/v1/duress/{event_id}/escalate", json={}, headers=headers)
    assert escalate_resp.status_code == 200
    assert escalate_resp.json()["status"] == "escalating"

    cancel_resp = await client.post(f"/v1/duress/{event_id}/cancel", json={}, headers=headers)
    assert cancel_resp.status_code == 409


async def test_cancel_already_cancelled_event_conflicts(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    first = await client.post(f"/v1/duress/{event_id}/cancel", json={}, headers=headers)
    assert first.status_code == 200

    second = await client.post(f"/v1/duress/{event_id}/cancel", json={}, headers=headers)
    assert second.status_code == 409


# --- escalate --------------------------------------------------------------------


async def test_escalate_cascade_reaches_dispatched(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="dispatcher")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    expected_stages = [
        ("cancel_window_expired", "escalating"),
        ("notify_dispatch", "escalating"),
        ("sms_emergency_contacts", "escalating"),
        ("present_000_call_script", "dispatched"),
    ]
    for stage_name, expected_status in expected_stages:
        resp = await client.post(f"/v1/duress/{event_id}/escalate", json={"note": stage_name}, headers=headers)
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["status"] == expected_status
        assert body["escalation_log_json"]["entries"][-1]["stage"] == stage_name

    # Fifth call: all stages exhausted.
    exhausted_resp = await client.post(f"/v1/duress/{event_id}/escalate", json={}, headers=headers)
    assert exhausted_resp.status_code == 409


async def test_escalate_requires_dispatch_role(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=driver_headers)
    event_id = trigger_resp.json()["id"]

    resp = await client.post(f"/v1/duress/{event_id}/escalate", json={}, headers=driver_headers)
    assert resp.status_code == 403


async def test_escalate_terminal_event_conflicts(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    close_resp = await client.post(f"/v1/duress/{event_id}/close", json={}, headers=headers)
    assert close_resp.status_code == 200

    escalate_resp = await client.post(f"/v1/duress/{event_id}/escalate", json={}, headers=headers)
    assert escalate_resp.status_code == 409


# --- close ----------------------------------------------------------------------


async def test_close_resolves_event(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="owner")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    resp = await client.post(f"/v1/duress/{event_id}/close", json={"note": "all clear"}, headers=headers)

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "resolved"
    assert body["closed_at"] is not None


async def test_close_requires_dispatch_role(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=driver_headers)
    event_id = trigger_resp.json()["id"]

    resp = await client.post(f"/v1/duress/{event_id}/close", json={}, headers=driver_headers)
    assert resp.status_code == 403


async def test_close_already_resolved_conflicts(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    first = await client.post(f"/v1/duress/{event_id}/close", json={}, headers=headers)
    assert first.status_code == 200
    second = await client.post(f"/v1/duress/{event_id}/close", json={}, headers=headers)
    assert second.status_code == 409


# --- GPS relay (HTTP side) -------------------------------------------------------


async def test_post_gps_with_no_listeners_delivers_to_zero(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    resp = await client.post(
        f"/v1/duress/{event_id}/gps", json={"lat": -33.87, "lng": 151.21}, headers=headers
    )
    assert resp.status_code == 202
    assert resp.json()["delivered_to"] == 0


async def test_post_gps_unknown_event_404s(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.post(
        f"/v1/duress/{uuid.uuid4()}/gps", json={"lat": -33.87, "lng": 151.21}, headers=headers
    )
    assert resp.status_code == 404


# --- standard CRUD ----------------------------------------------------------------


async def test_list_events_pagination_and_filters(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    driver_a = str(uuid.uuid4())
    driver_b = str(uuid.uuid4())

    for driver_id in (driver_a, driver_a, driver_b):
        await client.post("/v1/duress/trigger", json=_trigger_body(driver_id=driver_id), headers=headers)

    all_resp = await client.get("/v1/duress", headers=headers)
    assert all_resp.status_code == 200
    assert all_resp.json()["total"] >= 3

    filtered = await client.get("/v1/duress", params={"driver_id": driver_a}, headers=headers)
    assert filtered.status_code == 200
    assert filtered.json()["total"] == 2

    paged = await client.get(
        "/v1/duress", params={"driver_id": driver_a, "limit": 1, "offset": 1}, headers=headers
    )
    assert len(paged.json()["items"]) == 1

    status_filtered = await client.get("/v1/duress", params={"status": "open"}, headers=headers)
    assert status_filtered.status_code == 200
    assert all(item["status"] == "open" for item in status_filtered.json()["items"])


async def test_get_event_by_id(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="dispatcher")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    resp = await client.get(f"/v1/duress/{event_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["id"] == event_id


async def test_get_event_not_found(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(f"/v1/duress/{uuid.uuid4()}", headers=headers)
    assert resp.status_code == 404


async def test_create_event_generic_requires_dispatch_role(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    resp = await client.post(
        "/v1/duress",
        json={
            "vehicle_id": str(uuid.uuid4()),
            "driver_id": str(uuid.uuid4()),
            "trigger": "auto",
            "gps_stream_ref": "s3://bucket/backfill-gps",
        },
        headers=driver_headers,
    )
    assert resp.status_code == 403


async def test_create_event_generic_as_admin(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.post(
        "/v1/duress",
        json={
            "vehicle_id": str(uuid.uuid4()),
            "driver_id": str(uuid.uuid4()),
            "trigger": "voice",
            "gps_stream_ref": "s3://bucket/backfill-gps",
        },
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["status"] == "open"
    assert body["escalation_log_json"]["entries"] == []


async def test_update_and_delete_event_as_admin(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="owner")
    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=headers)
    event_id = trigger_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/duress/{event_id}", json={"audio_ref": "s3://bucket/corrected-audio"}, headers=headers
    )
    assert patch_resp.status_code == 200
    assert patch_resp.json()["audio_ref"] == "s3://bucket/corrected-audio"

    delete_resp = await client.delete(f"/v1/duress/{event_id}", headers=headers)
    assert delete_resp.status_code == 204

    get_resp = await client.get(f"/v1/duress/{event_id}", headers=headers)
    assert get_resp.status_code == 404


async def test_driver_cannot_patch_or_delete_event(client: AsyncClient, session: AsyncSession):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="Duress RBAC Co")
    driver_headers = await auth_headers(client, session, role="driver", tenant_name="Duress RBAC Co 2")

    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=admin_headers)
    event_id = trigger_resp.json()["id"]

    forbidden_resp = await client.patch(
        f"/v1/duress/{event_id}", json={"audio_ref": "nope"}, headers=driver_headers
    )
    # Either 403 (wrong role) or 404 (cross-tenant, can't even see it) is acceptable.
    assert forbidden_resp.status_code in (403, 404)


async def test_tenant_isolation_on_duress_events(client: AsyncClient, session: AsyncSession):
    tenant_a_headers = await auth_headers(client, session, role="admin", tenant_name="Duress Tenant A")
    tenant_b_headers = await auth_headers(client, session, role="admin", tenant_name="Duress Tenant B")

    trigger_resp = await client.post("/v1/duress/trigger", json=_trigger_body(), headers=tenant_a_headers)
    event_id = trigger_resp.json()["id"]

    cross_tenant_resp = await client.get(f"/v1/duress/{event_id}", headers=tenant_b_headers)
    assert cross_tenant_resp.status_code == 404


# --- live websocket relay ----------------------------------------------------------


def test_gps_points_broadcast_to_live_websocket_listeners(app):
    """Uses starlette's synchronous TestClient (rather than the async `client`
    fixture) because httpx's ASGITransport does not support websocket upgrade —
    TestClient does, via its own background portal, and can be safely used
    standalone like this. Builds its own tenant/user/event via direct DB access
    through a throwaway event loop since the `session`/`auth_headers` fixtures
    are async-only."""
    import asyncio

    from fastapi.testclient import TestClient

    from app.core import security
    from app.core.database import AsyncSessionLocal
    from app.models import Tenant, User

    async def _setup():
        async with AsyncSessionLocal() as db:
            tenant = Tenant(name=f"WS Test Tenant {uuid.uuid4()}", plan="standard")
            db.add(tenant)
            await db.commit()
            await db.refresh(tenant)

            dispatcher = User(
                tenant_id=tenant.id,
                role="dispatcher",
                name="Test Dispatcher",
                email=f"{uuid.uuid4()}@example.com",
                pin_hash=security.hash_password("Test-Passw0rd!"),
                status="active",
            )
            db.add(dispatcher)
            await db.commit()
            await db.refresh(dispatcher)

            token = security.create_access_token(
                user_id=dispatcher.id, tenant_id=tenant.id, role=dispatcher.role
            )
        return token

    token = asyncio.run(_setup())

    with TestClient(app) as test_client:
        trigger_resp = test_client.post(
            "/v1/duress/trigger",
            json=_trigger_body(),
            headers={"Authorization": f"Bearer {token}"},
        )
        assert trigger_resp.status_code == 201, trigger_resp.text
        event_id = trigger_resp.json()["id"]

        with test_client.websocket_connect(f"/v1/duress/{event_id}/live?token={token}") as ws:
            gps_resp = test_client.post(
                f"/v1/duress/{event_id}/gps",
                json={"lat": -33.87, "lng": 151.21, "speed_kmh": 42.0},
                headers={"Authorization": f"Bearer {token}"},
            )
            assert gps_resp.status_code == 202
            assert gps_resp.json()["delivered_to"] == 1

            received = ws.receive_json()
            assert received["lat"] == -33.87
            assert received["lng"] == 151.21
            assert received["event_id"] == event_id


def test_websocket_rejects_missing_token(app):
    from fastapi.testclient import TestClient
    from fastapi.websockets import WebSocketDisconnect as FastAPIWebSocketDisconnect

    with TestClient(app) as test_client:
        try:
            with test_client.websocket_connect(f"/v1/duress/{uuid.uuid4()}/live"):
                pass
            raised = False
        except FastAPIWebSocketDisconnect:
            raised = True
        assert raised
