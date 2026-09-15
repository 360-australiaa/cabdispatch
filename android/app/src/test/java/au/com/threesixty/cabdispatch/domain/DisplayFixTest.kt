package au.com.threesixty.cabdispatch.domain

import org.junit.Assert.assertSame
import org.junit.Test

/** [LocationFix.pickDisplayFix] -- the tablet map's own "which fix do I draw" rule (2026-09-15). */
class DisplayFixTest {
    private fun fix(receivedAtNanos: Long, lat: Double = -33.8) = LocationFix(
        lat = lat,
        lng = 151.2,
        speedKmh = 60.0,
        accuracyM = 5f,
        timestampMillis = 0L,
        receivedAtNanos = receivedAtNanos,
    )

    private val second = 1_000_000_000L

    @Test
    fun `a fresh real fix always wins`() {
        val real = fix(receivedAtNanos = 10 * second)
        val est = fix(receivedAtNanos = 11 * second, lat = -33.9)
        assertSame(real, LocationFix.pickDisplayFix(real, est, nowNanos = 12 * second))
    }

    @Test
    fun `a stale real fix yields to a newer estimate`() {
        val real = fix(receivedAtNanos = 10 * second)
        val est = fix(receivedAtNanos = 19 * second, lat = -33.9)
        assertSame(est, LocationFix.pickDisplayFix(real, est, nowNanos = 20 * second))
    }

    @Test
    fun `a stale real fix stays when the estimate is older still`() {
        val real = fix(receivedAtNanos = 10 * second)
        val est = fix(receivedAtNanos = 9 * second, lat = -33.9)
        assertSame(real, LocationFix.pickDisplayFix(real, est, nowNanos = 20 * second))
    }

    @Test
    fun `no real fix at all shows the estimate`() {
        val est = fix(receivedAtNanos = 9 * second)
        assertSame(est, LocationFix.pickDisplayFix(null, est, nowNanos = 20 * second))
    }
}
