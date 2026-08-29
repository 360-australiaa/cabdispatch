"""Tests for the WP-24 endpoint half -- roster CRUD on top of the
VehicleAssignment model (Stage 1) via the real HTTP endpoints:
POST/GET /v1/fleet/vehicles/{id}/roster and
DELETE /v1/fleet/vehicles/{id}/roster/{driver_id}.

See app.models.vehicle_assignment and app.services.fleet
(authorise_driver/list_active_roster/revoke_authorisation) for the design
rationale. tests/test_vehicle_assignment.py already covers the model/index
layer directly -- this file proves the same guarantees hold end-to-end
through the API (tenant isolation, role gating, duplicate/404 handling).
"""
from __future__ import annotations

import uuid

import pytest
from sqlalchemy import select

from app.models.user import User
from app.models.vehicle_assignment import VehicleAssignment
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


async def _create_vehicle(client, headers, **overrides):
    payload = {
        "rego": overrides.pop("rego", f"R-{uuid.uuid4().hex[:6].upper()}"),
        "vehicle_class": overrides.pop("vehicle_class", "standard"),
        **overrides,
    }
    resp = await client.post("/v1/fleet/vehicles", json=payload, headers=headers)
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _make_user(session, tenant_id: str, *, role: str) -> User:
    user = User(
        tenant_id=tenant_id,
        role=role,
        name=f"Test {role.capitalize()}",
        email=f"{uuid.uuid4()}@example.com",
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


# --- authorise (POST) --------------------------------------------------------


async def test_authorise_driver_creates_roster_row(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Roster Tenant A")
    vehicle = await _create_vehicle(client, headers)
    driver = await _make_user(session, vehicle["tenant_id"], role="driver")

    resp = await client.post(
        f"/v1/fleet/vehicles/{vehicle['id']}/roster",
        json={"driver_id": driver.id},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["vehicle_id"] == vehicle["id"]
    assert body["driver_id"] == driver.id
    assert body["revoked_at"] is None
    assert body["authorised_by_user_id"] is not None

    result = await session.execute(
        select(VehicleAssignment).where(VehicleAssignment.id == body["id"])
    )
    row = result.scalar_one()
    assert row.vehicle_id == vehicle["id"]
    assert row.driver_id == driver.id
    assert row.revoked_at is None


async def test_duplicate_active_authorisation_is_409(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Roster Tenant B")
    vehicle = await _create_vehicle(client, headers)
    driver = await _make_user(session, vehicle["tenant_id"], role="driver")

    first = await client.post(
        f"/v1/fleet/vehicles/{vehicle['id']}/roster",
        json={"driver_id": driver.id},
        headers=headers,
    )
    assert first.status_code == 201

    second = await client.post(
        f"/v1/fleet/vehicles/{vehicle['id']}/roster",
        json={"driver_id": driver.id},
        headers=headers,
    )
    assert second.status_code == 409


async def test_authorising_non_driver_role_is_rejected(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Roster Tenant C")
    vehicle = await _create_vehicle(client, headers)
    dispatcher = await _make_user(session, vehicle["tenant_id"], role="dispatcher")

    resp = await client.post(
        f"/v1/fleet/vehicles/{vehicle['id']}/roster",
        json={"driver_id": dispatcher.id},
        headers=headers,
    )
    assert resp.status_code == 422


# --- list (GET) ---------------------------------------------------------------


async def test_roster_list_shows_only_active_authorisations(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Roster Tenant D")
    vehicle = await _create_vehicle(client, headers)
    driver_active = await _make_user(session, vehicle["tenant_id"], role="driver")
    driver_revoked = await _make_user(session, vehicle["tenant_id"], role="driver")

    for driver in (driver_active, driver_revoked):
        resp = await client.post(
            f"/v1/fleet/vehicles/{vehicle['id']}/roster",
            json={"driver_id": driver.id},
            headers=headers,
        )
        assert resp.status_code == 201

    revoke_resp = await client.delete(
        f"/v1/fleet/vehicles/{vehicle['id']}/roster/{driver_revoked.id}",
        headers=headers,
    )
    assert revoke_resp.status_code == 200

    list_resp = await client.get(f"/v1/fleet/vehicles/{vehicle['id']}/roster", headers=headers)
    assert list_resp.status_code == 200
    driver_ids = {row["driver_id"] for row in list_resp.json()}
    assert driver_ids == {driver_active.id}


# --- revoke then re-authorise --------------------------------------------------


async def test_revoke_then_reauthorise_same_pair_succeeds(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Roster Tenant E")
    vehicle = await _create_vehicle(client, headers)
    driver = await _make_user(session, vehicle["tenant_id"], role="driver")

    first = await client.post(
        f"/v1/fleet/vehicles/{vehicle['id']}/roster",
        json={"driver_id": driver.id},
        headers=headers,
    )
    assert first.status_code == 201

    revoke = await client.request(
        "DELETE",
        f"/v1/fleet/vehicles/{vehicle['id']}/roster/{driver.id}",
        json={"reason": "roster change"},
        headers=headers,
    )
    assert revoke.status_code == 200
    assert revoke.json()["revoked_at"] is not None
    assert revoke.json()["revoked_reason"] == "roster change"

    # Revoking again with nothing active is 404.
    revoke_again = await client.delete(
        f"/v1/fleet/vehicles/{vehicle['id']}/roster/{driver.id}",
        headers=headers,
    )
    assert revoke_again.status_code == 404

    second = await client.post(
        f"/v1/fleet/vehicles/{vehicle['id']}/roster",
        json={"driver_id": driver.id},
        headers=headers,
    )
    assert second.status_code == 201
    assert second.json()["id"] != first.json()["id"]
    assert second.json()["revoked_at"] is None

    list_resp = await client.get(f"/v1/fleet/vehicles/{vehicle['id']}/roster", headers=headers)
    assert [row["driver_id"] for row in list_resp.json()] == [driver.id]


# --- tenant isolation -----------------------------------------------------------


async def test_tenant_isolation_across_all_roster_routes(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Roster Tenant F1")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Roster Tenant F2")

    vehicle_a = await _create_vehicle(client, headers_a)
    driver_a = await _make_user(session, vehicle_a["tenant_id"], role="driver")

    authorise = await client.post(
        f"/v1/fleet/vehicles/{vehicle_a['id']}/roster",
        json={"driver_id": driver_a.id},
        headers=headers_a,
    )
    assert authorise.status_code == 201

    # Tenant B cannot see, mutate, or authorise against tenant A's vehicle.
    get_as_b = await client.get(f"/v1/fleet/vehicles/{vehicle_a['id']}/roster", headers=headers_b)
    assert get_as_b.status_code == 404

    post_as_b = await client.post(
        f"/v1/fleet/vehicles/{vehicle_a['id']}/roster",
        json={"driver_id": driver_a.id},
        headers=headers_b,
    )
    assert post_as_b.status_code == 404

    delete_as_b = await client.delete(
        f"/v1/fleet/vehicles/{vehicle_a['id']}/roster/{driver_a.id}",
        headers=headers_b,
    )
    assert delete_as_b.status_code == 404

    # Tenant B also cannot authorise its own vehicle against tenant A's driver
    # (driver lookup is tenant-scoped -- DriverNotFoundError -> 404).
    vehicle_b = await _create_vehicle(client, headers_b)
    post_cross_driver = await client.post(
        f"/v1/fleet/vehicles/{vehicle_b['id']}/roster",
        json={"driver_id": driver_a.id},
        headers=headers_b,
    )
    assert post_cross_driver.status_code == 404