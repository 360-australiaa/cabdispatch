"""Seeds the real NSW toll-road registry (`app.models.toll`): every road in
`app/data/nsw_toll_roads.json`, their real per-toll-point breakdowns where
the source data resolves them (`TollPoint`/`TollPointPriceRevision` — M2,
CCT, LCT), plus all 141 real toll gantries from
`app/data/nsw_toll_gantries.csv`.

Idempotent: safe to re-run, and safe to re-run again after a real quarterly
price update lands in `app/data/nsw_toll_roads.json` — every `TollRoad` /
`TollPoint` / `TollGantry` row is looked up by its natural key and updated
in place (not duplicated), while every `TollRoadPriceRevision` /
`TollPointPriceRevision` is looked up by (parent_id, effective_date) and
only INSERTED if that exact date isn't already recorded — so re-running
after editing the JSON's prices in place without changing `effective_date`
will not create a bogus second revision on the same date; if the real toll
operator revises the date too, this correctly inserts an ADDITIONAL
revision beside the old one (see `app.models.toll.TollRoadPriceRevision`'s
docstring for why that's the whole point).

    uv run python scripts/seed_toll_roads.py

Run this AFTER `alembic upgrade head` (needs the tables from migration
a9c1f4e7d2b8 plus the toll_points / per-road-policy columns added by
migration <see alembic/versions for the exact revision id of this pass's
migration>) and independently of `scripts/seed.py` (which seeds
tenants/tariffs/users and, as of a prior pass, no longer seeds any
toll-kind reference geofence — see that script's own note).

--- 2026-09-07 correction pass: what changed here and why -------------------

This pass corrected two real pricing defects and two real modelling defects
inherited from the previous version of this registry (see
`app/data/nsw_toll_roads.json`'s own `notes` for the full list; summarised
here against exactly what this script does differently):

1. Westlink M7's $/km rate now comes from the JSON's own
   `rate_per_km_class_a` (Linkt's published $0.5252/km) rather than being
   derived at seed time from gantry coordinates. `_compute_m7_corridor_km`
   / `TollRoad.derived_corridor_km` are KEPT and still populated below —
   `app.services.tolls.effective_rate_per_km_class_a` only falls back to
   deriving a rate from it when a road's revision has no published rate at
   all, which is no longer true for M7 but could be true for some future
   "distance" road.

2. WestConnex (M4, M8, M5E, M4M8_LINK) are no longer `confidence=
   "not_captured"` placeholders — they're real `distance_with_flagfall`
   roads now, each carrying `network_group="WESTCONNEX"` so
   `app.services.tolls.apply_toll_detection` enforces the shared $12.74
   (Class A) network cap across all of them for one trip.

3. Military Road E-Ramp is no longer its own `TollRoad` row — it's a
   `TollPoint` of Lane Cove Tunnel (see `_ORPHAN_TOLL_POINTS_WITH_NO_GANTRY_
   DATA` below for why it still has zero gantries). Any leftover
   'MILITARY_E_RAMP' `TollRoad` row from a database seeded by a PRE-this-
   pass version of this script is deleted below (`_retire_superseded_roads`)
   — same "match a known specific legacy id and remove it" pattern
   `alembic/versions/a9c1f4e7d2b8_...py` already used for superseded
   reference geofences.

4. The old 'M4M5_ROZELLE' road (id) is gone. It conflated Linkt's own
   PRICED 'M4-M8 Link' segment with Linkt's own UNPRICED 'Rozelle
   Interchange' segment under one id — purely because the source gantry CSV
   groups both under one `motorway_code` ("M4M5_LINK"), not because they
   are the same tolled thing. `app/data/nsw_toll_gantries.csv` has been
   corrected to split that one code into two, BY GANTRY LOCATION (documented
   in `_MOTORWAY_CODE_TO_ROAD_ID` below): "Haberfield"/"St Peters" gantries
   -> "M4M8_LINK" (priced, from Linkt's own segment table); "Anzac
   Bridge"/"Iron Cove Bridge"/"City West Link" gantries -> "ROZELLE_
   INTERCHANGE" (left unpriced — Linkt's own table shows no price for this
   segment). This location-based split is an INTERPRETATION, not a stated
   fact — the source CSV does not itself label which gantry belongs to
   which named segment, but the location names correspond exactly to the
   two segments' own named endpoints on Linkt's page. Any leftover
   'M4M5_ROZELLE' row from a pre-this-pass database is likewise retired by
   `_retire_superseded_roads`.
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
from app.models.toll import (
    TollGantry,
    TollPoint,
    TollPointPriceRevision,
    TollRoad,
    TollRoadPriceRevision,
)
from app.services.tolls import haversine_m

_DATA_DIR = Path(__file__).resolve().parent.parent / "app" / "data"
_ROADS_JSON = _DATA_DIR / "nsw_toll_roads.json"
_GANTRIES_CSV = _DATA_DIR / "nsw_toll_gantries.csv"

# Maps the gantry CSV's own `motorway_code` column to the matching
# `TollRoad.id` (the JSON dataset's own `id` field) they physically belong
# to. Almost all are an identity mapping; three are not, and are called out
# here rather than silently guessed:
#
#   "M8" -> "M8": the CSV's "M8" gantries are explicitly labelled
#   "WestConnex M8 / M5 East" (motorway_name) -- i.e. this one gantry set
#   physically covers BOTH the JSON's separate "M8" and "M5E" toll-road
#   entries, and the source data gives no way to tell which specific gantry
#   belongs to which of the two. Mapped to "M8" only; "M5E" deliberately
#   gets ZERO gantries rather than an invented split -- see this script's
#   `_ORPHAN_ROADS_WITH_NO_GANTRY_DATA` note below. UNCHANGED by this pass
#   -- no new information resolved this ambiguity.
#
#   "M4M8_LINK" -> "M4M8_LINK" / "ROZELLE_INTERCHANGE" -> "ROZELLE_
#   INTERCHANGE": CORRECTED in this pass. The CSV used to tag all 10 of
#   these gantries "M4M5_LINK", mapped to one road "M4M5_ROZELLE" that
#   conflated two different real, separately-named Linkt segments (see this
#   module's docstring, point 4). The CSV itself has been split by gantry
#   location into these two distinct `motorway_code` values -- this
#   crosswalk is now a plain identity mapping for both.
#
#   "M12" -> "M12": NOT one of the roads in nsw_toll_roads.json at all --
#   see `_seed_m12_stub` below for why it still gets a `TollRoad` row.
_MOTORWAY_CODE_TO_ROAD_ID = {
    "M4": "M4",
    "M8": "M8",
    "M4M8_LINK": "M4M8_LINK",
    "ROZELLE_INTERCHANGE": "ROZELLE_INTERCHANGE",
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

# Real, priced roads/points with ZERO matching gantries in the source gantry
# dataset -- left that way deliberately, not papered over with an invented
# coordinate. A road/point with zero gantries can never be GPS-auto-detected
# by app.services.tolls (nothing to match against) -- it stays correctly
# priced and visible on the dashboard, just not auto-chargeable until real
# gantry coordinates are supplied.
_ORPHAN_ROADS_WITH_NO_GANTRY_DATA = ("M5E",)
_ORPHAN_TOLL_POINTS_WITH_NO_GANTRY_DATA = ("LCT:military_e_ramp",)

# `TollRoad` ids seeded by a version of this script PRIOR to the 2026-09-07
# correction pass that no longer exist in `nsw_toll_roads.json` at all --
# see this module's docstring, points 3-4, for why each was retired. Removed
# outright (not just left unreferenced) so a dashboard/API consumer never
# sees a stale road alongside its replacement. This is a one-time cleanup
# of THIS SCRIPT's own prior output, not a live production price -- no
# TollRoadPriceRevision row is being "mutated or deleted" as fare evidence
# (see app.models.toll.TollRoadPriceRevision's append-only docstring): these
# two ids' revisions were never real, in-force prices a passenger could have
# been charged against (MILITARY_E_RAMP's own price is preserved, unchanged,
# as an LCT toll point; M4M5_ROZELLE's "not_captured" revision was never a
# real price at all).
_SUPERSEDED_ROAD_IDS = ("MILITARY_E_RAMP", "M4M5_ROZELLE")


async def _retire_superseded_roads(session) -> None:
    for road_id in _SUPERSEDED_ROAD_IDS:
        result = await session.execute(select(TollRoad).where(TollRoad.id == road_id))
        road = result.scalar_one_or_none()
        if road is not None:
            await session.delete(road)  # cascades to its price revisions/gantries
            print(f"  retired superseded toll_road {road_id!r} (see module docstring)")
    await session.commit()


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


async def _get_or_create_toll_point(session, *, point_id: str, **fields) -> TollPoint:
    result = await session.execute(select(TollPoint).where(TollPoint.id == point_id))
    point = result.scalar_one_or_none()
    if point is None:
        point = TollPoint(id=point_id, **fields)
        session.add(point)
    else:
        for key, value in fields.items():
            setattr(point, key, value)
    await session.commit()
    await session.refresh(point)
    return point


async def _get_or_create_point_revision(session, *, toll_point_id: str, effective_date: date, **fields) -> None:
    result = await session.execute(
        select(TollPointPriceRevision).where(
            TollPointPriceRevision.toll_point_id == toll_point_id,
            TollPointPriceRevision.effective_date == effective_date,
        )
    )
    if result.scalar_one_or_none() is not None:
        return
    session.add(
        TollPointPriceRevision(toll_point_id=toll_point_id, effective_date=effective_date, **fields)
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
            charging_policy=entry.get("charging_policy", "once_per_road"),
            network_group=entry.get("network_group"),
            directional=entry.get("directional"),
            description=entry.get("description"),
        )
        roads_by_id[entry["id"]] = road

        cap = entry.get("cap") or {}
        network_cap = entry.get("network_cap") or {}
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
            rate_per_km_class_a=_decimal_or_none(entry.get("rate_per_km_class_a")),
            rate_per_km_class_b=_decimal_or_none(entry.get("rate_per_km_class_b")),
            flagfall_class_a=_decimal_or_none(entry.get("flagfall_class_a")),
            flagfall_class_b=_decimal_or_none(entry.get("flagfall_class_b")),
            network_cap_class_a=_decimal_or_none(network_cap.get("class_a")),
            network_cap_class_b=_decimal_or_none(network_cap.get("class_b")),
            time_of_day_rates_class_a=entry.get("time_of_day_rates_class_a"),
            currency=roads_data.get("currency", "AUD"),
            gst_included=bool(roads_data.get("gst_included", True)),
            indexation=entry["indexation"],
            confidence=entry["confidence"],
            verify_note=entry.get("verify_note"),
            source_url=entry.get("source_url"),
            retrieved_at=date.fromisoformat(entry["retrieved_at"]) if entry.get("retrieved_at") else None,
        )

        for point_entry in entry.get("toll_points", []):
            point_id = f"{entry['id']}:{point_entry['id']}"
            await _get_or_create_toll_point(
                session,
                point_id=point_id,
                toll_road_id=entry["id"],
                name=point_entry["name"],
                description=point_entry.get("description"),
                source_note=point_entry.get("source_note"),
            )
            await _get_or_create_point_revision(
                session,
                toll_point_id=point_id,
                effective_date=date.fromisoformat(entry["effective_date"]),
                price_class_a=_decimal_or_none(point_entry.get("price_class_a")),
                price_class_b=_decimal_or_none(point_entry.get("price_class_b")),
                currency=roads_data.get("currency", "AUD"),
                gst_included=bool(roads_data.get("gst_included", True)),
                indexation=entry["indexation"],
                confidence=point_entry.get("confidence", entry["confidence"]),
                verify_note=point_entry.get("verify_note"),
                source_url=point_entry.get("source_url", entry.get("source_url")),
                retrieved_at=date.fromisoformat(entry["retrieved_at"]) if entry.get("retrieved_at") else None,
            )

        n_points = len(entry.get("toll_points", []))
        suffix = f", {n_points} toll points" if n_points else ""
        print(
            f"  toll_road {entry['id']!r} ({entry['pricing_model']}, "
            f"policy={entry.get('charging_policy', 'once_per_road')!r}, "
            f"confidence={entry['confidence']!r}{suffix})"
        )
    return roads_by_id


async def _seed_m12_stub(session, roads_by_id: dict[str, TollRoad]) -> None:
    """M12 Motorway is NOT one of the roads in nsw_toll_roads.json -- it has
    4 real gantries in the gantry dataset but no published pricing record
    was captured in this data pass at all (not even a "not_captured"
    placeholder entry the way the WestConnex roads used to have). Rather
    than silently dropping those 4 real gantries on the floor (or worse,
    inventing a price for them), this creates a minimal `TollRoad` stub
    carrying ONLY what the gantry CSV itself states (id, name) so they have
    a real parent row to attach to, `pricing_model="unpriced"`, and an
    explicit `source_note` flagging exactly why -- so a dashboard viewer
    sees "M12 Motorway -- not priced -- no published pricing captured"
    rather than a road that looks just like the others."""
    if "M12" in roads_by_id:
        return
    road = await _get_or_create_road(
        session,
        road_id="M12",
        api_code=None,
        name="M12 Motorway",
        operator=None,
        pricing_model="unpriced",
        charging_policy="once_per_road",
        network_group=None,
        directional=None,
        description=None,
        source_note=(
            "Not one of the roads in the authoritative nsw_toll_roads.json pricing "
            "dataset -- these 4 gantry coordinates come only from the gantry CSV "
            "(app/data/nsw_toll_gantries.csv). No operator, directionality, or price "
            "has been captured for this road in any data pass; left deliberately "
            "unpriced rather than guessed."
        ),
    )
    roads_by_id["M12"] = road
    print("  toll_road 'M12' (stub -- gantry data only, no published pricing)")


def _compute_m7_corridor_km(m7_gantry_rows: list[dict]) -> Decimal:
    """The M7's real physical corridor length, derived from the max pairwise
    haversine distance across all of M7's own real gantry coordinates (not
    an external/assumed figure). KEPT as a fallback derivation only -- see
    this module's docstring point 1 and
    app.services.tolls.effective_rate_per_km_class_a's docstring for how
    it's used only when a road's revision has no published $/km rate."""
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
        print(f"  derived M7 corridor length (fallback only): {roads_by_id['M7'].derived_corridor_km} km (from {len(m7_rows)} real gantries)")

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

        toll_point_id = (row.get("toll_point_id") or "").strip() or None

        result = await session.execute(select(TollGantry).where(TollGantry.id == row["gantry_id"]))
        gantry = result.scalar_one_or_none()
        fields = {
            "toll_road_id": road_id,
            "toll_point_id": toll_point_id,
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
    for point_id in _ORPHAN_TOLL_POINTS_WITH_NO_GANTRY_DATA:
        print(f"  NOTE: toll_point {point_id!r} has 0 gantries in this dataset -- priced but not GPS-auto-detectable yet")


async def seed_toll_roads() -> None:
    with _ROADS_JSON.open(encoding="utf-8") as f:
        roads_data = json.load(f)

    async with AsyncSessionLocal() as session:
        await _retire_superseded_roads(session)
        roads_by_id = await _seed_roads_and_revisions(session, roads_data)
        await _seed_m12_stub(session, roads_by_id)
        await _seed_gantries(session, roads_by_id)

    print(f"Done: {len(roads_by_id)} toll roads seeded.")


if __name__ == "__main__":
    asyncio.run(seed_toll_roads())
