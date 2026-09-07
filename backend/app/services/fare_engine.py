"""NSW tariff-switching fare engine.

PURE module — no DB, no FastAPI imports. Implements the NSW Point to Point
Transport (Fares) Order 2026, effective 1 June 2026, GST-inclusive rates
transcribed exactly from the product spec (do not "correct" these from
memory). The prior 2025 (no.2) card is kept below purely as historical
changelog reference — it is not live and not reachable from any code path.

| Component                                   | Urban          | Country        |
|----------------------------------------------|----------------|----------------|
| Hiring Charge (flag fall)                    | $5.17          | $5.29          |
| Peak Time Hiring Charge (urban only)          | $2.65          | n/a            |
| Distance Rate >=26km/h, first 12km            | $2.61/km       | $2.49/km       |
| Distance Rate >=26km/h, beyond 12km           | $2.37/km       | $3.41/km       |
| Night Distance Rate (10pm-6am any night), <12km | $3.10/km     | $2.97/km       |
| Night Distance Rate, beyond 12km              | $2.82/km       | $4.07/km       |
| Holiday Distance Rate (country only), <12km   | n/a            | $2.97/km       |
| Holiday Distance Rate, beyond 12km            | n/a            | $4.07/km       |
| Waiting Time <26km/h                          | 113.0 c/min    | 108.1 c/min    |
| Cleaning fee cap (both areas)                 | $124.14 (+GST already folded in per the Order's convention here) | same |

Prior card (2025 no.2, effective 3 Nov 2025 — superseded, historical only):
Hiring $5.00/$5.11, Peak $2.56, Distance $2.52/$2.29 and $2.41/$3.30, Night
$3.00/$2.73 and $2.87/$3.93, Holiday $2.87/$3.93, Waiting 109.2c/104.5c per
min.

Fare engine rule: at any instant exactly ONE of Distance Rate or Waiting Time
accrues, switched by the 26km/h threshold (never both, never neither, while
HIRED). `time_class` (day/night/holiday) and the peak-hiring flag are fixed at
journey commencement and do not change mid-trip even if the clock crosses a
boundary. The first-12km/beyond-12km distance band applies to cumulative trip
distance.

Maxi-cab rate (Order cl 2(d)): up to 150% of "the fare" — flag fall + peak
charge + distance charge + waiting charge ONLY, never tolls/PSL/extras/
cleaning fee — applies only when `FareState.maxi_applied` is true, itself a
DERIVED, non-settable property of `is_maxi_vehicle` (the vehicle's real
seating class — resolved authoritatively server-side from
`Vehicle.vehicle_class`, see app.services.trips, never trusted from a raw
client-supplied flag), `passenger_count` (>=5 triggers it), `wheelchair_hiring`
(always overrides it off — cl 2(d)(ii)'s carve-out), and
`airport_rank_requested_maxi` (a maxi specifically requested at a Sydney
Airport rank triggers it independent of passenger count, again except for a
wheelchair hiring).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal
from enum import Enum
from zoneinfo import ZoneInfo

# --- rounding helpers --------------------------------------------------------

CENT = Decimal("0.01")


def round_half_up(amount: Decimal) -> Decimal:
    """Quantize to cents, half-up (<0.5c down, >=0.5c up) — the rounding rule
    the Fares Order mandates for the non-cash surcharge, applied generally here
    for all displayed money amounts other than the metered fare total itself
    (see round_down below)."""
    return amount.quantize(CENT, rounding=ROUND_HALF_UP)


def round_down(amount: Decimal) -> Decimal:
    """Quantize to cents, always DOWN. Per Act s76(5)/(6): a rank/hail fare
    must never exceed the regulated maximum, and rounding down is always
    lawful while rounding up is not — so the metered fare total itself
    (FareBreakdown.fare_total) rounds this way, never half-up. Non-cash
    surcharge and the GST-component figure keep round_half_up — that is the
    Order's own explicit rule for those two (cl 4(a)), untouched."""
    return amount.quantize(CENT, rounding=ROUND_DOWN)


def _d(value) -> Decimal:
    return value if isinstance(value, Decimal) else Decimal(str(value))


# --- time classification ------------------------------------------------------


class TimeClass(str, Enum):
    DAY = "day"
    NIGHT = "night"  # 10pm-6am, any night (urban or country)
    HOLIDAY = "holiday"  # country only: Sun/public holiday 6am-10pm


class AreaClass(str, Enum):
    URBAN = "urban"
    COUNTRY = "country"


class FaresOrderViolation(ValueError):
    """Raised by validate_against_fares_order when a rank/hail tariff's rates
    exceed the regulated Fares Order reference."""


# --- Tariff -------------------------------------------------------------------


@dataclass(frozen=True)
class Tariff:
    name: str
    area: AreaClass
    flag_fall: Decimal
    peak_charge: Decimal  # 0 where not applicable (country)
    dist_rate_1: Decimal
    dist_rate_2: Decimal
    night_rate_1: Decimal
    night_rate_2: Decimal
    holiday_rate_1: Decimal  # 0 where not applicable (urban)
    holiday_rate_2: Decimal  # 0 where not applicable (urban)
    waiting_rate_per_min: Decimal
    dist_km_threshold: Decimal = Decimal(12)
    speed_threshold_kmh: Decimal = Decimal(26)
    maxi_multiplier: Decimal = Decimal("1.5")
    multi_hire_pct: Decimal = Decimal("0.75")
    psl_amount: Decimal = Decimal("1.32")
    surcharge_pct_cap: Decimal = Decimal("5.0")
    # Order cl 2(f): up to $124.14 (GST inclusive per this Tariff's own
    # convention, matching every other money field here) — only chargeable
    # when soiling means the vehicle can't reasonably be used before
    # cleaning. Enforced by FareEngine.close(), which clamps any requested
    # cleaning_fee to this cap rather than trusting the caller.
    cleaning_fee_cap: Decimal = Decimal("124.14")

    _RATE_FIELDS = (
        "flag_fall",
        "peak_charge",
        "dist_rate_1",
        "dist_rate_2",
        "night_rate_1",
        "night_rate_2",
        "holiday_rate_1",
        "holiday_rate_2",
        "waiting_rate_per_min",
        "cleaning_fee_cap",
    )


# Default tariffs to ship — NSW Point to Point Transport (Fares) Order 2026,
# effective 1 June 2026.
URBAN_TARIFF = Tariff(
    name="urban-2026",
    area=AreaClass.URBAN,
    flag_fall=Decimal("5.17"),
    peak_charge=Decimal("2.65"),
    dist_rate_1=Decimal("2.61"),
    dist_rate_2=Decimal("2.37"),
    night_rate_1=Decimal("3.10"),
    night_rate_2=Decimal("2.82"),
    holiday_rate_1=Decimal(0),
    holiday_rate_2=Decimal(0),
    waiting_rate_per_min=Decimal("1.130"),
)

COUNTRY_TARIFF = Tariff(
    name="country-2026",
    area=AreaClass.COUNTRY,
    flag_fall=Decimal("5.29"),
    peak_charge=Decimal(0),
    dist_rate_1=Decimal("2.49"),
    dist_rate_2=Decimal("3.41"),
    night_rate_1=Decimal("2.97"),
    night_rate_2=Decimal("4.07"),
    holiday_rate_1=Decimal("2.97"),
    holiday_rate_2=Decimal("4.07"),
    waiting_rate_per_min=Decimal("1.081"),
)

AIRPORT_FIXED_FARE_STANDARD = Decimal("60.00")
AIRPORT_FIXED_FARE_MAXI = Decimal("80.00")


def airport_fixed_fare(maxi: bool) -> Decimal:
    """Sydney Airport Fixed Fare Trial — non-booked, Airport Precinct to the
    defined CBD zone. Exactly $60 standard / $80 maxi. Callers must not add PSL,
    tolls, or peak charge on top — only non-cash surcharge and cleaning fee may
    be layered on (enforced by FareEngine.close when fixed_fare is set)."""
    return AIRPORT_FIXED_FARE_MAXI if maxi else AIRPORT_FIXED_FARE_STANDARD


# Negotiated / "Set Price" fixed fare - matches a real competitor taxi-meter
# feature: the driver enters a fixed price before starting the meter. NSW law
# permits this for pre-arranged/negotiated fares (unlike rank/hail tariffs,
# which are Fares Order-capped - see validate_against_fares_order - negotiated
# fares are not rate-capped at all, since the passenger agrees to the exact
# number up front).
#
# 2026-09 product correction: negotiated_total is now ALL-INCLUSIVE, same as
# AIRPORT_FIXED_FARE_* above - "fixed price means, all toll fees everything
# included, driver will straight charge $50 or $60 or whatever they decide"
# (verbatim product requirement). PSL and tolls are NEVER added on top of a
# negotiated total any more - the driver's agreed number is the full amount
# the passenger pays, full stop. This reverses the previous behaviour (kept
# below only as a historical note): an earlier pass modelled this on a
# competitor's own on-screen disclaimer ("this price doesn't include levies
# and/or tolls"), which billed negotiated_total + tolls + psl + extras on
# top - that is now considered wrong and must not be reintroduced.
#
# Absorbing PSL/tolls into the negotiated total does NOT mean they stop being
# real, owed amounts: FareEngine.close's `negotiated_total` branch still
# returns the tariff's real psl_amount and whatever tolls were actually
# crossed in `FareBreakdown.psl`/`.tolls` (never zeroed, unlike
# AIRPORT_FIXED_FARE_* which genuinely has neither) - they are recorded for
# PSL-ledger remittance / audit / toll-reconciliation purposes, simply no
# longer ADDED to `fare_total`/`grand_total`.
#
# 2026-09-06 product ruling (owner, verbatim): "yes card surcharge will be
# absorbed into a fixed price, but not cleaning fee". So the non-cash
# surcharge joins tolls/PSL/extras above - absorbed into the agreed price,
# still computed and RECORDED on `FareBreakdown.surcharge` for accounting
# (the operator needs to know what card fee it absorbed), never added to
# `grand_total`. Only `cleaning_fee` remains additive on top (mirroring the
# fixed_fare/Sydney Airport branch above): a cleaning fee is a post-hoc
# soiling charge that is only ever discovered AFTER the price was agreed, so
# unlike a card surcharge it was never capable of being part of "the fare" in
# the first place.
#
# NEGOTIATED_TOTAL_MIN/MAX are a sanity cap only - guarding against an
# obvious data-entry error (a stray zero, a decimal-point slip), not a
# regulatory rate cap (negotiated fares aren't Fares Order-regulated in the
# first place, per the NSW rule above).
NEGOTIATED_TOTAL_MIN = Decimal("1.00")
NEGOTIATED_TOTAL_MAX = Decimal("500.00")


def validate_negotiated_total(amount: Decimal) -> None:
    """Sanity-checks a driver-entered negotiated/set-price amount. Raises
    ValueError (mirrors FaresOrderViolation's ValueError-subclass convention
    above) if `amount` falls outside the sane [MIN, MAX] band."""
    if not (NEGOTIATED_TOTAL_MIN <= amount <= NEGOTIATED_TOTAL_MAX):
        raise ValueError(
            f"negotiated_total {amount} is outside the sane range "
            f"{NEGOTIATED_TOTAL_MIN}-{NEGOTIATED_TOTAL_MAX}"
        )


def validate_against_fares_order(
    tariff: Tariff, fares_order_reference: Tariff, *, booked: bool = False
) -> None:
    """Rank/hail tariffs must not exceed the regulated Fares Order reference
    rates. Booked tariffs are unregulated and skip this check entirely."""
    if booked:
        return
    for f in Tariff._RATE_FIELDS:
        candidate = getattr(tariff, f)
        cap = getattr(fares_order_reference, f)
        if candidate > cap:
            raise FaresOrderViolation(
                f"Rank/hail tariff '{tariff.name}' field '{f}' = {candidate} exceeds "
                f"Fares Order reference '{fares_order_reference.name}' cap {cap}"
            )


# --- Server-side time_class/is_peak classification --------------------------

# Gazetted NSW public holidays used to independently classify a trip's real
# `occurred_at` timestamp below (resolve_time_class_and_peak) -- see that
# function's own doc for the exact Fares Order rule each provision feeds.
# Ported from the Android app's own `domain/NswPublicHolidays.kt` (the one
# other place in this codebase that already needed this exact same calendar,
# for the exact same Fares Order provisions) rather than re-derived
# independently from scratch, so the two copies can't silently disagree about
# which day is a public holiday. The backend copy is still the one that's
# actually authoritative for billing -- see resolve_time_class_and_peak.
#
# Deliberately EXCLUDES the NSW Bank Holiday (1st Monday in August) -- that
# one is a public-sector-only holiday, not a general public holiday, and does
# not trigger either Fares Order provision below.
#
# 2026 dates are the actual gazetted NSW public holidays. 2027 dates are
# calculated from the standard fixed rules this state has applied consistently
# for years (Easter via the standard computus; King's Birthday = 2nd Monday of
# June; Labour Day = 1st Monday of October; and the Christmas/Boxing Day
# "falls on a weekend -> an extra public holiday is gazetted on the next
# available weekday" convention) rather than transcribed from an official 2027
# gazette, since one does not yet exist this far out.
#
# TODO(risk flag): verify every 2027 date against the real NSW public holidays
# gazette once it is published -- these are a best-effort calculation from the
# standard rules, not an official source, and the government has occasionally
# varied from the mechanical rule for a specific year (e.g. shifting a
# clashing holiday to a different weekday than the "next Monday" default).
NSW_PUBLIC_HOLIDAYS: frozenset[date] = frozenset(
    {
        # --- 2026 (gazetted) ---
        date(2026, 1, 1),  # New Year's Day (Thu)
        date(2026, 1, 26),  # Australia Day (Mon)
        date(2026, 4, 3),  # Good Friday
        date(2026, 4, 4),  # Easter Saturday
        date(2026, 4, 5),  # Easter Sunday
        date(2026, 4, 6),  # Easter Monday
        date(2026, 4, 25),  # Anzac Day (Sat)
        date(2026, 6, 8),  # King's Birthday (2nd Mon June)
        date(2026, 10, 5),  # Labour Day (1st Mon October)
        date(2026, 12, 25),  # Christmas Day (Fri)
        date(2026, 12, 26),  # Boxing Day (Sat)
        date(2026, 12, 28),  # Boxing Day holiday (Boxing Day falls on a Saturday)
        # --- 2027 (calculated from standard rules -- TODO: verify against the gazette) ---
        date(2027, 1, 1),  # New Year's Day (Fri)
        date(2027, 1, 26),  # Australia Day (Tue)
        date(2027, 3, 26),  # Good Friday
        date(2027, 3, 27),  # Easter Saturday
        date(2027, 3, 28),  # Easter Sunday
        date(2027, 3, 29),  # Easter Monday
        date(2027, 4, 25),  # Anzac Day (Sun)
        date(2027, 6, 14),  # King's Birthday (2nd Mon June)
        date(2027, 10, 4),  # Labour Day (1st Mon October)
        date(2027, 12, 25),  # Christmas Day (Sat)
        date(2027, 12, 26),  # Boxing Day (Sun)
        date(2027, 12, 27),  # Christmas Day holiday (Christmas Day falls on a Saturday)
        date(2027, 12, 28),  # Boxing Day holiday (Boxing Day falls on a Sunday)
    }
)


def is_nsw_public_holiday(d: date) -> bool:
    return d in NSW_PUBLIC_HOLIDAYS


def is_day_before_nsw_public_holiday(d: date) -> bool:
    return (d + timedelta(days=1)) in NSW_PUBLIC_HOLIDAYS


# The clock every fare-time classification in this module is made against.
#
# NSW's Fares Order defines the night window (10pm-6am), the Sunday/public-
# holiday country rate and the Friday/Saturday peak hiring charge in NSW LOCAL
# time. Devices sync timestamps as UTC, so classifying on the raw value silently
# shifted every one of those windows by 10-11 hours -- see
# resolve_time_class_and_peak's own doc for the live trip this was found on.
#
# A fixed zone rather than the server's local time: the backend can be deployed
# anywhere, and the fare a NSW passenger pays must not depend on where the
# container happens to run. ZoneInfo handles the AEST/AEDT switch, which matters
# because the offset is +10 for part of the year and +11 for the rest.
NSW_FARE_ZONE = ZoneInfo("Australia/Sydney")


def resolve_time_class_and_peak(*, tariff: Tariff, occurred_at: datetime) -> tuple[TimeClass, bool]:
    """The authoritative, deterministic time_class/is_peak classification for
    a trip commencing at `occurred_at`, on `tariff` -- see this module's own
    docstring ("time_class ... and the peak-hiring flag are fixed at journey
    commencement") and app.services.trips.build_fare_state's doc for why this
    is called exactly ONCE, at trip-creation time (app.api.v1.trips.create_trip
    / sync_trips), never re-derived mid-trip or at display/reconstruction time.
    Deliberately never derived from a client-supplied time_class/is_peak (see
    app.schemas.trips.TripCreate.time_class/is_peak's doc comment): a device
    claiming `is_peak=true` (or a bogus `time_class`) must not be able to
    inflate -- or deflate -- its own fare on its own say-so, exactly the same
    reasoning as `resolve_is_maxi_vehicle` in app.services.trips.

    Pure/deterministic -- no DB, no clock reads, same "PURE module" contract
    this whole file already keeps. Mirrors the Android app's own
    `resolveTimeClassFor`/`resolveIsPeakFor` (domain/FareEngine.kt), rule for
    rule, so the two copies can't independently drift apart on what counts as
    night/peak/holiday, even though this server copy is the one that's
    actually authoritative for billing.

    Night: 10pm-6am, any night, both areas -> TimeClass.NIGHT (matches this
    module's own URBAN_TARIFF/COUNTRY_TARIFF night_rate_1/2 columns).

    Holiday: COUNTRY area only, 6am-10pm on a Sunday OR a gazetted NSW public
    holiday (NSW_PUBLIC_HOLIDAYS) -> TimeClass.HOLIDAY. Urban carries no
    holiday distance rate at all (holiday_rate_1/2 are always 0 on
    URBAN_TARIFF) so urban never resolves to HOLIDAY.

    Everything else -> TimeClass.DAY.

    Peak Time Hiring Charge eligibility: hiring commences 10pm-6am on a
    Friday, a Saturday, OR the night before a gazetted NSW public holiday.
    Urban-only in practice (COUNTRY_TARIFF.peak_charge is 0), but -- like the
    Android original -- this function itself is area-agnostic; the tariff's
    own zero peak charge for country is what makes it a no-op there.

    Classified in NSW LOCAL time (`NSW_FARE_ZONE`), never in whatever zone the
    timestamp happens to arrive in.

    This used to read `occurred_at.hour` exactly as given, on the reasoning
    that it matched `app.services.tariffs.classify_time_of_day`'s existing
    convention. Both were wrong, and the consequence was real: devices sync
    `start_at` as UTC, so a trip commencing 11:08pm Sydney arrived as 13:08Z
    and was classified DAY -- the night rate silently dropped off the server's
    reconstruction. Found 2026-09-07 on a live trip, where the device billed
    $31.28 with the 1.19x night rate and the server recomputed $28.69 without
    it, a 9.03% variance that auto-flagged the trip for dispute review.

    Sydney runs UTC+10/+11, so the error is not an edge case at the boundary:
    the entire NSW night window (22:00-06:00 local) lands in UTC hours
    12:00-20:00 and reads as DAY, while mid-morning local (UTC 00:00-04:00)
    reads as NIGHT. It mis-priced in BOTH directions for most of the day --
    undercharging real night fares and overcharging ordinary morning ones.

    The Fares Order defines the night window in NSW local time, so that is the
    only correct clock here: not UTC, and not the device's own zone (a tablet
    set to the wrong timezone, or carried interstate, must not move the night
    rate). A NAIVE datetime is taken to be NSW local already and used as-is,
    which keeps the existing callers and tests that pass local wall-clock
    times meaning exactly what they meant before.
    """
    if occurred_at.tzinfo is not None:
        occurred_at = occurred_at.astimezone(NSW_FARE_ZONE)
    hour = occurred_at.hour
    is_late_night = hour >= 22 or hour < 6
    occurred_date = occurred_at.date()
    weekday = occurred_at.weekday()  # Monday=0 ... Sunday=6

    if is_late_night:
        time_class = TimeClass.NIGHT
    elif tariff.area == AreaClass.COUNTRY and (weekday == 6 or is_nsw_public_holiday(occurred_date)):
        time_class = TimeClass.HOLIDAY
    else:
        time_class = TimeClass.DAY

    is_fri_sat_or_pre_holiday = weekday in (4, 5) or is_day_before_nsw_public_holiday(occurred_date)
    is_peak = is_late_night and is_fri_sat_or_pre_holiday

    return time_class, is_peak


# --- Journey state --------------------------------------------------------------


@dataclass
class FareState:
    tariff: Tariff
    time_class: TimeClass = TimeClass.DAY
    is_peak: bool = False  # urban-only peak hiring charge flag, fixed at commencement

    # Maxi-cab rate eligibility (Order cl 2(d)) — four raw inputs feeding one
    # DERIVED, non-settable `maxi_applied` property below. Never set
    # maxi_applied directly; there is no field for it, by design, so nothing
    # can bill the 150% rate without genuinely satisfying the legal
    # condition. `is_maxi_vehicle` must be resolved server-side from the
    # real `Vehicle.vehicle_class` (see app.services.trips) — never trusted
    # from a raw client-supplied boolean.
    is_maxi_vehicle: bool = False
    passenger_count: int = 1
    wheelchair_hiring: bool = False
    airport_rank_requested_maxi: bool = False

    hired: bool = True

    cumulative_distance_km: Decimal = field(default_factory=lambda: Decimal(0))
    accrued_distance_charge: Decimal = field(default_factory=lambda: Decimal(0))
    accrued_waiting_charge: Decimal = field(default_factory=lambda: Decimal(0))
    last_mode: str | None = None  # "distance" | "waiting" — introspection only

    tolls: Decimal = field(default_factory=lambda: Decimal(0))
    extras: Decimal = field(default_factory=lambda: Decimal(0))

    # Sydney Airport Fixed Fare Trial: when set, close() returns exactly this
    # amount plus ONLY a cleaning fee — no PSL, tolls, peak, or (2026-09
    # consistency call, see close()'s own comment) non-cash surcharge either;
    # the surcharge is still computed/recorded, just never added.
    fixed_fare: Decimal | None = None

    # Negotiated / "Set Price" fixed fare (see validate_negotiated_total /
    # NEGOTIATED_TOTAL_MIN/MAX above): when set, close() charges EXACTLY this
    # amount plus a cleaning fee, full stop - all-inclusive, same as
    # fixed_fare above. PSL, tolls, and extras still accrue into the returned
    # FareBreakdown (for ledger/audit/remittance purposes - the levy/toll
    # obligation is real and still owed) and the non-cash surcharge is still
    # computed and recorded too, but NONE of the four are added on top of
    # what the passenger is billed (2026-09 product correction/ruling - see
    # this module's negotiated_total docstring above for the full rationale).
    # Only a cleaning fee is ever additive on top of this amount.
    negotiated_total: Decimal | None = None

    @property
    def maxi_applied(self) -> bool:
        """The single source of truth for whether the 150% maxi rate is
        legally active right now — a wheelchair hiring always overrides it
        off (cl 2(d)(ii)); otherwise it's on for 5+ passengers or a maxi
        specifically requested at a Sydney Airport rank, but only on a
        vehicle that is actually a maxi-cab in the first place."""
        return self.is_maxi_vehicle and not self.wheelchair_hiring and (
            self.passenger_count >= 5 or self.airport_rank_requested_maxi
        )


@dataclass
class FareBreakdown:
    flag_fall: Decimal
    peak_charge: Decimal
    distance_charge: Decimal
    waiting_charge: Decimal
    tolls: Decimal
    psl: Decimal
    cleaning_fee: Decimal
    extras: Decimal
    maxi_applied: bool
    # The maxi-rate uplift as its own line: fare_total minus every other
    # component, so the itemised figures in this breakdown always SUM to
    # fare_total exactly. Zero unless `maxi_applied`. Derived rather than
    # computed as `base * (multiplier - 1)` on purpose -- see close()'s
    # reconciliation note. A receipt whose lines do not add up to its total is
    # not a valid tax invoice, and the UI used to re-derive this figure itself
    # and round it independently, which is how it stopped adding up.
    maxi_uplift: Decimal
    fare_total: Decimal  # after maxi multiplier, before non-cash surcharge
    surcharge: Decimal
    grand_total: Decimal  # fare_total + surcharge — amount actually charged
    gst_component: Decimal  # grand_total / 11, half-up to cents


class FareEngine:
    """Stateless engine — all mutable state lives in the `FareState` passed in."""

    def _rate_1(self, state: FareState) -> Decimal:
        if state.time_class == TimeClass.NIGHT:
            return state.tariff.night_rate_1
        if state.time_class == TimeClass.HOLIDAY:
            return state.tariff.holiday_rate_1
        return state.tariff.dist_rate_1

    def _rate_2(self, state: FareState) -> Decimal:
        if state.time_class == TimeClass.NIGHT:
            return state.tariff.night_rate_2
        if state.time_class == TimeClass.HOLIDAY:
            return state.tariff.holiday_rate_2
        return state.tariff.dist_rate_2

    def tick(
        self,
        state: FareState,
        speed_kmh,
        distance_delta_km,
        elapsed_seconds,
    ) -> FareState:
        """Advances the meter by one increment. Exactly one of distance-rate or
        waiting-rate accrues per tick, switched on `speed_kmh` vs
        `tariff.speed_threshold_kmh`. No-op while not hired."""
        if not state.hired:
            return state

        speed_kmh = _d(speed_kmh)
        distance_delta_km = _d(distance_delta_km)
        elapsed_seconds = _d(elapsed_seconds)

        if speed_kmh >= state.tariff.speed_threshold_kmh:
            # --- distance mode: split the delta across the 12km band boundary ---
            state.last_mode = "distance"
            if distance_delta_km <= 0:
                return state
            remaining = distance_delta_km
            cum = state.cumulative_distance_km
            threshold = state.tariff.dist_km_threshold
            charge = Decimal(0)

            if cum < threshold:
                portion1 = min(remaining, threshold - cum)
                charge += portion1 * self._rate_1(state)
                remaining -= portion1
                cum += portion1

            if remaining > 0:
                charge += remaining * self._rate_2(state)
                cum += remaining

            state.cumulative_distance_km = cum
            state.accrued_distance_charge += charge
        else:
            # --- waiting mode: time-based, distance covered is negligible but
            # still folded into cumulative distance so the band tracks reality ---
            state.last_mode = "waiting"
            minutes = elapsed_seconds / Decimal(60)
            state.accrued_waiting_charge += minutes * state.tariff.waiting_rate_per_min
            state.cumulative_distance_km += distance_delta_km

        return state

    def close(
        self,
        state: FareState,
        *,
        payment_method: str = "cash",
        surcharge_pct: Decimal | None = None,
        cleaning_fee: Decimal = Decimal(0),
        include_psl: bool = False,
    ) -> FareBreakdown:
        """Assembles the final (or checkpoint — this method does not mutate
        `state`, so it may safely be called mid-trip e.g. at each hirer's
        drop-off in a multiple-hiring scenario) fare breakdown."""

        # Order cl 2(f): clamp any requested cleaning fee to the tariff's cap
        # regardless of what the caller asked for — the enforced maximum,
        # not merely a display-layer suggestion.
        cleaning_fee = min(cleaning_fee, state.tariff.cleaning_fee_cap)

        if state.fixed_fare is not None:
            # Sydney Airport Fixed Fare Trial: no PSL, tolls, or peak allowed on
            # top — only a cleaning fee. The non-cash surcharge is computed
            # and RECORDED below (never billed) — see the 2026-09 consistency
            # note above `FareEngine.close`'s `negotiated_total` branch: this
            # fixed fare has the exact same "the price is the price"
            # character as a negotiated total, so it now absorbs the card
            # surcharge the same way, rather than diverging from it.
            fare_total = state.fixed_fare
            surcharge = Decimal(0)
            if payment_method == "card":
                pct = min(
                    surcharge_pct if surcharge_pct is not None else state.tariff.surcharge_pct_cap,
                    state.tariff.surcharge_pct_cap,
                )
                surcharge = round_half_up(fare_total * pct / Decimal(100))
            grand_total = fare_total + cleaning_fee  # surcharge absorbed, never added
            gst_component = round_half_up(grand_total / Decimal(11))
            return FareBreakdown(
                flag_fall=Decimal(0),
                peak_charge=Decimal(0),
                distance_charge=Decimal(0),
                waiting_charge=Decimal(0),
                tolls=Decimal(0),
                psl=Decimal(0),
                cleaning_fee=cleaning_fee,
                extras=Decimal(0),
                maxi_applied=state.maxi_applied,
                # An all-inclusive agreed price is not itemised, so there is no
                # uplift line to reconcile -- fare_total IS the agreed number.
                maxi_uplift=Decimal(0),
                fare_total=fare_total,
                surcharge=surcharge,
                grand_total=grand_total,
                gst_component=gst_component,
            )

        if state.negotiated_total is not None:
            # Negotiated / "Set Price" fixed fare: negotiated_total REPLACES
            # the flag/peak/distance/waiting components combined — reported
            # as 0 individually below, same convention fixed_fare uses. Not
            # run through the maxi multiplier: the negotiated number is the
            # number the driver and passenger already agreed to.
            #
            # 2026-09 product correction: negotiated_total is now
            # ALL-INCLUSIVE — tolls, PSL, and extras are still recorded below
            # (state.tolls / psl / state.extras still flow into the returned
            # FareBreakdown, for PSL-ledger remittance and toll-audit
            # purposes — the obligation is real even though it isn't billed)
            # but are deliberately EXCLUDED from `subtotal`/`fare_total` — see
            # FareState.negotiated_total's docstring.
            #
            # 2026-09 product ruling (owner, verbatim): "yes card surcharge
            # will be absorbed into a fixed price, but not cleaning fee" — a
            # cleaning fee is a post-hoc soiling charge discovered only AFTER
            # the price was agreed, so unlike tolls/PSL/extras/surcharge it is
            # never part of "the fare" and stays additive on top, exactly like
            # the fixed_fare/Sydney Airport branch above. The non-cash
            # surcharge itself is still computed and returned below — RECORDED
            # for accounting (the operator needs to know what card fee it
            # absorbed) but never added to grand_total.
            flag_fall = Decimal(0)
            peak_charge = Decimal(0)
            distance_charge_amount = Decimal(0)
            waiting_charge_amount = Decimal(0)
            psl = state.tariff.psl_amount if include_psl else Decimal(0)

            maxi_applied = state.maxi_applied
            fare_total = round_down(state.negotiated_total)

            surcharge = Decimal(0)
            if payment_method == "card":
                pct = min(
                    surcharge_pct if surcharge_pct is not None else state.tariff.surcharge_pct_cap,
                    state.tariff.surcharge_pct_cap,
                )
                surcharge = round_half_up(fare_total * pct / Decimal(100))

            grand_total = fare_total + cleaning_fee  # surcharge absorbed, never added
            gst_component = round_half_up(grand_total / Decimal(11))

            return FareBreakdown(
                flag_fall=flag_fall,
                peak_charge=peak_charge,
                distance_charge=distance_charge_amount,
                waiting_charge=waiting_charge_amount,
                tolls=state.tolls,
                psl=psl,
                cleaning_fee=cleaning_fee,
                extras=state.extras,
                maxi_applied=maxi_applied,
                maxi_uplift=Decimal(0),  # see the fixed-fare branch above
                fare_total=fare_total,
                surcharge=surcharge,
                grand_total=grand_total,
                gst_component=gst_component,
            )

        flag_fall = state.tariff.flag_fall
        peak_charge = state.tariff.peak_charge if state.is_peak else Decimal(0)
        psl = state.tariff.psl_amount if include_psl else Decimal(0)

        # Order cl 2(d): the 150% maxi rate applies only to "the fare" — flag
        # fall + peak charge + distance + waiting — never to tolls, PSL,
        # extras, or the cleaning fee, which are added on top unmultiplied.
        metered_fare = (
            flag_fall
            + peak_charge
            + state.accrued_distance_charge
            + state.accrued_waiting_charge
        )

        maxi_applied = state.maxi_applied
        if maxi_applied:
            metered_fare = metered_fare * state.tariff.maxi_multiplier

        subtotal = metered_fare + state.tolls + psl + state.extras + cleaning_fee

        # Unchanged, and deliberately still built from the RAW accruals: cl 4(a)
        # requires the fare charged to round DOWN, so the components must not be
        # rounded up first (a 0.5c tail in waiting time would otherwise raise the
        # regulated total by a cent). test_o pins this exact boundary.
        fare_total = round_down(subtotal)

        # --- reporting: make the itemisation reconcile to fare_total ----------
        #
        # A receipt whose lines do not add up to its total is not a valid tax
        # invoice, and this one is printed for passengers. A live trip on the
        # tablet printed $5.00 + $0.76 + $3.86 + $1.32 above a TOTAL of $10.93 --
        # the lines add to $10.94. The sub-cent tails were in the total (which
        # rounds down) but not in the lines, which each rounded half-up on their
        # own; nothing reconciled them.
        #
        # Nothing charged changes here. The components are rounded DOWN, the same
        # direction as the total, so no line can ever overstate what it
        # contributed, and the <=1c carry left over by the total's own rounding
        # is given to the waiting line -- the last metered component to accrue,
        # and the one already expressed in whole-cent-per-minute terms.
        #
        # This is invisible on the server at runtime, because _state_from_trip
        # rebuilds a closing state from the already-rounded persisted
        # trip.dist_amount/wait_amount, so every figure here is already cents.
        # The DEVICE recomputes both accruals from raw metres and seconds, so it
        # was the only side carrying sub-cent tails -- and the only side printing
        # receipts. The rule lives here so both sides share one definition.
        add_ons = state.tolls + psl + state.extras + cleaning_fee
        metered_base_billed = round_down(
            flag_fall + peak_charge + state.accrued_distance_charge + state.accrued_waiting_charge
        )
        distance_charge = round_down(state.accrued_distance_charge)
        waiting_charge = metered_base_billed - flag_fall - peak_charge - distance_charge

        # The multiplier's whole contribution, as its own line. Derived, not
        # computed as `base * (multiplier - 1)` and rounded separately: that lands
        # a cent away from what fare_total actually contains (base 10.01 x 1.5
        # rounds down to 15.01, while a separately rounded 5.005 uplift shows 5.01
        # and the lines read 15.02). Zero when the multiplier is 1, by the same
        # arithmetic rather than by a special case.
        maxi_uplift = (fare_total - add_ons) - metered_base_billed

        surcharge = Decimal(0)
        if payment_method == "card":
            pct = min(
                surcharge_pct if surcharge_pct is not None else state.tariff.surcharge_pct_cap,
                state.tariff.surcharge_pct_cap,
            )
            surcharge = round_half_up(fare_total * pct / Decimal(100))

        grand_total = fare_total + surcharge
        gst_component = round_half_up(grand_total / Decimal(11))

        return FareBreakdown(
            flag_fall=flag_fall,
            peak_charge=peak_charge,
            distance_charge=distance_charge,
            waiting_charge=waiting_charge,
            tolls=state.tolls,
            psl=psl,
            cleaning_fee=cleaning_fee,
            extras=state.extras,
            maxi_applied=maxi_applied,
            maxi_uplift=maxi_uplift,
            fare_total=fare_total,
            surcharge=surcharge,
            grand_total=grand_total,
            gst_component=gst_component,
        )

    def multi_hire_amount_owed(self, breakdown: FareBreakdown, tariff: Tariff) -> Decimal:
        """75% of the metered fare (fare_total, i.e. before non-cash surcharge)
        demanded from EACH hirer — the meter runs once, this is a pure function
        of a checkpoint breakdown, not a mutation of shared state."""
        return round_half_up(breakdown.fare_total * tariff.multi_hire_pct)
