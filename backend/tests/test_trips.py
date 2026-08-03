"""Tests for the Trips domain (`/v1/trips`) — CRUD, tick/close fare-engine
integration, and the offline-replay sync endpoint's idempotency + variance
check.

NOTE: `app/api/v1/trips.py`'s `router` is not yet registered in `app.main` —
that happens in a later integration step that wires all 12 domain routers
together. These tests are written correctly against the endpoints as built
and will 404 if this file is run in isolation before that registration
lands; that is expected, per the task brief.

Importing `app.models.trips` and `app.models.tariffs` below registers both
tables on `Base.metadata` before the session-scoped `_test_database` fixture
in conftest.py runs `create_all` (same pattern used by test_fleet.py /
test_shifts.py / test_tariffs.py) — `app/models/__init__.py` doesn't import
either domain's models yet, for the same "integration step wires it up"
reason.

Since `app/api/v1/tariffs.py` isn't registered in app.main either at this
point, tariff rows needed as fixtures here are inserted directly via the
`session` fixture rather than through the tariffs HTTP API.
"""
from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.geofence import GEOFENCE_KIND_TOLL, Geofence
from app.models.tariffs import Tariff as TariffRow
from app.models.trips import Trip  # noqa: F401 — see module docstring
from app.services.fare_engine import round_half_up
from app.services.trips import haversine_km
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio


# --- fixtures / helpers ---------------------------------------------------


async def _seed_tariff(session: AsyncSession, *, tenant_id: str, region: str = "urban") -> TariffRow:
    """Inserts a real tariffs-domain row with exactly the Fares Order 2025
    (no.2) rates, mirroring app.services.fare_engine.URBAN_TARIFF/COUNTRY_TARIFF."""
    if region == "urban":
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
    else:
        row = TariffRow(
            tenant_id=tenant_id,
            name="Standard Country",
            region="country",
            effective_from=datetime(2025, 11, 3, tzinfo=UTC),
            booked=False,
            flag_fall=Decimal("5.11"),
            peak_charge=Decimal(0),
            dist_rate_1=Decimal("2.41"),
            dist_rate_2=Decimal("3.30"),
            night_rate_1=Decimal("2.87"),
            night_rate_2=Decimal("3.93"),
            holiday_rate_1=Decimal("2.87"),
            holiday_rate_2=Decimal("3.93"),
            waiting_rate_per_min=Decimal("1.045"),
        )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


def _trip_payload(*, tariff_id: str, **overrides) -> dict:
    payload = {
        "client_uuid": str(uuid.uuid4()),
        "vehicle_id": str(uuid.uuid4()),
        "driver_id": str(uuid.uuid4()),
        "tariff_id": tariff_id,
        "type": "rank_hail",
        "start_lat": -33.8688,
        "start_lng": 151.2093,
        "start_at": datetime.now(UTC).isoformat(),
    }
    payload.update(overrides)
    return payload


async def _create_trip(client: AsyncClient, headers: dict, tariff_id: str, **overrides) -> dict:
    resp = await client.post("/v1/trips", json=_trip_payload(tariff_id=tariff_id, **overrides), headers=headers)
    assert resp.status_code == 201, resp.text
    return resp.json()


# --- create -----------------------------------------------------------------


async def test_create_trip_requires_auth(client: AsyncClient):
    resp = await client.post("/v1/trips", json=_trip_payload(tariff_id=str(uuid.uuid4())))
    assert resp.status_code in (401, 403)


async def test_create_trip_opens_it(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tariff = await _seed_tariff(session, tenant_id=headers and (await _tenant_of(client, headers)))
    body = await _create_trip(client, headers, tariff.id)

    assert body["status"] == "open"
    assert body["distance_m"] == 0
    assert body["total"] == "0.00"
    assert body["client_uuid"]


async def _tenant_of(client: AsyncClient, headers: dict) -> str:
    """Small helper: decode the tenant_id straight out of the bearer token so
    tests can seed a tariff for the exact tenant `auth_headers` created."""
    from app.core import security

    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["tenant_id"]


def _user_id_of(headers: dict) -> str:
    """Small helper: decode the user id (`sub`) straight out of the bearer
    token so dispute-flagging tests can set a trip's driver_id to the exact
    authenticated user's id (needed for the "own driver" authorization
    check — see app.api.v1.trips.flag_trip)."""
    from app.core import security

    token = headers["Authorization"].split(" ", 1)[1]
    return security.decode_token(token)["sub"]


async def test_create_trip_duplicate_client_uuid_is_409(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    client_uuid = str(uuid.uuid4())
    first = await client.post(
        "/v1/trips", json=_trip_payload(tariff_id=tariff.id, client_uuid=client_uuid), headers=headers
    )
    assert first.status_code == 201

    second = await client.post(
        "/v1/trips", json=_trip_payload(tariff_id=tariff.id, client_uuid=client_uuid), headers=headers
    )
    assert second.status_code == 409


async def test_create_trip_missing_client_uuid_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    payload = _trip_payload(tariff_id=tariff.id)
    del payload["client_uuid"]
    resp = await client.post("/v1/trips", json=payload, headers=headers)
    assert resp.status_code == 422


# --- tenant isolation -------------------------------------------------------


async def test_trip_is_not_visible_to_a_different_tenant(client: AsyncClient, session: AsyncSession):
    headers_a = await auth_headers(client, session, role="driver", tenant_name="Tenant A")
    tenant_a = await _tenant_of(client, headers_a)
    tariff_a = await _seed_tariff(session, tenant_id=tenant_a)
    trip = await _create_trip(client, headers_a, tariff_a.id)

    headers_b = await auth_headers(client, session, role="driver", tenant_name="Tenant B")
    resp = await client.get(f"/v1/trips/{trip['id']}", headers=headers_b)
    assert resp.status_code == 404


# --- list / get ---------------------------------------------------------


async def test_list_trips_paginated_and_filtered(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    await _create_trip(client, headers, tariff.id, type="rank_hail")
    await _create_trip(client, headers, tariff.id, type="booked")

    resp = await client.get("/v1/trips?type=booked", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] >= 1
    assert all(item["type"] == "booked" for item in body["items"])

    paged = await client.get("/v1/trips?limit=1&skip=0", headers=headers)
    assert paged.status_code == 200
    assert len(paged.json()["items"]) == 1


async def test_get_trip_404_when_missing(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.get(f"/v1/trips/{uuid.uuid4()}", headers=headers)
    assert resp.status_code == 404


# --- update / delete -------------------------------------------------------


async def test_update_trip_sets_tolls_and_extras(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="dispatcher")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    resp = await client.patch(
        f"/v1/trips/{trip['id']}", json={"tolls": "4.50", "extras": "1.00"}, headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["tolls"] == "4.50"
    assert body["extras"] == "1.00"


async def test_delete_open_trip_succeeds(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    resp = await client.delete(f"/v1/trips/{trip['id']}", headers=headers)
    assert resp.status_code == 204

    get_resp = await client.get(f"/v1/trips/{trip['id']}", headers=headers)
    assert get_resp.status_code == 404


async def test_delete_closed_trip_is_409(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    close_resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert close_resp.status_code == 200

    resp = await client.delete(f"/v1/trips/{trip['id']}", headers=headers)
    assert resp.status_code == 409


# --- tick --------------------------------------------------------------


async def test_tick_distance_mode_accrues_distance_charge(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    start_lat, start_lng = -33.8688, 151.2093
    trip = await _create_trip(client, headers, tariff.id, start_lat=start_lat, start_lng=start_lng)

    end_lat, end_lng = -33.8600, 151.2093  # due north, comfortably >26km/h leg
    # anchor elapsed-time math on the trip's own recorded start_at (not a
    # freshly-captured local timestamp) — apply_tick measures elapsed from
    # trip.start_at/last_ts, so using a different baseline here would make
    # the exact moving_s assertion flaky under any create-request latency.
    t0 = datetime.fromisoformat(trip["start_at"])
    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={"points": [{"lat": end_lat, "lng": end_lng, "speed_kmh": 40, "ts": (t0 + timedelta(seconds=60)).isoformat()}]},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()

    expected_km = haversine_km(start_lat, start_lng, end_lat, end_lng)
    expected_m = round(expected_km * 1000)
    expected_dist_amount = round_half_up(expected_km * Decimal("2.52"))

    assert abs(body["distance_m"] - expected_m) <= 2
    assert Decimal(body["dist_amount"]) == expected_dist_amount
    assert body["wait_amount"] == "0.00"
    assert body["moving_s"] == 60


async def test_tick_waiting_mode_accrues_waiting_charge(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    t0 = datetime.fromisoformat(trip["start_at"])  # see note in the distance-mode test above
    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {
                    "lat": trip["start_lat"],
                    "lng": trip["start_lng"],
                    "speed_kmh": 0,
                    "ts": (t0 + timedelta(seconds=120)).isoformat(),
                }
            ]
        },
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()

    expected_wait_amount = round_half_up(Decimal(2) * Decimal("1.092"))  # 2 minutes
    assert Decimal(body["wait_amount"]) == expected_wait_amount
    assert body["dist_amount"] == "0.00"
    assert body["waiting_s"] == 120


async def test_tick_is_continuous_across_multiple_batches(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id, start_lat=-33.8688, start_lng=151.2093)

    t0 = datetime.now(UTC)
    first = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={"points": [{"lat": -33.8650, "lng": 151.2093, "speed_kmh": 40, "ts": (t0 + timedelta(seconds=30)).isoformat()}]},
        headers=headers,
    )
    assert first.status_code == 200
    after_first_m = first.json()["distance_m"]

    second = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={"points": [{"lat": -33.8600, "lng": 151.2093, "speed_kmh": 40, "ts": (t0 + timedelta(seconds=60)).isoformat()}]},
        headers=headers,
    )
    assert second.status_code == 200
    after_second_m = second.json()["distance_m"]

    # second batch must continue from the first batch's last point, not restart
    # from the trip's start_lat/start_lng — so the incremental delta should be
    # roughly the -33.8650 -> -33.8600 leg, not -33.8688 -> -33.8600.
    incremental_km = haversine_km(-33.8650, 151.2093, -33.8600, 151.2093)
    assert after_second_m > after_first_m
    assert abs((after_second_m - after_first_m) - round(incremental_km * 1000)) <= 2


async def test_tick_on_closed_trip_is_409(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={"points": [{"lat": -33.86, "lng": 151.21, "speed_kmh": 10, "ts": datetime.now(UTC).isoformat()}]},
        headers=headers,
    )
    assert resp.status_code == 409


async def test_tick_through_toll_geofence_auto_adds_toll_once(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    toll_lat, toll_lng = -33.8523, 151.2108  # Sydney Harbour Bridge, per scripts/seed.py's approx coords
    geofence = Geofence(
        tenant_id=None,  # global reference row, same visibility as scripts/seed.py's seeded set
        name="Test Harbour Bridge toll",
        kind=GEOFENCE_KIND_TOLL,
        center_lat=toll_lat,
        center_lng=toll_lng,
        radius_m=400,
        toll_amount=Decimal("4.82"),
    )
    session.add(geofence)
    await session.commit()
    await session.refresh(geofence)

    trip = await _create_trip(client, headers, tariff.id, start_lat=toll_lat, start_lng=toll_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    first = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {"lat": toll_lat, "lng": toll_lng, "speed_kmh": 20, "ts": (t0 + timedelta(seconds=10)).isoformat()}
            ]
        },
        headers=headers,
    )
    assert first.status_code == 200
    body = first.json()
    assert Decimal(body["tolls"]) == Decimal("4.82")
    assert body["auto_tolls_applied"] == [geofence.id]

    # Lingering inside the same zone across a second, later tick call must NOT
    # double-charge the toll.
    second = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {"lat": toll_lat, "lng": toll_lng, "speed_kmh": 0, "ts": (t0 + timedelta(seconds=20)).isoformat()}
            ]
        },
        headers=headers,
    )
    assert second.status_code == 200
    body2 = second.json()
    assert Decimal(body2["tolls"]) == Decimal("4.82")
    assert body2["auto_tolls_applied"] == [geofence.id]

    # And it survives through to the final close() breakdown.
    close_resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert close_resp.status_code == 200
    assert Decimal(close_resp.json()["tolls"]) == Decimal("4.82")


async def test_tick_outside_toll_geofence_adds_no_toll(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    geofence = Geofence(
        tenant_id=None,
        name="Test far-away toll",
        kind=GEOFENCE_KIND_TOLL,
        center_lat=-33.8523,
        center_lng=151.2108,
        radius_m=100,
        toll_amount=Decimal("4.82"),
    )
    session.add(geofence)
    await session.commit()

    far_lat, far_lng = -33.70, 151.00  # well outside the 100m radius above
    trip = await _create_trip(client, headers, tariff.id, start_lat=far_lat, start_lng=far_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={"points": [{"lat": far_lat, "lng": far_lng, "speed_kmh": 20, "ts": (t0 + timedelta(seconds=10)).isoformat()}]},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["tolls"] == "0.00"
    assert body["auto_tolls_applied"] == []


async def test_tick_unknown_tariff_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    trip_resp = await client.post(
        "/v1/trips", json=_trip_payload(tariff_id=str(uuid.uuid4())), headers=headers
    )
    assert trip_resp.status_code == 201
    trip = trip_resp.json()

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={"points": [{"lat": -33.86, "lng": 151.21, "speed_kmh": 40, "ts": datetime.now(UTC).isoformat()}]},
        headers=headers,
    )
    assert resp.status_code == 422


# --- close -----------------------------------------------------------------


async def test_close_trip_computes_breakdown(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()

    assert body["status"] == "closed"
    assert body["flag_fall"] == "5.00"
    assert Decimal(body["total"]) == Decimal(body["subtotal"]) + Decimal(body["surcharge"])
    assert Decimal(body["gst_component"]) == round_half_up(Decimal(body["total"]) / Decimal(11))
    assert body["receipt_ref"]


async def test_close_already_closed_trip_is_409(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    first = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert first.status_code == 200

    second = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert second.status_code == 409


async def test_close_airport_fixed_trip_ignores_metered_charges(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id, type="airport_fixed", maxi=True)

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == "80.00"
    assert body["flag_fall"] == "0.00"


# --- sync (offline replay) --------------------------------------------------


def _sync_item(*, tariff_id: str, gps_trace: list[dict], device_total: str, **overrides) -> dict:
    now = datetime.now(UTC)
    item = {
        "client_uuid": str(uuid.uuid4()),
        "vehicle_id": str(uuid.uuid4()),
        "driver_id": str(uuid.uuid4()),
        "tariff_id": tariff_id,
        "type": "rank_hail",
        "start_at": now.isoformat(),
        "end_at": (now + timedelta(minutes=5)).isoformat(),
        "start_lat": -33.8688,
        "start_lng": 151.2093,
        "gps_trace": gps_trace,
        "device_total": device_total,
    }
    item.update(overrides)
    return item


async def test_sync_creates_trip_and_flags_variance_within_tolerance(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    start_lat, start_lng = -33.8688, 151.2093
    end_lat, end_lng = -33.8600, 151.2093
    now = datetime.now(UTC)
    trace = [{"lat": end_lat, "lng": end_lng, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]

    distance_km = haversine_km(start_lat, start_lng, end_lat, end_lng)
    expected_dist_amount = round_half_up(distance_km * Decimal("2.52"))
    expected_fare_total = round_half_up(Decimal("5.00") + expected_dist_amount)  # cash, no surcharge

    item = _sync_item(
        tariff_id=tariff.id,
        gps_trace=trace,
        device_total=str(expected_fare_total),
        start_lat=start_lat,
        start_lng=start_lng,
    )

    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()["results"]
    assert len(body) == 1
    assert body[0]["duplicate"] is False
    trip = body[0]["trip"]
    assert trip["status"] == "closed"
    assert trip["max_fare_check_passed"] is True
    assert Decimal(trip["variance_pct"]) <= Decimal("1.0")
    assert Decimal(trip["total"]) == expected_fare_total


async def test_sync_flags_variance_over_tolerance(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    now = datetime.now(UTC)
    trace = [{"lat": -33.86, "lng": 151.2093, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]

    item = _sync_item(tariff_id=tariff.id, gps_trace=trace, device_total="1.00")  # wildly under-reported
    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200
    trip = resp.json()["results"][0]["trip"]
    assert trip["max_fare_check_passed"] is False
    assert Decimal(trip["variance_pct"]) > Decimal("1.0")


async def test_sync_is_idempotent_on_client_uuid(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    now = datetime.now(UTC)
    trace = [{"lat": -33.86, "lng": 151.2093, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]
    item = _sync_item(tariff_id=tariff.id, gps_trace=trace, device_total="10.00")

    first = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert first.status_code == 200
    first_trip = first.json()["results"][0]["trip"]
    assert first.json()["results"][0]["duplicate"] is False

    second = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert second.status_code == 200
    second_result = second.json()["results"][0]
    assert second_result["duplicate"] is True
    assert second_result["trip"]["id"] == first_trip["id"]

    listing = await client.get(f"/v1/trips?vehicle_id={item['vehicle_id']}", headers=headers)
    assert listing.json()["total"] == 1


async def test_sync_batch_handles_mixed_new_and_duplicate(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    now = datetime.now(UTC)
    trace = [{"lat": -33.86, "lng": 151.2093, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]
    existing_item = _sync_item(tariff_id=tariff.id, gps_trace=trace, device_total="10.00")
    await client.post("/v1/trips/sync", json=[existing_item], headers=headers)

    new_item = _sync_item(tariff_id=tariff.id, gps_trace=trace, device_total="10.00")
    resp = await client.post("/v1/trips/sync", json=[existing_item, new_item], headers=headers)
    assert resp.status_code == 200
    results = resp.json()["results"]
    assert results[0]["duplicate"] is True
    assert results[1]["duplicate"] is False
    assert results[0]["trip"]["id"] != results[1]["trip"]["id"]


# --- sync + new payment methods (voucher/account/split_fare must not be dropped on the offline
# sync path — this used to be a real gap: TripSyncItem didn't declare these fields at all, so a
# trip closed with e.g. payment_method="voucher" on-device synced fine but silently lost its
# voucher_code, since Pydantic's default extra="ignore" behaviour drops unknown fields rather
# than erroring) -----------------------------------------------------------------------------


async def test_sync_voucher_payment_persists_voucher_code(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    now = datetime.now(UTC)
    trace = [{"lat": -33.86, "lng": 151.2093, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]
    item = _sync_item(
        tariff_id=tariff.id,
        gps_trace=trace,
        device_total="10.00",
        payment_method="voucher",
        voucher_code="SAVE10",
    )
    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200, resp.text
    trip = resp.json()["results"][0]["trip"]
    assert trip["payment_method"] == "voucher"
    assert trip["voucher_code"] == "SAVE10"


async def test_sync_voucher_without_code_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    now = datetime.now(UTC)
    trace = [{"lat": -33.86, "lng": 151.2093, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]
    item = _sync_item(
        tariff_id=tariff.id, gps_trace=trace, device_total="10.00", payment_method="voucher"
    )
    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 422


async def test_sync_split_fare_matching_sum_persists_split_payments(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    start_lat, start_lng = -33.8688, 151.2093
    now = datetime.now(UTC)
    trace = [{"lat": start_lat, "lng": start_lng, "speed_kmh": 0, "ts": now.isoformat()}]
    # No distance travelled -> total is just flag_fall ($5.00) for this tariff, matching
    # test_sync_creates_trip_and_flags_variance_within_tolerance's own "cash, no surcharge" note.
    item = _sync_item(
        tariff_id=tariff.id,
        gps_trace=trace,
        device_total="5.00",
        start_lat=start_lat,
        start_lng=start_lng,
        payment_method="split_fare",
        split_payments=[{"method": "cash", "amount": "2.00"}, {"method": "card", "amount": "3.00"}],
    )
    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200, resp.text
    trip = resp.json()["results"][0]["trip"]
    assert trip["payment_method"] == "split_fare"
    assert trip["split_payments"] == [
        {"method": "cash", "amount": "2.00"},
        {"method": "card", "amount": "3.00"},
    ]


async def test_sync_split_fare_mismatched_sum_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    start_lat, start_lng = -33.8688, 151.2093
    now = datetime.now(UTC)
    trace = [{"lat": start_lat, "lng": start_lng, "speed_kmh": 0, "ts": now.isoformat()}]
    item = _sync_item(
        tariff_id=tariff.id,
        gps_trace=trace,
        device_total="5.00",
        start_lat=start_lat,
        start_lng=start_lng,
        payment_method="split_fare",
        split_payments=[{"method": "cash", "amount": "1.00"}, {"method": "card", "amount": "1.00"}],
    )
    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 422


# --- dispute flagging (blueprint 5.2.5 "Dispute" button) --------------------


async def test_flag_open_trip_is_409(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    driver_id = _user_id_of(headers)
    trip = await _create_trip(client, headers, tariff.id, driver_id=driver_id)

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/flag", json={"reason": "meter looked wrong"}, headers=headers
    )
    assert resp.status_code == 409


async def test_flag_closed_trip_by_own_driver_succeeds(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    driver_id = _user_id_of(headers)
    trip = await _create_trip(client, headers, tariff.id, driver_id=driver_id)
    await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/flag", json={"reason": "passenger disputes the fare"}, headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["flagged_for_review"] is True
    assert body["review_notes"] == "passenger disputes the fare"


async def test_flag_without_reason_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    driver_id = _user_id_of(headers)
    trip = await _create_trip(client, headers, tariff.id, driver_id=driver_id)
    await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)

    resp = await client.patch(f"/v1/trips/{trip['id']}/flag", json={}, headers=headers)
    assert resp.status_code == 422


async def test_flag_by_unrelated_driver_is_403(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    # trip's driver_id is a random uuid, NOT this authenticated driver's own id
    trip = await _create_trip(client, headers, tariff.id)
    await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/flag", json={"reason": "not my trip"}, headers=headers
    )
    assert resp.status_code == 403


async def test_flag_by_staff_role_succeeds_for_any_trip(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, driver_headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, driver_headers, tariff.id)
    await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=driver_headers)

    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    resp = await client.patch(
        f"/v1/trips/{trip['id']}/flag", json={"reason": "flagged by dispatch"}, headers=admin_headers
    )
    assert resp.status_code == 200
    assert resp.json()["flagged_for_review"] is True


async def test_unflag_by_driver_is_403(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    driver_id = _user_id_of(headers)
    trip = await _create_trip(client, headers, tariff.id, driver_id=driver_id)
    await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    await client.patch(f"/v1/trips/{trip['id']}/flag", json={"reason": "dispute"}, headers=headers)

    resp = await client.patch(f"/v1/trips/{trip['id']}/flag", json={"flagged": False}, headers=headers)
    assert resp.status_code == 403


async def test_unflag_by_staff_clears_flag(client: AsyncClient, session: AsyncSession):
    driver_headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, driver_headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    driver_id = _user_id_of(driver_headers)
    trip = await _create_trip(client, driver_headers, tariff.id, driver_id=driver_id)
    await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=driver_headers)
    await client.patch(
        f"/v1/trips/{trip['id']}/flag", json={"reason": "dispute"}, headers=driver_headers
    )

    admin_headers = await auth_headers(client, session, role="admin", tenant_id=tenant_id)
    resp = await client.patch(f"/v1/trips/{trip['id']}/flag", json={"flagged": False}, headers=admin_headers)
    assert resp.status_code == 200
    assert resp.json()["flagged_for_review"] is False


async def test_flag_404_when_trip_missing(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.patch(
        f"/v1/trips/{uuid.uuid4()}/flag", json={"reason": "x"}, headers=headers
    )
    assert resp.status_code == 404


async def test_list_trips_filters_by_flagged_for_review(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    flagged_trip = await _create_trip(client, headers, tariff.id)
    await client.post(f"/v1/trips/{flagged_trip['id']}/close", json={}, headers=headers)
    await client.patch(
        f"/v1/trips/{flagged_trip['id']}/flag", json={"reason": "dispute"}, headers=headers
    )

    not_flagged_trip = await _create_trip(client, headers, tariff.id)
    await client.post(f"/v1/trips/{not_flagged_trip['id']}/close", json={}, headers=headers)

    resp = await client.get("/v1/trips?flagged_for_review=true", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    ids = {item["id"] for item in body["items"]}
    assert flagged_trip["id"] in ids
    assert not_flagged_trip["id"] not in ids


# --- new payment methods (blueprint 5.2.5: Account / Voucher / Split Fare) ---


async def test_create_trip_voucher_without_code_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    resp = await client.post(
        "/v1/trips",
        json=_trip_payload(tariff_id=tariff.id, payment_method="voucher"),
        headers=headers,
    )
    assert resp.status_code == 422


async def test_create_trip_account_without_reference_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    resp = await client.post(
        "/v1/trips",
        json=_trip_payload(tariff_id=tariff.id, payment_method="account"),
        headers=headers,
    )
    assert resp.status_code == 422


async def test_close_trip_with_voucher_payment_method_redeems_and_stores_code(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    trip = await _create_trip(
        client,
        headers,
        tariff.id,
        type="airport_fixed",
        payment_method="voucher",
        voucher_code="PROMO-2026-XYZ",
    )
    assert trip["voucher_code"] == "PROMO-2026-XYZ"

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["payment_method"] == "voucher"
    assert body["voucher_code"] == "PROMO-2026-XYZ"
    assert body["total"] == "60.00"


async def test_close_trip_voucher_payment_method_without_any_code_is_422(
    client: AsyncClient, session: AsyncSession
):
    """Trip opened as cash, then closed with payment_method="voucher" but no
    voucher_code anywhere (never set at creation, not supplied at close)."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id, type="airport_fixed")

    resp = await client.post(
        f"/v1/trips/{trip['id']}/close",
        json={"payment_method": "voucher", "voucher_code": ""},
        headers=headers,
    )
    assert resp.status_code == 422


async def test_close_trip_with_account_payment_method_stores_reference(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    trip = await _create_trip(
        client,
        headers,
        tariff.id,
        type="airport_fixed",
        payment_method="account",
        account_reference="ACME-CORP-0042",
    )

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["payment_method"] == "account"
    assert body["account_reference"] == "ACME-CORP-0042"
    assert body["total"] == "60.00"


async def test_close_trip_split_fare_sums_to_total(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id, type="airport_fixed")  # fixed $60.00 total

    resp = await client.post(
        f"/v1/trips/{trip['id']}/close",
        json={
            "payment_method": "split_fare",
            "split_payments": [
                {"method": "card", "amount": "40.00"},
                {"method": "cash", "amount": "20.00"},
            ],
        },
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["payment_method"] == "split_fare"
    assert body["total"] == "60.00"
    assert body["split_payments"] == [
        {"method": "card", "amount": "40.00"},
        {"method": "cash", "amount": "20.00"},
    ]


async def test_close_trip_split_fare_mismatched_sum_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id, type="airport_fixed")  # fixed $60.00 total

    resp = await client.post(
        f"/v1/trips/{trip['id']}/close",
        json={
            "payment_method": "split_fare",
            "split_payments": [
                {"method": "card", "amount": "10.00"},
                {"method": "cash", "amount": "20.00"},
            ],
        },
        headers=headers,
    )
    assert resp.status_code == 422


async def test_create_trip_with_split_fare_payment_method_is_allowed_without_split_payments(
    client: AsyncClient, session: AsyncSession
):
    """`split_payments` isn't a TripCreate field (the trip's total isn't known
    until close) — opening with payment_method="split_fare" is allowed; it's
    POST .../close that requires split_payments and enforces the sum-to-total
    check (see the next test)."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    resp = await client.post(
        "/v1/trips",
        json=_trip_payload(tariff_id=tariff.id, payment_method="split_fare"),
        headers=headers,
    )
    assert resp.status_code == 201


async def test_close_trip_split_fare_without_split_payments_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(
        client, headers, tariff.id, type="airport_fixed", payment_method="split_fare"
    )

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 422


async def test_update_trip_stores_split_payments_as_stringified_amounts(
    client: AsyncClient, session: AsyncSession
):
    """TripUpdate stores split_payments directly (no sum-vs-total check — that
    only happens at close, per app.services.trips.close_trip) but must still
    stringify Decimal amounts before hitting the JSON column (see
    app.api.v1.trips.update_trip)."""
    headers = await auth_headers(client, session, role="dispatcher")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    resp = await client.patch(
        f"/v1/trips/{trip['id']}",
        json={
            "payment_method": "split_fare",
            "split_payments": [{"method": "cash", "amount": "3.50"}],
        },
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["split_payments"] == [{"method": "cash", "amount": "3.50"}]
