package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken announcements, per spec B5 S3 ("optional spoken fare announcements (MTI parity, WCAG)").
 * Backed by Android's built-in TTS engine — no special hardware needed, so unlike [QrScanner]
 * this is a real implementation rather than a stub.
 *
 * ### Priority queue (navigator pass, 2026-09-03)
 * Originally a single caller ([au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s
 * "Fare now N dollars") with `QUEUE_FLUSH` semantics. The meter screen's navigator
 * ([au.com.threesixty.cabdispatch.ui.screens.hired.MeterNavViewModel]) now also speaks, from a
 * *separate* ViewModel, so two speakers must share one mouth without talking over each other.
 * Every utterance therefore carries a [SpeechPriority] and goes through a [SpeechQueue]:
 * fare announcements are always spoken before any pending nav instruction, and nothing ever
 * interrupts an utterance mid-word — the next one starts when the engine reports the current one
 * done. The one-arg [announce] keeps its original call signature and meaning (a fare
 * announcement) so the existing fare caller is untouched.
 */
interface SpeechAnnouncer {
    /** Fare announcement — the original, priority-[SpeechPriority.FARE] entry point. */
    fun announce(text: String) = announce(text, SpeechPriority.FARE)

    fun announce(text: String, priority: SpeechPriority)

    /** Drops this announcer's *pending* (not yet started) utterances of [priority]. */
    fun flush(priority: SpeechPriority)

    /** Drops every pending utterance this announcer enqueued and stops it if it is mid-utterance. */
    fun flushAll()

    fun shutdown()
}

/**
 * [SpeechAnnouncer] over the platform [TextToSpeech], with one engine shared by every live
 * instance in the process.
 *
 * **Why shared.** [HiredViewModel][au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]
 * and [MeterNavViewModel][au.com.threesixty.cabdispatch.ui.screens.hired.MeterNavViewModel] each
 * construct their own `TextToSpeechAnnouncer(application)`; if each owned a private engine the
 * two would happily speak simultaneously and the priority queue would be meaningless. So each
 * instance is a thin, ref-counted handle onto a process-wide [SharedTtsEngine] that owns the
 * single `TextToSpeech` and the single [SpeechQueue]. The first handle creates the engine, the
 * last [shutdown] tears it down (exactly what the pre-shared code did, just counted). Every
 * utterance is tagged with the handle that enqueued it, so a handle shutting down drops only its
 * own leftovers and stops the engine only if *its* utterance is the one currently speaking.
 */
class TextToSpeechAnnouncer(context: Context) : SpeechAnnouncer {

    private val engine: SharedTtsEngine = SharedTtsEngine.acquire(context.applicationContext)
    private var released = false

    override fun announce(text: String, priority: SpeechPriority) {
        if (released) return
        engine.announce(text, priority, owner = this)
    }

    override fun flush(priority: SpeechPriority) {
        if (released) return
        engine.flush(priority, owner = this)
    }

    override fun flushAll() {
        if (released) return
        engine.flushOwner(this)
    }

    override fun shutdown() {
        if (released) return
        released = true
        engine.flushOwner(this)
        SharedTtsEngine.release(engine)
    }
}

/**
 * The one real `TextToSpeech` in the process plus the [SpeechQueue] that feeds it. Package-private
 * to this file; only reachable through [TextToSpeechAnnouncer] handles.
 *
 * Threading: [announce]/[flush] come from the main thread, the engine's
 * [UtteranceProgressListener] callbacks from a binder thread — every touch of the queue and the
 * `speaking`/`ready` flags is under [lock], and `tts.speak` is always called *outside* it.
 */
internal class SharedTtsEngine private constructor(context: Context) {

    private val lock = Any()
    private val queue = SpeechQueue()
    private var ready = false

    /** The utterance the engine is currently voicing, or null when idle. */
    private var speaking: SpeechQueue.Utterance? = null
    private var refCount = 0

    // Declared before [tts] so it is initialised before the (asynchronous) engine-ready callback
    // below can possibly reference it.
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
    }

    // lateinit (as the pre-queue implementation was) because the init callback below references
    // it and Kotlin cannot prove the assignment has happened by then.
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts.language = Locale.getDefault()
                tts.setOnUtteranceProgressListener(progressListener)
            }
            synchronized(lock) { ready = ok }
            // Anything queued while the engine was still initialising gets its turn now.
            pump()
        }
    }

    fun announce(text: String, priority: SpeechPriority, owner: Any) {
        synchronized(lock) {
            // Only the latest fare figure is worth hearing (matches the original QUEUE_FLUSH
            // behaviour for fare-vs-fare); nav steps are each distinct and stay in order.
            queue.enqueue(text, priority, owner = owner, coalesce = priority == SpeechPriority.FARE)
        }
        pump()
    }

    /** Drops only [owner]'s pending utterances at [priority]; other owners' are untouched. */
    fun flush(priority: SpeechPriority, owner: Any) {
        synchronized(lock) {
            queue.clearIf { it.priority == priority && it.owner === owner }
        }
    }

    /** Drops [owner]'s pending utterances and, if [owner]'s utterance is mid-speech, stops it. */
    fun flushOwner(owner: Any) {
        val stopNow: Boolean
        synchronized(lock) {
            queue.clearOwner(owner)
            stopNow = speaking?.owner === owner
        }
        if (stopNow && ready) {
            // onStop -> finished() -> pump() picks up the next queued utterance, if any.
            tts.stop()
        }
    }

    private fun finished(utteranceId: String?) {
        synchronized(lock) {
            val current = speaking ?: return
            // Ignore stale callbacks for an utterance we've already moved past.
            if (utteranceId != null && utteranceId != current.utteranceId()) return
            speaking = null
        }
        pump()
    }

    /** Starts the next queued utterance if the engine is ready and idle. Safe to call from any thread. */
    private fun pump() {
        val next: SpeechQueue.Utterance
        synchronized(lock) {
            if (!ready || speaking != null) return
            next = queue.poll() ?: return
            speaking = next
        }
        val result = tts.speak(next.text, TextToSpeech.QUEUE_ADD, null, next.utteranceId())
        if (result != TextToSpeech.SUCCESS) {
            // The engine refused it (no callback will come) — skip it and try the next one.
            synchronized(lock) { if (speaking === next) speaking = null }
            pump()
        }
    }

    private fun destroy() {
        synchronized(lock) {
            queue.clearAll()
            speaking = null
        }
        if (ready) tts.stop()
        tts.shutdown()
    }

    private fun SpeechQueue.Utterance.utteranceId(): String =
        "cabdispatch-${priority.name.lowercase()}-$seq"

    companion object {
        private var current: SharedTtsEngine? = null

        @Synchronized
        fun acquire(appContext: Context): SharedTtsEngine {
            val engine = current ?: SharedTtsEngine(appContext).also { current = it }
            engine.refCount++
            return engine
        }

        @Synchronized
        fun release(engine: SharedTtsEngine) {
            engine.refCount--
            if (engine.refCount <= 0 && current === engine) {
                current = null
                engine.destroy()
            }
        }
    }
}
