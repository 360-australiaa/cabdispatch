"""Copy Linkt's Sydney toll prices into this registry -- `app/data/linkt_nsw_pricing.json`.

Why (owner decision, 2026-09-15): "copy all pricing from Linkt to our system, Linkt pricing is
accurate". The formula-per-road model this registry grew up with (flagfall + per-km + caps,
per-point sums, time bands) could not reproduce what Linkt actually bills a through trip:
Anzac Bridge -> Homebush Bay Drive is ONE WestConnex trip at $8.80 (one flagfall shared across the
Rozelle Interchange and the M4 East), where the per-road formulas gave $5.82 (Rozelle unpriced) or
$10.60 (two flagfalls). Linkt's own calculator prices every trip as an ENTRY POINT -> EXIT POINT
pair, so that is the model copied here: `pricing_model = "entry_exit"` roads whose points are
Linkt's entry/exit locations and whose prices are Linkt's own per-pair figures, verbatim.

Source: the public calculator at https://www.linkt.com.au/using-toll-roads/toll-calculator/sydney
is a thin client over `https://tollcalc.transurban.com` (`/v2/assets/NSW`, `/v1/exits/{asset}/
{entry}`, `/v4/prices/{entry asset}/{entry}/{exit asset}/{exit}`), authenticated with the API key
the page itself embeds for every visitor (`tuDigitalApiKey` in the calculator's config JSON). This
script asks the same endpoints the page does, at the same rate a person clicking would, and keeps:

  - every asset (road) and its entry points, mapped to this registry's road ids;
  - every DIRECT pair (entry and exit billed on the same asset -- WestConnex's M4 / M8 / M5 East /
    Rozelle Interchange sub-assets all bill as asset "140", so Anzac Bridge -> Homebush is direct)
    with its Class A and Class B "Tag" base price per time band (`day` is "all" / "weekdays" /
    "weekend", `interval` is "HHMM-HHMM" and may wrap midnight). The tag price IS the toll; tagless
    and pass products add Linkt's video-matching fee on top, which is not a road toll;
  - each entry/exit point's coordinate: the start (entry) / finish (exit) of the route polyline
    Linkt draws for the pair, majority-voted across that point's direct pairs.

Cross-network pairs (M2 entry -> M7 exit, and the round-Sydney detours the router builds for an
entry/exit combination that is really the wrong direction) are NOT imported: the meter bills each
network as its own section (exit one, enter the next), and the detours would be nonsense prices.

Re-run whenever Linkt changes prices (quarterly CPI for Transurban roads, 1 January for WestConnex,
government-announced for the Harbour crossings):

    backend/.venv/Scripts/python backend/scripts/fetch_linkt_prices.py            # fetch + build
    backend/.venv/Scripts/python backend/scripts/fetch_linkt_prices.py --dump x   # rebuild from a saved raw dump
    backend/.venv/Scripts/python backend/scripts/fetch_linkt_prices.py --save-dump raw.json

then `seed_toll_roads.py` (which applies this file last -- see its `_apply_linkt_pricing`).
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

BASE = "https://tollcalc.transurban.com"
# The public key the Linkt toll-calculator page embeds for every visitor (its `tuDigitalApiKey`).
PUBLIC_API_KEY = "10ZJMQx1zla8y2Qut3iB85JthK8YHno5iEu8rbmr"
OUT = Path(__file__).resolve().parent.parent / "app" / "data" / "linkt_nsw_pricing.json"

# Linkt asset id -> this registry's TollRoad.id. Every road here becomes `pricing_model =
# "entry_exit"` when seeded. "other" (Connecting roads) has no entries and is skipped.
ASSET_TO_ROAD_ID = {
    "112": "M2",
    "113": "NORTHCONNEX",
    "109": "LCT",
    "100": "SHB_SHT",
    "108": "CCT",
    "101": "ED",
    "140-m5e": "M5E",
    "140-m8": "M8",
    "105": "M5SW",
    "107": "M7",
    "140-m4": "M4",
    "140-m4m5": "ROZELLE_INTERCHANGE",
}


def billing_asset(asset_id: str) -> str:
    """WestConnex's sub-assets (140-m4, 140-m4m5, 140-m8, 140-m5e) bill as one asset, "140"."""
    return asset_id.split("-")[0]


def get(path: str, tries: int = 4):
    for attempt in range(tries):
        try:
            req = urllib.request.Request(
                BASE + path,
                headers={
                    "X-API-Key": PUBLIC_API_KEY,
                    "Origin": "https://www.linkt.com.au",
                    "Accept": "application/json",
                    "User-Agent": "Mozilla/5.0 (CabDispatch toll registry sync)",
                },
            )
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (400, 404):
                return {"_error": e.code}
            time.sleep(2 * (attempt + 1))
        except (urllib.error.URLError, TimeoutError, OSError):
            time.sleep(2 * (attempt + 1))
    return {"_error": "gave up"}


def fetch_raw(progress=print) -> dict:
    assets = get("/v2/assets/NSW")
    raw = {"source": BASE, "fetched": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "assets": assets, "exits": {}, "prices": {}}
    n = 0
    for a in assets:
        for e in a["entries"]:
            key = f'{a["id"]}/{e["id"]}'
            ex = get(f"/v1/exits/{a['id']}/{e['id']}")
            raw["exits"][key] = ex
            if isinstance(ex, dict):
                continue
            for xa in ex:
                for x in xa["exits"]:
                    raw["prices"][f'{key}/{xa["id"]}/{x["id"]}'] = get(f"/v4/prices/{a['id']}/{e['id']}/{xa['id']}/{x['id']}")
                    n += 1
                    if n % 100 == 0:
                        progress(f"  {n} pairs fetched")
    progress(f"  {n} pairs fetched")
    return raw


def _tag_breakdown(price_entry: list, vehicle_class: str) -> list[dict]:
    for cl in price_entry[0]["costByClass"]:
        if cl["class"] != vehicle_class:
            continue
        for prod in cl["costByProduct"]:
            if prod["id"] == "tag":
                return prod["breakdown"]
    return []


def _bands(costs: list[dict]) -> list[dict]:
    return [
        {"day": c["day"], "interval": c["interval"], "price": round(float(c["cost"]["base"]), 2)}
        for c in costs
    ]


def build(raw: dict) -> dict:
    assets_by_id = {a["id"]: a for a in raw["assets"]}
    entry_names = {f'{a["id"]}/{e["id"]}': e["name"] for a in raw["assets"] for e in a["entries"]}
    exit_names: dict[str, str] = {}
    for ex_list in raw["exits"].values():
        if isinstance(ex_list, list):
            for xa in ex_list:
                for x in xa["exits"]:
                    exit_names[f'{xa["id"]}/{x["id"]}'] = x["name"]

    pairs: list[dict] = []
    entry_coords: dict[str, Counter] = defaultdict(Counter)
    exit_coords: dict[str, Counter] = defaultdict(Counter)
    skipped_indirect = skipped_error = skipped_unpriced = 0
    for key, p in raw["prices"].items():
        if not isinstance(p, list) or not p:
            skipped_error += 1
            continue
        ea, e, xa, x = key.split("/")
        if ea not in ASSET_TO_ROAD_ID or xa not in ASSET_TO_ROAD_ID:
            continue
        if billing_asset(ea) != billing_asset(xa):
            skipped_indirect += 1
            continue
        bill = billing_asset(ea)
        a_items = _tag_breakdown(p, "classA")
        b_items = _tag_breakdown(p, "classB")
        # Direct = nothing else on the route carries a charge (the router's round-Sydney detours
        # for a wrong-direction combination list other assets with real costs -- not our trip).
        if any(item["costs"] and billing_asset(item["id"]) != bill for item in a_items):
            skipped_indirect += 1
            continue
        own_a = [item for item in a_items if billing_asset(item["id"]) == bill]
        own_b = [item for item in b_items if billing_asset(item["id"]) == bill]
        bands_a = _bands(own_a[0]["costs"]) if own_a else []
        bands_b = _bands(own_b[0]["costs"]) if own_b else []
        if not bands_a:
            skipped_unpriced += 1  # e.g. Harbour Bridge northbound: genuinely free in that direction
            bands_a = [{"day": "all", "interval": "0000-2400", "price": 0.0}]
            bands_b = bands_b or [{"day": "all", "interval": "0000-2400", "price": 0.0}]
        polylines = p[0].get("polylines") or []
        if polylines and polylines[0]:
            s = polylines[0][0]["start"]
            f = polylines[0][-1]["finish"]
            entry_coords[f"{ea}/{e}"][(round(s["lat"], 6), round(s["lng"], 6))] += 1
            exit_coords[f"{xa}/{x}"][(round(f["lat"], 6), round(f["lng"], 6))] += 1
        pairs.append(
            {
                "entry": f"LINKT:{ea}/{e}:entry",
                "exit": f"LINKT:{xa}/{x}:exit",
                "billing_asset_id": bill,
                "billing_name": own_a[0]["name"] if own_a else assets_by_id[ea]["name"],
                "class_a": bands_a,
                "class_b": bands_b,
            }
        )

    points: list[dict] = []
    for pk, counter in entry_coords.items():
        (lat, lng), _ = counter.most_common(1)[0]
        asset_id, point_id = pk.split("/")
        points.append(
            {
                "id": f"LINKT:{pk}:entry",
                "asset_id": asset_id,
                "road_id": ASSET_TO_ROAD_ID[asset_id],
                "point_id": point_id,
                "name": entry_names.get(pk, point_id),
                "role": "entry",
                "latitude": lat,
                "longitude": lng,
            }
        )
    for pk, counter in exit_coords.items():
        (lat, lng), _ = counter.most_common(1)[0]
        asset_id, point_id = pk.split("/")
        points.append(
            {
                "id": f"LINKT:{pk}:exit",
                "asset_id": asset_id,
                "road_id": ASSET_TO_ROAD_ID[asset_id],
                "point_id": point_id,
                "name": exit_names.get(pk, point_id),
                "role": "exit",
                "latitude": lat,
                "longitude": lng,
            }
        )
    known = {pt["id"] for pt in points}
    pairs = [pr for pr in pairs if pr["entry"] in known and pr["exit"] in known]
    pairs.sort(key=lambda pr: (pr["entry"], pr["exit"]))
    points.sort(key=lambda pt: pt["id"])
    return {
        "$schema_version": 1,
        "source": "Linkt Sydney toll calculator (tollcalc.transurban.com), Tag product base price",
        "source_url": "https://www.linkt.com.au/using-toll-roads/toll-calculator/sydney",
        "fetched_at": raw["fetched"],
        "vehicle_classes": {"class_a": "cars, taxis, motorcycles, light commercial", "class_b": "heavy vehicles"},
        "assets": [
            {"id": a["id"], "name": a["name"], "road_id": ASSET_TO_ROAD_ID[a["id"]], "billing_asset_id": billing_asset(a["id"])}
            for a in raw["assets"]
            if a["id"] in ASSET_TO_ROAD_ID
        ],
        "points": points,
        "pairs": pairs,
        "stats": {
            "pairs_fetched": len(raw["prices"]),
            "pairs_direct": len(pairs),
            "pairs_indirect_skipped": skipped_indirect,
            "pairs_errored": skipped_error,
            "pairs_free_direction": skipped_unpriced,
        },
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dump", help="rebuild from a saved raw dump instead of fetching")
    ap.add_argument("--save-dump", help="also save the raw fetched responses here")
    ap.add_argument("--out", default=str(OUT))
    args = ap.parse_args(argv)
    if args.dump:
        with open(args.dump, encoding="utf-8") as f:
            raw = json.load(f)
    else:
        print("Fetching Linkt Sydney toll calculator...")
        raw = fetch_raw()
        if args.save_dump:
            with open(args.save_dump, "w", encoding="utf-8") as f:
                json.dump(raw, f)
    data = build(raw)
    Path(args.out).write_text(json.dumps(data, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {args.out}: {len(data['points'])} points, {len(data['pairs'])} direct pairs ({data['stats']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
