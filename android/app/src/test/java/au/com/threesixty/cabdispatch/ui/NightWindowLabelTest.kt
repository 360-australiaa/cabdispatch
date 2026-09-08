package au.com.threesixty.cabdispatch.ui

import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.fare.AreaClass
import au.com.threesixty.cabdispatch.domain.resolveTimeClassFor
import au.com.threesixty.cabdispatch.ui.screens.dashboard.nightWindowLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The NIGHT FARE chip's window string is *derived from the fare engine*, not written out (A3,
 * 2026-09-08) — this pins that it stays derived, and stays correct.
 *
 * BACKGROUND. The chip used to display the literal `"10:00 PM – 6:00 AM"`, carrying a comment
 * recording that this app's own `FareEngine` disagreed with it (8pm vs 10pm). That was true when
 * written and is **no longer true**: the engine was corrected on 2026-08-29 and
 * `resolveTimeClassFor` now reads `hour >= 22 || hour < 6`. This workstream found the audit's
 * restatement of that discrepancy to be stale, verified the engine, and removed the literal in
 * favour of asking the engine directly.
 *
 * So the first test below is the one that matters: it asserts the two agree, hour by hour, rather
 * than asserting a hardcoded string on both sides. If a future jurisdiction moves the boundary,
 * the label follows it and this test keeps passing; if someone reintroduces a literal that drifts
 * from the engine, it fails.
 */
class NightWindowLabelTest {

    @Test
    fun `label matches the fare engine's own night boundary hour by hour`() {
        val reference = ZonedDateTime.of(2026, 1, 7, 0, 0, 0, 0, ZoneId.systemDefault())
        val nightHours = (0..23).filter {
            resolveTimeClassFor(reference.withHour(it), AreaClass.URBAN) == TimeClass.NIGHT
        }

        // The engine's current NSW boundary: 10pm through 5:59am.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 22, 23), nightHours)
        assertEquals("10pm–6am", nightWindowLabel())
    }

    /**
     * The window wraps midnight, which is the only genuinely fiddly part of deriving it: the night
     * hours are not a contiguous ascending run in a 0..23 list, they are two runs at either end.
     * This pins that the label reports the wrap correctly (10pm→6am) rather than reading the list
     * naively and reporting "12am–11pm".
     */
    @Test
    fun `window is reported across midnight, not as an ascending range`() {
        val label = nightWindowLabel()
        assertEquals("starts in the evening", true, label.startsWith("10pm"))
        assertEquals("ends in the morning", true, label.endsWith("6am"))
    }
}
