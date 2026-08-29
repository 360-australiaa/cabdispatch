"""Tests for the WP-21/22 meter-binding rewrite (plan D-3): register_device now
close-then-opens `DeviceAssignment` rows instead of overwriting
`Device.vehicle_id` directly, guards against re-pairing a vehicle that has an
open shift, and `Vehicle.meter_device_id` is gone entirely.

These exercise the real HTTP endpoints (`POST /v1/fleet/vehicles/{id}/pairing-code`
+ `POST /v1/fleet/devices/register`) plus direct DB assertions on
`DeviceAssignment` rows, mirroring the style already used by
`tests/test_fleet.py`'s QR-pairing tests.
"""
from __future__ import annotations

from datetime import UTC, datetime

import pytest
from sqlalchemy import select

from app.models.device_assignment import DeviceAssignment
from app.models.fleet import Device, DevicePairingCode, Vehicle  # noqa: F401
from app.models.shift import Shift
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


async def _create_vehicle(client, headers, **overrides):
    payload = {
        "rego": overrides.pop("rego", "ABC123"),
        "vehicle_class": overrides.pop("vehicle_class", "standard"),
        **overrides,
    }
    return await client.post("/v1/fleet/vehicles", json=payload, headers=headers)


async def _mint_pairing_code(client, headers, vehicle_id: str) -> str:
    resp = await client.post(f"/v1/fleet/vehicles/{vehicle_id}/pairing-code", headers=headers)
    assert resp.status_code == 200
    return resp.json()["code"]


async def _active_assignments(session, *, device_id: str):
    result = await session.execute(
        select(DeviceAssignment).where(
            DeviceAssignment.device_id == device_id, DeviceAssignment.unbound_at.is_(None)
        )
    )
    return list(result.scalars())


# --- re-pairing closes the old assignment, opens a new one -------------------


async def test_repair_closes_old_assignment_and_opens_new_one(client, session):
    headers = await auth_headers(client, session, role="admin")

    vehicle_a = (await _create_vehicle(client, headers, rego="WP21-A")).json()
    vehicle_b = (await _create_vehicle(client, headers, rego="WP21-B")).json()

    code_a = await _mint_pairing_code(client, headers, vehicle_a["id"])
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "wp21-android-1", "pairing_code": code_a},
        headers=headers,
    )
    assert resp.status_code == 200
    device_id = resp.json()["id"]
    assert resp.json()["vehicle_id"] == vehicle_a["id"]

    code_b = await _mint_pairing_code(client, headers, vehicle_b["id"])
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "wp21-android-1", "pairing_code": code_b},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["vehicle_id"] == vehicle_b["id"]

    # Exactly one active assignment for this device, and it points at B.
    active = await _active_assignments(session, device_id=device_id)
    assert len(active) == 1
    assert active[0].vehicle_id == vehicle_b["id"]
    assert active[0].pairing_code_id is not None
    assert active[0].bound_by_user_id is not None

    # The old (A) assignment is closed with the expected reason.
    all_result = await session.execute(
        select(DeviceAssignment).where(DeviceAssignment.device_id == device_id)
    )
    all_assignments = list(all_result.scalars())
    assert len(all_assignments) == 2
    closed = [a for a in all_assignments if a.id != active[0].id][0]
    assert closed.vehicle_id == vehicle_a["id"]
    assert closed.unbound_at is not None
    assert closed.unbound_reason == "re-paired"


async def test_device_vehicle_id_matches_active_assignment_across_multiple_repairs(client, session):
    headers = await auth_headers(client, session, role="admin")

    vehicle_a = (await _create_vehicle(client, headers, rego="WP21-M-A")).json()
    vehicle_b = (await _create_vehicle(client, headers, rego="WP21-M-B")).json()

    code_a = await _mint_pairing_code(client, headers, vehicle_a["id"])
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "wp21-android-multi", "pairing_code": code_a},
        headers=headers,
    )
    device_id = resp.json()["id"]

    code_b = await _mint_pairing_code(client, headers, vehicle_b["id"])
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "wp21-android-multi", "pairing_code": code_b},
        headers=headers,
    )
    assert resp.status_code == 200

    resp = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.json()["vehicle_id"] == vehicle_b["id"]

    active = await _active_assignments(session, device_id=device_id)
    assert len(active) == 1
    assert active[0].vehicle_id == vehicle_b["id"]


# --- open-shift guard ----------------------------------------------------------


async def test_repair_blocked_while_current_vehicle_has_open_shift(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="WP21 Shift Guard")

    vehicle_a = (await _create_vehicle(client, headers, rego="WP21-SHIFT-A")).json()
    vehicle_b = (await _create_vehicle(client, headers, rego="WP21-SHIFT-B")).json()

    code_a = await _mint_pairing_code(client, headers, vehicle_a["id"])
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "wp21-android-shift", "pairing_code": code_a},
        headers=headers,
    )
    assert resp.status_code == 200
    device_id = resp.json()["id"]

    # Open a shift on vehicle A directly (no shift router dependency needed --
    # Shift has no cross-domain FKs, see app.models.shift module docstring).
    open_shift = Shift(
        tenant_id=vehicle_a["tenant_id"],
        driver_id="wp21-test-driver",
        vehicle_id=vehicle_a["id"],
        start_at=datetime.now(UTC),
        end_at=None,
    )
    session.add(open_shift)
    await session.commit()

    code_b = await _mint_pairing_code(client, headers, vehicle_b["id"])
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "wp21-android-shift", "pairing_code": code_b},
        headers=headers,
    )
    assert resp.status_code == 409
    assert "open shift" in resp.json()["detail"]

    # Nothing changed: still exactly one active assignment, still pointing at A,
    # and the B pairing code is still unused.
    active = await _active_assignments(session, device_id=device_id)
    assert len(active) == 1
    assert active[0].vehicle_id == vehicle_a["id"]

    pairing_result = await session.execute(
        select(DevicePairingCode).where(DevicePairingCode.code == code_b)
    )
    pairing_b = pairing_result.scalar_one()
    assert pairing_b.used_at is None

    resp = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.json()["vehicle_id"] == vehicle_a["id"]


# --- vehicle deletion closes the active assignment, not just Device.vehicle_id --


async def test_delete_vehicle_closes_active_device_assignment(client, session):
    headers = await auth_headers(client, session, role="admin", tenant_name="WP21 Delete")

    vehicle = (await _create_vehicle(client, headers, rego="WP21-DEL")).json()
    code = await _mint_pairing_code(client, headers, vehicle["id"])
    resp = await client.post(
        "/v1/fleet/devices/register",
        json={"android_id": "wp21-android-del", "pairing_code": code},
        headers=headers,
    )
    assert resp.status_code == 200
    device_id = resp.json()["id"]

    resp = await client.delete(f"/v1/fleet/vehicles/{vehicle['id']}", headers=headers)
    assert resp.status_code == 204

    resp = await client.get(f"/v1/fleet/devices/{device_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["vehicle_id"] is None

    active = await _active_assignments(session, device_id=device_id)
    assert active == []

    all_result = await session.execute(
        select(DeviceAssignment).where(DeviceAssignment.device_id == device_id)
    )
    assignment = all_result.scalar_one()
    assert assignment.unbound_at is not None
    assert assignment.unbound_reason == "vehicle_deleted"


# --- meter_device_id removal ----------------------------------------------------


async def test_meter_device_id_removed_from_vehicle_create(client, session):
    """`Vehicle.meter_device_id` (and the matching schema fields) are gone
    entirely (plan D-3, WP-21/22). This project sets no `extra="forbid"` on
    any request schema (checked directly across app/schemas -- every model
    just uses the Pydantic v2 default, `extra="ignore"`), so an unknown
    `meter_device_id` field in the request body is silently dropped rather
    than 422ing."""
    headers = await auth_headers(client, session, role="admin", tenant_name="WP21 Meter Field")

    resp = await client.post(
        "/v1/fleet/vehicles",
        json={"rego": "WP21-NOFIELD", "vehicle_class": "standard", "meter_device_id": "should-be-ignored"},
        headers=headers,
    )
    assert resp.status_code == 201
    body = resp.json()
    assert "meter_device_id" not in body
