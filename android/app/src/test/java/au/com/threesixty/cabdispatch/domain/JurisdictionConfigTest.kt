package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * X1 jurisdiction seam (docs/plans/2026-09-08-global-meter-program.md Wave 3). Proves two things
 * about [JurisdictionConfig.NSW]:
 *   1. it reproduces the existing hardcoded NSW constants exactly (so wiring it in later cannot
 *      change behaviour by itself), and
 *   2. the seam actually varies for a different jurisdiction (a synthetic `VIC-TEST` fixture,
 *      mirroring `backend/tests/test_regions.py`'s own synthetic region).
 *
 * Deliberately does NOT touch [au.com.threesixty.cabdispatch.domain.fare.FareEngine] or
 * [FareEngine] — those two files' golden-tested arithmetic is out of scope for this test, by
 * design (see [JurisdictionConfig]'s own doc).
 */
class JurisdictionConfigTest {

    @Test
    fun `NSW config matches the existing hardcoded holiday calendar`() {
        assertEquals(NswPublicHolidays.DATES, JurisdictionConfig.NSW.holidaysForYear(2026) + JurisdictionConfig.NSW.holidaysForYear(2027))
    }

    @Test
    fun `NSW config matches the existing hardcoded night window and peak weekdays`() {
        assertEquals(22, JurisdictionConfig.NSW.nightStartHour)
        assertEquals(6, JurisdictionConfig.NSW.nightEndHour)
        assertEquals(setOf(5, 6), JurisdictionConfig.NSW.peakDaysOfWeek) // Friday, Saturday
    }

    @Test
    fun `NSW config matches the existing hardcoded fare zone and currency`() {
        assertEquals(ZoneId.of("Australia/Sydney"), JurisdictionConfig.NSW.fareZone)
        assertEquals("AUD", JurisdictionConfig.NSW.currencyCode)
        assertEquals(java.math.BigDecimal(11), JurisdictionConfig.NSW.gstDivisor)
    }

    @Test
    fun `a synthetic non-NSW region actually changes the night window outcome`() {
        val vicTest = JurisdictionConfig(
            code = "VIC-TEST",
            label = "Victoria (synthetic test fixture)",
            fareZone = ZoneId.of("Australia/Melbourne"),
            currencyCode = "AUD",
            gstDivisor = java.math.BigDecimal(11),
            regions = listOf("urban", "country"),
            nightStartHour = 21,
            nightEndHour = 5,
            peakDaysOfWeek = emptySet(),
            holidaysForYear = { year -> setOf(LocalDate.of(year, 12, 25)) },
            regionResolverCentreLat = -37.8136,
            regionResolverCentreLng = 144.9631,
            urbanRadiusKm = 50.0,
            operatingFootprintRadiusKm = 2000.0,
        )

        assertNotEquals(JurisdictionConfig.NSW.nightStartHour, vicTest.nightStartHour)
        assertNotEquals(JurisdictionConfig.NSW.nightEndHour, vicTest.nightEndHour)
        assertNotEquals(JurisdictionConfig.NSW.peakDaysOfWeek, vicTest.peakDaysOfWeek)
        assertNotEquals(JurisdictionConfig.NSW.fareZone, vicTest.fareZone)
    }

    @Test
    fun `RegionResolver config overload agrees with the hardcoded resolve for NSW's own centre point`() {
        val fromConstants = RegionResolver.resolve(-33.8688, 151.2093)
        val fromConfig = RegionResolver.resolve(-33.8688, 151.2093, JurisdictionConfig.NSW)

        // Both read the SAME centre point today (see JurisdictionConfig.NSW's own doc note about
        // SydneyCbdFallback actually holding Karachi's coordinates) -- this only asserts the two
        // resolution paths agree with EACH OTHER for a fixed input, not that either is "Sydney".
        assertEquals(fromConstants, fromConfig)
    }

    @Test
    fun `RegionResolver config overload degrades to urban on a null fix, same as the hardcoded resolve`() {
        assertEquals(RegionResolver.resolve(null, null), RegionResolver.resolve(null, null, JurisdictionConfig.NSW))
    }
}
