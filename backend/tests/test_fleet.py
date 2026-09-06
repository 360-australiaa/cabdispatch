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

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from sqlalchemy import func, select

from app.models.fleet import (  # noqa: F401
    Device,
    DevicePairingCode,
    DeviceVersionHistory,
    Vehicle,
    VehiclePositionHistory,
)
from app.models.audit_log import AuditLog
from app.models.shift import Shift
from app.models.user import ROLE_DRIVER, User
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


async def test_create_vehicle_make_model_round_trips(client, session):
    headers = await auth_headers(client, session, role="admin")

    resp = await _create_vehicle(client, headers, rego="tx-002", make="Toyota", model="Camry")
    assert resp.status_code == 201
    body = resp.json()
    assert body["make"] == "Toyota"
    assert body["model"] == "Camry"
    vehicle_id = body["id"]

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["make"] == "Toyota"
    assert body["model"] == "Camry"


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


async def test_delete_vehicle_closes_open_shift_instead_of_leaving_it_dangling(client, session):
    """Real production bug regression test: deleting a vehicle with a driver
    still on an OPEN shift used to leave that shift open forever, pointing at
    a vehicle_id that no longer resolves to anything (Shift.vehicle_id has no
    FK — see app/models/shift.py's own DEVIATION note) — surfacing on the
    dashboard's drivers list as a raw UUID where a rego should be. The chosen
    fix CLOSES the open shift as part of the vehicle delete (see
    app.services.shift.close_open_shifts_for_vehicle_deletion's own
    docstring for why "close" was chosen over "refuse the delete") — marking
    it unreconciled with a zero psl_owed (honest: nobody actually reconciled
    it) and recording a tamper-evident audit-log entry explaining why."""
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-OPENSHIFT")
    vehicle_id = resp.json()["id"]
    driver_id = str(uuid.uuid4())

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_id, "vehicle_id": vehicle_id},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    shift_id = resp.json()["id"]
    assert resp.json()["end_at"] is None

    resp = await client.delete(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 204, resp.text

    # The vehicle is gone...
    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 404

    # ...but the shift that was open on it is now CLOSED, not dangling.
    resp = await client.get(f"/v1/shifts/{shift_id}", headers=headers)
    assert resp.status_code == 200
    shift_body = resp.json()
    assert shift_body["end_at"] is not None
    assert shift_body["reconciled"] is False
    assert Decimal(str(shift_body["psl_owed"])) == Decimal("0.00")
    # vehicle_id is left as-is on the now-closed historical shift row (same
    # "unconstrained cross-domain id, unaffected by the referent's deletion"
    # convention already documented on Trip/Shift) -- it's the OPEN-ness that
    # was the bug, not the stored id itself.
    assert shift_body["vehicle_id"] == vehicle_id

    # A tamper-evident audit-log entry explains why this shift closed when it did.
    result = await session.execute(
        select(AuditLog).where(
            AuditLog.entity_type == "shift",
            AuditLog.entity_id == shift_id,
            AuditLog.action == "shift_force_closed_vehicle_deleted",
        )
    )
    audit_rows = result.scalars().all()
    assert len(audit_rows) == 1
    assert audit_rows[0].after_json["reason"] == "vehicle_deleted"
    assert audit_rows[0].after_json["vehicle_id"] == vehicle_id


async def test_delete_vehicle_does_not_touch_already_closed_shifts(client, session):
    """Sanity check on the fix above: a shift that was already ended before
    the vehicle delete must be left completely alone (no re-closing, no
    audit-log noise) -- only genuinely OPEN shifts are in scope."""
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-CLOSEDSHIFT")
    vehicle_id = resp.json()["id"]
    driver_id = str(uuid.uuid4())

    resp = await client.post(
        "/v1/shifts/start", json={"driver_id": driver_id, "vehicle_id": vehicle_id}, headers=headers
    )
    shift_id = resp.json()["id"]
    resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "12.50", "reconciled": True},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    original_end_at = resp.json()["end_at"]

    resp = await client.delete(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 204

    resp = await client.get(f"/v1/shifts/{shift_id}", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["end_at"] == original_end_at
    assert body["reconciled"] is True
    assert Decimal(str(body["psl_owed"])) == Decimal("12.50")

    result = await session.execute(
        select(func.count())
        .select_from(AuditLog)
        .where(AuditLog.entity_type == "shift", AuditLog.entity_id == shift_id)
    )
    assert result.scalar_one() == 0


async def test_delete_vehicle_with_position_history_and_pairing_codes_succeeds(client, session):
    """Real-production bug regression test (see app.services.fleet's module
    docstring): postgres enforces the NOT NULL FKs from
    vehicle_position_history/device_pairing_codes to vehicles.id, and every
    real vehicle accumulates position-history rows from routine heartbeats —
    so this delete failed 100% of the time in production before the
    ondelete="CASCADE" fix (app/models/fleet.py). This only proves anything
    because tests/conftest.py's sqlite engine now enforces PRAGMA
    foreign_keys=ON (app.core.database) -- before that pass this exact test
    would have silently passed even with no ondelete= at all, the same way
    the 649-test suite already did in production."""
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-CASCADE")
    vehicle_id = resp.json()["id"]

    # Position history: a couple of heartbeat-style publishes.
    for lat in (-33.86, -33.87):
        resp = await client.post(
            "/v1/fleet/positions",
            json={"vehicle_id": vehicle_id, "lat": lat, "lng": 151.2, "status": "available"},
            headers=headers,
        )
        assert resp.status_code == 201

    # A used pairing code: device_pairing_codes.vehicle_id (CASCADE) and
    # .used_by_device_id (SET NULL, exercised by the device-delete test
    # below -- here the device is left alive so this row also carries a
    # live used_by_device_id right up until the vehicle delete).
    resp = await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    code = resp.json()["code"]
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-cascade-1", "pairing_code": code},
        headers=headers,
    )
    assert resp.status_code == 200
    device_id = resp.json()["id"]

    result = await session.execute(
        select(func.count()).select_from(VehiclePositionHistory).where(
            VehiclePositionHistory.vehicle_id == vehicle_id
        )
    )
    assert result.scalar_one() == 2
    result = await session.execute(
        select(func.count()).select_from(DevicePairingCode).where(DevicePairingCode.vehicle_id == vehicle_id)
    )
    assert result.scalar_one() == 1

    resp = await client.delete(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 204, resp.text

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}", headers=headers)
    assert resp.status_code == 404

    # Dependents didn't leak: cascaded away along with the vehicle.
    result = await session.execute(
        select(func.count()).select_from(VehiclePositionHistory).where(
            VehiclePositionHistory.vehicle_id == vehicle_id
        )
    )
    assert result.scalar_one() == 0
    result = await session.execute(
        select(func.count()).select_from(DevicePairingCode).where(DevicePairingCode.vehicle_id == vehicle_id)
    )
    assert result.scalar_one() == 0

    # The device that redeemed the pairing code is untouched (only unbound
    # from the now-deleted vehicle, per the pre-existing unlink behaviour).
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


async def test_delete_device_with_version_history_succeeds_and_unlinks_pairing_code(client, session):
    """Real-production bug regression test (see app.services.fleet's module
    docstring): postgres enforces device_version_history.device_id's NOT
    NULL FK, and every real device accumulates version-history rows from
    routine heartbeats -- so this delete failed 100% of the time in
    production before the ondelete="CASCADE" fix. Also covers
    device_pairing_codes.used_by_device_id's ondelete="SET NULL": deleting
    the device that redeemed a code must not delete the code row itself
    (the vehicle's pairing history is worth keeping), just null the
    now-dangling back-reference."""
    headers = await auth_headers(client, session, role="admin")
    resp = await _create_vehicle(client, headers, rego="TX-DEVCASCADE")
    vehicle_id = resp.json()["id"]

    resp = await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    code = resp.json()["code"]
    # Registering with an app_version stamps Device.app_version directly
    # (app.services.fleet.register_device) -- it does NOT itself append a
    # DeviceVersionHistory row; only a heartbeat with a *changed* app_version
    # does that (app.services.fleet.record_heartbeat). So the two heartbeats
    # below (None -> "1.0.0", then "1.0.0" -> "1.1.0") are what create the
    # two history rows asserted below, not the registration call.
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-devcascade-1", "pairing_code": code},
        headers=headers,
    )
    assert resp.status_code == 200
    device_id = resp.json()["id"]

    for app_version in ("1.0.0", "1.1.0"):
        resp = await client.post(
            f"/v1/fleet/devices/{device_id}/heartbeat",
            json={"app_version": app_version},
            headers=headers,
        )
        assert resp.status_code == 200

    result = await session.execute(
        select(func.count()).select_from(DeviceVersionHistory).where(
            DeviceVersionHistory.device_id == device_id
        )
    )
    assert result.scalar_one() == 2
    result = await session.execute(
        select(DevicePairingCode).where(DevicePairingCode.vehicle_id == vehicle_id)
    )
    pairing_row = result.scalar_one()
    assert pairing_row.used_by_device_id == device_id

    resp = await client.delete(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.status_code == 204, resp.text

    resp = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.status_code == 404

    # Version history didn't leak: cascaded away along with the device.
    result = await session.execute(
        select(func.count()).select_from(DeviceVersionHistory).where(
            DeviceVersionHistory.device_id == device_id
        )
    )
    assert result.scalar_one() == 0

    # The pairing code row survives (it's the vehicle's history, not the
    # device's) -- only its dangling used_by_device_id is nulled.
    await session.refresh(pairing_row)
    assert pairing_row.used_by_device_id is None
    assert pairing_row.vehicle_id == vehicle_id


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


async def test_heartbeat_carries_latest_version_code_hint(client, session):
    """POST .../heartbeat stamps `latest_version_code` from the current
    GET /v1/app-releases/latest answer (see app/api/v1/fleet.py's
    device_heartbeat) -- a low-cost hint riding the existing 60s poll.

    App releases are platform-wide, not tenant-scoped (see
    app.models.app_release.AppRelease's docstring), and this suite shares one
    DB across every test file in the session -- tests/test_app_releases.py may
    already have published releases by the time this runs, so this asserts
    the hint tracks a NEW highest version_code this test itself publishes,
    rather than assuming a None starting point no other test file's state can
    guarantee."""
    from app.core.security import PLATFORM_TENANT_ID
    from app.models.tenant import Tenant

    headers = await auth_headers(client, session, role="admin", tenant_name="OTA Hint Tenant")
    resp = await client.post(
        "/v1/fleet/devices", json={"android_id": "android-ota-hint-1"}, headers=headers
    )
    device_id = resp.json()["id"]

    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    if result.scalar_one_or_none() is None:
        session.add(Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform"))
        await session.commit()
    platform_headers = await auth_headers(client, session, role="owner", tenant_id=PLATFORM_TENANT_ID)
    # A very high version_code -- guaranteed higher than any other release any
    # sibling test file in this session publishes -- so this deterministically
    # becomes (and stays) the platform-wide "latest" for the rest of the run.
    resp = await client.post(
        "/v1/platform/app-releases",
        data={"version_code": "999999", "version_name": "99.99.99"},
        files={"file": ("r.apk", b"bytes", "application/vnd.android.package-archive")},
        headers=platform_headers,
    )
    assert resp.status_code == 201

    resp = await client.post(f"/v1/fleet/devices/{device_id}/heartbeat", json={}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["latest_version_code"] == 999999


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


# --- shift history: "which drivers has this vehicle had" -------------------------


async def _make_driver(session, *, tenant_id, name="Driver One"):
    driver = User(
        tenant_id=tenant_id, role=ROLE_DRIVER, name=name, email=f"{uuid.uuid4()}@example.com", status="active"
    )
    session.add(driver)
    await session.commit()
    await session.refresh(driver)
    return driver


async def _make_shift(
    session, *, tenant_id, driver_id, vehicle_id, start_at, end_at=None, km_total=Decimal("0"),
    cash_total=Decimal("0"), card_total=Decimal("0"),
):
    shift = Shift(
        tenant_id=tenant_id,
        driver_id=driver_id,
        vehicle_id=vehicle_id,
        start_at=start_at,
        end_at=end_at,
        km_total=km_total,
        cash_total=cash_total,
        card_total=card_total,
    )
    session.add(shift)
    await session.commit()
    await session.refresh(shift)
    return shift


async def test_vehicle_shift_history_returns_past_shifts_newest_first_with_driver_names(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Shift History Tenant")
    resp = await _create_vehicle(client, headers, rego="TX-HIST")
    vehicle_id = resp.json()["id"]

    # Resolve the tenant_id backing `headers` via a fresh vehicle lookup isn't
    # available directly, so pull it off the created vehicle's own response.
    tenant_id = resp.json()["tenant_id"]

    driver_a = await _make_driver(session, tenant_id=tenant_id, name="Alice Morning")
    driver_b = await _make_driver(session, tenant_id=tenant_id, name="Bob Evening")

    now = datetime.now(UTC)
    older_shift = await _make_shift(
        session,
        tenant_id=tenant_id,
        driver_id=driver_a.id,
        vehicle_id=vehicle_id,
        start_at=now - timedelta(hours=24),
        end_at=now - timedelta(hours=12),
        km_total=Decimal("120.500"),
        cash_total=Decimal("80.00"),
        card_total=Decimal("40.00"),
    )
    newer_shift = await _make_shift(
        session,
        tenant_id=tenant_id,
        driver_id=driver_b.id,
        vehicle_id=vehicle_id,
        start_at=now - timedelta(hours=11),
        end_at=now - timedelta(hours=1),
        km_total=Decimal("95.250"),
        cash_total=Decimal("50.00"),
        card_total=Decimal("60.00"),
    )

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/shift-history", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 2
    assert body["skip"] == 0
    assert body["limit"] == 20

    items = body["items"]
    assert len(items) == 2
    # Newest-first: driver B's more-recent shift comes before driver A's.
    assert items[0]["shift_id"] == newer_shift.id
    assert items[0]["driver_id"] == driver_b.id
    assert items[0]["driver_name"] == "Bob Evening"
    assert Decimal(str(items[0]["distance_km"])) == Decimal("95.250")
    assert Decimal(str(items[0]["fare_total"])) == Decimal("110.00")

    assert items[1]["shift_id"] == older_shift.id
    assert items[1]["driver_id"] == driver_a.id
    assert items[1]["driver_name"] == "Alice Morning"
    assert Decimal(str(items[1]["distance_km"])) == Decimal("120.500")
    assert Decimal(str(items[1]["fare_total"])) == Decimal("120.00")


async def test_vehicle_shift_history_paginates(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Shift History Paging Tenant")
    resp = await _create_vehicle(client, headers, rego="TX-HISTPAGE")
    vehicle_id = resp.json()["id"]
    tenant_id = resp.json()["tenant_id"]

    driver = await _make_driver(session, tenant_id=tenant_id, name="Solo Driver")
    now = datetime.now(UTC)
    for i in range(3):
        await _make_shift(
            session,
            tenant_id=tenant_id,
            driver_id=driver.id,
            vehicle_id=vehicle_id,
            start_at=now - timedelta(hours=i),
            end_at=now - timedelta(hours=i) + timedelta(minutes=30),
        )

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/shift-history?limit=2&skip=0", headers=headers)
    body = resp.json()
    assert body["total"] == 3
    assert len(body["items"]) == 2

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/shift-history?limit=2&skip=2", headers=headers)
    body = resp.json()
    assert body["total"] == 3
    assert len(body["items"]) == 1


async def test_vehicle_shift_history_unknown_vehicle_404s(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get("/v1/fleet/vehicles/does-not-exist/shift-history", headers=headers)
    assert resp.status_code == 404


async def test_vehicle_shift_history_is_tenant_isolated(client, session):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Shift History Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Shift History Tenant B")

    resp = await _create_vehicle(client, headers_a, rego="TX-HISTISO")
    vehicle_id = resp.json()["id"]
    tenant_id = resp.json()["tenant_id"]

    driver = await _make_driver(session, tenant_id=tenant_id, name="Isolated Driver")
    await _make_shift(
        session,
        tenant_id=tenant_id,
        driver_id=driver.id,
        vehicle_id=vehicle_id,
        start_at=datetime.now(UTC) - timedelta(hours=2),
        end_at=datetime.now(UTC) - timedelta(hours=1),
    )

    # Tenant B's dispatcher must never see tenant A's vehicle or its shifts.
    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/shift-history", headers=headers_b)
    assert resp.status_code == 404

    # Tenant A itself still sees its own shift.
    resp = await client.get(f"/v1/fleet/vehicles/{vehicle_id}/shift-history", headers=headers_a)
    assert resp.status_code == 200
    assert resp.json()["total"] == 1
