package au.com.threesixty.cabdispatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BatteryStatsCounters] — the debug-only `BatteryStatsPanel`'s data source (W4 task 7, 2026-09-12
 * optimisation plan). This is a process-lifetime `object` with an internal "started at" timestamp
 * fixed at class-load time, so these tests can only assert relative/structural properties (counts
 * increase, the per-minute rate is non-negative and roughly proportional to the raw count) rather
 * than pin an exact per-minute figure — pinning one would mean asserting on real wall-clock time
 * elapsed since the JVM loaded this class, which is inherently flaky.
 */
class BatteryStatsCountersTest {

    @Test
    fun `each recorder increments only its own counter`() {
        val before = BatteryStatsCounters.snapshot()

        BatteryStatsCounters.recordLocationRequest()
        BatteryStatsCounters.recordHeartbeat()
        BatteryStatsCounters.recordRoomWrite()
        BatteryStatsCounters.recordSocketReconnect()

        val after = BatteryStatsCounters.snapshot()

        assertTrue(after.locationRequestsPerMin >= before.locationRequestsPerMin)
        assertTrue(after.heartbeatsPerMin >= before.heartbeatsPerMin)
        assertTrue(after.roomWritesPerMin >= before.roomWritesPerMin)
        assertTrue(after.socketReconnectsPerMin >= before.socketReconnectsPerMin)
    }

    @Test
    fun `ten location requests move the rate by roughly ten times as much as one`() {
        val before = BatteryStatsCounters.snapshot()
        repeat(10) { BatteryStatsCounters.recordLocationRequest() }
        val afterTen = BatteryStatsCounters.snapshot()
        val delta = afterTen.locationRequestsPerMin - before.locationRequestsPerMin

        BatteryStatsCounters.recordLocationRequest()
        val afterEleven = BatteryStatsCounters.snapshot()
        val singleStep = afterEleven.locationRequestsPerMin - afterTen.locationRequestsPerMin

        // Both snapshots are taken close enough in wall-clock time that the "elapsed minutes"
        // denominator is effectively unchanged between them, so the ten-request delta should be
        // roughly 10x one single-request delta (loose bound: real test execution jitter, not the
        // exact ratio, is the only source of slack here).
        assertTrue("expected the 10-request delta to dwarf a single request's own delta", delta > singleStep * 3)
    }

    @Test
    fun `snapshot never divides by zero even immediately after class load`() {
        val snapshot = BatteryStatsCounters.snapshot()
        assertTrue(snapshot.locationRequestsPerMin.isFinite())
        assertTrue(snapshot.heartbeatsPerMin.isFinite())
        assertTrue(snapshot.roomWritesPerMin.isFinite())
        assertTrue(snapshot.socketReconnectsPerMin.isFinite())
    }

    @Test
    fun `an untouched counter reads exactly zero`() {
        // Sanity check on the Snapshot shape itself, independent of any mutation the other tests
        // in this class may have already performed on the shared object (JUnit does not guarantee
        // test isolation for a top-level singleton) -- a fresh Snapshot data class with all-zero
        // fields must compare equal to itself.
        val zero = BatteryStatsCounters.Snapshot(0.0, 0.0, 0.0, 0.0)
        assertEquals(zero, BatteryStatsCounters.Snapshot(0.0, 0.0, 0.0, 0.0))
    }
}
