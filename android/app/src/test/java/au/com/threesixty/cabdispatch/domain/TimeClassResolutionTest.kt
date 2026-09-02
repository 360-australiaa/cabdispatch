package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.domain.fare.AreaClass
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM unit tests for [resolveTimeClassFor]/[resolveIsPeakFor] (domain/FareEngine.kt) — the
 * Point to Point Transport (Fares) Order 2026 date/holiday-calendar classification rules that
 * [FareEngineImpl.startTrip] fixes `timeClass`/`isPeak` from at journey commencement. These are
 * top-level functions taking an explicit [ZonedDateTime] specifically so this logic is testable
 * without needing to fake the system clock (`FareEngineImpl`'s own `resolveTimeClass`/
 * `resolveIsPeak` are thin `ZonedDateTime.now()`-supplying wrappers around these, exercised only
 * implicitly/manually since they're private and clock-dependent).
 */
class TimeClassResolutionTest {

    private val zone: ZoneId = ZoneId.of("Australia/Sydney")

    @Test
    fun `Thursday night before a gazetted Friday public holiday gets the peak charge`() {
        // 2026-12-24 is a Thursday; 2026-12-25 (Christmas Day) is a gazetted NSW public holiday.
        val thursdayNightBeforeChristmas = ZonedDateTime.of(2026, 12, 24, 23, 0, 0, 0, zone)
        assertTrue(resolveIsPeakFor(thursdayNightBeforeChristmas))
    }

    @Test
    fun `an ordinary Thursday night (not before a public holiday) does not get the peak charge`() {
        // 2026-12-17 is a plain Thursday -- the following day (Dec 18) is not a public holiday.
        val ordinaryThursdayNight = ZonedDateTime.of(2026, 12, 17, 23, 0, 0, 0, zone)
        assertFalse(resolveIsPeakFor(ordinaryThursdayNight))
    }

    @Test
    fun `Friday and Saturday nights get the peak charge regardless of any holiday`() {
        val fridayNight = ZonedDateTime.of(2026, 1, 2, 23, 30, 0, 0, zone) // 2026-01-02 is a Friday
        val saturdayNight = ZonedDateTime.of(2026, 1, 3, 2, 0, 0, 0, zone) // 2026-01-03 is a Saturday
        assertTrue(resolveIsPeakFor(fridayNight))
        assertTrue(resolveIsPeakFor(saturdayNight))
    }

    @Test
    fun `peak charge never applies outside the 10pm-6am window even on a Friday`() {
        val fridayAfternoon = ZonedDateTime.of(2026, 1, 2, 15, 0, 0, 0, zone)
        assertFalse(resolveIsPeakFor(fridayAfternoon))
    }

    @Test
    fun `country Sunday daytime trip gets HOLIDAY time class`() {
        // 2026-01-04 is a Sunday and not a gazetted public holiday.
        val sundayAfternoon = ZonedDateTime.of(2026, 1, 4, 14, 0, 0, 0, zone)
        assertEquals(TimeClass.HOLIDAY, resolveTimeClassFor(sundayAfternoon, AreaClass.COUNTRY))
    }

    @Test
    fun `urban Sunday daytime trip stays DAY -- urban has no holiday band`() {
        val sundayAfternoon = ZonedDateTime.of(2026, 1, 4, 14, 0, 0, 0, zone)
        assertEquals(TimeClass.DAY, resolveTimeClassFor(sundayAfternoon, AreaClass.URBAN))
    }

    @Test
    fun `country weekday daytime trip stays DAY`() {
        val wednesdayAfternoon = ZonedDateTime.of(2026, 1, 7, 14, 0, 0, 0, zone) // a plain Wednesday
        assertEquals(TimeClass.DAY, resolveTimeClassFor(wednesdayAfternoon, AreaClass.COUNTRY))
    }

    @Test
    fun `country public holiday daytime trip (non-Sunday) gets HOLIDAY time class`() {
        // 2026-01-01 (New Year's Day) is a Thursday -- a gazetted public holiday, not a Sunday.
        val newYearsDayAfternoon = ZonedDateTime.of(2026, 1, 1, 14, 0, 0, 0, zone)
        assertEquals(TimeClass.HOLIDAY, resolveTimeClassFor(newYearsDayAfternoon, AreaClass.COUNTRY))
    }

    @Test
    fun `10pm-6am is always NIGHT regardless of area or day`() {
        val lateNight = ZonedDateTime.of(2026, 1, 4, 23, 0, 0, 0, zone) // a Sunday night
        assertEquals(TimeClass.NIGHT, resolveTimeClassFor(lateNight, AreaClass.COUNTRY))
        assertEquals(TimeClass.NIGHT, resolveTimeClassFor(lateNight, AreaClass.URBAN))
    }
}
