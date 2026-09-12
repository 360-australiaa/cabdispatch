package au.com.threesixty.cabdispatch.domain

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
}
