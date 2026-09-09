"""Tests for the REAL chain sequence/cumulative-distance upgrade
(`app.models.toll.TollGantry.sequence_position` / `.cumulative_distance_km`,
`app.services.tolls._distance_to_real_chain_corridor_m`) — see
`scripts/seed_toll_roads.py`'s `_REAL_ROAD_CHAIN_WAYPOINTS` for the real
source data (the official NSW toll CartoDB `tollpoints_data` table) this
was built from, and that module's docstring for exactly which roads
qualified (only M5SW, as of this pass) and why every other real road with
gantries did not (a genuine branch/cycle/disconnected chain in the source
data, not a data-entry gap).

The fixture below is built from M5SW's REAL point-to-point distances
(1.8 / 1.6 / 3.5 / 3.4 km — the real `km_to_closest_cw` deltas between
Belmore Rd -> Fairford Rd -> The River Rd -> Henry Lawson Dr -> Hammondville
in the source `tollpoints_data` chain, cumulative 0 / 1.8 / 3.4 / 6.9 /
10.3 km), laid out as an L-shaped corridor (a north leg then an east leg —
a real road bending at an interchange, not merely a synthetic convenience)
so the difference between the OLD any-two-gantries chord approximation and
the NEW real-chain-adjacent-segments approximation is actually visible: the
OLD approximation also draws a chord across the START and END gantries
(skipping every real point between them), which cuts straight across the
inside of the bend — a shortcut the real road never takes. A vehicle
sitting in that cut-across gap is genuinely off the real corridor, but the
OLD chord approximation reads it as practically ON one (distance ~0m to the
WP0-WP4 chord); the NEW real-chain approximation, which only ever draws a
line between two waypoints that are actually ADJACENT on the real road,
correctly reads the same point as roughly 1.7km off both real legs — well
outside `CORRIDOR_EXIT_RADIUS_M` (750m) either way.
"""
from __future__ import annotations

import math
from datetime import datetime, timedelta
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.services.tolls import (
    CORRIDOR_EXIT_RADIUS_M,
    _distance_to_real_chain_corridor_m,
    _distance_to_road_corridor_m,
)
from tests.conftest import auth_headers
from tests.test_trips import _create_trip, _seed_tariff, _tenant_of

from .test_tolls import _add_gantry, _make_road, _next_zone, _tick

pytestmark = pytest.mark.asyncio

_KM_PER_DEG_LAT = 111.32  # standard spherical approximation, same order this
# module's own `_local_xy_m` already relies on for a "no PostGIS" small-table
# trade-off -- good enough to place synthetic test fixtures at real-world-ish
# distances, not meant to be survey-grade.


def _north_km(lat: float, km: float) -> float:
    return lat + km / _KM_PER_DEG_LAT


def _east_km(lat: float, lng: float, km: float) -> float:
    return lng + km / (_KM_PER_DEG_LAT * math.cos(math.radians(lat)))


# The real M5SW chain's own point-to-point distances (see module docstring).
_REAL_DELTAS_KM = [1.8, 1.6, 3.5, 3.4]
_REAL_CUMULATIVE_KM = [0.0, 1.8, 3.4, 6.9, 10.3]


def _l_shaped_waypoints(base_lat: float, base_lng: float) -> list[tuple[float, float]]:
    """5 waypoints, real M5SW deltas, laid out as an L: WP0->WP1->WP2 heads
    due north (3.4km total), WP2->WP3->WP4 then heads due east (6.9km
    total) -- the same corridor a real interchange bend looks like."""
    wp0 = (base_lat, base_lng)
    wp1 = (_north_km(base_lat, _REAL_CUMULATIVE_KM[1]), base_lng)
    wp2 = (_north_km(base_lat, _REAL_CUMULATIVE_KM[2]), base_lng)
    corner_lat = wp2[0]
    wp3 = (corner_lat, _east_km(corner_lat, base_lng, _REAL_DELTAS_KM[2]))
    wp4 = (corner_lat, _east_km(corner_lat, base_lng, _REAL_DELTAS_KM[2] + _REAL_DELTAS_KM[3]))
    return [wp0, wp1, wp2, wp3, wp4]


async def _seed_l_shaped_real_chain_road(session: AsyncSession, *, road_id: str) -> list[tuple[float, float]]:
    """A `distance` road, real M5SW-shaped L corridor, every gantry carrying
    a real `sequence_position`/`cumulative_distance_km` -- exactly the shape
    `scripts/seed_toll_roads.py` produces for the real M5SW road."""
    base_lat, base_lng = _next_zone()
    waypoints = _l_shaped_waypoints(base_lat, base_lng)

    road = await _make_road(
        session, road_id=road_id, pricing_model="distance", directional="both",
        price_class_a=None, cap_class_a="100.00", rate_per_km_class_a="1.00",
    )
    for i, (lat, lng) in enumerate(waypoints):
        await _add_gantry(
            session, road_id=road.id, gantry_id=f"{road_id}:g{i}", lat=lat, lng=lng,
            sequence_position=i, cumulative_distance_km=str(_REAL_CUMULATIVE_KM[i]),
        )
    return waypoints


def _corner_cut_point(waypoints: list[tuple[float, float]]) -> tuple[float, float]:
    """Midpoint of the straight line from the FIRST to the LAST waypoint --
    exactly the shortcut chord the OLD all-pairs approximation would draw,
    and exactly the point genuinely off the real (bent) corridor."""
    (lat0, lng0), (lat4, lng4) = waypoints[0], waypoints[-1]
    return (lat0 + lat4) / 2, (lng0 + lng4) / 2


# --- the core claim: real chain data beats the chord approximation ---------


async def test_real_chain_distance_correctly_flags_a_corner_cut_the_chord_approximation_misses(
    session: AsyncSession,
):
    """Direct proof of the upgrade at the geometry level, no HTTP involved:
    on the exact same 5 real-chain gantries, the OLD chord approximation
    (`_distance_to_road_corridor_m`, every pair of gantries) reads the
    corner-cut point as practically on the corridor, while the NEW real
    chain approximation (`_distance_to_real_chain_corridor_m`, only
    REALLY-adjacent pairs) correctly reads it as well off both real legs."""
    waypoints = await _seed_l_shaped_real_chain_road(session, road_id="TESTM5SWCHAIN")
    cut_lat, cut_lng = _corner_cut_point(waypoints)

    chord_distance = await _distance_to_road_corridor_m(session, road_id="TESTM5SWCHAIN", lat=cut_lat, lng=cut_lng)
    real_distance = await _distance_to_real_chain_corridor_m(
        session, road_id="TESTM5SWCHAIN", lat=cut_lat, lng=cut_lng
    )

    assert real_distance is not None, "a road with every gantry carrying real chain data must not fall back to None"
    # The OLD approximation is fooled by its own WP0-WP4 shortcut chord --
    # the corner-cut point sits almost exactly on it, by construction.
    assert chord_distance < 50.0, chord_distance
    # The NEW approximation only ever draws a line between REALLY adjacent
    # waypoints, so it correctly measures how far this point actually is
    # from either real leg of the bend -- comfortably outside the exit
    # radius, where the old figure was comfortably inside it.
    assert real_distance > CORRIDOR_EXIT_RADIUS_M, real_distance
    assert real_distance > 1_000.0, real_distance
    assert real_distance > chord_distance + CORRIDOR_EXIT_RADIUS_M, (
        "the real-chain distance must be decisively larger than the chord approximation's -- "
        "that gap IS the bug this upgrade fixes"
    )


async def test_apply_toll_detection_uses_real_chain_data_to_finalize_a_corner_cut_the_chord_approximation_would_miss(
    client: AsyncClient, session: AsyncSession
):
    """End-to-end proof through the real billing path: a vehicle that drives
    the real L-shaped corridor, then cuts straight across the inside of the
    bend (the WP0-WP4 shortcut) far enough to keep GPS-integrating a large
    extra distance, must have its bill FROZEN at the real corner -- not kept
    open and overbilled for a shortcut the real road never takes. Before
    this upgrade, `_distance_to_road_corridor_m`'s chord approximation would
    have read that shortcut as still-on-corridor (see the direct geometry
    test above) and kept revising the bill for the extra distance; the real
    chain data now correctly finalizes it instead.
    """
    headers = await auth_headers(client, session, role="driver")
    tenant_id = await _tenant_of(client, headers)
    tariff = await _seed_tariff(session, tenant_id=tenant_id)

    waypoints = await _seed_l_shaped_real_chain_road(session, road_id="TESTM5SWCHAIN2")
    wp0, wp1, wp2, _wp3, wp4 = waypoints

    trip = await _create_trip(client, headers, tariff.id, start_lat=wp0[0], start_lng=wp0[1])
    t0 = datetime.fromisoformat(trip["start_at"])

    # Drive the real corridor, gantry by gantry (2 confirmations -> corroborated).
    await _tick(client, headers, trip["id"], lat=wp0[0], lng=wp0[1], ts=t0 + timedelta(seconds=1))
    await _tick(client, headers, trip["id"], lat=wp1[0], lng=wp1[1], ts=t0 + timedelta(minutes=1))
    body_wp2 = await _tick(client, headers, trip["id"], lat=wp2[0], lng=wp2[1], ts=t0 + timedelta(minutes=2))
    tolls_at_corner = Decimal(body_wp2["tolls"])
    assert tolls_at_corner > 0

    # Now cut straight across the inside of the bend -- the shortcut chord
    # from wp0 to wp4 -- to well past its midpoint, and further still, deep
    # into the gap the real road never occupies. Each of these points is
    # genuinely off the real corridor, so once the first one finalizes the
    # bill it must never move again.
    cut_lat, cut_lng = _corner_cut_point(waypoints)
    beyond_lat = wp4[0] + (wp4[0] - wp0[0]) * 0.1
    beyond_lng = wp4[1] + (wp4[1] - wp0[1]) * 0.1

    body_cut = await _tick(client, headers, trip["id"], lat=cut_lat, lng=cut_lng, ts=t0 + timedelta(minutes=3))
    body_beyond = await _tick(
        client, headers, trip["id"], lat=beyond_lat, lng=beyond_lng, ts=t0 + timedelta(minutes=4)
    )

    assert Decimal(body_cut["tolls"]) == tolls_at_corner, (
        "the bill moved once the vehicle left the real corridor at the corner-cut point -- "
        "the real chain data should have finalized it there"
    )
    assert Decimal(body_beyond["tolls"]) == tolls_at_corner, (
        "the bill kept growing well off the real corridor -- this is exactly the "
        "chord-approximation false-corridor bug the real chain data exists to fix"
    )


# --- regression guard: no real chain data -> still the chord approximation -


async def test_road_with_no_real_chain_data_still_falls_back_to_the_chord_approximation(session: AsyncSession):
    """A road whose gantries carry no `sequence_position` at all (every real
    road except M5SW today, plus any future/synthetic road) must get `None`
    from the real-chain lookup -- the explicit "not covered, use the old
    approximation" signal `apply_toll_detection` branches on -- and the old
    chord approximation must still behave exactly as it always has."""
    base_lat, base_lng = _next_zone()
    road = await _make_road(
        session, road_id="TESTNOCHAIN", pricing_model="distance", directional="both",
        price_class_a=None, cap_class_a="100.00", rate_per_km_class_a="1.00",
    )
    entry = (base_lat, base_lng)
    far = (_north_km(base_lat, 5.0), base_lng)
    for i, (lat, lng) in enumerate([entry, far]):
        # No sequence_position/cumulative_distance_km -- an ordinary gantry.
        await _add_gantry(session, road_id=road.id, gantry_id=f"TESTNOCHAIN:g{i}", lat=lat, lng=lng)

    real_distance = await _distance_to_real_chain_corridor_m(session, road_id="TESTNOCHAIN", lat=entry[0], lng=entry[1])
    assert real_distance is None, "a road with zero real-chain gantries must signal 'not covered', not a false zero"

    # The pre-existing chord approximation still works unchanged: a point
    # roughly on the chord between the two gantries reads as close; the
    # earlier module-level test (test_tolls.py) already exercises the
    # end-to-end billing behaviour for this fallback path in detail, so this
    # test stays scoped to the specific None-signal contract this upgrade
    # introduced.
    midpoint_lat = (entry[0] + far[0]) / 2
    on_chord_distance = await _distance_to_road_corridor_m(session, road_id="TESTNOCHAIN", lat=midpoint_lat, lng=base_lng)
    assert on_chord_distance < 50.0, on_chord_distance
