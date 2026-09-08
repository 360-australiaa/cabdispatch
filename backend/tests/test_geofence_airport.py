"""Sydney Airport ground-transport access fee via `kind="airport"` geofences.

Rule under test (see app.services.trips.apply_airport_access_fee_at_start):
charged ONCE when a hiring STARTS inside an airport zone (a pickup); never
when a ticked point drives INTO the zone mid-trip (a drop-off); never on a
`TRIP_TYPE_AIRPORT_FIXED` trip (the $60/$80 fixed fare already includes it);
never on top of a client that already sent its own toll ledger.

Schema / router coverage: `kind="airport"` is accepted and requires
`toll_amount`; `?kind=airport` list filter; the platform-owner pricing gate
applies to airport rows exactly as it does to toll rows; and
`GET /v1/geofences/presets/airport` mirrors
`app.services.regions.nsw.SYDNEY_AIRPORT_TERMINAL_ZONES`.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal

import pytest
from pydantic import ValidationError
from sqlalchemy import select

from app.core.security import PLATFORM_TENANT_ID
from app.models.geofence import (
    GEOFENCE_KIND_AIRPORT,
    GEOFENCE_KINDS,
    GEOFENCE_PRICING_KINDS,
    Geofence,
)
from app.models.tenant import Tenant
from app.models.trips import Trip
from app.schemas.geofence import GeofenceCreate
from app.services.regions import get_region
from app.services.regions.nsw import SYDNEY_AIRPORT_TERMINAL_ZONES
from tests.conftest import auth_headers
from tests.test_trips import _create_trip, _seed_tariff, _tenant_of

pytestmark = pytest.mark.asyncio

# T2 Domestic rank, per SYDNEY_AIRPORT_TERMINAL_ZONES (approximate).
_T2_LAT, _T2_LNG = -33.9339, 151.1799
# Sydney CBD — nowhere near the airport.
_CBD_LAT, _CBD_LNG = -33.8688, 151.2093

FEE = Decimal("6.43")


def _spot(i: int) -> tuple[float, float]:
    """A per-test pickup point. The test database persists across tests and
    global (tenant_id NULL) airport rows are visible to every tenant, so two
    tests seeding a zone at the same coordinates would see each other's rows;
    ~5.5 km of latitude between spots keeps every test's circles disjoint."""
    return (-34.20 - 0.05 * i, 151.00)


def _airport_payload(**overrides) -> dict:
    payload = {
        "name": "Sydney Airport T2 Domestic taxi rank",
        "kind": "airport",
        "center_lat": _T2_LAT,
        "center_lng": _T2_LNG,
        "radius_m": 400,
        "toll_amount": str(FEE),
    }
    payload.update(overrides)
    return payload


async def _platform_owner_headers(client, session):
    """Same per-file duplication convention as tests/test_geofences.py."""
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    if result.scalar_one_or_none() is None:
        session.add(Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform"))
        await session.commit()
    return await auth_headers(client, session, role="owner", tenant_id=PLATFORM_TENANT_ID)


async def _seed_global_airport_zone(
    session, *, lat: float, lng: float, name="Airport rank", radius_m=400.0, fee=FEE
) -> Geofence:
    zone = Geofence(
        tenant_id=None,  # global reference row — visible to every tenant
        name=name,
        kind=GEOFENCE_KIND_AIRPORT,
        center_lat=lat,
        center_lng=lng,
        radius_m=radius_m,
        toll_amount=fee,
    )
    session.add(zone)
    await session.commit()
    await session.refresh(zone)
    return zone


# --- model / schema ------------------------------------------------------------


async def test_airport_kind_is_registered_and_priced():
    assert GEOFENCE_KIND_AIRPORT == "airport"
    assert GEOFENCE_KIND_AIRPORT in GEOFENCE_KINDS
    assert GEOFENCE_KIND_AIRPORT in GEOFENCE_PRICING_KINDS
    # String(10) column — "airport" fits, no migration needed.
    assert Geofence.__table__.c.kind.type.length >= len(GEOFENCE_KIND_AIRPORT)


async def test_schema_accepts_kind_airport_with_toll_amount():
    model = GeofenceCreate(**_airport_payload())
    assert model.kind == "airport"
    assert model.toll_amount == FEE


async def test_schema_requires_toll_amount_for_kind_airport():
    payload = _airport_payload()
    del payload["toll_amount"]
    with pytest.raises(ValidationError, match="toll_amount is required when kind='airport'"):
        GeofenceCreate(**payload)


# --- router: platform-owner gate + list filter --------------------------------


async def test_tenant_admin_gets_403_creating_airport_geofence(client, session):
    headers = await auth_headers(client, session, role="admin")
    resp = await client.post("/v1/geofences", json=_airport_payload(), headers=headers)
    assert resp.status_code == 403


async def test_tenant_owner_gets_403_creating_airport_geofence(client, session):
    headers = await auth_headers(client, session, role="owner")
    resp = await client.post("/v1/geofences", json=_airport_payload(), headers=headers)
    assert resp.status_code == 403


async def test_platform_owner_can_create_airport_geofence(client, session):
    tenant_headers = await auth_headers(client, session, role="admin")
    tenant_id = await _tenant_of(client, tenant_headers)
    owner_headers = await _platform_owner_headers(client, session)

    resp = await client.post(
        f"/v1/geofences?tenant_id={tenant_id}", json=_airport_payload(), headers=owner_headers
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["kind"] == "airport"
    assert body["toll_amount"] == "6.43"
    assert body["tenant_id"] == tenant_id

    # Tenant admin may read it but not touch it.
    assert (
        await client.get(f"/v1/geofences/{body['id']}", headers=tenant_headers)
    ).status_code == 200
    patch = await client.patch(
        f"/v1/geofences/{body['id']}", json={"toll_amount": "9.99"}, headers=tenant_headers
    )
    assert patch.status_code == 403
    assert (
        await client.delete(f"/v1/geofences/{body['id']}", headers=tenant_headers)
    ).status_code == 403


async def test_create_airport_geofence_without_toll_amount_is_422(client, session):
    owner_headers = await _platform_owner_headers(client, session)
    payload = _airport_payload()
    del payload["toll_amount"]
    resp = await client.post("/v1/geofences", json=payload, headers=owner_headers)
    assert resp.status_code == 422


async def test_list_geofences_filters_by_kind_airport(client, session):
    headers = await auth_headers(client, session, role="admin")
    lat, lng = _spot(0)
    await _seed_global_airport_zone(session, lat=lat, lng=lng)
    # A region row the same tenant can see, to prove the filter excludes it.
    region_payload = {
        "name": "Some region",
        "kind": "region",
        "center_lat": _T2_LAT,
        "center_lng": _T2_LNG,
        "radius_m": 100,
    }
    assert (
        await client.post("/v1/geofences", json=region_payload, headers=headers)
    ).status_code == 201

    resp = await client.get("/v1/geofences?kind=airport", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] >= 1
    assert all(item["kind"] == "airport" for item in body["items"])


# --- presets ------------------------------------------------------------------


async def test_sydney_airport_terminal_zone_constant():
    assert [z["name"] for z in SYDNEY_AIRPORT_TERMINAL_ZONES] == [
        "Sydney Airport T1 International taxi rank",
        "Sydney Airport T2 Domestic taxi rank",
        "Sydney Airport T3 Domestic taxi rank",
    ]
    assert SYDNEY_AIRPORT_TERMINAL_ZONES[0]["radius_m"] == 450
    assert SYDNEY_AIRPORT_TERMINAL_ZONES[1]["radius_m"] == 400
    assert SYDNEY_AIRPORT_TERMINAL_ZONES[2]["radius_m"] == 400
    for zone in SYDNEY_AIRPORT_TERMINAL_ZONES:
        assert set(zone) == {"name", "center_lat", "center_lng", "radius_m"}


async def test_airport_presets_endpoint_mirrors_region_constant(client, session):
    # Any authenticated user — a driver-role tablet login is enough.
    headers = await auth_headers(client, session, role="driver")
    resp = await client.get("/v1/geofences/presets/airport", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert len(body) == 3
    fee = get_region("NSW").airport_access_fee
    for item, zone in zip(body, SYDNEY_AIRPORT_TERMINAL_ZONES, strict=True):
        assert item["kind"] == "airport"
        assert item["name"] == zone["name"]
        assert item["center_lat"] == zone["center_lat"]
        assert item["center_lng"] == zone["center_lng"]
        assert item["radius_m"] == zone["radius_m"]
        assert Decimal(item["toll_amount"]) == fee == FEE


async def test_airport_presets_endpoint_requires_auth(client):
    assert (await client.get("/v1/geofences/presets/airport")).status_code == 401


# --- trip start: the charge ----------------------------------------------------


async def test_create_trip_inside_global_airport_zone_charges_fee_once(client, session):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    lat, lng = _spot(1)
    zone = await _seed_global_airport_zone(session, lat=lat, lng=lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=lat, start_lng=lng)
    assert Decimal(trip["tolls"]) == FEE
    assert trip["auto_tolls_applied"] == [zone.id]

    # Lingering at the rank across a tick must NOT re-charge it, and the
    # fee must survive to close as a plain toll line.
    t0 = datetime.fromisoformat(trip["start_at"])
    tick = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {
                    "lat": lat,
                    "lng": lng,
                    "speed_kmh": 5,
                    "ts": (t0 + timedelta(seconds=10)).isoformat(),
                }
            ]
        },
        headers=headers,
    )
    assert tick.status_code == 200, tick.text
    assert Decimal(tick.json()["tolls"]) == FEE
    assert tick.json()["auto_tolls_applied"] == [zone.id]

    close = await client.post(f"/v1/trips/{trip['id']}/close", json={}, headers=headers)
    assert close.status_code == 200, close.text
    assert Decimal(close.json()["tolls"]) == FEE

    row = (await session.execute(select(Trip).where(Trip.id == trip["id"]))).scalar_one()
    assert row.tolls == FEE


async def test_create_trip_inside_tenant_owned_airport_zone_charges_fee(client, session):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    lat, lng = _spot(2)
    zone = Geofence(
        tenant_id=tenant_id,
        name="Tenant's own airport zone",
        kind=GEOFENCE_KIND_AIRPORT,
        center_lat=lat,
        center_lng=lng,
        radius_m=400,
        toll_amount=Decimal("7.00"),
    )
    session.add(zone)
    await session.commit()

    trip = await _create_trip(client, headers, tariff.id, start_lat=lat, start_lng=lng)
    assert Decimal(trip["tolls"]) == Decimal("7.00")
    assert trip["auto_tolls_applied"] == [zone.id]


async def test_create_trip_outside_airport_zone_charges_nothing(client, session):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    lat, lng = _spot(3)
    await _seed_global_airport_zone(session, lat=lat, lng=lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=_CBD_LAT, start_lng=_CBD_LNG)
    assert Decimal(trip["tolls"]) == Decimal(0)
    assert trip["auto_tolls_applied"] == []


async def test_create_airport_fixed_fare_trip_charges_nothing(client, session):
    """The $60/$80 Sydney Airport fixed-fare trial fares already include the
    access fee — it must never be added on top."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    lat, lng = _spot(4)
    await _seed_global_airport_zone(session, lat=lat, lng=lng)

    trip = await _create_trip(
        client, headers, tariff.id, type="airport_fixed", start_lat=lat, start_lng=lng
    )
    assert trip["type"] == "airport_fixed"
    assert Decimal(trip["tolls"]) == Decimal(0)
    assert trip["auto_tolls_applied"] == []


async def test_create_trip_in_overlapping_zones_charges_smallest_radius_only(client, session):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    # Two circles ~170 m apart (the real T2/T3 spacing), both containing the
    # pickup point. The wider one is priced differently so the assertion can
    # tell which was charged.
    wide_lat, wide_lng = _spot(5)
    tight_lat, tight_lng = wide_lat + 0.0006, wide_lng + 0.0016
    wide = await _seed_global_airport_zone(
        session, name="T2 wide", lat=wide_lat, lng=wide_lng, radius_m=600.0, fee=Decimal("9.99")
    )
    tight = await _seed_global_airport_zone(
        session, name="T3 tight", lat=tight_lat, lng=tight_lng, radius_m=400.0, fee=FEE
    )

    trip = await _create_trip(client, headers, tariff.id, start_lat=tight_lat, start_lng=tight_lng)
    assert Decimal(trip["tolls"]) == FEE
    assert trip["auto_tolls_applied"] == [tight.id]
    assert wide.id not in trip["auto_tolls_applied"]


async def test_create_trip_with_client_toll_ledger_is_not_double_charged(client, session):
    """A caller that opens the trip with its own non-zero toll ledger (the
    tablet auto-applies the airport fee on-device at start) owns `tolls`;
    the server must not add the fee a second time."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    lat, lng = _spot(6)
    await _seed_global_airport_zone(session, lat=lat, lng=lng)

    trip = await _create_trip(
        client, headers, tariff.id, start_lat=lat, start_lng=lng, tolls=str(FEE)
    )
    assert Decimal(trip["tolls"]) == FEE
    assert trip["auto_tolls_applied"] == []


# --- tick path: a drop-off is never a pickup ----------------------------------


async def test_tick_entering_airport_zone_charges_nothing(client, session):
    """Driving INTO the airport mid-trip is a drop-off. `apply_tick` charges
    only kind="toll" on entry; an airport zone must be ignored there."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    lat, lng = _spot(7)
    await _seed_global_airport_zone(session, lat=lat, lng=lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=_CBD_LAT, start_lng=_CBD_LNG)
    assert Decimal(trip["tolls"]) == Decimal(0)
    t0 = datetime.fromisoformat(trip["start_at"])

    resp = await client.patch(
        f"/v1/trips/{trip['id']}/tick",
        json={
            "points": [
                {
                    "lat": lat,
                    "lng": lng,
                    "speed_kmh": 20,
                    "ts": (t0 + timedelta(minutes=20)).isoformat(),
                },
                {
                    "lat": lat,
                    "lng": lng,
                    "speed_kmh": 0,
                    "ts": (t0 + timedelta(minutes=21)).isoformat(),
                },
            ]
        },
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert Decimal(body["tolls"]) == Decimal(0)
    assert body["auto_tolls_applied"] == []

    close = await client.post(
        f"/v1/trips/{trip['id']}/close",
        json={"end_lat": lat, "end_lng": lng},
        headers=headers,
    )
    assert close.status_code == 200, close.text
    assert Decimal(close.json()["tolls"]) == Decimal(0)
