package au.com.threesixty.cabdispatch.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveHeartbeatCadence] and [HeartbeatScheduler] — the pure decision logic behind
 * [LivePositionHeartbeat]'s adaptive interval (2026-09-12 optimisation plan, W4 task 1). Both are
 * plain Kotlin with an injectable clock, so this file exercises all four named cadences and every
 * immediate-publish trigger from the plan text with a fake/virtual clock -- no coroutines, no
 * Android, no real wall-clock sleeping.
 */
class LivePositionHeartbeatSchedulerTest {

    // ================================================================================
    // resolveHeartbeatCadence — the four cadences
    // ================================================================================

    @Test
    fun `hired or duress is always 5s, regardless of speed or screen state`() {
        assertEquals(
            HeartbeatCadence.HIRED_OR_DURESS,
            resolveHeartbeatCadence(hiredOrDuress = true, speedKmh = 0.0, screenOn = false),
        )
        assertEquals(
            HeartbeatCadence.HIRED_OR_DURESS,
            resolveHeartbeatCadence(hiredOrDuress = true, speedKmh = 80.0, screenOn = true),
        )
    }

    @Test
    fun `on shift and moving is 10s`() {
        assertEquals(
            HeartbeatCadence.MOVING,
            resolveHeartbeatCadence(hiredOrDuress = false, speedKmh = 40.0, screenOn = true),
        )
        // Screen off but still moving must not fall into the deep screen-off tier -- a driver who
        // locked the tablet while cruising between ranks is still worth tracking closely.
        assertEquals(
            HeartbeatCadence.MOVING,
            resolveHeartbeatCadence(hiredOrDuress = false, speedKmh = 40.0, screenOn = false),
        )
    }

    @Test
    fun `on shift and stationary, screen on, is 30s`() {
        assertEquals(
            HeartbeatCadence.STATIONARY,
            resolveHeartbeatCadence(hiredOrDuress = false, speedKmh = 0.0, screenOn = true),
        )
    }

    @Test
    fun `screen off and stationary is 120s`() {
        assertEquals(
            HeartbeatCadence.SCREEN_OFF_STATIONARY,
            resolveHeartbeatCadence(hiredOrDuress = false, speedKmh = 0.0, screenOn = false),
        )
    }

    @Test
    fun `the moving threshold is exactly 5kmh, walking pace, exclusive`() {
        assertEquals(
            "exactly at the threshold is still stationary",
            HeartbeatCadence.STATIONARY,
            resolveHeartbeatCadence(hiredOrDuress = false, speedKmh = MOVING_SPEED_THRESHOLD_KMH, screenOn = true),
        )
        assertEquals(
            HeartbeatCadence.MOVING,
            resolveHeartbeatCadence(
                hiredOrDuress = false,
                speedKmh = MOVING_SPEED_THRESHOLD_KMH + 0.1,
                screenOn = true,
            ),
        )
    }

    @Test
    fun `the four intervals match the plan's exact figures`() {
        assertEquals(5_000L, HeartbeatCadence.HIRED_OR_DURESS.intervalMs)
        assertEquals(10_000L, HeartbeatCadence.MOVING.intervalMs)
        assertEquals(30_000L, HeartbeatCadence.STATIONARY.intervalMs)
        assertEquals(120_000L, HeartbeatCadence.SCREEN_OFF_STATIONARY.intervalMs)
    }

    // ================================================================================
    // HeartbeatScheduler — immediate-publish triggers, driven by a fake/virtual clock
    // ================================================================================

    /** A mutable "now", advanced explicitly by each test rather than slept — the virtual clock
     * the plan asks for. */
    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(byMs: Long) {
            nowMs += byMs
        }
    }

    @Test
    fun `the very first tick always publishes -- shift start`() {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }

        assertTrue(
            "nothing has ever been published yet, so this must fire regardless of cadence",
            scheduler.shouldPublish(
                HeartbeatCadence.SCREEN_OFF_STATIONARY,
                materialChange = false,
                lat = null,
                lng = null,
            ),
        )
    }

    @Test
    fun `a material change (trip open, close, or duress) publishes immediately, mid-interval`() {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        scheduler.recordPublish(-33.87, 151.21)

        // One second later, deep inside the 120s screen-off-stationary window: an ordinary tick
        // would not publish yet...
        clock.advance(1_000L)
        assertFalse(
            scheduler.shouldPublish(
                HeartbeatCadence.SCREEN_OFF_STATIONARY,
                materialChange = false,
                lat = -33.87,
                lng = 151.21,
            ),
        )
        // ...but a material change (a trip opening, closing, or a duress event firing) forces one
        // regardless of how little time or distance has passed.
        assertTrue(
            scheduler.shouldPublish(
                HeartbeatCadence.SCREEN_OFF_STATIONARY,
                materialChange = true,
                lat = -33.87,
                lng = 151.21,
            ),
        )
    }

    @Test
    fun `moving 100m or more since the last publish forces one, even mid-interval`() {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        scheduler.recordPublish(-33.87, 151.21)
        clock.advance(1_000L)

        // ~70m east at this latitude -- short of the 100m trigger.
        val shortMoveLng = 151.21 + 0.0007
        assertFalse(
            "under 100m must not force an early publish",
            scheduler.shouldPublish(
                HeartbeatCadence.STATIONARY,
                materialChange = false,
                lat = -33.87,
                lng = shortMoveLng,
            ),
        )

        // ~130m east -- over the 100m trigger.
        val longMoveLng = 151.21 + 0.0013
        assertTrue(
            "100m+ of displacement must force an immediate publish regardless of the cadence timer",
            scheduler.shouldPublish(
                HeartbeatCadence.STATIONARY,
                materialChange = false,
                lat = -33.87,
                lng = longMoveLng,
            ),
        )
    }

    @Test
    fun `with no material change and no movement, publishing waits out the cadence interval`() {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        scheduler.recordPublish(-33.87, 151.21)

        clock.advance(29_999L)
        assertFalse(
            "one millisecond short of the 30s stationary interval",
            scheduler.shouldPublish(HeartbeatCadence.STATIONARY, materialChange = false, lat = -33.87, lng = 151.21),
        )

        clock.advance(1L)
        assertTrue(
            "exactly at the 30s stationary interval",
            scheduler.shouldPublish(HeartbeatCadence.STATIONARY, materialChange = false, lat = -33.87, lng = 151.21),
        )
    }

    @Test
    fun `a fix-less publish (no GPS yet) never poisons the distance check`() {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        scheduler.recordPublish(null, null)
        clock.advance(1_000L)

        // No prior fix to compare against -- the distance trigger must never fire spuriously just
        // because a real fix has now arrived.
        assertFalse(
            scheduler.shouldPublish(
                HeartbeatCadence.SCREEN_OFF_STATIONARY,
                materialChange = false,
                lat = -33.87,
                lng = 151.21,
            ),
        )
    }

    @Test
    fun `each recorded publish resets the elapsed-time and distance baselines`() {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        scheduler.recordPublish(-33.87, 151.21)

        clock.advance(10_000L)
        scheduler.recordPublish(-33.87, 151.21) // e.g. the moving-cadence publish that just fired
        clock.advance(5_000L)

        // Only 5s since the LAST publish, well under the 30s stationary interval, and no
        // displacement from that same last publish -- must not fire again yet.
        assertFalse(
            scheduler.shouldPublish(HeartbeatCadence.STATIONARY, materialChange = false, lat = -33.87, lng = 151.21),
        )
    }

    // ================================================================================
    // The three ordering defects fixed on 2026-09-19 -- see LivePositionHeartbeat's
    // "Failure handling" doc and HeartbeatScheduler.recordAttempt for the full write-ups.
    // ================================================================================

    /** A fix at a given point. Only lat/lng matter to any of this; the rest are plausible
     * placeholders, same shape the other domain tests build fixes with. */
    private fun fixAt(lat: Double, lng: Double) =
        LocationFix(lat = lat, lng = lng, speedKmh = 0.0, accuracyM = 5f, timestampMillis = 0L)

    /** Sydney CBD. ~0.0030 degrees of longitude here is ~280m -- comfortably over the 100m
     * [MATERIAL_MOVE_METRES] trigger, so which of two points is the baseline is unambiguous. */
    private val lastRealFix = fixAt(-33.87, 151.2100)
    private val inertialEstimate = fixAt(-33.87, 151.2130)

    private fun tick(
        cadence: HeartbeatCadence = HeartbeatCadence.STATIONARY,
        materialChange: Boolean = false,
        fix: LocationFix? = lastRealFix,
    ) = HeartbeatTick(cadence = cadence, materialChange = materialChange, fix = fix)

    /**
     * Defect (b). During a GPS blackout `publishLoop` reads the frozen real fix to evaluate the
     * cadence, while `publishOnce` independently re-reads and sends the INERTIAL ESTIMATE instead.
     * Recording the loop's read as the "where were we last time" baseline anchored the 100m
     * force-publish to a coordinate that had never been transmitted. The baseline must be the point
     * that actually went on the wire.
     */
    @Test
    fun `the distance baseline is the fix that was published, not the one the loop read`() = runBlocking {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }

        val outcome = publishTickAndRecord(scheduler, tick(fix = lastRealFix)) {
            PublishAttempt(PositionPublishOutcome.PUBLISHED, inertialEstimate)
        }
        assertEquals(PositionPublishOutcome.PUBLISHED, outcome)
        clock.advance(1_000L)

        assertFalse(
            "sitting exactly where the published point put us is zero displacement",
            scheduler.shouldPublish(
                HeartbeatCadence.STATIONARY,
                materialChange = false,
                lat = inertialEstimate.lat,
                lng = inertialEstimate.lng,
            ),
        )
        assertTrue(
            "and the stale point the loop had read is ~280m from what was actually published",
            scheduler.shouldPublish(
                HeartbeatCadence.STATIONARY,
                materialChange = false,
                lat = lastRealFix.lat,
                lng = lastRealFix.lng,
            ),
        )
    }

    /** The other half of (b): a tick that is not due must not call the publisher at all, and must
     * not disturb any baseline. */
    @Test
    fun `a tick that is not due never touches the network or the baselines`() = runBlocking {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        scheduler.recordPublish(lastRealFix.lat, lastRealFix.lng)
        clock.advance(1_000L)

        var published = 0
        val outcome = publishTickAndRecord(scheduler, tick()) {
            published++
            PublishAttempt(PositionPublishOutcome.PUBLISHED, lastRealFix)
        }
        assertEquals("1s into a 30s window, with no movement, is not due", null, outcome)
        assertEquals(0, published)
    }

    /**
     * Defect (c), first half. A transport failure used to reset the cadence timer exactly as a
     * success did, so a tablet whose request never left the building waited out its whole tier --
     * up to 120s -- before trying again.
     */
    @Test
    fun `a failed publish does not reset the cadence timer`() {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }

        scheduler.recordAttempt(PositionPublishOutcome.TRANSPORT_FAILURE, lastRealFix)
        clock.advance(HeartbeatCadence.HIRED_OR_DURESS.intervalMs)

        assertTrue(
            "5s after a failure the tablet must retry, not sit out the 120s screen-off tier",
            scheduler.shouldPublish(
                HeartbeatCadence.SCREEN_OFF_STATIONARY,
                materialChange = false,
                lat = lastRealFix.lat,
                lng = lastRealFix.lng,
            ),
        )
    }

    /**
     * Defect (c), second half. A `NO_FIX` tick used to write null/null over the distance baseline,
     * silently disarming the 100m force-publish. Nothing was sent, so nothing may be recorded.
     */
    @Test
    fun `a NO_FIX tick never erases the distance baseline`() = runBlocking {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        scheduler.recordPublish(lastRealFix.lat, lastRealFix.lng)

        clock.advance(1_000L)
        publishTickAndRecord(scheduler, tick(materialChange = true, fix = null)) {
            PublishAttempt(PositionPublishOutcome.NO_FIX, null)
        }

        clock.advance(1_000L)
        assertTrue(
            "the 100m trigger must still be armed against the last point actually published",
            scheduler.shouldPublish(
                HeartbeatCadence.STATIONARY,
                materialChange = false,
                lat = inertialEstimate.lat,
                lng = inertialEstimate.lng,
            ),
        )
    }

    /** ...and a `NO_FIX` tick is not a failure either: it made no request, so it must not arm the
     * backoff. The instant a fix arrives, the tablet publishes. */
    @Test
    fun `a NO_FIX tick does not arm the failure backoff`() = runBlocking {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }

        publishTickAndRecord(scheduler, tick(fix = null)) { PublishAttempt(PositionPublishOutcome.NO_FIX, null) }
        assertEquals(0L, scheduler.failureBackoffMs())

        clock.advance(1_000L)
        var published = 0
        publishTickAndRecord(scheduler, tick()) {
            published++
            PublishAttempt(PositionPublishOutcome.PUBLISHED, lastRealFix)
        }
        assertEquals("the first tick with a real fix publishes immediately", 1, published)
    }

    /** The backoff ramp itself: 5s, then doubling, capped at the deepest ordinary cadence tier. */
    @Test
    fun `the failure backoff doubles from 5s and caps at 120s`() {
        val scheduler = HeartbeatScheduler { 0L }
        val ramp = (1..8).map {
            scheduler.recordFailure()
            scheduler.failureBackoffMs()
        }
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L, 120_000L),
            ramp,
        )
    }

    /** A success clears it outright -- a tablet that comes back out of a tunnel resumes its normal
     * cadence on the very next tick, not on a backoff schedule. */
    @Test
    fun `one successful publish clears the whole backoff`() {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        repeat(6) { scheduler.recordFailure() }
        assertEquals(120_000L, scheduler.failureBackoffMs())

        scheduler.recordAttempt(PositionPublishOutcome.PUBLISHED, lastRealFix)
        assertEquals(0L, scheduler.failureBackoffMs())

        clock.advance(HeartbeatCadence.STATIONARY.intervalMs)
        assertTrue(
            scheduler.shouldPublish(
                HeartbeatCadence.STATIONARY,
                materialChange = false,
                lat = lastRealFix.lat,
                lng = lastRealFix.lng,
            ),
        )
    }

    /**
     * The storm guard. Recording only real publishes (defect (c)'s fix) removes the accidental
     * spacing a failure used to get from resetting the cadence timer, which would leave the 1s poll
     * tick as the retry interval: 3,600 refused requests per tablet per hour. This drives ten
     * simulated minutes of a permanently-403ing tablet through the real scheduler, one tick per
     * simulated second, and counts what would actually have gone out.
     *
     * Nine attempts in ten minutes is the backoff ramp exactly: t = 0, 5, 15, 35, 75, 155, 275,
     * 395, 515 seconds (5s, then doubling, capped at 120s).
     */
    @Test
    fun `a permanently failing tablet does not storm the backend`() = runBlocking {
        val clock = FakeClock()
        val scheduler = HeartbeatScheduler { clock.nowMs }
        val tenMinutesOfTicks = 600
        var attempts = 0

        repeat(tenMinutesOfTicks) {
            publishTickAndRecord(scheduler, tick(cadence = HeartbeatCadence.HIRED_OR_DURESS)) {
                attempts++
                PublishAttempt(PositionPublishOutcome.NOT_MY_VEHICLE, lastRealFix)
            }
            clock.advance(POLL_TICK_SIMULATED_MS)
        }

        assertEquals("ten minutes of 403s must cost nine requests, not six hundred", 9, attempts)
    }

    /** Matches `LivePositionHeartbeat.POLL_TICK_MS`, which is private to that class -- restated
     * here rather than opened up, since this is a simulation of the loop, not the loop. */
    private companion object {
        const val POLL_TICK_SIMULATED_MS = 1_000L
    }
}
