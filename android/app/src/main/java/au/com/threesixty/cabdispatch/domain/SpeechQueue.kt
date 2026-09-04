package au.com.threesixty.cabdispatch.domain

/**
 * What an utterance is *for*, which decides who goes first when the fare meter and the navigator
 * both want the speaker at once. Higher [rank] is spoken first; ties are FIFO.
 *
 * - [FARE]: the spec-B5 "Fare now N dollars" announcements — the regulated figure the passenger
 *   is entitled to hear, so it always outranks guidance.
 * - [NAV]: turn-by-turn instructions from the meter screen's navigator
 *   ([au.com.threesixty.cabdispatch.ui.screens.hired.MeterNavViewModel]). Queued behind any
 *   pending fare announcement and spoken once the speaker is free — never interleaved with it.
 */
enum class SpeechPriority(val rank: Int) {
    FARE(2),
    NAV(1),
}

/**
 * Pure, Android-free ordering policy for [SpeechAnnouncer]: the priority queue that stops fare
 * announcements and navigation instructions from overlapping or interleaving. Kept as its own
 * class (rather than inlined into [TextToSpeechAnnouncer]) precisely so the ordering can be unit
 * tested on the JVM without a `TextToSpeech` engine — see `SpeechQueueTest`.
 *
 * Ordering rule: [poll] returns the pending utterance with the highest [SpeechPriority.rank];
 * among equal priorities, the one enqueued first. So a fare announcement enqueued while two nav
 * instructions are waiting is spoken before both of them, but never cuts into an utterance that
 * has already started (that is the caller's concern — this class only orders what has *not* been
 * spoken yet).
 *
 * Not thread-safe on its own; [TextToSpeechAnnouncer] guards it with a lock because the TTS
 * engine's completion callbacks arrive on a binder thread.
 */
class SpeechQueue {

    /**
     * One pending utterance. [seq] is a monotonic enqueue counter (the FIFO tiebreaker and a unique
     * id for the TTS utterance-id string); [owner] is an opaque tag for whoever enqueued it, so a
     * departing owner (a ViewModel being cleared) can drop only its own leftovers.
     */
    data class Utterance(
        val text: String,
        val priority: SpeechPriority,
        val seq: Long,
        val owner: Any? = null,
    )

    private val pending = ArrayList<Utterance>()
    private var nextSeq = 0L

    val size: Int get() = pending.size
    fun isEmpty(): Boolean = pending.isEmpty()

    /**
     * Adds [text] at [priority]. With [coalesce], every *pending* utterance of the same priority is
     * dropped first — the fare meter uses this because only the latest "Fare now N dollars" is
     * worth hearing (the pre-queue implementation got the same effect from `QUEUE_FLUSH`); nav
     * instructions do not coalesce, each one is a distinct step.
     */
    fun enqueue(
        text: String,
        priority: SpeechPriority,
        owner: Any? = null,
        coalesce: Boolean = false,
    ): Utterance {
        if (coalesce) pending.removeAll { it.priority == priority }
        val utterance = Utterance(text = text, priority = priority, seq = nextSeq++, owner = owner)
        pending.add(utterance)
        return utterance
    }

    /** Removes and returns the next utterance to speak (highest priority, then FIFO), or null. */
    fun poll(): Utterance? {
        if (pending.isEmpty()) return null
        var best = 0
        for (i in 1 until pending.size) {
            val candidate = pending[i]
            val current = pending[best]
            if (candidate.priority.rank > current.priority.rank ||
                (candidate.priority.rank == current.priority.rank && candidate.seq < current.seq)
            ) {
                best = i
            }
        }
        return pending.removeAt(best)
    }

    /** Drops every pending utterance of [priority]. Does not touch anything already speaking. */
    fun clear(priority: SpeechPriority) {
        pending.removeAll { it.priority == priority }
    }

    /** Drops every pending utterance tagged with [owner] (identity comparison). */
    fun clearOwner(owner: Any) {
        pending.removeAll { it.owner === owner }
    }

    /** Drops every pending utterance matching [predicate]. */
    fun clearIf(predicate: (Utterance) -> Boolean) {
        pending.removeAll(predicate)
    }

    fun clearAll() {
        pending.clear()
    }
}
