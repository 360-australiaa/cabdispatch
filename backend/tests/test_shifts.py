# Tests for the Shifts domain (`/v1/shifts`).
#
# WP-30/WP-31 (plan D-1): start_shift now runs a full validation chain (gates
# a-h, see app.services.shift.start_shift) instead of the previous 5-line
# unvalidated version, and the two D-1 partial unique indexes on
# app.models.shift.Shift make double-booking a vehicle/driver impossible by
# construction. Every test below that opens a shift now needs a REAL,
# fully-eligible driver + a REAL, fully-operational vehicle + a roster
# authorisation -- the helper fixtures at the top of this file build exactly
# that ("ready" fixtures), mirroring the fixture-building style already used
# in tests/test_device_assignment.py and tests/test_vehicle_readiness.py.
#
# Two deliberate behavior changes from the pre-WP-30 test file, both because
# the OLD test's assumption is now structurally wrong, not because the new
# code is wrong (see docs/ARCHITECTURE_TENANCY_FLEET_COMPLIANCE.md Part 6,
# "do not weaken a test to make it pass -- work out which one is wrong"):
#   1. An unrecognized/unsynced vehicle_id used to silently pass through and
#      open a shift anyway. Gate (d) now requires the vehicle to actually
#      exist -- see test_start_shift_with_unrecognized_vehicle_id_now_404.
#   2. A driver could previously hold two simultaneously-open shifts (the
#      old pagination test relied on this). Gate (g) / the D-1 driver index
#      now forbid it -- see test_list_shifts_pagination_and_filters below,
#      rewritten to use ended-then-reopened shifts instead.
from __future__ import annotations

import asyncio
import csv
import io
import uuid
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import security
from app.models.audit_log import AuditLog
from app.models.compliance import DOC_TYPE_CAMERA_REGISTER, ComplianceDocument
from app.models.device_assignment import DeviceAssignment
from app.models.fleet import (
    OPERATING_AREA_COUNTRY,
    VEHICLE_STATUS_ACTIVE,
    Device,
    Vehicle,
)
from app.models.shift import Shift
from app.models.tenant import Tenant
from app.models.trips import Trip
from app.models.user import ROLE_ADMIN, ROLE_DRIVER, SUITABILITY_CLEAR, SUITABILITY_PENDING, User
from app.models.vehicle_assignment import VehicleAssignment
from tests.conftest import auth_headers


# --- fixture-building helpers -------------------------------------------------


async def _make_tenant(session: AsyncSession, name: str | None = None) -> Tenant:
    tenant = Tenant(name=name or f"Test Tenant {uuid.uuid4()}", plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant


async def _make_user(session: AsyncSession, tenant_id: str, *, role: str = "admin", **overrides) -> User:
    defaults = dict(
        tenant_id=tenant_id,
        role=role,
        name=f"Test {role}",
        email=f"{uuid.uuid4()}@example.com",
        pin_hash=security.hash_password("Test-Passw0rd!"),
        status="active",
    )
    defaults.update(overrides)
    user = User(**defaults)
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


def _headers_for(user: User, tenant_id: str) -> dict:
    token = security.create_access_token(user_id=user.id, tenant_id=tenant_id, role=user.role)
    return {"Authorization": f"Bearer {token}"}


async def _make_ready_driver(session: AsyncSession, tenant_id: str, **overrides) -> User:
    overrides.setdefault("suitability_status", SUITABILITY_CLEAR)
    return await _make_user(session, tenant_id, role=ROLE_DRIVER, **overrides)


async def _make_vehicle_shell(
    session: AsyncSession, tenant_id: str, *, rego: str | None = None, **overrides
) -> Vehicle:
    # Bare Vehicle row -- ACTIVE status but with none of the other readiness
    # gates satisfied. Used directly by gate (e) tests; _make_operational_vehicle
    # below builds on top of this to satisfy every gate.
    defaults = dict(
        tenant_id=tenant_id,
        rego=rego or f"T-{uuid.uuid4().hex[:6].upper()}",
        status=VEHICLE_STATUS_ACTIVE,
        operating_area=OPERATING_AREA_COUNTRY,
    )
    defaults.update(overrides)
    vehicle = Vehicle(**defaults)
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def _make_operational_vehicle(
    session: AsyncSession, tenant_id: str, *, actor_user_id: str, rego: str | None = None
) -> Vehicle:
    # Satisfies every app.services.vehicle_readiness.assert_vehicle_operational
    # gate for operating_area="country" (which skips the duress/tracking
    # gates): active status, camera_serial + camera_register doc, a currently
    # active DeviceAssignment, taxi_licence_no set. Every *_expiry column is
    # left None, which is "unknown" and fails OPEN per that module's
    # documented convention -- not a shortcut, the correct behavior.
    vehicle = await _make_vehicle_shell(
        session,
        tenant_id,
        rego=rego,
        camera_serial="CAM-SERIAL-0001",
        taxi_licence_no="LIC-0001",
    )

    doc = ComplianceDocument(
        tenant_id=tenant_id,
        vehicle_id=vehicle.id,
        doc_type=DOC_TYPE_CAMERA_REGISTER,
        file_path=f"uploads/{tenant_id}/{vehicle.id}/camera_register.pdf",
        original_filename="camera_register.pdf",
        uploaded_by=actor_user_id,
        uploaded_at=datetime.now(UTC),
    )
    session.add(doc)

    device = Device(tenant_id=tenant_id, android_id=f"AND-{uuid.uuid4().hex[:10]}")
    session.add(device)
    await session.commit()
    await session.refresh(device)

    assignment = DeviceAssignment(
        tenant_id=tenant_id,
        device_id=device.id,
        vehicle_id=vehicle.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=actor_user_id,
    )
    session.add(assignment)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def _authorise_driver(
    session: AsyncSession, tenant_id: str, *, vehicle_id: str, driver_id: str, actor_user_id: str
) -> VehicleAssignment:
    assignment = VehicleAssignment(
        tenant_id=tenant_id,
        vehicle_id=vehicle_id,
        driver_id=driver_id,
        authorised_by_user_id=actor_user_id,
        authorised_at=datetime.now(UTC),
    )
    session.add(assignment)
    await session.commit()
    await session.refresh(assignment)
    return assignment


class ReadyFixture:
    # Bag of everything a happy-path start_shift call needs: a real tenant,
    # an admin (the fixture-building actor, also usable as a dispatcher
    # caller), a fully-eligible driver, and an operational, roster-authorised
    # vehicle.
    def __init__(self, tenant, admin, driver, vehicle):
        self.tenant = tenant
        self.admin = admin
        self.driver = driver
        self.vehicle = vehicle

    @property
    def admin_headers(self) -> dict:
        return _headers_for(self.admin, self.tenant.id)

    @property
    def driver_headers(self) -> dict:
        return _headers_for(self.driver, self.tenant.id)


async def _make_ready_fixture(session: AsyncSession, *, tenant_name: str | None = None) -> ReadyFixture:
    tenant = await _make_tenant(session, tenant_name)
    admin = await _make_user(session, tenant.id, role=ROLE_ADMIN)
    driver = await _make_ready_driver(session, tenant.id)
    vehicle = await _make_operational_vehicle(session, tenant.id, actor_user_id=admin.id)
    await _authorise_driver(
        session, tenant.id, vehicle_id=vehicle.id, driver_id=driver.id, actor_user_id=admin.id
    )
    return ReadyFixture(tenant, admin, driver, vehicle)


def _trip_kwargs(*, tenant_id: str, shift_id: str, driver_id: str, vehicle_id: str, **overrides):
    base = {
        "id": str(uuid.uuid4()),
        "tenant_id": tenant_id,
        "client_uuid": str(uuid.uuid4()),
        "vehicle_id": vehicle_id,
        "driver_id": driver_id,
        "shift_id": shift_id,
        "tariff_id": str(uuid.uuid4()),
        "type": "rank_hail",
        "status": "closed",
        "start_at": datetime.now(UTC),
        "end_at": datetime.now(UTC),
        "start_lat": -33.87,
        "start_lng": 151.21,
        "distance_m": 5000,
        "payment_method": "cash",
        "total": Decimal("25.00"),
    }
    base.update(overrides)
    return base


# --- happy path ----------------------------------------------------------------


async def test_start_shift_opens_shift(client: AsyncClient, session: AsyncSession):
    # Gate (h) happy path: the driver opens their OWN shift.
    fx = await _make_ready_fixture(session, tenant_name="Happy Path Co")

    resp = await client.post(
        "/v1/shifts/start",
        json={
            "driver_id": fx.driver.id,
            "vehicle_id": fx.vehicle.id,
            "inspection_json": {"tyres": "ok", "lights": "ok"},
        },
        headers=fx.driver_headers,
    )

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_id"] == fx.driver.id
    assert body["vehicle_id"] == fx.vehicle.id
    assert body["end_at"] is None
    assert body["trips_count"] == 0
    assert body["inspection_json"] == {"tyres": "ok", "lights": "ok"}


async def test_dispatcher_can_start_shift_for_driver(client: AsyncClient, session: AsyncSession):
    # Gate (h) happy path: a dispatcher/admin/owner may open a shift FOR a
    # different driver.
    fx = await _make_ready_fixture(session, tenant_name="Dispatcher Opens Co")

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["driver_id"] == fx.driver.id


async def test_plain_driver_cannot_start_shift_for_different_driver(
    client: AsyncClient, session: AsyncSession
):
    # Gate (h) failure: a plain driver (not the driver on the shift, not a
    # dispatcher/admin/owner) may not open a shift for someone else. 403.
    fx = await _make_ready_fixture(session, tenant_name="Caller Guard Co")
    other_driver = await _make_ready_driver(session, fx.tenant.id)
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=fx.vehicle.id,
        driver_id=other_driver.id,
        actor_user_id=fx.admin.id,
    )

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": other_driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,  # fx.driver, trying to open a shift for other_driver
    )
    assert resp.status_code == 403, resp.text


# --- gate (a): driver exists / role / status ------------------------------------


async def test_start_shift_driver_not_found(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate A Not Found Co")

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": str(uuid.uuid4()), "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 404, resp.text


async def test_start_shift_driver_wrong_role(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate A Wrong Role Co")
    non_driver = await _make_user(session, fx.tenant.id, role="dispatcher")

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": non_driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 422, resp.text


async def test_start_shift_driver_not_active(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate A Inactive Co")
    inactive_driver = await _make_ready_driver(session, fx.tenant.id, status="suspended")
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=fx.vehicle.id,
        driver_id=inactive_driver.id,
        actor_user_id=fx.admin.id,
    )

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": inactive_driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 422, resp.text


# --- gate (b): licence + authority unexpired ------------------------------------


async def test_start_shift_driver_licence_expired(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate B Licence Co")
    expired_driver = await _make_ready_driver(
        session, fx.tenant.id, driver_license_expiry=date.today() - timedelta(days=1)
    )
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=fx.vehicle.id,
        driver_id=expired_driver.id,
        actor_user_id=fx.admin.id,
    )

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": expired_driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 422, resp.text


async def test_start_shift_driver_authority_expired(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate B Authority Co")
    expired_driver = await _make_ready_driver(
        session, fx.tenant.id, driver_authority_expiry=date.today() - timedelta(days=1)
    )
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=fx.vehicle.id,
        driver_id=expired_driver.id,
        actor_user_id=fx.admin.id,
    )

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": expired_driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 422, resp.text


async def test_start_shift_driver_licence_unknown_passes_gate_b(
    client: AsyncClient, session: AsyncSession
):
    # None expiry ("unknown") must NOT block -- fail-open convention.
    fx = await _make_ready_fixture(session, tenant_name="Gate B Unknown Co")
    assert fx.driver.driver_license_expiry is None
    assert fx.driver.driver_authority_expiry is None

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 201, resp.text


# --- gate (c): suitability clear ------------------------------------------------


async def test_start_shift_driver_not_suitable(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate C Suitability Co")
    pending_driver = await _make_user(
        session,
        fx.tenant.id,
        role=ROLE_DRIVER,
        suitability_status=SUITABILITY_PENDING,
    )
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=fx.vehicle.id,
        driver_id=pending_driver.id,
        actor_user_id=fx.admin.id,
    )

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": pending_driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 422, resp.text


# --- gate (d): vehicle exists ---------------------------------------------------


async def test_start_shift_vehicle_not_found(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate D Not Found Co")

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": str(uuid.uuid4())},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 404, resp.text


# --- gate (e): vehicle operational ----------------------------------------------


async def test_start_shift_vehicle_not_operational(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate E Not Ready Co")
    # A bare active vehicle -- no camera_register doc, no device assignment,
    # no taxi_licence_no -- fails app.services.vehicle_readiness's checklist.
    not_ready_vehicle = await _make_vehicle_shell(session, fx.tenant.id)
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=not_ready_vehicle.id,
        driver_id=fx.driver.id,
        actor_user_id=fx.admin.id,
    )

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": not_ready_vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 422, resp.text
    body = resp.json()
    reasons = body["detail"]["reasons"] if isinstance(body["detail"], dict) else []
    assert reasons, "expected the not-operational reasons list to be non-empty"


# --- gate (f): driver is on the vehicle's roster --------------------------------


async def test_start_shift_driver_not_on_roster(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate F Roster Co")
    # A second, fully-eligible driver who is simply never authorised on
    # fx.vehicle's roster.
    unrostered_driver = await _make_ready_driver(session, fx.tenant.id)

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": unrostered_driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert resp.status_code == 422, resp.text


# --- gate (g): neither driver nor vehicle already has an open shift ------------


async def test_start_shift_driver_already_on_shift(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate G Driver Co")
    other_vehicle = await _make_operational_vehicle(session, fx.tenant.id, actor_user_id=fx.admin.id)
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=other_vehicle.id,
        driver_id=fx.driver.id,
        actor_user_id=fx.admin.id,
    )

    first = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert first.status_code == 201, first.text

    second = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": other_vehicle.id},
        headers=fx.admin_headers,
    )
    assert second.status_code == 409, second.text


async def test_start_shift_vehicle_already_in_use(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Gate G Vehicle Co")
    other_driver = await _make_ready_driver(session, fx.tenant.id)
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=fx.vehicle.id,
        driver_id=other_driver.id,
        actor_user_id=fx.admin.id,
    )

    first = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert first.status_code == 201, first.text

    second = await client.post(
        "/v1/shifts/start",
        json={"driver_id": other_driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert second.status_code == 409, second.text


# --- audit logging (WP-34, start side) ------------------------------------------


async def test_start_shift_writes_audit_log_entry(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Audit Start Co")

    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    assert resp.status_code == 201, resp.text
    shift_id = resp.json()["id"]

    result = await session.execute(
        select(AuditLog).where(
            AuditLog.tenant_id == fx.tenant.id,
            AuditLog.entity_type == "shift",
            AuditLog.entity_id == shift_id,
            AuditLog.action == "shift_started",
        )
    )
    row = result.scalar_one_or_none()
    assert row is not None
    assert row.actor_user_id == fx.driver.id
    assert row.after_json["driver_id"] == fx.driver.id
    assert row.after_json["vehicle_id"] == fx.vehicle.id


async def test_end_shift_writes_audit_log_entry(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Audit End Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    end_resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "0", "reconciled": True},
        headers=fx.admin_headers,
    )
    assert end_resp.status_code == 200, end_resp.text

    result = await session.execute(
        select(AuditLog).where(
            AuditLog.tenant_id == fx.tenant.id,
            AuditLog.entity_type == "shift",
            AuditLog.entity_id == shift_id,
            AuditLog.action == "shift_ended",
        )
    )
    assert result.scalar_one_or_none() is not None


async def test_update_shift_writes_audit_log_entry(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Audit Update Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/shifts/{shift_id}",
        json={"inspection_json": {"note": "corrected"}},
        headers=fx.admin_headers,
    )
    assert patch_resp.status_code == 200, patch_resp.text

    result = await session.execute(
        select(AuditLog).where(
            AuditLog.tenant_id == fx.tenant.id,
            AuditLog.entity_type == "shift",
            AuditLog.entity_id == shift_id,
            AuditLog.action == "shift_updated",
        )
    )
    assert result.scalar_one_or_none() is not None


async def test_delete_shift_writes_audit_log_entry(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Audit Delete Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    delete_resp = await client.delete(f"/v1/shifts/{shift_id}", headers=fx.admin_headers)
    assert delete_resp.status_code == 204, delete_resp.text

    result = await session.execute(
        select(AuditLog).where(
            AuditLog.tenant_id == fx.tenant.id,
            AuditLog.entity_type == "shift",
            AuditLog.entity_id == shift_id,
            AuditLog.action == "shift_deleted",
        )
    )
    assert result.scalar_one_or_none() is not None


# --- D-1 partial unique indexes, proven at the DB level -------------------------
# Mirrors the exact pattern in tests/test_device_assignment.py: construct two
# rows that collide under the partial index directly via the ORM (bypassing
# the service layer entirely) and assert IntegrityError.


async def test_db_partial_unique_index_blocks_two_open_shifts_same_vehicle(
    session: AsyncSession,
):
    fx = await _make_ready_fixture(session, tenant_name="DB Index Vehicle Co")
    other_driver = await _make_ready_driver(session, fx.tenant.id)

    first = Shift(
        tenant_id=fx.tenant.id,
        driver_id=fx.driver.id,
        vehicle_id=fx.vehicle.id,
        start_at=datetime.now(UTC),
    )
    session.add(first)
    await session.commit()

    second = Shift(
        tenant_id=fx.tenant.id,
        driver_id=other_driver.id,
        vehicle_id=fx.vehicle.id,  # same vehicle, both end_at IS NULL
        start_at=datetime.now(UTC),
    )
    session.add(second)
    with pytest.raises(IntegrityError):
        await session.commit()
    await session.rollback()


async def test_db_partial_unique_index_blocks_two_open_shifts_same_driver(
    session: AsyncSession,
):
    fx = await _make_ready_fixture(session, tenant_name="DB Index Driver Co")
    other_vehicle = await _make_operational_vehicle(session, fx.tenant.id, actor_user_id=fx.admin.id)

    first = Shift(
        tenant_id=fx.tenant.id,
        driver_id=fx.driver.id,
        vehicle_id=fx.vehicle.id,
        start_at=datetime.now(UTC),
    )
    session.add(first)
    await session.commit()

    second = Shift(
        tenant_id=fx.tenant.id,
        driver_id=fx.driver.id,  # same driver, both end_at IS NULL
        vehicle_id=other_vehicle.id,
        start_at=datetime.now(UTC),
    )
    session.add(second)
    with pytest.raises(IntegrityError):
        await session.commit()
    await session.rollback()


async def test_db_partial_unique_index_allows_reopen_after_close(session: AsyncSession):
    # Proves the index really is partial (WHERE end_at IS NULL), not a plain
    # unique constraint: once the first shift is closed, a second open shift
    # on the SAME vehicle must succeed.
    fx = await _make_ready_fixture(session, tenant_name="DB Index Partial Co")
    other_driver = await _make_ready_driver(session, fx.tenant.id)

    first = Shift(
        tenant_id=fx.tenant.id,
        driver_id=fx.driver.id,
        vehicle_id=fx.vehicle.id,
        start_at=datetime.now(UTC),
    )
    session.add(first)
    await session.commit()

    first.end_at = datetime.now(UTC)
    await session.commit()

    second = Shift(
        tenant_id=fx.tenant.id,
        driver_id=other_driver.id,
        vehicle_id=fx.vehicle.id,
        start_at=datetime.now(UTC),
    )
    session.add(second)
    await session.commit()
    await session.refresh(second)
    assert second.id is not None
    assert second.end_at is None


# --- real concurrency test (WP-31) ----------------------------------------------


async def test_concurrent_shift_starts_on_same_vehicle_exactly_one_wins(
    app, session: AsyncSession
):
    # Fires two genuinely concurrent POST /v1/shifts/start calls for the SAME
    # vehicle (two different, both otherwise-fully-eligible drivers) via
    # asyncio.gather over two independent AsyncClient instances (independent
    # connections, not sequential calls written next to each other) and
    # asserts exactly one succeeds (201) and the other fails cleanly (409) --
    # never both succeeding. This is the direct test of the D-1 guarantee the
    # whole work package exists for.
    #
    # NOTE: app.services.tenant_settings.get_or_create_settings (a defensive
    # fallback used by vehicle_readiness, unrelated to WP-30/31) has its own,
    # separate TOCTOU race on first access -- two concurrent requests for a
    # tenant with no settings row yet can both attempt to INSERT and one hits
    # an IntegrityError. That is a pre-existing latent bug in that module,
    # out of scope for this work package; pre-warming the row here (as the
    # normal production path already does at tenant-creation time, WP-17)
    # sidesteps it so this test exercises only the D-1 shift guarantee it is
    # actually about. Flagged in this work package's final report.
    from app.services.tenant_settings import get_or_create_settings

    fx = await _make_ready_fixture(session, tenant_name="Concurrency Co")
    await get_or_create_settings(session, tenant_id=fx.tenant.id)
    driver_b = await _make_ready_driver(session, fx.tenant.id)
    await _authorise_driver(
        session,
        fx.tenant.id,
        vehicle_id=fx.vehicle.id,
        driver_id=driver_b.id,
        actor_user_id=fx.admin.id,
    )

    transport = ASGITransport(app=app)

    async def _attempt(driver_id: str, headers: dict):
        async with AsyncClient(transport=transport, base_url="http://testserver") as c:
            return await c.post(
                "/v1/shifts/start",
                json={"driver_id": driver_id, "vehicle_id": fx.vehicle.id},
                headers=headers,
            )

    results = await asyncio.gather(
        _attempt(fx.driver.id, fx.admin_headers),
        _attempt(driver_b.id, fx.admin_headers),
        return_exceptions=True,
    )

    statuses = []
    for r in results:
        assert not isinstance(r, Exception), f"request raised instead of returning a clean status: {r}"
        statuses.append(r.status_code)

    assert statuses.count(201) == 1, f"expected exactly one 201, got statuses={statuses}"
    assert statuses.count(409) == 1, f"expected exactly one 409, got statuses={statuses}"


# --- generic CRUD ---------------------------------------------------------------


async def test_get_shift_by_id(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Get By Id Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    resp = await client.get(f"/v1/shifts/{shift_id}", headers=fx.admin_headers)
    assert resp.status_code == 200
    assert resp.json()["id"] == shift_id


async def test_get_shift_not_found(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(f"/v1/shifts/{uuid.uuid4()}", headers=headers)
    assert resp.status_code == 404


async def test_end_shift_recomputes_aggregates_from_trips(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Recon Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift = start_resp.json()
    shift_id = shift["id"]
    tenant_id = shift["tenant_id"]

    # Two cash trips + one card trip, aggregated at shift-end time.
    session.add(
        Trip(
            **_trip_kwargs(
                tenant_id=tenant_id,
                shift_id=shift_id,
                driver_id=fx.driver.id,
                vehicle_id=fx.vehicle.id,
                payment_method="cash",
                total=Decimal("20.00"),
                distance_m=4000,
            )
        )
    )
    session.add(
        Trip(
            **_trip_kwargs(
                tenant_id=tenant_id,
                shift_id=shift_id,
                driver_id=fx.driver.id,
                vehicle_id=fx.vehicle.id,
                payment_method="cash",
                total=Decimal("15.50"),
                distance_m=3000,
            )
        )
    )
    session.add(
        Trip(
            **_trip_kwargs(
                tenant_id=tenant_id,
                shift_id=shift_id,
                driver_id=fx.driver.id,
                vehicle_id=fx.vehicle.id,
                payment_method="tap_to_pay",
                total=Decimal("42.75"),
                distance_m=8000,
            )
        )
    )
    # A trip belonging to a DIFFERENT shift must not be counted.
    other_shift_id = str(uuid.uuid4())
    session.add(
        Trip(
            **_trip_kwargs(
                tenant_id=tenant_id,
                shift_id=other_shift_id,
                driver_id=fx.driver.id,
                vehicle_id=fx.vehicle.id,
                payment_method="cash",
                total=Decimal("999.00"),
                distance_m=99000,
            )
        )
    )
    await session.commit()

    resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "4.20", "reconciled": True},
        headers=fx.admin_headers,
    )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["end_at"] is not None
    assert body["trips_count"] == 3
    assert Decimal(body["km_total"]) == Decimal("15.000")
    assert Decimal(body["cash_total"]) == Decimal("35.50")
    assert Decimal(body["card_total"]) == Decimal("42.75")
    assert Decimal(body["psl_owed"]) == Decimal("4.20")
    assert body["reconciled"] is True


async def test_start_and_end_break_happy_path(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Break Happy Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]
    assert start_resp.json()["break_started_at"] is None
    assert start_resp.json()["break_taken"] is False

    start_break_resp = await client.post(
        f"/v1/shifts/{shift_id}/break/start", json={}, headers=fx.driver_headers
    )
    assert start_break_resp.status_code == 200, start_break_resp.text
    body = start_break_resp.json()
    assert body["break_started_at"] is not None
    assert body["break_taken"] is False

    end_break_resp = await client.post(
        f"/v1/shifts/{shift_id}/break/end", json={}, headers=fx.driver_headers
    )
    assert end_break_resp.status_code == 200, end_break_resp.text
    body = end_break_resp.json()
    assert body["break_started_at"] is None
    assert body["break_taken"] is True


async def test_break_start_conflicts_when_already_in_progress(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Break Conflict Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    first = await client.post(
        f"/v1/shifts/{shift_id}/break/start", json={}, headers=fx.driver_headers
    )
    assert first.status_code == 200

    second = await client.post(
        f"/v1/shifts/{shift_id}/break/start", json={}, headers=fx.driver_headers
    )
    assert second.status_code == 409


async def test_break_start_conflicts_when_shift_already_ended(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Break Ended Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    end_resp = await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "0", "reconciled": True},
        headers=fx.admin_headers,
    )
    assert end_resp.status_code == 200

    break_resp = await client.post(
        f"/v1/shifts/{shift_id}/break/start", json={}, headers=fx.driver_headers
    )
    assert break_resp.status_code == 409


async def test_break_end_conflicts_when_no_break_in_progress(
    client: AsyncClient, session: AsyncSession
):
    fx = await _make_ready_fixture(session, tenant_name="Break None Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    resp = await client.post(f"/v1/shifts/{shift_id}/break/end", json={}, headers=fx.driver_headers)
    assert resp.status_code == 409


async def test_end_shift_twice_conflicts(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="End Twice Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    first = await client.post(
        f"/v1/shifts/{shift_id}/end", json={"psl_owed": "0", "reconciled": True}, headers=fx.admin_headers
    )
    assert first.status_code == 200

    second = await client.post(
        f"/v1/shifts/{shift_id}/end", json={"psl_owed": "0", "reconciled": True}, headers=fx.admin_headers
    )
    assert second.status_code == 409


async def test_report_endpoint_returns_summary(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Report Summary Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "1.32", "reconciled": True},
        headers=fx.admin_headers,
    )

    resp = await client.get(f"/v1/shifts/{shift_id}/report", headers=fx.admin_headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["shift_id"] == shift_id
    assert body["total_takings"] == "0.00"
    assert body["duration_minutes"] is not None
    assert "generated_at" in body


async def test_list_shifts_pagination_and_filters(client: AsyncClient, session: AsyncSession):
    # NOTE: driver_a can no longer hold two simultaneously-open shifts (D-1 /
    # gate g) -- unlike the pre-WP-30 version of this test, driver_a's first
    # shift is ended before the second is opened, and active_only now
    # correctly reports only the still-open one.
    fx = await _make_ready_fixture(session, tenant_name="Pagination Co")
    driver_a = fx.driver
    vehicle_a2 = await _make_operational_vehicle(session, fx.tenant.id, actor_user_id=fx.admin.id)
    await _authorise_driver(
        session, fx.tenant.id, vehicle_id=vehicle_a2.id, driver_id=driver_a.id, actor_user_id=fx.admin.id
    )
    driver_b = await _make_ready_driver(session, fx.tenant.id)
    vehicle_b = await _make_operational_vehicle(session, fx.tenant.id, actor_user_id=fx.admin.id)
    await _authorise_driver(
        session, fx.tenant.id, vehicle_id=vehicle_b.id, driver_id=driver_b.id, actor_user_id=fx.admin.id
    )

    # driver_a: shift #1 (ended), then shift #2 (left open).
    shift1 = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_a.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    assert shift1.status_code == 201, shift1.text
    await client.post(
        f"/v1/shifts/{shift1.json()['id']}/end",
        json={"psl_owed": "0", "reconciled": True},
        headers=fx.admin_headers,
    )
    shift2 = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_a.id, "vehicle_id": vehicle_a2.id},
        headers=fx.admin_headers,
    )
    assert shift2.status_code == 201, shift2.text

    # driver_b: one open shift.
    shift3 = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_b.id, "vehicle_id": vehicle_b.id},
        headers=fx.admin_headers,
    )
    assert shift3.status_code == 201, shift3.text

    all_resp = await client.get("/v1/shifts", headers=fx.admin_headers)
    assert all_resp.status_code == 200
    all_body = all_resp.json()
    assert all_body["total"] >= 3
    assert all_body["limit"] == 50
    assert all_body["offset"] == 0

    filtered = await client.get(
        "/v1/shifts", params={"driver_id": driver_a.id}, headers=fx.admin_headers
    )
    assert filtered.status_code == 200
    assert filtered.json()["total"] == 2

    paged = await client.get(
        "/v1/shifts",
        params={"driver_id": driver_a.id, "limit": 1, "offset": 1},
        headers=fx.admin_headers,
    )
    assert paged.status_code == 200
    assert len(paged.json()["items"]) == 1

    active_only = await client.get(
        "/v1/shifts",
        params={"driver_id": driver_a.id, "active_only": True},
        headers=fx.admin_headers,
    )
    assert active_only.json()["total"] == 1  # only shift #2 is still open


async def test_driver_cannot_patch_or_delete_shift(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="RBAC Co")
    other_tenant_driver_headers = await auth_headers(
        client, session, role="driver", tenant_name="RBAC Co 2"
    )

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.admin_headers,
    )
    shift_id = start_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/shifts/{shift_id}", json={"reconciled": True}, headers=fx.admin_headers
    )
    assert patch_resp.status_code == 200

    # A driver in a DIFFERENT tenant can't even see this shift (tenant isolation),
    # and drivers generally are barred from PATCH/DELETE regardless of tenant.
    forbidden_resp = await client.patch(
        f"/v1/shifts/{shift_id}", json={"reconciled": False}, headers=other_tenant_driver_headers
    )
    assert forbidden_resp.status_code in (403, 404)


async def test_update_and_delete_shift_as_admin(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Update Delete Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    patch_resp = await client.patch(
        f"/v1/shifts/{shift_id}",
        json={"inspection_json": {"note": "corrected by admin"}},
        headers=fx.admin_headers,
    )
    assert patch_resp.status_code == 200
    assert patch_resp.json()["inspection_json"] == {"note": "corrected by admin"}

    delete_resp = await client.delete(f"/v1/shifts/{shift_id}", headers=fx.admin_headers)
    assert delete_resp.status_code == 204

    get_resp = await client.get(f"/v1/shifts/{shift_id}", headers=fx.admin_headers)
    assert get_resp.status_code == 404


async def test_tenant_isolation_on_shifts(client: AsyncClient, session: AsyncSession):
    fx_a = await _make_ready_fixture(session, tenant_name="Tenant A")
    fx_b = await _make_ready_fixture(session, tenant_name="Tenant B")

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx_a.driver.id, "vehicle_id": fx_a.vehicle.id},
        headers=fx_a.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    cross_tenant_resp = await client.get(f"/v1/shifts/{shift_id}", headers=fx_b.admin_headers)
    assert cross_tenant_resp.status_code == 404


async def test_report_pdf_endpoint_returns_pdf(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Report PDF Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "1.32", "reconciled": True},
        headers=fx.admin_headers,
    )

    resp = await client.get(f"/v1/shifts/{shift_id}/report.pdf", headers=fx.admin_headers)
    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"] == "application/pdf"
    assert resp.content.startswith(b"%PDF")
    assert len(resp.content) > 0


async def test_report_csv_endpoint_returns_csv(client: AsyncClient, session: AsyncSession):
    fx = await _make_ready_fixture(session, tenant_name="Report CSV Co")
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": fx.vehicle.id},
        headers=fx.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    await client.post(
        f"/v1/shifts/{shift_id}/end",
        json={"psl_owed": "1.32", "reconciled": True},
        headers=fx.admin_headers,
    )

    resp = await client.get(f"/v1/shifts/{shift_id}/report.csv", headers=fx.admin_headers)
    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"].startswith("text/csv")
    assert len(resp.content) > 0

    rows = list(csv.reader(io.StringIO(resp.content.decode("utf-8"))))
    assert len(rows) == 2
    header, values = rows
    assert header[0] == "shift_id"
    assert header[1] == "tenant_id"
    assert values[0] == shift_id


async def test_report_pdf_not_found(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(f"/v1/shifts/{uuid.uuid4()}/report.pdf", headers=headers)
    assert resp.status_code == 404


async def test_report_csv_not_found(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(f"/v1/shifts/{uuid.uuid4()}/report.csv", headers=headers)
    assert resp.status_code == 404


async def test_report_pdf_csv_tenant_isolation(client: AsyncClient, session: AsyncSession):
    fx_a = await _make_ready_fixture(session, tenant_name="Report Tenant A")
    fx_b = await _make_ready_fixture(session, tenant_name="Report Tenant B")

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx_a.driver.id, "vehicle_id": fx_a.vehicle.id},
        headers=fx_a.driver_headers,
    )
    shift_id = start_resp.json()["id"]

    pdf_resp = await client.get(f"/v1/shifts/{shift_id}/report.pdf", headers=fx_b.admin_headers)
    assert pdf_resp.status_code == 404

    csv_resp = await client.get(f"/v1/shifts/{shift_id}/report.csv", headers=fx_b.admin_headers)
    assert csv_resp.status_code == 404


# --- generic POST "" (admin backfill path) --------------------------------------


async def test_generic_create_shift_respects_d1_index_via_clean_409(
    client: AsyncClient, session: AsyncSession
):
    # WP-30 step 5: the generic POST "" backfill route deliberately skips the
    # full start_shift validation chain, but MUST still respect the D-1
    # partial unique indexes -- and must translate the resulting
    # IntegrityError into a clean 409, not a raw 500.
    fx = await _make_ready_fixture(session, tenant_name="Backfill 409 Co")

    first = await client.post(
        "/v1/shifts",
        json={
            "driver_id": fx.driver.id,
            "vehicle_id": fx.vehicle.id,
            "start_at": datetime.now(UTC).isoformat(),
        },
        headers=fx.admin_headers,
    )
    assert first.status_code == 201, first.text

    second = await client.post(
        "/v1/shifts",
        json={
            "driver_id": fx.driver.id,  # same driver -- collides on uq_shifts_one_open_per_driver
            "vehicle_id": str(uuid.uuid4()),
            "start_at": datetime.now(UTC).isoformat(),
        },
        headers=fx.admin_headers,
    )
    assert second.status_code == 409, second.text


# --- vehicle_id canonicalization (start_shift) --------------------------------
# Regression coverage for the finding surfaced by the Android-side agent
# (2026-08-28): POST /v1/shifts/start and POST /v1/fleet/positions disagreed
# on whether vehicle_id was the fleet-vehicle UUID or the rego for the same
# physical car. D-1's partial unique index only holds if shifts.vehicle_id is
# canonical, so start_shift must resolve either shape to the real UUID before
# the Shift row is constructed (see app.services.shift._canonical_vehicle_id).
# Every test below now needs a fully operational, roster-authorised vehicle
# (built directly via the ORM on top of the API-created row) since these
# calls now go through the full WP-30 validation chain.


async def _make_operational_vehicle_via_api(
    client: AsyncClient, session: AsyncSession, *, headers: dict, tenant_id: str, actor_user_id: str, rego: str
) -> Vehicle:
    vehicle_resp = await client.post(
        "/v1/fleet/vehicles",
        json={"rego": rego, "vehicle_class": "standard"},
        headers=headers,
    )
    assert vehicle_resp.status_code == 201, vehicle_resp.text
    vehicle_id = vehicle_resp.json()["id"]

    result = await session.execute(select(Vehicle).where(Vehicle.id == vehicle_id))
    vehicle = result.scalar_one()
    vehicle.status = VEHICLE_STATUS_ACTIVE
    vehicle.camera_serial = "CAM-SERIAL-API"
    vehicle.taxi_licence_no = "LIC-API"
    await session.commit()

    doc = ComplianceDocument(
        tenant_id=tenant_id,
        vehicle_id=vehicle.id,
        doc_type=DOC_TYPE_CAMERA_REGISTER,
        file_path=f"uploads/{tenant_id}/{vehicle.id}/camera_register.pdf",
        original_filename="camera_register.pdf",
        uploaded_by=actor_user_id,
        uploaded_at=datetime.now(UTC),
    )
    session.add(doc)

    device = Device(tenant_id=tenant_id, android_id=f"AND-{uuid.uuid4().hex[:10]}")
    session.add(device)
    await session.commit()
    await session.refresh(device)

    assignment = DeviceAssignment(
        tenant_id=tenant_id,
        device_id=device.id,
        vehicle_id=vehicle.id,
        bound_at=datetime.now(UTC),
        bound_by_user_id=actor_user_id,
    )
    session.add(assignment)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def test_start_shift_with_rego_resolves_to_vehicle_uuid(
    client: AsyncClient, session: AsyncSession
):
    tenant = await _make_tenant(session, "Canon Co")
    admin = await _make_user(session, tenant.id, role=ROLE_ADMIN)
    admin_headers = _headers_for(admin, tenant.id)
    driver = await _make_ready_driver(session, tenant.id)

    vehicle = await _make_operational_vehicle_via_api(
        client, session, headers=admin_headers, tenant_id=tenant.id, actor_user_id=admin.id, rego="CANON-1"
    )
    await _authorise_driver(
        session, tenant.id, vehicle_id=vehicle.id, driver_id=driver.id, actor_user_id=admin.id
    )

    start_resp = await client.post(
        "/v1/shifts/start",
        # Client sends the rego, not the UUID -- the exact shape mismatch the
        # Android-side agent found live between shifts/start and fleet/positions.
        json={"driver_id": driver.id, "vehicle_id": "CANON-1"},
        headers=admin_headers,
    )
    assert start_resp.status_code == 201, start_resp.text
    shift = start_resp.json()
    # The persisted shift row must hold the real vehicle UUID, not the raw
    # rego the client sent -- otherwise a second shift opened with the UUID
    # for the same car would not collide with this one under D-1's index.
    assert shift["vehicle_id"] == vehicle.id


async def test_start_shift_with_uuid_stays_canonical(client: AsyncClient, session: AsyncSession):
    tenant = await _make_tenant(session, "Canon Co UUID")
    admin = await _make_user(session, tenant.id, role=ROLE_ADMIN)
    admin_headers = _headers_for(admin, tenant.id)
    driver = await _make_ready_driver(session, tenant.id)

    vehicle = await _make_operational_vehicle_via_api(
        client, session, headers=admin_headers, tenant_id=tenant.id, actor_user_id=admin.id, rego="CANON-2"
    )
    await _authorise_driver(
        session, tenant.id, vehicle_id=vehicle.id, driver_id=driver.id, actor_user_id=admin.id
    )

    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver.id, "vehicle_id": vehicle.id},
        headers=admin_headers,
    )
    assert start_resp.status_code == 201, start_resp.text
    assert start_resp.json()["vehicle_id"] == vehicle.id


async def test_start_shift_two_shapes_for_same_vehicle_collide(
    client: AsyncClient, session: AsyncSession
):
    # The actual guarantee this fix exists for: a shift opened with the rego
    # and a second shift attempted with the UUID, for the SAME physical
    # vehicle, must be recognized as the SAME vehicle_id -- proven here by
    # the second attempt being rejected 409 (vehicle already has an open
    # shift) even though it names the vehicle differently. If canonicalization
    # were broken, the two different id shapes would look like two different
    # vehicles and the second call would wrongly succeed.
    tenant = await _make_tenant(session, "Canon Co Collide")
    admin = await _make_user(session, tenant.id, role=ROLE_ADMIN)
    admin_headers = _headers_for(admin, tenant.id)
    driver_1 = await _make_ready_driver(session, tenant.id)
    driver_2 = await _make_ready_driver(session, tenant.id)

    vehicle = await _make_operational_vehicle_via_api(
        client, session, headers=admin_headers, tenant_id=tenant.id, actor_user_id=admin.id, rego="CANON-3"
    )
    await _authorise_driver(
        session, tenant.id, vehicle_id=vehicle.id, driver_id=driver_1.id, actor_user_id=admin.id
    )
    await _authorise_driver(
        session, tenant.id, vehicle_id=vehicle.id, driver_id=driver_2.id, actor_user_id=admin.id
    )

    first = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_1.id, "vehicle_id": "CANON-3"},
        headers=admin_headers,
    )
    assert first.status_code == 201, first.text
    assert first.json()["vehicle_id"] == vehicle.id

    second = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_2.id, "vehicle_id": vehicle.id},
        headers=admin_headers,
    )
    assert second.status_code == 409, second.text


async def test_start_shift_with_unrecognized_vehicle_id_now_404(
    client: AsyncClient, session: AsyncSession
):
    # BEHAVIOR CHANGE from the pre-WP-30 version of this test: an id matching
    # neither an existing vehicle's UUID nor its rego used to silently pass
    # through and open a shift anyway. Gate (d) now requires the vehicle to
    # actually exist in this tenant -- an unsynced/offline vehicle_id must be
    # corrected (or the vehicle created) before a shift can be opened for it,
    # closing the "any string opens a shift" hole documented in the
    # architecture plan. The Android-side team needs a real spec update for
    # this -- see this work package's final report.
    fx = await _make_ready_fixture(session, tenant_name="Canon Co Unknown")

    unknown_id = "UNSYNCED-999"
    start_resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": fx.driver.id, "vehicle_id": unknown_id},
        headers=fx.admin_headers,
    )
    assert start_resp.status_code == 404, start_resp.text
