"""Tests for the TEMPORARY force-wipe endpoint (`POST
/v1/fleet/wipe-test-data/force`) — see app.services.fleet_wipe's module
docstring for full context. The ordinary per-row delete endpoints
(`DELETE /v1/fleet/vehicles/{id}`, `/v1/fleet/devices/{id}`, `/v1/users/{id}`)
and their evidence-blocking behaviour are covered by test_fleet.py /
test_users.py and are NOT re-tested here — this file only covers the new
force path and confirms it doesn't change the old one."""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from sqlalchemy import func, select

from app.core import security
from app.models.audit_log import AuditLog
from app.models.compliance import ComplianceDocument
from app.models.driver_engagement import TripRating, WalletTransaction
from app.models.fleet import Device, Vehicle
from app.models.psl_ledger import PSLLedgerEntry, PSLTopUp
from app.models.shift import Shift
from app.models.tariffs import Tariff, TariffChangeLog
from app.models.tenant import Tenant
from app.models.trips import TRIP_STATUS_CLOSED, Trip
from app.models.user import ROLE_DRIVER, User
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


def _unique_email(prefix: str = "wipe") -> str:
    return f"{prefix}-{uuid.uuid4()}@example.com"


async def _tenant(session, name: str = "Force Wipe Tenant") -> str:
    tenant = Tenant(name=name, plan="standard")
    session.add(tenant)
    await session.commit()
    await session.refresh(tenant)
    return tenant.id


async def _user(session, *, tenant_id: str, role: str = ROLE_DRIVER) -> str:
    user = User(
        tenant_id=tenant_id,
        role=role,
        name=f"Test {role}",
        email=_unique_email(role),
        pin_hash=security.hash_password("Test-Passw0rd!"),
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user.id


async def _tariff(session, *, tenant_id: str) -> str:
    tariff = Tariff(
        tenant_id=tenant_id,
        name="Force Wipe Test Tariff",
        region="urban",
        effective_from=datetime(2025, 1, 1, tzinfo=UTC),
        flag_fall=Decimal("5.00"),
        dist_rate_1=Decimal("2.50"),
        dist_rate_2=Decimal("2.30"),
        night_rate_1=Decimal("3.00"),
        night_rate_2=Decimal("2.70"),
        waiting_rate_per_min=Decimal("1.09"),
    )
    session.add(tariff)
    await session.commit()
    await session.refresh(tariff)
    return tariff.id


async def _trip(session, *, tenant_id: str, driver_id: str) -> str:
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        driver_id=driver_id,
        tariff_id=str(uuid.uuid4()),
        type="rank_hail",
        status=TRIP_STATUS_CLOSED,
        start_at=datetime.now(UTC),
        end_at=datetime.now(UTC),
        start_lat=-33.87,
        start_lng=151.21,
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)
    return trip.id


async def _owner_headers(client, session, *, tenant_id: str) -> dict:
    return await auth_headers(client, session, role="owner", tenant_id=tenant_id)


async def test_force_wipe_requires_owner_role(client, session):
    tenant_id = await _tenant(session)
    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)

    resp = await client.post(
        "/v1/fleet/wipe-test-data/force", json={"confirm": True}, headers=admin_headers
    )
    assert resp.status_code == 403


async def test_force_wipe_requires_explicit_confirm_true(client, session):
    tenant_id = await _tenant(session)
    owner_headers = await _owner_headers(client, session, tenant_id=tenant_id)

    resp = await client.post("/v1/fleet/wipe-test-data/force", json={"confirm": False}, headers=owner_headers)
    assert resp.status_code == 422

    resp = await client.post("/v1/fleet/wipe-test-data/force", json={}, headers=owner_headers)
    assert resp.status_code == 422


async def test_force_wipe_deletes_vehicles_devices_drivers_and_their_evidence(client, session):
    """The kitchen-sink case: one of every evidence category
    `assert_user_deletable` would otherwise block on, attached to a driver
    who also has an open shift on a vehicle with a paired device. Force wipe
    must destroy all of it and report accurate counts."""
    tenant_id = await _tenant(session)
    owner_headers = await _owner_headers(client, session, tenant_id=tenant_id)
    driver_id = await _user(session, tenant_id=tenant_id, role=ROLE_DRIVER)
    staff_id = await _user(session, tenant_id=tenant_id, role="admin")

    vehicle = Vehicle(tenant_id=tenant_id, rego="WIPE-001", vehicle_class="standard", status="active")
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)

    device = Device(tenant_id=tenant_id, android_id="wipe-android-1", vehicle_id=vehicle.id)
    session.add(device)

    shift = Shift(tenant_id=tenant_id, driver_id=driver_id, vehicle_id=vehicle.id, start_at=datetime.now(UTC))
    session.add(shift)

    trip_id = await _trip(session, tenant_id=tenant_id, driver_id=driver_id)
    tariff_id = await _tariff(session, tenant_id=tenant_id)

    session.add(PSLLedgerEntry(tenant_id=tenant_id, driver_id=driver_id, period="2026-07"))
    session.add(
        PSLTopUp(
            tenant_id=tenant_id,
            driver_id=driver_id,
            period="2026-07",
            amount=Decimal("20.00"),
            stripe_charge_id="ch_force_wipe_1",
        )
    )
    session.add(
        WalletTransaction(tenant_id=tenant_id, driver_id=driver_id, amount_aud=Decimal("50.00"), kind="top_up")
    )
    session.add(TripRating(tenant_id=tenant_id, trip_id=trip_id, driver_id=driver_id, stars=4))
    # In practice compliance documents are only ever uploaded by staff (see
    # app/api/v1/compliance.py's write-role gate), but assert_user_deletable
    # checks `uploaded_by` generically -- attribute this one to the DRIVER so
    # the purge logic's handling of that category is actually exercised here.
    session.add(
        ComplianceDocument(
            tenant_id=tenant_id,
            vehicle_id=vehicle.id,
            doc_type="calibration_record",
            file_path="uploads/fake/force-wipe-fake.pdf",
            original_filename="fake.pdf",
            uploaded_by=driver_id,
            uploaded_at=datetime.now(UTC),
        )
    )
    session.add(
        TariffChangeLog(tariff_id=tariff_id, tenant_id=tenant_id, actor_user_id=driver_id, after_json={"x": "1"})
    )
    await session.commit()

    resp = await client.post(
        "/v1/fleet/wipe-test-data/force", json={"confirm": True}, headers=owner_headers
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()

    assert body["vehicles_deleted"] == 1
    assert body["devices_deleted"] == 1
    assert body["drivers_deleted"] == 1
    assert body["audit_log_preserved"] is True
    assert body["failures"] == []
    assert body["evidence_rows_destroyed"] == {
        "psl_ledger_entries": 1,
        "psl_topups": 1,
        "wallet_transactions": 1,
        "trip_ratings": 1,
        "compliance_documents": 1,
        # The staff account (staff_id) that uploaded the compliance document
        # above was never targeted -- only the driver's evidence is purged --
        # but the TariffChangeLog row was attributed to the DRIVER here (a
        # driver could not really do this in practice, see fleet_wipe's own
        # comment, but the deletion logic itself must still handle it).
        "tariff_change_log_entries": 1,
    }

    # Everything really is gone from the database, not just reported as gone.
    for model, column in (
        (PSLLedgerEntry, PSLLedgerEntry.driver_id),
        (PSLTopUp, PSLTopUp.driver_id),
        (WalletTransaction, WalletTransaction.driver_id),
        (TripRating, TripRating.driver_id),
        (TariffChangeLog, TariffChangeLog.actor_user_id),
    ):
        count = (
            await session.execute(select(func.count()).select_from(model).where(column == driver_id))
        ).scalar_one()
        assert count == 0, model

    count = (
        await session.execute(
            select(func.count()).select_from(ComplianceDocument).where(ComplianceDocument.uploaded_by == driver_id)
        )
    ).scalar_one()
    assert count == 0

    assert (await client.get(f"/v1/users/{driver_id}", headers=owner_headers)).status_code == 404
    assert (await client.get(f"/v1/fleet/vehicles/{vehicle.id}", headers=owner_headers)).status_code == 404
    assert (await client.get(f"/v1/fleet/devices/{device.id}", headers=owner_headers)).status_code == 404

    # The open shift the driver had on the deleted vehicle was closed (same
    # behaviour as the ordinary DELETE /v1/fleet/vehicles/{id} path — see
    # test_fleet.py's test_delete_vehicle_closes_open_shift_...), not just
    # abandoned.
    await session.refresh(shift)
    assert shift.end_at is not None
    assert shift.reconciled is False

    # The staff account that merely uploaded a compliance document (never a
    # driver, never targeted by the wipe) is untouched.
    assert (await client.get(f"/v1/users/{staff_id}", headers=owner_headers)).status_code == 200


async def test_force_wipe_never_deletes_audit_log_and_reports_the_survivor(client, session):
    """A driver who has ever been recorded as an audit-log actor cannot be
    deleted even under force -- the hash-chained AuditLog table is never
    touched (see app.services.fleet_wipe's module docstring). This must show
    up as an honest failure, not a silent skip or a miscounted success."""
    tenant_id = await _tenant(session)
    owner_headers = await _owner_headers(client, session, tenant_id=tenant_id)
    driver_id = await _user(session, tenant_id=tenant_id, role=ROLE_DRIVER)

    session.add(
        AuditLog(
            tenant_id=tenant_id,
            actor_user_id=driver_id,
            action="shift_device_vehicle_mismatch",
            entity_type="shift",
            entity_id=str(uuid.uuid4()),
        )
    )
    await session.commit()

    resp = await client.post(
        "/v1/fleet/wipe-test-data/force", json={"confirm": True}, headers=owner_headers
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()

    assert body["drivers_deleted"] == 0
    assert body["audit_log_preserved"] is True
    assert len(body["failures"]) == 1
    failure = body["failures"][0]
    assert failure["kind"] == "driver"
    assert failure["id"] == driver_id
    assert "audit log" in failure["reason"]

    # The driver survives...
    assert (await client.get(f"/v1/users/{driver_id}", headers=owner_headers)).status_code == 200
    # ...and so does the audit-log row, completely untouched.
    count = (
        await session.execute(
            select(func.count()).select_from(AuditLog).where(AuditLog.actor_user_id == driver_id)
        )
    ).scalar_one()
    assert count == 1


async def test_force_wipe_is_tenant_scoped(client, session):
    """Force wipe on tenant A must never touch tenant B's rows -- the sole
    multi-tenancy isolation mechanism in this system, same as every other
    endpoint."""
    tenant_a = await _tenant(session, name="Force Wipe Tenant A")
    tenant_b = await _tenant(session, name="Force Wipe Tenant B")
    owner_headers_a = await _owner_headers(client, session, tenant_id=tenant_a)
    driver_b = await _user(session, tenant_id=tenant_b, role=ROLE_DRIVER)

    vehicle_b = Vehicle(tenant_id=tenant_b, rego="WIPE-B-1", vehicle_class="standard", status="active")
    session.add(vehicle_b)
    await session.commit()

    resp = await client.post(
        "/v1/fleet/wipe-test-data/force", json={"confirm": True}, headers=owner_headers_a
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["vehicles_deleted"] == 0
    assert body["drivers_deleted"] == 0

    result = await session.execute(select(func.count()).select_from(User).where(User.id == driver_b))
    assert result.scalar_one() == 1
    result = await session.execute(select(func.count()).select_from(Vehicle).where(Vehicle.id == vehicle_b.id))
    assert result.scalar_one() == 1
