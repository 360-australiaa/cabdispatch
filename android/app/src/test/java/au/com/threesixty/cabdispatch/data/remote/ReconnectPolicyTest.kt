package au.com.threesixty.cabdispatch.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReconnectPolicy] — the shared exponential-backoff-with-jitter helper collapsing the
 * ~32 scattered `reconnect` sites this codebase had onto one implementation (W4 task 5, 2026-09-12
 * optimisation plan). Pure math, no coroutines/Android, so every test below drives it directly.
 */
class ReconnectPolicyTest {

    /** Deterministic "random" -- always the same fraction, so jitter is an exact, assertable
     * number rather than a range. */
    private fun fixedJitter(fraction: Double) = ReconnectPolicy(random = { fraction })

    @Test
    fun `the first delay is the floor, plus jitter`() {
        val policy = fixedJitter(0.0) // zero jitter -- exactly the floor.
        assertEquals(ReconnectPolicy.MIN_DELAY_MS, policy.nextDelayMillis())
    }

    @Test
    fun `delays double on every call, capped at the ceiling`() {
        val policy = fixedJitter(0.0)
        val delays = generateSequence { policy.nextDelayMillis() }.take(10).toList()

        // 1s, 2s, 4s, 8s, 16s, 32s, 60s (capped), 60s, 60s, 60s.
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun `jitter adds up to the configured fraction on top of the base delay, never below it`() {
        val policy = fixedJitter(1.0) // maximum jitter every time.
        val first = policy.nextDelayMillis()
        // base 1000ms + up to 20% jitter (JITTER_FRACTION) = up to 1200ms.
        assertTrue("jitter must never reduce the delay below the base", first >= ReconnectPolicy.MIN_DELAY_MS)
        assertTrue("jitter must be bounded (20% here, at max randomness)", first <= 1_200L)
    }

    @Test
    fun `reset collapses the backoff back to the floor`() {
        val policy = fixedJitter(0.0)
        repeat(5) { policy.nextDelayMillis() } // climb well past the floor.
        policy.reset()
        assertEquals(
            "a reset connection's NEXT failure must not inherit the old outage's climbed-up backoff",
            ReconnectPolicy.MIN_DELAY_MS,
            policy.nextDelayMillis(),
        )
    }

    @Test
    fun `two independent policy instances never share state`() {
        val jobsSocketPolicy = fixedJitter(0.0)
        val messagesSocketPolicy = fixedJitter(0.0)

        repeat(4) { jobsSocketPolicy.nextDelayMillis() } // jobs socket flapping badly...
        // ...must not penalise the completely independent messages socket's own first attempt.
        assertEquals(ReconnectPolicy.MIN_DELAY_MS, messagesSocketPolicy.nextDelayMillis())
    }
}
