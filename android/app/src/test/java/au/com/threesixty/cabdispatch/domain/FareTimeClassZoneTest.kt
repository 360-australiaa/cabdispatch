package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.domain.fare.AreaClass
import au.com.threesixty.cabdispatch.domain.fare.NSW_FARE_ZONE
// TimeClass unqualified on purpose: `resolveTimeClassFor` returns this package's
// domain.TimeClass, and there is a SECOND, distinct TimeClass in domain.fare that an
// import here would silently shadow it with (the first version of this file did exactly
// that and failed on "expected fare.TimeClass<DAY> but was TimeClass<DAY>").
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The fare-time clock: NSW local, never the tablet's own zone.
 *
 * Found live on 2026-09-07 on a field-test tablet running on Karachi time. It billed a trip with
 * the 1.19x night rate because it was 11pm *locally*; the server -- classifying the same instant
 * in NSW -- disagreed, and the trip auto-flagged for dispute review on a 9.03% fare variance.
 * Whichever of the two is "right" is beside the point: a regulated NSW fare cannot depend on where
 * the tablet thinks it is, and the two classifiers have to agree or every night trip fails the
 * variance check on sync.
 *
 * These test the RULE against explicit instants rather than `now()`, so they say something on any
 * machine, in any zone, at any time of day -- the property under test is precisely that the answer
 * does not depend on those things.
 */
class FareTimeClassZoneTest {

    private val sydney = ZoneId.of("Australia/Sydney")
    private val karachi = ZoneId.of("Asia/Karachi")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `the fare zone is NSW, matching the backend's own constant`() {
        // app.services.fare_engine.NSW_FARE_ZONE. A disagreement here is a fare-variance flag on
        // every synced night trip, so the two are pinned to the same string.
        assertEquals(ZoneId.of("Australia/Sydney"), NSW_FARE_ZONE)
    }

    @Test
    fun `an instant inside the NSW night window is NIGHT however it is expressed`() {
        // 11:08pm Sydney -- the exact trip that surfaced this.
        val instant = ZonedDateTime.of(2026, 9, 7, 23, 8, 0, 0, sydney)

        for (zone in listOf(sydney, utc, karachi)) {
            assertEquals(
                "same instant, zone $zone",
                TimeClass.NIGHT,
                resolveTimeClassFor(instant.withZoneSameInstant(NSW_FARE_ZONE), AreaClass.URBAN),
            )
            // And the caller must convert before classifying: reading the raw local hour of the
            // same instant in another zone is exactly the defect.
            assertEquals(
                TimeClass.NIGHT,
                resolveTimeClassFor(instant.withZoneSameInstant(zone).withZoneSameInstant(NSW_FARE_ZONE), AreaClass.URBAN),
            )
        }
    }

    @Test
    fun `mid-morning in Sydney is DAY even where it reads as night elsewhere`() {
        // 10am Sydney is midnight UTC. Classifying on the raw UTC hour would call this NIGHT and
        // OVERCHARGE -- the error ran in both directions, not just the one that lost money.
        val instant = ZonedDateTime.of(2026, 9, 7, 10, 0, 0, 0, sydney)

        assertEquals(
            TimeClass.DAY,
            resolveTimeClassFor(instant.withZoneSameInstant(NSW_FARE_ZONE), AreaClass.URBAN),
        )
    }

    @Test
    fun `the night window boundaries hold in NSW local time`() {
        fun classAt(hour: Int, minute: Int) = resolveTimeClassFor(
            ZonedDateTime.of(2026, 9, 7, hour, minute, 0, 0, sydney),
            AreaClass.URBAN,
        )

        assertEquals(TimeClass.DAY, classAt(21, 59))
        assertEquals(TimeClass.NIGHT, classAt(22, 0))
        assertEquals(TimeClass.NIGHT, classAt(5, 59))
        assertEquals(TimeClass.DAY, classAt(6, 0))
    }

    @Test
    fun `the peak hiring window follows NSW local time`() {
        // Friday 11pm Sydney. On a tablet an hour or two out of zone this would fall the wrong
        // side of both the 10pm boundary and, near midnight, the Friday/Saturday one.
        val fridayNight = ZonedDateTime.of(2026, 9, 11, 23, 0, 0, 0, sydney)

        assertEquals(true, resolveIsPeakFor(fridayNight.withZoneSameInstant(NSW_FARE_ZONE)))
    }
}
