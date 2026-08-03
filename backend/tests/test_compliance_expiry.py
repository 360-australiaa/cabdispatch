"""Tests for driver-license/authority and vehicle-registration/insurance
compliance-expiry tracking (blueprint 7.2.3/7.2.4/10.1):

- new nullable expiry columns on User/Vehicle round-trip through the CRUD
  endpoints (`POST/PATCH /v1/users`, `POST/PATCH /v1/fleet/vehicles`).
- detection: `PATCH /v1/trips/{id}/tick` raises the right FatigueAlert kinds
  (see app.services.compliance_expiry), with the unacknowledged-dedup rule.
- driver-login (`POST /v1/auth/driver-login`) blocks only on an actually
  expired license, never on null/expiring-soon/expired-authority.
- the dashboard listing endpoint (`GET /v1/fleet/compliance-expiry`).

Reuses the tariff-seeding / trip-tick helper pattern from
tests/test_fatigue_alerts.py (a real tariffs-domain row is required for
apply_tick/resolve_tariff to succeed inside PATCH .../tick).
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
from app.models.fatigue_alert import FatigueAlert
from app.models.fleet import Vehicle
from app.models.tariffs import Tariff as TariffRow
from app.models.user import User
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

_PASSWORD = "Compliance-Pass1!"


def _unique_email(prefix: str = "compliance") -> str:
    return f"{prefix}-{uuid.uuid4()}@example.com"


async def _tenant_of(headers: dict) -> str:
    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["tenant_id"]


async def _seed_tariff(session: AsyncSession, *, tenant_id: str) -> TariffRow:
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


async def _create_driver_row(session: AsyncSession, *, tenant_id: str, **overrides) -> User:
    user = User(
        tenant_id=tenant_id,
        role="driver",
        name=overrides.pop("name", "Test Driver"),
        email=overrides.pop("email", _unique_email()),
        pin_hash=security.hash_password(_PASSWORD),
        status="active",
        **overrides,
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)
    return user


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


async def _create_trip(
    client: AsyncClient, headers: dict, *, tariff_id: str, driver_id: str, vehicle_id: str
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
    resp = await client.post("/v1/trips", json=payload, headers=headers)
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _tick(client: AsyncClient, headers: dict, trip_id: str) -> dict:
    point = {"lat": -33.87, "lng": 151.21, "speed_kmh": 40.0, "ts": datetime.now(UTC).isoformat()}
    resp = await client.patch(f"/v1/trips/{trip_id}/tick", json={"points": [point]}, headers=headers)
    assert resp.status_code == 200, resp.text
    return resp.json()


# --- User/Vehicle schema round-trip ------------------------------------------


async def test_create_driver_with_expiry_fields_round_trips(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    license_expiry = (date.today() + timedelta(days=90)).isoformat()
    authority_expiry = (date.today() + timedelta(days=180)).isoformat()

    resp = await client.post(
        "/v1/users",
        json={
            "name": "Expiry Driver",
            "email": _unique_email(),
            "password": _PASSWORD,
            "role": "driver",
            "driver_license_expiry": license_expiry,
            "driver_authority_expiry": authority_expiry,
        },
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_license_expiry"] == license_expiry
    assert body["driver_authority_expiry"] == authority_expiry

    get_resp = await client.get(f"/v1/users/{body['id']}", headers=headers)
    assert get_resp.json()["driver_license_expiry"] == license_expiry


async def test_create_driver_without_expiry_fields_defaults_to_null(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/users",
        json={"name": "No Expiry Driver", "email": _unique_email(), "password": _PASSWORD, "role": "driver"},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["driver_license_expiry"] is None
    assert body["driver_authority_expiry"] is None


async def test_create_and_update_vehicle_with_expiry_fields(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    registration_expiry = (date.today() + timedelta(days=45)).isoformat()

    resp = await client.post(
        "/v1/fleet/vehicles",
        json={"rego": "EXP-001", "registration_expiry": registration_expiry},
        headers=headers,
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["registration_expiry"] == registration_expiry
    assert body["insurance_expiry"] is None
    vehicle_id = body["id"]

    new_insurance_expiry = (date.today() - timedelta(days=1)).isoformat()
    patch_resp = await client.patch(
        f"/v1/fleet/vehicles/{vehicle_id}",
        json={"insurance_expiry": new_insurance_expiry},
        headers=headers,
    )
    assert patch_resp.status_code == 200, patch_resp.text
    assert patch_resp.json()["insurance_expiry"] == new_insurance_expiry
    assert patch_resp.json()["registration_expiry"] == registration_expiry


# --- detection via PATCH /v1/trips/{id}/tick ---------------------------------


async def test_tick_creates_license_expiring_soon_alert(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver = await _create_driver_row(
        session, tenant_id=tenant_id, driver_license_expiry=date.today() + timedelta(days=10)
    )
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver.id, vehicle_id=vehicle.id)
    await _tick(client, headers, trip["id"])

    result = await session.execute(
        select(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id, FatigueAlert.driver_id == driver.id)
    )
    alerts = result.scalars().all()
    assert len(alerts) == 1
    assert alerts[0].kind == "license_expiring_soon"
    assert alerts[0].vehicle_id is None
    assert alerts[0].details_json["days_remaining"] == 10


async def test_tick_creates_license_expired_alert(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver = await _create_driver_row(
        session, tenant_id=tenant_id, driver_license_expiry=date.today() - timedelta(days=3)
    )
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver.id, vehicle_id=vehicle.id)
    await _tick(client, headers, trip["id"])

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.driver_id == driver.id,
            FatigueAlert.kind == "license_expired",
        )
    )
    alerts = result.scalars().all()
    assert len(alerts) == 1
    assert alerts[0].details_json["days_remaining"] == -3


async def test_tick_with_null_expiry_creates_no_alert(client: AsyncClient, session: AsyncSession):
    """Fail-open: a driver/vehicle with no expiry data set at all must never
    raise a compliance-expiry alert."""
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver = await _create_driver_row(session, tenant_id=tenant_id)
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)

    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver.id, vehicle_id=vehicle.id)
    await _tick(client, headers, trip["id"])

    result = await session.execute(select(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id))
    kinds = {a.kind for a in result.scalars().all()}
    assert kinds.isdisjoint(
        {"license_expiring_soon", "license_expired", "authority_expiring_soon", "authority_expired",
         "registration_expiring_soon", "registration_expired", "insurance_expiring_soon", "insurance_expired"}
    )


async def test_tick_creates_vehicle_registration_and_insurance_alerts(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver = await _create_driver_row(session, tenant_id=tenant_id)
    vehicle = await _create_vehicle_row(
        session,
        tenant_id=tenant_id,
        registration_expiry=date.today() + timedelta(days=5),
        insurance_expiry=date.today() - timedelta(days=1),
    )

    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver.id, vehicle_id=vehicle.id)
    await _tick(client, headers, trip["id"])

    result = await session.execute(
        select(FatigueAlert).where(FatigueAlert.tenant_id == tenant_id, FatigueAlert.vehicle_id == vehicle.id)
    )
    alerts = {a.kind: a for a in result.scalars().all()}
    assert set(alerts) == {"registration_expiring_soon", "insurance_expired"}
    assert alerts["registration_expiring_soon"].driver_id is None
    assert alerts["insurance_expired"].driver_id is None


async def test_tick_dedup_does_not_duplicate_unacknowledged_alert(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver = await _create_driver_row(
        session, tenant_id=tenant_id, driver_license_expiry=date.today() - timedelta(days=1)
    )
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver.id, vehicle_id=vehicle.id)

    await _tick(client, headers, trip["id"])
    await _tick(client, headers, trip["id"])

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.driver_id == driver.id,
            FatigueAlert.kind == "license_expired",
        )
    )
    assert len(result.scalars().all()) == 1


async def test_tick_raises_new_alert_after_previous_one_acknowledged(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    driver = await _create_driver_row(
        session, tenant_id=tenant_id, driver_license_expiry=date.today() - timedelta(days=1)
    )
    vehicle = await _create_vehicle_row(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff_id=tariff.id, driver_id=driver.id, vehicle_id=vehicle.id)

    await _tick(client, headers, trip["id"])

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.driver_id == driver.id,
            FatigueAlert.kind == "license_expired",
        )
    )
    first_alert = result.scalar_one()
    ack_resp = await client.post(
        f"/v1/fatigue-alerts/{first_alert.id}/acknowledge", json={}, headers=headers
    )
    assert ack_resp.status_code == 200, ack_resp.text

    await _tick(client, headers, trip["id"])

    result = await session.execute(
        select(FatigueAlert).where(
            FatigueAlert.tenant_id == tenant_id,
            FatigueAlert.driver_id == driver.id,
            FatigueAlert.kind == "license_expired",
        )
    )
    assert len(result.scalars().all()) == 2


# --- driver-login expiry block -----------------------------------------------


async def _create_driver_via_api(client: AsyncClient, headers: dict, **overrides) -> dict:
    payload = {
        "name": overrides.pop("name", "Login Expiry Driver"),
        "email": overrides.pop("email", _unique_email("login-expiry")),
        "password": overrides.pop("password", _PASSWORD),
        "role": "driver",
        **overrides,
    }
    resp = await client.post("/v1/users", json=payload, headers=headers)
    assert resp.status_code == 201, resp.text
    return resp.json()


async def test_driver_login_blocked_when_license_actually_expired(client: AsyncClient, session: AsyncSession):
    admin_headers = await auth_headers(client, session, role="admin")
    driver = await _create_driver_via_api(
        client, admin_headers, driver_license_expiry=(date.today() - timedelta(days=1)).isoformat()
    )

    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver["driver_code"], "pin": _PASSWORD}
    )
    assert login.status_code == 403
    assert "expired" in login.json()["detail"].lower()


async def test_driver_login_allowed_when_license_expiring_soon(client: AsyncClient, session: AsyncSession):
    admin_headers = await auth_headers(client, session, role="admin")
    driver = await _create_driver_via_api(
        client, admin_headers, driver_license_expiry=(date.today() + timedelta(days=10)).isoformat()
    )

    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver["driver_code"], "pin": _PASSWORD}
    )
    assert login.status_code == 200


async def test_driver_login_allowed_when_license_expiry_is_null(client: AsyncClient, session: AsyncSession):
    admin_headers = await auth_headers(client, session, role="admin")
    driver = await _create_driver_via_api(client, admin_headers)

    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver["driver_code"], "pin": _PASSWORD}
    )
    assert login.status_code == 200


async def test_driver_login_not_blocked_by_expired_authority_alone(client: AsyncClient, session: AsyncSession):
    """Blueprint 5.2.1's login block is license-specific — an expired
    driver_authority_expiry must not block login on its own."""
    admin_headers = await auth_headers(client, session, role="admin")
    driver = await _create_driver_via_api(
        client, admin_headers, driver_authority_expiry=(date.today() - timedelta(days=30)).isoformat()
    )

    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver["driver_code"], "pin": _PASSWORD}
    )
    assert login.status_code == 200


async def test_driver_login_wrong_pin_still_401s_even_with_expired_license(
    client: AsyncClient, session: AsyncSession
):
    """Credential verification must happen BEFORE the expiry check, so a
    wrong-PIN attempt against an expired-license account still reports
    invalid credentials rather than leaking expiry status."""
    admin_headers = await auth_headers(client, session, role="admin")
    driver = await _create_driver_via_api(
        client, admin_headers, driver_license_expiry=(date.today() - timedelta(days=1)).isoformat()
    )

    login = await client.post(
        "/v1/auth/driver-login", json={"driver_code": driver["driver_code"], "pin": "wrong-pin"}
    )
    assert login.status_code == 401


# --- GET /v1/fleet/compliance-expiry -----------------------------------------


async def test_list_compliance_expiry_returns_drivers_and_vehicles(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    await _create_driver_row(session, tenant_id=tenant_id, driver_license_expiry=date.today() + timedelta(days=5))
    await _create_vehicle_row(session, tenant_id=tenant_id, insurance_expiry=date.today() - timedelta(days=2))
    await _create_driver_row(session, tenant_id=tenant_id)  # no expiry set — must not appear

    resp = await client.get("/v1/fleet/compliance-expiry", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["total"] == 2
    entity_types = {item["entity_type"] for item in body["items"]}
    assert entity_types == {"driver", "vehicle"}
    # Sorted soonest-expiring (most negative days_remaining) first.
    assert body["items"][0]["status"] == "expired"


async def test_list_compliance_expiry_filters_by_entity_type_and_status(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    await _create_driver_row(session, tenant_id=tenant_id, driver_license_expiry=date.today() + timedelta(days=5))
    await _create_vehicle_row(session, tenant_id=tenant_id, insurance_expiry=date.today() - timedelta(days=2))

    resp = await client.get(
        "/v1/fleet/compliance-expiry", params={"entity_type": "vehicle"}, headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["entity_type"] == "vehicle"

    resp = await client.get(
        "/v1/fleet/compliance-expiry", params={"status": "expired"}, headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 1
    assert body["items"][0]["status"] == "expired"


async def test_list_compliance_expiry_respects_within_days_window(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(headers)

    await _create_driver_row(session, tenant_id=tenant_id, driver_license_expiry=date.today() + timedelta(days=80))

    resp = await client.get(
        "/v1/fleet/compliance-expiry", params={"within_days": 30}, headers=headers
    )
    assert resp.status_code == 200
    assert resp.json()["total"] == 0

    resp = await client.get(
        "/v1/fleet/compliance-expiry", params={"within_days": 90}, headers=headers
    )
    assert resp.status_code == 200
    assert resp.json()["total"] == 1


async def test_list_compliance_expiry_is_tenant_isolated(client: AsyncClient, session: AsyncSession):
    headers_a = await auth_headers(client, session, role="admin", tenant_name="Compliance Tenant A")
    headers_b = await auth_headers(client, session, role="admin", tenant_name="Compliance Tenant B")
    tenant_a = await _tenant_of(headers_a)

    await _create_driver_row(session, tenant_id=tenant_a, driver_license_expiry=date.today() - timedelta(days=1))

    resp = await client.get("/v1/fleet/compliance-expiry", headers=headers_b)
    assert resp.status_code == 200
    assert resp.json()["total"] == 0
