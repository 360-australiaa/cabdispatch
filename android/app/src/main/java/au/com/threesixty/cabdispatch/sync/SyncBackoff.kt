package au.com.threesixty.cabdispatch.sync

/**
 * Retry policy for one outbox row (finding S1).
 *
 * Kept as a pure object with no Android or Room dependency so the schedule can be asserted directly
 * in `OutboxDrainerTest` — the same reason [OutboxDrainer] itself is a plain class behind a Ports
 * seam.
 *
 * ### Why the row needs its own backoff at all
 * WorkManager already applies exponential backoff to the *worker*, which is why this looked
 * covered. It isn't: the reconnect trigger enqueues a fresh one-time worker on every
 * `onAvailable`, and a fresh worker starts at attempt zero with no backoff. A tablet on a marginal
 * connection — the normal case in a car — flaps repeatedly, and each flap fired an immediate,
 * unthrottled retry of a row that had already failed. The backoff has to live on the row, because
 * the row is the thing that keeps failing.
 */
object SyncBackoff {

    /**
     * How many times a row is retried before it is dead-lettered.
     *
     * Five, per the workstream brief. The number matters less than the existence of a bound: the
     * bug being fixed is *unbounded* retry of a row the server will never accept, which both wastes
     * the radio forever and — because the batch is oldest-first and capped at
     * [OutboxDrainer.DEFAULT_BATCH_SIZE] — holds a slot in front of trips that would sync fine.
     */
    const val MAX_ATTEMPTS = 5

    /** First retry lands 30 s out. Short enough that an ordinary transient blip (a dropped packet,
     * a server restart) still clears on the next trigger rather than stalling a shift's sync. */
    const val BASE_DELAY_MS = 30_000L

    /** Ceiling on the doubling. With [MAX_ATTEMPTS] = 5 the schedule never actually reaches this,
     * but it bounds the delay if the cap is ever raised. */
    const val MAX_DELAY_MS = 60 * 60 * 1000L

    /**
     * When a row that has now failed [attempts] times may next be tried.
     *
     * Schedule for attempts 1..5: 30 s, 1 m, 2 m, 4 m, 8 m. [attempts] is the count *after* the
     * failure being recorded, so the first failure passes 1 and waits [BASE_DELAY_MS].
     */
    fun delayMsAfter(attempts: Int): Long {
        if (attempts <= 0) return 0
        val shift = (attempts - 1).coerceAtMost(MAX_SHIFT)
        val delay = BASE_DELAY_MS shl shift
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    /** Absolute epoch-millis deadline for [delayMsAfter], as stored in
     * [au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity.nextAttemptAt]. */
    fun nextAttemptAt(now: Long, attempts: Int): Long = now + delayMsAfter(attempts)

    /** True once a row has burned its retries and belongs in the dead-letter state. */
    fun isExhausted(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS

    /** Guards the `shl` against overflowing a Long if the cap is ever raised a long way. */
    private const val MAX_SHIFT = 20
}
