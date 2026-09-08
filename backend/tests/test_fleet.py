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

from app.models.audit_log import AuditLog
from app.models.fleet import (  # noqa: F401
    Device,
    DevicePairingCode,
    DeviceVersionHistory,
    Vehicle,
    VehiclePositionHistory,
)
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


async def test_a_pairing_code_can_only_ever_enrol_into_its_own_tenant(client, session):
    """The tenant comes from the CODE, never from whoever presents it.

    This used to assert a 400 when tenant B's admin presented tenant A's code,
    back when registration read the tenant off the caller's token. It no longer
    can: registration is the gate a tablet passes BEFORE anyone logs into the
    meter, so there is usually no token at all, and the code is the credential.

    What must still hold -- and is the property that actually protects a tenant
    -- is that a code cannot move a device into the presenter's tenant. Holding
    tenant A's secret code enrols into tenant A, which is what the code is for;
    it can never be turned into a device inside tenant B.
    """
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
    assert resp.status_code == 200
    device = resp.json()

    # Tenant A's vehicle, tenant A's device -- not tenant B's, despite tenant B
    # being the caller.
    assert device["vehicle_id"] == vehicle_id
    tenant_a_devices = (await client.get("/v1/fleet/devices", headers=headers_a)).json()["items"]
    tenant_b_devices = (await client.get("/v1/fleet/devices", headers=headers_b)).json()["items"]
    assert device["id"] in {d["id"] for d in tenant_a_devices}
    assert device["id"] not in {d["id"] for d in tenant_b_devices}


async def test_registration_needs_no_login_at_all(client, session):
    """The whole point of the change: a tablet that has never been paired has
    nobody logged into it, so registration cannot require a bearer token.

    Note there are no `headers=` on the register call. If this ever starts
    needing auth again, the readiness gate in the meter app becomes unclearable
    in the field -- a driver would be told to pair before logging in, on a
    screen whose pair button cannot work until they log in.
    """
    headers = await auth_headers(client, session, role="admin", tenant_name="No Login Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-NOAUTH")).json()["id"]
    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]

    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-noauth-1", "pairing_code": code, "model": "SM-T575"},
    )

    assert resp.status_code == 200
    device = resp.json()
    assert device["vehicle_id"] == vehicle_id
    assert device["paired_at"] is not None
    assert device["revoked_at"] is None
    # And it comes back with the credential it will use from now on.
    assert device["device_secret"]


async def test_the_device_secret_is_returned_on_registration_and_never_again(client, session):
    """A device credential must not be readable by anything that merely reads
    the fleet -- a dashboard user listing devices, or the device's own
    heartbeat."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Secret Once Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-ONCE")).json()["id"]
    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]

    registered = (
        await client.post(
            "/v1/fleet/devices/register",
            json={"android_id": "android-once-1", "pairing_code": code},
        )
    ).json()
    device_id = registered["id"]
    secret = registered["device_secret"]
    assert secret

    read = (await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)).json()
    assert read["device_secret"] is None

    listed = (await client.get("/v1/fleet/devices", headers=headers)).json()["items"]
    assert all(d["device_secret"] is None for d in listed)

    beat = (
        await client.post(
            f"/v1/fleet/devices/{device_id}/heartbeat",
            json={"battery": 80},
            headers={"X-Device-Secret": secret},
        )
    ).json()
    assert beat["device_secret"] is None


async def test_a_device_secret_authenticates_a_heartbeat_with_nobody_logged_in(client, session):
    """The reason the secret exists: a parked or logged-off tablet must still be
    able to collect its kiosk-lock / locate / force-update flags. It could not
    before, because the heartbeat rode the driver's bearer token."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Beat Secret Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-BEAT")).json()["id"]
    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]
    registered = (
        await client.post(
            "/v1/fleet/devices/register",
            json={"android_id": "android-beat-1", "pairing_code": code},
        )
    ).json()
    device_id, secret = registered["id"], registered["device_secret"]

    await client.post(
        f"/v1/fleet/devices/{device_id}/kiosk-lock", json={"enabled": True}, headers=headers
    )

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"battery": 55, "network": "4g"},
        headers={"X-Device-Secret": secret},
    )

    assert resp.status_code == 200
    assert resp.json()["kiosk_locked"] is True
    assert resp.json()["battery"] == 55


async def test_a_wrong_or_missing_device_secret_does_not_get_in(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="Bad Secret Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-BADSEC")).json()["id"]
    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]
    device_id = (
        await client.post(
            "/v1/fleet/devices/register",
            json={"android_id": "android-badsec-1", "pairing_code": code},
        )
    ).json()["id"]

    wrong = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"battery": 10},
        headers={"X-Device-Secret": "not-the-secret"},
    )
    assert wrong.status_code == 401

    # No credential of either kind. get_optional_tenant_id authorises nothing on
    # its own, so the route itself has to refuse -- this is the test that proves
    # making the bearer optional did not open the heartbeat to everyone.
    none_at_all = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={"battery": 10}
    )
    assert none_at_all.status_code == 401


async def test_a_revoked_device_is_a_404_so_the_tablet_knows_it_is_out(client, session):
    """Revocation has to look like "no such device" to the meter app, because
    that is the one signal it already acts on: DeviceCommandHeartbeat turns a
    heartbeat 404 into a sticky `deviceRejected`, which fails the readiness gate
    on the next cold start."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Revoke Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-REVOKE")).json()["id"]
    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]
    registered = (
        await client.post(
            "/v1/fleet/devices/register",
            json={"android_id": "android-revoke-1", "pairing_code": code},
        )
    ).json()
    device_id, secret = registered["id"], registered["device_secret"]

    patched = await client.patch(
        f"/v1/fleet/devices/{device_id}", json={"revoked": True}, headers=headers
    )
    assert patched.status_code == 200
    assert patched.json()["revoked_at"] is not None

    beat = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat",
        json={"battery": 90},
        headers={"X-Device-Secret": secret},
    )
    assert beat.status_code == 404


async def test_re_pairing_a_revoked_device_puts_it_back_in_service(client, session):
    """An operator handing out a fresh code for a tablet they retired is
    un-retiring it. Leaving revoked_at set would silently 404 every heartbeat
    after a pairing the driver just watched succeed."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Unrevoke Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="TX-UNREV")).json()["id"]

    async def new_code():
        resp = await client.post(
            f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers
        )
        return resp.json()["code"]

    first = (
        await client.post(
            "/v1/fleet/devices/register",
            json={"android_id": "android-unrev-1", "pairing_code": await new_code()},
        )
    ).json()
    await client.patch(f"/v1/fleet/devices/{first['id']}", json={"revoked": True}, headers=headers)

    second = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "android-unrev-1", "pairing_code": await new_code()},
    )

    assert second.status_code == 200
    assert second.json()["id"] == first["id"]  # same physical tablet, same row
    assert second.json()["revoked_at"] is None
    # ...and re-pairing minted a NEW secret, so the old one no longer works.
    assert second.json()["device_secret"] != first["device_secret"]
    stale = await client.post(
        f"/v1/fleet/devices/{first['id']}/heartbeat",
        json={"battery": 5},
        headers={"X-Device-Secret": first["device_secret"]},
    )
    assert stale.status_code == 401


async def test_a_device_paired_before_secrets_existed_can_still_heartbeat(client, session):
    """Rollout safety. Every tablet in the field today has no device_secret_hash.
    If the heartbeat demanded a secret, the whole fleet would stop reporting the
    moment this deployed -- so the bearer path stays until they re-pair."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Legacy Tenant")
    device_id = (
        await client.post(
            "/v1/fleet/devices", json={"android_id": "android-legacy-1"}, headers=headers
        )
    ).json()["id"]

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={"battery": 42}, headers=headers
    )

    assert resp.status_code == 200
    assert resp.json()["battery"] == 42
    assert resp.json()["paired_at"] is None  # provisioned by hand, never enrolled


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
    session, *, tenant_id, driver_id, vehicle_id, start_at, end_at=None, km_total=Decimal(0),
    cash_total=Decimal(0), card_total=Decimal(0),
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


# --- remote locate: the answer path, and the flag actually clearing -------------


async def _paired_device(client, session, *, tenant_name: str, rego: str, android_id: str):
    """A really-enrolled device plus its secret and its tenant's admin headers."""
    headers = await auth_headers(client, session, role="admin", tenant_name=tenant_name)
    vehicle_id = (await _create_vehicle(client, headers, rego=rego)).json()["id"]
    code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    ).json()["code"]
    registered = (
        await client.post(
            "/v1/fleet/devices/register",
            json={"android_id": android_id, "pairing_code": code},
        )
    ).json()
    return headers, registered["id"], registered["device_secret"]


async def test_answering_a_locate_clears_the_flag_and_records_where_it_is(client, session):
    """The bug reported from the field: "when I try to locate it's showing
    pending, but nothing is working".

    `locate_requested` was set by an admin and read by the tablet, but NOTHING
    anywhere ever set it back to false -- no route, no service call, not the
    heartbeat -- so the badge said Pending for the life of the row whether or not
    the device had answered. There was also nowhere for an answer to go.
    """
    headers, device_id, secret = await _paired_device(
        client, session, tenant_name="Locate Tenant", rego="TX-LOC", android_id="android-loc-1"
    )

    requested = await client.post(
        f"/v1/fleet/devices/{device_id}/locate", json={"enabled": True}, headers=headers
    )
    assert requested.json()["locate_requested"] is True

    answered = await client.post(
        f"/v1/fleet/devices/{device_id}/locate-response",
        json={"lat": -33.8688, "lng": 151.2093, "accuracy_m": 12.5},
        headers={"X-Device-Secret": secret},
    )

    assert answered.status_code == 200
    body = answered.json()
    assert body["locate_requested"] is False  # the whole point
    assert body["last_locate_lat"] == -33.8688
    assert body["last_locate_lng"] == 151.2093
    assert body["last_locate_accuracy_m"] == 12.5
    assert body["last_locate_at"] is not None

    # And it stays cleared on the next read -- an admin refreshing the page sees
    # the answer, not a stale Pending.
    reread = (await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)).json()
    assert reread["locate_requested"] is False
    assert reread["last_locate_lat"] == -33.8688


async def test_a_locate_can_be_answered_with_nobody_logged_in(client, session):
    """The reason this route is device-authenticated and lives on the DEVICE.

    The old answer path published a vehicle position, which needs a live driver
    session and a current vehicle binding. A parked, logged-off tablet has
    neither -- and that is exactly the tablet someone reaching for "locate" is
    trying to find. Note the absence of any bearer token here.
    """
    _, device_id, secret = await _paired_device(
        client, session, tenant_name="Parked Tenant", rego="TX-PARK", android_id="android-park-1"
    )

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/locate-response",
        json={"lat": -33.9, "lng": 151.1},
        headers={"X-Device-Secret": secret},
    )

    assert resp.status_code == 200
    assert resp.json()["last_locate_at"] is not None
    # Accuracy is optional and never invented -- a device that cannot say how
    # good its fix is sends nothing rather than a guess.
    assert resp.json()["last_locate_accuracy_m"] is None


async def test_a_locate_answer_needs_a_real_credential(client, session):
    _, device_id, _secret = await _paired_device(
        client, session, tenant_name="Loc Auth Tenant", rego="TX-LOCA", android_id="android-loca-1"
    )

    wrong = await client.post(
        f"/v1/fleet/devices/{device_id}/locate-response",
        json={"lat": -33.9, "lng": 151.1},
        headers={"X-Device-Secret": "nope"},
    )
    assert wrong.status_code == 401

    none_at_all = await client.post(
        f"/v1/fleet/devices/{device_id}/locate-response", json={"lat": -33.9, "lng": 151.1}
    )
    assert none_at_all.status_code == 401


async def test_a_restart_ack_clears_the_reboot_flag(client, session):
    """Same missing-clear problem as locate. The app now restarts its own
    process on this flag (it cannot reboot the OS without Device Owner) and says
    so here, which is what turns a permanent "Pending" into a carried-out
    command."""
    headers, device_id, secret = await _paired_device(
        client, session, tenant_name="Restart Tenant", rego="TX-RST", android_id="android-rst-1"
    )

    queued = await client.post(
        f"/v1/fleet/devices/{device_id}/reboot", json={"enabled": True}, headers=headers
    )
    assert queued.json()["reboot_requested"] is True

    acked = await client.post(
        f"/v1/fleet/devices/{device_id}/command-ack",
        json={"command": "restart"},
        headers={"X-Device-Secret": secret},
    )

    assert acked.status_code == 200
    assert acked.json()["reboot_requested"] is False
    assert acked.json()["command_acked_at"] is not None


async def test_an_unknown_command_is_rejected_rather_than_silently_accepted(client, session):
    _, device_id, secret = await _paired_device(
        client, session, tenant_name="Bad Cmd Tenant", rego="TX-BADC", android_id="android-badc-1"
    )

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/command-ack",
        json={"command": "self-destruct"},
        headers={"X-Device-Secret": secret},
    )

    # A device claiming to have carried out something this server has no concept
    # of must not be recorded as having carried anything out.
    assert resp.status_code == 422


# ==============================================================================
# B5 · device commissioning lifecycle (backend audit §3, gaps G1/G2/G3/G5/G6/
# G8/G9/G10)
# ==============================================================================


async def test_a_revoked_device_is_refused_on_the_BEARER_path_too(client, session):
    """G3, the serious one. `authenticate_device` checked `revoked_at`; the
    bearer FALLBACK on the same routes called `get_device_or_404`, which did
    not. So revoking a lost or stolen tablet stopped only its device-secret
    calls -- with any valid human token on it (a driver's, say) it kept
    heartbeating, answering locates and acknowledging commands indefinitely.
    Cutting a tablet off is the entire purpose of the revoke button.
    """
    headers, device_id, secret = await _paired_device(
        client, session, tenant_name="Bearer Revoke Tenant", rego="BR-001", android_id="android-br-1"
    )

    # Both credentials work while the device is live.
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/heartbeat",
            json={"battery": 80},
            headers={"X-Device-Secret": secret},
        )
    ).status_code == 200
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/heartbeat", json={"battery": 80}, headers=headers
        )
    ).status_code == 200

    await client.patch(f"/v1/fleet/devices/{device_id}", json={"revoked": True}, headers=headers)

    # The device-secret path was always closed...
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/heartbeat",
            json={"battery": 80},
            headers={"X-Device-Secret": secret},
        )
    ).status_code == 404
    # ...and now so is the bearer path, on every route a tablet calls.
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/heartbeat", json={"battery": 80}, headers=headers
        )
    ).status_code == 404
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/locate-response",
            json={"lat": -33.8, "lng": 151.2},
            headers=headers,
        )
    ).status_code == 404
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/command-ack",
            json={"command": "restart"},
            headers=headers,
        )
    ).status_code == 404

    # Admin CRUD still SEES the revoked row -- that is how it gets un-revoked,
    # and how it stays visible on the dashboard instead of vanishing.
    read = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert read.status_code == 200
    assert read.json()["revoked_at"] is not None


async def test_rotating_a_secret_kills_the_old_one_and_the_new_one_works(client, session):
    """G2. `mint_device_secret` had exactly one call site (registration), so a
    device secret was immortal -- the only way to change one was to physically
    visit the tablet with a fresh pairing code."""
    headers, device_id, old_secret = await _paired_device(
        client, session, tenant_name="Rotate Tenant", rego="RT-001", android_id="android-rt-1"
    )

    before = (await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)).json()

    resp = await client.post(f"/v1/fleet/devices/{device_id}/rotate-secret", headers=headers)
    assert resp.status_code == 200
    new_secret = resp.json()["device_secret"]
    assert new_secret and new_secret != old_secret

    # Old secret: dead. New secret: works.
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/heartbeat",
            json={"battery": 55},
            headers={"X-Device-Secret": old_secret},
        )
    ).status_code == 401
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/heartbeat",
            json={"battery": 55},
            headers={"X-Device-Secret": new_secret},
        )
    ).status_code == 200

    # Rotation is NOT a re-pair: the vehicle binding and pairing timestamp are
    # untouched. Conflating the two would silently rebind a car on what is
    # meant to be a routine credential refresh.
    after = (await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)).json()
    assert after["vehicle_id"] == before["vehicle_id"]
    assert after["paired_at"] == before["paired_at"]
    assert after["device_secret"] is None  # never returned again on an ordinary read

    # ...and it is audit-logged, so an operator can see who cut a tablet off.
    rotations = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.entity_id == device_id,
                    AuditLog.action == "device_secret_rotated",
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(rotations) == 1


async def test_rotate_secret_needs_admin_and_refuses_a_revoked_device(client, session):
    headers, device_id, _secret = await _paired_device(
        client, session, tenant_name="Rotate Guard Tenant", rego="RG-001", android_id="android-rg-1"
    )
    driver_headers = await auth_headers(
        client, session, role=ROLE_DRIVER, tenant_name="Rotate Guard Tenant"
    )
    assert (
        await client.post(f"/v1/fleet/devices/{device_id}/rotate-secret", headers=driver_headers)
    ).status_code == 403

    await client.patch(f"/v1/fleet/devices/{device_id}", json={"revoked": True}, headers=headers)
    # Handing a working credential to a retired tablet would un-revoke it in
    # every practical sense while the dashboard still showed it as retired.
    assert (
        await client.post(f"/v1/fleet/devices/{device_id}/rotate-secret", headers=headers)
    ).status_code == 404


async def test_re_pairing_a_tablet_into_a_different_vehicle_is_audit_logged(client, session):
    """G1. `register_device` reassigned `vehicle_id` unconditionally and wrote
    nothing anywhere, so a tablet could move car to car leaving no trace. The
    only thing that ever noticed was the ADVISORY shift-start cross-check --
    which by construction fires only if somebody starts a shift on the old car.
    """
    headers = await auth_headers(client, session, role="admin", tenant_name="Repair Audit Tenant")
    first_vehicle = (await _create_vehicle(client, headers, rego="RA-001")).json()["id"]
    second_vehicle = (await _create_vehicle(client, headers, rego="RA-002")).json()["id"]

    async def pair(vehicle_id):
        code = (
            await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
        ).json()["code"]
        return (
            await client.post(
                "/v1/fleet/devices/register",
                json={"android_id": "android-ra-1", "pairing_code": code},
            )
        ).json()

    device = await pair(first_vehicle)
    device_id = device["id"]

    # First enrolment reads as a registration, not a re-pair.
    rows = (
        (
            await session.execute(
                select(AuditLog).where(AuditLog.entity_id == device_id).order_by(AuditLog.at)
            )
        )
        .scalars()
        .all()
    )
    assert [r.action for r in rows] == ["device_registered"]

    moved = await pair(second_vehicle)
    assert moved["vehicle_id"] == second_vehicle

    session.expire_all()
    rows = (
        (
            await session.execute(
                select(AuditLog).where(AuditLog.entity_id == device_id).order_by(AuditLog.at)
            )
        )
        .scalars()
        .all()
    )
    assert [r.action for r in rows] == ["device_registered", "device_repaired"]

    rebind = rows[-1]
    assert rebind.entity_type == "device"
    assert rebind.before_json["vehicle_id"] == first_vehicle
    assert rebind.after_json["vehicle_id"] == second_vehicle
    # Called out explicitly so a reviewer can filter on one boolean rather than
    # diffing two dicts for every pairing event in the tenant.
    assert rebind.after_json["vehicle_changed"] is True


async def test_re_pairing_into_the_SAME_vehicle_is_logged_but_not_flagged_as_a_move(
    client, session
):
    """A tablet re-paired into the car it was already in is a credential
    refresh, not a vehicle move. Both are recorded -- only one is a move."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Same Vehicle Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="SV-001")).json()["id"]

    async def pair():
        code = (
            await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
        ).json()["code"]
        return (
            await client.post(
                "/v1/fleet/devices/register",
                json={"android_id": "android-sv-1", "pairing_code": code},
            )
        ).json()

    device_id = (await pair())["id"]
    await pair()

    session.expire_all()
    rows = (
        (
            await session.execute(
                select(AuditLog).where(AuditLog.entity_id == device_id).order_by(AuditLog.at)
            )
        )
        .scalars()
        .all()
    )
    assert [r.action for r in rows] == ["device_registered", "device_repaired"]
    assert rows[-1].after_json["vehicle_changed"] is False


async def test_a_tablet_can_read_its_own_row_with_only_a_device_secret(client, session):
    """G5. Both device-reading routes were bearer-only, so a parked, logged-off
    tablet holding a stale binding could not correct itself until a human signed
    in -- and that is exactly the tablet an operator is trying to straighten
    out."""
    headers, device_id, secret = await _paired_device(
        client, session, tenant_name="Devices Me Tenant", rego="DM-001", android_id="android-dm-1"
    )
    expected_vehicle = (
        await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    ).json()["vehicle_id"]

    resp = await client.get("/v1/fleet/devices/me", headers={"X-Device-Secret": secret})
    assert resp.status_code == 200
    body = resp.json()
    assert body["id"] == device_id
    assert body["vehicle_id"] == expected_vehicle
    # Never leaks the credential back out.
    assert body["device_secret"] is None

    # A wrong secret is a 401 -- never a 404, which would confirm which secrets
    # exist. No header at all cannot even be dispatched (the header is required).
    assert (await client.get("/v1/fleet/devices/me")).status_code == 422
    assert (
        await client.get("/v1/fleet/devices/me", headers={"X-Device-Secret": "not-a-secret"})
    ).status_code == 401

    # And revoking the tablet closes it, like every other device route.
    await client.patch(f"/v1/fleet/devices/{device_id}", json={"revoked": True}, headers=headers)
    assert (
        await client.get("/v1/fleet/devices/me", headers={"X-Device-Secret": secret})
    ).status_code == 401


async def test_devices_me_is_not_swallowed_by_the_device_id_route(client, session):
    """Route-ordering regression guard. FastAPI matches in declaration order; if
    `GET /devices/{device_id}` is ever moved above `GET /devices/me`, `me` gets
    read as a device id and this endpoint silently becomes a bearer-only 404."""
    _headers, _device_id, secret = await _paired_device(
        client, session, tenant_name="Route Order Tenant", rego="RO-001", android_id="android-ro-1"
    )
    resp = await client.get("/v1/fleet/devices/me", headers={"X-Device-Secret": secret})
    assert resp.status_code == 200


async def test_force_update_and_kiosk_lock_can_finally_be_acknowledged(client, session):
    """G9. `record_command_ack` understood only `restart`, so half the commands
    an admin can queue had no ack path at all and read "Pending" forever -- the
    exact bug `locate` and `reboot` were already fixed for."""
    headers, device_id, secret = await _paired_device(
        client, session, tenant_name="Ack Tenant", rego="AK-001", android_id="android-ak-1"
    )
    device_headers = {"X-Device-Secret": secret}

    # force_update is a ONE-SHOT REQUEST: acking clears it, or the tablet would
    # re-install on every heartbeat forever.
    await client.post(
        f"/v1/fleet/devices/{device_id}/force-update", json={"enabled": True}, headers=headers
    )
    beat = await client.post(
        f"/v1/fleet/devices/{device_id}/heartbeat", json={}, headers=device_headers
    )
    assert beat.json()["force_update_pending"] is True

    acked = await client.post(
        f"/v1/fleet/devices/{device_id}/command-ack",
        json={"command": "force_update"},
        headers=device_headers,
    )
    assert acked.status_code == 200
    assert acked.json()["force_update_pending"] is False
    assert acked.json()["last_acked_command"] == "force_update"
    assert acked.json()["command_acked_at"] is not None

    # kiosk_lock is a DESIRED STATE: acking records that the tablet applied it
    # WITHOUT unlocking it. Clearing here would unlock every tablet the moment
    # it confirmed it had locked.
    await client.post(
        f"/v1/fleet/devices/{device_id}/kiosk-lock", json={"enabled": True}, headers=headers
    )
    acked = await client.post(
        f"/v1/fleet/devices/{device_id}/command-ack",
        json={"command": "kiosk_lock"},
        headers=device_headers,
    )
    assert acked.status_code == 200
    assert acked.json()["kiosk_locked"] is True
    assert acked.json()["last_acked_command"] == "kiosk_lock"

    # restart still behaves as before, and `last_acked_command` is what keeps a
    # single shared timestamp from reading as "the update landed".
    await client.post(
        f"/v1/fleet/devices/{device_id}/reboot", json={"enabled": True}, headers=headers
    )
    acked = await client.post(
        f"/v1/fleet/devices/{device_id}/command-ack",
        json={"command": "restart"},
        headers=device_headers,
    )
    assert acked.json()["reboot_requested"] is False
    assert acked.json()["last_acked_command"] == "restart"

    # An unknown command is rejected at the schema, not silently swallowed.
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/command-ack",
            json={"command": "self_destruct"},
            headers=device_headers,
        )
    ).status_code == 422


async def test_rego_exact_resolves_one_vehicle_past_the_hundred_row_cap(client, session):
    """G6. `GET /fleet/vehicles` is capped at 100 and the meter uses it as its
    rego->UUID resolver with no paging loop, so the lookup silently fails on a
    tenant's 101st vehicle. The pre-existing `rego` filter is a PARTIAL match
    built for a dashboard search box and cannot serve as a resolver: "AB12"
    also matches "AB123"."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Rego Exact Tenant")
    for rego in ("AB12", "AB123", "ZZ999"):
        await _create_vehicle(client, headers, rego=rego)

    resp = await client.get("/v1/fleet/vehicles?rego_exact=ab12", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["rego"] == "AB12"

    # The partial filter still behaves as the search box needs it to -- which is
    # precisely why it could not be tightened in place.
    partial = await client.get("/v1/fleet/vehicles?rego=ab12", headers=headers)
    assert partial.json()["total"] == 2

    assert (await client.get("/v1/fleet/vehicles?rego_exact=NOPE", headers=headers)).json()[
        "total"
    ] == 0


async def test_verify_admin_pin_accepts_a_device_secret_instead_of_a_human_token(client, session):
    """G8. The tablet's factory-reset flow ran on a driver token, which the role
    gate correctly started refusing. The answer was never "give the tablet an
    admin token" -- that hands every tablet in the fleet the power to administer
    the tenant. A tablet authenticates as ITSELF instead."""
    headers, device_id, secret = await _paired_device(
        client, session, tenant_name="Pin Device Tenant", rego="PD-001", android_id="android-pd-1"
    )
    tenant_id = (await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)).json()[
        "tenant_id"
    ]
    # Same tenant as the device -- auth_headers creates a fresh tenant per call
    # unless handed an existing tenant_id, and the admin-pin route refuses a
    # path tenant that is not the caller's own.
    owner_headers = await auth_headers(client, session, role="owner", tenant_id=tenant_id)
    set_pin = await client.post(
        f"/v1/tenants/{tenant_id}/admin-pin", json={"pin": "4821"}, headers=owner_headers
    )
    assert set_pin.status_code in (200, 204)

    resp = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin",
        json={"pin": "4821"},
        headers={"X-Device-Secret": secret},
    )
    assert resp.status_code == 200
    assert resp.json() == {"valid": True, "configured": True}

    wrong = await client.post(
        f"/v1/fleet/devices/{device_id}/verify-admin-pin",
        json={"pin": "0000"},
        headers={"X-Device-Secret": secret},
    )
    assert wrong.status_code == 200
    assert wrong.json()["valid"] is False

    # A bad secret does not become an anonymous caller, and no credential at all
    # is a 401 rather than an open oracle.
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/verify-admin-pin",
            json={"pin": "4821"},
            headers={"X-Device-Secret": "wrong"},
        )
    ).status_code == 401
    assert (
        await client.post(f"/v1/fleet/devices/{device_id}/verify-admin-pin", json={"pin": "4821"})
    ).status_code == 401
    # ...and the role gate on the human path is untouched.
    driver_headers = await auth_headers(client, session, role=ROLE_DRIVER, tenant_id=tenant_id)
    assert (
        await client.post(
            f"/v1/fleet/devices/{device_id}/verify-admin-pin",
            json={"pin": "4821"},
            headers=driver_headers,
        )
    ).status_code == 403


async def test_minting_a_pairing_code_prunes_spent_ones(client, session):
    """G10. Pairing codes were never garbage-collected -- one dead row per
    tablet per re-pair, accumulating forever. Pruned lazily on mint, the same
    pattern the rest of this backend uses instead of a background timer."""
    headers = await auth_headers(client, session, role="admin", tenant_name="Prune Tenant")
    vehicle_id = (await _create_vehicle(client, headers, rego="PR-001")).json()["id"]

    async def mint():
        return (
            await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
        ).json()["code"]

    stale_unused = await mint()
    long_ago_used = await mint()
    recently_used = await mint()

    rows = {
        row.code: row
        for row in (
            await session.execute(
                select(DevicePairingCode).where(
                    DevicePairingCode.code.in_([stale_unused, long_ago_used, recently_used])
                )
            )
        ).scalars()
    }
    now = datetime.now(UTC)
    # Never used, and expired: no history worth keeping.
    rows[stale_unused].expires_at = now - timedelta(minutes=1)
    # Used, but long enough ago that its `used_by_device_id` has stopped being
    # something anyone is still asking about.
    rows[long_ago_used].used_at = now - timedelta(days=60)
    # Used yesterday: that link is still fresh, so it stays.
    rows[recently_used].used_at = now - timedelta(days=1)
    await session.commit()

    await mint()

    session.expire_all()
    surviving = {
        row.code for row in (await session.execute(select(DevicePairingCode))).scalars()
    }
    assert stale_unused not in surviving
    assert long_ago_used not in surviving
    assert recently_used in surviving


async def test_pruning_never_reaches_across_tenants(client, session):
    """The prune is a DELETE, so it gets the same tenant_id filter every other
    query in this file gets -- one tenant minting a code must never remove
    another tenant's rows."""
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Prune Scope A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Prune Scope B")
    vehicle_a = (await _create_vehicle(client, headers_a, rego="PA-001")).json()["id"]
    vehicle_b = (await _create_vehicle(client, headers_b, rego="PB-001")).json()["id"]

    b_code = (
        await client.post(f"/v1/fleet/vehicles/{vehicle_b}/pairing-code", headers=headers_b)
    ).json()["code"]
    b_row = (
        await session.execute(select(DevicePairingCode).where(DevicePairingCode.code == b_code))
    ).scalar_one()
    b_row.expires_at = datetime.now(UTC) - timedelta(minutes=1)
    await session.commit()

    await client.post(f"/v1/fleet/vehicles/{vehicle_a}/pairing-code", headers=headers_a)

    session.expire_all()
    still_there = (
        await session.execute(select(DevicePairingCode).where(DevicePairingCode.code == b_code))
    ).scalar_one_or_none()
    assert still_there is not None
