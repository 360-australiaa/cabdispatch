"""Tests for `scripts/seed_toll_roads.py` — the idempotent loader for the
real NSW toll-road registry (13 roads from `app/data/nsw_toll_roads.json` +
141 real gantries from `app/data/nsw_toll_gantries.csv`, plus the M12 stub —
see that script's own docstring for why 14 `TollRoad` rows come out of a
"13 road" dataset).

Runs the REAL script against the test database (same DATABASE_URL every
other test file uses, via conftest.py) rather than re-deriving expected
counts by hand, so this test breaks the moment the script and the real data
files disagree -- exactly what should happen.
"""
from __future__ import annotations

import pytest
from sqlalchemy import func, select

from app.models.toll import TollGantry, TollRoad, TollRoadPriceRevision
from scripts.seed_toll_roads import _MOTORWAY_CODE_TO_ROAD_ID, seed_toll_roads

pytestmark = pytest.mark.asyncio

# The complete real-data road-id set this script produces (13 authoritative
# roads from nsw_toll_roads.json + the M12 gantry-only stub) -- used to scope
# these assertions to real seeded rows only, since `TollRoad`/`TollGantry`
# are shared, un-tenant-scoped tables and other test files (tests/test_tolls.py)
# add their own synthetic rows to the SAME session-scoped test database.
_REAL_ROAD_IDS = {
    "M2", "LCT", "MILITARY_E_RAMP", "ED", "CCT", "M5SW", "M7", "NORTHCONNEX",
    "SHB_SHT", "M4", "M8", "M5E", "M4M5_ROZELLE", "M12",
}


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

    # The two roads with zero gantry data stay real, priced rows -- just
    # un-auto-detectable (see the script's _ORPHAN_ROADS_WITH_NO_GANTRY_DATA).
    for orphan_id in ("M5E", "MILITARY_E_RAMP"):
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
