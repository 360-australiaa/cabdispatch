package au.com.threesixty.cabdispatch.data.remote

/**
 * Shared exponential-backoff reconnect policy (2026-09-12 optimisation plan, W4 task 5 — "there
 * are ~32 scattered `reconnect`-related sites in this codebase... collapse the various ad-hoc
 * reconnect loops onto ONE shared, testable reconnect-policy helper").
 *
 * Before this pass, every WebSocket consumer that wanted resilience hand-rolled its own retry loop
 * around [RealtimeSocket.connect] (which deliberately does not retry itself — see that class's own
 * doc): [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel.observeLive]
 * and [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel.observeLive] both wrote
 * the identical `while (isActive) { ...; delay(3000) }` flat 3-second backoff, independently, with
 * no jitter, no cap, and no awareness of whether the device even had a network connection at all —
 * a socket with no connectivity would spin a reconnect attempt (and the exception it throws) every
 * 3 seconds forever. This class is the ONE implementation both now share, via
 * [RealtimeSocket.connectWithReconnect].
 *
 * Pure math, no coroutines/Android dependency — directly unit-testable (see
 * `ReconnectPolicyTest`) against explicit attempt counts rather than real elapsed time.
 *
 * @param minDelayMs The first retry's backoff floor — 1s.
 * @param maxDelayMs The backoff ceiling once repeated attempts keep failing — 60s.
 * @param jitterFraction Up to this fraction of the current backoff is added as random jitter on
 * top of it, so many devices reconnecting after the same outage (a cell tower coming back, a
 * depot Wi-Fi AP rebooting) don't all retry in the same instant and hammer the server together.
 * @param random Injectable so a test can pin the "random" component to something deterministic;
 * defaults to [kotlin.random.Random.Default].
 */
class ReconnectPolicy(
    private val minDelayMs: Long = MIN_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val jitterFraction: Double = JITTER_FRACTION,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) {

    @Volatile
    private var currentDelayMs: Long = minDelayMs

    /**
     * The backoff to wait before the NEXT connection attempt (including jitter), and advances the
     * internal state so the attempt AFTER that one waits longer still — doubling on every call,
     * capped at [maxDelayMs]. Called once per failed/ended connection attempt, never more than
     * once per attempt (calling it twice for the same failure would double-advance the backoff for
     * no reason).
     */
    fun nextDelayMillis(): Long {
        val base = currentDelayMs
        currentDelayMs = (currentDelayMs * 2).coerceAtMost(maxDelayMs)
        val jitter = (base * jitterFraction * random()).toLong()
        return base + jitter
    }

    /**
     * Collapses the backoff back to [minDelayMs] — called once a connection is judged healthy
     * (see [RealtimeSocket.connectWithReconnect]'s [CONNECTED_GRACE_MS] doc for exactly when).
     * Without this, one bad outage would leave every LATER, unrelated reconnect waiting out
     * whatever the backoff had climbed to during the outage that has already ended.
     */
    fun reset() {
        currentDelayMs = minDelayMs
    }

    companion object {
        const val MIN_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 60_000L
        private const val JITTER_FRACTION = 0.2

        /**
         * How long a connection must stay open, error-free, before [reset] is called — see
         * [RealtimeSocket.connectWithReconnect]'s own doc for exactly how this is measured. Long
         * enough that a socket which connects and immediately drops (a flapping link) is not
         * mistaken for a healthy one; short enough that an ordinary quiet minute between job
         * offers/messages does not leave the backoff sitting at whatever it climbed to during the
         * outage that preceded this success. Chosen, not derived — same flagging convention this
         * codebase's other background-loop intervals already use (see
         * [au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat]'s "Adaptive cadence" doc).
         */
        const val CONNECTED_GRACE_MS = 5_000L
    }
}
