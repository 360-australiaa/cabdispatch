package au.com.threesixty.cabdispatch.ui.overlays

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression, 2026-09-08, seen on the tablet from a clean install.
 *
 * `fullScreenGateVisible` suppresses the app-level status chips (TABLET NOT REGISTERED, FLEET
 * LOCKED, UPDATE PENDING) while a full-screen gate is up, because those screens have no header for
 * the chips to measure clearance against and the chips otherwise land on the screen's own headline.
 *
 * It was a plain boolean, and Compose runs an incoming screen's `DisposableEffect` BEFORE the
 * outgoing screen's `onDispose`. So disclaimer -> readiness ran `set(true)` and then `set(false)`:
 * the screen that had just LEFT got the last word, and TABLET NOT REGISTERED sat across the words
 * "Set up this tablet". A count is order-independent, which is the whole point.
 */
class ChromeMetricsGateTest {

    /** The metric is a global object shared across tests, so start each case from a known floor. */
    @Before
    fun drain() {
        repeat(8) { CaptainChromeMetrics.setFullScreenGateVisible(false) }
    }

    private fun acquire() = CaptainChromeMetrics.setFullScreenGateVisible(true)
    private fun release() = CaptainChromeMetrics.setFullScreenGateVisible(false)

    @Test
    fun `a gate raises and lowers it`() {
        acquire()
        assertTrue(CaptainChromeMetrics.fullScreenGateVisible)
        release()
        assertFalse(CaptainChromeMetrics.fullScreenGateVisible)
    }

    @Test
    fun `the outgoing screen disposing after the incoming one enters does not lower the gate`() {
        acquire() // disclaimer is on screen
        acquire() // readiness enters -- Compose runs this BEFORE the disclaimer's onDispose
        release() // disclaimer finally disposes

        assertTrue(
            "the gate must stay up while the readiness screen is still composed",
            CaptainChromeMetrics.fullScreenGateVisible,
        )

        release() // readiness leaves
        assertFalse(CaptainChromeMetrics.fullScreenGateVisible)
    }

    @Test
    fun `an unbalanced release cannot drive the count negative and swallow the next gate`() {
        release()
        release()
        acquire()
        assertTrue(CaptainChromeMetrics.fullScreenGateVisible)
        release()
        assertFalse(CaptainChromeMetrics.fullScreenGateVisible)
    }
}
