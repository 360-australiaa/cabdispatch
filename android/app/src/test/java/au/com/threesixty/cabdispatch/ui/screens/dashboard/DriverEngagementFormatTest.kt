package au.com.threesixty.cabdispatch.ui.screens.dashboard

import au.com.threesixty.cabdispatch.ui.screens.dashboard.StarFill.EMPTY
import au.com.threesixty.cabdispatch.ui.screens.dashboard.StarFill.FULL
import au.com.threesixty.cabdispatch.ui.screens.dashboard.StarFill.HALF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Plain-JVM tests for [DriverEngagementFormat] — the pure display mapping behind the dashboard's
 * WALLET / RATING / ANNOUNCEMENTS / INCENTIVE tiles (`EngagementTiles.kt`). Same shape as
 * `HudRollTest`: no Compose/Android on the classpath.
 */
class DriverEngagementFormatTest {

    // --- star fill mapping --------------------------------------------------------------------

    @Test
    fun `4_8 fills all five stars`() {
        assertEquals(listOf(FULL, FULL, FULL, FULL, FULL), DriverEngagementFormat.starFills(4.8))
    }

    @Test
    fun `4_3 fills four and a half`() {
        assertEquals(listOf(FULL, FULL, FULL, FULL, HALF), DriverEngagementFormat.starFills(4.3))
    }

    @Test
    fun `4_1 leaves the fifth star empty`() {
        assertEquals(listOf(FULL, FULL, FULL, FULL, EMPTY), DriverEngagementFormat.starFills(4.1))
    }

    @Test
    fun `2_5 is two full plus a half`() {
        assertEquals(listOf(FULL, FULL, HALF, EMPTY, EMPTY), DriverEngagementFormat.starFills(2.5))
    }

    @Test
    fun `no average means every star empty, never a stand-in score`() {
        assertEquals(listOf(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY), DriverEngagementFormat.starFills(null))
        assertEquals(listOf(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY), DriverEngagementFormat.starFills(0.0))
    }

    @Test
    fun `out-of-range and non-finite averages are clamped`() {
        assertEquals(listOf(FULL, FULL, FULL, FULL, FULL), DriverEngagementFormat.starFills(9.0))
        assertEquals(listOf(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY), DriverEngagementFormat.starFills(-1.0))
        assertEquals(listOf(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY), DriverEngagementFormat.starFills(Double.NaN))
    }

    // --- incentive progress fraction ------------------------------------------------------------

    @Test
    fun `progress fraction is completed over target`() {
        assertEquals(0.65f, DriverEngagementFormat.incentiveFraction(26, 40), 1e-6f)
        assertEquals(0f, DriverEngagementFormat.incentiveFraction(0, 40), 1e-6f)
        assertEquals(1f, DriverEngagementFormat.incentiveFraction(40, 40), 1e-6f)
    }

    @Test
    fun `progress fraction clamps overshoot and guards a zero target`() {
        assertEquals(1f, DriverEngagementFormat.incentiveFraction(55, 40), 1e-6f)
        assertEquals(0f, DriverEngagementFormat.incentiveFraction(5, 0), 1e-6f)
        assertEquals(0f, DriverEngagementFormat.incentiveFraction(-3, 40), 1e-6f)
    }

    // --- money / count formatting ---------------------------------------------------------------

    @Test
    fun `money strings format with grouping, two decimals and a leading sign`() {
        assertEquals("\$1,264.35", DriverEngagementFormat.formatAud("1264.35"))
        assertEquals("\$0.00", DriverEngagementFormat.formatAud("0"))
        assertEquals("-\$12.00", DriverEngagementFormat.formatAud("-12"))
        assertEquals("—", DriverEngagementFormat.formatAud("not money"))
        assertEquals("—", DriverEngagementFormat.formatAud(null))
    }

    @Test
    fun `ledger amounts always carry a sign`() {
        assertEquals("+\$32.40", DriverEngagementFormat.formatSignedAud("32.40"))
        assertEquals("-\$250.00", DriverEngagementFormat.formatSignedAud("-250.00"))
        assertEquals("\$0.00", DriverEngagementFormat.formatSignedAud("0.00"))
    }

    @Test
    fun `average and count labels`() {
        assertEquals("4.8", DriverEngagementFormat.formatAverage("4.80"))
        assertEquals("4.8", DriverEngagementFormat.formatAverage("4.75"))
        assertNull(DriverEngagementFormat.formatAverage(null))
        assertEquals("1,240 ratings", DriverEngagementFormat.ratingCountLabel(1240))
        assertEquals("1 rating", DriverEngagementFormat.ratingCountLabel(1))
    }

    // --- timestamps -----------------------------------------------------------------------------

    @Test
    fun `relative time handles offset, zulu and naive-UTC timestamps`() {
        val now = Instant.parse("2026-09-04T10:00:00Z")
        assertEquals("Just now", DriverEngagementFormat.relativeTime("2026-09-04T09:59:40Z", now))
        assertEquals("5 min ago", DriverEngagementFormat.relativeTime("2026-09-04T09:55:00+00:00", now))
        assertEquals("3 hr ago", DriverEngagementFormat.relativeTime("2026-09-04T07:00:00", now))
        assertEquals("Yesterday", DriverEngagementFormat.relativeTime("2026-09-03T08:00:00Z", now))
        assertEquals("4 days ago", DriverEngagementFormat.relativeTime("2026-08-31T09:00:00Z", now))
        assertEquals("", DriverEngagementFormat.relativeTime("garbage", now))
    }

    @Test
    fun `ends-in label counts forward and says Ended once past`() {
        val now = Instant.parse("2026-09-04T10:00:00Z")
        assertEquals("Ends in 3 days", DriverEngagementFormat.endsInLabel("2026-09-07T12:00:00Z", now))
        assertEquals("Ends in 5 hr", DriverEngagementFormat.endsInLabel("2026-09-04T15:30:00Z", now))
        assertEquals("Ended", DriverEngagementFormat.endsInLabel("2026-09-04T09:00:00Z", now))
    }
}
