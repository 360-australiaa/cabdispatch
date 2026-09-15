"""The `entry_exit` pricing model -- Linkt's own entry-point -> exit-point prices (2026-09-15,
owner: "copy all pricing from Linkt, Linkt pricing is accurate"). See `app.models.toll.TollPricePair`
and `app.services.tolls._apply_entry_exit_hits`.

Same `PATCH /v1/trips/{id}/tick` integration style as `tests/test_tolls.py`; the synthetic road here
is the Anzac Bridge -> Homebush Bay Drive shape: one WestConnex section that Linkt bills at $8.80
through the Rozelle Interchange and the M4 East, with an intermediate exit (Haberfield, $4.75) the
through trip passes without leaving.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.toll import TollGantry, TollPricePair, TollRoad
from app.services.fare_engine import NSW_FARE_ZONE
from app.services.tolls import select_pair_band_price
from tests.conftest import auth_headers
from tests.test_tolls import _next_zone, _tick
from tests.test_trips import _create_trip, _seed_tariff, _tenant_of

pytestmark = pytest.mark.asyncio

_BAND_ALL = [{"day": "all", "interval": "0000-2400", "price": 8.8}]


async def _make_entry_exit_road(session: AsyncSession, road_id: str) -> TollRoad:
    road = TollRoad(
        id=road_id,
        api_code=None,
        name=f"{road_id} (Linkt)",
        operator="Transurban",
        pricing_model="entry_exit",
        charging_policy="entry_exit_pair",
        network_group="WESTCONNEX",
        directional=None,
    )
    session.add(road)
    await session.commit()
    return road


async def _point(session: AsyncSession, *, road_id: str, gantry_id: str, role: str, lat: float, lng: float) -> TollGantry:
    g = TollGantry(
        id=gantry_id, toll_road_id=road_id, location=gantry_id, ramp=role, direction=None,
        latitude=lat, longitude=lng, source_sheet="linkt",
    )
    session.add(g)
    await session.commit()
    return g


async def _pair(session: AsyncSession, entry: str, exit_: str, bands_a: list[dict], name: str = "WestConnex") -> TollPricePair:
    pair = TollPricePair(
        id=f"{entry}->{exit_}", entry_gantry_id=entry, exit_gantry_id=exit_, billing_asset_id="140",
        billing_name=name, class_a_bands=bands_a, class_b_bands=bands_a, effective_date=date(2026, 9, 15),
    )
    session.add(pair)
    await session.commit()
    return pair


async def _anzac_to_homebush(session: AsyncSession, road_id: str) -> tuple[TollRoad, tuple[float, float], tuple[float, float], tuple[float, float]]:
    """One road, an entry point, an intermediate exit 2 km on, the final exit 4 km on; Linkt prices
    entry->intermediate at 4.75 and entry->final at 8.80."""
    road = await _make_entry_exit_road(session, road_id)
    lat, lng = _next_zone()
    e_id, mid_id, end_id = f"LINKT:{road_id}/anzac:entry", f"LINKT:{road_id}/haberfield:exit", f"LINKT:{road_id}/homebush:exit"
    entry = (lat, lng)
    mid = (lat, lng + 0.02)  # ~2 km east
    end = (lat, lng + 0.04)  # ~4 km east
    await _point(session, road_id=road.id, gantry_id=e_id, role="entry", lat=entry[0], lng=entry[1])
    await _point(session, road_id=road.id, gantry_id=mid_id, role="exit", lat=mid[0], lng=mid[1])
    await _point(session, road_id=road.id, gantry_id=end_id, role="exit", lat=end[0], lng=end[1])
    await _pair(session, e_id, mid_id, [{"day": "all", "interval": "0000-2400", "price": 4.75}])
    await _pair(session, e_id, end_id, _BAND_ALL)
    return road, entry, mid, end


async def test_through_trip_is_charged_the_pair_for_the_last_exit_passed(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    road, entry, mid, end = await _anzac_to_homebush(session, "TEST_WCX_THROUGH")

    trip = await _create_trip(client, headers, tariff.id, start_lat=entry[0], start_lng=entry[1] - 0.01)
    t0 = datetime.fromisoformat(trip["start_at"])

    body = await _tick(client, headers, trip["id"], lat=entry[0], lng=entry[1], ts=t0 + timedelta(seconds=30))
    assert Decimal(body["tolls"]) == Decimal("0.00")  # entry alone is not a charge
    assert body["unpriced_toll_road_ids"] == []

    body = await _tick(client, headers, trip["id"], lat=mid[0], lng=mid[1], ts=t0 + timedelta(seconds=120))
    assert Decimal(body["tolls"]) == Decimal("4.75")  # would exit at Haberfield: Linkt's 4.75
    assert body["auto_tolled_roads"] == {road.id: "4.75"}

    body = await _tick(client, headers, trip["id"], lat=end[0], lng=end[1], ts=t0 + timedelta(seconds=240))
    assert Decimal(body["tolls"]) == Decimal("8.80")  # drove on to Homebush: the through price, revised in place
    assert body["auto_tolled_roads"] == {road.id: "8.80"}
    assert body["unpriced_toll_road_ids"] == []


async def test_an_exit_with_no_entry_is_flagged_never_guessed(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    road, _entry, mid, end = await _anzac_to_homebush(session, "TEST_WCX_NOENTRY")

    trip = await _create_trip(client, headers, tariff.id, start_lat=mid[0], start_lng=mid[1] - 0.005)
    t0 = datetime.fromisoformat(trip["start_at"])
    body = await _tick(client, headers, trip["id"], lat=end[0], lng=end[1], ts=t0 + timedelta(seconds=60))
    assert Decimal(body["tolls"]) == Decimal("0.00")
    assert body["auto_tolled_roads"] == {}
    assert body["unpriced_toll_road_ids"] == [road.id]


async def test_a_second_section_on_the_same_road_adds_to_the_first(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    road, entry, mid, end = await _anzac_to_homebush(session, "TEST_WCX_TWICE")
    # A second entry point beyond the final exit, priced back to... nothing here; instead re-enter
    # at the same entry after leaving at Haberfield.
    trip = await _create_trip(client, headers, tariff.id, start_lat=entry[0], start_lng=entry[1] - 0.01)
    t0 = datetime.fromisoformat(trip["start_at"])
    await _tick(client, headers, trip["id"], lat=entry[0], lng=entry[1], ts=t0 + timedelta(seconds=30))
    body = await _tick(client, headers, trip["id"], lat=mid[0], lng=mid[1], ts=t0 + timedelta(seconds=120))
    assert Decimal(body["tolls"]) == Decimal("4.75")
    # off the motorway, back to the entry, on again, through to Homebush
    await _tick(client, headers, trip["id"], lat=entry[0] + 0.02, lng=entry[1], ts=t0 + timedelta(seconds=300))
    await _tick(client, headers, trip["id"], lat=entry[0], lng=entry[1], ts=t0 + timedelta(seconds=600))
    body = await _tick(client, headers, trip["id"], lat=end[0], lng=end[1], ts=t0 + timedelta(seconds=900))
    assert Decimal(body["tolls"]) == Decimal("13.55")  # 4.75 + 8.80, two sections
    assert body["auto_tolled_roads"] == {road.id: "13.55"}


def test_band_selection_follows_linkt_day_and_interval_in_nsw_time():
    bands = [
        {"day": "weekdays", "interval": "0630-0930", "price": 4.55},
        {"day": "weekdays", "interval": "0930-1600", "price": 3.41},
        {"day": "weekdays", "interval": "1600-1900", "price": 4.55},
        {"day": "weekdays", "interval": "1900-0630", "price": 2.85},
        {"day": "weekend", "interval": "0800-2000", "price": 3.41},
        {"day": "weekend", "interval": "2000-0800", "price": 2.85},
    ]
    mon_peak = datetime(2026, 9, 14, 8, 0, tzinfo=NSW_FARE_ZONE)  # Monday 08:00 Sydney
    assert select_pair_band_price(bands, mon_peak) == Decimal("4.55")
    assert select_pair_band_price(bands, mon_peak.astimezone(UTC)) == Decimal("4.55")  # sender's zone is irrelevant
    assert select_pair_band_price(bands, datetime(2026, 9, 14, 23, 30, tzinfo=NSW_FARE_ZONE)) == Decimal("2.85")  # wraps midnight
    assert select_pair_band_price(bands, datetime(2026, 9, 15, 2, 0, tzinfo=NSW_FARE_ZONE)) == Decimal("2.85")
    assert select_pair_band_price(bands, datetime(2026, 9, 19, 12, 0, tzinfo=NSW_FARE_ZONE)) == Decimal("3.41")  # Saturday
    assert select_pair_band_price(bands, datetime(2026, 9, 19, 21, 0, tzinfo=NSW_FARE_ZONE)) == Decimal("2.85")
    assert select_pair_band_price(_BAND_ALL, mon_peak) == Decimal("8.8")
    assert select_pair_band_price([], mon_peak) is None


async def test_price_pairs_endpoint_lists_every_pair(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    road, *_ = await _anzac_to_homebush(session, "TEST_WCX_API")
    resp = await client.get("/v1/toll-roads/price-pairs", headers=headers)
    assert resp.status_code == 200, resp.text
    pairs = {p["id"]: p for p in resp.json()}
    assert pairs[f"LINKT:{road.id}/anzac:entry->LINKT:{road.id}/homebush:exit"]["class_a_bands"] == [
        {"day": "all", "interval": "0000-2400", "price": "8.8"}
    ]
    resp = await client.get("/v1/toll-roads", headers=headers)
    assert resp.status_code == 200
    row = next(r for r in resp.json() if r["id"] == road.id)
    assert row["pricing_model"] == "entry_exit"
    assert row["entry_exit_pair_count"] == 2
    assert Decimal(row["entry_exit_min_class_a"]) == Decimal("4.75")
    assert Decimal(row["entry_exit_max_class_a"]) == Decimal("8.8")


async def _homebush_interchange(session: AsyncSession, road_id: str):
    """Anzac entry, Haberfield exit, then the Homebush interchange as Linkt models it: an entry
    point 15 m from its exit point, and Hill Road one more exit on."""
    road, entry, mid, end = await _anzac_to_homebush(session, road_id)
    homebush_entry = (end[0] + 0.00013, end[1])
    hill = (end[0], end[1] + 0.02)
    await _point(session, road_id=road.id, gantry_id=f"LINKT:{road_id}/homebush:entry", role="entry", lat=homebush_entry[0], lng=homebush_entry[1])
    await _point(session, road_id=road.id, gantry_id=f"LINKT:{road_id}/hill-rd:exit", role="exit", lat=hill[0], lng=hill[1])
    await _pair(session, f"LINKT:{road_id}/anzac:entry", f"LINKT:{road_id}/hill-rd:exit", [{"day": "all", "interval": "0000-2400", "price": 9.4}])
    await _pair(session, f"LINKT:{road_id}/homebush:entry", f"LINKT:{road_id}/hill-rd:exit", [{"day": "all", "interval": "0000-2400", "price": 2.2}])
    return road, entry, mid, end, hill


async def test_driving_through_an_interchange_does_not_open_a_second_section(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    road, entry, mid, end, hill = await _homebush_interchange(session, "TEST_WCX_THROUGH2")
    trip = await _create_trip(client, headers, tariff.id, start_lat=entry[0], start_lng=entry[1] - 0.01)
    t0 = datetime.fromisoformat(trip["start_at"])
    await _tick(client, headers, trip["id"], lat=entry[0], lng=entry[1], ts=t0 + timedelta(seconds=30))
    await _tick(client, headers, trip["id"], lat=mid[0], lng=mid[1], ts=t0 + timedelta(seconds=120))
    # Homebush: the exit point AND the co-located entry point are both inside 60 m of this tick.
    body = await _tick(client, headers, trip["id"], lat=end[0], lng=end[1], ts=t0 + timedelta(seconds=240))
    assert Decimal(body["tolls"]) == Decimal("8.80")
    # On to Hill Road: still the Anzac Bridge trip at Linkt's 9.40 -- not 8.80 + 2.20.
    body = await _tick(client, headers, trip["id"], lat=hill[0], lng=hill[1], ts=t0 + timedelta(seconds=360))
    assert Decimal(body["tolls"]) == Decimal("9.40")
    assert body["auto_tolled_roads"] == {road.id: "9.40"}


async def test_a_trip_starting_at_an_interchange_is_not_flagged_by_its_own_exit_point(client: AsyncClient, session: AsyncSession):
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)
    _road, _entry, _mid, end, hill = await _homebush_interchange(session, "TEST_WCX_START")
    trip = await _create_trip(client, headers, tariff.id, start_lat=end[0] - 0.003, start_lng=end[1])
    t0 = datetime.fromisoformat(trip["start_at"])
    body = await _tick(client, headers, trip["id"], lat=end[0], lng=end[1], ts=t0 + timedelta(seconds=30))
    assert body["unpriced_toll_road_ids"] == []
    assert Decimal(body["tolls"]) == Decimal("0.00")
    body = await _tick(client, headers, trip["id"], lat=hill[0], lng=hill[1], ts=t0 + timedelta(seconds=120))
    assert Decimal(body["tolls"]) == Decimal("2.20")
