"""Golden compliance-evidence vectors for the NSW tariff-switching fare engine.

Every expected Decimal total below is computed BY HAND from the NSW Point to
Point Transport (Fares) Order 2026 rate table in app/services/fare_engine.py's
module docstring — do not "fix" a failing assertion by copying the engine's
output back in; if a test fails, the engine (or the hand calculation in the
comment) is wrong and must be fixed.
"""
from datetime import UTC, datetime
from decimal import Decimal

import pytest

from app.services.fare_engine import (
    COUNTRY_TARIFF,
    URBAN_TARIFF,
    AreaClass,
    FareEngine,
    FaresOrderViolation,
    FareState,
    Tariff,
    TimeClass,
    airport_fixed_fare,
    resolve_time_class_and_peak,
    validate_against_fares_order,
)


def test_a_short_urban_day_trip_all_moving():
    """3km, all >=26km/h, urban day, cash. Within first-12km band throughout.

    distance_charge = 3 * 2.61            = 7.83
    subtotal        = flag_fall 5.17 + 7.83 = 13.00
    fare_total      = round_down(13.00)   = 13.00 (already exact cents)
    gst_component   = 13.00 / 11          = 1.18181... -> 1.18
    """
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.DAY)

    state = engine.tick(state, speed_kmh=40, distance_delta_km=3, elapsed_seconds=270)

    breakdown = engine.close(state)

    assert breakdown.distance_charge == Decimal("7.83")
    assert breakdown.fare_total == Decimal("13.00")
    assert breakdown.surcharge == Decimal(0)
    assert breakdown.grand_total == Decimal("13.00")
    assert breakdown.gst_component == Decimal("1.18")


def test_b_urban_night_trip_over_12km_mixes_both_bands():
    """16km in one continuous fast segment, urban night (10pm-6am), peak hiring.

    first 12km  @ night_rate_1 3.10 = 37.20
    next   4km  @ night_rate_2 2.82 = 11.28
    distance_charge = 48.48
    subtotal = flag_fall 5.17 + peak 2.65 + 48.48 = 56.30
    fare_total = round_down(56.30) = 56.30 (already exact cents)
    gst_component = 56.30 / 11 = 5.11818... -> 5.12
    """
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.NIGHT, is_peak=True)

    state = engine.tick(state, speed_kmh=50, distance_delta_km=16, elapsed_seconds=900)

    assert state.cumulative_distance_km == Decimal(16)
    breakdown = engine.close(state)

    assert breakdown.distance_charge == Decimal("48.48")
    assert breakdown.fare_total == Decimal("56.30")
    assert breakdown.gst_component == Decimal("5.12")


def test_c_country_holiday_trip():
    """10km, country, Sunday daytime (holiday time_class), within first-12km band.

    distance_charge = 10 * holiday_rate_1 2.97 = 29.70
    subtotal = flag_fall 5.29 + 29.70 = 34.99
    fare_total = round_down(34.99) = 34.99 (already exact cents)
    gst_component = 34.99 / 11 = 3.18090... -> 3.18
    """
    engine = FareEngine()
    state = FareState(tariff=COUNTRY_TARIFF, time_class=TimeClass.HOLIDAY)

    state = engine.tick(state, speed_kmh=45, distance_delta_km=10, elapsed_seconds=800)

    breakdown = engine.close(state)

    assert breakdown.distance_charge == Decimal("29.70")
    assert breakdown.fare_total == Decimal("34.99")
    assert breakdown.gst_component == Decimal("3.18")


def test_d_waiting_heavy_cbd_crawl():
    """Mostly <26km/h (waiting), urban day, one short fast hop mixed in.

    waiting: 10 min @ 1.130/min = 11.30
    waiting:  5 min @ 1.130/min =  5.65
    waiting_charge total = 16.95
    distance: 1km @ dist_rate_1 2.61 = 2.61
    subtotal = flag_fall 5.17 + 2.61 + 16.95 = 24.73
    fare_total = round_down(24.73) = 24.73 (already exact cents)
    gst_component = 24.73 / 11 = 2.24818... -> 2.25
    """
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.DAY)

    # Waiting mode: speed below the 26km/h threshold -> time-based charge only.
    state = engine.tick(state, speed_kmh=8, distance_delta_km=0.05, elapsed_seconds=600)
    assert state.last_mode == "waiting"
    state = engine.tick(state, speed_kmh=5, distance_delta_km=0.02, elapsed_seconds=300)
    assert state.last_mode == "waiting"

    # A brief fast hop: distance mode kicks in, waiting stops accruing.
    state = engine.tick(state, speed_kmh=40, distance_delta_km=1, elapsed_seconds=90)
    assert state.last_mode == "distance"

    breakdown = engine.close(state)

    assert breakdown.waiting_charge == Decimal("16.95")
    assert breakdown.distance_charge == Decimal("2.61")
    assert breakdown.fare_total == Decimal("24.73")
    assert breakdown.gst_component == Decimal("2.25")


def test_e_multiple_hiring_each_hirer_owes_75pct_at_their_drop():
    """2 hirers, meter runs ONCE (single continuous state, never reset).

    Hirer 1 drop checkpoint:
      distance_charge so far = 2km * 2.61 = 5.22
      fare_total_1 = flag_fall 5.17 + 5.22 = 10.39
      owed_1 = 10.39 * 0.75 = 7.7925 -> round_half_up -> 7.79

    Hirer 2 (final) drop:
      + 3km * 2.61 = 7.83  ->  distance_charge = 5.22 + 7.83 = 13.05
      fare_total_2 = 5.17 + 13.05 = 18.22
      owed_2 = 18.22 * 0.75 = 13.665 -> round_half_up (exact 0.5c boundary) -> 13.67
    """
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.DAY)

    state = engine.tick(state, speed_kmh=40, distance_delta_km=2, elapsed_seconds=180)
    checkpoint_1 = engine.close(state)  # non-mutating: safe to checkpoint mid-trip
    owed_1 = engine.multi_hire_amount_owed(checkpoint_1, URBAN_TARIFF)

    assert checkpoint_1.fare_total == Decimal("10.39")
    assert owed_1 == Decimal("7.79")

    # Meter keeps running on the SAME state object for hirer 2.
    state = engine.tick(state, speed_kmh=40, distance_delta_km=3, elapsed_seconds=270)
    checkpoint_2 = engine.close(state)
    owed_2 = engine.multi_hire_amount_owed(checkpoint_2, URBAN_TARIFF)

    assert checkpoint_2.fare_total == Decimal("18.22")
    assert owed_2 == Decimal("13.67")


def test_f_maxi_cab_5_passengers_applies_150pct_to_metered_fare_only():
    """4km urban day, a real maxi-cab (is_maxi_vehicle) carrying 5 passengers —
    both conditions genuinely satisfied, not a raw settable flag (Order cl 2(d)).

    metered_fare = flag_fall 5.17 + (4 * 2.61 = 10.44) = 15.61
    metered_fare *= 1.5 (maxi_applied)                  = 23.415
    subtotal (no tolls/psl/extras/cleaning)             = 23.415
    fare_total = round_down(23.415)                     = 23.41  <- NOT 23.42;
        proves the fare total rounds DOWN (Act s76(5)/(6)), never half-up, even
        though 23.415 sits exactly on the classic half-up rounding boundary.
    gst_component (cash, no surcharge, grand_total = fare_total)
        = 23.41 / 11 = 2.12818... -> 2.13
    """
    engine = FareEngine()
    state = FareState(
        tariff=URBAN_TARIFF,
        time_class=TimeClass.DAY,
        is_maxi_vehicle=True,
        passenger_count=5,
    )

    state = engine.tick(state, speed_kmh=40, distance_delta_km=4, elapsed_seconds=360)

    breakdown = engine.close(state)

    assert breakdown.maxi_applied is True
    assert breakdown.fare_total == Decimal("23.41")
    assert breakdown.gst_component == Decimal("2.13")


def test_f2_maxi_vehicle_with_4_passengers_is_not_eligible():
    """Same maxi-cab, same 4km trip, but only 4 passengers — a hard cutoff at 5,
    not a sliding scale. Standard (non-maxi) fare applies.

    fare_total = flag_fall 5.17 + (4 * 2.61 = 10.44) = 15.61 (no multiplier)
    """
    engine = FareEngine()
    state = FareState(
        tariff=URBAN_TARIFF,
        time_class=TimeClass.DAY,
        is_maxi_vehicle=True,
        passenger_count=4,
    )

    state = engine.tick(state, speed_kmh=40, distance_delta_km=4, elapsed_seconds=360)
    breakdown = engine.close(state)

    assert breakdown.maxi_applied is False
    assert breakdown.fare_total == Decimal("15.61")


def test_f3_wheelchair_hiring_overrides_maxi_rate_off_even_with_6_passengers():
    """Order cl 2(d)(ii)'s carve-out: a wheelchair hiring never gets the maxi
    rate, regardless of passenger count or vehicle class."""
    engine = FareEngine()
    state = FareState(
        tariff=URBAN_TARIFF,
        time_class=TimeClass.DAY,
        is_maxi_vehicle=True,
        passenger_count=6,
        wheelchair_hiring=True,
    )

    state = engine.tick(state, speed_kmh=40, distance_delta_km=4, elapsed_seconds=360)
    breakdown = engine.close(state)

    assert breakdown.maxi_applied is False
    assert breakdown.fare_total == Decimal("15.61")


def test_f4_airport_rank_requested_maxi_applies_independent_of_passenger_count():
    """A maxi specifically requested at a Sydney Airport rank triggers the
    150% rate even with just 1 passenger — the airport-request limb is
    independent of the passenger-count limb (Order cl 2(d)(i) vs (ii))."""
    engine = FareEngine()
    state = FareState(
        tariff=URBAN_TARIFF,
        time_class=TimeClass.DAY,
        is_maxi_vehicle=True,
        passenger_count=1,
        airport_rank_requested_maxi=True,
    )

    state = engine.tick(state, speed_kmh=40, distance_delta_km=4, elapsed_seconds=360)
    breakdown = engine.close(state)

    assert breakdown.maxi_applied is True
    assert breakdown.fare_total == Decimal("23.41")


def test_f5_non_maxi_vehicle_never_gets_the_rate_regardless_of_passenger_count():
    """is_maxi_vehicle gates everything — a standard sedan can't charge maxi
    rates just because 6 people somehow crammed in."""
    engine = FareEngine()
    state = FareState(
        tariff=URBAN_TARIFF,
        time_class=TimeClass.DAY,
        is_maxi_vehicle=False,
        passenger_count=6,
    )

    state = engine.tick(state, speed_kmh=40, distance_delta_km=4, elapsed_seconds=360)
    breakdown = engine.close(state)

    assert breakdown.maxi_applied is False
    assert breakdown.fare_total == Decimal("15.61")


def test_g_sydney_airport_fixed_fare_standard_and_maxi():
    """Fixed $60/$80 (unchanged by the 2026 Order) — no PSL, tolls, or peak may
    be added on top, only non-cash surcharge and cleaning fee. We deliberately
    set tolls/PSL/peak on the state to prove close() ignores them entirely for
    a fixed-fare trip."""
    engine = FareEngine()

    assert airport_fixed_fare(maxi=False) == Decimal("60.00")
    assert airport_fixed_fare(maxi=True) == Decimal("80.00")

    standard_state = FareState(
        tariff=URBAN_TARIFF,
        is_peak=True,  # should be ignored
        fixed_fare=airport_fixed_fare(maxi=False),
    )
    standard_state.tolls = Decimal("15.00")  # should be ignored

    breakdown = engine.close(standard_state, include_psl=True)  # PSL request should be ignored

    assert breakdown.fare_total == Decimal("60.00")
    assert breakdown.tolls == Decimal(0)
    assert breakdown.psl == Decimal(0)
    assert breakdown.peak_charge == Decimal(0)
    assert breakdown.grand_total == Decimal("60.00")

    maxi_state = FareState(
        tariff=URBAN_TARIFF,
        is_maxi_vehicle=True,
        passenger_count=5,
        fixed_fare=airport_fixed_fare(maxi=True),
    )
    maxi_breakdown = engine.close(maxi_state)

    assert maxi_breakdown.fare_total == Decimal("80.00")
    assert maxi_breakdown.grand_total == Decimal("80.00")


def test_h_non_cash_surcharge_rounds_half_up_at_exact_boundary():
    """fare_total = 200.50 (flag_fall 5.17 + accrued_distance_charge 195.33),
    custom surcharge_pct = 1.0%.

    raw surcharge = 200.50 * 1.0 / 100 = 2.005 exactly -> the 0.5c boundary.
    Fares Order rule (cl 4(a)): <0.5c rounds down, >=0.5c rounds up => rounds
    UP to 2.01. (This is the ONE money figure in the whole engine that still
    uses round_half_up, not round_down — the surcharge keeps the Order's own
    explicit rule, unlike fare_total. Bankers'/round-half-even rounding would
    instead give 2.00 here — this test exists specifically to prove we are
    doing neither round-down nor round-half-even for the surcharge.)
    """
    engine = FareEngine()
    state = FareState(
        tariff=URBAN_TARIFF,
        time_class=TimeClass.DAY,
        accrued_distance_charge=Decimal("195.33"),  # + flag_fall 5.17 = 200.50
    )

    breakdown = engine.close(state, payment_method="card", surcharge_pct=Decimal("1.0"))

    assert breakdown.fare_total == Decimal("200.50")
    assert breakdown.surcharge == Decimal("2.01")
    assert breakdown.grand_total == Decimal("202.51")


def test_h2_cleaning_fee_is_clamped_to_the_tariffs_cap_never_trusted_raw():
    """Order cl 2(f): up to $124.14. A driver/device requesting $200 must be
    silently clamped to the cap, not billed at face value."""
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.DAY)

    breakdown = engine.close(state, cleaning_fee=Decimal("200.00"))

    assert breakdown.cleaning_fee == URBAN_TARIFF.cleaning_fee_cap
    assert breakdown.cleaning_fee == Decimal("124.14")
    # flag_fall 5.17 (no distance/waiting accrued) + cleaning fee 124.14, never multiplied by
    # anything maxi-related since this trip isn't a maxi trip at all.
    assert breakdown.fare_total == Decimal("129.31")


def test_h3_negotiated_set_price_bills_the_agreed_amount_not_the_metered_fare():
    """A $25 negotiated ("Set Price") fare with a $6.43 toll and PSL on:
    grand_total = 25.00 (agreed) + 6.43 (toll) + 1.32 (PSL) = 32.75 — the
    agreed amount, never the metered accrual, per Act s79(3) (never demand
    more than what was agreed) and this engine's own negotiated_total
    contract (levies/tolls still add on top, exactly as the driver-facing
    copy promises)."""
    engine = FareEngine()
    state = FareState(
        tariff=URBAN_TARIFF,
        time_class=TimeClass.DAY,
        negotiated_total=Decimal("25.00"),
        tolls=Decimal("6.43"),
    )
    # Prove the metered accrual is irrelevant to what gets billed: rack up a
    # large distance charge that would dwarf the negotiated total if it were
    # mistakenly used instead.
    state = engine.tick(state, speed_kmh=40, distance_delta_km=50, elapsed_seconds=4500)

    breakdown = engine.close(state, include_psl=True)

    assert breakdown.maxi_applied is False
    assert breakdown.fare_total == Decimal("32.75")
    assert breakdown.grand_total == Decimal("32.75")
    assert breakdown.gst_component == Decimal("2.98")


def test_i_validate_against_fares_order_rank_hail_vs_booked():
    """A rank/hail tariff with rates above the Fares Order reference must raise;
    the identical (still excessive) tariff sold as a BOOKED fare is exempt."""
    excessive_tariff = Tariff(
        name="rogue-urban",
        area=AreaClass.URBAN,
        flag_fall=Decimal("5.18"),  # 1c above the $5.17 reference cap
        peak_charge=URBAN_TARIFF.peak_charge,
        dist_rate_1=URBAN_TARIFF.dist_rate_1,
        dist_rate_2=URBAN_TARIFF.dist_rate_2,
        night_rate_1=URBAN_TARIFF.night_rate_1,
        night_rate_2=URBAN_TARIFF.night_rate_2,
        holiday_rate_1=URBAN_TARIFF.holiday_rate_1,
        holiday_rate_2=URBAN_TARIFF.holiday_rate_2,
        waiting_rate_per_min=URBAN_TARIFF.waiting_rate_per_min,
    )

    with pytest.raises(FaresOrderViolation):
        validate_against_fares_order(excessive_tariff, URBAN_TARIFF, booked=False)

    # Booked fares are unregulated -> the identical excessive tariff is fine.
    validate_against_fares_order(excessive_tariff, URBAN_TARIFF, booked=True)

    # A tariff that matches the reference exactly (not exceeding) never raises,
    # rank/hail or not.
    validate_against_fares_order(URBAN_TARIFF, URBAN_TARIFF, booked=False)


def test_i2_validate_against_fares_order_also_catches_an_over_cap_cleaning_fee():
    """cleaning_fee_cap is in Tariff._RATE_FIELDS too — a tenant tariff can set
    a LOWER cleaning-fee cap than the Order's $124.14 maximum, but never a
    higher one."""
    over_cap_tariff = Tariff(
        name="rogue-cleaning-cap",
        area=AreaClass.URBAN,
        flag_fall=URBAN_TARIFF.flag_fall,
        peak_charge=URBAN_TARIFF.peak_charge,
        dist_rate_1=URBAN_TARIFF.dist_rate_1,
        dist_rate_2=URBAN_TARIFF.dist_rate_2,
        night_rate_1=URBAN_TARIFF.night_rate_1,
        night_rate_2=URBAN_TARIFF.night_rate_2,
        holiday_rate_1=URBAN_TARIFF.holiday_rate_1,
        holiday_rate_2=URBAN_TARIFF.holiday_rate_2,
        waiting_rate_per_min=URBAN_TARIFF.waiting_rate_per_min,
        cleaning_fee_cap=Decimal("124.15"),  # 1c above the $124.14 reference cap
    )

    with pytest.raises(FaresOrderViolation):
        validate_against_fares_order(over_cap_tariff, URBAN_TARIFF, booked=False)


# --- resolve_time_class_and_peak: server-side time_class/is_peak classification ---
#
# 2026-07-17 is a Friday (2026-07-15 is a Wednesday, per this file's own
# constant below) not adjacent to any date in NSW_PUBLIC_HOLIDAYS, chosen
# specifically so these boundary vectors exercise ONLY the day-of-week /
# hour rules, never accidentally also tripping the public-holiday branch.


def test_j_resolve_time_class_and_peak_night_window_boundary_both_sides():
    """10pm-6am is NIGHT on both areas; the peak window shares the same
    10pm boundary. 21:59 is still DAY/not-peak; 22:00 flips both."""
    just_before = datetime(2026, 7, 17, 21, 59, tzinfo=UTC)  # Friday
    at_boundary = datetime(2026, 7, 17, 22, 0, tzinfo=UTC)  # Friday

    time_class, is_peak = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=just_before)
    assert time_class == TimeClass.DAY
    assert is_peak is False

    time_class, is_peak = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=at_boundary)
    assert time_class == TimeClass.NIGHT
    assert is_peak is True  # Friday + late-night


def test_j2_resolve_time_class_and_peak_morning_night_window_boundary_both_sides():
    """05:59 is still NIGHT; 06:00 flips back to DAY. A Saturday morning, so
    is_peak is also exercised: true right up to 05:59, false at 06:00."""
    just_before = datetime(2026, 7, 18, 5, 59, tzinfo=UTC)  # Saturday
    at_boundary = datetime(2026, 7, 18, 6, 0, tzinfo=UTC)  # Saturday

    time_class, is_peak = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=just_before)
    assert time_class == TimeClass.NIGHT
    assert is_peak is True  # Saturday + late-night

    time_class, is_peak = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=at_boundary)
    assert time_class == TimeClass.DAY
    assert is_peak is False


def test_j3_resolve_time_class_and_peak_23_59_to_00_00_rollover_stays_night():
    """Crossing midnight (Wed 23:59 -> Thu 00:01) must not be mistaken for a
    day/night rollover artifact -- both instants are within the 10pm-6am
    window and must both resolve to NIGHT, and neither Wednesday nor Thursday
    is Friday/Saturday/pre-holiday, so is_peak stays False on both sides."""
    wed_late = datetime(2026, 7, 15, 23, 59, tzinfo=UTC)  # Wednesday
    thu_early = datetime(2026, 7, 16, 0, 1, tzinfo=UTC)  # Thursday

    time_class, is_peak = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=wed_late)
    assert time_class == TimeClass.NIGHT
    assert is_peak is False

    time_class, is_peak = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=thu_early)
    assert time_class == TimeClass.NIGHT
    assert is_peak is False


def test_j4_resolve_time_class_and_peak_country_sunday_and_public_holiday():
    """Country-only HOLIDAY band: a Sunday daytime trip, and a non-Sunday
    gazetted public holiday (2026-01-01, a Thursday), both resolve to
    HOLIDAY; the same instants on URBAN never do (urban has no holiday
    band); an ordinary country weekday stays DAY."""
    sunday_afternoon = datetime(2026, 1, 4, 14, 0, tzinfo=UTC)  # a Sunday
    new_years_day = datetime(2026, 1, 1, 14, 0, tzinfo=UTC)  # Thursday, gazetted holiday
    wednesday_afternoon = datetime(2026, 1, 7, 14, 0, tzinfo=UTC)  # plain Wednesday

    assert resolve_time_class_and_peak(tariff=COUNTRY_TARIFF, occurred_at=sunday_afternoon)[0] == TimeClass.HOLIDAY
    assert resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=sunday_afternoon)[0] == TimeClass.DAY
    assert resolve_time_class_and_peak(tariff=COUNTRY_TARIFF, occurred_at=new_years_day)[0] == TimeClass.HOLIDAY
    assert resolve_time_class_and_peak(tariff=COUNTRY_TARIFF, occurred_at=wednesday_afternoon)[0] == TimeClass.DAY


def test_j5_resolve_time_class_and_peak_night_before_public_holiday_is_peak():
    """The peak window's third trigger (beyond Friday/Saturday): the night
    before a gazetted public holiday. 2025-12-31 23:30 is a Wednesday night,
    ordinarily not peak-eligible, but 2026-01-01 (New Year's Day) is gazetted
    -> is_peak is True. The following (ordinary) Wednesday night is not."""
    night_before_new_year = datetime(2025, 12, 31, 23, 30, tzinfo=UTC)  # Wednesday
    ordinary_wednesday_night = datetime(2026, 1, 7, 23, 30, tzinfo=UTC)  # Wednesday, no holiday follows

    assert resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=night_before_new_year)[1] is True
    assert resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=ordinary_wednesday_night)[1] is False
