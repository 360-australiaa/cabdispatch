"""Tests for the fleet domain (vehicles + devices).

NOTE: `app/api/v1/fleet.py`'s router is not registered in `app.main` yet — a
later integration step does that. Until then, every request in this file 404s
(FastAPI has no route for it) rather than exercising real behaviour. That is
expected; these tests are written correctly against the endpoints as built so
they pass once the integration step wires `fleet_router` into `app.main`.

Importing `app.models.fleet` below (even though nothing else in this file uses
the names directly) is required so those tables land on `Base.metadata` before
the session-scoped `_test_database` fixture in conftest.py runs `create_all` —
`app/models/__init__.py` doesn't import this domain's models yet either, for
the same "integration step wires it up" reason.
"""
from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from app.models.fleet import Device, DevicePairingCode, Vehicle  # noqa: F401
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- helpers ------------------------------------------------------------------


async def _create_vehicle(client, headers, **overrides):
    payload = {
        "rego": overrides.pop("rego", "ABC123"),
        "vehicle_class": overrides.pop("vehicle_class", "standard"),
        **overrides,
    }
    return await client.post("/v1/fleet/vehicles", json=payload, headers=headers)


# --- vehicles: CRUD -------------------------------------------------------------


async def test_create_and_get_vehicle(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_vehicle(client, headers, rego="tx-001", vin="1HGCM82633A004352")
    assert resp.status_code == 201
    body = resp.json()
    assert body["rego"] == "TX-001"  # normalized upper-case
    assert body["status"] == "active"
    assert body["vehicle_class"] == "standard"
    vehicle_id = body["id"]

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["id"] == vehicle_id


async def test_create_vehicle_requires_admin_role(client, session):
    headers = await auth_headers(client, session, role="driver")

    resp = await _create_vehicle(client, headers, rego="TX-777")
    assert resp.status_code == 403


async def test_create_vehicle_duplicate_rego_conflicts(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_vehicle(client, headers, rego="TX-DUPE")
    assert resp.status_code == 201

    resp = await _create_vehicle(client, headers, rego="tx-dupe")  # same after normalization
    assert resp.status_code == 409


async def test_same_rego_allowed_across_different_tenants(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    resp_a = await _create_vehicle(client, headers_a, rego="TX-SHARED")
    resp_b = await _create_vehicle(client, headers_b, rego="TX-SHARED")
    assert resp_a.status_code == 201
    assert resp_b.status_code == 201


async def test_vehicle_is_tenant_isolated(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B")

    resp = await _create_vehicle(client, headers_a, rego="TX-ISOLATED")
    vehicle_id = resp.json()["id"]

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers_b)
    assert resp.status_code == 404


async def test_list_vehicles_pagination_and_filtering(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Tenant Listing")

    for i in range(3):
        await _create_vehicle(client, headers, rego=f"TX-L{i}", vehicle_class="maxi")
    await _create_vehicle(client, headers, rego="TX-STD", vehicle_class="standard")

    resp = await client.get("/v1/fleet/vehicles?limit=2&skip=0", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 4
    assert len(body["items"]) == 2

    resp = await client.get("/v1/fleet/vehicles?vehicle_class=maxi", headers=headers)
    body = resp.json()
    assert body["total"] == 3
    assert all(v["vehicle_class"] == "maxi" for v in body["items"])

    resp = await client.get("/v1/fleet/vehicles?rego=std", headers=headers)
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["rego"] == "TX-STD"


async def test_update_vehicle_status(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-UPD")
    vehicle_id = resp.json()["id"]

    resp = await client.patch(
        f"/v1/fleet/vehicles/{vehicle_id}", json={"status": "maintenance"}, headers=headers
    )
    assert resp.status_code == 200
    assert resp.json()["status"] == "maintenance"


async def test_update_vehicle_rego_conflict(client, session):
    headers = await auth_headers(client, session, role="admin")
    await _create_vehicle(client, headers, rego="TX-TAKEN")
    resp = await _create_vehicle(client, headers, rego="TX-FREE")
    vehicle_id = resp.json()["id"]

    resp = await client.patch(
        f"/v1/fleet/vehicles/{vehicle_id}", json={"rego": "TX-TAKEN"}, headers=headers
    )
    assert resp.status_code == 409


async def test_delete_vehicle_unbinds_devices(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-DEL")
    vehicle_id = resp.json()["id"]

    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-del-1", "vehicle_id": vehicle_id}, headers=headers
    )
    assert resp.status_code == 201
    device_id = resp.json()["id"]

    resp = await client.delete(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 204

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 404

    resp = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["vehicle_id"] is None


# --- devices: CRUD --------------------------------------------------------------


async def test_create_get_update_delete_device(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post("/v1/fleet/devices", json={"android_id": "android-crud-1"}, headers=headers)
    assert resp.status_code == 201
    device = resp.json()
    assert device["kiosk_locked"] is False
    assert device["force_update_pending"] is False
    device_id = device["id"]

    resp = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.status_code == 200

    resp = await client.patch(
        f"/v1/fleet/devices/{device_id}", json={"model": "Pixel Tablet"}, headers=headers
    )
    assert resp.status_code == 200
    assert resp.json()["model"] == "Pixel Tablet"

    resp = await client.delete(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.status_code == 204

    resp = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.status_code == 404


async def test_list_devices_filter_by_vehicle_and_lock_state(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Device List Tenant")
    resp = await _create_vehicle(client, headers, rego="TX-DL")
    vehicle_id = resp.json()["id"]

    await client.post(
        "/v1/fleet/devices", json={"android_id": "android-dl-1", "vehicle_id": vehicle_id}, headers=headers
    )
    await client.post("/v1/fleet/devices", json={"android_id": "android-dl-2"}, headers=headers)

    resp = await client.get(f"/v1/fleet/devices?vehicle_id={vehicle_id}", headers=headers)
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["android_id"] == "android-dl-1"

    resp = await client.get("/v1/fleet/devices?kiosk_locked=false", headers=headers)
    assert resp.json()["total"] == 2


# --- QR pairing -----------------------------------------------------------------


async def test_pairing_code_flow_binds_device_to_vehicle(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-PAIR")
    vehicle_id = resp.json()["id"]

    resp = await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    assert resp.status_code == 200
    pairing = resp.json()
    assert pairing["vehicle_id"] == vehicle_id
    code = pairing["code"]

    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-pair-1", "pairing_code": code, "app_version": "1.0.0"},
        headers=headers,
    )
    assert resp.status_code == 200
    device = resp.json()
    assert device["vehicle_id"] == vehicle_id
    assert device["android_id"] == "android-pair-1"
    assert device["app_version"] == "1.0.0"
    assert device["last_seen_at"] is not None


async def test_pairing_code_cannot_be_reused(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-REUSE")
    vehicle_id = resp.json()["id"]

    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]

    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-reuse-1", "pairing_code": code},
        headers=headers,
    )
    assert resp.status_code == 200

    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-reuse-2", "pairing_code": code},
        headers=headers,
    )
    assert resp.status_code == 400


async def test_invalid_pairing_code_rejected(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-bad-code", "pairing_code": "NOTREAL1"},
        headers=headers,
    )
    assert resp.status_code == 400


async def test_expired_pairing_code_rejected(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-EXPIRE")
    vehicle_id = resp.json()["id"]

    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]

    # Force the freshly-minted code into the past directly via the DB.
    from sqlalchemy import select

    result = await session.execute(select(DevicePairingCode).where(DevicePairingCode.code == code))
    pairing = result.scalar_one()
    pairing.expires_at = datetime.now(UTC) - timedelta(minutes=1)
    await session.commit()

    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-expired-1", "pairing_code": code},
        headers=headers,
    )
    assert resp.status_code == 400


async def test_pairing_code_is_tenant_scoped(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Pairing Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Pairing Tenant B")

    resp = await _create_vehicle(client, headers_a, rego="TX-SCOPE")
    vehicle_id = resp.json()["id"]
    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers_a)
    ).json()["code"]

    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-scope-1", "pairing_code": code},
        headers=headers_b,
    )
    assert resp.status_code == 400


async def test_pairing_code_requires_admin_role(client, session):
    """require_role checks the caller's role before the handler body ever runs,
    so a nonexistent vehicle_id is fine here — the 403 fires first regardless."""
    headers = await auth_headers(client, session, role="dispatcher")
    resp = await client.post("/v1/fleet/vehicles/some-id/pairing-code", headers=headers)
    assert resp.status_code == 403


# --- heartbeat + admin flags -----------------------------------------------------


async def test_heartbeat_updates_status_fields_and_reports_flags(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.post("/v1/fleet/devices", json={"android_id": "android-hb-1"}, headers=headers)
    device_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"battery": 87, "network": "wifi", "app_version": "2.1.0"},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["battery"] == 87
    assert body["network"] == "wifi"
    assert body["app_version"] == "2.1.0"
    assert body["kiosk_locked"] is False
    assert body["force_update_pending"] is False
    assert body["last_seen_at"] is not None


async def test_kiosk_lock_is_admin_only_and_visible_on_heartbeat(client, session):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="Kiosk Tenant")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=None)

    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-kiosk-1"}, headers=admin_headers
    )
    device_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/kiosk-lock", json={"enabled": True}, headers=driver_headers
    )
    assert resp.status_code == 403

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/kiosk-lock", json={"enabled": True}, headers=admin_headers
    )
    assert resp.status_code == 200
    assert resp.json()["kiosk_locked"] is True

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={}, headers=admin_headers
    )
    assert resp.json()["kiosk_locked"] is True


async def test_force_update_is_admin_only_and_visible_on_heartbeat(client, session):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="Force Update Tenant")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=None)

    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-fu-1"}, headers=admin_headers
    )
    device_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/force-update", json={"enabled": True}, headers=driver_headers
    )
    assert resp.status_code == 403

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/force-update", json={"enabled": True}, headers=admin_headers
    )
    assert resp.status_code == 200
    assert resp.json()["force_update_pending"] is True

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={}, headers=admin_headers
    )
    assert resp.json()["force_update_pending"] is True

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/force-update", json={"enabled": False}, headers=admin_headers
    )
    assert resp.json()["force_update_pending"] is False
