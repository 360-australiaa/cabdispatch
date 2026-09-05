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
from datetime import UTC, datetime, time, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fleet import VEHICLE_CLASS_MAXI, Vehicle
from app.models.geofence import GEOFENCE_KIND_TOLL, Geofence
from app.models.tariffs import Tariff as TariffRow
from app.models.trips import TRIP_STATUS_CLOSED, TRIP_STATUS_OPEN, Trip
from app.models.vouchers import CorporateAccount, Voucher
from app.services import fare_engine as fe
from app.services.fare_engine import round_down, round_half_up
from app.services.trips import compute_variance_pct, haversine_km
from tests.conftest import auth_headers

pytestmark = pytest.mark.asyncio

# A fixed, deterministic ordinary-Wednesday-daytime timestamp (2026-07-15 is a
# Wednesday, comfortably clear of the 22:00-06:00 night window, of Fri/Sat/
# Sunday, and of every date in fare_engine.NSW_PUBLIC_HOLIDAYS or the day
# before one) — used as the default `start_at` below instead of
# `datetime.now(UTC)` so tests that assert an exact day-rate dollar figure
# (e.g. "urban day dist_rate_1") can never flake depending on the real
# wall-clock time a test happens to run at, now that time_class/is_peak are
# resolved server-side from the trip's real start_at (see
# app.services.fare_engine.resolve_time_class_and_peak) rather than trusted
# verbatim from the request body.
_FIXED_DAY_START_AT = datetime(2026, 7, 15, 14, 0, 0, tzinfo=UTC)


# --- fixtures / helpers ---------------------------------------------------


async def _seed_tariff(session: AsyncSession, *, tenant_id: str, region: str = "urban") -> TariffRow:
    """Inserts a real tariffs-domain row with exactly the current Point to
    Point Transport (Fares) Order rates — DERIVED from
    app.services.fare_engine.URBAN_TARIFF/COUNTRY_TARIFF (never a second,
    independently-hardcoded copy of the numbers) so this helper can never
    silently drift out of sync with the engine the next time the rate card
    changes, the way it did across the 2025->2026 Order update."""
    engine_tariff = fe.URBAN_TARIFF if region == "urban" else fe.COUNTRY_TARIFF
    row = TariffRow(
        tenant_id=tenant_id,
        name="Standard Urban" if region == "urban" else "Standard Country",
        region=region,
        effective_from=datetime(2026, 6, 1, tzinfo=UTC),
        booked=False,
        flag_fall=engine_tariff.flag_fall,
        peak_charge=engine_tariff.peak_charge,
        dist_rate_1=engine_tariff.dist_rate_1,
        dist_rate_2=engine_tariff.dist_rate_2,
        night_rate_1=engine_tariff.night_rate_1,
        night_rate_2=engine_tariff.night_rate_2,
        holiday_rate_1=engine_tariff.holiday_rate_1,
        holiday_rate_2=engine_tariff.holiday_rate_2,
        waiting_rate_per_min=engine_tariff.waiting_rate_per_min,
        dist_km_threshold=engine_tariff.dist_km_threshold,
        speed_threshold_kmh=engine_tariff.speed_threshold_kmh,
        maxi_multiplier=engine_tariff.maxi_multiplier,
        multi_hire_pct=engine_tariff.multi_hire_pct,
        psl_amount=engine_tariff.psl_amount,
        surcharge_pct_cap=engine_tariff.surcharge_pct_cap,
        cleaning_fee_cap=engine_tariff.cleaning_fee_cap,
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
        "start_at": _FIXED_DAY_START_AT.isoformat(),
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


async def test_create_trip_ignores_client_time_class_and_is_peak_claims(
    client: AsyncClient, session: AsyncSession
):
    """The same class of bug as resolve_is_maxi_vehicle's: a device claiming
    `time_class="night"`/`is_peak=true` for a start_at that is provably
    ordinary Wednesday daytime must not get either -- the server
    deterministically re-derives both from the tariff + the trip's real
    start_at (app.services.fare_engine.resolve_time_class_and_peak), ignoring
    whatever a device sends in the request body."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    body = await _create_trip(
        client,
        headers,
        tariff.id,
        start_at=_FIXED_DAY_START_AT.isoformat(),  # ordinary Wednesday 14:00 -- see this constant's doc
        # Lies: this instant is neither night nor a peak window.
        time_class="night",
        is_peak=True,
    )

    assert body["time_class"] == "day"
    assert body["is_peak"] is False


async def test_sync_ignores_client_time_class_and_is_peak_claims(client: AsyncClient, session: AsyncSession):
    """Same client-override behaviour as the create-trip test above, but for
    the offline-replay sync path (app.services.trips.recompute_from_trace) --
    a synced item claiming a bogus time_class/is_peak for its real start_at
    must be persisted with the server-derived values, not the device's
    claim."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    now = _FIXED_DAY_START_AT  # ordinary Wednesday 14:00 -- see this constant's doc
    trace = [{"lat": -33.8688, "lng": 151.2093, "speed_kmh": 0, "ts": now.isoformat()}]
    item = _sync_item(
        tariff_id=tariff.id,
        gps_trace=trace,
        device_total="5.17",
        start_at=now.isoformat(),
        end_at=(now + timedelta(minutes=5)).isoformat(),
        # Lies: this instant is neither night nor a peak window.
        time_class="night",
        is_peak=True,
    )

    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200, resp.text
    trip = resp.json()["results"][0]["trip"]
    assert trip["time_class"] == "day"
    assert trip["is_peak"] is False


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
    expected_dist_amount = round_half_up(expected_km * Decimal("2.61"))  # urban day dist_rate_1, 2026 Order

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

    expected_wait_amount = round_half_up(Decimal(2) * Decimal("1.130"))  # 2 minutes, 2026 Order waiting rate
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


async def test_tick_with_dest_persists_planned_destination(client: AsyncClient, session: AsyncSession):
    """A driver-picked mid-trip destination sent on a tick is written onto
    Trip.planned_dest_lat/lng (module docstring deviation #7) -- verified
    against the ORM row directly since TripRead doesn't (and needn't) echo
    it back; the read-only surface for it is GET /v1/vehicles instead (see
    test_live_ops.py)."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    dest_lat, dest_lng = -33.8568, 151.2153  # Sydney Opera House, arbitrary real destination
    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [{"lat": -33.86, "lng": 151.21, "speed_kmh": 20, "ts": datetime.now(UTC).isoformat()}],
            "dest_lat": dest_lat,
            "dest_lng": dest_lng,
        },
        headers=headers,
    )
    assert resp.status_code == 200

    row = await session.get(Trip, trip["id"])
    assert row.planned_dest_lat == dest_lat
    assert row.planned_dest_lng == dest_lng


async def test_tick_omitting_dest_does_not_clear_previously_set_value(
    client: AsyncClient, session: AsyncSession
):
    """A later tick that carries no dest_lat/dest_lng at all must leave a
    previously-picked destination alone -- a driver isn't required to keep
    resending it on every subsequent tick (see apply_tick's own docstring)."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)
    t0 = datetime.fromisoformat(trip["start_at"])

    dest_lat, dest_lng = -33.8568, 151.2153
    first = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {"lat": -33.86, "lng": 151.21, "speed_kmh": 20, "ts": (t0 + timedelta(seconds=10)).isoformat()}
            ],
            "dest_lat": dest_lat,
            "dest_lng": dest_lng,
        },
        headers=headers,
    )
    assert first.status_code == 200

    second = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {"lat": -33.859, "lng": 151.211, "speed_kmh": 20, "ts": (t0 + timedelta(seconds=20)).isoformat()}
            ]
        },
        headers=headers,
    )
    assert second.status_code == 200

    row = await session.get(Trip, trip["id"])
    assert row.planned_dest_lat == dest_lat
    assert row.planned_dest_lng == dest_lng


async def test_close_trip_only_writes_end_lat_lng_not_planned_dest(
    client: AsyncClient, session: AsyncSession
):
    """close_trip is unaffected by this pass: it still only ever writes the
    REAL end_lat/end_lng, and never touches planned_dest_lat/lng even when a
    destination was picked mid-trip via tick (see Trip's module docstring
    deviation #7 for the end_lat/end_lng vs planned_dest_lat/lng
    distinction)."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    dest_lat, dest_lng = -33.8568, 151.2153
    await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [{"lat": -33.86, "lng": 151.21, "speed_kmh": 20, "ts": datetime.now(UTC).isoformat()}],
            "dest_lat": dest_lat,
            "dest_lng": dest_lng,
        },
        headers=headers,
    )

    real_end_lat, real_end_lng = -33.80, 151.05  # deliberately NOT the planned destination above
    close_resp = await client.post(
        f"/v1/trips/{trip['id']}/close",
        json={"end_lat": real_end_lat, "end_lng": real_end_lng},
        headers=headers,
    )
    assert close_resp.status_code == 200
    assert close_resp.json()["end_lat"] == real_end_lat
    assert close_resp.json()["end_lng"] == real_end_lng

    row = await session.get(Trip, trip["id"])
    assert row.end_lat == real_end_lat
    assert row.end_lng == real_end_lng
    # planned_dest_lat/lng survive, untouched by close -- they describe the
    # driver's mid-trip intent, not the (possibly different) real outcome.
    assert row.planned_dest_lat == dest_lat
    assert row.planned_dest_lng == dest_lng


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
    assert body["flag_fall"] == "5.17"
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
    """The $80 maxi airport fixed fare requires the vehicle to genuinely be a
    maxi-cab (resolved server-side from Vehicle.vehicle_class) AND 5+
    passengers — a raw client-supplied `maxi=True` claim on its own (with no
    real maxi vehicle behind it) is advisory-only and must NOT unlock it, per
    app.services.trips.resolve_is_maxi_vehicle."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    maxi_vehicle = Vehicle(rego="MAXI-01", tenant_id=tenant_id, vehicle_class=VEHICLE_CLASS_MAXI)
    session.add(maxi_vehicle)
    await session.commit()

    trip = await _create_trip(
        client,
        headers,
        tariff.id,
        type="airport_fixed",
        vehicle_id=maxi_vehicle.id,
        passenger_count=5,
        # This raw flag is advisory-only now and deliberately left False here
        # to prove the $80 fare comes from the real vehicle_class + passenger
        # count, not from trusting this field.
        maxi=False,
    )

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == "80.00"
    assert body["flag_fall"] == "0.00"


async def test_close_airport_fixed_trip_ignores_raw_maxi_claim_without_a_real_maxi_vehicle(
    client: AsyncClient, session: AsyncSession
):
    """The inverse of the test above: a device claiming maxi=True for a
    vehicle_id that isn't a registered maxi-cab (or doesn't exist at all)
    must still be billed the standard $60 fixed fare."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id, type="airport_fixed", maxi=True)

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == "60.00"


# --- sync (offline replay) --------------------------------------------------


def _sync_item(*, tariff_id: str, gps_trace: list[dict], device_total: str, **overrides) -> dict:
    # Deliberately real `datetime.now(UTC)`, NOT `_FIXED_DAY_START_AT` — every
    # other sync test below anchors its own gps_trace timestamps off this same
    # "now" (via its own local `now = datetime.now(UTC)`, matching this
    # default almost exactly since both calls happen within the same test),
    # so switching this default to a fixed past date would blow out
    # elapsed-time-based waiting/distance charges for every test that doesn't
    # explicitly override start_at/end_at. The one test that needs a
    # deterministic day-rate dollar figure
    # (test_sync_creates_trip_and_flags_variance_within_tolerance) passes
    # explicit start_at/end_at overrides instead of relying on this default.
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
    now = _FIXED_DAY_START_AT  # see this constant's own doc — keeps the day dist_rate_1 assertion below deterministic
    trace = [{"lat": end_lat, "lng": end_lng, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]

    distance_km = haversine_km(start_lat, start_lng, end_lat, end_lng)
    expected_dist_amount = round_half_up(distance_km * Decimal("2.61"))  # urban day dist_rate_1, 2026 Order
    expected_fare_total = round_down(Decimal("5.17") + expected_dist_amount)  # cash, no surcharge; server rounds fare_total DOWN, never up

    item = _sync_item(
        tariff_id=tariff.id,
        gps_trace=trace,
        device_total=str(expected_fare_total),
        start_lat=start_lat,
        start_lng=start_lng,
        # Explicit, deterministic day-time start_at/end_at (overriding
        # _sync_item's own real-`now` default) so the day dist_rate_1
        # assertion above can never flake depending on the real wall-clock
        # time this test happens to run at — see _FIXED_DAY_START_AT's doc.
        start_at=now.isoformat(),
        end_at=(now + timedelta(minutes=5)).isoformat(),
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


def test_compute_variance_pct_clamps_to_column_precision():
    # Trip.variance_pct is Numeric(6, 2) -- max representable value 9999.99. Real bug
    # found live (2026-08-27): an unclamped wildly-wrong device_total produced a
    # variance percentage the column could not store, which SQLite silently accepted
    # (loose NUMERIC affinity) but Postgres rejected with a real, unhandled 500
    # (NumericValueOutOfRange), aborting the whole sync batch for one bad item.
    huge = compute_variance_pct(Decimal("5.00"), Decimal("999999.00"))
    assert huge == Decimal("9999.99")

    # A realistic, in-range variance is untouched by the clamp.
    normal = compute_variance_pct(Decimal("5.00"), Decimal("6.00"))
    assert normal == Decimal("20.00")


async def test_sync_survives_absurd_device_total_without_500(client: AsyncClient, session: AsyncSession):
    # Integration-level proof, not just the unit-level clamp above: the actual
    # POST /v1/trips/sync endpoint must not crash on a pathological device_total --
    # a corrupted value, a driver typo, or a currency-unit mistake, not just an
    # adversarial test. This is the exact request shape that 500'd against real
    # Postgres before the clamp fix (reproduced live, then locally, before fixing).
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    now = datetime.now(UTC)
    trace = [{"lat": -33.86, "lng": 151.2093, "speed_kmh": 0, "ts": (now + timedelta(seconds=60)).isoformat()}]
    item = _sync_item(tariff_id=tariff.id, gps_trace=trace, device_total="999999.00")

    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200, resp.text
    trip = resp.json()["results"][0]["trip"]
    assert Decimal(trip["variance_pct"]) == Decimal("9999.99")
    assert trip["max_fare_check_passed"] is False
    assert trip["flagged_for_review"] is True


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
    # This test own name claimed the trip gets flagged -- until now nothing actually
    # asserted that. Found live (2026-08-27) via a real device sync: a trip failed this
    # exact check by 19% and flagged_for_review stayed False, invisible on the
    # dashboard flagged-trips view unless someone thought to query variance_pct by
    # hand. Now auto-flagged with a real reason, same as a manual Dispute would set.
    assert trip["flagged_for_review"] is True
    assert trip["review_notes"] and "variance" in trip["review_notes"].lower()

    # The dashboard actual flagged-trips filter must be able to find it.
    list_resp = await client.get("/v1/trips", params={"flagged_for_review": True}, headers=headers)
    assert list_resp.status_code == 200
    assert trip["id"] in {t["id"] for t in list_resp.json()["items"]}


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
    # Real voucher ledger (app.services.payments.redeem_voucher) now requires
    # an actual tenant-owned Voucher row to redeem against.
    session.add(Voucher(tenant_id=tenant_id, code="SAVE10", value_aud=Decimal("10.00")))
    await session.commit()

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
    # Fixed day-time (not a real "now") -- unlike the other sync tests in this
    # section, this one asserts an EXACT total (no distance/waiting accrued,
    # so total == flag_fall alone) and must not pick up an extra peak_charge
    # if the real wall clock this test runs at happens to land in the
    # Friday/Saturday/pre-holiday 10pm-6am peak window. See
    # _FIXED_DAY_START_AT's own doc.
    now = _FIXED_DAY_START_AT
    trace = [{"lat": start_lat, "lng": start_lng, "speed_kmh": 0, "ts": now.isoformat()}]
    # No distance travelled -> total is just flag_fall ($5.17, 2026 Order) for this tariff,
    # matching test_sync_creates_trip_and_flags_variance_within_tolerance's own "cash, no
    # surcharge" note.
    item = _sync_item(
        tariff_id=tariff.id,
        gps_trace=trace,
        device_total="5.17",
        start_lat=start_lat,
        start_lng=start_lng,
        start_at=now.isoformat(),
        end_at=(now + timedelta(minutes=5)).isoformat(),
        payment_method="split_fare",
        split_payments=[{"method": "cash", "amount": "2.17"}, {"method": "card", "amount": "3.00"}],
    )
    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200, resp.text
    trip = resp.json()["results"][0]["trip"]
    assert trip["payment_method"] == "split_fare"
    assert trip["split_payments"] == [
        {"method": "cash", "amount": "2.17"},
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
    # Real voucher ledger (app.services.payments.redeem_voucher) now requires
    # an actual tenant-owned Voucher row to redeem against.
    session.add(Voucher(tenant_id=tenant_id, code="PROMO-2026-XYZ", value_aud=Decimal("20.00")))
    await session.commit()

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
    # Real corporate-account ledger (app.services.payments.validate_account_reference)
    # now requires an actual tenant-owned, active CorporateAccount row.
    session.add(
        CorporateAccount(tenant_id=tenant_id, reference="ACME-CORP-0042", company_name="Acme Corp")
    )
    await session.commit()

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



# --- negotiated / "Set Price" fixed fare ------------------------------------


async def test_create_trip_with_negotiated_total_over_cap_is_422(client: AsyncClient, session: AsyncSession):
    """Sanity cap: app.services.fare_engine.NEGOTIATED_TOTAL_MAX is $500.00."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    resp = await client.post(
        "/v1/trips",
        json=_trip_payload(tariff_id=tariff.id, negotiated_total="99999.00"),
        headers=headers,
    )
    assert resp.status_code == 422


async def test_create_trip_with_negotiated_total_zero_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    resp = await client.post(
        "/v1/trips",
        json=_trip_payload(tariff_id=tariff.id, negotiated_total="0.00"),
        headers=headers,
    )
    assert resp.status_code == 422


async def test_create_trip_with_negotiated_total_persists_it(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    trip = await _create_trip(client, headers, tariff.id, negotiated_total="45.00")
    assert Decimal(trip["negotiated_total"]) == Decimal("45.00")
    # Not charged onto `total` until close() runs.
    assert trip["total"] == "0.00"


async def test_close_negotiated_total_trip_charges_negotiated_plus_tolls_and_psl(
    client: AsyncClient, session: AsyncSession
):
    """The important nuance from the competitor's own on-screen disclaimer
    ("this price doesn't include levies and/or tolls"): PSL and tolls still
    accrue and add ON TOP of negotiated_total — unlike the pre-existing
    airport_fixed trip type, which excludes them entirely."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    # Tenant-scoped (not tenant_id=None) and at a location no other test in
    # this module uses, so this toll geofence can't overlap with a different
    # test's leftover global geofence in the shared session-scoped test DB
    # (see tests/conftest.py's _test_database — the DB persists for the whole
    # module run, and app.services.geofence.detect_geofences treats every
    # tenant_id=None geofence as visible to every tenant/trip).
    toll_lat, toll_lng = -34.4012, 150.8931
    geofence = Geofence(
        tenant_id=tenant_id,
        name="Test negotiated-fare toll",
        kind=GEOFENCE_KIND_TOLL,
        center_lat=toll_lat,
        center_lng=toll_lng,
        radius_m=400,
        toll_amount=Decimal("4.82"),
    )
    session.add(geofence)
    await session.commit()

    trip = await _create_trip(
        client, headers, tariff.id, negotiated_total="45.00", start_lat=toll_lat, start_lng=toll_lng
    )
    t0 = datetime.fromisoformat(trip["start_at"])

    tick_resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {"lat": toll_lat, "lng": toll_lng, "speed_kmh": 20, "ts": (t0 + timedelta(seconds=10)).isoformat()}
            ]
        },
        headers=headers,
    )
    assert tick_resp.status_code == 200
    assert Decimal(tick_resp.json()["tolls"]) == Decimal("4.82")

    close_resp = await client.post(
        f"/v1/trips/{trip['id']}/close", json={"include_psl": True}, headers=headers
    )
    assert close_resp.status_code == 200
    body = close_resp.json()

    # Metered components are zeroed out — negotiated_total replaces them.
    assert body["flag_fall"] == "0.00"
    assert body["dist_amount"] == "0.00"
    assert body["wait_amount"] == "0.00"
    assert body["peak_amount"] == "0.00"

    assert Decimal(body["tolls"]) == Decimal("4.82")
    assert Decimal(body["psl"]) == Decimal("1.32")
    assert Decimal(body["negotiated_total"]) == Decimal("45.00")

    expected_subtotal = Decimal("45.00") + Decimal("4.82") + Decimal("1.32")
    assert Decimal(body["subtotal"]) == expected_subtotal
    assert Decimal(body["total"]) == expected_subtotal + Decimal(body["surcharge"])
    # Not negotiated_total alone.
    assert Decimal(body["total"]) != Decimal("45.00")


async def test_close_negotiated_total_trip_without_tolls_or_psl_charges_exactly_negotiated(
    client: AsyncClient, session: AsyncSession
):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id, negotiated_total="45.00")

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == "45.00"
    assert body["subtotal"] == "45.00"


# --- tips (Close & Pay "tips" pass) -----------------------------------------


async def test_close_trip_persists_tip_amount(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={"tip_amount": "5.00"}, headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert Decimal(body["tip_amount"]) == Decimal("5.00")


async def test_close_trip_without_tip_leaves_tip_amount_null(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["tip_amount"] is None


async def test_close_trip_negative_tip_amount_is_422(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    trip = await _create_trip(client, headers, tariff.id)

    resp = await client.post(f"/v1/trips/{trip['id']}/close", json={"tip_amount": "-1.00"}, headers=headers)
    assert resp.status_code == 422


async def test_close_trip_tip_amount_is_never_folded_into_fare_total_or_gst(
    client: AsyncClient, session: AsyncSession
):
    """The whole point of keeping tip_amount off the fare engine: closing the
    SAME trip shape with and without a tip must produce an IDENTICAL
    subtotal/surcharge/total/gst_component — only tip_amount itself differs."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    trip_no_tip = await _create_trip(client, headers, tariff.id)
    trip_with_tip = await _create_trip(client, headers, tariff.id)

    resp_no_tip = await client.post(f"/v1/trips/{trip_no_tip['id']}/close", json={}, headers=headers)
    resp_with_tip = await client.post(
        f"/v1/trips/{trip_with_tip['id']}/close", json={"tip_amount": "20.00"}, headers=headers
    )
    assert resp_no_tip.status_code == 200
    assert resp_with_tip.status_code == 200
    no_tip = resp_no_tip.json()
    with_tip = resp_with_tip.json()

    assert no_tip["tip_amount"] is None
    assert Decimal(with_tip["tip_amount"]) == Decimal("20.00")

    for field in ("flag_fall", "dist_amount", "wait_amount", "peak_amount", "subtotal", "surcharge", "total", "gst_component"):
        assert with_tip[field] == no_tip[field], f"{field} differs between tipped/untipped close ({with_tip[field]} vs {no_tip[field]})"


async def test_sync_persists_tip_amount_without_affecting_device_total_variance(
    client: AsyncClient, session: AsyncSession
):
    """The real Android call path (see ApiService.kt's TripSyncItemDto doc) —
    a tip entered on-device must round-trip through /v1/trips/sync, and must
    not be counted as part of device_total for the max-fare variance check."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    start_lat, start_lng = -33.8688, 151.2093
    end_lat, end_lng = -33.8600, 151.2093
    now = _FIXED_DAY_START_AT
    trace = [{"lat": end_lat, "lng": end_lng, "speed_kmh": 40, "ts": (now + timedelta(seconds=60)).isoformat()}]

    distance_km = haversine_km(start_lat, start_lng, end_lat, end_lng)
    expected_dist_amount = round_half_up(distance_km * Decimal("2.61"))  # urban day dist_rate_1, 2026 Order
    expected_fare_total = round_down(Decimal("5.17") + expected_dist_amount)  # cash, no surcharge

    item = _sync_item(
        tariff_id=tariff.id,
        gps_trace=trace,
        device_total=str(expected_fare_total),  # device_total deliberately excludes the tip below
        start_lat=start_lat,
        start_lng=start_lng,
        start_at=now.isoformat(),
        end_at=(now + timedelta(minutes=5)).isoformat(),
        tip_amount="10.00",
    )

    resp = await client.post("/v1/trips/sync", json=[item], headers=headers)
    assert resp.status_code == 200, resp.text
    trip = resp.json()["results"][0]["trip"]
    assert Decimal(trip["tip_amount"]) == Decimal("10.00")
    assert trip["max_fare_check_passed"] is True
    assert Decimal(trip["variance_pct"]) <= Decimal("1.0")
    assert Decimal(trip["total"]) == expected_fare_total


async def test_tip_amount_is_tenant_isolated(client: AsyncClient, session: AsyncSession):
    headers_a = await auth_headers(client, session, role="driver", tenant_name="Tenant A Tips")
    tenant_a = await _tenant_of(client, headers_a)
    tariff_a = await _seed_tariff(session, tenant_id=tenant_a)
    trip = await _create_trip(client, headers_a, tariff_a.id)

    close_resp = await client.post(
        f"/v1/trips/{trip['id']}/close", json={"tip_amount": "7.50"}, headers=headers_a
    )
    assert close_resp.status_code == 200
    assert Decimal(close_resp.json()["tip_amount"]) == Decimal("7.50")

    headers_b = await auth_headers(client, session, role="driver", tenant_name="Tenant B Tips")
    resp = await client.get(f"/v1/trips/{trip['id']}", headers=headers_b)
    assert resp.status_code == 404


# --- earnings today (dashboard tiles, GET /v1/trips/earnings/today) ---------


def _seed_closed_trip(
    *, tenant_id: str, driver_id: str, start_at: datetime, total: Decimal, status: str = TRIP_STATUS_CLOSED
) -> Trip:
    """Inserts a trip row directly (bypassing the fare engine/create+close
    API) — only the columns `driver_earnings_today` reads matter here, same
    "seed the aggregate's own inputs directly" approach test_reports.py's
    `_make_trip` and test_driver_engagement.py's `_trip` already use for
    testing a SQL aggregate rather than the fare engine itself."""
    return Trip(
        tenant_id=tenant_id,
        client_uuid=str(uuid.uuid4()),
        vehicle_id=str(uuid.uuid4()),
        driver_id=driver_id,
        tariff_id=str(uuid.uuid4()),
        type="rank_hail",
        status=status,
        start_at=start_at,
        end_at=start_at if status == TRIP_STATUS_CLOSED else None,
        start_lat=-33.8688,
        start_lng=151.2093,
        total=total,
    )


async def test_earnings_today_requires_auth(client: AsyncClient):
    resp = await client.get("/v1/trips/earnings/today")
    assert resp.status_code in (401, 403)


async def test_earnings_today_sums_only_the_callers_own_closed_trips_started_today(
    client: AsyncClient, session: AsyncSession
):
    """Real, honest aggregate: sums `total` for CLOSED trips whose `start_at`
    falls in today's UTC calendar day for the CALLING driver only — an open
    trip today, a closed trip today for a different driver, and a closed
    trip from yesterday must all be excluded from `today_total`."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    driver_id = _user_id_of(headers)

    today = datetime.now(UTC).date()
    today_morning = datetime.combine(today, time(9, 0), tzinfo=UTC)
    today_evening = datetime.combine(today, time(18, 0), tzinfo=UTC)
    yesterday_morning = datetime.combine(today - timedelta(days=1), time(9, 0), tzinfo=UTC)

    other_driver_id = str(uuid.uuid4())
    session.add_all(
        [
            _seed_closed_trip(tenant_id=tenant_id, driver_id=driver_id, start_at=today_morning, total=Decimal("25.00")),
            _seed_closed_trip(tenant_id=tenant_id, driver_id=driver_id, start_at=today_evening, total=Decimal("40.50")),
            # Excluded: still open (no final total yet).
            _seed_closed_trip(
                tenant_id=tenant_id,
                driver_id=driver_id,
                start_at=today_morning,
                total=Decimal("999.00"),
                status=TRIP_STATUS_OPEN,
            ),
            # Excluded: a different driver's closed trip, same tenant, same day.
            _seed_closed_trip(tenant_id=tenant_id, driver_id=other_driver_id, start_at=today_morning, total=Decimal("500.00")),
            # Excluded from today_total (but feeds yesterday_total below): started yesterday.
            _seed_closed_trip(tenant_id=tenant_id, driver_id=driver_id, start_at=yesterday_morning, total=Decimal("30.00")),
        ]
    )
    await session.commit()

    resp = await client.get("/v1/trips/earnings/today", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["driver_id"] == driver_id
    assert body["date"] == today.isoformat()
    assert Decimal(body["today_total"]) == Decimal("65.50")
    assert body["trips_completed_today"] == 2
    assert Decimal(body["yesterday_total"]) == Decimal("30.00")


async def test_earnings_today_pct_change_is_null_without_a_yesterday_baseline(
    client: AsyncClient, session: AsyncSession
):
    """No yesterday trips at all -- and separately, a yesterday total of
    exactly zero -- must both render as "no comparison available" (`null`),
    never a fabricated 0% or 100%."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    driver_id = _user_id_of(headers)

    today_morning = datetime.combine(datetime.now(UTC).date(), time(9, 0), tzinfo=UTC)
    session.add(_seed_closed_trip(tenant_id=tenant_id, driver_id=driver_id, start_at=today_morning, total=Decimal("20.00")))
    await session.commit()

    resp = await client.get("/v1/trips/earnings/today", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert Decimal(body["yesterday_total"]) == Decimal("0.00")
    assert body["pct_change"] is None


async def test_earnings_today_pct_change_is_computed_against_yesterday(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    driver_id = _user_id_of(headers)

    today = datetime.now(UTC).date()
    today_morning = datetime.combine(today, time(9, 0), tzinfo=UTC)
    yesterday_morning = datetime.combine(today - timedelta(days=1), time(9, 0), tzinfo=UTC)

    session.add_all(
        [
            _seed_closed_trip(tenant_id=tenant_id, driver_id=driver_id, start_at=today_morning, total=Decimal("150.00")),
            _seed_closed_trip(tenant_id=tenant_id, driver_id=driver_id, start_at=yesterday_morning, total=Decimal("100.00")),
        ]
    )
    await session.commit()

    resp = await client.get("/v1/trips/earnings/today", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert Decimal(body["today_total"]) == Decimal("150.00")
    assert Decimal(body["yesterday_total"]) == Decimal("100.00")
    assert body["pct_change"] == pytest.approx(50.0)


async def test_earnings_today_never_reads_another_drivers_trips_even_via_query_param(
    client: AsyncClient, session: AsyncSession
):
    """Caller-scoped like app.api.v1.me: the endpoint must resolve `driver_id`
    from the authenticated caller, never trust a client-supplied one — the
    real-world motivation being Android's `ApiService.earningsToday` call
    sends `?driver_id=...` as a query param (its own driver's id, in
    practice) that this route must NOT treat as authoritative, exactly the
    way `app.api.v1.me`'s routes never take a driver id from the caller."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    driver_id = _user_id_of(headers)

    today_morning = datetime.combine(datetime.now(UTC).date(), time(9, 0), tzinfo=UTC)
    other_driver_id = str(uuid.uuid4())
    session.add_all(
        [
            _seed_closed_trip(tenant_id=tenant_id, driver_id=driver_id, start_at=today_morning, total=Decimal("10.00")),
            _seed_closed_trip(tenant_id=tenant_id, driver_id=other_driver_id, start_at=today_morning, total=Decimal("999.00")),
        ]
    )
    await session.commit()

    resp = await client.get(
        "/v1/trips/earnings/today", params={"driver_id": other_driver_id}, headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["driver_id"] == driver_id
    assert Decimal(body["today_total"]) == Decimal("10.00")


async def test_earnings_today_only_counts_closed_trips_status_and_tenant(client: AsyncClient, session: AsyncSession):
    """A closed trip belonging to a different tenant (even with the same
    driver_id string, which is possible since ids are unconstrained
    cross-domain refs — see app.models.trips.Trip's module docstring) must
    never bleed into this tenant's total."""
    headers_a = await auth_headers(client, session, role="driver", tenant_name="Earnings Tenant A")
    tenant_a = await _tenant_of(client, headers_a)
    driver_id = _user_id_of(headers_a)

    headers_b = await auth_headers(client, session, role="driver", tenant_name="Earnings Tenant B")
    tenant_b = await _tenant_of(client, headers_b)

    today_morning = datetime.combine(datetime.now(UTC).date(), time(9, 0), tzinfo=UTC)
    session.add_all(
        [
            _seed_closed_trip(tenant_id=tenant_a, driver_id=driver_id, start_at=today_morning, total=Decimal("15.00")),
            # Same driver_id string, but a different tenant -- must not be counted.
            _seed_closed_trip(tenant_id=tenant_b, driver_id=driver_id, start_at=today_morning, total=Decimal("777.00")),
        ]
    )
    await session.commit()

    resp = await client.get("/v1/trips/earnings/today", headers=headers_a)
    assert resp.status_code == 200
    assert Decimal(resp.json()["today_total"]) == Decimal("15.00")
