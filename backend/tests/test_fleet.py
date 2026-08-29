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
    assert body["status"] == "draft"  # new vehicles start in draft (plan D-4 lifecycle)
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


# --- MDM-lite remote commands: locate / reboot (blueprint 4.1.3/6.2.1) -----------


async def test_locate_is_admin_only_and_visible_on_heartbeat(client, session):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="Locate Tenant")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=None)

    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-locate-1"}, headers=admin_headers
    )
    device = resp.json()
    assert device["locate_requested"] is False
    device_id = device["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/locate", json={"enabled": True}, headers=driver_headers
    )
    assert resp.status_code == 403

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/locate", json={"enabled": True}, headers=admin_headers
    )
    assert resp.status_code == 200
    assert resp.json()["locate_requested"] is True

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={}, headers=admin_headers
    )
    assert resp.status_code == 200
    assert resp.json()["locate_requested"] is True

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/locate", json={"enabled": False}, headers=admin_headers
    )
    assert resp.json()["locate_requested"] is False


async def test_reboot_is_admin_only_and_visible_on_heartbeat(client, session):
    admin_headers = await auth_headers(client, session, role="admin", tenant_name="Reboot Tenant")
    driver_headers = await auth_headers(client, session, role="driver", tenant_id=None)

    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-reboot-1"}, headers=admin_headers
    )
    device = resp.json()
    assert device["reboot_requested"] is False
    device_id = device["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/reboot", json={"enabled": True}, headers=driver_headers
    )
    assert resp.status_code == 403

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/reboot", json={"enabled": True}, headers=admin_headers
    )
    assert resp.status_code == 200
    assert resp.json()["reboot_requested"] is True

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={}, headers=admin_headers
    )
    assert resp.status_code == 200
    assert resp.json()["reboot_requested"] is True

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/reboot", json={"enabled": False}, headers=admin_headers
    )
    assert resp.json()["reboot_requested"] is False


async def test_locate_and_reboot_flags_are_independent(client, session):
    """Setting one MDM-lite command flag must not disturb the other, or the
    pre-existing kiosk_locked/force_update_pending flags."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Independent Flags Tenant")
    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-independent-1"}, headers=headers
    )
    device_id = resp.json()["id"]

    await client.post(f"/v1/fleet/devices/{device_id}/locate", json={"enabled": True}, headers=headers)

    resp = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    body = resp.json()
    assert body["locate_requested"] is True
    assert body["reboot_requested"] is False
    assert body["kiosk_locked"] is False
    assert body["force_update_pending"] is False


# --- POST /devices manual provisioning opens a real DeviceAssignment (D-3 gap fix) ---
# Regression coverage for the gap flagged after WP-21/22: manually pre-provisioning
# a device via POST /devices with vehicle_id set used to write Device.vehicle_id
# directly with no DeviceAssignment row at all, so assert_vehicle_operational's
# "a meter is currently assigned" gate (which reads DeviceAssignment, not
# Device.vehicle_id) would never see it, and the one-active-meter-per-vehicle
# guarantee did not hold for this creation path. Fixed in
# app.services.fleet.create_device.


async def test_create_device_with_vehicle_opens_real_assignment(client, session):
    from sqlalchemy import select

    from app.models.device_assignment import DeviceAssignment

    headers = await auth_headers(client, session, role="admin", tenant_name="Manual Provision Tenant")
    vehicle_resp = await _create_vehicle(client, headers, rego="TX-MANUAL-1")
    vehicle_id = vehicle_resp.json()["id"]

    resp = await client.post(
        "/v1/fleet/devices",
        json={"android_id": "android-manual-1", "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    device_id = resp.json()["id"]
    assert resp.json()["vehicle_id"] == vehicle_id

    result = await session.execute(
        select(DeviceAssignment).where(
            DeviceAssignment.device_id == device_id,
            DeviceAssignment.unbound_at.is_(None),
        )
    )
    assignment = result.scalar_one()
    assert assignment.vehicle_id == vehicle_id
    assert assignment.pairing_code_id is None
    assert assignment.bound_by_user_id is not None


async def test_create_device_without_vehicle_opens_no_assignment(client, session):
    from sqlalchemy import select

    from app.models.device_assignment import DeviceAssignment

    headers = await auth_headers(client, session, role="admin", tenant_name="Manual Provision No Vehicle Tenant")

    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-manual-2"}, headers=headers
    )
    assert resp.status_code == 201, resp.text
    device_id = resp.json()["id"]

    result = await session.execute(
        select(DeviceAssignment).where(DeviceAssignment.device_id == device_id)
    )
    assert result.scalar_one_or_none() is None


async def test_create_device_rejects_vehicle_with_active_device(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Manual Provision Conflict Tenant")
    vehicle_resp = await _create_vehicle(client, headers, rego="TX-MANUAL-3")
    vehicle_id = vehicle_resp.json()["id"]

    first = await client.post(
        "/v1/fleet/devices",
        json={"android_id": "android-manual-3a", "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert first.status_code == 201, first.text

    second = await client.post(
        "/v1/fleet/devices",
        json={"android_id": "android-manual-3b", "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert second.status_code == 409, second.text
    assert "already has an actively assigned device" in second.json()["detail"]


async def test_create_device_after_active_assignment_moves_away_frees_the_vehicle(client, session):
    """Once the device that was actively assigned to a vehicle gets RE-PAIRED
    to a DIFFERENT vehicle (via the real POST /devices/register flow, which
    closes the old assignment), the original vehicle has no active assignment
    any more -- manual provisioning against it is not permanently blocked by
    history, only by a currently-ACTIVE assignment."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Manual Provision Repair Tenant")
    vehicle_one = (await _create_vehicle(client, headers, rego="TX-MANUAL-4")).json()["id"]
    vehicle_two = (await _create_vehicle(client, headers, rego="TX-MANUAL-5")).json()["id"]

    first = await client.post(
        "/v1/fleet/devices",
        json={"android_id": "android-manual-4a", "vehicle_id": vehicle_one},
        headers=headers,
    )
    assert first.status_code == 201, first.text

    # Re-pair the SAME device to vehicle_two -- closes its active assignment
    # on vehicle_one, opens a new one on vehicle_two. vehicle_one is now free.
    pairing_resp = await client.post(
        f"/v1/fleet/vehicles/{vehicle_two}/pairing-code", headers=headers
    )
    pairing_code = pairing_resp.json()["code"]
    register_resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-manual-4a", "pairing_code": pairing_code},
        headers=headers,
    )
    assert register_resp.status_code == 200, register_resp.text
    assert register_resp.json()["vehicle_id"] == vehicle_two

    second = await client.post(
        "/v1/fleet/devices",
        json={"android_id": "android-manual-4b", "vehicle_id": vehicle_one},
        headers=headers,
    )
    assert second.status_code == 201, second.text

# --- device-scoped heartbeat auth (real production gap found by the Android session) ---
# Regression coverage for: POST /devices/{id}/heartbeat used to require a driver bearer
# token unconditionally, so a parked/logged-off/rebooted tablet with no signed-in driver
# could never receive kiosk-lock/force-update/locate commands at all. Fixed by accepting
# EITHER a bearer token (unchanged, existing behaviour) OR a device secret issued once by
# POST /devices/register, presented via the X-Device-Secret header.


async def test_register_device_returns_a_one_time_device_secret(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Device Secret Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-SECRET-1")).json()["id"]
    pairing_code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]

    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-secret-1", "pairing_code": pairing_code},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert "device_secret" in body
    assert isinstance(body["device_secret"], str)
    assert len(body["device_secret"]) >= 32


async def test_heartbeat_with_device_secret_needs_no_bearer_token(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Device Secret Tenant 2")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-SECRET-2")).json()["id"]
    pairing_code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]
    register_resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-secret-2", "pairing_code": pairing_code},
        headers=headers,
    )
    device_id = register_resp.json()["id"]
    device_secret = register_resp.json()["device_secret"]

    # No Authorization header at all -- exactly the parked/logged-off/rebooted case.
    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"battery": 42, "network": "wifi", "app_version": "1.2.3"},
        headers={"X-Device-Secret": device_secret},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["battery"] == 42
    assert body["id"] == device_id


async def test_heartbeat_with_wrong_device_secret_is_rejected(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Device Secret Tenant 3")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-SECRET-3")).json()["id"]
    pairing_code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]
    register_resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-secret-3", "pairing_code": pairing_code},
        headers=headers,
    )
    device_id = register_resp.json()["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"app_version": "1.0.0"},
        headers={"X-Device-Secret": "totally-wrong-secret"},
    )
    assert resp.status_code == 401


async def test_heartbeat_with_no_credentials_at_all_is_rejected(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Device Secret Tenant 4")
    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-secret-4"}, headers=headers
    )
    device_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"app_version": "1.0.0"},
    )
    assert resp.status_code == 401


async def test_heartbeat_bearer_path_still_works_for_devices_with_no_secret(client, session):
    """Devices created via POST /devices (manual pre-provisioning, no register_device
    call, no secret issued) must keep working over the existing bearer-token path --
    this is the pre-existing, unmodified heartbeat behaviour."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Device Secret Tenant 5")
    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-secret-5"}, headers=headers
    )
    device_id = resp.json()["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"battery": 55, "app_version": "1.0.0"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["battery"] == 55


async def test_repairing_rotates_the_device_secret(client, session):
    """Re-pairing a device (moving it to a different vehicle) issues a FRESH secret --
    the old one must stop working, same "swapping invalidates the old credential"
    property D-3 already gives DeviceAssignment history."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Device Secret Tenant 6")
    vehicle_one = (await _create_vehicle(client, headers, rego="TX-SECRET-6A")).json()["id"]
    vehicle_two = (await _create_vehicle(client, headers, rego="TX-SECRET-6B")).json()["id"]

    code_one = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_one}/pairing-code", headers=headers)
    ).json()["code"]
    first_register = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-secret-6", "pairing_code": code_one},
        headers=headers,
    )
    device_id = first_register.json()["id"]
    old_secret = first_register.json()["device_secret"]

    code_two = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_two}/pairing-code", headers=headers)
    ).json()["code"]
    second_register = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-secret-6", "pairing_code": code_two},
        headers=headers,
    )
    new_secret = second_register.json()["device_secret"]
    assert new_secret != old_secret

    old_secret_resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"app_version": "1.0.0"},
        headers={"X-Device-Secret": old_secret},
    )
    assert old_secret_resp.status_code == 401

    new_secret_resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"app_version": "1.0.0"},
        headers={"X-Device-Secret": new_secret},
    )
    assert new_secret_resp.status_code == 200