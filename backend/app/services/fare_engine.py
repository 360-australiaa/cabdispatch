"""NSW tariff-switching fare engine.

PURE module — no DB, no FastAPI imports. Implements the NSW Fares Order 2025
(no.2), effective 3 Nov 2025, GST-inclusive rates transcribed exactly from the
product spec (do not "correct" these from memory):

| Component                                   | Urban          | Country        |
|----------------------------------------------|----------------|----------------|
| Hiring Charge (flag fall)                    | $5.00          | $5.11          |
| Peak Time Hiring Charge (urban only)          | $2.56          | n/a            |
| Distance Rate >=26km/h, first 12km            | $2.52/km       | $2.41/km       |
| Distance Rate >=26km/h, beyond 12km           | $2.29/km       | $3.30/km       |
| Night Distance Rate (10pm-6am any night), <12km | $3.00/km     | $2.87/km       |
| Night Distance Rate, beyond 12km              | $2.73/km       | $3.93/km       |
| Holiday Distance Rate (country only), <12km   | n/a            | $2.87/km       |
| Holiday Distance Rate, beyond 12km            | n/a            | $3.93/km       |
| Waiting Time <26km/h                          | 109.2 c/min    | 104.5 c/min    |

Fare engine rule: at any instant exactly ONE of Distance Rate or Waiting Time
accrues, switched by the 26km/h threshold (never both, never neither, while
HIRED). `time_class` (day/night/holiday) and the peak-hiring flag are fixed at
journey commencement and do not change mid-trip even if the clock crosses a
boundary. The first-12km/beyond-12km distance band applies to cumulative trip
distance.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum

# --- rounding helpers --------------------------------------------------------

CENT = Decimal("0.01")


def round_half_up(amount: Decimal) -> Decimal:
    """Quantize to cents, half-up (<0.5c down, >=0.5c up) — the rounding rule
    the Fares Order mandates for the non-cash surcharge, applied generally here
    for all displayed money amounts."""
    return amount.quantize(CENT, rounding=ROUND_HALF_UP)


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
    )


# Default tariffs to ship — NSW Fares Order 2025 (no.2), effective 3 Nov 2025.
URBAN_TARIFF = Tariff(
    name="urban-2025-no2",
    area=AreaClass.URBAN,
    flag_fall=Decimal("5.00"),
    peak_charge=Decimal("2.56"),
    dist_rate_1=Decimal("2.52"),
    dist_rate_2=Decimal("2.29"),
    night_rate_1=Decimal("3.00"),
    night_rate_2=Decimal("2.73"),
    holiday_rate_1=Decimal(0),
    holiday_rate_2=Decimal(0),
    waiting_rate_per_min=Decimal("1.092"),
)

COUNTRY_TARIFF = Tariff(
    name="country-2025-no2",
    area=AreaClass.COUNTRY,
    flag_fall=Decimal("5.11"),
    peak_charge=Decimal(0),
    dist_rate_1=Decimal("2.41"),
    dist_rate_2=Decimal("3.30"),
    night_rate_1=Decimal("2.87"),
    night_rate_2=Decimal("3.93"),
    holiday_rate_1=Decimal("2.87"),
    holiday_rate_2=Decimal("3.93"),
    waiting_rate_per_min=Decimal("1.045"),
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
# number up front). The competitor's own on-screen disclaimer reads "this
# price doesn't include levies and/or tolls" - i.e. PSL and tolls still accrue
# and add ON TOP of the negotiated amount, unlike AIRPORT_FIXED_FARE_* above
# which excludes them entirely. See FareEngine.close's `negotiated_total`
# branch.
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


# --- Journey state --------------------------------------------------------------


@dataclass
class FareState:
    tariff: Tariff
    time_class: TimeClass = TimeClass.DAY
    is_peak: bool = False  # urban-only peak hiring charge flag, fixed at commencement
    maxi: bool = False
    hired: bool = True

    cumulative_distance_km: Decimal = field(default_factory=lambda: Decimal(0))
    accrued_distance_charge: Decimal = field(default_factory=lambda: Decimal(0))
    accrued_waiting_charge: Decimal = field(default_factory=lambda: Decimal(0))
    last_mode: str | None = None  # "distance" | "waiting" — introspection only

    tolls: Decimal = field(default_factory=lambda: Decimal(0))
    extras: Decimal = field(default_factory=lambda: Decimal(0))

    # Sydney Airport Fixed Fare Trial: when set, close() returns exactly this
    # amount (60/80) plus ONLY surcharge/cleaning fee — no PSL, tolls, or peak.
    fixed_fare: Decimal | None = None

    # Negotiated / "Set Price" fixed fare (see validate_negotiated_total /
    # NEGOTIATED_TOTAL_MIN/MAX above): when set, close() charges exactly this
    # amount for the base/flag/distance/time components combined, but — unlike
    # fixed_fare above — PSL and tolls still accrue and add ON TOP, exactly as
    # they do for a normal metered trip (per the competitor's own "doesn't
    # include levies and/or tolls" on-screen disclaimer this mirrors).
    negotiated_total: Decimal | None = None


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

        if state.fixed_fare is not None:
            # Sydney Airport Fixed Fare Trial: no PSL, tolls, or peak allowed on
            # top — only the non-cash surcharge and a cleaning fee.
            fare_total = state.fixed_fare
            surcharge = Decimal(0)
            if payment_method == "card":
                pct = min(
                    surcharge_pct if surcharge_pct is not None else state.tariff.surcharge_pct_cap,
                    state.tariff.surcharge_pct_cap,
                )
                surcharge = round_half_up(fare_total * pct / Decimal(100))
            grand_total = fare_total + surcharge + cleaning_fee
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
                maxi_applied=state.maxi,
                fare_total=fare_total,
                surcharge=surcharge,
                grand_total=grand_total,
                gst_component=gst_component,
            )

        if state.negotiated_total is not None:
            # Negotiated / "Set Price" fixed fare: negotiated_total REPLACES
            # the flag/peak/distance/waiting components combined — reported
            # as 0 individually below, same convention fixed_fare uses — but
            # PSL and tolls still accrue and add on top, exactly as they do
            # for a normal metered trip (see FareState.negotiated_total's
            # docstring). Not run through the maxi multiplier: the negotiated
            # number is the number the driver and passenger already agreed to.
            flag_fall = Decimal(0)
            peak_charge = Decimal(0)
            distance_charge_amount = Decimal(0)
            waiting_charge_amount = Decimal(0)
            psl = state.tariff.psl_amount if include_psl else Decimal(0)

            subtotal = (
                state.negotiated_total
                + state.tolls
                + psl
                + state.extras
                + cleaning_fee
            )

            maxi_applied = state.maxi
            fare_total = round_half_up(subtotal)

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
                distance_charge=distance_charge_amount,
                waiting_charge=waiting_charge_amount,
                tolls=state.tolls,
                psl=psl,
                cleaning_fee=cleaning_fee,
                extras=state.extras,
                maxi_applied=maxi_applied,
                fare_total=fare_total,
                surcharge=surcharge,
                grand_total=grand_total,
                gst_component=gst_component,
            )

        flag_fall = state.tariff.flag_fall
        peak_charge = state.tariff.peak_charge if state.is_peak else Decimal(0)
        psl = state.tariff.psl_amount if include_psl else Decimal(0)

        subtotal = (
            flag_fall
            + peak_charge
            + state.accrued_distance_charge
            + state.accrued_waiting_charge
            + state.tolls
            + psl
            + state.extras
            + cleaning_fee
        )

        maxi_applied = state.maxi
        if maxi_applied:
            subtotal = subtotal * state.tariff.maxi_multiplier

        fare_total = round_half_up(subtotal)

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
            distance_charge=round_half_up(state.accrued_distance_charge),
            waiting_charge=round_half_up(state.accrued_waiting_charge),
            tolls=state.tolls,
            psl=psl,
            cleaning_fee=cleaning_fee,
            extras=state.extras,
            maxi_applied=maxi_applied,
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
