"""Tests for `scripts/seed_toll_roads.py` — the idempotent loader for the
real NSW toll-road registry (13 roads from `app/data/nsw_toll_roads.json` +
141 real gantries from `app/data/nsw_toll_gantries.csv`, plus the M12 stub —
see that script's own docstring for why 14 `TollRoad` rows come out of a
"13 road" dataset).

Updated for the 2026-09-07 price-correction pass, which changed WHICH 13
roads those are without changing the count: 'MILITARY_E_RAMP' stopped being
its own road (it is a `TollPoint` of Lane Cove Tunnel), and the old
'M4M5_ROZELLE' road split into the priced 'M4M8_LINK' and the unpriced
'ROZELLE_INTERCHANGE'. Both retirements are asserted below, since a stale
road left behind alongside its replacement is exactly the failure the
script's `_retire_superseded_roads` step exists to prevent.

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

# The complete real-data road-id set this script produces (13 authoritative
# roads from nsw_toll_roads.json + the M12 gantry-only stub) -- used to scope
# these assertions to real seeded rows only, since `TollRoad`/`TollGantry`
# are shared, un-tenant-scoped tables and other test files (tests/test_tolls.py)
# add their own synthetic rows to the SAME session-scoped test database.
_REAL_ROAD_IDS = {
    "M2", "LCT", "ED", "CCT", "M5SW", "M7", "NORTHCONNEX", "SHB_SHT",
    "M4", "M8", "M5E", "M4M8_LINK", "ROZELLE_INTERCHANGE", "M12",
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
    assert n_roads == 14  # 13 authoritative + the M12 gantry-only stub
    assert n_gantries == 141
    assert set(_MOTORWAY_CODE_TO_ROAD_ID.values()) <= _REAL_ROAD_IDS

    m7 = await session.get(TollRoad, "M7")
    assert m7.pricing_model == "distance"
    assert m7.derived_corridor_km is not None and m7.derived_corridor_km > 0

    m12 = await session.get(TollRoad, "M12")
    assert m12.pricing_model == "unpriced"
    assert m12.source_note  # flagged, not silently invented

    # The one road left with zero gantry data stays a real, priced row --
    # just un-auto-detectable (see _ORPHAN_ROADS_WITH_NO_GANTRY_DATA). Its
    # former companion, MILITARY_E_RAMP, is now an LCT toll point instead
    # and is covered by the per_point assertions below.
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

    assert n_roads == 14
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
