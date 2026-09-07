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
"""
from __future__ import annotations

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
_ROADS_UNDER_TEST = ["CCT", "LCT", "ED", "M7"]

# M7 is a KNOWN OPEN CASE for the parallel-drive test below, kept visible as an
# xfail rather than deleted or quietly excluded.
#
# The corroboration rule (60m confirmation radius + two confirmed gantries) stops
# the parallel-road false charge on every road here except Westlink M7, which has
# 45 gantries spread over 52 km. A vehicle driving a road that runs alongside it
# for tens of kilometres eventually dips within 60m at two separate bends, and two
# confirmations anywhere along a road that long is too weak a test.
#
# The obvious tightening -- requiring the two confirmations to be close together in
# trip distance -- was tried and REJECTED on evidence: real consecutive gantry gaps
# in this dataset reach 19.76 km on M7 and 3-7 km on LCT, M2, M4, M8, so any
# threshold loose enough to keep those roads chargeable is far too loose to reject
# a parallel drive. It would have traded a false charge for several guaranteed
# missed ones.
#
# The real fix is corridor-ORDER matching: require the confirmed gantries to be
# adjacent in the road's own along-corridor sequence, which is independent of how
# far apart they happen to sit. That needs a corridor ordering available to both
# the device and the server, which is a larger change than this pass.
_PARALLEL_KNOWN_OPEN = {"M7"}

# ~100m east at Sydney's latitude. Inside the 150m watch radius the whole way,
# outside the 60m confirmation radius the whole way -- a service road.
_PARALLEL_LNG_OFFSET = 0.00108


async def _real_gantries(session: AsyncSession, road_id: str) -> list[TollGantry]:
    result = await session.execute(
        select(TollGantry).where(TollGantry.toll_road_id == road_id).order_by(TollGantry.id)
    )
    return list(result.scalars().all())


async def _drive(
    client: AsyncClient,
    headers: dict,
    tariff_id: str,
    gantries: list[TollGantry],
    *,
    lng_offset: float = 0.0,
) -> dict:
    """Drive a trip across `gantries` in order, one telemetry point each."""
    first = gantries[0]
    trip = await _create_trip(
        client, headers, tariff_id,
        start_lat=first.latitude - 0.004, start_lng=first.longitude + lng_offset,
    )
    t0 = datetime.fromisoformat(trip["start_at"])

    body: dict = {}
    for index, gantry in enumerate(gantries):
        body = await _tick(
            client, headers, trip["id"],
            lat=gantry.latitude, lng=gantry.longitude + lng_offset,
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

    # Guard the premise: if the offset track were outside the 150m watch radius the
    # test would pass trivially, proving nothing about the corroboration rule.
    watched = any(
        haversine_m(g.latitude, g.longitude + _PARALLEL_LNG_OFFSET, g.latitude, g.longitude) <= 150.0
        for g in gantries
    )
    assert watched, "the parallel track must stay inside the watch radius or this proves nothing"

    body = await _drive(client, headers, tariff.id, gantries, lng_offset=_PARALLEL_LNG_OFFSET)

    if road_id in _PARALLEL_KNOWN_OPEN:
        pytest.xfail(
            f"{road_id}: known open case -- see _PARALLEL_KNOWN_OPEN. Charged {body['tolls']}."
        )

    assert Decimal(body["tolls"]) == Decimal("0.00"), (
        f"{road_id} charged {body['tolls']} to a vehicle that never left the adjacent road"
    )
    assert body["auto_tolled_roads"] == {}
