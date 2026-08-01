"""Tests for the Live Ops domain (`GET /v1/vehicles`, `GET /v1/drivers`,
`POST`/`GET /v1/fleet/positions`, `WS /v1/fleet/live`).

NOTE: `app/api/v1/live_ops.py`'s router is not registered in `app.main` yet —
a later integration step does that. Until then, every HTTP/WS request in this
file 404s / fails to connect (FastAPI has no route for it) rather than
exercising real behaviour. That is expected; these tests are written
correctly against the endpoints as built, so they pass once the integration
step wires `live_ops_router` into `app.main`.

Importing the sibling domains' models below (even though nothing else in this
file uses the names directly) is required so those tables land on
`Base.metadata` before the session-scoped `_test_database` fixture in
conftest.py runs `create_all` — `app/models/__init__.py` doesn't import them
yet either, for the same "integration step wires it up" reason. This domain
reads those tables directly via the ORM (bypassing the sibling routers
entirely) so its tests don't additionally depend on the fleet/trips/shift
routers being registered.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest
from starlette.testclient import TestClient

from app.models import Tenant
from app.models.fleet import Device, Vehicle
from app.models.shift import Shift
from app.models.trips import Trip
from app.models.user import ROLE_DRIVER, User
from app.services.live_ops import fleet_broadcaster
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- fixtures / helpers ---------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_broadcaster():
    """The broadcaster is a module-level singleton (matching the production
    pattern of `app.core.security.revocation_store`) so its state must be
    reset between tests or positions/subscribers from one test would leak
    into the next."""
    fleet_broadcaster._latest.clear()
    fleet_broadcaster._subscribers.clear()
    yield
    fleet_broadcaster._latest.clear()
    fleet_broadcaster._subscribers.clear()


async def _tenant_and_headers(client, session, *, role="admin", tenant_name="Test Tenant"):
    tenant = Tenant(name=tenant_name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    headers = await auth_headers(client, session, role=role, tenant_id=tenant.id)
    return tenant.id, headers


async def _make_vehicle(session, *, tenant_id, rego="TX-100", vehicle_class="standard", status="active"):
    vehicle = Vehicle(tenant_id=tenant_id, rego=rego, vehicle_class=vehicle_class, status=status)
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def _make_device(session, *, tenant_id, vehicle_id, android_id, last_seen_at=None):
    device = Device(tenant_id=tenant_id, android_id=android_id, vehicle_id=vehicle_id, last_seen_at=last_seen_at)
    session.add(device)
    await session.commit()
    await session.refresh(device)
    return device


async def _make_driver(session, *, tenant_id, name="Driver One"):
    driver = User(
        tenant_id=tenant_id, role=ROLE_DRIVER, name=name, email=f"{uuid.uuid4()}@example.com", status="active"
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)
    return driver


async def _make_shift(session, *, tenant_id, driver_id, vehicle_id, end_at=None):
    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver_id,
        vehicle_id=vehicle_id,
        start_at=datetime.now(UTC),
        end_at=end_at,
    )
    session.add(shift)
    await session.commit()
    await session.refresh(shift)
    return shift


async def _make_open_trip(session, *, tenant_id, driver_id, vehicle_id, last_lat=None, last_lng=None):
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=vehicle_id,
        driver_id=driver_id,
        tariff_id=str(uuid.uuid4()),
        type="rank_hail",
        status="open",
        start_at=datetime.now(UTC),
        start_lat=-33.87,
        start_lng=151.21,
        last_lat=last_lat,
        last_lng=last_lng,
        last_ts=datetime.now(UTC) if last_lat is not None else None,
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)
    return trip


# --- vehicles: list/get -----------------------------------------------------------


async def test_list_vehicles_with_no_position_falls_back_to_offline(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant 1")
    await _make_vehicle(session, tenant_id=tenant_id, rego="TX-NOPOS")

    resp = await client.get("/v1/vehicles", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 1
    item = body["items"][0]
    assert item["rego"] == "TX-NOPOS"
    assert item["lat"] is None
    assert item["position_source"] == "none"
    assert item["live_status"] == "offline"


async def test_vehicle_falls_back_to_open_trip_last_position(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant 2")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-TRIP")
    driver = await _make_driver(session, tenant_id=tenant_id)
    await _make_open_trip(
        session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id, last_lat=-33.9, last_lng=151.2
    )

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["lat"] == -33.9
    assert body["lng"] == 151.2
    assert body["position_source"] == "trip"
    assert body["live_status"] == "on_trip"
    assert body["current_trip_id"] is not None


async def test_vehicle_prefers_live_published_position_over_trip_fallback(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant 3")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-LIVE")
    driver = await _make_driver(session, tenant_id=tenant_id)
    await _make_open_trip(
        session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id, last_lat=-33.9, last_lng=151.2
    )

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.5, "lng": 151.5, "status": "available"},
        headers=headers,
    )
    assert resp.status_code == 201

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    body = resp.json()
    assert body["lat"] == -33.5
    assert body["lng"] == 151.5
    assert body["position_source"] == "live"
    assert body["live_status"] == "available"


async def test_vehicle_includes_paired_device(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant 4")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-DEV")
    seen_at = datetime.now(UTC)
    device = await _make_device(session, tenant_id=tenant_id, vehicle_id=vehicle.id, android_id="and-1", last_seen_at=seen_at)

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    body = resp.json()
    assert body["device_id"] == device.id
    assert body["device_last_seen_at"] is not None


async def test_get_vehicle_not_found(client, session):
    _, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant 5")
    resp = await client.get("/v1/vehicles/does-not-exist", headers=headers)
    assert resp.status_code == 404


async def test_vehicle_is_tenant_isolated(client, session):
    tenant_a, _headers_a = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant A")
    _, headers_b = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant B")
    vehicle = await _make_vehicle(session, tenant_id=tenant_a, rego="TX-ISO")

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers_b)
    assert resp.status_code == 404

    resp = await client.get("/v1/vehicles", headers=headers_b)
    assert resp.json()["total"] == 0


async def test_list_vehicles_pagination_and_filtering(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant 6")
    for i in range(3):
        await _make_vehicle(session, tenant_id=tenant_id, rego=f"TX-M{i}", vehicle_class="maxi")
    await _make_vehicle(session, tenant_id=tenant_id, rego="TX-STD", vehicle_class="standard")

    resp = await client.get("/v1/vehicles?limit=2&skip=0", headers=headers)
    body = resp.json()
    assert body["total"] == 4
    assert len(body["items"]) == 2

    resp = await client.get("/v1/vehicles?vehicle_class=maxi", headers=headers)
    body = resp.json()
    assert body["total"] == 3

    resp = await client.get("/v1/vehicles?rego=std", headers=headers)
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["rego"] == "TX-STD"


async def test_list_vehicles_filters_by_live_status(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant 7")
    v1 = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-A1")
    await _make_vehicle(session, tenant_id=tenant_id, rego="TX-A2")

    await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": v1.id, "lat": 0, "lng": 0, "status": "available"},
        headers=headers,
    )

    resp = await client.get("/v1/vehicles?live_status=available", headers=headers)
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["rego"] == "TX-A1"


# --- drivers: list/get -------------------------------------------------------------


async def test_list_drivers_off_shift_by_default(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Drivers Tenant 1")
    await _make_driver(session, tenant_id=tenant_id, name="Off Shift Driver")

    resp = await client.get("/v1/drivers", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["on_shift"] is False
    assert body["items"][0]["shift_id"] is None


async def test_driver_on_shift_reports_shift_and_vehicle(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Drivers Tenant 2")
    driver = await _make_driver(session, tenant_id=tenant_id, name="On Shift Driver")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-SHIFT")
    shift = await _make_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)

    resp = await client.get(f"/v1/drivers/{driver.id}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["on_shift"] is True
    assert body["shift_id"] == shift.id
    assert body["vehicle_id"] == vehicle.id


async def test_ended_shift_does_not_count_as_on_shift(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Drivers Tenant 3")
    driver = await _make_driver(session, tenant_id=tenant_id)
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-ENDED")
    await _make_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id, end_at=datetime.now(UTC))

    resp = await client.get(f"/v1/drivers/{driver.id}", headers=headers)
    body = resp.json()
    assert body["on_shift"] is False


async def test_driver_current_trip_id_reflects_open_trip(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Drivers Tenant 4")
    driver = await _make_driver(session, tenant_id=tenant_id)
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-DTRIP")
    trip = await _make_open_trip(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)

    resp = await client.get(f"/v1/drivers/{driver.id}", headers=headers)
    assert resp.json()["current_trip_id"] == trip.id


async def test_list_drivers_filter_on_shift(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Drivers Tenant 5")
    on_driver = await _make_driver(session, tenant_id=tenant_id, name="On")
    await _make_driver(session, tenant_id=tenant_id, name="Off")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-FILT")
    await _make_shift(session, tenant_id=tenant_id, driver_id=on_driver.id, vehicle_id=vehicle.id)

    resp = await client.get("/v1/drivers?on_shift=true", headers=headers)
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["id"] == on_driver.id


async def test_get_driver_not_found(client, session):
    _, headers = await _tenant_and_headers(client, session, tenant_name="Drivers Tenant 6")
    resp = await client.get("/v1/drivers/does-not-exist", headers=headers)
    assert resp.status_code == 404


async def test_driver_is_tenant_isolated(client, session):
    tenant_a, _headers_a = await _tenant_and_headers(client, session, tenant_name="Drivers Tenant A")
    _, headers_b = await _tenant_and_headers(client, session, tenant_name="Drivers Tenant B")
    driver = await _make_driver(session, tenant_id=tenant_a)

    resp = await client.get(f"/v1/drivers/{driver.id}", headers=headers_b)
    assert resp.status_code == 404


# --- positions: publish / list / get ------------------------------------------------


async def test_publish_position_updates_cache_and_vehicle_list(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant 1")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-PUB")

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.86, "lng": 151.2, "status": "on_trip"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["vehicle_id"] == vehicle.id
    assert body["subscriber_count"] == 0  # nobody connected via WS in this test

    resp = await client.get(f"/v1/fleet/positions/{vehicle.id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["status"] == "on_trip"

    resp = await client.get("/v1/fleet/positions", headers=headers)
    assert len(resp.json()) == 1


async def test_publish_position_requires_vehicle_to_exist_in_tenant(client, session):
    _, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant 2")
    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": "not-a-real-vehicle", "lat": 0, "lng": 0, "status": "available"},
        headers=headers,
    )
    assert resp.status_code == 404


async def test_publish_position_is_tenant_scoped(client, session):
    tenant_a, _headers_a = await _tenant_and_headers(client, session, tenant_name="Positions Tenant A")
    _, headers_b = await _tenant_and_headers(client, session, tenant_name="Positions Tenant B")
    vehicle = await _make_vehicle(session, tenant_id=tenant_a, rego="TX-SCOPE")

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": 0, "lng": 0, "status": "available"},
        headers=headers_b,
    )
    assert resp.status_code == 404  # vehicle doesn't belong to tenant B

    resp = await client.get(f"/v1/fleet/positions/{vehicle.id}", headers=headers_b)
    assert resp.status_code == 404  # never published under tenant B's cache either


async def test_get_position_not_found(client, session):
    _, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant 3")
    resp = await client.get("/v1/fleet/positions/never-published", headers=headers)
    assert resp.status_code == 404


# --- WS /v1/fleet/live --------------------------------------------------------------


async def test_websocket_receives_published_position(app, session):
    """Best-effort end-to-end check that a published position reaches a
    connected WS subscriber. Both the WS connection and the triggering POST
    are made through the same `starlette.testclient.TestClient` instance
    (its own background thread/event loop) rather than mixing in the
    pytest-asyncio `client` fixture's event loop, since `asyncio.Queue` (used
    internally by the broadcaster) is loop-bound and crossing loops for the
    same queue is not safe. Production only ever has one event loop per
    worker process, so this loop-boundary concern is a test-harness artifact,
    not a real code path.
    """
    tenant = Tenant(name="WS Tenant", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)

    vehicle = await _make_vehicle(session, tenant_id=tenant.id, rego="TX-WS")

    from app.core import security

    token = security.create_access_token(user_id=str(uuid.uuid4()), tenant_id=tenant.id, role="admin")
    headers = {"Authorization": f"Bearer {token}"}

    with TestClient(app) as tc, tc.websocket_connect(f"/v1/fleet/live?token={token}") as ws:
        resp = tc.post(
            "/v1/fleet/positions",
            json={"vehicle_id": vehicle.id, "lat": -33.87, "lng": 151.21, "status": "available"},
            headers=headers,
        )
        assert resp.status_code == 201

        message = ws.receive_json()
        assert message["vehicle_id"] == vehicle.id
        assert message["status"] == "available"
        assert message["lat"] == -33.87


async def test_websocket_rejects_missing_token(app):
    from starlette.websockets import WebSocketDisconnect

    with TestClient(app) as tc, pytest.raises(WebSocketDisconnect), tc.websocket_connect("/v1/fleet/live"):
        pass
