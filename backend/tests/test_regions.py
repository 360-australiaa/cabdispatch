"""Proves the jurisdiction seam (X1) actually changes fare-time outcomes —
not just that a second `FareRegion` object can be constructed. `VIC-TEST` is
a synthetic region (never seeded, never registered against a real tenant):
`Australia/Melbourne`, night window 21:00-05:00 (an hour earlier/later than
NSW's 22:00-06:00 on both ends), no peak-eligible weekdays at all, and no
holiday-weekday rule — chosen specifically so every assertion below would
FAIL if `resolve_time_class_and_peak`/`FareEngine.close` still secretly read
NSW's hardcoded window instead of the `region` argument.

Per `docs/plans/2026-09-08-global-meter-program.md` §5 decision #6 ("a
synthetic VIC-TEST region is used") — this is that fixture, and it is a test
fixture only. It is not registered in `app.services.regions`'s registry and
no `Tenant.jurisdiction` value resolves to it in production.
"""
from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

from app.services.fare_engine import (
    COUNTRY_TARIFF,
    URBAN_TARIFF,
    FareEngine,
    FareState,
    TimeClass,
    resolve_time_class_and_peak,
)
from app.services.regions import FareRegion
from app.services.regions.nsw import NSW_REGION

VIC_TEST_REGION = FareRegion(
    code="VIC-TEST",
    label="Victoria (synthetic test fixture)",
    tz=ZoneInfo("Australia/Melbourne"),
    currency="AUD",
    gst_divisor=Decimal(11),
    night_start_hour=21,
    night_end_hour=5,
    peak_weekdays=frozenset(),  # deliberately NO peak-eligible weekday at all
    holiday_weekdays=frozenset(),  # deliberately NO holiday-weekday rule
    holidays=lambda year: frozenset({date(year, 12, 25)}),  # Christmas only
    region_vocabulary=("urban", "country"),
    maxi_rule="no maxi uplift modelled for this fixture",
)


def test_vic_test_region_is_a_real_fare_region_not_nsw():
    assert VIC_TEST_REGION.code == "VIC-TEST"
    assert VIC_TEST_REGION.code != NSW_REGION.code
    assert VIC_TEST_REGION.tz.key == "Australia/Melbourne"
    assert VIC_TEST_REGION != NSW_REGION


def test_night_window_boundary_differs_from_nsw():
    """21:30 is NIGHT under VIC-TEST's earlier night start (21:00) but DAY
    under NSW's (22:00) — same instant, same tariff, different region."""
    at = datetime(2026, 3, 11, 21, 30, tzinfo=VIC_TEST_REGION.tz)  # a plain Wednesday

    nsw_class, _ = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=at, region=NSW_REGION)
    vic_class, _ = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=at, region=VIC_TEST_REGION)

    assert nsw_class == TimeClass.DAY
    assert vic_class == TimeClass.NIGHT


def test_night_window_morning_edge_differs_from_nsw():
    """05:30 is DAY under VIC-TEST's earlier night end (05:00) but NIGHT
    under NSW's (06:00)."""
    at = datetime(2026, 3, 12, 5, 30, tzinfo=VIC_TEST_REGION.tz)

    nsw_class, _ = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=at, region=NSW_REGION)
    vic_class, _ = resolve_time_class_and_peak(tariff=URBAN_TARIFF, occurred_at=at, region=VIC_TEST_REGION)

    assert nsw_class == TimeClass.NIGHT
    assert vic_class == TimeClass.DAY


def test_no_peak_weekday_ever_under_vic_test():
    """A Friday-night hiring is peak-eligible under NSW (Fri/Sat rule) but
    never under VIC-TEST, which defines no peak-eligible weekday at all."""
    friday_night = datetime(2026, 3, 13, 23, 0, tzinfo=VIC_TEST_REGION.tz)  # a Friday

    _, nsw_peak = resolve_time_class_and_peak(
        tariff=URBAN_TARIFF, occurred_at=friday_night, region=NSW_REGION
    )
    _, vic_peak = resolve_time_class_and_peak(
        tariff=URBAN_TARIFF, occurred_at=friday_night, region=VIC_TEST_REGION
    )

    assert nsw_peak is True
    assert vic_peak is False


def test_holiday_calendar_is_per_region():
    """A gazetted NSW public holiday (Australia Day, 26 Jan) is not in
    VIC-TEST's fixture calendar (Christmas only) — the COUNTRY-area HOLIDAY
    class must not fire for it under VIC-TEST."""
    australia_day_afternoon = datetime(2027, 1, 26, 14, 0, tzinfo=NSW_REGION.tz)  # a Tuesday

    nsw_class, _ = resolve_time_class_and_peak(
        tariff=COUNTRY_TARIFF, occurred_at=australia_day_afternoon, region=NSW_REGION
    )
    vic_class, _ = resolve_time_class_and_peak(
        tariff=COUNTRY_TARIFF, occurred_at=australia_day_afternoon, region=VIC_TEST_REGION
    )

    assert nsw_class == TimeClass.HOLIDAY
    assert vic_class == TimeClass.DAY


def test_close_gst_component_is_region_gst_divisor_not_a_literal_11():
    """A region with no GST(-equivalent) levy at all reports gst_component as
    0, not `grand_total / 11` — proving `FareEngine.close` reads the divisor
    off `region`, not a hardcoded literal."""
    no_gst_region = FareRegion(
        code="NO-GST-TEST",
        label="No-GST test fixture",
        tz=ZoneInfo("UTC"),
        currency="AUD",
        gst_divisor=None,
        night_start_hour=22,
        night_end_hour=6,
        peak_weekdays=frozenset(),
        holiday_weekdays=frozenset(),
        holidays=lambda year: frozenset(),
        region_vocabulary=("urban",),
        maxi_rule="n/a",
    )
    state = FareState(tariff=URBAN_TARIFF, time_class=TimeClass.DAY)
    engine = FareEngine()

    default_breakdown = engine.close(state)
    no_gst_breakdown = engine.close(state, region=no_gst_region)

    assert default_breakdown.gst_component > Decimal(0)
    assert no_gst_breakdown.gst_component == Decimal(0)
    # The amount actually charged must not depend on the GST reporting
    # divisor at all — only the reported tax-component figure changes.
    assert default_breakdown.grand_total == no_gst_breakdown.grand_total
