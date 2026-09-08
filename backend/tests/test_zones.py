"""Tests for the zones domain (`/v1/zones`): zone CRUD, "plot into a zone"
(`POST /v1/zones/{id}/plot` / `POST /v1/zones/unplot`), and the live
per-zone stats aggregation (`GET /v1/zones/stats`) -- see
`app.services.zones` for the exact aggregation rules and documented
simplifications this exercises.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from sqlalchemy import select

from app.core import security
from app.core.security import PLATFORM_TENANT_ID
from app.models import Tenant
from app.models.fleet import Vehicle
from app.models.jobs import Job
from app.models.shift import Shift
from app.models.trips import Trip
from app.models.user import ROLE_DRIVER, User
from app.services.live_ops import fleet_broadcaster
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

# A small circle around a fixed Sydney CBD-ish point, and a point far enough
# away (~9km) to be reliably outside it -- same style as tests/test_geofences.py.
_ZONE_CENTER = {"center_lat": -33.8523, "center_lng": 151.2108, "radius_m": 500}
_INSIDE = {"lat": -33.8523, "lng": 151.2108}
_OUTSIDE = {"lat": -33.90, "lng": 151.30}


@pytest.fixture(autouse=True)
def _reset_broadcaster():
    """See tests/test_live_ops.py -- the fleet_broadcaster is a module-level
    singleton and must be reset between tests."""
    fleet_broadcaster._latest.clear()
    fleet_broadcaster._subscribers.clear()
    yield
    fleet_broadcaster._latest.clear()
    fleet_broadcaster._subscribers.clear()


async def _tenant_and_headers(client, session, *, role="admin", tenant_name="Zones Tenant"):
    tenant = Tenant(name=f"{tenant_name} {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    headers = await auth_headers(client, session, role=role, tenant_id=tenant.id)
    return tenant.id, headers


def _zone_payload(**overrides) -> dict:
    payload = {"name": "Sydney City", "number": "1", **_ZONE_CENTER}
    payload.update(overrides)
    return payload


# --- platform-owner write helpers ------------------------------------------------
#
# POST/PUT/DELETE /v1/zones are platform-owner-only since this pass (see
# app/api/v1/zones.py's module docstring) -- "as an admin, we are setting up
# the plotting, not the network". Existing tests below still authenticate as
# a tenant "admin" for reads and for identifying which tenant a write should
# land on, but every actual write goes through the platform owner acting
# cross-tenant via the `?tenant_id=` override
# (app.core.security.get_current_tenant_id's docstring) onto that tenant.
# POST /{id}/plot and POST /unplot are untouched -- those stay any
# authenticated tenant user (a driver's own operational action, not zone
# configuration) and are called directly against client.post below, same as
# before this pass.


async def _platform_owner_headers(client, session):
    """Duplicated per-test-file rather than cross-imported, matching the
    existing tests/test_platform.py / tests/test_app_releases.py convention."""
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    if result.scalar_one_or_none() is None:
        session.add(Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform"))
        await session.commit()
    return await auth_headers(client, session, role="owner", tenant_id=PLATFORM_TENANT_ID)


def _tenant_id_of(headers: dict) -> str:
    token = headers["Authorization"].removeprefix("Bearer ")
    return security.decode_token(token)["tenant_id"]


async def _create_zone(client, session, tenant_headers, payload):
    tenant_id = _tenant_id_of(tenant_headers)
    owner_headers = await _platform_owner_headers(client, session)
    return await client.post(f"/v1/zones?tenant_id={tenant_id}", json=payload, headers=owner_headers)


async def _update_zone(client, session, tenant_headers, zone_id, payload):
    tenant_id = _tenant_id_of(tenant_headers)
    owner_headers = await _platform_owner_headers(client, session)
    return await client.put(f"/v1/zones/{zone_id}?tenant_id={tenant_id}", json=payload, headers=owner_headers)


async def _delete_zone(client, session, tenant_headers, zone_id):
    tenant_id = _tenant_id_of(tenant_headers)
    owner_headers = await _platform_owner_headers(client, session)
    return await client.delete(f"/v1/zones/{zone_id}?tenant_id={tenant_id}", headers=owner_headers)


async def _make_driver(session, *, tenant_id, name="Driver One"):
    driver = User(
        tenant_id=tenant_id, role=ROLE_DRIVER, name=name, email=f"{uuid.uuid4()}@example.com", status="active"
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)
    return driver


async def _make_vehicle(session, *, tenant_id, rego="TX-100"):
    vehicle = Vehicle(tenant_id=tenant_id, rego=rego, vehicle_class="standard", status="active")
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def _make_open_shift(session, *, tenant_id, driver_id, vehicle_id):
    shift = Shift(tenant_id=tenant_id, driver_id=driver_id, vehicle_id=vehicle_id, start_at=datetime.now(UTC))
    session.add(shift)
    await session.commit()
    await session.refresh(shift)
    return shift


# ==================================================================================
# Zone CRUD
# ==================================================================================


async def test_create_and_get_zone(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session, role="admin")

    resp = await _create_zone(client, session, headers, _zone_payload())
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["name"] == "Sydney City"
    assert body["number"] == "1"
    assert body["radius_m"] == 500

    get_resp = await client.get(f"/v1/zones/{body['id']}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["id"] == body["id"]


async def test_create_zone_requires_admin_role(client, session):
    # Direct call (not through the _create_zone platform-owner helper): a
    # driver is refused before ever reaching the platform-owner check --
    # require_platform_owner still 403s a non-owner/admin role first.
    _tenant_id, headers = await _tenant_and_headers(client, session, role="driver")
    resp = await client.post("/v1/zones", json=_zone_payload(), headers=headers)
    assert resp.status_code == 403


async def test_create_zone_requires_platform_owner_not_just_tenant_admin(client, session):
    """The product decision this pass implements: an ordinary tenant
    owner/admin can no longer create a zone at all -- only the platform
    owner can (see app/api/v1/zones.py's module docstring)."""
    _tenant_id, headers = await _tenant_and_headers(client, session, role="admin")
    resp = await client.post("/v1/zones", json=_zone_payload(), headers=headers)
    assert resp.status_code == 403

    owner_resp = await _create_zone(client, session, headers, _zone_payload())
    assert owner_resp.status_code == 201, owner_resp.text


async def test_create_zone_duplicate_number_conflicts(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session, role="admin")
    resp1 = await _create_zone(client, session, headers, _zone_payload(number="17"))
    assert resp1.status_code == 201

    resp2 = await _create_zone(client, session, headers, _zone_payload(number="17", name="Airport"))
    assert resp2.status_code == 409


async def test_same_number_allowed_across_different_tenants(client, session):
    _t1, headers1 = await _tenant_and_headers(client, session, role="admin", tenant_name="T1")
    _t2, headers2 = await _tenant_and_headers(client, session, role="admin", tenant_name="T2")

    resp1 = await _create_zone(client, session, headers1, _zone_payload(number="1"))
    resp2 = await _create_zone(client, session, headers2, _zone_payload(number="1"))
    assert resp1.status_code == 201
    assert resp2.status_code == 201


async def test_list_zones_scoped_to_tenant(client, session):
    t1, headers1 = await _tenant_and_headers(client, session, role="admin", tenant_name="ListT1")
    _t2, headers2 = await _tenant_and_headers(client, session, role="admin", tenant_name="ListT2")

    await _create_zone(client, session, headers1, _zone_payload(number="1"))
    await _create_zone(client, session, headers1, _zone_payload(number="2"))
    await _create_zone(client, session, headers2, _zone_payload(number="1"))

    resp = await client.get("/v1/zones", headers=headers1)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 2
    assert all(item["tenant_id"] == t1 for item in body["items"])


async def test_update_zone_full_replace(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session, role="admin")
    create_resp = await _create_zone(client, session, headers, _zone_payload())
    zone_id = create_resp.json()["id"]

    put_resp = await _update_zone(client, session, headers, zone_id, _zone_payload(name="Airport", number="23", radius_m=1000))
    assert put_resp.status_code == 200, put_resp.text
    body = put_resp.json()
    assert body["name"] == "Airport"
    assert body["number"] == "23"
    assert body["radius_m"] == 1000


async def test_delete_zone(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session, role="admin")
    create_resp = await _create_zone(client, session, headers, _zone_payload())
    zone_id = create_resp.json()["id"]

    del_resp = await _delete_zone(client, session, headers, zone_id)
    assert del_resp.status_code == 204

    get_resp = await client.get(f"/v1/zones/{zone_id}", headers=headers)
    assert get_resp.status_code == 404


async def test_get_zone_not_found(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session, role="admin")
    resp = await client.get(f"/v1/zones/{uuid.uuid4()}", headers=headers)
    assert resp.status_code == 404


# --- Platform-owner write gate (product decision, 2026) --------------------------
#
# "As an admin, we are setting up the plotting, not the network." Only
# require_platform_owner may create/update/delete a zone; a tenant
# owner/admin keeps read access (list/get/stats) and drivers keep plotting.


async def test_tenant_admin_gets_403_updating_and_deleting_zone(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session, role="admin")
    create_resp = await _create_zone(client, session, headers, _zone_payload())
    zone_id = create_resp.json()["id"]

    put_resp = await client.put(
        f"/v1/zones/{zone_id}", json=_zone_payload(name="Airport"), headers=headers
    )
    assert put_resp.status_code == 403

    del_resp = await client.delete(f"/v1/zones/{zone_id}", headers=headers)
    assert del_resp.status_code == 403

    # neither write was actually applied
    get_resp = await client.get(f"/v1/zones/{zone_id}", headers=headers)
    assert get_resp.status_code == 200
    assert get_resp.json()["name"] == "Sydney City"


async def test_tenant_reads_and_driver_plotting_stay_open_after_platform_owner_gate(
    client, session
):
    """Read paths (list/get/stats) and the driver plot/unplot action must
    keep working unchanged for ordinary tenant roles even though zone CRUD
    now 403s a tenant owner/admin."""
    tenant_id, admin_headers = await _tenant_and_headers(client, session, role="admin")
    zone_resp = await _create_zone(client, session, admin_headers, _zone_payload())
    zone_id = zone_resp.json()["id"]

    assert (await client.get("/v1/zones", headers=admin_headers)).status_code == 200
    assert (await client.get(f"/v1/zones/{zone_id}", headers=admin_headers)).status_code == 200
    assert (await client.get("/v1/zones/stats", headers=admin_headers)).status_code == 200

    driver = await _make_driver(session, tenant_id=tenant_id)
    vehicle = await _make_vehicle(session, tenant_id=tenant_id)
    await _make_open_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)
    from app.core.security import create_access_token

    driver_headers = {
        "Authorization": f"Bearer {create_access_token(user_id=driver.id, tenant_id=tenant_id, role='driver')}"
    }
    plot_resp = await client.post(f"/v1/zones/{zone_id}/plot", headers=driver_headers)
    assert plot_resp.status_code == 200, plot_resp.text
    unplot_resp = await client.post("/v1/zones/unplot", headers=driver_headers)
    assert unplot_resp.status_code == 200


# ==================================================================================
# Plot / unplot
# ==================================================================================


async def test_plot_without_active_shift_returns_409(client, session):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, role="admin")
    zone_resp = await _create_zone(client, session, admin_headers, _zone_payload())
    zone_id = zone_resp.json()["id"]

    driver_headers = await auth_headers(client, session, role="driver", tenant_id=tenant_id)
    resp = await client.post(f"/v1/zones/{zone_id}/plot", headers=driver_headers)
    assert resp.status_code == 409


async def test_plot_and_unplot_roundtrip(client, session):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, role="admin")
    zone_resp = await _create_zone(client, session, admin_headers, _zone_payload())
    zone_id = zone_resp.json()["id"]

    driver = await _make_driver(session, tenant_id=tenant_id)
    vehicle = await _make_vehicle(session, tenant_id=tenant_id)
    await _make_open_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)

    driver_token_headers = {
        "Authorization": f"Bearer {__import__('app.core.security', fromlist=['create_access_token']).create_access_token(user_id=driver.id, tenant_id=tenant_id, role='driver')}"
    }

    plot_resp = await client.post(f"/v1/zones/{zone_id}/plot", headers=driver_token_headers)
    assert plot_resp.status_code == 200, plot_resp.text
    body = plot_resp.json()
    assert body["driver_id"] == driver.id
    assert body["plotted_zone_id"] == zone_id
    assert body["plotted_at"] is not None

    unplot_resp = await client.post("/v1/zones/unplot", headers=driver_token_headers)
    assert unplot_resp.status_code == 200
    unplot_body = unplot_resp.json()
    assert unplot_body["plotted_zone_id"] is None
    assert unplot_body["plotted_at"] is None


async def test_plotting_into_new_zone_clears_old_zone(client, session):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, role="admin")
    zone1_resp = await _create_zone(client, session, admin_headers, _zone_payload(number="1"))
    zone2_resp = await _create_zone(client, session, admin_headers, _zone_payload(number="2", name="Airport"))
    zone1_id = zone1_resp.json()["id"]
    zone2_id = zone2_resp.json()["id"]

    driver = await _make_driver(session, tenant_id=tenant_id)
    vehicle = await _make_vehicle(session, tenant_id=tenant_id)
    await _make_open_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)

    from app.core.security import create_access_token

    driver_headers = {
        "Authorization": f"Bearer {create_access_token(user_id=driver.id, tenant_id=tenant_id, role='driver')}"
    }

    resp1 = await client.post(f"/v1/zones/{zone1_id}/plot", headers=driver_headers)
    assert resp1.json()["plotted_zone_id"] == zone1_id

    resp2 = await client.post(f"/v1/zones/{zone2_id}/plot", headers=driver_headers)
    assert resp2.status_code == 200
    assert resp2.json()["plotted_zone_id"] == zone2_id


async def test_plot_into_nonexistent_zone_404s(client, session):
    tenant_id, _admin_headers = await _tenant_and_headers(client, session, role="admin")
    driver = await _make_driver(session, tenant_id=tenant_id)
    vehicle = await _make_vehicle(session, tenant_id=tenant_id)
    await _make_open_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)

    from app.core.security import create_access_token

    driver_headers = {
        "Authorization": f"Bearer {create_access_token(user_id=driver.id, tenant_id=tenant_id, role='driver')}"
    }
    resp = await client.post(f"/v1/zones/{uuid.uuid4()}/plot", headers=driver_headers)
    assert resp.status_code == 404


# ==================================================================================
# Live stats aggregation
# ==================================================================================


async def test_zone_stats_plotted_vehicles(client, session):
    from app.core.security import create_access_token

    tenant_id, admin_headers = await _tenant_and_headers(client, session, role="admin")
    zone_resp = await _create_zone(client, session, admin_headers, _zone_payload())
    zone_id = zone_resp.json()["id"]

    driver = await _make_driver(session, tenant_id=tenant_id)
    vehicle = await _make_vehicle(session, tenant_id=tenant_id)
    await _make_open_shift(session, tenant_id=tenant_id, driver_id=driver.id, vehicle_id=vehicle.id)
    driver_headers = {
        "Authorization": f"Bearer {create_access_token(user_id=driver.id, tenant_id=tenant_id, role='driver')}"
    }
    await client.post(f"/v1/zones/{zone_id}/plot", headers=driver_headers)

    resp = await client.get("/v1/zones/stats", headers=admin_headers)
    assert resp.status_code == 200
    stats = {row["zone_id"]: row for row in resp.json()}
    assert stats[zone_id]["plotted_vehicles"] == 1
    assert stats[zone_id]["vacant_vehicles"] == 0
    assert stats[zone_id]["busy_vehicles"] == 0


async def test_zone_stats_vacant_and_busy_vehicles_from_live_positions(client, session):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, role="admin")
    zone_resp = await _create_zone(client, session, admin_headers, _zone_payload())
    zone_id = zone_resp.json()["id"]

    vacant_vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-VACANT")
    busy_vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-BUSY")
    outside_vehicle = await _make_vehicle(session, tenant_id=tenant_id, rego="TX-OUTSIDE")

    await client.post(
        "/v1/fleet/positions",
        json={
            "vehicle_id": vacant_vehicle.id,
            "lat": _INSIDE["lat"],
            "lng": _INSIDE["lng"],
            "status": "active",
        },
        headers=admin_headers,
    )
    await client.post(
        "/v1/fleet/positions",
        json={
            "vehicle_id": busy_vehicle.id,
            "lat": _INSIDE["lat"],
            "lng": _INSIDE["lng"],
            "status": "on_trip",
        },
        headers=admin_headers,
    )
    await client.post(
        "/v1/fleet/positions",
        json={
            "vehicle_id": outside_vehicle.id,
            "lat": _OUTSIDE["lat"],
            "lng": _OUTSIDE["lng"],
            "status": "active",
        },
        headers=admin_headers,
    )

    resp = await client.get("/v1/zones/stats", headers=admin_headers)
    assert resp.status_code == 200
    stats = {row["zone_id"]: row for row in resp.json()}
    assert stats[zone_id]["vacant_vehicles"] == 1
    assert stats[zone_id]["busy_vehicles"] == 1


async def test_zone_stats_jobs_holding_and_trip_counts(client, session):
    tenant_id, admin_headers = await _tenant_and_headers(client, session, role="admin")
    zone_resp = await _create_zone(client, session, admin_headers, _zone_payload())
    zone_id = zone_resp.json()["id"]

    # A queued job with a pickup inside the zone counts toward jobs_holding;
    # an accepted job (terminal, no longer "holding") does not, even with a
    # pickup inside the zone.
    session.add(
        Job(
            tenant_id=tenant_id,
            origin_lat=_INSIDE["lat"],
            origin_lng=_INSIDE["lng"],
            origin_address="1 Test St",
            dest_lat=_OUTSIDE["lat"],
            dest_lng=_OUTSIDE["lng"],
            dest_address="2 Test St",
            status="queued",
            fare_estimate_low=Decimal("10.00"),
            fare_estimate_high=Decimal("15.00"),
            requested_at=datetime.now(UTC),
        )
    )
    session.add(
        Job(
            tenant_id=tenant_id,
            origin_lat=_INSIDE["lat"],
            origin_lng=_INSIDE["lng"],
            origin_address="1 Test St",
            dest_lat=_OUTSIDE["lat"],
            dest_lng=_OUTSIDE["lng"],
            dest_address="2 Test St",
            status="accepted",
            fare_estimate_low=Decimal("10.00"),
            fare_estimate_high=Decimal("15.00"),
            requested_at=datetime.now(UTC),
        )
    )
    # A queued job with a pickup outside the zone doesn't count.
    session.add(
        Job(
            tenant_id=tenant_id,
            origin_lat=_OUTSIDE["lat"],
            origin_lng=_OUTSIDE["lng"],
            origin_address="3 Test St",
            dest_lat=_OUTSIDE["lat"],
            dest_lng=_OUTSIDE["lng"],
            dest_address="4 Test St",
            status="queued",
            fare_estimate_low=Decimal("10.00"),
            fare_estimate_high=Decimal("15.00"),
            requested_at=datetime.now(UTC),
        )
    )

    now = datetime.now(UTC)
    two_hours_ago = now - timedelta(hours=2)

    def _trip(**overrides):
        base = dict(
            tenant_id=tenant_id,
            client_uuid=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            driver_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            status="closed",
            start_at=now,
            start_lat=_INSIDE["lat"],
            start_lng=_INSIDE["lng"],
            total=Decimal("20.00"),
        )
        base.update(overrides)
        return Trip(**base)

    session.add(_trip(type="booked"))  # counts: booking, inside zone, recent
    session.add(_trip(type="rank_hail"))  # counts: street hail, inside zone, recent
    session.add(_trip(type="booked", start_lat=_OUTSIDE["lat"], start_lng=_OUTSIDE["lng"]))  # outside zone
    session.add(_trip(type="booked", start_at=two_hours_ago))  # too old

    await session.commit()

    resp = await client.get("/v1/zones/stats", headers=admin_headers)
    assert resp.status_code == 200
    stats = {row["zone_id"]: row for row in resp.json()}
    assert stats[zone_id]["jobs_holding"] == 1
    assert stats[zone_id]["bookings_last_hour"] == 1
    assert stats[zone_id]["street_hails_last_hour"] == 1


async def test_zone_stats_empty_when_no_zones(client, session):
    _tenant_id, headers = await _tenant_and_headers(client, session, role="admin")
    resp = await client.get("/v1/zones/stats", headers=headers)
    assert resp.status_code == 200
    assert resp.json() == []