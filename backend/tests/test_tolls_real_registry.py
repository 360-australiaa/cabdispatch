"""Corroboration against the REAL seeded NSW registry, not synthetic fixtures.

`test_tolls.py` proves the corroboration rule with hand-built roads. That is the
right place to pin the logic, but it cannot answer the question that actually
matters after adding the rule:

    does every real toll road still charge when a vehicle genuinely drives it?

A rule that stops false charges by also stopping true ones is not an improvement,
and the failure would be silent -- an under-charge nobody notices until the
operator reconciles revenue. The real dataset is what decides this: Cross City
Tunnel has 3 gantries, the M1 Eastern Distributor 2, Westlink M7 45. A rule
demanding two confirmations behaves differently on each.

So these tests drive each road's OWN real gantry coordinates, exactly as the
in-app GPS simulator does, and assert it charges. Then they drive the same
corridor offset to the side and assert it does NOT -- the service-road case the
rule exists for, measured against real geometry rather than a convenient fixture.

One warning for anyone extending this file: the sideways offset must be
PERPENDICULAR to the local corridor (`_offset_perpendicular`), never a constant
shift in longitude. A constant eastward shift stops being a lateral offset
wherever the corridor runs east-west, and M7's gantries sit in tight entry/exit
clusters at each interchange -- so 100m east of one gantry is ~28m from its
neighbour. The first version of this test made exactly that mistake, produced a
"parallel" track that actually weaved through the interchange, and led to a wrong
conclusion that the corroboration rule had a hole in it. It does not: with a real
perpendicular offset, M7 confirms one gantry out of 45 and charges nothing.
"""
from __future__ import annotations

import math
from datetime import datetime, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.toll import TollGantry
from app.services.tolls import haversine_m
from scripts.seed_toll_roads import seed_toll_roads

from .test_tolls import _create_trip, _seed_tariff, _tenant_of, _tick, auth_headers

pytestmark = pytest.mark.asyncio

# Roads chosen to cover every shape the corroboration rule can encounter on real
# data: a 3-gantry tunnel (the worst false-positive case, since its gantry
# coordinate is the surface projection of a point underground), a 6-gantry
# tunnel, a 2-gantry surface motorway sitting exactly at the corroboration
# minimum, and a 45-gantry distance-priced corridor.
# Every road in the registry that has BOTH gantries (so it can be GPS-detected at
# all) and a price. Deliberately the full set rather than a sample: the question
# this file answers -- "does the corroboration rule leave any real road
# unchargeable?" -- is only answered by asking it of every road.
#
# Excluded, and why, so the gaps are visible rather than silently untested:
#   M5E                  priced, but the dataset has zero gantry coordinates for
#                        it, so it cannot be auto-detected by any rule.
#   ROZELLE_INTERCHANGE  genuinely unpriced in the source data, and (as of the
#                        2026-09-09 correction) has zero gantry coordinates of
#                        its own -- see scripts/seed_toll_roads.py.
#   M12, IRON_COVE_LINK  real gantries, but `pricing_model="toll_free"` --
#                        `test_...still_charges` below asserts tolls > 0, which
#                        is never true for these two by design. Covered
#                        instead by test_driving_m12_and_iron_cove_link_
#                        charges_nothing_and_is_never_flagged_unpriced below.
_ROADS_UNDER_TEST = [
    "CCT", "ED", "LCT", "M2", "M4", "M4M8_LINK", "M5SW", "M7", "M8",
    "NORTHCONNEX", "SHB_SHT",
]

# Lateral offset, in metres, of the "adjacent road" track below.
#
# 100m is a realistic separation between a motorway and its own service road, and
# sits comfortably outside the 60m confirmation radius while staying inside the
# 150m watch radius -- which is the whole point: the track must be something
# detection notices and charging still refuses.
_PARALLEL_OFFSET_M = 100.0


async def _real_gantries(session: AsyncSession, road_id: str) -> list[TollGantry]:
    result = await session.execute(
        select(TollGantry).where(TollGantry.toll_road_id == road_id).order_by(TollGantry.id)
    )
    return list(result.scalars().all())


def _offset_perpendicular(
    gantries: list[TollGantry], target: TollGantry, distance_m: float
) -> tuple[float, float]:
    """`target` moved `distance_m` sideways, perpendicular to the local corridor.

    Perpendicular to the corridor, NOT a fixed longitude shift. This distinction
    is the whole validity of the parallel test and it was got wrong first time
    round: a constant eastward offset stops being a LATERAL offset wherever the
    corridor runs east-west, and worse, M7's gantries sit in tight entry/exit
    clusters at each interchange, so shifting 100m east of one gantry lands ~28m
    from its neighbour. That produced a "parallel" track weaving through the
    interchange and a false conclusion that the corroboration rule had a hole in
    it. A real service road keeps its distance from the carriageway; so must this.

    Local corridor direction is taken as the bearing to the nearest other gantry.
    """
    lat1, lng1 = math.radians(target.latitude), math.radians(target.longitude)
    nearest = min(
        (g for g in gantries if g.id != target.id),
        key=lambda g: haversine_m(target.latitude, target.longitude, g.latitude, g.longitude),
    )
    lat2, lng2 = math.radians(nearest.latitude), math.radians(nearest.longitude)
    d_lng = lng2 - lng1
    corridor_bearing = math.atan2(
        math.sin(d_lng) * math.cos(lat2),
        math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(d_lng),
    )

    bearing = corridor_bearing + math.pi / 2  # sideways
    angular = distance_m / 6_371_008.8
    out_lat = math.asin(
        math.sin(lat1) * math.cos(angular) + math.cos(lat1) * math.sin(angular) * math.cos(bearing)
    )
    out_lng = lng1 + math.atan2(
        math.sin(bearing) * math.sin(angular) * math.cos(lat1),
        math.cos(angular) - math.sin(lat1) * math.sin(out_lat),
    )
    return math.degrees(out_lat), math.degrees(out_lng)


async def _drive(
    client: AsyncClient,
    headers: dict,
    tariff_id: str,
    gantries: list[TollGantry],
    *,
    offset_m: float = 0.0,
) -> dict:
    """Drive a trip across `gantries` in order, one telemetry point each, optionally
    offset `offset_m` sideways from the corridor the whole way."""
    def point(g: TollGantry) -> tuple[float, float]:
        if offset_m == 0.0:
            return g.latitude, g.longitude
        return _offset_perpendicular(gantries, g, offset_m)

    first_lat, first_lng = point(gantries[0])
    trip = await _create_trip(client, headers, tariff_id, start_lat=first_lat - 0.004, start_lng=first_lng)
    t0 = datetime.fromisoformat(trip["start_at"])

    body: dict = {}
    for index, gantry in enumerate(gantries):
        lat, lng = point(gantry)
        body = await _tick(
            client, headers, trip["id"], lat=lat, lng=lng,
            ts=t0 + timedelta(seconds=20 * (index + 1)),
        )
    return body


@pytest.mark.parametrize("road_id", _ROADS_UNDER_TEST)
async def test_driving_a_real_road_over_its_own_gantries_still_charges(
    client: AsyncClient, session: AsyncSession, road_id: str
):
    """The regression this file exists for: corroboration must not have made any
    real road unchargeable."""
    await seed_toll_roads()
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    gantries = await _real_gantries(session, road_id)
    assert gantries, f"{road_id} has no seeded gantries -- fixture assumption broken"

    body = await _drive(client, headers, tariff.id, gantries)

    charged = body["auto_tolled_roads"]
    assert charged, f"{road_id} was driven over every one of its real gantries and charged nothing"
    # Keyed by road id for every road here (none is cumulative_per_point), and the
    # amount must be a real figure, never zero-as-a-placeholder.
    assert Decimal(body["tolls"]) > 0, f"{road_id} charged {body['tolls']}"


@pytest.mark.parametrize("road_id", _ROADS_UNDER_TEST)
async def test_driving_parallel_to_a_real_road_charges_nothing(
    client: AsyncClient, session: AsyncSession, road_id: str
):
    """The same corridor, offset ~100m -- a service road or the street above a
    tunnel. Inside the watch radius the entire way, and it must still cost the
    passenger nothing."""
    await seed_toll_roads()
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    gantries = await _real_gantries(session, road_id)

    # Guard the premise twice over, because a parallel test that drifts out of range
    # passes for the wrong reason and proves nothing.
    offsets = [_offset_perpendicular(gantries, g, _PARALLEL_OFFSET_M) for g in gantries]
    watched = any(
        haversine_m(g.latitude, g.longitude, lat, lng) <= 150.0
        for g, (lat, lng) in zip(gantries, offsets)
    )
    assert watched, "the parallel track must stay inside the 150m watch radius"

    body = await _drive(client, headers, tariff.id, gantries, offset_m=_PARALLEL_OFFSET_M)

    assert Decimal(body["tolls"]) == Decimal("0.00"), (
        f"{road_id} charged {body['tolls']} to a vehicle that never left the adjacent road"
    )
    assert body["auto_tolled_roads"] == {}


# --- toll_free roads (2026-09-09 correction): M12, Iron Cove Link -----------------
#
# Both used to be modelled `pricing_model="unpriced"`, which meant a trip that
# genuinely drove either of them got flagged in `unpriced_toll_road_ids` -- the
# tablet's "this toll needs a price, enter manually" prompt -- for a road that is
# confirmed, by real government/Linkt policy, to never charge a cent. This proves
# the fix against the REAL seeded registry, not a synthetic fixture: driving every
# one of M12's 4 real gantries, and every one of Iron Cove Link's 6, must charge
# exactly $0.00 and must never appear in `unpriced_toll_road_ids`.
@pytest.mark.parametrize("road_id", ["M12", "IRON_COVE_LINK"])
async def test_driving_m12_and_iron_cove_link_charges_nothing_and_is_never_flagged_unpriced(
    client: AsyncClient, session: AsyncSession, road_id: str
):
    await seed_toll_roads()
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    gantries = await _real_gantries(session, road_id)
    assert gantries, f"{road_id} has no seeded gantries -- fixture assumption broken"

    body = await _drive(client, headers, tariff.id, gantries)

    assert Decimal(body["tolls"]) == Decimal("0.00"), (
        f"{road_id} is toll_free but charged {body['tolls']}"
    )
    assert body["unpriced_toll_road_ids"] == [], (
        f"{road_id} is toll_free (a confirmed zero) but was flagged as unpriced (an unknown price)"
    )
    # Detected and charged $0.00 -- NOT silently absent from auto_tolled_roads,
    # which would look identical to "never detected at all" on the dashboard.
    assert body["auto_tolled_roads"].get(road_id) == "0.00"
