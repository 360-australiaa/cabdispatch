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
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select
from starlette.testclient import TestClient

from app.models import Tenant
from app.models.fleet import Device, Vehicle, VehiclePositionHistory
from app.models.jobs import DriverAvailability
from app.models.shift import Shift
from app.models.trips import Trip
from app.models.user import ROLE_DRIVER, User
from app.services.live_ops import POSITION_HISTORY_RETENTION_HOURS, fleet_broadcaster
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


async def _make_availability(session, *, tenant_id, driver_id, is_available):
    """Direct-ORM row creation (bypassing `POST /v1/jobs/availability`) --
    same rationale as this file's other `_make_*` helpers (`_make_shift`,
    `_make_device`, ...): this domain reads the jobs domain's
    `DriverAvailability` table directly, read-only, so its tests don't need
    the jobs router registered. Mirrors `tests/test_job_proximity.py`'s own
    direct-row-mutation pattern for this same model."""
    row = DriverAvailability(tenant_id=tenant_id, driver_id=driver_id, is_available=is_available)
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


async def _make_open_trip(
    session,
    *,
    tenant_id,
    driver_id,
    vehicle_id,
    last_lat=None,
    last_lng=None,
    planned_dest_lat=None,
    planned_dest_lng=None,
):
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
        planned_dest_lat=planned_dest_lat,
        planned_dest_lng=planned_dest_lng,
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


async def test_vehicle_with_planned_destination_shows_it(client, session):
    """A driver-picked mid-trip destination (module docstring deviation #7 on
    app.models.trips.Trip) surfaces on GET /v1/vehicles so the dispatcher
    Live Map can draw a route line to it."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant Dest")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-DEST")
    driver = await _make_driver(session, tenant_id=tenant_id)
    await _make_open_trip(
        session,
        tenant_id=tenant_id,
        driver_id=driver.id,
        vehicle_id=vehicle.id,
        planned_dest_lat=-33.8568,
        planned_dest_lng=151.2153,
    )

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["planned_dest_lat"] == -33.8568
    assert body["planned_dest_lng"] == 151.2153


async def test_vehicle_with_open_trip_but_no_planned_destination_shows_null(client, session):
    """An open trip whose driver hasn't picked a destination yet reports
    null for both fields -- never a fabricated 0/0 (house convention, see
    e.g. PositionRead.battery)."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Vehicles Tenant NoDest")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-NODEST")
    driver = await _make_driver(session, tenant_id=tenant_id)
    await _make_open_trip(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["planned_dest_lat"] is None
    assert body["planned_dest_lng"] is None


async def test_vehicle_prefers_live_published_position_over_trip_fallback(client, session):
    """lat/lng/position_source prefer the live-published position over the
    open trip's last tick -- unaffected by the live_status priority-order fix
    below, since `_compose_vehicle_live` derives position and live_status
    independently. `live_status` itself, however, is "on_trip" here, not the
    published "available" -- an open trip always wins live_status regardless
    of what the last-published position's status field says (see
    test_open_trip_wins_live_status_even_over_a_published_status, the
    regression test for the bug this priority order fixes, and
    `_compose_vehicle_live`'s own docstring for the full four-step order)."""
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
    assert body["live_status"] == "on_trip"


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


# --- live_status priority order (2026-09-05 fix) ------------------------------------


async def test_open_trip_wins_live_status_even_over_a_published_status(client, session):
    """Regression test for the real bug this pass fixes: before the fix,
    `_compose_vehicle_live` trusted `live_position["status"]` unconditionally
    once ANY position had ever been published, so a vehicle mid-trip that had
    also published a position (i.e. every on-shift vehicle, since Android
    heartbeats constantly) showed whatever the client sent -- in practice
    always the literal placeholder "unknown" -- instead of "on_trip". An open
    trip must win regardless of what the last-published position's status
    field says, even a stale/placeholder one."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Priority Tenant OpenTrip")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-PRIO-TRIP")
    driver = await _make_driver(session, tenant_id=tenant_id)
    await _make_open_trip(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.86, "lng": 151.2, "status": "unknown"},
        headers=headers,
    )
    assert resp.status_code == 201

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    body = resp.json()
    assert body["live_status"] == "on_trip"
    assert body["current_trip_id"] is not None
    # the live-published position/lat-lng still comes through -- only
    # live_status is overridden by the open trip, not the position itself.
    assert body["position_source"] == "live"
    assert body["lat"] == -33.86


async def test_live_status_falls_back_to_driver_availability_true(client, session):
    """No open trip, no real (non-"unknown") live-published status, but the
    on-shift driver has toggled themselves available (jobs domain
    `DriverAvailability.is_available`) -- step 3 of the priority order."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Priority Tenant Available")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-PRIO-AVAIL")
    driver = await _make_driver(session, tenant_id=tenant_id)
    await _make_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)
    await _make_availability(session, tenant_id=tenant_id, driver_id=driver.id, is_available=True)

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    assert resp.json()["live_status"] == "available"


async def test_live_status_falls_back_to_driver_availability_false_is_break(client, session):
    """Same as above but is_available=False -> 'break', not 'offline' -- a
    driver on shift who has marked themselves unavailable is on a break, not
    off the road."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Priority Tenant Break")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-PRIO-BREAK")
    driver = await _make_driver(session, tenant_id=tenant_id)
    await _make_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)
    await _make_availability(session, tenant_id=tenant_id, driver_id=driver.id, is_available=False)

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    assert resp.json()["live_status"] == "break"


async def test_live_status_with_no_shift_falls_back_to_vehicle_status_unchanged(client, session):
    """No open trip, no live position, no shift at all (so no
    DriverAvailability lookup is even possible) -- step 4, the pre-existing
    `vehicle.status`/`DEFAULT_LIVE_STATUS` fallback, must be unaffected by
    this pass."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Priority Tenant NoShift")
    await _make_vehicle(session, tenant_id=tenant_id, rego="TX-PRIO-NOSHIFT", status="active")
    maintenance_vehicle = await _make_vehicle(
        session, tenant_id=tenant_id, rego="TX-PRIO-MAINT", status="maintenance"
    )

    resp = await client.get("/v1/vehicles?rego=NOSHIFT", headers=headers)
    assert resp.json()["items"][0]["live_status"] == "offline"

    resp = await client.get(f"/v1/vehicles/{maintenance_vehicle.id}", headers=headers)
    assert resp.json()["live_status"] == "maintenance"


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


async def test_publish_position_404s_on_the_rego_not_just_a_random_id(client, session):
    """Locks in the exact identifier `POST /v1/fleet/positions` requires:
    `Vehicle.id` (the real fleet-vehicle UUID), not `Vehicle.rego`.

    This is the live-confirmed 404 behind a real client bug (Android
    `SettingsViewModel.respondToLocateRequest`, found 2026-09-04 on a real
    tablet): it published `DriverSession.vehicleId` -- the driver-entered/
    QR'd rego string -- as `vehicle_id`, which `get_vehicle_or_404` (this
    endpoint) rejects with 404 "Vehicle not found" because it looks
    `vehicle_id` up against `Vehicle.id` only. The route/method/payload shape
    itself was never wrong; the client was sending the wrong kind of
    identifier. See `au.com.threesixty.cabdispatch.domain.DriverSession.vehicleUuid`'s
    doc and `LivePositionHeartbeat`/`DeviceCommandHeartbeat`, which already used the
    UUID for this same reason."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant Rego")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="KHI-01")

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.rego, "lat": -33.86, "lng": 151.2, "status": "available"},
        headers=headers,
    )
    assert resp.status_code == 404
    assert resp.json()["detail"] == "Vehicle not found"

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.86, "lng": 151.2, "status": "available"},
        headers=headers,
    )
    assert resp.status_code == 201


async def test_publish_position_with_battery_and_network_reaches_vehicle_list(client, session):
    """battery/network are optional on PositionPublishRequest -- when given,
    they show up on the live cache (GET /v1/fleet/positions/{id}) AND on the
    composed GET /v1/vehicles rollup, same call, no second heartbeat needed."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant Battery")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-BATT")

    resp = await client.post(
        "/v1/fleet/positions",
        json={
            "vehicle_id": vehicle.id,
            "lat": -33.86,
            "lng": 151.2,
            "status": "available",
            "battery": 72,
            "network": "4g",
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["battery"] == 72
    assert resp.json()["network"] == "4g"

    resp = await client.get(f"/v1/fleet/positions/{vehicle.id}", headers=headers)
    assert resp.json()["battery"] == 72
    assert resp.json()["network"] == "4g"

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    assert resp.json()["battery"] == 72
    assert resp.json()["network"] == "4g"


async def test_publish_position_battery_and_network_persist_onto_device_row(client, session):
    """The paired Device row's own battery/network columns get updated too
    (durable, survives a broadcaster restart), not just the ephemeral live
    cache -- see app.services.live_ops._persist_device_telemetry."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant Battery Device")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-BATT-DEV")
    device = await _make_device(session, tenant_id=tenant_id, vehicle_id=vehicle.id, android_id="and-batt")
    assert device.battery is None
    assert device.network is None

    resp = await client.post(
        "/v1/fleet/positions",
        json={
            "vehicle_id": vehicle.id,
            "lat": -33.86,
            "lng": 151.2,
            "status": "available",
            "battery": 55,
            "network": "wifi",
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text

    await session.refresh(device)
    assert device.battery == 55
    assert device.network == "wifi"
    assert device.last_seen_at is not None


async def test_vehicle_falls_back_to_device_row_battery_when_no_live_position(client, session):
    """A device that already reported battery/network via a plain heartbeat
    (POST /v1/fleet/devices/{id}/heartbeat) should still show up on
    GET /v1/vehicles even before any position has ever been published for
    that vehicle."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant Battery Fallback")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-BATT-FALLBACK")
    device = await _make_device(session, tenant_id=tenant_id, vehicle_id=vehicle.id, android_id="and-fallback")
    device.battery = 40
    device.network = "offline"
    await session.commit()

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    body = resp.json()
    assert body["battery"] == 40
    assert body["network"] == "offline"
    assert body["lat"] is None  # still no position published


async def test_publish_position_without_battery_or_network_leaves_device_row_untouched(client, session):
    """A plain lat/lng/status publish (no battery/network) must NOT overwrite
    an already-known Device.battery/network with None, and must not touch
    last_seen_at either -- only a heartbeat/publish that actually carries
    telemetry should update it."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant No Telemetry")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-NO-TELEM")
    device = await _make_device(session, tenant_id=tenant_id, vehicle_id=vehicle.id, android_id="and-no-telem")
    device.battery = 90
    device.network = "wifi"
    await session.commit()

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.86, "lng": 151.2, "status": "available"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["battery"] is None
    assert resp.json()["network"] is None

    await session.refresh(device)
    assert device.battery == 90  # unchanged
    assert device.network == "wifi"  # unchanged
    assert device.last_seen_at is None  # untouched -- this publish carried no telemetry


async def test_publish_position_with_speed_and_heading_reaches_vehicle_list(client, session):
    """speed_kmh/heading are optional on PositionPublishRequest, same as
    battery/network -- when given, they show up on the live cache
    (GET /v1/fleet/positions/{id}) AND on the composed GET /v1/vehicles
    rollup. Unlike battery/network there is no Device-row persistence for
    these two (see app.services.live_ops.publish_position's docstring)."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant Speed")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-SPEED")

    resp = await client.post(
        "/v1/fleet/positions",
        json={
            "vehicle_id": vehicle.id,
            "lat": -33.86,
            "lng": 151.2,
            "status": "available",
            "speed_kmh": 42.5,
            "heading": 270.0,
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["speed_kmh"] == 42.5
    assert resp.json()["heading"] == 270.0

    resp = await client.get(f"/v1/fleet/positions/{vehicle.id}", headers=headers)
    assert resp.json()["speed_kmh"] == 42.5
    assert resp.json()["heading"] == 270.0

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    assert resp.json()["speed_kmh"] == 42.5
    assert resp.json()["heading"] == 270.0


async def test_publish_position_without_speed_or_heading_leaves_them_honestly_null(client, session):
    """A plain lat/lng/status publish with no speed_kmh/heading must show
    None for both -- never a fabricated 0, which is a real, valid heading
    (north) and a real, valid speed (stationary)."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant No Speed")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-NO-SPEED")

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.86, "lng": 151.2, "status": "available"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["speed_kmh"] is None
    assert resp.json()["heading"] is None

    resp = await client.get(f"/v1/vehicles/{vehicle.id}", headers=headers)
    body = resp.json()
    assert body["speed_kmh"] is None
    assert body["heading"] is None


async def test_publish_position_rejects_out_of_range_speed_and_heading(client, session):
    """ge/le bounds on PositionPublishRequest.speed_kmh/heading -- 422, not a
    silently-clamped value."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="Positions Tenant Bad Speed")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-BAD-SPEED")

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": 0, "lng": 0, "status": "available", "speed_kmh": -5},
        headers=headers,
    )
    assert resp.status_code == 422

    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": 0, "lng": 0, "status": "available", "heading": 360},
        headers=headers,
    )
    assert resp.status_code == 422


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


# --- durable position history + driving signals (dispatcher-replay pass) ------------


async def _history_rows(session, *, vehicle_id):
    result = await session.execute(
        select(VehiclePositionHistory)
        .where(VehiclePositionHistory.vehicle_id == vehicle_id)
        .order_by(VehiclePositionHistory.recorded_at.asc())
    )
    return list(result.scalars())


async def test_publish_position_appends_durable_history_row(client, session):
    """Every publish_position call appends one row to the new
    VehiclePositionHistory table -- on top of, not instead of, the existing
    in-memory broadcast/cache."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="History Tenant 1")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST")

    resp = await client.post(
        "/v1/fleet/positions",
        json={
            "vehicle_id": vehicle.id,
            "lat": -33.86,
            "lng": 151.2,
            "status": "available",
            "speed_kmh": 40.0,
            "heading": 90.0,
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text

    rows = await _history_rows(session, vehicle_id=vehicle.id)
    assert len(rows) == 1
    assert rows[0].tenant_id == tenant_id
    assert rows[0].lat == -33.86
    assert rows[0].lng == 151.2
    assert rows[0].status == "available"
    assert rows[0].speed_kmh == 40.0
    assert rows[0].heading == 90.0
    assert rows[0].recorded_at is not None

    # A second publish appends a second row rather than overwriting the first
    # -- unlike the in-memory "latest position" cache, this is a real log.
    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": vehicle.id, "lat": -33.87, "lng": 151.21, "status": "on_trip"},
        headers=headers,
    )
    assert resp.status_code == 201
    rows = await _history_rows(session, vehicle_id=vehicle.id)
    assert len(rows) == 2


async def test_publish_position_prunes_only_this_vehicles_own_stale_history(client, session):
    """The lazy prune in `_persist_position_history` deletes only THIS
    vehicle's rows older than POSITION_HISTORY_RETENTION_HOURS, scoped by
    vehicle_id -- a stale row for a different vehicle in the same tenant must
    survive untouched, and a recent row for that other vehicle must also
    survive."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="History Tenant Prune")
    stale_vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST-STALE")
    other_vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST-OTHER")

    old_recorded_at = datetime.now(UTC) - timedelta(hours=POSITION_HISTORY_RETENTION_HOURS + 1)
    stale_row = VehiclePositionHistory(
        tenant_id=tenant_id,
        vehicle_id=stale_vehicle.id,
        lat=-33.0,
        lng=151.0,
        status="offline",
        recorded_at=old_recorded_at,
    )
    other_recent_row = VehiclePositionHistory(
        tenant_id=tenant_id,
        vehicle_id=other_vehicle.id,
        lat=-33.1,
        lng=151.1,
        status="offline",
        recorded_at=old_recorded_at,  # also old, but belongs to a DIFFERENT vehicle
    )
    session.add_all([stale_row, other_recent_row])
    await session.commit()

    # Publishing for stale_vehicle triggers its own prune -- must delete the
    # old row for stale_vehicle, but must NOT touch other_vehicle's old row.
    resp = await client.post(
        "/v1/fleet/positions",
        json={"vehicle_id": stale_vehicle.id, "lat": -33.2, "lng": 151.2, "status": "available"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text

    stale_rows = await _history_rows(session, vehicle_id=stale_vehicle.id)
    assert len(stale_rows) == 1  # the old one is gone; only the fresh publish remains
    # sqlite round-trips DateTime(timezone=True) as a naive value -- strip
    # tzinfo from both sides for the comparison rather than relying on
    # driver-specific timezone-preservation behaviour.
    assert stale_rows[0].recorded_at.replace(tzinfo=None) > old_recorded_at.replace(tzinfo=None)

    other_rows = await _history_rows(session, vehicle_id=other_vehicle.id)
    assert len(other_rows) == 1  # untouched -- this vehicle never published, so no prune ran for it
    assert other_rows[0].lat == -33.1


async def test_position_history_endpoint_returns_points_in_order(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="History Tenant Order")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST-ORDER")

    base = datetime.now(UTC) - timedelta(minutes=10)
    rows = [
        VehiclePositionHistory(
            tenant_id=tenant_id,
            vehicle_id=vehicle.id,
            lat=-33.0 - i * 0.01,
            lng=151.0 + i * 0.01,
            status="on_trip",
            recorded_at=base + timedelta(minutes=i),
        )
        for i in range(3)
    ]
    session.add_all(rows)
    await session.commit()

    resp = await client.get(f"/v1/vehicles/{vehicle.id}/position-history", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["items"]) == 3
    recorded_ats = [item["recorded_at"] for item in body["items"]]
    assert recorded_ats == sorted(recorded_ats)
    assert body["threshold_kmh_per_s"] == 8.0


async def test_position_history_since_filter_narrows_the_window(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="History Tenant Since")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST-SINCE")

    now = datetime.now(UTC)
    old_row = VehiclePositionHistory(
        tenant_id=tenant_id, vehicle_id=vehicle.id, lat=-33.0, lng=151.0, status="on_trip",
        recorded_at=now - timedelta(hours=2),
    )
    recent_row = VehiclePositionHistory(
        tenant_id=tenant_id, vehicle_id=vehicle.id, lat=-33.1, lng=151.1, status="on_trip",
        recorded_at=now - timedelta(minutes=5),
    )
    session.add_all([old_row, recent_row])
    await session.commit()

    since = (now - timedelta(hours=1)).isoformat()
    # Pass `since` via `params` (not hand-embedded in the URL) so httpx
    # properly percent-encodes the "+00:00" UTC offset -- an un-encoded "+"
    # in a raw query string is otherwise decoded as a space server-side,
    # breaking datetime parsing (a real gotcha, not a client requirement).
    resp = await client.get(
        f"/v1/vehicles/{vehicle.id}/position-history", params={"since": since}, headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["items"]) == 1
    assert body["items"][0]["lat"] == -33.1


async def test_position_history_harsh_brake_and_rapid_accel_counts(client, session):
    """A hand-crafted sequence of consecutive-point deltas at an 8 km/h/s
    threshold: 60->20 km/h over 2s (-20 km/h/s) is one harsh brake; 20->55
    km/h over 2s (+17.5 km/h/s) and 55->95 km/h over 2s (+20 km/h/s) are each
    a rapid accel. For contrast, this pass's own brief example -- 60->55
    km/h over 2s, a mere -2.5 km/h/s -- would NOT cross the threshold, which
    is exactly why it is not one of the deltas used here."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="History Tenant Signals")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST-SIGNAL")

    base = datetime.now(UTC) - timedelta(minutes=10)
    speeds_and_offsets = [
        (60.0, 0),
        (20.0, 2),  # harsh brake vs previous
        (55.0, 4),  # NOT a harsh brake (60->55 comparison never happens; this is 20->55)
        (95.0, 6),  # rapid accel vs previous (55->95)
    ]
    rows = [
        VehiclePositionHistory(
            tenant_id=tenant_id,
            vehicle_id=vehicle.id,
            lat=-33.0,
            lng=151.0,
            status="on_trip",
            speed_kmh=speed,
            recorded_at=base + timedelta(seconds=offset),
        )
        for speed, offset in speeds_and_offsets
    ]
    session.add_all(rows)
    await session.commit()

    resp = await client.get(f"/v1/vehicles/{vehicle.id}/position-history", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    # 60->20 in 2s (-20 km/h/s) is a harsh brake; 20->55 in 2s (+17.5 km/h/s)
    # is also a rapid accel; 55->95 in 2s (+20 km/h/s) is another rapid accel.
    assert body["harsh_brake_events"] == 1
    assert body["rapid_accel_events"] == 2
    assert body["threshold_kmh_per_s"] == 8.0


async def test_position_history_brief_examples_60_to_20_brakes_60_to_55_does_not(client, session):
    """The two examples given directly in this pass's brief, each in
    isolation (a fresh vehicle per case, one pair of points each): 60->20
    km/h in 2s (-20 km/h/s) crosses the 8 km/h/s threshold and counts as one
    harsh brake; 60->55 km/h in 2s (-2.5 km/h/s) does not."""
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="History Tenant Brief Examples")

    braking_vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST-BRAKE")
    base = datetime.now(UTC) - timedelta(minutes=10)
    session.add_all(
        [
            VehiclePositionHistory(
                tenant_id=tenant_id, vehicle_id=braking_vehicle.id, lat=-33.0, lng=151.0, status="on_trip",
                speed_kmh=60.0, recorded_at=base,
            ),
            VehiclePositionHistory(
                tenant_id=tenant_id, vehicle_id=braking_vehicle.id, lat=-33.0, lng=151.0, status="on_trip",
                speed_kmh=20.0, recorded_at=base + timedelta(seconds=2),
            ),
        ]
    )

    gentle_vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST-GENTLE")
    session.add_all(
        [
            VehiclePositionHistory(
                tenant_id=tenant_id, vehicle_id=gentle_vehicle.id, lat=-33.0, lng=151.0, status="on_trip",
                speed_kmh=60.0, recorded_at=base,
            ),
            VehiclePositionHistory(
                tenant_id=tenant_id, vehicle_id=gentle_vehicle.id, lat=-33.0, lng=151.0, status="on_trip",
                speed_kmh=55.0, recorded_at=base + timedelta(seconds=2),
            ),
        ]
    )
    await session.commit()

    resp = await client.get(f"/v1/vehicles/{braking_vehicle.id}/position-history", headers=headers)
    assert resp.json()["harsh_brake_events"] == 1

    resp = await client.get(f"/v1/vehicles/{gentle_vehicle.id}/position-history", headers=headers)
    assert resp.json()["harsh_brake_events"] == 0


async def test_position_history_ignores_pairs_missing_speed_or_with_a_long_gap(client, session):
    tenant_id, headers = await _tenant_and_headers(client, session, tenant_name="History Tenant Gaps")
    vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-HIST-GAP")

    base = datetime.now(UTC) - timedelta(hours=1)
    rows = [
        VehiclePositionHistory(
            tenant_id=tenant_id, vehicle_id=vehicle.id, lat=-33.0, lng=151.0, status="on_trip",
            speed_kmh=None, recorded_at=base,
        ),
        VehiclePositionHistory(
            # No speed on this point either -- the pair (None, None) must not count.
            tenant_id=tenant_id, vehicle_id=vehicle.id, lat=-33.0, lng=151.0, status="on_trip",
            speed_kmh=None, recorded_at=base + timedelta(seconds=2),
        ),
        VehiclePositionHistory(
            # A real speed after a long gap (5 minutes) from the previous point --
            # the huge implied delta must NOT count as a harsh brake/rapid accel.
            tenant_id=tenant_id, vehicle_id=vehicle.id, lat=-33.0, lng=151.0, status="on_trip",
            speed_kmh=100.0, recorded_at=base + timedelta(minutes=5),
        ),
    ]
    session.add_all(rows)
    await session.commit()

    resp = await client.get(f"/v1/vehicles/{vehicle.id}/position-history", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["harsh_brake_events"] == 0
    assert body["rapid_accel_events"] == 0


async def test_position_history_is_tenant_isolated(client, session):
    tenant_a, _headers_a = await _tenant_and_headers(client, session, tenant_name="History Tenant A")
    _, headers_b = await _tenant_and_headers(client, session, tenant_name="History Tenant B")
    vehicle = await _make_vehicle(session, tenant_id=tenant_a, rego="TX-HIST-ISO")

    resp = await client.get(f"/v1/vehicles/{vehicle.id}/position-history", headers=headers_b)
    assert resp.status_code == 404


async def test_position_history_vehicle_not_found(client, session):
    _, headers = await _tenant_and_headers(client, session, tenant_name="History Tenant NotFound")
    resp = await client.get("/v1/vehicles/does-not-exist/position-history", headers=headers)
    assert resp.status_code == 404
