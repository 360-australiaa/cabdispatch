"""Tests for the operations-cycle tracking pass:

1. Device.calibration_due (meter re-verification due-date) extends the
   EXISTING compliance-expiry alert pattern (app.services.compliance_expiry)
   with calibration_expiring_soon/calibration_expired FatigueAlert kinds, and
   GET /v1/fleet/compliance-expiry rolls them up as entity_type="device".
2. GET /v1/fleet/vehicles/{id}/lifetime-totals -- per-vehicle lifetime
   cumulative-totals register (SUM aggregation over every CLOSED Trip).
3. GET /v1/fleet/vehicles/{id}/pilot-report -- date-ranged evidence pack.

Reuses the direct-ORM driver/vehicle-row helper pattern from
tests/test_compliance_expiry.py (that file predates this one and already
established the convention for this domain).
"""
from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import security
from app.models.duress import DuressEvent
from app.models.fatigue_alert import FatigueAlert
from app.models.fleet import Device, Vehicle
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_STATUS_OPEN, TRIP_TYPE_RANK_HAIL, Trip
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


async def _tenant_of(headers: dict) -> str:
    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["tenant_id"]


async def _create_vehicle_row(session: AsyncSession, *, tenant_id: str, **overrides) -> Vehicle:
    vehicle = Vehicle(
        tenant_id=tenant_id,
        rego=overrides.pop("rego", f"TX-{uuid.uuid4().hex[:6].upper()}"),
        **overrides,
    )
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def _create_device_row(session: AsyncSession, *, tenant_id: str, **overrides) -> Device:
    device = Device(
        tenant_id=tenant_id,
        android_id=overrides.pop("android_id", f"ANDROID-{uuid.uuid4().hex[:8]}"),
        **overrides,
    )
    session.add(device)
    await session.commit()
    await session.refresh(device)
    return device


async def _create_trip_row(session: AsyncSession, *, tenant_id: str, vehicle_id: str, **overrides) -> Trip:
    trip = Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=vehicle_id,
        driver_id=overrides.pop("driver_id", str(uuid.uuid4())),
        tariff_id=overrides.pop("tariff_id", str(uuid.uuid4())),
        type=overrides.pop("type", TRIP_TYPE_RANK_HAIL),
        status=overrides.pop("status", TRIP_STATUS_CLOSED),
        start_at=overrides.pop("start_at", datetime.now(UTC)),
        start_lat=overrides.pop("start_lat", -33.8688),
        start_lng=overrides.pop("start_lng", 151.2093),
        **overrides,
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)
    return trip


# --- Device.calibration_due detection (extends compliance_expiry pattern) ---


async def test_run_vehicle_compliance_checks_raises_calibration_expiring_soon(
    client: AsyncClient, session: AsyncSession
):
    from app.services import compliance_expiry as compliance_expiry_service

    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    await _create_device_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, calibration_due=date.today() + timedelta(days=10)
    )

    await compliance_expiry_service.run_vehicle_compliance_checks(session, tenant_id=tenant_id, vehicle=vehicle)
    await session.commit()

    result = await session.execute(
        select(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id, FatigueAlert.vehicle_id == vehicle.id)
    )
    alerts = result.scalars().all()
    assert len(alerts) == 1
    assert alerts[0].kind == "calibration_expiring_soon"
    assert alerts[0].driver_id is None
    assert alerts[0].details_json["days_remaining"] == 10


async def test_run_vehicle_compliance_checks_raises_calibration_expired(client: AsyncClient, session: AsyncSession):
    from app.services import compliance_expiry as compliance_expiry_service

    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    await _create_device_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, calibration_due=date.today() - timedelta(days=2)
    )

    await compliance_expiry_service.run_vehicle_compliance_checks(session, tenant_id=tenant_id, vehicle=vehicle)
    await session.commit()

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.vehicle_id == vehicle.id,
            FatigueAlert.kind == "calibration_expired",
        )
    )
    assert len(result.scalars().all()) == 1


async def test_calibration_check_skips_null_and_unpaired_device(client: AsyncClient, session: AsyncSession):
    from app.services import compliance_expiry as compliance_expiry_service

    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    # Null calibration_due -- fail-open, no alert.
    await _create_device_row(session, tenant_id=tenant_id, vehicle_id=vehicle.id, calibration_due=None)
    # Set calibration_due but not paired to any vehicle -- fail-open, no alert.
    await _create_device_row(
        session, tenant_id=tenant_id, vehicle_id=None, calibration_due=date.today() - timedelta(days=1)
    )

    await compliance_expiry_service.run_vehicle_compliance_checks(session, tenant_id=tenant_id, vehicle=vehicle)
    await session.commit()

    result = await session.execute(select(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id))
    assert result.scalars().all() == []


async def test_calibration_dedup_does_not_duplicate_unacknowledged_alert(client: AsyncClient, session: AsyncSession):
    from app.services import compliance_expiry as compliance_expiry_service

    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    await _create_device_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, calibration_due=date.today() - timedelta(days=1)
    )

    await compliance_expiry_service.run_vehicle_compliance_checks(session, tenant_id=tenant_id, vehicle=vehicle)
    await session.commit()
    await compliance_expiry_service.run_vehicle_compliance_checks(session, tenant_id=tenant_id, vehicle=vehicle)
    await session.commit()

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.kind == "calibration_expired",
        )
    )
    assert len(result.scalars().all()) == 1


# --- GET /v1/fleet/compliance-expiry rollup includes device calibration -----


async def test_list_compliance_expiry_includes_device_calibration(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    device = await _create_device_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, calibration_due=date.today() - timedelta(days=3)
    )

    resp = await client.get("/v1/fleet/compliance-expiry", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    device_items = [i for i in body["items"] if i["entity_type"] == "device"]
    assert len(device_items) == 1
    assert device_items[0]["entity_id"] == device.id
    assert device_items[0]["field"] == "calibration_due"
    assert device_items[0]["status"] == "expired"

    filtered = await client.get(
        "/v1/fleet/compliance-expiry", params={"entity_type": "device"}, headers=headers
    )
    assert filtered.status_code == 200
    assert filtered.json()["total"] == 1


async def test_list_compliance_expiry_skips_unpaired_device(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    await _create_device_row(
        session, tenant_id=tenant_id, vehicle_id=None, calibration_due=date.today() - timedelta(days=1)
    )

    resp = await client.get("/v1/fleet/compliance-expiry", headers=headers)
    assert resp.status_code == 200
    assert all(i["entity_type"] != "device" for i in resp.json()["items"])


# --- Device schema round-trip: calibration_due ------------------------------


async def test_device_calibration_due_round_trips_via_api(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    vehicle_resp = await client.post("/v1/fleet/vehicles", json={"rego": "CAL-001"}, headers=headers)
    assert vehicle_resp.status_code == 201, vehicle_resp.text
    vehicle_id = vehicle_resp.json()["id"]

    due = (date.today() + timedelta(days=200)).isoformat()
    resp = await client.post(
        "/v1/fleet/devices",
        json={"android_id": f"AND-{uuid.uuid4().hex[:8]}", "vehicle_id": vehicle_id, "calibration_due": due},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["calibration_due"] == due

    device_id = body["id"]
    new_due = (date.today() + timedelta(days=10)).isoformat()
    patch_resp = await client.patch(
        f"/v1/fleet/devices/{device_id}", json={"calibration_due": new_due}, headers=headers
    )
    assert patch_resp.status_code == 200, patch_resp.text
    assert patch_resp.json()["calibration_due"] == new_due


# --- GET /v1/fleet/vehicles/{id}/lifetime-totals -----------------------------


async def test_lifetime_totals_zero_when_no_trips(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle.id}/lifetime-totals", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["trip_count"] == 0
    assert body["total_fares"] == "0.00"
    assert body["total_psl"] == "0.00"
    assert body["total_tolls"] == "0.00"
    assert body["total_tips"] is None
    assert body["total_km"] == "0.000"


async def test_lifetime_totals_sums_only_closed_trips(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    await _create_trip_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, status=TRIP_STATUS_CLOSED,
        total=Decimal("42.50"), psl=Decimal("1.20"), tolls=Decimal("4.50"), distance_m=12000,
    )
    await _create_trip_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, status=TRIP_STATUS_CLOSED,
        total=Decimal("30.00"), psl=Decimal("1.20"), tolls=Decimal("0.00"), distance_m=8000,
    )
    # Open trip -- must be excluded from the register.
    await _create_trip_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, status=TRIP_STATUS_OPEN,
        total=Decimal("999.99"), psl=Decimal("99.00"), tolls=Decimal("99.00"), distance_m=999000,
    )

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle.id}/lifetime-totals", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["trip_count"] == 2
    assert body["total_fares"] == "72.50"
    assert body["total_psl"] == "2.40"
    assert body["total_tolls"] == "4.50"
    assert body["total_km"] == "20.000"
    assert body["total_tips"] is None


async def test_lifetime_totals_404_for_unknown_vehicle(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(f"/v1/fleet/vehicles/{uuid.uuid4()}/lifetime-totals", headers=headers)
    assert resp.status_code == 404


async def test_lifetime_totals_is_tenant_isolated(client: AsyncClient, session: AsyncSession):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Lifetime Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Lifetime Tenant B")
    tenant_a = await _tenant_of(headers_a)

    vehicle = await _create_vehicle_row(session, tenant_id=tenant_a)
    await _create_trip_row(
        session, tenant_id=tenant_a, vehicle_id=vehicle.id, status=TRIP_STATUS_CLOSED, total=Decimal("50.00")
    )

    resp = await client.get(f"/v1/fleet/vehicles/{vehicle.id}/lifetime-totals", headers=headers_b)
    assert resp.status_code == 404


# --- GET /v1/fleet/vehicles/{id}/pilot-report ---------------------------------


async def test_pilot_report_averages_variance_and_counts_flagged(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    in_range = datetime(2026, 6, 15, tzinfo=UTC)
    out_of_range = datetime(2026, 5, 1, tzinfo=UTC)

    await _create_trip_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, start_at=in_range,
        variance_pct=Decimal("2.00"), flagged_for_review=True,
    )
    await _create_trip_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, start_at=in_range,
        variance_pct=Decimal("4.00"), flagged_for_review=False,
    )
    # Outside the requested range -- must not affect the average or counts.
    await _create_trip_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id, start_at=out_of_range,
        variance_pct=Decimal("99.00"), flagged_for_review=True,
    )

    resp = await client.get(
        f"/v1/fleet/vehicles/{vehicle.id}/pilot-report",
        params={"from": "2026-06-01", "to": "2026-06-30"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["trip_count"] == 2
    assert body["avg_fare_accuracy_variance_pct"] == "3.00"
    assert body["flagged_for_review_count"] == 1


async def test_pilot_report_null_variance_average_when_no_trips_in_range(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    resp = await client.get(
        f"/v1/fleet/vehicles/{vehicle.id}/pilot-report",
        params={"from": "2026-06-01", "to": "2026-06-30"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["trip_count"] == 0
    assert body["avg_fare_accuracy_variance_pct"] is None


async def test_pilot_report_device_uptime_estimate(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    await _create_device_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id,
        last_seen_at=datetime(2026, 6, 30, 23, 0, tzinfo=UTC),
    )

    resp = await client.get(
        f"/v1/fleet/vehicles/{vehicle.id}/pilot-report",
        params={"from": "2026-06-01", "to": "2026-06-30"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["device_uptime_estimate_pct"] == "100"


async def test_pilot_report_device_uptime_zero_when_stale(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    await _create_device_row(
        session, tenant_id=tenant_id, vehicle_id=vehicle.id,
        last_seen_at=datetime(2026, 5, 1, tzinfo=UTC),
    )

    resp = await client.get(
        f"/v1/fleet/vehicles/{vehicle.id}/pilot-report",
        params={"from": "2026-06-01", "to": "2026-06-30"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["device_uptime_estimate_pct"] == "0"


async def test_pilot_report_device_uptime_null_when_no_device(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    resp = await client.get(
        f"/v1/fleet/vehicles/{vehicle.id}/pilot-report",
        params={"from": "2026-06-01", "to": "2026-06-30"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["device_uptime_estimate_pct"] is None


async def test_pilot_report_duress_test_activation_gap_and_total_count(
    client: AsyncClient, session: AsyncSession
):
    """duress_test_activation_count is a documented gap (DuressEvent has no
    test_activation field in this codebase) -- always null. The bonus
    duress_event_count_total field is real and counts every duress event of
    any kind in range."""
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    in_range = datetime(2026, 6, 10, tzinfo=UTC)
    out_of_range = datetime(2026, 5, 1, tzinfo=UTC)

    for opened_at in (in_range, in_range):
        session.add(
            DuressEvent(
                tenant_id=tenant_id,
                vehicle_id=vehicle.id,
                driver_id=str(uuid.uuid4()),
                trigger="button",
                opened_at=opened_at,
                gps_stream_ref="s3://fake/ref",
                escalation_log_json={},
            )
        )
    session.add(
        DuressEvent(
            tenant_id=tenant_id,
            vehicle_id=vehicle.id,
            driver_id=str(uuid.uuid4()),
            trigger="button",
            opened_at=out_of_range,
            gps_stream_ref="s3://fake/ref2",
            escalation_log_json={},
        )
    )
    await session.commit()

    resp = await client.get(
        f"/v1/fleet/vehicles/{vehicle.id}/pilot-report",
        params={"from": "2026-06-01", "to": "2026-06-30"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["duress_test_activation_count"] is None
    assert body["duress_event_count_total"] == 2


async def test_pilot_report_invalid_date_range_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    resp = await client.get(
        f"/v1/fleet/vehicles/{vehicle.id}/pilot-report",
        params={"from": "2026-06-30", "to": "2026-06-01"},
        headers=headers,
    )
    assert resp.status_code == 422


async def test_pilot_report_404_for_unknown_vehicle(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(
        f"/v1/fleet/vehicles/{uuid.uuid4()}/pilot-report",
        params={"from": "2026-06-01", "to": "2026-06-30"},
        headers=headers,
    )
    assert resp.status_code == 404


async def test_pilot_report_is_tenant_isolated(client: AsyncClient, session: AsyncSession):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Pilot Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Pilot Tenant B")
    tenant_a = await _tenant_of(headers_a)

    vehicle = await _create_vehicle_row(session, tenant_id=tenant_a)

    resp = await client.get(
        f"/v1/fleet/vehicles/{vehicle.id}/pilot-report",
        params={"from": "2026-06-01", "to": "2026-06-30"},
        headers=headers_b,
    )
    assert resp.status_code == 404
