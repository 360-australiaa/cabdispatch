"""Golden compliance-evidence vectors for the NSW tariff-switching fare engine.

Every expected Decimal total below is computed BY HAND from the NSW Fares Order
2025 (no.2) rate table in app/services/fare_engine.py's module docstring — do not
"fix" a failing assertion by copying the engine's output back in; if a test fails,
the engine (or the hand calculation in the comment) is wrong and must be fixed.
"""
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
    validate_against_fares_order,
)


def test_a_short_urban_day_trip_all_moving():
    """3km, all >=26km/h, urban day, cash. Within first-12km band throughout.

    distance_charge = 3 * 2.52            = 7.56
    subtotal        = flag_fall 5.00 + 7.56 = 12.56
    gst_component   = 12.56 / 11          = 1.1418... -> 1.14
    """
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.DAY)

    state = engine.tick(state, speed_kmh=40, distance_delta_km=3, elapsed_seconds=270)

    breakdown = engine.close(state)

    assert breakdown.distance_charge == Decimal("7.56")
    assert breakdown.fare_total == Decimal("12.56")
    assert breakdown.surcharge == Decimal(0)
    assert breakdown.grand_total == Decimal("12.56")
    assert breakdown.gst_component == Decimal("1.14")


def test_b_urban_night_trip_over_12km_mixes_both_bands():
    """16km in one continuous fast segment, urban night (10pm-6am), peak hiring.

    first 12km  @ night_rate_1 3.00 = 36.00
    next   4km  @ night_rate_2 2.73 = 10.92
    distance_charge = 46.92
    subtotal = flag_fall 5.00 + peak 2.56 + 46.92 = 54.48
    gst_component = 54.48 / 11 = 4.9527... -> 4.95
    """
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.NIGHT, is_peak=True)

    state = engine.tick(state, speed_kmh=50, distance_delta_km=16, elapsed_seconds=900)

    assert state.cumulative_distance_km == Decimal(16)
    breakdown = engine.close(state)

    assert breakdown.distance_charge == Decimal("46.92")
    assert breakdown.fare_total == Decimal("54.48")
    assert breakdown.gst_component == Decimal("4.95")


def test_c_country_holiday_trip():
    """10km, country, Sunday daytime (holiday time_class), within first-12km band.

    distance_charge = 10 * holiday_rate_1 2.87 = 28.70
    subtotal = flag_fall 5.11 + 28.70 = 33.81
    gst_component = 33.81 / 11 = 3.07363... -> 3.07
    """
    engine = FareEngine()
    state = FareState(tariff=COUNTRY_TARIFF, time_class=TimeClass.HOLIDAY)

    state = engine.tick(state, speed_kmh=45, distance_delta_km=10, elapsed_seconds=800)

    breakdown = engine.close(state)

    assert breakdown.distance_charge == Decimal("28.70")
    assert breakdown.fare_total == Decimal("33.81")
    assert breakdown.gst_component == Decimal("3.07")


def test_d_waiting_heavy_cbd_crawl():
    """Mostly <26km/h (waiting), urban day, one short fast hop mixed in.

    waiting: 10 min @ 1.092/min = 10.92
    waiting:  5 min @ 1.092/min =  5.46
    waiting_charge total = 16.38
    distance: 1km @ dist_rate_1 2.52 = 2.52
    subtotal = flag_fall 5.00 + 2.52 + 16.38 = 23.90
    gst_component = 23.90 / 11 = 2.17272... -> 2.17
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

    assert breakdown.waiting_charge == Decimal("16.38")
    assert breakdown.distance_charge == Decimal("2.52")
    assert breakdown.fare_total == Decimal("23.90")
    assert breakdown.gst_component == Decimal("2.17")


def test_e_multiple_hiring_each_hirer_owes_75pct_at_their_drop():
    """2 hirers, meter runs ONCE (single continuous state, never reset).

    Hirer 1 drop checkpoint:
      distance_charge so far = 2km * 2.52 = 5.04
      fare_total_1 = flag_fall 5.00 + 5.04 = 10.04
      owed_1 = 10.04 * 0.75 = 7.53

    Hirer 2 (final) drop:
      + 3km * 2.52 = 7.56  ->  distance_charge = 5.04 + 7.56 = 12.60
      fare_total_2 = 5.00 + 12.60 = 17.60
      owed_2 = 17.60 * 0.75 = 13.20
    """
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.DAY)

    state = engine.tick(state, speed_kmh=40, distance_delta_km=2, elapsed_seconds=180)
    checkpoint_1 = engine.close(state)  # non-mutating: safe to checkpoint mid-trip
    owed_1 = engine.multi_hire_amount_owed(checkpoint_1, URBAN_TARIFF)

    assert checkpoint_1.fare_total == Decimal("10.04")
    assert owed_1 == Decimal("7.53")

    # Meter keeps running on the SAME state object for hirer 2.
    state = engine.tick(state, speed_kmh=40, distance_delta_km=3, elapsed_seconds=270)
    checkpoint_2 = engine.close(state)
    owed_2 = engine.multi_hire_amount_owed(checkpoint_2, URBAN_TARIFF)

    assert checkpoint_2.fare_total == Decimal("17.60")
    assert owed_2 == Decimal("13.20")


def test_f_maxi_cab_urban_trip_applies_150pct():
    """4km urban day, maxi flagged (5+ pax).

    subtotal_before_maxi = flag_fall 5.00 + (4 * 2.52 = 10.08) = 15.08
    fare_total = 15.08 * 1.5 = 22.62
    gst_component = 22.62 / 11 = 2.05636... -> 2.06
    """
    engine = FareEngine()
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.DAY, maxi=True)

    state = engine.tick(state, speed_kmh=40, distance_delta_km=4, elapsed_seconds=360)

    breakdown = engine.close(state)

    assert breakdown.maxi_applied is True
    assert breakdown.fare_total == Decimal("22.62")
    assert breakdown.gst_component == Decimal("2.06")


def test_g_sydney_airport_fixed_fare_standard_and_maxi():
    """Fixed $60/$80 — no PSL, tolls, or peak may be added on top, only
    non-cash surcharge and cleaning fee. We deliberately set tolls/PSL/peak on
    the state to prove close() ignores them entirely for a fixed-fare trip."""
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

    maxi_state = FareState(tariff=URBAN_TARIFF, maxi=True, fixed_fare=airport_fixed_fare(maxi=True))
    maxi_breakdown = engine.close(maxi_state)

    assert maxi_breakdown.fare_total == Decimal("80.00")
    assert maxi_breakdown.grand_total == Decimal("80.00")


def test_h_non_cash_surcharge_rounds_half_up_at_exact_boundary():
    """fare_total = 200.50, custom surcharge_pct = 1.0%.

    raw surcharge = 200.50 * 1.0 / 100 = 2.005 exactly -> the 0.5c boundary.
    Fares Order rule: <0.5c rounds down, >=0.5c rounds up => rounds UP to 2.01.
    (Bankers'/round-half-even rounding would instead give 2.00 here — this test
    exists specifically to prove we are NOT doing that.)
    """
    engine = FareEngine()
    state = FareState(
        tariff=URBAN_TARIFF,
        time_class=TimeClass.DAY,
        accrued_distance_charge=Decimal("195.50"),  # + flag_fall 5.00 = 200.50
    )

    breakdown = engine.close(state, payment_method="card", surcharge_pct=Decimal("1.0"))

    assert breakdown.fare_total == Decimal("200.50")
    assert breakdown.surcharge == Decimal("2.01")
    assert breakdown.grand_total == Decimal("202.51")


def test_i_validate_against_fares_order_rank_hail_vs_booked():
    """A rank/hail tariff with rates above the Fares Order reference must raise;
    the identical (still excessive) tariff sold as a BOOKED fare is exempt."""
    excessive_tariff = Tariff(
        name="rogue-urban",
        area=AreaClass.URBAN,
        flag_fall=Decimal("5.01"),  # 1c above the $5.00 reference cap
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
