"""Tests for `scripts/seed_toll_roads.py` — the idempotent loader for the
real NSW toll-road registry (15 roads from `app/data/nsw_toll_roads.json`,
all real entries now -- see the 2026-09-09 note below for why the old "13 +
the M12 stub" framing is gone -- plus 141 real gantries from
`app/data/nsw_toll_gantries.csv`).

Updated for the 2026-09-07 price-correction pass, which changed WHICH roads
those are without changing the (then) count: 'MILITARY_E_RAMP' stopped being
its own road (it is a `TollPoint` of Lane Cove Tunnel), and the old
'M4M5_ROZELLE' road split into the priced 'M4M8_LINK' and the unpriced
'ROZELLE_INTERCHANGE'. Both retirements are asserted below, since a stale
road left behind alongside its replacement is exactly the failure the
script's `_retire_superseded_roads` step exists to prevent.

Updated AGAIN for the 2026-09-09 correction pass: M12 Motorway is no longer
a bespoke gantry-only "unpriced" stub -- it is confirmed permanently
toll-free by government policy, and is now a real `nsw_toll_roads.json`
entry with `pricing_model="toll_free"`. The 6 gantries the 2026-09-07 pass
put under "ROZELLE_INTERCHANGE" (Anzac Bridge/Iron Cove Bridge/City West
Link) are, in fact, the toll-free Iron Cove Link, not the genuinely still-
unpriced Rozelle Interchange segment -- they are now their own
`pricing_model="toll_free"` road, "IRON_COVE_LINK"; "ROZELLE_INTERCHANGE"
keeps its own row (still unpriced, still a real data gap) but now correctly
has zero gantries of its own. See `app/data/nsw_toll_roads.json`'s own
`notes` and `scripts/seed_toll_roads.py`'s module docstring for the full
reasoning and cited sources.

Runs the REAL script against the test database (same DATABASE_URL every
other test file uses, via conftest.py) rather than re-deriving expected
counts by hand, so this test breaks the moment the script and the real data
files disagree -- exactly what should happen.
"""
from __future__ import annotations

import pytest
from sqlalchemy import func, select

from app.models.toll import (
    TollGantry,
    TollPoint,
    TollPointPriceRevision,
    TollRoad,
    TollRoadPriceRevision,
)
from app.services.tolls import current_price_revision
from scripts.seed_toll_roads import (
    _MOTORWAY_CODE_TO_ROAD_ID,
    _ORPHAN_ROADS_WITH_NO_GANTRY_DATA,
    seed_toll_roads,
)

pytestmark = pytest.mark.asyncio

# The complete real-data road-id set this script produces -- every road in
# nsw_toll_roads.json, 15 as of the 2026-09-09 pass -- used to scope these
# assertions to real seeded rows only, since `TollRoad`/`TollGantry` are
# shared, un-tenant-scoped tables and other test files (tests/test_tolls.py)
# add their own synthetic rows to the SAME session-scoped test database.
_REAL_ROAD_IDS = {
    "M2", "LCT", "ED", "CCT", "M5SW", "M7", "NORTHCONNEX", "SHB_SHT",
    "M4", "M8", "M5E", "M4M8_LINK", "ROZELLE_INTERCHANGE", "IRON_COVE_LINK", "M12",
}

# Road ids a PRE-2026-09-07 version of this script used to create, which the
# correction pass retired (see `_retire_superseded_roads`). Asserted absent,
# not merely unlisted: leaving one of these behind would show a stale road
# next to its own replacement on the dashboard.
_RETIRED_ROAD_IDS = ("MILITARY_E_RAMP", "M4M5_ROZELLE")


async def test_seed_toll_roads_loads_all_roads_and_gantries(session):
    await seed_toll_roads()

    n_roads = (
        await session.execute(select(func.count()).select_from(TollRoad).where(TollRoad.id.in_(_REAL_ROAD_IDS)))
    ).scalar_one()
    n_gantries = (
        await session.execute(
            select(func.count()).select_from(TollGantry).where(TollGantry.toll_road_id.in_(_REAL_ROAD_IDS))
        )
    ).scalar_one()
    assert n_roads == 15  # every real road in nsw_toll_roads.json, none of them a stub anymore
    assert n_gantries == 141
    assert set(_MOTORWAY_CODE_TO_ROAD_ID.values()) <= _REAL_ROAD_IDS

    m7 = await session.get(TollRoad, "M7")
    assert m7.pricing_model == "distance"
    assert m7.derived_corridor_km is not None and m7.derived_corridor_km > 0

    # M12: permanently toll-free by government policy, not merely unpriced
    # (2026-09-09 correction -- see this module's docstring).
    m12 = await session.get(TollRoad, "M12")
    assert m12.pricing_model == "toll_free"
    assert m12.description  # real, cited description -- not a bare stub anymore
    m12_gantries = (
        await session.execute(select(func.count()).select_from(TollGantry).where(TollGantry.toll_road_id == "M12"))
    ).scalar_one()
    assert m12_gantries == 4

    # Iron Cove Link: the toll-free WestConnex/Rozelle Interchange component
    # that used to be misassigned to "ROZELLE_INTERCHANGE" (2026-09-09
    # correction). Its 6 real gantries (Anzac Bridge, Iron Cove Bridge,
    # City West Link -- entry+exit each) came along with the split.
    icl = await session.get(TollRoad, "IRON_COVE_LINK")
    assert icl.pricing_model == "toll_free"
    icl_gantries = (
        await session.execute(
            select(func.count()).select_from(TollGantry).where(TollGantry.toll_road_id == "IRON_COVE_LINK")
        )
    ).scalar_one()
    assert icl_gantries == 6

    # The genuine Rozelle Interchange tolled segment stays unpriced -- a
    # real, unresolved data gap, unlike M12/Iron Cove Link above -- but now
    # correctly has zero gantries of its own (they were never really its
    # gantries to begin with).
    rozelle = await session.get(TollRoad, "ROZELLE_INTERCHANGE")
    assert rozelle.pricing_model == "unpriced"

    # Roads left with zero gantry data stay real rows -- priced-but-un-auto-
    # detectable for M5E, genuinely-unpriced for ROZELLE_INTERCHANGE (see
    # _ORPHAN_ROADS_WITH_NO_GANTRY_DATA). MILITARY_E_RAMP, the old orphan's
    # former companion, is now an LCT toll point instead and is covered by
    # the per_point assertions below.
    for orphan_id in _ORPHAN_ROADS_WITH_NO_GANTRY_DATA:
        count = (
            await session.execute(
                select(func.count()).select_from(TollGantry).where(TollGantry.toll_road_id == orphan_id)
            )
        ).scalar_one()
        assert count == 0
        road = await session.get(TollRoad, orphan_id)
        assert road is not None


async def test_seed_toll_roads_is_idempotent(session):
    await seed_toll_roads()
    await seed_toll_roads()

    n_roads = (
        await session.execute(select(func.count()).select_from(TollRoad).where(TollRoad.id.in_(_REAL_ROAD_IDS)))
    ).scalar_one()
    n_gantries = (
        await session.execute(
            select(func.count()).select_from(TollGantry).where(TollGantry.toll_road_id.in_(_REAL_ROAD_IDS))
        )
    ).scalar_one()
    n_revisions_m7 = (
        await session.execute(
            select(func.count()).select_from(TollRoadPriceRevision).where(TollRoadPriceRevision.toll_road_id == "M7")
        )
    ).scalar_one()

    assert n_roads == 15
    assert n_gantries == 141
    assert n_revisions_m7 == 1  # re-running with the same effective_date never duplicates


async def test_seed_toll_roads_retires_superseded_road_ids(session):
    """The correction pass re-modelled two roads out of existence. A database
    seeded by the previous version of this script still has their rows, so
    the script deletes them -- otherwise the dashboard would list, say, both
    'M4M5_ROZELLE' and the two real segments that replaced it."""
    await seed_toll_roads()

    for retired_id in _RETIRED_ROAD_IDS:
        assert await session.get(TollRoad, retired_id) is None


async def test_seed_toll_roads_seeds_per_point_prices_and_charging_policy(session):
    """Hills M2 is the one road that charges ADDITIVELY per toll point, so
    its 6 real Linkt-published point prices must land as `TollPoint` rows
    with their own revisions -- the road-level min/max is a display summary
    and is never what a trip is charged (see app/data/nsw_toll_roads.json)."""
    await seed_toll_roads()

    m2 = await session.get(TollRoad, "M2")
    assert m2.pricing_model == "per_point"
    assert m2.charging_policy == "cumulative_per_point"

    point_ids = set(
        (
            await session.execute(select(TollPoint.id).where(TollPoint.toll_road_id == "M2"))
        )
        .scalars()
        .all()
    )
    assert len(point_ids) == 6
    assert "M2:north_ryde" in point_ids

    # Every seeded point is really priced -- a point with no revision would
    # silently never charge (see app.services.tolls's "unpriced" handling).
    for point_id in point_ids:
        n_revisions = (
            await session.execute(
                select(func.count())
                .select_from(TollPointPriceRevision)
                .where(TollPointPriceRevision.toll_point_id == point_id)
            )
        ).scalar_one()
        assert n_revisions >= 1, point_id

    # Lane Cove Tunnel has real per-point prices too, but is deliberately
    # NOT cumulative -- its two points are a main tunnel and an alternate
    # ramp a vehicle uses one of. See app.models.toll's docstring: this is a
    # flagged interpretation call, and the test pins the chosen reading.
    lct = await session.get(TollRoad, "LCT")
    assert lct.pricing_model == "per_point"
    assert lct.charging_policy == "once_per_road"


async def test_seed_toll_roads_prices_westconnex_with_a_shared_network_cap(session):
    """WestConnex was previously seeded `not_captured` with NULL prices, so it
    never charged at all. It is now priced from Linkt's published flagfall +
    per-km formula, and its stages share one network-wide cap for a trip."""
    await seed_toll_roads()

    for road_id in ("M4", "M8", "M5E", "M4M8_LINK"):
        road = await session.get(TollRoad, road_id)
        assert road is not None, road_id
        assert road.network_group == "WESTCONNEX", road_id

    revision = await current_price_revision(session, toll_road_id="M4")
    assert revision is not None
    assert revision.flagfall_class_a is not None
    assert revision.rate_per_km_class_a is not None
    assert revision.network_cap_class_a is not None
    # Every corrected figure is citable -- see TollRoadPriceRevision.source_url.
    assert revision.source_url


async def test_seed_toll_roads_uses_published_m7_rate_not_the_derived_one(session):
    """The defect this pass fixed: M7 charged a $/km rate derived from its own
    cap (~0.44) instead of Linkt's published 0.5252. The derivation is kept
    only as a fallback, so both must be present and they must differ."""
    await seed_toll_roads()

    m7 = await session.get(TollRoad, "M7")
    revision = await current_price_revision(session, toll_road_id="M7")
    assert revision.rate_per_km_class_a is not None
    assert float(revision.rate_per_km_class_a) == pytest.approx(0.5252)
    assert m7.derived_corridor_km is not None and m7.derived_corridor_km > 0


async def test_seed_toll_roads_uses_a_distance_with_flagfall_formula_for_m4m8_link_not_a_flat_toll(session):
    """2026-09-09 cross-check correction: M4-M8 Link was modelled `flat` at a
    single $6.48 figure, but a SECOND independent source (the official
    CartoDB toll-calculator table AND Linkt's own real origin-destination
    trip table) both show it is priced by the same flagfall+per-km formula
    as M4/M8/M5E -- the flat figure only ever matched Linkt's own number for
    the link's FULL length end to end, and silently overcharged every
    shorter partial trip through it. See app/data/nsw_toll_roads.json's
    M4M8_LINK entry for the full evidence."""
    await seed_toll_roads()

    road = await session.get(TollRoad, "M4M8_LINK")
    assert road.pricing_model == "distance_with_flagfall"
    assert road.charging_policy == "distance_metered"

    revision = await current_price_revision(session, toll_road_id="M4M8_LINK")
    assert revision is not None
    assert float(revision.rate_per_km_class_a) == pytest.approx(0.6667)
    assert float(revision.flagfall_class_a) == pytest.approx(1.80)
    # This road's own cap is Linkt's full-length segment price -- the same
    # figure the old (wrong) flat model charged unconditionally.
    assert float(revision.cap_class_a) == pytest.approx(6.48)
    assert float(revision.network_cap_class_a) == pytest.approx(12.74)


async def test_seed_toll_roads_gives_m5sw_real_chain_sequence_and_cumulative_distance(session):
    """2026-09-09 cross-check upgrade: M5SW is the one real road whose
    source chain data (official CartoDB `tollpoints_data`) resolves to a
    single, consistent, non-cyclic sequence -- see
    `scripts/seed_toll_roads.py`'s `_REAL_ROAD_CHAIN_WAYPOINTS` for exactly
    why every other real road does not. Its 5 named toll points (10
    gantries, one pair per direction) must carry the REAL cumulative
    distances from the source chain, not a chord-approximation guess."""
    await seed_toll_roads()

    gantries = (
        await session.execute(select(TollGantry).where(TollGantry.toll_road_id == "M5SW"))
    ).scalars().all()
    assert len(gantries) == 10

    by_cumulative = {float(g.cumulative_distance_km): g.sequence_position for g in gantries if g.cumulative_distance_km is not None}
    assert by_cumulative == {0.0: 0, 1.8: 1, 3.4: 2, 6.9: 3, 10.3: 4}

    # Every gantry actually has one -- this is a fully-resolved real chain,
    # not a partial one.
    assert all(g.sequence_position is not None for g in gantries)
    assert all(g.cumulative_distance_km is not None for g in gantries)

    # A road NOT in `_REAL_ROAD_CHAIN_WAYPOINTS` (e.g. M7, whose real source
    # chain genuinely branches -- see that dict's own docstring) must stay
    # NULL, i.e. still on the pre-existing chord approximation.
    m7_gantries = (
        await session.execute(select(TollGantry).where(TollGantry.toll_road_id == "M7"))
    ).scalars().all()
    assert m7_gantries  # sanity: M7 really does have real gantries
    assert all(g.sequence_position is None for g in m7_gantries)
    assert all(g.cumulative_distance_km is None for g in m7_gantries)


async def test_seed_toll_roads_real_chain_backfill_is_idempotent(session):
    """Re-running the loader (the same idempotent mechanism that populates
    every other real fact on TollGantry) must reproduce the exact same
    sequence_position/cumulative_distance_km values, not duplicate rows or
    drift on a second pass."""
    await seed_toll_roads()
    first_pass = {
        g.id: (g.sequence_position, g.cumulative_distance_km)
        for g in (await session.execute(select(TollGantry).where(TollGantry.toll_road_id == "M5SW"))).scalars().all()
    }

    await seed_toll_roads()
    second_pass = {
        g.id: (g.sequence_position, g.cumulative_distance_km)
        for g in (await session.execute(select(TollGantry).where(TollGantry.toll_road_id == "M5SW"))).scalars().all()
    }

    assert first_pass == second_pass
    assert len(second_pass) == 10  # no duplicate rows either
