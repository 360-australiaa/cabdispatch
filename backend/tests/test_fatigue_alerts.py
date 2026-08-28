"""Tests for driver-fatigue monitoring (blueprint 12.3): shift-duration and
speed-exceeded alert creation as a side effect of `PATCH /v1/trips/{id}/tick`
(see `app.services.fatigue` + that endpoint's docstring), and the
list/acknowledge endpoints in `app/api/v1/fatigue_alerts.py`.

Reuses the tariff-seeding pattern from `tests/test_trips.py` (a real
`tariffs`-domain row is required for `apply_tick`/`resolve_tariff` to
succeed) and the shift-start flow from `tests/test_shifts.py`.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.models.fatigue_alert import FatigueAlert  # noqa: F401 - see test_fleet.py's module docstring pattern
from app.models.tariffs import Tariff as TariffRow
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- fixtures / helpers ---------------------------------------------------


async def _seed_tariff(session: AsyncSession, *, tenant_id: str) -> TariffRow:
    """Minimal real tariffs-domain row — same urban rates used by
    tests/test_trips.py's _seed_tariff — just enough for resolve_tariff to
    succeed inside apply_tick."""
    row = TariffRow(
        tenant_id=tenant_id,
        name="Standard Urban",
        region="urban",
        effective_from=datetime(2025, 11, 3, tzinfo=UTC),
        booked=False,
        flag_fall=Decimal("5.00"),
        peak_charge=Decimal("2.56"),
        dist_rate_1=Decimal("2.52"),
        dist_rate_2=Decimal("2.29"),
        night_rate_1=Decimal("3.00"),
        night_rate_2=Decimal("2.73"),
        holiday_rate_1=Decimal(0),
        holiday_rate_2=Decimal(0),
        waiting_rate_per_min=Decimal("1.092"),
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


async def _tenant_of(headers: dict) -> str:
    from app.core import security

    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["tenant_id"]


async def _start_shift(client: AsyncClient, headers: dict, *, driver_id: str, vehicle_id: str, start_at: datetime) -> dict:
    resp = await client.post(
        "/v1/shifts/start",
        json={"driver_id": driver_id, "vehicle_id": vehicle_id, "start_at": start_at.isoformat()},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _create_trip(
    client: AsyncClient, headers: dict, *, tariff_id: str, driver_id: str, vehicle_id: str, shift_id: str | None = None
) -> dict:
    payload = {
        "client_uuid": str(uuid.uuid4()),
        "vehicle_id": vehicle_id,
        "driver_id": driver_id,
        "tariff_id": tariff_id,
        "type": "rank_hail",
        "start_lat": -33.8688,
        "start_lng": 151.2093,
        "start_at": datetime.now(UTC).isoformat(),
    }
    if shift_id is not None:
        payload["shift_id"] = shift_id
    resp = await client.post("/v1/trips", json=payload, headers=headers)
    assert resp.status_code == 201, resp.text
    return resp.json()


def _point(*, speed_kmh: float) -> dict:
    return {"lat": -33.87, "lng": 151.21, "speed_kmh": speed_kmh, "ts": datetime.now(UTC).isoformat()}


# --- shift-duration alert -----------------------------------------------------


async def test_tick_past_shift_duration_threshold_creates_exactly_one_alert(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    over_limit_start = datetime.now(UTC) - timedelta(hours=settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS + 1)
    shift = await _start_shift(client, headers, driver_id=driver_id, vehicle_id=vehicle_id, start_at=over_limit_start)

    trip = await _create_trip(
        client, headers, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id, shift_id=shift["id"]
    )

    # Tick three times, well under the speed threshold each time, to isolate
    # shift-duration behaviour: a shift ticked past the threshold repeatedly
    # must still only ever have ONE shift_duration_exceeded alert.
    for _ in range(3):
        resp = await client.patch(
            f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=40.0)]}, headers=headers
        )
        assert resp.status_code == 200, resp.text

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.shift_id == shift["id"],
            FatigueAlert.kind == "shift_duration_exceeded",
        )
    )
    alerts = result.scalars().all()
    assert len(alerts) == 1
    assert alerts[0].driver_id == driver_id
    assert alerts[0].acknowledged is False
    assert alerts[0].details_json["limit_hours"] == settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS


async def test_tick_under_shift_duration_threshold_creates_no_alert(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    shift = await _start_shift(
        client, headers, driver_id=driver_id, vehicle_id=vehicle_id, start_at=datetime.now(UTC)
    )
    trip = await _create_trip(
        client, headers, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id, shift_id=shift["id"]
    )

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=40.0)]}, headers=headers
    )
    assert resp.status_code == 200, resp.text

    result = await session.execute(
        select(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id, FatigueAlert.shift_id == shift["id"])
    )
    assert result.scalars().all() == []


# --- no-break-taken alert -------------------------------------------------------


async def test_tick_past_no_break_threshold_with_no_break_creates_alert(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    half_limit_hours = settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS / 2
    over_half_start = datetime.now(UTC) - timedelta(hours=half_limit_hours + 1)
    shift = await _start_shift(client, headers, driver_id=driver_id, vehicle_id=vehicle_id, start_at=over_half_start)

    trip = await _create_trip(
        client, headers, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id, shift_id=shift["id"]
    )

    # Tick three times to prove dedup: still only ever one no_break_taken alert.
    for _ in range(3):
        resp = await client.patch(
            f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=40.0)]}, headers=headers
        )
        assert resp.status_code == 200, resp.text

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.shift_id == shift["id"],
            FatigueAlert.kind == "no_break_taken",
        )
    )
    alerts = result.scalars().all()
    assert len(alerts) == 1
    assert alerts[0].driver_id == driver_id
    assert alerts[0].acknowledged is False


async def test_tick_past_no_break_threshold_with_break_taken_creates_no_alert(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    half_limit_hours = settings.FATIGUE_SHIFT_DURATION_LIMIT_HOURS / 2
    over_half_start = datetime.now(UTC) - timedelta(hours=half_limit_hours + 1)
    shift = await _start_shift(client, headers, driver_id=driver_id, vehicle_id=vehicle_id, start_at=over_half_start)

    # Driver actually takes a break before ticking.
    resp = await client.post(f"/v1/shifts/{shift['id']}/break/start", json={}, headers=headers)
    assert resp.status_code == 200, resp.text
    resp = await client.post(f"/v1/shifts/{shift['id']}/break/end", json={}, headers=headers)
    assert resp.status_code == 200, resp.text
    assert resp.json()["break_taken"] is True

    trip = await _create_trip(
        client, headers, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id, shift_id=shift["id"]
    )
    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=40.0)]}, headers=headers
    )
    assert resp.status_code == 200, resp.text

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.shift_id == shift["id"],
            FatigueAlert.kind == "no_break_taken",
        )
    )
    assert result.scalars().all() == []


async def test_tick_under_no_break_threshold_creates_no_alert(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    shift = await _start_shift(
        client, headers, driver_id=driver_id, vehicle_id=vehicle_id, start_at=datetime.now(UTC)
    )
    trip = await _create_trip(
        client, headers, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id, shift_id=shift["id"]
    )
    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=40.0)]}, headers=headers
    )
    assert resp.status_code == 200, resp.text

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.shift_id == shift["id"],
            FatigueAlert.kind == "no_break_taken",
        )
    )
    assert result.scalars().all() == []


# --- speed alert ---------------------------------------------------------------


async def test_tick_over_speed_threshold_creates_speed_alert(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id)

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=130.0)]}, headers=headers
    )
    assert resp.status_code == 200, resp.text

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.driver_id == driver_id,
            FatigueAlert.kind == "speed_exceeded",
        )
    )
    alerts = result.scalars().all()
    assert len(alerts) == 1
    assert alerts[0].details_json["speed_kmh"] == 130.0
    assert alerts[0].shift_id is None  # this trip had no shift attached


async def test_tick_under_speed_threshold_creates_no_speed_alert(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id)

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=90.0)]}, headers=headers
    )
    assert resp.status_code == 200, resp.text

    result = await session.execute(
        select(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id, FatigueAlert.driver_id == driver_id)
    )
    assert result.scalars().all() == []


# --- list / acknowledge endpoints ----------------------------------------------


async def test_list_and_acknowledge_fatigue_alerts(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id)
    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=130.0)]}, headers=headers
    )
    assert resp.status_code == 200, resp.text

    resp = await client.get("/v1/fatigue-alerts", params={"driver_id": driver_id}, headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["total"] == 1
    alert_id = body["items"][0]["id"]
    assert body["items"][0]["acknowledged"] is False

    resp = await client.post(f"/v1/fatigue-alerts/{alert_id}/acknowledge", json={}, headers=headers)
    assert resp.status_code == 200, resp.text
    assert resp.json()["acknowledged"] is True

    resp = await client.get(f"/v1/fatigue-alerts/{alert_id}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["acknowledged"] is True


async def test_list_fatigue_alerts_requires_dispatch_role(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.get("/v1/fatigue-alerts", headers=headers)
    assert resp.status_code == 403


async def test_fatigue_alerts_are_tenant_isolated(client: AsyncClient, session: AsyncSession):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Fatigue Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Fatigue Tenant B")
    tenant_a = await _tenant_of(headers_a)
    tariff = await _seed_tariff(session, tenant_id=tenant_a)

    driver_id = str(uuid.uuid4())
    vehicle_id = str(uuid.uuid4())
    trip = await _create_trip(client, headers_a, tariff_id=tariff.id, driver_id=driver_id, vehicle_id=vehicle_id)
    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick", json={"points": [_point(speed_kmh=130.0)]}, headers=headers_a
    )
    assert resp.status_code == 200, resp.text

    resp = await client.get("/v1/fatigue-alerts", headers=headers_b)
    assert resp.status_code == 200
    assert resp.json()["total"] == 0
