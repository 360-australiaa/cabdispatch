"""The jurisdiction seam (X1, `docs/plans/2026-09-08-global-meter-program.md`
Wave 3): a `FareRegion` describes every rule that used to be a hardcoded NSW
literal inside `app.services.fare_engine` — the timezone the fare clock runs
in, the night window, which weekdays count as "peak-eligible", the public
holiday calendar, the GST divisor, and the region vocabulary (urban/country/
exempt for NSW; a different jurisdiction may not have an urban/country split
at all).

**What this is not (yet).** A `FareRegion` is still selected in code (one
`NSWRegion` instance, exported below as `NSW_REGION`, and a synthetic
`VIC_TEST_REGION` used only by `tests/test_regions.py` to prove the seam
isn't NSW-only by construction) — `Tenant.jurisdiction` (added alongside
this) names *which* region a tenant is in, but nothing yet loads a
`FareRegion` from a database row. Making a jurisdiction fully data-driven
(a `jurisdictions` table an operator could add a row to without a code
change) is future work; what this pass buys is that `fare_engine.py` itself
no longer has NSW baked into its control flow — every rule is read off a
`FareRegion` object, and `NSWRegion` is simply the one this repo ships.

Golden-vector honesty: `NSWRegion` must reproduce today's behaviour bit for
bit. Nothing in `tests/test_fare_engine_golden.py` was changed to make this
pass — see that file's own untouched assertions.
"""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from zoneinfo import ZoneInfo


@dataclass(frozen=True)
class FareRegion:
    """One jurisdiction's fare-time and money rules. Every field here is a
    literal that `app.services.fare_engine` used to hardcode as a module
    constant (see that module's history, and the backend audit §2 "Hardcoded
    region assumptions" table) before this seam existed.

    Deliberately a plain frozen dataclass rather than `typing.Protocol` —
    `NSWRegion`/`VIC_TEST_REGION` are the only two instances that exist, and
    a dataclass gives them real equality/repr for free, which the protocol
    itself would not.
    """

    #: Machine-stable code — what `Tenant.jurisdiction` stores (e.g. "NSW").
    code: str
    #: Human label for the jurisdiction, e.g. "New South Wales".
    label: str
    #: The clock every fare-time classification in this region is made
    #: against — see `fare_engine.resolve_time_class_and_peak`'s own doc for
    #: why this must be a fixed zone, never the server's local time.
    tz: ZoneInfo
    #: ISO 4217 currency code this jurisdiction's tenants bill in by default.
    currency: str
    #: Divisor applied to a GST(-equivalent)-inclusive grand total to report
    #: its tax component — `None` for a jurisdiction with no such levy at
    #: all, in which case callers must omit the figure rather than report 0.
    gst_divisor: Decimal | None
    #: Night-rate window, as [start_hour, end_hour) wrapping midnight —
    #: NSW's is (22, 6): 10pm through (not including) 6am.
    night_start_hour: int
    night_end_hour: int
    #: Weekdays (Python `date.weekday()`: Monday=0 … Sunday=6) that make a
    #: NIGHT-window hiring "peak-eligible" on their own, independent of the
    #: holiday calendar below — NSW's is Friday/Saturday, `{4, 5}`.
    peak_weekdays: frozenset[int]
    #: Weekdays that resolve to the COUNTRY-only HOLIDAY time class on their
    #: own (NSW: Sunday, `{6}`) — separate from the public-holiday calendar
    #: because a jurisdiction may have neither, one, or both rules.
    holiday_weekdays: frozenset[int]
    #: Returns the set of public holiday dates for a given calendar year.
    #: A callable, not a precomputed set, because a real jurisdiction's
    #: calendar is gazetted year by year (see `nsw.py`'s own honesty note
    #: about 2027 being calculated, not gazetted).
    holidays: Callable[[int], frozenset[date]]
    #: The tariff "area" values this jurisdiction's Fares-Order-style rate
    #: card distinguishes — NSW: `("urban", "country", "exempt")`. A
    #: jurisdiction with a single flat rate card would supply a single-value
    #: tuple.
    region_vocabulary: tuple[str, ...]
    #: Human-readable summary of the maxi/large-vehicle uplift rule this
    #: jurisdiction applies — the eligibility LOGIC itself
    #: (`FareState.maxi_applied`) is jurisdiction-agnostic today (vehicle
    #: class + passenger count + wheelchair carve-out), so this field is
    #: documentation for now, not a second code path; the multiplier value
    #: itself already lives on the per-tenant `Tariff.maxi_multiplier`.
    maxi_rule: str

    def is_public_holiday(self, d: date) -> bool:
        return d in self.holidays(d.year)

    def is_day_before_public_holiday(self, d: date) -> bool:
        from datetime import timedelta

        return (d + timedelta(days=1)) in self.holidays((d + timedelta(days=1)).year)


_REGISTRY: dict[str, FareRegion] = {}


def register_region(region: FareRegion) -> FareRegion:
    _REGISTRY[region.code] = region
    return region


def get_region(code: str) -> FareRegion:
    """Looks up a registered `FareRegion` by `Tenant.jurisdiction` code.
    Falls back to NSW for an unrecognised/blank code — every tenant row that
    exists today predates this column and defaults to `"NSW"` via the
    accompanying migration, so this is a defensive floor, not the primary
    lookup path."""
    from app.services.regions.nsw import NSW_REGION

    return _REGISTRY.get(code) or NSW_REGION


__all__ = ["FareRegion", "get_region", "register_region"]
