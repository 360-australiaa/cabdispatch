"""Seeds the real NSW toll-road registry (`app.models.toll`): all 13 roads
from the product owner's authoritative dataset plus all 141 real toll
gantries, loaded from `app/data/nsw_toll_roads.json` /
`app/data/nsw_toll_gantries.csv` (verbatim copies of the supplied source
files — see those files' own top-level `sources` field / this script's
crosswalk comments below for provenance).

Idempotent: safe to re-run, and safe to re-run again after a real quarterly
price update lands in `app/data/nsw_toll_roads.json` — every `TollRoad` /
`TollGantry` row is looked up by its natural key and updated in place (not
duplicated), while every `TollRoadPriceRevision` is looked up by
(toll_road_id, effective_date) and only INSERTED if that exact date isn't
already recorded — so re-running after editing the JSON's prices in place
without changing `effective_date` will not create a bogus second revision on
the same date; if the real toll operator revises the date too, this
correctly inserts an ADDITIONAL revision beside the old one (see
`app.models.toll.TollRoadPriceRevision`'s docstring for why that's the
whole point).

    uv run python scripts/seed_toll_roads.py

Run this AFTER `alembic upgrade head` (needs the tables from migration
a9c1f4e7d2b8) and independently of `scripts/seed.py` (which seeds
tenants/tariffs/users and, as of this same pass, no longer seeds any
toll-kind reference geofence — see that script's own note).
"""
from __future__ import annotations

import asyncio
import csv
import json
from datetime import date
from decimal import Decimal
from itertools import combinations
from pathlib import Path

from sqlalchemy import select

import app.models  # noqa: F401 -- populate Base.metadata before any query runs
from app.core.database import AsyncSessionLocal
from app.models.toll import TollGantry, TollRoad, TollRoadPriceRevision
from app.services.tolls import haversine_m

_DATA_DIR = Path(__file__).resolve().parent.parent / "app" / "data"
_ROADS_JSON = _DATA_DIR / "nsw_toll_roads.json"
_GANTRIES_CSV = _DATA_DIR / "nsw_toll_gantries.csv"

# Maps the gantry CSV's own `motorway_code` column to the matching
# `TollRoad.id` (the JSON dataset's own `id` field) they physically belong
# to. Almost all are an identity mapping; two are not, and are called out
# here rather than silently guessed:
#
#   "M4M5_LINK" -> "M4M5_ROZELLE": the same interchange under two different
#   short codes across the two source files (CSV: "M4-M5 Link / Rozelle
#   Interchange" / JSON: "M4-M5 Link / Rozelle Interchange") -- same road,
#   different code string, not two different roads.
#
#   "M8" -> "M8": the CSV's "M8" gantries are explicitly labelled
#   "WestConnex M8 / M5 East" (motorway_name) -- i.e. this one gantry set
#   physically covers BOTH the JSON's separate "M8" and "M5E" toll-road
#   entries, and the source data gives no way to tell which specific gantry
#   belongs to which of the two. Mapped to "M8" only; "M5E" deliberately
#   gets ZERO gantries rather than an invented split -- see this script's
#   `_ORPHAN_ROADS_WITH_NO_GANTRY_DATA` note below.
#
#   "M12" -> "M12": NOT one of the 13 roads in nsw_toll_roads.json at all --
#   see `_seed_m12_stub` below for why it still gets a `TollRoad` row.
_MOTORWAY_CODE_TO_ROAD_ID = {
    "M4": "M4",
    "M8": "M8",
    "M4M5_LINK": "M4M5_ROZELLE",
    "M2": "M2",
    "M5SW": "M5SW",
    "ED": "ED",
    "CCT": "CCT",
    "LCT": "LCT",
    "NORTHCONNEX": "NORTHCONNEX",
    "SHB_SHT": "SHB_SHT",
    "M7": "M7",
    "M12": "M12",
}

# Two of the 13 real, priced roads have ZERO matching gantries in the source
# gantry dataset -- left that way deliberately (see module docstring on
# _MOTORWAY_CODE_TO_ROAD_ID above), not papered over with an invented
# coordinate:
#   - "M5E" (M5 East): its physical tolling points are part of the combined
#     M8/M5E interchange the CSV only tags "M8" -- no separate M5E gantry
#     data exists to seed.
#   - "MILITARY_E_RAMP" (Military Road E-Ramp): a real, separately-priced
#     ramp toll under the Lane Cove Tunnel concession, but the gantry CSV's
#     6 "LCT" rows are the tunnel's own mainline gantries, not this ramp's.
# A road with zero gantries can never be GPS-auto-detected by
# app.services.tolls (nothing to match against) -- it stays correctly priced
# and visible on the dashboard, just not auto-chargeable until real gantry
# coordinates for it are supplied.
_ORPHAN_ROADS_WITH_NO_GANTRY_DATA = ("M5E", "MILITARY_E_RAMP")


async def _get_or_create_road(session, *, road_id: str, **fields) -> TollRoad:
    result = await session.execute(select(TollRoad).where(TollRoad.id == road_id))
    road = result.scalar_one_or_none()
    if road is None:
        road = TollRoad(id=road_id, **fields)
        session.add(road)
    else:
        for key, value in fields.items():
            setattr(road, key, value)
    await session.commit()
    await session.refresh(road)
    return road


async def _get_or_create_revision(session, *, toll_road_id: str, effective_date: date, **fields) -> None:
    result = await session.execute(
        select(TollRoadPriceRevision).where(
            TollRoadPriceRevision.toll_road_id == toll_road_id,
            TollRoadPriceRevision.effective_date == effective_date,
        )
    )
    existing = result.scalar_one_or_none()
    if existing is not None:
        return  # this exact dated revision is already recorded -- see module doc
    session.add(
        TollRoadPriceRevision(toll_road_id=toll_road_id, effective_date=effective_date, **fields)
    )
    await session.commit()


def _decimal_or_none(value) -> Decimal | None:
    return None if value is None else Decimal(str(value))


async def _seed_roads_and_revisions(session, roads_data: dict) -> dict[str, TollRoad]:
    roads_by_id: dict[str, TollRoad] = {}
    for entry in roads_data["toll_roads"]:
        road = await _get_or_create_road(
            session,
            road_id=entry["id"],
            api_code=entry.get("api_code"),
            name=entry["name"],
            operator=entry.get("operator"),
            pricing_model=entry["pricing_model"],
            directional=entry.get("directional"),
            description=entry.get("description"),
        )
        roads_by_id[entry["id"]] = road

        cap = entry.get("cap") or {}
        await _get_or_create_revision(
            session,
            toll_road_id=entry["id"],
            effective_date=date.fromisoformat(entry["effective_date"]),
            price_class_a_min=_decimal_or_none(entry.get("price_class_a_min")),
            price_class_a_max=_decimal_or_none(entry.get("price_class_a_max")),
            price_class_b_min=_decimal_or_none(entry.get("price_class_b_min")),
            price_class_b_max=_decimal_or_none(entry.get("price_class_b_max")),
            cap_class_a=_decimal_or_none(cap.get("class_a")),
            cap_class_b=_decimal_or_none(cap.get("class_b")),
            time_of_day_rates_class_a=entry.get("time_of_day_rates_class_a"),
            currency=roads_data.get("currency", "AUD"),
            gst_included=bool(roads_data.get("gst_included", True)),
            indexation=entry["indexation"],
            confidence=entry["confidence"],
            verify_note=entry.get("verify_note"),
        )
        print(f"  toll_road {entry['id']!r} ({entry['pricing_model']}, confidence={entry['confidence']!r})")
    return roads_by_id


async def _seed_m12_stub(session, roads_by_id: dict[str, TollRoad]) -> None:
    """M12 Motorway is NOT one of the 13 roads in nsw_toll_roads.json -- it
    has 4 real gantries in the gantry dataset but no published pricing
    record was captured in this data pass at all (not even a "not_captured"
    placeholder entry the way the four WestConnex roads have). Rather than
    silently dropping those 4 real gantries on the floor (or worse, inventing
    a price for them), this creates a minimal `TollRoad` stub carrying ONLY
    what the gantry CSV itself states (id, name) so they have a real parent
    row to attach to, `pricing_model="unpriced"`, and an explicit
    `source_note` flagging exactly why -- so a dashboard viewer sees "M12
    Motorway -- not priced -- no published pricing captured" rather than a
    road that looks just like the other 12."""
    if "M12" in roads_by_id:
        return
    road = await _get_or_create_road(
        session,
        road_id="M12",
        api_code=None,
        name="M12 Motorway",
        operator=None,
        pricing_model="unpriced",
        directional=None,
        description=None,
        source_note=(
            "Not one of the 13 roads in the authoritative nsw_toll_roads.json pricing "
            "dataset -- these 4 gantry coordinates come only from the gantry CSV "
            "(app/data/nsw_toll_gantries.csv). No operator, directionality, or price "
            "has been captured for this road in this data pass; left deliberately "
            "unpriced rather than guessed."
        ),
    )
    roads_by_id["M12"] = road
    print("  toll_road 'M12' (stub -- gantry data only, no published pricing)")


def _compute_m7_corridor_km(m7_gantry_rows: list[dict]) -> Decimal:
    """The M7's real physical corridor length, derived from the max pairwise
    haversine distance across all of M7's own real gantry coordinates (not
    an external/assumed figure) -- see app.services.tolls.
    distance_rate_per_km_class_a's docstring for how this is then used to
    back out an effective $/km rate from the published Class A cap."""
    coords = [(float(r["latitude"]), float(r["longitude"])) for r in m7_gantry_rows]
    max_km = 0.0
    for (lat1, lng1), (lat2, lng2) in combinations(coords, 2):
        max_km = max(max_km, haversine_m(lat1, lng1, lat2, lng2) / 1000.0)
    return Decimal(str(round(max_km, 3)))


async def _seed_gantries(session, roads_by_id: dict[str, TollRoad]) -> None:
    with _GANTRIES_CSV.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    m7_rows = [r for r in rows if r["motorway_code"] == "M7"]
    if m7_rows and "M7" in roads_by_id:
        roads_by_id["M7"].derived_corridor_km = _compute_m7_corridor_km(m7_rows)
        await session.commit()
        print(f"  derived M7 corridor length: {roads_by_id['M7'].derived_corridor_km} km (from {len(m7_rows)} real gantries)")

    created = 0
    updated = 0
    for row in rows:
        road_id = _MOTORWAY_CODE_TO_ROAD_ID.get(row["motorway_code"])
        if road_id is None or road_id not in roads_by_id:
            raise ValueError(
                f"gantry {row['gantry_id']!r} has motorway_code={row['motorway_code']!r}, "
                "which has no crosswalk entry to a seeded TollRoad -- real data must never "
                "be silently dropped; update _MOTORWAY_CODE_TO_ROAD_ID"
            )

        result = await session.execute(select(TollGantry).where(TollGantry.id == row["gantry_id"]))
        gantry = result.scalar_one_or_none()
        fields = {
            "toll_road_id": road_id,
            "location": row["location"],
            "ramp": row["ramp"] or None,
            "direction": row["direction"] or None,
            "latitude": float(row["latitude"]),
            "longitude": float(row["longitude"]),
            "source_sheet": row["source_sheet"] or None,
        }
        if gantry is None:
            session.add(TollGantry(id=row["gantry_id"], **fields))
            created += 1
        else:
            for key, value in fields.items():
                setattr(gantry, key, value)
            updated += 1

    await session.commit()
    print(f"  gantries: {created} created, {updated} updated (of {len(rows)} total real gantries)")

    for road_id in _ORPHAN_ROADS_WITH_NO_GANTRY_DATA:
        print(f"  NOTE: toll_road {road_id!r} has 0 gantries in this dataset -- priced but not GPS-auto-detectable yet")


async def seed_toll_roads() -> None:
    with _ROADS_JSON.open(encoding="utf-8") as f:
        roads_data = json.load(f)

    async with AsyncSessionLocal() as session:
        roads_by_id = await _seed_roads_and_revisions(session, roads_data)
        await _seed_m12_stub(session, roads_by_id)
        await _seed_gantries(session, roads_by_id)

    print(f"Done: {len(roads_by_id)} toll roads seeded.")


if __name__ == "__main__":
    asyncio.run(seed_toll_roads())
