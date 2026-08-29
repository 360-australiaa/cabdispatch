"""Tests for WP-23 (plan D-4): assert_vehicle_operational + vehicle lifecycle
transitions + GET /v1/fleet/vehicles/{id}/readiness.

Section 1 exercises the pure function directly (no DB, no client) -- one gate
failing in isolation per test, plus a happy path and the operating_area
mandated-vs-country distinction. Section 2 exercises the real HTTP endpoints
(readiness + activate) against a seeded vehicle/documents/assignment, same
style as tests/test_device_assignment_flow.py.
"""
from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy import select

from app.models.compliance import (
    DOC_TYPE_CAMERA_REGISTER,
    DOC_TYPE_DURESS_REGISTER,
    DOC_TYPE_TRACKING_REGISTER,
    ComplianceDocument,
)
from app.models.device_assignment import DeviceAssignment
from app.models.fleet import (
    OPERATING_AREA_COUNTRY,
    OPERATING_AREA_SYDNEY,
    VEHICLE_STATUS_ACTIVE,
    VEHICLE_STATUS_PENDING_COMPLIANCE,
    Device,
    DevicePairingCode,
    Vehicle,
)
from app.models.tenant_settings import TenantSettings
from app.models.user import User
from app.services.vehicle_readiness import (
    MANDATED_TRACKING_AREAS,
    VehicleNotOperationalError,
    assert_vehicle_operational,
)
from tests.conftest import auth_headers

TODAY = date(2026, 1, 15)
FUTURE = TODAY + timedelta(days=30)
PAST = TODAY - timedelta(days=30)

# The integration tests (Section 2) exercise `get_vehicle_readiness`/
# `activate_vehicle` through the real HTTP endpoints, which call
# `assert_vehicle_operational` with its default `today=date.today()` (the
# real wall-clock date) -- NOT the fixed TODAY constant above, which exists
# only to make Section 1's pure-function tests deterministic. So the seeded
# expiry dates those integration tests write must be future/past relative to
# the real wall clock, not TODAY.
REAL_FUTURE = date.today() + timedelta(days=30)


# --- Section 1: assert_vehicle_operational pure-function tests ----------------


def _vehicle(**overrides) -> Vehicle:
    """A vehicle that passes every single gate by default -- individual tests
    override just the one field(s) relevant to the gate under test."""
    defaults = dict(
        tenant_id="t1",
        rego="ABC123",
        vehicle_class="standard",
        status=VEHICLE_STATUS_ACTIVE,
        registration_expiry=FUTURE,
        insurance_expiry=FUTURE,
        inspection_expiry=FUTURE,
        taxi_licence_no="LIC-1",
        licence_expiry=FUTURE,
        camera_serial="CAM-1",
        tracking_device_id="TRK-1",
        operating_area=OPERATING_AREA_SYDNEY,
    )
    defaults.update(overrides)
    return Vehicle(**defaults)


def _settings() -> TenantSettings:
    return TenantSettings(tenant_id="t1")


def _assert_ok(vehicle: Vehicle, **input_overrides) -> list[str]:
    inputs = dict(
        has_camera_register_doc=True,
        has_duress_register_doc=True,
        has_tracking_register_doc=True,
        has_active_device_assignment=True,
        device_calibration_due=FUTURE,
    )
    inputs.update(input_overrides)
    return assert_vehicle_operational(vehicle, _settings(), today=TODAY, **inputs)


def test_happy_path_all_gates_pass():
    reasons = _assert_ok(_vehicle())
    assert reasons == []


def test_gate_status_not_active():
    reasons = _assert_ok(_vehicle(status=VEHICLE_STATUS_PENDING_COMPLIANCE))
    assert any("status" in r for r in reasons)


def test_gate_registration_expiry_passed():
    reasons = _assert_ok(_vehicle(registration_expiry=PAST))
    assert any("registration_expiry" in r for r in reasons)


def test_gate_registration_expiry_none_is_fail_open():
    reasons = _assert_ok(_vehicle(registration_expiry=None))
    assert not any("registration_expiry" in r for r in reasons)


def test_gate_insurance_expiry_passed():
    reasons = _assert_ok(_vehicle(insurance_expiry=PAST))
    assert any("insurance_expiry" in r for r in reasons)


def test_gate_inspection_expiry_passed():
    reasons = _assert_ok(_vehicle(inspection_expiry=PAST))
    assert any("inspection_expiry" in r for r in reasons)


def test_gate_taxi_licence_no_not_set():
    reasons = _assert_ok(_vehicle(taxi_licence_no=None))
    assert any("taxi_licence_no" in r for r in reasons)


def test_gate_licence_expiry_passed():
    reasons = _assert_ok(_vehicle(licence_expiry=PAST))
    assert any("licence_expiry" in r for r in reasons)


def test_gate_camera_serial_not_set():
    reasons = _assert_ok(_vehicle(camera_serial=None))
    assert any("camera_serial" in r for r in reasons)


def test_gate_missing_camera_register_doc():
    reasons = _assert_ok(_vehicle(), has_camera_register_doc=False)
    assert any("camera_register" in r for r in reasons)


def test_gate_missing_tracking_device_id_in_mandated_area():
    reasons = _assert_ok(_vehicle(tracking_device_id=None, operating_area=OPERATING_AREA_SYDNEY))
    assert any("tracking_device_id" in r for r in reasons)


def test_gate_missing_tracking_register_doc_in_mandated_area():
    reasons = _assert_ok(_vehicle(operating_area=OPERATING_AREA_SYDNEY), has_tracking_register_doc=False)
    assert any("tracking_register" in r for r in reasons)


def test_gate_missing_duress_register_doc_in_mandated_area():
    reasons = _assert_ok(_vehicle(operating_area=OPERATING_AREA_SYDNEY), has_duress_register_doc=False)
    assert any("duress_register" in r for r in reasons)


def test_gate_no_active_device_assignment():
    reasons = _assert_ok(_vehicle(), has_active_device_assignment=False)
    assert any("meter" in r or "device" in r for r in reasons)


def test_gate_device_calibration_due_passed():
    reasons = _assert_ok(_vehicle(), device_calibration_due=PAST)
    assert any("calibration_due" in r for r in reasons)


def test_gate_device_calibration_due_none_is_fail_open():
    reasons = _assert_ok(_vehicle(), device_calibration_due=None)
    assert not any("calibration_due" in r for r in reasons)


def test_operating_area_country_does_not_require_tracking_duress_camera_of_that_kind():
    """Same vehicle, same missing duress doc: blocked in a mandated area
    ("sydney") but NOT blocked in "country"."""
    vehicle_sydney = _vehicle(operating_area=OPERATING_AREA_SYDNEY, tracking_device_id=None)
    reasons_sydney = _assert_ok(vehicle_sydney, has_duress_register_doc=False, has_tracking_register_doc=False)
    assert any("duress_register" in r for r in reasons_sydney)
    assert any("tracking_register" in r for r in reasons_sydney)
    assert any("tracking_device_id" in r for r in reasons_sydney)

    vehicle_country = _vehicle(operating_area=OPERATING_AREA_COUNTRY, tracking_device_id=None)
    reasons_country = _assert_ok(vehicle_country, has_duress_register_doc=False, has_tracking_register_doc=False)
    assert not any("duress_register" in r for r in reasons_country)
    assert not any("tracking_register" in r for r in reasons_country)
    assert not any("tracking_device_id" in r for r in reasons_country)


def test_all_mandated_areas_are_covered():
    assert MANDATED_TRACKING_AREAS == {"sydney", "newcastle", "wollongong", "central_coast"}


def test_vehicle_not_operational_error_carries_reasons():
    err = VehicleNotOperationalError(["reason one", "reason two"])
    assert err.reasons == ["reason one", "reason two"]


# --- Section 2: readiness endpoint + activate transition (real HTTP) ----------


async def _create_vehicle(client, headers, **overrides):
    payload = {
        "rego": overrides.pop("rego", "WP23-001"),
        "vehicle_class": overrides.pop("vehicle_class", "standard"),
        **overrides,
    }
    return await client.post("/v1/fleet/vehicles", json=payload, headers=headers)


async def _pending_operational_vehicle(client, session, headers, tenant_id, *, rego, admin_user_id):
    """Creates a vehicle in pending_compliance with every readiness gate
    satisfied (fully seeded compliance docs + an active device assignment)
    except vehicle.status itself, which stays pending_compliance until the
    activate route flips it."""
    vehicle_resp = await _create_vehicle(
        client,
        headers,
        rego=rego,
        camera_serial="CAM-WP23",
        tracking_device_id="TRK-WP23",
        taxi_licence_no="LIC-WP23",
        licence_expiry=str(REAL_FUTURE),
        registration_expiry=str(REAL_FUTURE),
        insurance_expiry=str(REAL_FUTURE),
        inspection_expiry=str(REAL_FUTURE),
        operating_area=OPERATING_AREA_SYDNEY,
    )
    assert vehicle_resp.status_code == 201
    vehicle = vehicle_resp.json()

    patch_resp = await client.patch(
        f"/v1/fleet/vehicles/{vehicle['id']}",
        json={"status": VEHICLE_STATUS_PENDING_COMPLIANCE},
        headers=headers,
    )
    assert patch_resp.status_code == 200
    vehicle = patch_resp.json()

    now = datetime.now(UTC)
    for doc_type in (DOC_TYPE_CAMERA_REGISTER, DOC_TYPE_DURESS_REGISTER, DOC_TYPE_TRACKING_REGISTER):
        session.add(
            ComplianceDocument(
                tenant_id=tenant_id,
                vehicle_id=vehicle["id"],
                doc_type=doc_type,
                file_path=f"uploads/{tenant_id}/{vehicle['id']}/{doc_type}.pdf",
                original_filename=f"{doc_type}.pdf",
                content_type="application/pdf",
                uploaded_by=admin_user_id,
                uploaded_at=now,
            )
        )

    device = Device(tenant_id=tenant_id, android_id=f"wp23-android-{rego}", calibration_due=REAL_FUTURE)
    session.add(device)
    await session.flush()

    session.add(
        DeviceAssignment(
            tenant_id=tenant_id,
            device_id=device.id,
            vehicle_id=vehicle["id"],
            bound_at=now,
            bound_by_user_id=admin_user_id,
        )
    )
    await session.commit()

    return vehicle


async def _get_admin_user_id(session, tenant_id: str) -> str:
    result = await session.execute(
        select(User.id).where(User.tenant_id == tenant_id, User.role == "admin")
    )
    return result.scalar_one()


async def test_readiness_endpoint_reports_operational_for_fully_seeded_vehicle(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="WP23 Readiness OK")
    whoami = await client.get("/v1/fleet/vehicles", headers=headers)  # cheap way to confirm auth wired
    assert whoami.status_code == 200

    # Recover tenant_id + admin user id from a freshly created vehicle response.
    vehicle_probe = (await _create_vehicle(client, headers, rego="WP23-PROBE")).json()
    tenant_id = vehicle_probe["tenant_id"]
    admin_user_id = await _get_admin_user_id(session, tenant_id)

    vehicle = await _pending_operational_vehicle(
        client, session, headers, tenant_id, rego="WP23-READY", admin_user_id=admin_user_id
    )

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle['id']}/readiness", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    # status is still pending_compliance at this point (readiness doesn't
    # flip status), so the status gate is expected to be the sole failure.
    assert body["operational"] is False
    assert len(body["reasons"]) == 1
    assert "status" in body["reasons"][0]


async def test_readiness_endpoint_lists_multiple_reasons_for_bare_vehicle(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="WP23 Readiness Bad")
    vehicle = (await _create_vehicle(client, headers, rego="WP23-BARE")).json()

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle['id']}/readiness", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["operational"] is False
    assert len(body["reasons"]) > 1


async def test_readiness_endpoint_404s_for_unknown_vehicle(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="WP23 Readiness 404")
    resp = await client.get("/v1/fleet/vehicles/does-not-exist/readiness", headers=headers)
    assert resp.status_code == 404


async def test_activate_happy_path(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="WP23 Activate OK")
    vehicle_probe = (await _create_vehicle(client, headers, rego="WP23-A-PROBE")).json()
    tenant_id = vehicle_probe["tenant_id"]
    admin_user_id = await _get_admin_user_id(session, tenant_id)

    vehicle = await _pending_operational_vehicle(
        client, session, headers, tenant_id, rego="WP23-ACTIVATE", admin_user_id=admin_user_id
    )
    assert vehicle["status"] == VEHICLE_STATUS_PENDING_COMPLIANCE

    resp = await client.post(f"/v1/fleet/vehicles/{vehicle['id']}/activate", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["status"] == VEHICLE_STATUS_ACTIVE

    check = await client.get(f"/v1/fleet/vehicles/{vehicle['id']}", headers=headers)
    assert check.json()["status"] == VEHICLE_STATUS_ACTIVE


async def test_activate_returns_409_with_reasons_when_not_operational(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="WP23 Activate Fail")
    vehicle = (await _create_vehicle(client, headers, rego="WP23-NOTREADY")).json()

    patch_resp = await client.patch(
        f"/v1/fleet/vehicles/{vehicle['id']}",
        json={"status": VEHICLE_STATUS_PENDING_COMPLIANCE},
        headers=headers,
    )
    assert patch_resp.status_code == 200

    resp = await client.post(f"/v1/fleet/vehicles/{vehicle['id']}/activate", headers=headers)
    assert resp.status_code == 409
    detail = resp.json()["detail"]
    assert isinstance(detail, list)
    assert len(detail) > 0

    # Nothing changed -- vehicle is still pending_compliance.
    check = await client.get(f"/v1/fleet/vehicles/{vehicle['id']}", headers=headers)
    assert check.json()["status"] == VEHICLE_STATUS_PENDING_COMPLIANCE


async def test_activate_requires_pending_compliance_status(client, session):
    """A vehicle still in draft can't be activated -- draft -> active is not
    the modelled transition (draft -> pending_compliance is ungated PATCH;
    pending_compliance -> active is this route)."""
    headers = await auth_headers(client, session, role="admin", tenant_name="WP23 Activate Draft")
    vehicle = (await _create_vehicle(client, headers, rego="WP23-DRAFT")).json()
    assert vehicle["status"] == "draft"

    resp = await client.post(f"/v1/fleet/vehicles/{vehicle['id']}/activate", headers=headers)
    assert resp.status_code == 409
    detail = resp.json()["detail"]
    assert any("pending_compliance" in r for r in detail)


async def test_activate_requires_admin_role(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="WP23 Activate RoleSetup")
    vehicle = (await _create_vehicle(client, headers, rego="WP23-ROLE")).json()

    driver_headers = await auth_headers(
        client, session, role="driver", tenant_id=vehicle["tenant_id"]
    )
    resp = await client.post(f"/v1/fleet/vehicles/{vehicle['id']}/activate", headers=driver_headers)
    assert resp.status_code == 403
