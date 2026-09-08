"""Tests for the NSW toll-road registry (`app.models.toll` /
`app.services.tolls`) — the real per-road, per-gantry, direction-aware
replacement for the old flat-circle `Geofence(kind="toll")` auto-detection.

Covers, end-to-end through `PATCH /v1/trips/{id}/tick` (the same integration
style as `tests/test_trips.py::test_tick_through_toll_geofence_auto_adds_toll_once`
for the old mechanism):
  - once-per-road charging across multiple gantries of the same road
  - a directional (one-way) road NOT charging the untolled direction
  - distance-model pricing (M7-style) respecting its published cap
  - time-of-day band selection (Sydney Harbour Bridge/Tunnel-style)
  - a trip crossing two DIFFERENT roads being charged for both
  - unpriced roads, and roads whose pricing model this service has no
    formula for, being flagged and never charged a guessed amount

Also covers the read-only `/v1/toll-roads` API and the platform-owner-gated
price-revision endpoint.

Test fixtures use synthetic ids/coordinates/prices (same convention
`tests/test_geofences.py` already uses for its own toll geofences) — the
real seeded data (13 roads, 141 real gantries) is exercised by
`scripts/seed_toll_roads.py` directly (see `tests/test_seed_toll_roads.py`),
not re-derived here.
"""
from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from itertools import count
from zoneinfo import ZoneInfo

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import PLATFORM_TENANT_ID
from app.models.tenant import Tenant
from app.models.toll import (
    TollGantry,
    TollPoint,
    TollPointPriceRevision,
    TollRoad,
    TollRoadPriceRevision,
)
from app.services.fare_engine import NSW_FARE_ZONE
from app.services.tolls import (
    classify_bearing,
    direction_allows_charge,
    haversine_m,
    select_time_of_day_price,
)
from tests.conftest import auth_headers
from tests.test_trips import _create_trip, _seed_tariff, _tenant_of

pytestmark = pytest.mark.asyncio

_EFFECTIVE_DATE = date(2026, 7, 1)

# `toll_gantries` is a global, un-tenant-scoped table (see app.models.toll's
# module docstring) that persists across every test in this session-scoped
# test database -- so two tests that placed a gantry at the same literal
# lat/lng would see EACH OTHER's gantries within the 150m detection radius
# and cross-contaminate their tolls. `_next_zone` hands each test its own
# base coordinate at least 1 degree (~110km) away from every other test's,
# far beyond that radius, so tests never interfere with each other.
_zone_seq = count()


def _next_zone() -> tuple[float, float]:
    n = next(_zone_seq)
    return (-10.0 - n, 140.0)


async def _make_road(
    session: AsyncSession,
    *,
    road_id: str,
    pricing_model: str,
    directional: str = "both",
    price_class_a: str | None = "5.00",
    cap_class_a: str | None = None,
    derived_corridor_km: str | None = None,
    time_of_day_rates_class_a: list[dict] | None = None,
    confidence: str = "verified",
    charging_policy: str = "once_per_road",
    network_group: str | None = None,
    rate_per_km_class_a: str | None = None,
    flagfall_class_a: str | None = None,
    network_cap_class_a: str | None = None,
) -> TollRoad:
    road = TollRoad(
        id=road_id,
        api_code=None,
        name=f"{road_id} Motorway",
        operator="Test Operator",
        pricing_model=pricing_model,
        charging_policy=charging_policy,
        network_group=network_group,
        directional=directional,
        derived_corridor_km=Decimal(derived_corridor_km) if derived_corridor_km else None,
    )
    session.add(road)
    await session.commit()

    if pricing_model != "unpriced":
        session.add(
            TollRoadPriceRevision(
                toll_road_id=road_id,
                price_class_a_min=Decimal(price_class_a) if price_class_a else None,
                price_class_a_max=Decimal(price_class_a) if price_class_a else None,
                price_class_b_min=None,
                price_class_b_max=None,
                cap_class_a=Decimal(cap_class_a) if cap_class_a else None,
                cap_class_b=None,
                rate_per_km_class_a=Decimal(rate_per_km_class_a) if rate_per_km_class_a else None,
                flagfall_class_a=Decimal(flagfall_class_a) if flagfall_class_a else None,
                network_cap_class_a=Decimal(network_cap_class_a) if network_cap_class_a else None,
                time_of_day_rates_class_a=time_of_day_rates_class_a,
                currency="AUD",
                gst_included=True,
                effective_date=_EFFECTIVE_DATE,
                indexation="quarterly",
                confidence=confidence,
            )
        )
        await session.commit()
    return road


async def _add_gantry(
    session: AsyncSession,
    *,
    road_id: str,
    gantry_id: str,
    lat: float,
    lng: float,
    toll_point_id: str | None = None,
) -> TollGantry:
    gantry = TollGantry(
        id=gantry_id, toll_road_id=road_id, location=gantry_id, ramp=None, direction=None,
        latitude=lat, longitude=lng, toll_point_id=toll_point_id,
    )
    session.add(gantry)
    await session.commit()
    return gantry


async def _make_toll_point(
    session: AsyncSession,
    *,
    road_id: str,
    point_id: str,
    price_class_a: str | None,
    confidence: str = "verified",
) -> TollPoint:
    """One named toll point of a `per_point` road, plus the dated revision
    that prices it. `price_class_a=None` models a point the source data never
    resolved a price for -- which must be FLAGGED, never guessed."""
    point = TollPoint(id=point_id, toll_road_id=road_id, name=point_id)
    session.add(point)
    await session.commit()
    session.add(
        TollPointPriceRevision(
            toll_point_id=point_id,
            price_class_a=Decimal(price_class_a) if price_class_a else None,
            price_class_b=None,
            currency="AUD",
            gst_included=True,
            effective_date=_EFFECTIVE_DATE,
            indexation="quarterly",
            confidence=confidence,
        )
    )
    await session.commit()
    return point


async def _tick(client: AsyncClient, headers: dict, trip_id: str, *, lat: float, lng: float, ts: datetime, speed_kmh: float = 80.0):
    resp = await client.patch(
        f"/v1/trips/{trip_id}/tick",
        json={"points": [{"lat": lat, "lng": lng, "speed_kmh": speed_kmh, "ts": ts.isoformat()}]},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


# --- pure helpers --------------------------------------------------------------


def test_classify_bearing_none_when_too_close():
    assert classify_bearing(-34.0, 151.0, -34.0, 151.0) is None


def test_classify_bearing_north_south():
    # Moving from further south to further north -> north.
    assert classify_bearing(-34.002, 151.0, -34.000, 151.0) == "north"
    # Moving from further north to further south -> south.
    assert classify_bearing(-34.000, 151.0, -34.002, 151.0) == "south"


def test_direction_allows_charge_both_and_one_way_never_need_bearing():
    assert direction_allows_charge("both", None) is True
    assert direction_allows_charge("one_way", None) is True


def test_direction_allows_charge_northbound_only():
    assert direction_allows_charge("northbound_only", "north") is True
    assert direction_allows_charge("northbound_only", "south") is False
    assert direction_allows_charge("northbound_only", None) is None


# SHB/SHT's published bands are Sydney wall clock, so every timestamp in these
# tests is NSW local. They used to be written in UTC and asserted against the raw
# UTC hour, which only worked while _shb_sht_band read the hour as given -- the
# same defect that mis-billed the night fare. Sydney is UTC+10/+11, so "07:00"
# written as UTC is 17:00 here: a whole band away.
_NSW = NSW_FARE_ZONE


def test_select_time_of_day_price_picks_matching_band():
    rates = [
        {"band": "peak", "price": "4.55"},
        {"band": "off_peak", "price": "3.41"},
        {"band": "night", "price": "2.85"},
    ]
    # Wednesday 07:00 NSW -> weekday peak window (06:30-09:30).
    assert select_time_of_day_price(rates, datetime(2026, 7, 15, 7, 0, tzinfo=_NSW)) == Decimal("4.55")
    # Wednesday 12:00 NSW -> weekday off-peak window (09:30-16:00).
    assert select_time_of_day_price(rates, datetime(2026, 7, 15, 12, 0, tzinfo=_NSW)) == Decimal("3.41")
    # Wednesday 23:00 NSW -> night.
    assert select_time_of_day_price(rates, datetime(2026, 7, 15, 23, 0, tzinfo=_NSW)) == Decimal("2.85")
    # Saturday 10:00 NSW -> weekend off-peak window (08:00-20:00).
    assert select_time_of_day_price(rates, datetime(2026, 7, 18, 10, 0, tzinfo=_NSW)) == Decimal("3.41")


def test_the_band_follows_nsw_local_time_not_the_senders_zone():
    """One instant, three zones, one price.

    The band a passenger is charged must depend on when they crossed the bridge,
    not on which timezone the device happened to send the timestamp in -- and
    devices sync UTC, so this is the real production path, not a hypothetical.
    """
    rates = [
        {"band": "peak", "price": "4.55"},
        {"band": "off_peak", "price": "3.41"},
        {"band": "night", "price": "2.85"},
    ]
    crossing = datetime(2026, 7, 15, 7, 0, tzinfo=_NSW)  # Wed 07:00 Sydney = Tue 21:00 UTC
    for zone in (_NSW, UTC, ZoneInfo("Asia/Karachi")):
        assert select_time_of_day_price(rates, crossing.astimezone(zone)) == Decimal("4.55"), zone


# --- once per road, not per gantry ----------------------------------------------


async def test_tick_charges_road_once_across_multiple_gantries(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTFLAT", pricing_model="flat", directional="both", price_class_a="5.00")
    zone_lat, zone_lng = _next_zone()
    gantry_a = (zone_lat, zone_lng)
    gantry_b = (zone_lat - 0.002, zone_lng)  # ~222m away -- a different real gantry on the same road
    await _add_gantry(session, road_id=road.id, gantry_id="TESTFLAT:a", lat=gantry_a[0], lng=gantry_a[1])
    await _add_gantry(session, road_id=road.id, gantry_id="TESTFLAT:b", lat=gantry_b[0], lng=gantry_b[1])

    trip = await _create_trip(client, headers, tariff.id, start_lat=gantry_a[0], start_lng=gantry_a[1])
    t0 = datetime.fromisoformat(trip["start_at"])

    # One gantry is not corroboration -- see app.services.tolls.TOLL_CONFIRM_RADIUS_M.
    # A road with two known gantries must be confirmed at both before it bills, so a
    # vehicle on an adjacent service road that clips one of them is never charged.
    body1 = await _tick(client, headers, trip["id"], lat=gantry_a[0], lng=gantry_a[1], ts=t0 + timedelta(seconds=5))
    assert Decimal(body1["tolls"]) == Decimal("0.00")
    assert body1["auto_tolled_roads"] == {}

    # Crossing a SECOND, physically different gantry on the SAME road must
    # not charge again -- this is exactly the once-per-gantry overcharge bug
    # the old flat-circle model had (M7's 45 gantries, Lane Cove's 6, etc.).
    body2 = await _tick(client, headers, trip["id"], lat=gantry_b[0], lng=gantry_b[1], ts=t0 + timedelta(seconds=30))
    assert Decimal(body2["tolls"]) == Decimal("5.00")
    assert body2["auto_tolled_roads"] == {"TESTFLAT": "5.00"}


# --- directionality ---------------------------------------------------------------


async def test_northbound_only_road_does_not_charge_southbound_crossing(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTED", pricing_model="flat", directional="northbound_only", price_class_a="10.48")
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTED:g", lat=gantry_lat, lng=gantry_lng)

    # Trip starts NORTH of the gantry and drives south INTO it -- the
    # untolled direction on a northbound-only road.
    start_lat = gantry_lat + 0.0015  # ~167m north of the gantry
    trip = await _create_trip(client, headers, tariff.id, start_lat=start_lat, start_lng=gantry_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=gantry_lat, lng=gantry_lng, ts=t0 + timedelta(seconds=10))
    assert body["tolls"] == "0.00"
    assert body["auto_tolled_roads"] == {}


async def test_northbound_only_road_charges_northbound_crossing(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTED2", pricing_model="flat", directional="northbound_only", price_class_a="10.48")
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTED2:g", lat=gantry_lat, lng=gantry_lng)

    # Trip starts SOUTH of the gantry and drives north INTO it -- the real
    # tolled direction.
    start_lat = gantry_lat - 0.0015  # ~167m south of the gantry
    trip = await _create_trip(client, headers, tariff.id, start_lat=start_lat, start_lng=gantry_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=gantry_lat, lng=gantry_lng, ts=t0 + timedelta(seconds=10))
    assert Decimal(body["tolls"]) == Decimal("10.48")
    assert body["auto_tolled_roads"] == {"TESTED2": "10.48"}


async def test_one_way_road_charges_without_needing_a_bearing(client: AsyncClient, session: AsyncSession):
    """Military Road E-Ramp-style: `directional="one_way"` charges on any
    detected crossing, unlike northbound_only/southbound_only which need a
    matching bearing."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTRAMP", pricing_model="flat", directional="one_way", price_class_a="2.15")
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTRAMP:g", lat=gantry_lat, lng=gantry_lng)

    # Start AT the gantry itself (zero movement -> no reliable bearing at
    # all) -- must still charge, since one_way needs no bearing.
    trip = await _create_trip(client, headers, tariff.id, start_lat=gantry_lat, start_lng=gantry_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=gantry_lat, lng=gantry_lng, ts=t0 + timedelta(seconds=10))
    assert Decimal(body["tolls"]) == Decimal("2.15")


# --- distance-model pricing (M7-style), respecting the cap -----------------------


async def test_distance_model_respects_cap(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    # $1.00/km (cap 10.00 / corridor 10.000km), so the maths below are exact.
    road = await _make_road(
        session, road_id="TESTM7", pricing_model="distance", directional="both",
        price_class_a=None, cap_class_a="10.00", derived_corridor_km="10.000",
    )
    zone_lat, zone_lng = _next_zone()
    entry = (zone_lat, zone_lng)
    mid = (zone_lat + 0.045, zone_lng)   # ~5km along the corridor from entry
    far = (zone_lat + 0.135, zone_lng)   # ~15km along -- exceeds the cap
    for i, (lat, lng) in enumerate([entry, mid, far]):
        await _add_gantry(session, road_id=road.id, gantry_id=f"TESTM7:g{i}", lat=lat, lng=lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=entry[0], start_lng=entry[1])
    t0 = datetime.fromisoformat(trip["start_at"])

    # Entry gantry: zero distance travelled yet -> zero charge so far, but the
    # road is now marked "entered" (tracked in toll_road_progress).
    body0 = await _tick(client, headers, trip["id"], lat=entry[0], lng=entry[1], ts=t0 + timedelta(seconds=1))
    assert Decimal(body0["tolls"]) == Decimal("0.00")

    mid_distance_km = Decimal(str(round(haversine_m(*entry, *mid) / 1000.0, 6)))
    body1 = await _tick(client, headers, trip["id"], lat=mid[0], lng=mid[1], ts=t0 + timedelta(minutes=3))
    expected_mid = min(mid_distance_km * Decimal("1.00"), Decimal("10.00")).quantize(Decimal("0.01"))
    assert Decimal(body1["tolls"]) == expected_mid
    assert 4 < expected_mid < 6  # sanity: really is ~$5, not the cap yet

    # Still ONE line item for this road even though the amount has revised.
    assert body1["auto_tolled_roads"].keys() == {"TESTM7"}

    body2 = await _tick(client, headers, trip["id"], lat=far[0], lng=far[1], ts=t0 + timedelta(minutes=8))
    assert Decimal(body2["tolls"]) == Decimal("10.00")  # capped, not ~15.00
    assert body2["auto_tolled_roads"] == {"TESTM7": "10.00"}


# --- time-of-day pricing (Sydney Harbour Bridge/Tunnel-style) -------------------


async def test_time_of_day_selects_peak_band(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(
        session, road_id="TESTSHB", pricing_model="time_of_day", directional="southbound_only",
        price_class_a=None,
        time_of_day_rates_class_a=[
            {"band": "peak", "price": "4.55"},
            {"band": "off_peak", "price": "3.41"},
            {"band": "night", "price": "2.85"},
        ],
    )
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTSHB:g", lat=gantry_lat, lng=gantry_lng)

    # Southbound = travelling from north to south, so start NORTH of the gantry.
    trip = await _create_trip(client, headers, tariff.id, start_lat=gantry_lat + 0.0015, start_lng=gantry_lng)
    # 2026-07-15 is a Wednesday. 07:00 NSW falls in the peak window (06:30-09:30).
    peak_ts = datetime(2026, 7, 15, 7, 0, tzinfo=_NSW)

    body = await _tick(client, headers, trip["id"], lat=gantry_lat, lng=gantry_lng, ts=peak_ts)
    assert Decimal(body["tolls"]) == Decimal("4.55")


async def test_time_of_day_selects_off_peak_band(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(
        session, road_id="TESTSHB2", pricing_model="time_of_day", directional="southbound_only",
        price_class_a=None,
        time_of_day_rates_class_a=[
            {"band": "peak", "price": "4.55"},
            {"band": "off_peak", "price": "3.41"},
            {"band": "night", "price": "2.85"},
        ],
    )
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTSHB2:g", lat=gantry_lat, lng=gantry_lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=gantry_lat + 0.0015, start_lng=gantry_lng)
    # Wednesday 12:00 NSW -> off-peak window (09:30-16:00).
    off_peak_ts = datetime(2026, 7, 15, 12, 0, tzinfo=_NSW)

    body = await _tick(client, headers, trip["id"], lat=gantry_lat, lng=gantry_lng, ts=off_peak_ts)
    assert Decimal(body["tolls"]) == Decimal("3.41")


# --- two different roads both charged --------------------------------------------


async def test_crossing_two_different_roads_charges_both(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road_a = await _make_road(session, road_id="TESTROADA", pricing_model="flat", directional="both", price_class_a="6.06")
    road_b = await _make_road(session, road_id="TESTROADB", pricing_model="flat", directional="both", price_class_a="4.30")
    zone_lat, zone_lng = _next_zone()
    point_a = (zone_lat, zone_lng)
    point_b = (zone_lat, zone_lng + 0.05)  # far enough away to be a distinct road/gantry
    await _add_gantry(session, road_id=road_a.id, gantry_id="TESTROADA:g", lat=point_a[0], lng=point_a[1])
    await _add_gantry(session, road_id=road_b.id, gantry_id="TESTROADB:g", lat=point_b[0], lng=point_b[1])

    trip = await _create_trip(client, headers, tariff.id, start_lat=point_a[0], start_lng=point_a[1])
    t0 = datetime.fromisoformat(trip["start_at"])

    body1 = await _tick(client, headers, trip["id"], lat=point_a[0], lng=point_a[1], ts=t0 + timedelta(seconds=5))
    assert Decimal(body1["tolls"]) == Decimal("6.06")

    body2 = await _tick(client, headers, trip["id"], lat=point_b[0], lng=point_b[1], ts=t0 + timedelta(minutes=5))
    assert Decimal(body2["tolls"]) == Decimal("6.06") + Decimal("4.30")
    assert body2["auto_tolled_roads"] == {"TESTROADA": "6.06", "TESTROADB": "4.30"}


# --- unrecognised / unpriced models: flagged, never guessed -----------------------


async def test_retired_or_unknown_pricing_model_is_flagged_not_charged(
    client: AsyncClient, session: AsyncSession
):
    """A road whose `pricing_model` this service has no formula for must be
    FLAGGED for manual entry, never charged a guessed number.

    Uses the retired "zone_flat" value deliberately. Before the 2026-09-07
    correction pass that model was a real, explicitly-handled branch (an
    ambiguous min-max range across a road's cheapest ramp and its full
    mainline toll); the pass replaced it with real per-toll-point prices, so
    "zone_flat" is now exactly what this test needs — a value a production
    database seeded by an older build can genuinely still contain, which
    today's code does not recognise. It stands in for any future model that
    reaches this service before its formula does; the invariant under test
    ("no formula -> unpriced, never a guess") is what matters, not the
    string."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTZONE", pricing_model="zone_flat", directional="both", price_class_a="3.15")
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTZONE:g", lat=gantry_lat, lng=gantry_lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=gantry_lat, start_lng=gantry_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=gantry_lat, lng=gantry_lng, ts=t0 + timedelta(seconds=5))
    assert body["tolls"] == "0.00"
    assert body["auto_tolled_roads"] == {}
    assert body["unpriced_toll_road_ids"] == ["TESTZONE"]


async def test_unpriced_road_is_flagged_not_charged(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTUNPRICED", pricing_model="unpriced", directional="both")
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTUNPRICED:g", lat=gantry_lat, lng=gantry_lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=gantry_lat, start_lng=gantry_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=gantry_lat, lng=gantry_lng, ts=t0 + timedelta(seconds=5))
    assert body["tolls"] == "0.00"
    assert body["unpriced_toll_road_ids"] == ["TESTUNPRICED"]


# --- per_point roads: cumulative vs. once-per-road -------------------------------
#
# The 2026-09-07 correction pass replaced the old, ambiguous `zone_flat` model
# (one min-max range per road, never chargeable) with real per-toll-point
# prices, and with it a second axis the old model had no need for: a road's
# `charging_policy`. `pricing_model` alone does NOT determine what a trip is
# charged -- M2 and Lane Cove Tunnel are both "per_point" and charge
# differently -- so both policies are pinned here against the real endpoint.


async def test_cumulative_per_point_road_charges_each_distinct_point(
    client: AsyncClient, session: AsyncSession
):
    """Hills M2's own pricing page prices by the number of toll points
    traversed, so a trip through two of its points pays for BOTH. This is the
    one road where a second gantry of the SAME road adds money rather than
    being deduplicated away."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(
        session,
        road_id="TESTCUMUL",
        pricing_model="per_point",
        charging_policy="cumulative_per_point",
        directional="both",
    )
    await _make_toll_point(session, road_id=road.id, point_id="TESTCUMUL:p1", price_class_a="10.64")
    await _make_toll_point(session, road_id=road.id, point_id="TESTCUMUL:p2", price_class_a="3.76")
    point_a = _next_zone()
    point_b = _next_zone()
    # Two gantries per toll point, metres apart, as a real mainline has -- so a single
    # pass corroborates the road (see TOLL_CONFIRM_RADIUS_M) without having to reach
    # the second, distant toll point first.
    for suffix, point in (("a", point_a), ("b", point_a)):
        await _add_gantry(
            session, road_id=road.id, gantry_id=f"TESTCUMUL:g1{suffix}",
            lat=point[0] + (0.0002 if suffix == "b" else 0.0), lng=point[1],
            toll_point_id="TESTCUMUL:p1",
        )
    for suffix in ("a", "b"):
        await _add_gantry(
            session, road_id=road.id, gantry_id=f"TESTCUMUL:g2{suffix}",
            lat=point_b[0] + (0.0002 if suffix == "b" else 0.0), lng=point_b[1],
            toll_point_id="TESTCUMUL:p2",
        )

    trip = await _create_trip(client, headers, tariff.id, start_lat=point_a[0], start_lng=point_a[1])
    t0 = datetime.fromisoformat(trip["start_at"])

    body1 = await _tick(client, headers, trip["id"], lat=point_a[0], lng=point_a[1], ts=t0 + timedelta(seconds=5))
    assert Decimal(body1["tolls"]) == Decimal("10.64")

    body2 = await _tick(client, headers, trip["id"], lat=point_b[0], lng=point_b[1], ts=t0 + timedelta(minutes=5))
    assert Decimal(body2["tolls"]) == Decimal("10.64") + Decimal("3.76")
    # Keyed by TOLL POINT id, not road id -- the receipt has to be able to
    # show which points were actually traversed, not one merged road total.
    assert body2["auto_tolled_roads"] == {"TESTCUMUL:p1": "10.64", "TESTCUMUL:p2": "3.76"}

    # Re-crossing an already-charged point never adds it twice.
    body3 = await _tick(client, headers, trip["id"], lat=point_a[0], lng=point_a[1], ts=t0 + timedelta(minutes=10))
    assert Decimal(body3["tolls"]) == Decimal("10.64") + Decimal("3.76")


async def test_once_per_road_per_point_road_charges_only_the_first_point_crossed(
    client: AsyncClient, session: AsyncSession
):
    """Cross City Tunnel and Lane Cove Tunnel have real per-point prices but
    their two points are a main tunnel and an alternate ramp a vehicle uses
    ONE of. Neither source says those are cumulative, so they are modelled
    once_per_road -- the deliberate no-overcharge reading (a flagged
    interpretation call; see app.models.toll's module docstring). This test
    exists so that call can never be silently reversed by a refactor."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(
        session,
        road_id="TESTONCEPP",
        pricing_model="per_point",
        charging_policy="once_per_road",
        directional="both",
    )
    await _make_toll_point(session, road_id=road.id, point_id="TESTONCEPP:main", price_class_a="4.30")
    await _make_toll_point(session, road_id=road.id, point_id="TESTONCEPP:ramp", price_class_a="2.15")
    point_a = _next_zone()
    point_b = _next_zone()
    # Two gantries on the main point. This is not just fixture convenience: a
    # once_per_road per_point road's points are ALTERNATIVES (main tunnel vs. ramp),
    # so a vehicle only ever crosses one, and corroboration has to be satisfiable
    # from a single point's own gantries or the road could never charge at all. The
    # real Lane Cove Tunnel has six mainline gantries for exactly one main point.
    await _add_gantry(
        session, road_id=road.id, gantry_id="TESTONCEPP:g1a",
        lat=point_a[0], lng=point_a[1], toll_point_id="TESTONCEPP:main",
    )
    await _add_gantry(
        session, road_id=road.id, gantry_id="TESTONCEPP:g1b",
        lat=point_a[0] + 0.0002, lng=point_a[1], toll_point_id="TESTONCEPP:main",
    )
    await _add_gantry(
        session, road_id=road.id, gantry_id="TESTONCEPP:g2",
        lat=point_b[0], lng=point_b[1], toll_point_id="TESTONCEPP:ramp",
    )

    trip = await _create_trip(client, headers, tariff.id, start_lat=point_a[0], start_lng=point_a[1])
    t0 = datetime.fromisoformat(trip["start_at"])

    body1 = await _tick(client, headers, trip["id"], lat=point_a[0], lng=point_a[1], ts=t0 + timedelta(seconds=5))
    assert Decimal(body1["tolls"]) == Decimal("4.30")
    # Charged under the ROAD id here, not the point id -- there is only ever
    # one charge for this road per trip, so the road is the honest key.
    assert body1["auto_tolled_roads"] == {"TESTONCEPP": "4.30"}

    body2 = await _tick(client, headers, trip["id"], lat=point_b[0], lng=point_b[1], ts=t0 + timedelta(minutes=5))
    assert Decimal(body2["tolls"]) == Decimal("4.30"), "a second point of a once_per_road road must never add"


async def test_per_point_road_with_an_unpriced_point_is_flagged_not_guessed(
    client: AsyncClient, session: AsyncSession
):
    """A toll point the source data never resolved a price for must be
    flagged for manual entry, exactly like an unpriced road -- never charged
    the road-level min/max range, which is a descriptive summary and not a
    real per-crossing price."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(
        session,
        road_id="TESTPPUNPRICED",
        pricing_model="per_point",
        charging_policy="cumulative_per_point",
        directional="both",
    )
    await _make_toll_point(session, road_id=road.id, point_id="TESTPPUNPRICED:p1", price_class_a=None)
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(
        session, road_id=road.id, gantry_id="TESTPPUNPRICED:g1",
        lat=gantry_lat, lng=gantry_lng, toll_point_id="TESTPPUNPRICED:p1",
    )

    trip = await _create_trip(client, headers, tariff.id, start_lat=gantry_lat, start_lng=gantry_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=gantry_lat, lng=gantry_lng, ts=t0 + timedelta(seconds=5))
    assert body["tolls"] == "0.00"
    assert body["auto_tolled_roads"] == {}
    assert body["unpriced_toll_road_ids"] == ["TESTPPUNPRICED:p1"]


# --- WestConnex network-wide cap -------------------------------------------------


async def test_network_group_roads_share_one_capped_total(client: AsyncClient, session: AsyncSession):
    """A trip using more than one WestConnex stage is capped for the WHOLE
    network, not just per road. Two roads that would each bill their own
    per-road amount must together never exceed the shared network cap."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    # Two stages of one network. Each is generously capped on its own
    # (9.00 each = 18.00 if the network cap were not applied), against a
    # shared network cap of 12.74 -- the real published WestConnex figure.
    common = {
        "pricing_model": "distance_with_flagfall",
        "charging_policy": "distance_metered",
        "directional": "both",
        "network_group": "TESTNET",
        "price_class_a": None,
        "rate_per_km_class_a": "5.0000",
        "flagfall_class_a": "1.80",
        "cap_class_a": "9.00",
        "network_cap_class_a": "12.74",
    }
    road_a = await _make_road(session, road_id="TESTNETA", **common)
    road_b = await _make_road(session, road_id="TESTNETB", **common)

    # Two gantries per road, ~5.5 km apart: an entry one that anchors the distance accrual and an
    # exit one that revises it. A distance road is only ever re-priced on a tick that is actually
    # NEAR one of its gantries (apply_toll_detection returns early otherwise), which is why a
    # single gantry per road would leave both charges pinned at their entry-tick value of zero.
    entry_a = _next_zone()
    entry_b = _next_zone()
    exit_a = (entry_a[0] + 0.05, entry_a[1])
    exit_b = (entry_b[0] + 0.05, entry_b[1])
    await _add_gantry(session, road_id=road_a.id, gantry_id="TESTNETA:in", lat=entry_a[0], lng=entry_a[1])
    await _add_gantry(session, road_id=road_a.id, gantry_id="TESTNETA:out", lat=exit_a[0], lng=exit_a[1])
    await _add_gantry(session, road_id=road_b.id, gantry_id="TESTNETB:in", lat=entry_b[0], lng=entry_b[1])
    await _add_gantry(session, road_id=road_b.id, gantry_id="TESTNETB:out", lat=exit_b[0], lng=exit_b[1])

    trip = await _create_trip(client, headers, tariff.id, start_lat=entry_a[0], start_lng=entry_a[1])
    t0 = datetime.fromisoformat(trip["start_at"])

    # Drive far enough on each stage that both would hit their own per-road cap.
    await _tick(client, headers, trip["id"], lat=entry_a[0], lng=entry_a[1], ts=t0 + timedelta(seconds=5))
    await _tick(client, headers, trip["id"], lat=exit_a[0], lng=exit_a[1], ts=t0 + timedelta(minutes=5))
    await _tick(client, headers, trip["id"], lat=entry_b[0], lng=entry_b[1], ts=t0 + timedelta(minutes=10))
    body = await _tick(client, headers, trip["id"], lat=exit_b[0], lng=exit_b[1], ts=t0 + timedelta(minutes=15))

    charged = {k: Decimal(v) for k, v in body["auto_tolled_roads"].items()}
    assert set(charged) == {"TESTNETA", "TESTNETB"}
    assert sum(charged.values()) <= Decimal("12.74"), charged
    assert Decimal(body["tolls"]) <= Decimal("12.74")
    # And the cap really bit -- otherwise this test would pass on any
    # implementation that simply undercharged.
    assert sum(charged.values()) > Decimal("9.00"), "each road alone caps at 9.00; the network total should exceed that"


# --- adjacent-road false positives ------------------------------------------------
#
# Field report, 2026-09-07: vehicles that never entered a toll road were being
# charged for it, because a single fix inside the 150m detection radius was enough.
# Australian motorways are flanked by service roads, and a tunnel gantry's
# coordinate is the surface projection of a point underground.


async def test_driving_parallel_to_a_toll_road_is_never_charged(
    client: AsyncClient, session: AsyncSession
):
    """~100m to the side of the corridor for the entire pass: inside the 150m watch
    radius the whole way, never inside the 60m confirmation radius. This is the
    service-road case, and it must cost the passenger nothing."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTPARALLEL", pricing_model="flat", price_class_a="5.00")
    lat, lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTPARALLEL:g1", lat=lat, lng=lng)
    await _add_gantry(session, road_id=road.id, gantry_id="TESTPARALLEL:g2", lat=lat + 0.0002, lng=lng)

    parallel_lng = lng + 0.00108  # ~100m to the side
    trip = await _create_trip(client, headers, tariff.id, start_lat=lat - 0.0027, start_lng=parallel_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = None
    for step in range(5):
        body = await _tick(
            client, headers, trip["id"],
            lat=lat + step * 0.0001, lng=parallel_lng,
            ts=t0 + timedelta(seconds=10 * (step + 1)),
        )

    assert Decimal(body["tolls"]) == Decimal("0.00")
    assert body["auto_tolled_roads"] == {}
    # And not nagged about either: a road driven past is not a road whose price is
    # unknown, and flagging it would just relocate the false charge to a prompt.
    assert body["unpriced_toll_road_ids"] == []


async def test_a_single_close_pass_is_not_enough_on_a_multi_gantry_road(
    client: AsyncClient, session: AsyncSession
):
    """The cross-street-over-a-tunnel case: one genuinely sub-60m fix, which alone is
    a coincidence rather than evidence of having travelled the road."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTONEPASS", pricing_model="flat", price_class_a="4.30")
    lat, lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTONEPASS:g1", lat=lat, lng=lng)
    await _add_gantry(session, road_id=road.id, gantry_id="TESTONEPASS:g2", lat=lat - 0.05, lng=lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=lat, start_lng=lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=lat, lng=lng, ts=t0 + timedelta(seconds=5))

    assert Decimal(body["tolls"]) == Decimal("0.00")
    assert body["auto_tolled_roads"] == {}


async def test_a_road_with_one_known_gantry_still_charges_on_one_close_pass(
    client: AsyncClient, session: AsyncSession
):
    """Corroboration must never make a road permanently unchargeable. Where the
    dataset cannot supply a second checkpoint, the tight 60m test is the whole gate
    -- trading a guaranteed missed charge for a second opinion that does not exist
    would be the worse deal."""
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    road = await _make_road(session, road_id="TESTSOLO", pricing_model="flat", price_class_a="9.16")
    lat, lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTSOLO:only", lat=lat, lng=lng)

    trip = await _create_trip(client, headers, tariff.id, start_lat=lat, start_lng=lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=lat, lng=lng, ts=t0 + timedelta(seconds=5))

    assert Decimal(body["tolls"]) == Decimal("9.16")


# --- /v1/toll-roads read API -------------------------------------------------------


async def test_list_toll_roads_shows_gantry_count_and_current_price(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    road = await _make_road(session, road_id="TESTLIST", pricing_model="flat", directional="both", price_class_a="4.30")
    await _add_gantry(session, road_id=road.id, gantry_id="TESTLIST:g1", lat=-34.0, lng=151.0)
    await _add_gantry(session, road_id=road.id, gantry_id="TESTLIST:g2", lat=-34.1, lng=151.1)

    resp = await client.get("/v1/toll-roads", headers=headers)
    assert resp.status_code == 200, resp.text
    by_id = {row["id"]: row for row in resp.json()}
    assert by_id["TESTLIST"]["gantry_count"] == 2
    assert by_id["TESTLIST"]["current_price"]["price_class_a_max"] == "4.30"
    assert by_id["TESTLIST"]["pricing_model"] == "flat"


async def test_get_toll_road_detail_includes_gantries_and_price_history(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    road = await _make_road(session, road_id="TESTDETAIL", pricing_model="flat", directional="both", price_class_a="4.30")
    await _add_gantry(session, road_id=road.id, gantry_id="TESTDETAIL:g1", lat=-34.0, lng=151.0)

    resp = await client.get("/v1/toll-roads/TESTDETAIL", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert len(body["gantries"]) == 1
    assert len(body["price_history"]) == 1


async def test_toll_roads_api_exposes_per_point_prices_and_charging_policy(
    client: AsyncClient, session: AsyncSession
):
    """A `per_point` road has no real road-level price -- its min/max is a
    descriptive range across its points, never charged -- so the API must
    return the points themselves, on the LIST endpoint as well as the detail
    one. Without this the dashboard has nothing true to show for M2/CCT/LCT
    and the meter caches a road it cannot price.

    `charging_policy` is exposed for the same reason it exists at all: it is
    not derivable from `pricing_model` (M2 and LCT are both "per_point" and
    charge differently), so a consumer that only sees `pricing_model` cannot
    reproduce what a trip is actually billed."""
    headers = await auth_headers(client, session, role="driver")
    road = await _make_road(
        session,
        road_id="TESTPPAPI",
        pricing_model="per_point",
        charging_policy="cumulative_per_point",
        directional="both",
    )
    await _make_toll_point(session, road_id=road.id, point_id="TESTPPAPI:p1", price_class_a="5.32")
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(
        session, road_id=road.id, gantry_id="TESTPPAPI:g1",
        lat=gantry_lat, lng=gantry_lng, toll_point_id="TESTPPAPI:p1",
    )

    listed = await client.get("/v1/toll-roads", headers=headers)
    assert listed.status_code == 200, listed.text
    row = {r["id"]: r for r in listed.json()}["TESTPPAPI"]
    assert row["charging_policy"] == "cumulative_per_point"
    assert [(p["id"], p["current_price"]["price_class_a"]) for p in row["toll_points"]] == [
        ("TESTPPAPI:p1", "5.32")
    ]
    assert row["toll_points"][0]["gantry_count"] == 1

    detail = await client.get("/v1/toll-roads/TESTPPAPI", headers=headers)
    assert detail.status_code == 200, detail.text
    body = detail.json()
    assert len(body["toll_points"]) == 1
    # The gantry says which point it belongs to, so a consumer can price a
    # detected crossing without a second lookup.
    assert body["gantries"][0]["toll_point_id"] == "TESTPPAPI:p1"


async def test_toll_roads_api_omits_toll_points_for_a_road_priced_at_road_level(
    client: AsyncClient, session: AsyncSession
):
    """Every non-`per_point` road prices at the road level, so an empty list
    here is the honest answer -- not merely a redundant one."""
    headers = await auth_headers(client, session, role="driver")
    road = await _make_road(session, road_id="TESTFLATAPI", pricing_model="flat", price_class_a="4.30")
    gantry_lat, gantry_lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTFLATAPI:g", lat=gantry_lat, lng=gantry_lng)

    resp = await client.get("/v1/toll-roads/TESTFLATAPI", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["toll_points"] == []
    assert body["charging_policy"] == "once_per_road"
    assert body["gantries"][0]["toll_point_id"] is None


async def test_toll_roads_api_exposes_the_distance_formula_and_its_source(
    client: AsyncClient, session: AsyncSession
):
    """The published $/km rate, flagfall and network cap are what a distance
    road is actually billed from, and `source_url` is what lets an operator
    defend a disputed toll by opening the page the figure came from. All
    three were added by the 2026-09-07 correction pass and are useless if
    they never leave the database."""
    headers = await auth_headers(client, session, role="driver")
    await _make_road(
        session,
        road_id="TESTFORMULA",
        pricing_model="distance_with_flagfall",
        charging_policy="distance_metered",
        network_group="TESTNETAPI",
        price_class_a=None,
        rate_per_km_class_a="0.6667",
        flagfall_class_a="1.80",
        cap_class_a="9.00",
        network_cap_class_a="12.74",
    )

    resp = await client.get("/v1/toll-roads/TESTFORMULA", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["network_group"] == "TESTNETAPI"
    price = body["current_price"]
    assert price["rate_per_km_class_a"] == "0.6667"
    assert price["flagfall_class_a"] == "1.80"
    assert price["network_cap_class_a"] == "12.74"


async def test_list_all_toll_gantries_returns_every_gantry_with_real_coordinates(
    client: AsyncClient, session: AsyncSession
):
    """The dashboard plots the whole registry on one map from this. Coordinates
    are the whole point of the endpoint, so they are asserted explicitly —
    a gantry list without them is not a pinpoint, it is a name."""
    headers = await auth_headers(client, session, role="driver")
    road = await _make_road(session, road_id="TESTGANTRYALL", pricing_model="flat", price_class_a="4.30")
    lat, lng = _next_zone()
    await _add_gantry(session, road_id=road.id, gantry_id="TESTGANTRYALL:g1", lat=lat, lng=lng)

    resp = await client.get("/v1/toll-roads/gantries", headers=headers)
    assert resp.status_code == 200, resp.text
    by_id = {row["id"]: row for row in resp.json()}
    gantry = by_id["TESTGANTRYALL:g1"]
    assert gantry["latitude"] == lat
    assert gantry["longitude"] == lng
    assert gantry["toll_road_id"] == "TESTGANTRYALL"


async def test_gantries_path_is_not_shadowed_by_the_road_detail_route(
    client: AsyncClient, session: AsyncSession
):
    """FastAPI matches routes in declaration order. If `GET /{road_id}` is ever
    moved above `GET /gantries`, this path silently becomes a lookup for a road
    literally named "gantries" and 404s — a regression no other test would
    catch, because both routes keep working on their own."""
    headers = await auth_headers(client, session, role="driver")

    resp = await client.get("/v1/toll-roads/gantries", headers=headers)

    assert resp.status_code == 200, resp.text
    assert isinstance(resp.json(), list)


async def test_get_unknown_toll_road_is_404(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    resp = await client.get("/v1/toll-roads/DOES-NOT-EXIST", headers=headers)
    assert resp.status_code == 404


# --- price revisions: platform-owner-only, additive not destructive -------------


async def _platform_owner_headers(client, session):
    result = await session.execute(select(Tenant).where(Tenant.id == PLATFORM_TENANT_ID))
    if result.scalar_one_or_none() is None:
        session.add(Tenant(id=PLATFORM_TENANT_ID, name="TCT", plan="platform"))
        await session.commit()
    return await auth_headers(client, session, role="owner", tenant_id=PLATFORM_TENANT_ID)


async def test_tenant_admin_cannot_create_price_revision(client: AsyncClient, session: AsyncSession):
    await _make_road(session, road_id="TESTREV", pricing_model="flat", directional="both", price_class_a="4.30")
    headers = await auth_headers(client, session, role="admin")

    resp = await client.post(
        "/v1/toll-roads/TESTREV/price-revisions",
        json={
            "price_class_a_min": "4.50", "price_class_a_max": "4.50",
            "effective_date": "2026-10-01", "indexation": "quarterly", "confidence": "verified",
        },
        headers=headers,
    )
    assert resp.status_code == 403


async def test_platform_owner_adds_price_revision_without_destroying_old_one(client: AsyncClient, session: AsyncSession):
    road = await _make_road(session, road_id="TESTREV2", pricing_model="flat", directional="both", price_class_a="4.30")
    owner_headers = await _platform_owner_headers(client, session)

    # A date strictly between the original 2026-07-01 revision and "today"
    # (this suite's fixed reference "now" is 2026-09), so the new revision
    # is genuinely the CURRENT one, not a future-dated one that hasn't taken
    # effect yet -- app.services.tolls.current_price_revision only ever picks
    # a revision whose effective_date has arrived.
    resp = await client.post(
        "/v1/toll-roads/TESTREV2/price-revisions",
        json={
            "price_class_a_min": "4.50", "price_class_a_max": "4.50",
            "effective_date": "2026-08-01", "indexation": "quarterly", "confidence": "verified",
        },
        headers=owner_headers,
    )
    assert resp.status_code == 201, resp.text

    detail = await client.get(f"/v1/toll-roads/{road.id}", headers=owner_headers)
    prices = {row["effective_date"]: row["price_class_a_max"] for row in detail.json()["price_history"]}
    # Both the original (seeded via _make_road, effective 2026-07-01) AND the
    # new quarterly revision must be present -- the old one is never deleted.
    assert prices["2026-07-01"] == "4.30"
    assert prices["2026-08-01"] == "4.50"
    # And the road now PRICES at the new revision going forward.
    assert detail.json()["current_price"]["price_class_a_max"] == "4.50"


async def test_duplicate_effective_date_price_revision_is_409(client: AsyncClient, session: AsyncSession):
    await _make_road(session, road_id="TESTREV3", pricing_model="flat", directional="both", price_class_a="4.30")
    owner_headers = await _platform_owner_headers(client, session)

    resp = await client.post(
        "/v1/toll-roads/TESTREV3/price-revisions",
        json={
            "price_class_a_min": "4.30", "price_class_a_max": "4.30",
            "effective_date": "2026-07-01", "indexation": "quarterly", "confidence": "verified",
        },
        headers=owner_headers,
    )
    assert resp.status_code == 409
