"""NSW toll-road auto-detection and pricing.

Replaces the old `app.models.geofence.Geofence(kind="toll")` flat-circle
model (still used, unchanged, for tenant-defined AD HOC toll circles — see
`app.services.geofence`) for the roads in the authoritative NSW toll dataset
(`app/data/nsw_toll_roads.json` / `app/data/nsw_toll_gantries.csv`, loaded by
`scripts/seed_toll_roads.py`).

The things every function in this module exists to get right: honour each
road's own `TollRoad.charging_policy` (see `app.models.toll`'s module
docstring — once-per-road, cumulative-per-point, or distance-metered are NOT
interchangeable, a single blanket "charge once" rule is wrong for a road
like Hills M2), respect a one-way/northbound/southbound-only road's actual
direction, enforce a shared network-wide cap across roads that have one
(`TollRoad.network_group` — WestConnex), and never invent a dollar figure
the source data doesn't support (see the "unpriced" handling in
`apply_toll_detection` below).

Called from `app.services.trips.apply_tick`, once per submitted GPS point,
ALONGSIDE (not instead of) the pre-existing ad hoc-geofence toll detection —
the two dedup mechanisms are independent and additive (see
`app.models.trips.Trip.auto_tolled_roads` vs `.auto_tolls_applied`).
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from decimal import Decimal
from itertools import combinations, pairwise
from math import asin, atan2, cos, degrees, radians, sin, sqrt

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.toll import (
    TollGantry,
    TollPointPriceRevision,
    TollRoad,
    TollRoadPriceRevision,
)
from app.services.fare_engine import NSW_FARE_ZONE, round_half_up

# Pricing models whose charge is computed from running distance-since-entry
# rather than a single fixed amount -- see `TollRoad.charging_policy ==
# "distance_metered"` in app.models.toll's module docstring. A road on this
# list is the one case where re-detecting the SAME road on a later tick must
# still REVISE its existing `auto_tolled_roads` entry (rather than being
# skipped as "already charged") -- see apply_toll_detection below.
_DISTANCE_METERED_PRICING_MODELS = ("distance", "distance_with_flagfall")

# Gantries are precise point structures (unlike the old "near this landmark"
# circles, which used 300-1500m radii) — a tight, DOCUMENTED app-level
# constant, NOT sourced from data (the dataset carries no per-gantry
# detection radius at all). Generous enough for ordinary in-vehicle GPS
# accuracy (typically 5-30m) while narrow enough that two gantries a few
# hundred metres apart on the same interchange don't both fire for one
# crossing.
GANTRY_DETECTION_RADIUS_M = 150.0

# Closest approach a trip must actually achieve to a gantry before that gantry
# counts as CROSSED rather than merely passed nearby -- the device-side mirror is
# `TOLL_CONFIRM_RADIUS_M` in android/.../domain/fare/TollDetector.kt, and the two
# MUST stay equal (see that constant's own doc for the full reasoning).
#
# Field report, 2026-09-07: vehicles that never entered a toll road were being
# charged for it. Australian motorways are flanked by service roads and parallel
# surface streets, and a tunnel's gantry coordinate is the SURFACE projection of a
# point underground -- so the street above it is metres away horizontally. All of
# those sit inside the 150m detection radius. That radius has to stay generous (it
# is derived from real GPS error plus the distance covered between fixes at
# motorway speed), so a second, tighter test decides whether to CHARGE: a vehicle
# in the tolled lanes passes essentially underneath the gantry, while one on
# adjacent infrastructure keeps a real lateral offset.
TOLL_CONFIRM_RADIUS_M = 60.0

# How many of a road's own gantries must be confirmed at close range before the
# road is charged -- the "multiple checkpoints" corroboration rule. One close pass
# can still be a coincidence (a cross street directly over a gantry); a vehicle
# genuinely travelling the road passes a SEQUENCE of its gantries. Capped at the
# number the registry actually has for that road, so a single-gantry road stays
# chargeable rather than becoming permanently un-billable.
TOLL_MIN_CONFIRMATIONS = 2

# How far (lat/lng distance, via _distance_to_road_corridor_m) a "distance_
# metered" road's OPEN per-km progress may drift from that road's own real
# gantries/gantry-to-gantry chords before the vehicle is treated as having
# genuinely LEFT the corridor -- see apply_toll_detection's open-progress
# loop, which is what this constant exists to gate.
#
# Field defect, 2026-09-09: a per-km road's running distance
# (`trip.toll_road_progress`) used to be revised ONLY on a tick that also
# matched find_nearby_gantries -- i.e. only while within
# GANTRY_DETECTION_RADIUS_M (150m) of one of that SAME road's own gantries.
# On a road like Westlink M7 or WestConnex, whose real gantries sit
# kilometres apart (an interchange ramp here, another kilometres down the
# corridor), every tick in between silently never revised the bill, and the
# final charge froze at whatever it was at the LAST gantry actually passed
# -- permanently missing the fare for the stretch from there to wherever the
# vehicle really exits. This constant is deliberately generous (5x the
# gantry-detection radius) because there is no real polyline for these
# roads in the source data -- only the gantry POINTS themselves --  so the
# corridor between two real gantries several kilometres apart is only ever
# APPROXIMATED by the straight-line chord joining them (see
# _distance_to_road_corridor_m), and a real motorway's actual alignment
# bows away from that chord by well more than 150m over a multi-kilometre
# span. Too tight here reintroduces exactly the under-billing this exists
# to fix (a real corridor point wrongly read as "left"); too loose only
# delays finalization, it never invents a charge, so this errs generous on
# purpose.
#
# 2026-09-09 cross-check pass: for the handful of roads whose real chain
# data resolves cleanly (see `app.models.toll.TollGantry.sequence_position`
# / `scripts/seed_toll_roads.py`'s `_REAL_ROAD_CHAIN_WAYPOINTS`), this radius
# is now tested against `_distance_to_real_chain_corridor_m`'s REAL
# chain-adjacent-segment distance instead of `_distance_to_road_corridor_m`'s
# any-two-gantries chord — a strictly tighter approximation of the actual
# road — before falling back to the chord approximation for every other
# road, unchanged. The constant itself is kept the same for both: it is
# already a generous, GPS-error-driven buffer, and the real-chain distance
# it is now sometimes compared against is more accurate, never less, so
# nothing about widening/narrowing this figure was required to adopt it.
CORRIDOR_EXIT_RADIUS_M = GANTRY_DETECTION_RADIUS_M * 5  # 750.0

_EARTH_RADIUS_M = 6_371_008.8

# Minimum movement between consecutive GPS points before a computed bearing
# is trusted to mean anything — below this, ordinary GPS jitter (or a
# vehicle stopped at lights) dominates and the "direction of travel" it
# implies is noise, not signal.
_MIN_BEARING_DISTANCE_M = 15.0


# --- geometry -----------------------------------------------------------------


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Great-circle distance between two lat/lng points, in metres. Same
    formula/constant family as app.services.geofence.haversine_m and
    app.services.trips.haversine_km — kept as its own copy (rather than a
    cross-domain import) for the same reason those two don't share one
    either: each is a tiny, self-contained pure function."""
    phi1, phi2 = radians(lat1), radians(lat2)
    dphi = radians(lat2 - lat1)
    dlambda = radians(lng2 - lng1)
    a = sin(dphi / 2) ** 2 + cos(phi1) * cos(phi2) * sin(dlambda / 2) ** 2
    c = 2 * asin(sqrt(a))
    return _EARTH_RADIUS_M * c


def _local_xy_m(lat: float, lng: float, ref_lat: float, ref_lng: float) -> tuple[float, float]:
    """(lat, lng) as flat (east, north) metres relative to (ref_lat, ref_lng)
    — a simple equirectangular projection, accurate enough over the few-
    kilometre spans between two of one road's own real gantries (NOT a
    geodesic projection — deliberately simple, the same "small table, no
    PostGIS" trade-off haversine_m itself already accepts for this module)."""
    x = radians(lng - ref_lng) * cos(radians(ref_lat)) * _EARTH_RADIUS_M
    y = radians(lat - ref_lat) * _EARTH_RADIUS_M
    return x, y


def distance_to_segment_m(
    lat: float, lng: float, lat1: float, lng1: float, lat2: float, lng2: float
) -> float:
    """Distance in metres from (lat, lng) to the line SEGMENT between
    (lat1, lng1) and (lat2, lng2) (not the infinite line through them) —
    projects all three points to local flat metres around (lat1, lng1) via
    `_local_xy_m`, then ordinary 2D point-to-segment distance. Used by
    `_distance_to_road_corridor_m` to approximate a road's corridor as the
    chord between two of its own real gantries, since the source data has
    no polyline for these roads."""
    px, py = _local_xy_m(lat, lng, lat1, lng1)
    bx, by = _local_xy_m(lat2, lng2, lat1, lng1)
    length_sq = bx * bx + by * by
    if length_sq == 0:
        return sqrt(px * px + py * py)
    t = max(0.0, min(1.0, (px * bx + py * by) / length_sq))
    proj_x, proj_y = t * bx, t * by
    return sqrt((px - proj_x) ** 2 + (py - proj_y) ** 2)


async def _distance_to_real_chain_corridor_m(
    session: AsyncSession, *, road_id: str, lat: float, lng: float
) -> float | None:
    """The REAL upgrade to `_distance_to_road_corridor_m` below: for a road
    whose gantries carry real `TollGantry.sequence_position` /
    `cumulative_distance_km` (see that model's docstring and
    `scripts/seed_toll_roads.py`'s `_REAL_ROAD_CHAIN_WAYPOINTS` for exactly
    which roads qualify), (lat, lng)'s distance from the road's corridor is
    the smaller of (a) distance to the nearest real waypoint itself, and (b)
    distance to the chord segment between each pair of CHAIN-ADJACENT real
    waypoints (consecutive `sequence_position`, NOT every pairwise
    combination the chord approximation below has to use) — a strictly
    tighter, more faithful approximation of the real road than a chord
    between two arbitrary gantries, because it only ever draws a line
    between two points genuinely adjacent along the real corridor.

    Returns `None` — NOT `float("inf")` — when `road_id` has zero gantries
    with real chain data at all, which the caller (`apply_toll_detection`)
    reads as "fall back to `_distance_to_road_corridor_m`'s chord
    approximation for this road" rather than "this road's corridor is
    infinitely far away"; those are two different facts and must not share
    a sentinel."""
    result = await session.execute(
        select(TollGantry)
        .where(TollGantry.toll_road_id == road_id, TollGantry.sequence_position.is_not(None))
        .order_by(TollGantry.sequence_position)
    )
    waypoints = result.scalars().all()
    if not waypoints:
        return None
    best = min(haversine_m(lat, lng, g.latitude, g.longitude) for g in waypoints)
    for g1, g2 in pairwise(waypoints):
        best = min(
            best,
            distance_to_segment_m(lat, lng, g1.latitude, g1.longitude, g2.latitude, g2.longitude),
        )
    return best


async def _distance_to_road_corridor_m(
    session: AsyncSession, *, road_id: str, lat: float, lng: float
) -> float:
    """Approximates how far (lat, lng) is from `road_id`'s own real
    corridor: the smaller of (a) the distance to road_id's nearest single
    gantry, and (b) the distance to the straight-line chord between any TWO
    of road_id's gantries (every pair, not just some assumed "next along
    the corridor" order — the source data does not itself record gantry
    sequence). `float("inf")` for a road with zero gantries (can't be on a
    corridor that has no known coordinates at all).

    Used ONLY to decide whether a "distance_metered" road's OPEN per-km
    progress is still genuinely accruing (see CORRIDOR_EXIT_RADIUS_M in
    apply_toll_detection) — actual gantry CROSSING detection is unrelated
    and still uses the tight, point-only `find_nearby_gantries`/
    TOLL_CONFIRM_RADIUS_M tests, unchanged by this function."""
    result = await session.execute(select(TollGantry).where(TollGantry.toll_road_id == road_id))
    gantries = result.scalars().all()
    if not gantries:
        return float("inf")
    best = min(haversine_m(lat, lng, g.latitude, g.longitude) for g in gantries)
    for g1, g2 in combinations(gantries, 2):
        best = min(
            best,
            distance_to_segment_m(lat, lng, g1.latitude, g1.longitude, g2.latitude, g2.longitude),
        )
    return best


# Sized like GANTRY_DETECTION_RADIUS_M but larger, deliberately -- mirrors
# Android's CORRIDOR_PORTAL_MATCH_RADIUS_M (domain/fare/KnownCorridor.kt) and
# for the identical reason: the EXIT side of a real GPS blackout can take a
# fix cycle or two longer than ordinary jitter to re-lock after a genuine
# signal loss, so the vehicle can be materially further past the tunnel
# mouth before the first usable post-blackout point lands. Kept as its own
# named constant, not reused from GANTRY_DETECTION_RADIUS_M, so the two can
# drift independently if either one's real-world tuning need ever diverges.
CORRIDOR_BLACKOUT_PORTAL_RADIUS_M = 250.0


def _greedy_chain_distance_m(
    gantries: list[TollGantry], start: TollGantry, end: TollGantry
) -> float | None:
    """Sums the real, consecutive haversine legs of a greedy nearest-neighbour
    walk from `start` to `end` over `gantries` (one road's own points) --
    the same reconstruction Android's `greedyChainDistanceM`
    (domain/fare/KnownCorridor.kt) uses, and for the identical reason: this
    table carries no stored path order (`TollGantry.sequence_position` is
    null for nearly every real road today -- see
    `scripts/seed_toll_roads.py`'s `_REAL_ROAD_CHAIN_WAYPOINTS`), so a road's
    own point sequence has to be reconstructed per lookup from whichever
    gantries it actually has. `None` if the walk cannot reach `end` within
    `gantries`' own size (a defensive bound, not an expected outcome for a
    finite, correctly-tagged road)."""
    if start.id == end.id:
        return 0.0
    remaining = [g for g in gantries if g.id != start.id]
    current = start
    total = 0.0
    steps_left = len(gantries)
    while current.id != end.id:
        if steps_left <= 0 or not remaining:
            return None
        steps_left -= 1
        nxt = min(
            remaining,
            key=lambda g: haversine_m(current.latitude, current.longitude, g.latitude, g.longitude),
        )
        total += haversine_m(current.latitude, current.longitude, nxt.latitude, nxt.longitude)
        remaining = [g for g in remaining if g.id != nxt.id]
        current = nxt
    return total


async def known_corridor_distance_km(
    session: AsyncSession, *, entry_lat: float, entry_lng: float, exit_lat: float, exit_lng: float
) -> Decimal | None:
    """Server-side port of Android's `knownCorridorDistanceKm`
    (domain/fare/KnownCorridor.kt) -- the SAME question, asked here for
    `app.services.trips.recompute_from_trace`'s benefit rather than the
    live on-device meter's: given the last point before a real GPS gap in a
    submitted trace and the first point after it, is there a real mapped
    road whose own gantry points both points plausibly sit on, and if so,
    what is the real distance along THAT road's own points between them?

    Exists because `recompute_from_trace` previously had no concept of a
    GPS blackout at all -- it replayed every trace point through
    `plausible_distance_km`'s speed-cap alone, which exists to catch a
    SHORT-timeframe glitch (Sydney-to-Perth in 5 seconds) and has nothing to
    say about a genuinely long gap (a real tunnel: the device lost signal
    for real, for minutes, not seconds). A gap that long stays "plausible"
    to a 300km/h speed cap for a perfectly ordinary tunnel-length haversine
    jump, so the server billed the FULL straight-line distance across a
    blackout the device itself had already, correctly, refused to bill
    anything for -- a real trip found live (2026-09-10) recomputed to
    43% more than the device's own total purely from this one gap, auto-
    flagged for review instead of silently overcharging, but wrong either
    way. This function is `recompute_from_trace`'s side of the same fix
    Android already shipped: for a gap this function cannot match to a real
    road, the caller must bill nothing extra for it (see that call site) --
    rejection, not fabrication, is the default here exactly as it is there.

    Same geometry, same tolerances, same "shortest candidate wins" rule as
    the Android original; see that file's own doc for the full reasoning
    this deliberately does not re-derive. `None` -- no known distance, no
    charge -- whenever: no road's gantries lie within
    `CORRIDOR_BLACKOUT_PORTAL_RADIUS_M` of BOTH points; the two points match
    to the very same single gantry; or a road has fewer than two gantries at
    all."""
    result = await session.execute(select(TollGantry))
    all_gantries = result.scalars().all()

    by_road: dict[str, list[TollGantry]] = {}
    for g in all_gantries:
        by_road.setdefault(g.toll_road_id, []).append(g)

    best_km: Decimal | None = None
    for gantries in by_road.values():
        if len(gantries) < 2:
            continue  # nothing to interpolate a path along

        entry_nearest = min(
            gantries, key=lambda g: haversine_m(entry_lat, entry_lng, g.latitude, g.longitude)
        )
        entry_gap_m = haversine_m(
            entry_lat, entry_lng, entry_nearest.latitude, entry_nearest.longitude
        )
        if entry_gap_m > CORRIDOR_BLACKOUT_PORTAL_RADIUS_M:
            continue  # entry point is not near this road at all

        exit_nearest = min(
            gantries, key=lambda g: haversine_m(exit_lat, exit_lng, g.latitude, g.longitude)
        )
        exit_gap_m = haversine_m(exit_lat, exit_lng, exit_nearest.latitude, exit_nearest.longitude)
        if exit_gap_m > CORRIDOR_BLACKOUT_PORTAL_RADIUS_M:
            continue  # exit point does not plausibly continue this road

        if entry_nearest.id == exit_nearest.id:
            continue  # same single point both ends -- no real path

        path_m = _greedy_chain_distance_m(gantries, entry_nearest, exit_nearest)
        if path_m is None:
            continue

        total_km = Decimal(str((entry_gap_m + path_m + exit_gap_m) / 1000.0))
        if best_km is None or total_km < best_km:
            best_km = total_km

    return best_km if best_km is not None and best_km > 0 else None


def bearing_degrees(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Initial great-circle bearing from point 1 to point 2, in degrees
    clockwise from true north, normalized to [0, 360)."""
    phi1, phi2 = radians(lat1), radians(lat2)
    dlambda = radians(lng2 - lng1)
    x = sin(dlambda) * cos(phi2)
    y = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dlambda)
    return (degrees(atan2(x, y)) + 360) % 360


def classify_bearing(lat1: float, lng1: float, lat2: float, lng2: float) -> str | None:
    """Coarse compass quadrant (45-degree buckets centred on the four
    cardinal directions) for the direction of travel from point 1 to point
    2, or None if the two points are too close together to trust (see
    _MIN_BEARING_DISTANCE_M) — e.g. the trip's very first tick, or a vehicle
    stopped at a light. Deliberately coarse: this is a fair, DOCUMENTED
    approximation from consecutive raw GPS fixes for the handful of NSW
    roads this actually gates (Eastern Distributor northbound, Sydney
    Harbour Bridge/Tunnel southbound — both run close enough to a cardinal
    axis for this to work), not a survey-grade bearing calculation."""
    if haversine_m(lat1, lng1, lat2, lng2) < _MIN_BEARING_DISTANCE_M:
        return None
    bearing = bearing_degrees(lat1, lng1, lat2, lng2)
    if bearing >= 315 or bearing < 45:
        return "north"
    if bearing < 135:
        return "east"
    if bearing < 225:
        return "south"
    return "west"


def direction_allows_charge(directional: str | None, compass: str | None) -> bool | None:
    """Whether a crossing travelling in `compass` direction may charge a road
    whose `TollRoad.directional` is `directional`.

    Returns True (charge), False (genuinely the wrong direction — never
    charge this crossing), or None ("can't tell yet this tick" — the caller
    must not charge on a None, but should try again on a later tick once
    there's reliable movement; this is NOT the same as False).

    "both" always allows. "one_way" (Military Road E-Ramp) always allows
    too, WITHOUT needing a bearing — a one-way road has only one physical
    carriageway, so any detected crossing is necessarily travelling its only
    tolled direction regardless of what a coarse compass bucket says.
    """
    if directional in ("both", "one_way", None):
        return True
    if compass is None:
        return None
    if directional == "northbound_only":
        return compass == "north"
    if directional == "southbound_only":
        return compass == "south"
    return True


# --- time-of-day pricing (Sydney Harbour Bridge / Tunnel) --------------------


def _shb_sht_band(ts: datetime) -> str:
    """Classifies `ts` into one of SHB_SHT's three real published bands
    (peak/off_peak/night). This is a faithful, commented STRUCTURAL mirror of
    the exact windows described in app/data/nsw_toll_roads.json's own
    `time_of_day_rates_class_a[].windows` text for that road:
      peak:     Mon-Fri 06:30-09:30 and 16:00-19:00
      off_peak: Mon-Fri 09:30-16:00; Sat-Sun 08:00-20:00
      night:    Mon-Fri 19:00-06:30; Sat-Sun 20:00-08:00
    Not a generic parser of that free-text field (it's prose for humans, not
    a machine schedule format) — the bands/prices themselves still come from
    the JSON; only the *shape* of the schedule is encoded here, from the same
    real source text.

    Classified in NSW LOCAL time (`NSW_FARE_ZONE`), like every other
    time-of-day rule in this codebase. Those published windows are Sydney wall
    clock, and devices sync `ts` as UTC — reading the raw hour shifted every
    band by 10-11 hours, so the weekday morning peak was billed at the night
    rate and vice versa. (This is the same defect, and the same fix, as
    app.services.fare_engine.resolve_time_class_and_peak; see its doc for the
    live trip that surfaced it.) A naive `ts` is taken to be NSW local already,
    matching that function and keeping existing callers' meaning intact."""
    if ts.tzinfo is not None:
        ts = ts.astimezone(NSW_FARE_ZONE)
    weekday = ts.weekday()  # Monday=0 ... Sunday=6
    is_weekend = weekday >= 5
    minute_of_day = ts.hour * 60 + ts.minute

    def _t(hour: int, minute: int = 0) -> int:
        return hour * 60 + minute

    if not is_weekend:
        if _t(6, 30) <= minute_of_day < _t(9, 30) or _t(16, 0) <= minute_of_day < _t(19, 0):
            return "peak"
        if _t(9, 30) <= minute_of_day < _t(16, 0):
            return "off_peak"
        return "night"
    if _t(8, 0) <= minute_of_day < _t(20, 0):
        return "off_peak"
    return "night"


def select_time_of_day_price(rates_class_a: list[dict], ts: datetime) -> Decimal | None:
    """Picks the Class A price for whichever band `ts` falls into, from a
    TollRoadPriceRevision.time_of_day_rates_class_a list. None if that list
    doesn't carry the resolved band (shouldn't happen for real seeded data,
    but a road with an incomplete/malformed revision must never crash the
    tick — see apply_toll_detection's use of this as an "unpriced" signal)."""
    band = _shb_sht_band(ts)
    for entry in rates_class_a:
        if entry.get("band") == band and entry.get("price") is not None:
            return Decimal(str(entry["price"]))
    return None


# --- pricing lookup -----------------------------------------------------------


async def current_price_revision(
    session: AsyncSession, *, toll_road_id: str, as_of: date | None = None
) -> TollRoadPriceRevision | None:
    """The revision in force `as_of` (defaults to today) for `toll_road_id` —
    the latest revision whose effective_date is not after `as_of`. See
    app.models.toll's module docstring for why pricing is versioned in its
    own table rather than columns on TollRoad."""
    as_of = as_of or datetime.now(UTC).date()
    stmt = (
        select(TollRoadPriceRevision)
        .where(
            TollRoadPriceRevision.toll_road_id == toll_road_id,
            TollRoadPriceRevision.effective_date <= as_of,
        )
        .order_by(TollRoadPriceRevision.effective_date.desc())
        .limit(1)
    )
    result = await session.execute(stmt)
    return result.scalar_one_or_none()


async def current_toll_point_price_revision(
    session: AsyncSession, *, toll_point_id: str, as_of: date | None = None
) -> TollPointPriceRevision | None:
    """Same lookup as `current_price_revision`, one level down — the
    revision in force `as_of` for one named `TollPoint` of a
    `pricing_model == "per_point"` road (M2/CCT/LCT)."""
    as_of = as_of or datetime.now(UTC).date()
    stmt = (
        select(TollPointPriceRevision)
        .where(
            TollPointPriceRevision.toll_point_id == toll_point_id,
            TollPointPriceRevision.effective_date <= as_of,
        )
        .order_by(TollPointPriceRevision.effective_date.desc())
        .limit(1)
    )
    result = await session.execute(stmt)
    return result.scalar_one_or_none()


def distance_rate_per_km_class_a(road: TollRoad, revision: TollRoadPriceRevision) -> Decimal | None:
    """FALLBACK ONLY — see `effective_rate_per_km_class_a` below, which
    always prefers a real published rate over this. $/km for a "distance"
    pricing-model road, backed out of its published Class A cap and its REAL
    corridor length (`TollRoad.derived_corridor_km` — computed at seed time
    from the road's actual gantry coordinates, see
    `scripts/seed_toll_roads.py`; not invented). None if either input is
    missing, which `apply_toll_detection` treats as "unpriced".

    Westlink M7 used to rely on this as its ONLY rate, which the 2026-09-07
    correction pass identified as wrong: dividing a cap by a self-measured
    gantry corridor is a derivation, not Linkt's actual published $/km rate,
    and the two need not agree (they didn't — this produced roughly
    $0.44/km against a real published $0.5252/km). Kept only as a fallback
    for a future "distance" road with no published rate captured yet.
    """
    if revision.cap_class_a is None or not road.derived_corridor_km:
        return None
    return revision.cap_class_a / road.derived_corridor_km


def effective_rate_per_km_class_a(
    road: TollRoad, revision: TollRoadPriceRevision
) -> Decimal | None:
    """The $/km rate actually used to price a "distance" or
    "distance_with_flagfall" road — the revision's own published
    `rate_per_km_class_a` (Linkt's real, cited figure) if present, else the
    derived-corridor fallback (`distance_rate_per_km_class_a`). None if
    neither is available, which `apply_toll_detection` treats as
    "unpriced"."""
    if revision.rate_per_km_class_a is not None:
        return revision.rate_per_km_class_a
    return distance_rate_per_km_class_a(road, revision)


# --- gantry detection -----------------------------------------------------------


async def find_nearby_gantries(
    session: AsyncSession, *, lat: float, lng: float, radius_m: float = GANTRY_DETECTION_RADIUS_M
) -> list[TollGantry]:
    """Every TollGantry within `radius_m` of (lat, lng). Same "no PostGIS, no
    spatial index, just haversine over a small table" trade-off
    app.services.geofence already accepts for its circles — 141 rows is
    cheap to full-scan once per tick point; see that module's docstring."""
    result = await session.execute(select(TollGantry))
    candidates = result.scalars().all()
    return [g for g in candidates if haversine_m(lat, lng, g.latitude, g.longitude) <= radius_m]


# --- main entry point, called from app.services.trips.apply_tick ------------


async def _network_capped_amount(
    session: AsyncSession,
    *,
    road: TollRoad,
    road_id: str,
    raw_amount: Decimal,
    revision: TollRoadPriceRevision,
    charged_roads: dict[str, str],
) -> Decimal:
    """Clamps `raw_amount` (this road's own amount, already capped at its
    OWN per-road cap) so that the SUM of every already-charged road sharing
    `road.network_group` never exceeds that group's published network-wide
    cap (WestConnex: $12.74 Class A across M4/M8/M5E/M4M8_LINK for one trip
    — see `TollRoad.network_group` / `TollRoadPriceRevision.
    network_cap_class_a`). A no-op for a road with no network_group, or a
    group whose revision carries no network cap figure.

    Interpretation call (flagged, not a stated fact): when a crossing would
    push the network total over the cap, THIS (the most-recently-crossed)
    road absorbs the reduction rather than retroactively reducing an
    earlier one — chosen because it never requires revising a charge already
    shown to the passenger for a different road, and it never overcharges
    the trip as a whole."""
    if not road.network_group or revision.network_cap_class_a is None:
        return raw_amount

    prior_network_total = Decimal(0)
    for other_id, other_amount in charged_roads.items():
        if other_id == road_id:
            continue
        other_road = await session.get(TollRoad, other_id)
        if other_road is not None and other_road.network_group == road.network_group:
            prior_network_total += Decimal(other_amount)

    remaining = revision.network_cap_class_a - prior_network_total
    if remaining < 0:
        remaining = Decimal(0)
    return min(raw_amount, remaining)


async def _required_confirmations(session: AsyncSession, road_id: str) -> int:
    """How many close-range gantry confirmations `road_id` needs before it may be
    charged: TOLL_MIN_CONFIRMATIONS where the registry knows enough gantries to
    supply them, otherwise as many as physically exist.

    A road the dataset only has one gantry for cannot corroborate itself; demanding
    two would make it permanently unchargeable, silently converting a false-charge
    risk into a guaranteed missed charge. Those roads still have to clear the tight
    TOLL_CONFIRM_RADIUS_M test."""
    result = await session.execute(
        select(func.count()).select_from(TollGantry).where(TollGantry.toll_road_id == road_id)
    )
    gantry_count = result.scalar_one()
    return max(1, min(TOLL_MIN_CONFIRMATIONS, gantry_count))


async def _is_corroborated(
    session: AsyncSession, confirmed: dict[str, set[str]], road_id: str
) -> bool:
    """Whether this trip has enough close-range evidence to charge `road_id`."""
    return len(confirmed.get(road_id, ())) >= await _required_confirmations(session, road_id)


async def apply_toll_detection(
    session: AsyncSession,
    *,
    trip,
    prev_lat: float,
    prev_lng: float,
    lat: float,
    lng: float,
    ts: datetime,
    cumulative_distance_km: Decimal,
) -> None:
    """Detects every real NSW toll road gantry-crossed at (lat, lng) and
    updates `trip.tolls` / `trip.auto_tolled_roads` / `trip.toll_road_progress`
    / `trip.unpriced_toll_road_ids` in place. Does NOT commit — same
    "caller owns the session/transaction" contract as
    `app.services.trips.apply_tick`, which calls this once per submitted
    telemetry point, right after that point has been folded into the fare
    engine's own cumulative distance (`cumulative_distance_km` is that
    post-tick running total, needed for the "distance"/"distance_with_
    flagfall" pricing models).

    `(prev_lat, prev_lng)` is the immediately preceding point (or the trip's
    last known position/start position) — used only to classify a
    directional road's travel bearing (see `classify_bearing`).

    Honours each road's own `TollRoad.charging_policy` (see
    `app.models.toll`'s module docstring) instead of one blanket rule:
      - "once_per_road": charge once per trip. For a `pricing_model ==
        "per_point"` road with this policy (CCT, LCT), the first toll point
        actually crossed sets the price under the ROAD's own id in
        `auto_tolled_roads` — a second, different point of the same road
        later in the trip is not charged again.
      - "cumulative_per_point": charge ADDITIVELY, once per distinct toll
        point crossed, keyed by the `TollPoint.id` itself (e.g.
        "M2:north_ryde") in `auto_tolled_roads` rather than the road id —
        so a trip through 3 of M2's 6 points shows 3 separate line items
        that sum together, and a 4th distinct point later still adds to the
        total.
      - "distance_metered": the road's own running-distance-since-entry
        amount is REVISED (not skipped) on every later tick, same as
        before, now also true for "distance_with_flagfall" (WestConnex) —
        and, for a `network_group` road, additionally clamped so the group's
        shared network-wide cap for this trip is never exceeded (see
        `_network_capped_amount`).

    A `pricing_model == "toll_free"` road (M12, Iron Cove Link — see
    `app.models.toll.TOLL_PRICING_MODELS`) is charged $0.00 the moment it is
    detected at all, with no direction/corroboration gate and no revision
    lookup: there is no false-charge risk to guard against when the amount
    is always zero, and gating it would only delay clearing it out of
    `unpriced_toll_road_ids` — the dashboard/tablet prompt this whole status
    exists to suppress.

    2026-09-09 fix — per-km ("distance_metered") under-billing: this
    function used to revise a distance-metered road's bill ONLY on a tick
    that also matched `find_nearby_gantries` below, i.e. only while within
    GANTRY_DETECTION_RADIUS_M of one of THAT road's own gantries. Real
    gantries on a road like Westlink M7 or WestConnex sit kilometres apart,
    so every tick in between silently never revised `toll_road_progress` /
    `auto_tolled_roads`, and the final bill froze at whatever it was at the
    LAST gantry actually passed — never overbilling, always potentially
    undercharging the stretch from there to wherever the vehicle really
    exits the corridor. The open-progress loop directly below now keeps
    every already-charged distance-metered road's bill current on EVERY
    tick while the vehicle is still plausibly on that road's corridor (see
    `_distance_to_road_corridor_m` / `CORRIDOR_EXIT_RADIUS_M`), and finalizes
    (freezes, stops tracking) it the first tick that measures the vehicle as
    having genuinely left the corridor. A trip that closes while a per-km
    toll is still open is finalized implicitly: `app.services.trips.
    close_trip` bills `trip.tolls` exactly as this function last left it, so
    once this function keeps that figure current every tick (not just at
    gantries), the last tick before close IS the finalization — no separate
    "on close" step is needed or added in trips.py.
    """
    charged_roads: dict[str, str] = dict(trip.auto_tolled_roads or {})
    progress: dict[str, str] = dict(trip.toll_road_progress or {})
    unpriced: set[str] = set(trip.unpriced_toll_road_ids or [])
    confirmed: dict[str, set[str]] = {
        road_id: set(gantry_ids)
        for road_id, gantry_ids in (trip.toll_confirmed_gantries or {}).items()
    }
    tolls: Decimal = trip.tolls or Decimal(0)

    # --- keep every OPEN per-km toll's distance current, every tick --------
    # Unconditional (not gated on find_nearby_gantries matching anything
    # this tick) — see this function's own docstring for why that
    # unconditional-ness is the fix. `progress` doubles as the "still open"
    # marker: a road_id present in it has a confirmed entry not yet
    # finalized. Only a road already CHARGED at least once (`road_id in
    # charged_roads`, i.e. corroborated — see TOLL_MIN_CONFIRMATIONS) is
    # finalized here; one merely confirmed-but-not-yet-corroborated keeps
    # its entry_km anchor untouched, exactly as before this fix, so a road
    # that never corroborates never gets a spurious "exit" that would reset
    # its true entry point later.
    for road_id in [r for r in progress if r in charged_roads]:
        road = await session.get(TollRoad, road_id)
        if road is None or road.pricing_model not in _DISTANCE_METERED_PRICING_MODELS:
            continue  # defensive -- progress is only ever written for these

        real_distance = await _distance_to_real_chain_corridor_m(
            session, road_id=road_id, lat=lat, lng=lng
        )
        distance_to_corridor = (
            real_distance
            if real_distance is not None
            else await _distance_to_road_corridor_m(session, road_id=road_id, lat=lat, lng=lng)
        )
        if distance_to_corridor > CORRIDOR_EXIT_RADIUS_M:
            # Finalize: the vehicle has genuinely left this road's corridor.
            # Freeze the bill at whatever it already is (this OFF-corridor
            # point must never itself count as distance driven ON the
            # road) and stop tracking -- deleting the entry is what lets a
            # LATER re-entry (a fresh gantry hit on this same road, later in
            # this same trip) open a brand new entry_km anchor rather than
            # revise a segment that has already been billed and closed.
            del progress[road_id]
            continue

        revision = await current_price_revision(session, toll_road_id=road_id, as_of=ts.date())
        if revision is None or revision.confidence == "not_captured":
            continue  # can't reprice this tick -- leave the existing charge as-is
        rate = effective_rate_per_km_class_a(road, revision)
        if rate is None:
            continue

        entry_km = Decimal(progress[road_id])
        travelled_km = max(cumulative_distance_km - entry_km, Decimal(0))
        flagfall = revision.flagfall_class_a or Decimal(0)
        raw = flagfall + rate * travelled_km if travelled_km > 0 else Decimal(0)
        amount = min(raw, revision.cap_class_a) if revision.cap_class_a is not None else raw
        amount = await _network_capped_amount(
            session,
            road=road,
            road_id=road_id,
            raw_amount=amount,
            revision=revision,
            charged_roads=charged_roads,
        )
        amount = round_half_up(amount)
        previous_amount = Decimal(charged_roads[road_id])
        # Never let a re-tick REDUCE what's already billed -- a distance-
        # metered road's charge only ever grows within one continuous
        # corridor pass (see the finalize-then-fresh-anchor note above for
        # the one case a smaller number could otherwise arise from: a
        # later, separate re-entry after this road was already finalized).
        amount = max(amount, previous_amount)
        tolls = tolls - previous_amount + amount
        charged_roads[road_id] = str(amount)

    compass = classify_bearing(prev_lat, prev_lng, lat, lng)

    hits = await find_nearby_gantries(session, lat=lat, lng=lng)
    if not hits:
        trip.tolls = tolls
        trip.auto_tolled_roads = charged_roads
        trip.toll_road_progress = progress
        trip.unpriced_toll_road_ids = sorted(unpriced)
        trip.toll_confirmed_gantries = {road_id: sorted(ids) for road_id, ids in confirmed.items()}
        return

    # Record close-range evidence BEFORE any pricing -- see TOLL_CONFIRM_RADIUS_M
    # for the adjacent-road false charge this prevents. The distance anchor for a
    # distance-priced road is set here too, at FIRST confirmed contact rather than
    # when corroboration completes, so a road never under-bills the stretch between
    # its first and second confirmed gantry.
    for gantry in hits:
        if haversine_m(lat, lng, gantry.latitude, gantry.longitude) <= TOLL_CONFIRM_RADIUS_M:
            confirmed.setdefault(gantry.toll_road_id, set()).add(gantry.id)
            progress.setdefault(gantry.toll_road_id, str(cumulative_distance_km))

    for road_id in {g.toll_road_id for g in hits}:
        road = await session.get(TollRoad, road_id)
        if road is None:
            continue  # FK integrity guarantees this never happens for real data

        if road.pricing_model == "unpriced":
            unpriced.add(road_id)
            continue

        if road.pricing_model == "toll_free":
            # Detected at all -> zero charge, never a manual-price prompt.
            # See this function's docstring for why no gate is needed here.
            charged_roads.setdefault(road_id, "0.00")
            unpriced.discard(road_id)
            continue

        allowed = direction_allows_charge(road.directional, compass)
        if allowed is False:
            continue  # genuinely the wrong direction on a directional road — never charge
        if allowed is None:
            continue  # no reliable bearing yet this tick — try again once there's real movement

        # Corroboration gate. Being inside the 150m watch radius is not evidence of
        # having used the road. `continue`, never "flag as unpriced": a road driven
        # past is not a road whose price is unknown, and prompting for it manually
        # would just relocate the false charge. A trip that goes on to genuinely
        # enter the road corroborates on a later point and charges then.
        if not await _is_corroborated(session, confirmed, road_id):
            continue

        # --- per_point roads (M2 cumulative; CCT/LCT once-per-road) --------
        if road.pricing_model == "per_point":
            road_points = [
                g.toll_point_id for g in hits if g.toll_road_id == road_id and g.toll_point_id
            ]
            if not road_points:
                # A gantry matched this road but carries no toll_point_id —
                # a per_point road can't be priced without one; flag it.
                unpriced.add(road_id)
                continue

            if road.charging_policy == "cumulative_per_point":
                for point_id in road_points:
                    if point_id in charged_roads:
                        continue  # this exact toll point already charged this trip
                    point_revision = await current_toll_point_price_revision(
                        session, toll_point_id=point_id, as_of=ts.date()
                    )
                    if (
                        point_revision is None
                        or point_revision.confidence == "not_captured"
                        or point_revision.price_class_a is None
                    ):
                        unpriced.add(point_id)
                        continue
                    amount = round_half_up(point_revision.price_class_a)
                    tolls += amount
                    charged_roads[point_id] = str(amount)
                    unpriced.discard(point_id)
            else:
                # once_per_road: first point actually crossed sets the
                # price; already-charged roads never revise (see module
                # docstring's CCT/LCT interpretation note).
                if road_id not in charged_roads:
                    point_revision = await current_toll_point_price_revision(
                        session, toll_point_id=road_points[0], as_of=ts.date()
                    )
                    if (
                        point_revision is None
                        or point_revision.confidence == "not_captured"
                        or point_revision.price_class_a is None
                    ):
                        unpriced.add(road_id)
                    else:
                        amount = round_half_up(point_revision.price_class_a)
                        tolls += amount
                        charged_roads[road_id] = str(amount)
                        unpriced.discard(road_id)
            continue  # per_point roads are fully handled above

        distance_metered = road.pricing_model in _DISTANCE_METERED_PRICING_MODELS
        if road_id in charged_roads and not distance_metered:
            continue  # already charged once — nothing left to revise for this model

        revision = await current_price_revision(session, toll_road_id=road_id, as_of=ts.date())
        if revision is None or revision.confidence == "not_captured":
            unpriced.add(road_id)
            continue

        amount: Decimal | None
        if road.pricing_model == "flat":
            amount = revision.price_class_a_max
        elif road.pricing_model == "time_of_day":
            amount = select_time_of_day_price(revision.time_of_day_rates_class_a or [], ts)
        elif road.pricing_model in _DISTANCE_METERED_PRICING_MODELS:
            rate = effective_rate_per_km_class_a(road, revision)
            if rate is None:
                amount = None
            else:
                if road_id not in progress:
                    progress[road_id] = str(cumulative_distance_km)
                entry_km = Decimal(progress[road_id])
                travelled_km = max(cumulative_distance_km - entry_km, Decimal(0))
                flagfall = revision.flagfall_class_a or Decimal(0)
                # Zero charge (flagfall included) until real movement is
                # recorded past the entry gantry -- same "never charge on
                # the entry tick itself" convention the plain "distance"
                # model already used for M7, extended here to "distance_
                # with_flagfall": never overcharge a trip that crosses the
                # entry gantry but never sends another tick on this road.
                raw = flagfall + rate * travelled_km if travelled_km > 0 else Decimal(0)
                amount = min(raw, revision.cap_class_a) if revision.cap_class_a is not None else raw
                if amount is not None:
                    amount = await _network_capped_amount(
                        session,
                        road=road,
                        road_id=road_id,
                        raw_amount=amount,
                        revision=revision,
                        charged_roads=charged_roads,
                    )
        else:
            amount = None  # any future pricing model with no formula captured here

        if amount is None:
            unpriced.add(road_id)
            continue

        amount = round_half_up(amount)
        previous_amount = Decimal(charged_roads.get(road_id, "0"))
        if distance_metered:
            # Never let a re-tick REDUCE what's already billed -- see the
            # open-progress loop above's identical clamp/comment for the
            # one case this guards: a road finalized (corridor-exit) and
            # then re-entered later in the same trip opens a FRESH entry_km
            # anchor, whose own travelled_km could otherwise compute smaller
            # than the amount already billed for the earlier pass.
            amount = max(amount, previous_amount)
        tolls = tolls - previous_amount + amount
        charged_roads[road_id] = str(amount)
        unpriced.discard(road_id)

    trip.tolls = tolls
    trip.auto_tolled_roads = charged_roads
    trip.toll_road_progress = progress
    trip.unpriced_toll_road_ids = sorted(unpriced)
    trip.toll_confirmed_gantries = {road_id: sorted(ids) for road_id, ids in confirmed.items()}
