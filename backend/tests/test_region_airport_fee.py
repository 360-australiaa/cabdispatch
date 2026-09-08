"""The NSW region's airport access fee is the server-side reference for the
tablet's auto-applied airport pickup extra (android JurisdictionConfig.NSW)."""

from decimal import Decimal

from app.services.regions import get_region


def test_nsw_region_declares_the_sydney_airport_access_fee():
    nsw = get_region("NSW")
    assert nsw.airport_access_fee == Decimal("6.43")
    assert nsw.airport_precinct_lat == -33.9399
    assert nsw.airport_precinct_lng == 151.1753
    assert nsw.airport_precinct_radius_m == 1_800.0


def test_unknown_region_falls_back_to_nsw_with_the_fee():
    assert get_region("").airport_access_fee == Decimal("6.43")
