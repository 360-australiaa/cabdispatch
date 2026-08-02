"""Tests for the Reports domain (`/v1/reports`) — NSW PtP compliance export,
revenue dashboard aggregation, and GST/BAS-prep summary.

This domain owns no table of its own; it reads from the existing `trips`
table (plus lightweight joins to `users`/`vehicles`/`tariffs`/`audit_log`).
Trip rows are seeded directly via the `session` fixture (not through the
full tick/close fare-engine flow) so the fare-breakdown numbers used to
assert aggregation math are exact and test-controlled — same approach
test_trips.py uses for seeding a `tariffs` row directly.

Importing `app.models.trips` / `app.models.tariffs` / `app.models.fleet`
below registers those tables on `Base.metadata` before the session-scoped
`_test_database` fixture in conftest.py runs `create_all`, matching the
precedent set by test_trips.py.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import security
from app.models.fleet import Vehicle
from app.models.tariffs import Tariff
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_STATUS_OPEN, Trip
from app.models.user import User
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- helpers ----------------------------------------------------------------


async def _tenant_of(headers: dict) -> str:
    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["tenant_id"]


def _make_trip(
    *,
    tenant_id: str,
    driver_id: str,
    vehicle_id: str,
    tariff_id: str,
    start_at: datetime,
    status: str = TRIP_STATUS_CLOSED,
    payment_method: str = "cash",
    subtotal: Decimal = Decimal("20.00"),
    surcharge: Decimal = Decimal("1.00"),
    gst_component: Decimal = Decimal("2.00"),
    total: Decimal = Decimal("21.00"),
    tolls: Decimal = Decimal("0.00"),
    psl: Decimal = Decimal("0.00"),
    extras: Decimal = Decimal("0.00"),
) -> Trip:
    return Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=vehicle_id,
        driver_id=driver_id,
        tariff_id=tariff_id,
        type="rank_hail",
        status=status,
        start_at=start_at,
        end_at=start_at,
        start_lat=-33.8688,
        start_lng=151.2093,
        payment_method=payment_method,
        flag_fall=Decimal("5.00"),
        dist_amount=Decimal("15.00"),
        subtotal=subtotal,
        surcharge=surcharge,
        gst_component=gst_component,
        total=total,
        tolls=tolls,
        psl=psl,
        extras=extras,
        max_fare_check_passed=True,
        receipt_ref=f"RCPT-{uuid.uuid4().hex[:8].upper()}",
    )


async def _seed_driver(session: AsyncSession, *, tenant_id: str, name: str) -> User:
    user = User(
        tenant_id=tenant_id,
        role="driver",
        name=name,
        email=f"{uuid.uuid4()}@example.com",
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


async def _seed_vehicle(session: AsyncSession, *, tenant_id: str, rego: str) -> Vehicle:
    vehicle = Vehicle(tenant_id=tenant_id, rego=rego)
    session.add(vehicle)
    await session.commit()
    await session.refresh(vehicle)
    return vehicle


async def _seed_tariff(session: AsyncSession, *, tenant_id: str, name: str) -> Tariff:
    tariff = Tariff(
        tenant_id=tenant_id,
        name=name,
        region="urban",
        effective_from=datetime(2025, 11, 3, tzinfo=UTC),
        booked=False,
        flag_fall=Decimal("5.00"),
        dist_rate_1=Decimal("2.52"),
        dist_rate_2=Decimal("2.29"),
        night_rate_1=Decimal("3.00"),
        night_rate_2=Decimal("2.73"),
        waiting_rate_per_min=Decimal("1.092"),
    )
    session.add(tariff)
    await session.commit()
    await session.refresh(tariff)
    return tariff


# --- auth gate ----------------------------------------------------------------


async def test_reports_require_auth(client: AsyncClient):
    resp = await client.get("/v1/reports/revenue", params={"from": "2026-01-01", "to": "2026-01-31"})
    assert resp.status_code in (401, 403)


async def test_reports_require_read_role(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.get(
        "/v1/reports/revenue", params={"from": "2026-01-01", "to": "2026-01-31"}, headers=headers
    )
    assert resp.status_code == 403


# --- NSW PtP export -----------------------------------------------------------


async def test_ptp_export_json_resolves_names_and_fare_breakdown(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    driver = await _seed_driver(session, tenant_id=tenant_id, name="Jane Driver")
    vehicle = await _seed_vehicle(session, tenant_id=tenant_id, rego="ABC123")
    tariff = await _seed_tariff(session, tenant_id=tenant_id, name="Standard Urban")

    trip = _make_trip(
        tenant_id=tenant_id,
        driver_id=driver.id,
        vehicle_id=vehicle.id,
        tariff_id=tariff.id,
        start_at=datetime(2026, 3, 15, 9, 0, tzinfo=UTC),
        subtotal=Decimal("18.50"),
        surcharge=Decimal("0.50"),
        gst_component=Decimal("1.90"),
        total=Decimal("19.00"),
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)

    resp = await client.get(
        "/v1/reports/nsw-ptp-export",
        params={"from": "2026-03-01", "to": "2026-03-31", "format": "json"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["row_count"] == 1
    row = body["rows"][0]
    assert row["trip_id"] == trip.id
    assert row["driver_id"] == driver.id
    assert row["driver_name"] == "Jane Driver"
    assert row["vehicle_id"] == vehicle.id
    assert row["vehicle_rego"] == "ABC123"
    assert row["tariff_id"] == tariff.id
    assert row["tariff_name"] == "Standard Urban"
    assert row["subtotal"] == "18.50"
    assert row["surcharge"] == "0.50"
    assert row["gst_component"] == "1.90"
    assert row["total"] == "19.00"
    assert row["payment_method"] == "cash"
    assert row["compliance_audit_log"] == []


async def test_ptp_export_unresolved_driver_vehicle_tariff_are_null(client: AsyncClient, session: AsyncSession):
    """driver_id/vehicle_id/tariff_id that don't match a real row (allowed —
    these are unconstrained cross-domain refs, see app/models/trips.py)
    resolve to null names rather than erroring."""
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    trip = _make_trip(
        tenant_id=tenant_id,
        driver_id=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        tariff_id=str(uuid.uuid4()),
        start_at=datetime(2026, 3, 16, 9, 0, tzinfo=UTC),
    )
    session.add(trip)
    await session.commit()

    resp = await client.get(
        "/v1/reports/nsw-ptp-export", params={"from": "2026-03-01", "to": "2026-03-31"}, headers=headers
    )
    assert resp.status_code == 200
    row = resp.json()["rows"][0]
    assert row["driver_name"] is None
    assert row["vehicle_rego"] is None
    assert row["tariff_name"] is None


async def test_ptp_export_csv_format(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="dispatcher")
    tenant_id = await _tenant_of(headers)

    trip = _make_trip(
        tenant_id=tenant_id,
        driver_id=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        tariff_id=str(uuid.uuid4()),
        start_at=datetime(2026, 3, 17, 9, 0, tzinfo=UTC),
        total=Decimal("42.00"),
    )
    session.add(trip)
    await session.commit()
    await session.refresh(trip)

    resp = await client.get(
        "/v1/reports/nsw-ptp-export",
        params={"from": "2026-03-01", "to": "2026-03-31", "format": "csv"},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/csv")
    assert "attachment" in resp.headers["content-disposition"]

    text = resp.text
    lines = text.strip().splitlines()
    header = lines[0].split(",")
    assert "trip_id" in header
    assert "compliance_audit_log_count" in header
    assert trip.id in text
    assert "42.00" in text


async def test_ptp_export_date_range_excludes_out_of_range_trips(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    in_range = _make_trip(
        tenant_id=tenant_id,
        driver_id=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        tariff_id=str(uuid.uuid4()),
        start_at=datetime(2026, 4, 10, tzinfo=UTC),
    )
    out_of_range = _make_trip(
        tenant_id=tenant_id,
        driver_id=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        tariff_id=str(uuid.uuid4()),
        start_at=datetime(2026, 5, 1, tzinfo=UTC),
    )
    session.add_all([in_range, out_of_range])
    await session.commit()

    resp = await client.get(
        "/v1/reports/nsw-ptp-export", params={"from": "2026-04-01", "to": "2026-04-30"}, headers=headers
    )
    assert resp.status_code == 200
    ids = [r["trip_id"] for r in resp.json()["rows"]]
    assert in_range.id in ids
    assert out_of_range.id not in ids


async def test_ptp_export_invalid_range_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(
        "/v1/reports/nsw-ptp-export", params={"from": "2026-04-30", "to": "2026-04-01"}, headers=headers
    )
    assert resp.status_code == 422


# --- Revenue dashboard ----------------------------------------------------------


async def test_revenue_group_by_day_sums_correctly(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    day = datetime(2026, 6, 1, 8, 0, tzinfo=UTC)
    trips = [
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=day,
            total=Decimal("20.00"),
            gst_component=Decimal("1.82"),
        ),
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=day.replace(hour=14),
            total=Decimal("30.00"),
            gst_component=Decimal("2.73"),
        ),
    ]
    session.add_all(trips)
    await session.commit()

    resp = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "day"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["totals"]["trip_count"] == 2
    assert body["totals"]["gross_revenue"] == "50.00"
    assert len(body["groups"]) == 1
    assert body["groups"][0]["group_key"] == "2026-06-01"
    assert body["groups"][0]["trip_count"] == 2
    assert body["groups"][0]["gross_revenue"] == "50.00"


async def test_revenue_group_by_week_and_month_bucket_correctly(client: AsyncClient, session: AsyncSession):
    """2026-06-01 is a Monday; 06-01..06-07 fall in the same ISO week and
    should collapse into one week bucket keyed by that Monday. 06-01 and
    06-30 are the same month and should collapse into one month bucket."""
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    trips = [
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 6, 1, tzinfo=UTC),
            total=Decimal("10.00"),
        ),
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 6, 7, tzinfo=UTC),
            total=Decimal("10.00"),
        ),
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 6, 30, tzinfo=UTC),
            total=Decimal("10.00"),
        ),
    ]
    session.add_all(trips)
    await session.commit()

    week_resp = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "week"},
        headers=headers,
    )
    assert week_resp.status_code == 200, week_resp.text
    week_groups = week_resp.json()["groups"]
    week_of_first_two = next(g for g in week_groups if g["group_key"] == "2026-06-01")
    assert week_of_first_two["trip_count"] == 2
    assert week_of_first_two["gross_revenue"] == "20.00"

    month_resp = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "month"},
        headers=headers,
    )
    assert month_resp.status_code == 200, month_resp.text
    month_groups = month_resp.json()["groups"]
    assert len(month_groups) == 1
    assert month_groups[0]["group_key"] == "2026-06-01"
    assert month_groups[0]["trip_count"] == 3
    assert month_groups[0]["gross_revenue"] == "30.00"


async def test_revenue_group_by_vehicle_and_tariff(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    vehicle = await _seed_vehicle(session, tenant_id=tenant_id, rego="XYZ999")
    tariff = await _seed_tariff(session, tenant_id=tenant_id, name="Peak Urban")

    trips = [
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=vehicle.id,
            tariff_id=tariff.id,
            start_at=datetime(2026, 6, 10, tzinfo=UTC),
            total=Decimal("17.00"),
        ),
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=vehicle.id,
            tariff_id=tariff.id,
            start_at=datetime(2026, 6, 11, tzinfo=UTC),
            total=Decimal("23.00"),
        ),
    ]
    session.add_all(trips)
    await session.commit()

    vehicle_resp = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "vehicle"},
        headers=headers,
    )
    assert vehicle_resp.status_code == 200, vehicle_resp.text
    v_groups = vehicle_resp.json()["groups"]
    assert len(v_groups) == 1
    assert v_groups[0]["group_key"] == vehicle.id
    assert v_groups[0]["group_label"] == "XYZ999"
    assert v_groups[0]["gross_revenue"] == "40.00"

    tariff_resp = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "tariff"},
        headers=headers,
    )
    assert tariff_resp.status_code == 200, tariff_resp.text
    t_groups = tariff_resp.json()["groups"]
    assert len(t_groups) == 1
    assert t_groups[0]["group_key"] == tariff.id
    assert t_groups[0]["group_label"] == "Peak Urban"
    assert t_groups[0]["gross_revenue"] == "40.00"


async def test_revenue_group_by_driver(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    driver = await _seed_driver(session, tenant_id=tenant_id, name="Ali Driver")

    trips = [
        _make_trip(
            tenant_id=tenant_id,
            driver_id=driver.id,
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 6, 2, tzinfo=UTC),
            total=Decimal("15.00"),
        ),
        _make_trip(
            tenant_id=tenant_id,
            driver_id=driver.id,
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 6, 3, tzinfo=UTC),
            total=Decimal("25.00"),
        ),
    ]
    session.add_all(trips)
    await session.commit()

    resp = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "driver"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    groups = resp.json()["groups"]
    assert len(groups) == 1
    assert groups[0]["group_key"] == driver.id
    assert groups[0]["group_label"] == "Ali Driver"
    assert groups[0]["trip_count"] == 2
    assert groups[0]["gross_revenue"] == "40.00"


async def test_revenue_group_by_payment_method(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    trips = [
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 6, 4, tzinfo=UTC),
            payment_method="cash",
            total=Decimal("10.00"),
        ),
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 6, 4, tzinfo=UTC),
            payment_method="card",
            total=Decimal("12.00"),
        ),
    ]
    session.add_all(trips)
    await session.commit()

    resp = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "payment_method"},
        headers=headers,
    )
    assert resp.status_code == 200
    by_key = {g["group_key"]: g for g in resp.json()["groups"]}
    assert by_key["cash"]["gross_revenue"] == "10.00"
    assert by_key["card"]["gross_revenue"] == "12.00"


async def test_revenue_excludes_open_trips(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    open_trip = _make_trip(
        tenant_id=tenant_id,
        driver_id=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        tariff_id=str(uuid.uuid4()),
        start_at=datetime(2026, 6, 5, tzinfo=UTC),
        status=TRIP_STATUS_OPEN,
        total=Decimal("999.00"),
    )
    session.add(open_trip)
    await session.commit()

    resp = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "day"},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["totals"]["trip_count"] == 0


async def test_revenue_tenant_isolation(client: AsyncClient, session: AsyncSession):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Tenant B")
    tenant_a = await _tenant_of(headers_a)
    tenant_b = await _tenant_of(headers_b)

    trip_a = _make_trip(
        tenant_id=tenant_a,
        driver_id=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        tariff_id=str(uuid.uuid4()),
        start_at=datetime(2026, 6, 6, tzinfo=UTC),
        total=Decimal("100.00"),
    )
    trip_b = _make_trip(
        tenant_id=tenant_b,
        driver_id=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        tariff_id=str(uuid.uuid4()),
        start_at=datetime(2026, 6, 6, tzinfo=UTC),
        total=Decimal("500.00"),
    )
    session.add_all([trip_a, trip_b])
    await session.commit()

    resp_a = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "day"},
        headers=headers_a,
    )
    resp_b = await client.get(
        "/v1/reports/revenue",
        params={"from": "2026-06-01", "to": "2026-06-30", "group_by": "day"},
        headers=headers_b,
    )
    assert resp_a.json()["totals"]["gross_revenue"] == "100.00"
    assert resp_b.json()["totals"]["gross_revenue"] == "500.00"

    ptp_a = await client.get(
        "/v1/reports/nsw-ptp-export", params={"from": "2026-06-01", "to": "2026-06-30"}, headers=headers_a
    )
    ptp_ids = [r["trip_id"] for r in ptp_a.json()["rows"]]
    assert trip_a.id in ptp_ids
    assert trip_b.id not in ptp_ids


# --- GST / BAS-prep summary ------------------------------------------------------


async def test_gst_summary_groups_by_month(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    trips = [
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 7, 5, tzinfo=UTC),
            total=Decimal("22.00"),
            gst_component=Decimal("2.00"),
        ),
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 7, 20, tzinfo=UTC),
            total=Decimal("33.00"),
            gst_component=Decimal("3.00"),
        ),
        _make_trip(
            tenant_id=tenant_id,
            driver_id=str(uuid.uuid4()),
            vehicle_id=str(uuid.uuid4()),
            tariff_id=str(uuid.uuid4()),
            start_at=datetime(2026, 8, 2, tzinfo=UTC),
            total=Decimal("11.00"),
            gst_component=Decimal("1.00"),
        ),
    ]
    session.add_all(trips)
    await session.commit()

    resp = await client.get(
        "/v1/reports/gst-summary", params={"from": "2026-07-01", "to": "2026-08-31"}, headers=headers
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert "not an ato" in body["disclaimer"].lower() or "not" in body["disclaimer"].lower()

    months = {m["month"]: m for m in body["months"]}
    assert months["2026-07"]["trip_count"] == 2
    assert months["2026-07"]["gross_revenue"] == "55.00"
    assert months["2026-07"]["gst_component"] == "5.00"
    assert months["2026-07"]["net_of_gst"] == "50.00"

    assert months["2026-08"]["trip_count"] == 1
    assert months["2026-08"]["gst_component"] == "1.00"

    assert body["totals"]["trip_count"] == 3
    assert body["totals"]["gross_revenue"] == "66.00"
    assert body["totals"]["gst_component"] == "6.00"
    assert body["totals"]["net_of_gst"] == "60.00"


async def test_gst_summary_excludes_open_trips_and_empty_range_is_zero(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(
        "/v1/reports/gst-summary", params={"from": "2020-01-01", "to": "2020-01-31"}, headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["months"] == []
    assert body["totals"]["trip_count"] == 0
    assert body["totals"]["gst_component"] == "0.00"
