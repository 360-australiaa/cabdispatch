"""NSW toll-road auto-detection and pricing.

Replaces the old `app.models.geofence.Geofence(kind="toll")` flat-circle
model (still used, unchanged, for tenant-defined AD HOC toll circles — see
`app.services.geofence`) for the 13 roads in the product owner's
authoritative NSW toll dataset (`app/data/nsw_toll_roads.json` /
`app/data/nsw_toll_gantries.csv`, loaded by `scripts/seed_toll_roads.py`).

The one thing every function in this module exists to get right: charge a
road ONCE PER TRIP no matter how many of its gantries are crossed, respect a
one-way/northbound/southbound-only road's actual direction, and never invent
a dollar figure the source data doesn't support (see the `zone_flat` /
"unpriced" handling in `apply_toll_detection` below).

Called from `app.services.trips.apply_tick`, once per submitted GPS point,
ALONGSIDE (not instead of) the pre-existing ad hoc-geofence toll detection —
the two dedup mechanisms are independent and additive (see
`app.models.trips.Trip.auto_tolled_roads` vs `.auto_tolls_applied`).
"""
from __future__ import annotations

from datetime import UTC, date, datetime
from decimal import Decimal
from math import asin, atan2, cos, degrees, radians, sin, sqrt

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.toll import TollGantry, TollRoad, TollRoadPriceRevision
from app.services.fare_engine import round_half_up

# Gantries are precise point structures (unlike the old "near this landmark"
# circles, which used 300-1500m radii) — a tight, DOCUMENTED app-level
# constant, NOT sourced from data (the dataset carries no per-gantry
# detection radius at all). Generous enough for ordinary in-vehicle GPS
# accuracy (typically 5-30m) while narrow enough that two gantries a few
# hundred metres apart on the same interchange don't both fire for one
# crossing.
GANTRY_DETECTION_RADIUS_M = 150.0

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

    Deliberately uses `ts`'s own hour/weekday exactly as given — no timezone
    conversion — same established convention as
    app.services.fare_engine.resolve_time_class_and_peak."""
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


def distance_rate_per_km_class_a(road: TollRoad, revision: TollRoadPriceRevision) -> Decimal | None:
    """$/km for a "distance" pricing-model road (M7 today), backed out of its
    published Class A cap and its REAL corridor length
    (`TollRoad.derived_corridor_km` — computed at seed time from the road's
    actual gantry coordinates, see `scripts/seed_toll_roads.py`; not
    invented). None if either input is missing, which
    `apply_toll_detection` treats as "unpriced"."""
    if revision.cap_class_a is None or not road.derived_corridor_km:
        return None
    return revision.cap_class_a / road.derived_corridor_km


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
    post-tick running total, needed for the "distance" pricing model).

    `(prev_lat, prev_lng)` is the immediately preceding point (or the trip's
    last known position/start position) — used only to classify a
    directional road's travel bearing (see `classify_bearing`).
    """
    hits = await find_nearby_gantries(session, lat=lat, lng=lng)
    if not hits:
        return

    charged_roads: dict[str, str] = dict(trip.auto_tolled_roads or {})
    progress: dict[str, str] = dict(trip.toll_road_progress or {})
    unpriced: set[str] = set(trip.unpriced_toll_road_ids or [])
    tolls: Decimal = trip.tolls or Decimal(0)

    compass = classify_bearing(prev_lat, prev_lng, lat, lng)

    for road_id in {g.toll_road_id for g in hits}:
        road = await session.get(TollRoad, road_id)
        if road is None:
            continue  # FK integrity guarantees this never happens for real data

        if road.pricing_model == "unpriced":
            unpriced.add(road_id)
            continue

        allowed = direction_allows_charge(road.directional, compass)
        if allowed is False:
            continue  # genuinely the wrong direction on a directional road — never charge
        if allowed is None:
            continue  # no reliable bearing yet this tick — try again once there's real movement

        if road.pricing_model == "zone_flat":
            # The published range spans a cheap ramp toll to the full
            # mainline toll and this dataset does not say which applies to
            # which specific gantry (see app.models.toll's module docstring)
            # — charging a guessed number from that range is exactly what
            # the task brief forbids. Flag the crossing instead.
            unpriced.add(road_id)
            continue

        if road_id in charged_roads and road.pricing_model != "distance":
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
        elif road.pricing_model == "distance":
            rate = distance_rate_per_km_class_a(road, revision)
            if rate is None:
                amount = None
            else:
                if road_id not in progress:
                    progress[road_id] = str(cumulative_distance_km)
                entry_km = Decimal(progress[road_id])
                travelled_km = max(cumulative_distance_km - entry_km, Decimal(0))
                raw = rate * travelled_km
                amount = min(raw, revision.cap_class_a) if revision.cap_class_a is not None else raw
        else:
            amount = None  # distance_with_flagfall etc. with no formula captured here

        if amount is None:
            unpriced.add(road_id)
            continue

        amount = round_half_up(amount)
        previous_amount = Decimal(charged_roads.get(road_id, "0"))
        tolls = tolls - previous_amount + amount
        charged_roads[road_id] = str(amount)
        unpriced.discard(road_id)

    trip.tolls = tolls
    trip.auto_tolled_roads = charged_roads
    trip.toll_road_progress = progress
    trip.unpriced_toll_road_ids = sorted(unpriced)
