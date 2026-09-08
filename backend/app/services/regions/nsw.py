"""The one `FareRegion` this repo actually ships: NSW, reproducing
`app.services.fare_engine`'s pre-seam behaviour bit for bit.

The holiday calendar, timezone and rate-card vocabulary below are read
straight off the existing module-level constants in `fare_engine.py`
(`NSW_PUBLIC_HOLIDAYS`, `NSW_FARE_ZONE`) rather than re-declared here, so
there is exactly one copy of "what is a NSW public holiday" in this backend
— this file wraps it in a `FareRegion`, it does not fork it.
"""

from __future__ import annotations

from decimal import Decimal
from zoneinfo import ZoneInfo

from app.models.tariffs import REGION_COUNTRY, REGION_EXEMPT, REGION_URBAN
from app.services.regions import FareRegion, register_region


def _nsw_holidays(year: int) -> frozenset:
    # Deferred import: fare_engine imports app.services.regions lazily too
    # (see resolve_time_class_and_peak's default), so importing it at module
    # scope here would be a cycle at package-init time.
    from app.services.fare_engine import NSW_PUBLIC_HOLIDAYS

    return frozenset(d for d in NSW_PUBLIC_HOLIDAYS if d.year == year)


NSW_REGION = register_region(
    FareRegion(
        code="NSW",
        label="New South Wales",
        tz=ZoneInfo("Australia/Sydney"),
        currency="AUD",
        gst_divisor=Decimal(11),
        night_start_hour=22,
        night_end_hour=6,
        peak_weekdays=frozenset({4, 5}),  # Friday, Saturday
        holiday_weekdays=frozenset({6}),  # Sunday (country-only HOLIDAY class)
        holidays=_nsw_holidays,
        region_vocabulary=(REGION_URBAN, REGION_COUNTRY, REGION_EXEMPT),
        maxi_rule=(
            "Fares Order cl 2(d): up to 150% of the fare (flag fall + peak + "
            "distance + waiting only) for a real maxi-cab vehicle carrying "
            "5+ passengers, or a maxi specifically requested at a Sydney "
            "Airport rank; a wheelchair hiring always overrides it off "
            "(cl 2(d)(ii))."
        ),
        # Sydney Airport ground-transport access fee (IPART Point-to-Point
        # passenger extras, 2025-26 schedule): a flat pass-through when the
        # hiring starts inside the airport precinct. Precinct = 1.8 km around
        # the terminals; mirrors android JurisdictionConfig.NSW exactly.
        airport_access_fee=Decimal("6.43"),
        airport_precinct_lat=-33.9399,
        airport_precinct_lng=151.1753,
        airport_precinct_radius_m=1_800.0,
    )
)

# Sydney Airport terminal taxi-rank pickup zones for `kind="airport"`
# geofences (app.models.geofence). One circle per rank; a hiring that STARTS
# inside any of them attracts `NSW_REGION.airport_access_fee` once (see
# app.services.trips.apply_airport_access_fee_at_start). Where two circles
# overlap (T2/T3 sit ~200 m apart) the smallest-radius one is charged.
#
# Coordinates and radii are APPROXIMATE — taken off the map, not surveyed —
# and are to be confirmed on the map by the operator before going live.
# They are deliberately NOT seeded into production (scripts/seed.py never
# runs there); the dashboard's "Add Sydney Airport terminals" button creates
# them through POST /v1/geofences, reading them from
# GET /v1/geofences/presets/airport (app/api/v1/geofences.py), and the
# tablet's offline fallback mirrors the same numbers.
SYDNEY_AIRPORT_TERMINAL_ZONES: tuple[dict, ...] = (
    {
        "name": "Sydney Airport T1 International taxi rank",
        "center_lat": -33.9361,
        "center_lng": 151.1656,
        "radius_m": 450.0,
    },
    {
        "name": "Sydney Airport T2 Domestic taxi rank",
        "center_lat": -33.9339,
        "center_lng": 151.1799,
        "radius_m": 400.0,
    },
    {
        "name": "Sydney Airport T3 Domestic taxi rank",
        "center_lat": -33.9333,
        "center_lng": 151.1783,
        "radius_m": 400.0,
    },
)

__all__ = ["NSW_REGION", "SYDNEY_AIRPORT_TERMINAL_ZONES"]
