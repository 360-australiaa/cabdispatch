"""Tests for `app.services.tolls.known_corridor_distance_km` — the
server-side port of Android's `knownCorridorDistanceKm`
(`domain/fare/KnownCorridor.kt`), used by
`app.services.trips.recompute_from_trace` to price a real GPS blackout (a
road tunnel) against the toll registry's own mapped gantry geometry, instead
of either the raw haversine chord between the two fixes (which undercounts a
bent corridor — the exact shape `test_tolls_real_chain` proves for the
gantry-proximity check this shares its geometry style with) or nothing at
all. See `known_corridor_distance_km`'s own doc for the real production trip
(2026-09-10, 43% over the device's own total) this closes, and
`tests.test_trips.test_sync_bills_the_known_corridor_distance_across_a_real_gps_blackout`
for the same behaviour exercised end-to-end through `POST /v1/trips/sync`.
"""

from __future__ import annotations

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.services.tolls import haversine_m, known_corridor_distance_km

from .test_tolls import _add_gantry, _make_road, _next_zone
from .test_tolls_real_chain import _east_km, _north_km

pytestmark = pytest.mark.asyncio


async def _seed_bent_road(
    session: AsyncSession, *, road_id: str
) -> tuple[tuple[float, float], tuple[float, float], tuple[float, float]]:
    """A 3-gantry `distance` road bent like a real tunnel-to-interchange
    corridor: g0->g1 is 2km due north, g1->g2 is 3km due east — the same
    "L" shape `test_tolls_real_chain` uses, so the direct chord from g0 to
    g2 (~3.6km) is meaningfully shorter than the real corridor path (5km)."""
    base_lat, base_lng = _next_zone()
    g0 = (base_lat, base_lng)
    g1 = (_north_km(base_lat, 2.0), base_lng)
    g2 = (g1[0], _east_km(g1[0], base_lng, 3.0))

    await _make_road(
        session,
        road_id=road_id,
        pricing_model="distance",
        cap_class_a="100.00",
        rate_per_km_class_a="1.00",
    )
    for i, (lat, lng) in enumerate([g0, g1, g2]):
        await _add_gantry(session, road_id=road_id, gantry_id=f"{road_id}:g{i}", lat=lat, lng=lng)
    return g0, g1, g2


async def test_matches_a_real_bent_corridor_and_bills_the_real_path_not_the_chord(
    session: AsyncSession,
):
    g0, _g1, g2 = await _seed_bent_road(session, road_id="TESTBLACKOUT1")

    corridor_km = await known_corridor_distance_km(
        session, entry_lat=g0[0], entry_lng=g0[1], exit_lat=g2[0], exit_lng=g2[1]
    )

    assert corridor_km is not None
    chord_km = haversine_m(g0[0], g0[1], g2[0], g2[1]) / 1000.0
    # Real path is g0->g1 (2km) + g1->g2 (3km) = ~5km, well above the ~3.6km chord --
    # proof this bills the real bent corridor, not a shortcut across it.
    assert float(corridor_km) > chord_km + 1.0, (corridor_km, chord_km)
    assert 4.9 < float(corridor_km) < 5.1, corridor_km


async def test_returns_none_when_no_road_is_anywhere_near_either_fix(session: AsyncSession):
    # No toll road seeded anywhere near this zone -- the ordinary
    # "blackout ended somewhere this registry doesn't map" case, where
    # rejection (bill nothing extra), not fabrication, is the contract.
    lat, lng = _next_zone()
    corridor_km = await known_corridor_distance_km(
        session, entry_lat=lat, entry_lng=lng, exit_lat=lat + 0.05, exit_lng=lng
    )
    assert corridor_km is None


async def test_returns_none_when_only_the_entry_fix_matches(session: AsyncSession):
    g0, _g1, g2 = await _seed_bent_road(session, road_id="TESTBLACKOUT2")
    # Exit fix is a real 20km from every gantry of this road -- comfortably
    # outside CORRIDOR_BLACKOUT_PORTAL_RADIUS_M, so the road is not a
    # candidate even though the entry fix alone matched it.
    far_lat = _north_km(g2[0], 20.0)
    corridor_km = await known_corridor_distance_km(
        session, entry_lat=g0[0], entry_lng=g0[1], exit_lat=far_lat, exit_lng=g2[1]
    )
    assert corridor_km is None


async def test_returns_none_when_both_fixes_match_the_very_same_gantry(session: AsyncSession):
    g0, _g1, _g2 = await _seed_bent_road(session, road_id="TESTBLACKOUT3")
    # Both fixes land on g0 alone -- a real momentary drop-and-reacquire near
    # the same spot, not a tunnel transit; no real path exists between one
    # point and itself.
    corridor_km = await known_corridor_distance_km(
        session, entry_lat=g0[0], entry_lng=g0[1], exit_lat=g0[0], exit_lng=g0[1]
    )
    assert corridor_km is None


async def test_prefers_the_shortest_candidate_when_more_than_one_road_matches(
    session: AsyncSession,
):
    """Two different roads both plausibly bracket the same blackout — the
    real, shorter one must win, per `known_corridor_distance_km`'s own
    documented "shortest candidate wins" rule, not whichever the query
    happens to see first."""
    base_lat, base_lng = _next_zone()
    entry = (base_lat, base_lng)
    exit_ = (_north_km(base_lat, 5.0), base_lng)

    # SHORT road: two gantries exactly at entry/exit (~5km apart, direct).
    await _make_road(
        session,
        road_id="TESTBLACKOUTSHORT",
        pricing_model="distance",
        cap_class_a="100.00",
        rate_per_km_class_a="1.00",
    )
    await _add_gantry(
        session, road_id="TESTBLACKOUTSHORT", gantry_id="short:g0", lat=entry[0], lng=entry[1]
    )
    await _add_gantry(
        session, road_id="TESTBLACKOUTSHORT", gantry_id="short:g1", lat=exit_[0], lng=exit_[1]
    )

    # LONG road: also brackets both fixes (within the portal radius at each
    # end) but its own gantry chain detours well out of the way first.
    detour = (_north_km(base_lat, 2.5), _east_km(base_lat, base_lng, 2.0))
    await _make_road(
        session,
        road_id="TESTBLACKOUTLONG",
        pricing_model="distance",
        cap_class_a="100.00",
        rate_per_km_class_a="1.00",
    )
    await _add_gantry(
        session, road_id="TESTBLACKOUTLONG", gantry_id="long:g0", lat=entry[0], lng=entry[1]
    )
    await _add_gantry(
        session, road_id="TESTBLACKOUTLONG", gantry_id="long:g1", lat=detour[0], lng=detour[1]
    )
    await _add_gantry(
        session, road_id="TESTBLACKOUTLONG", gantry_id="long:g2", lat=exit_[0], lng=exit_[1]
    )

    corridor_km = await known_corridor_distance_km(
        session, entry_lat=entry[0], entry_lng=entry[1], exit_lat=exit_[0], exit_lng=exit_[1]
    )

    assert corridor_km is not None
    # The short road's real path (~5km) must win over the long road's
    # detour (~6.4km) -- the shorter, less coincidental match.
    assert 4.8 < float(corridor_km) < 5.5, corridor_km
