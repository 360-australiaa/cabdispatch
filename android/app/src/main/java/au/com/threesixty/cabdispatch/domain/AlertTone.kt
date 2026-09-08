package au.com.threesixty.cabdispatch.domain

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * A short, non-speech notification tone — the literal "beep" half of the automatic toll-detection
 * product requirement ("when vehicle move from that location diameter, automatically it will make
 * beep sound and show toll has been added").
 *
 * **Why this exists alongside [SpeechAnnouncer], rather than reusing it.** The spoken alert
 * ("Cross City Tunnel toll added — $7.41") carries far more information and is the better signal
 * when the driver has speech on. But spoken fare announcements are an opt-in accessibility feature
 * (spec B5 S3) and are OFF by default — `HiredViewModel._speechEnabled` starts `false` — so on a
 * freshly-installed tablet the spoken alert produces silence. A toll is money added to the fare
 * without the driver touching anything; it is the one event that must be audible even to a driver
 * who has deliberately silenced the running "Fare now N dollars" ticker, which is chatter of a
 * completely different kind. So the beep is unconditional and the speech stays gated: two
 * different signals, deliberately not sharing one mute.
 *
 * Kept behind an interface for the same reason [SpeechAnnouncer] is: so a ViewModel test can
 * assert "a tone was played, exactly once per alert" on a plain JVM with no Android audio stack.
 */
interface AlertTone {
    /** Plays the toll-detected tone. Must never throw and must never block the caller. */
    fun tollDetected()

    fun shutdown()
}

/**
 * [AlertTone] over the platform [ToneGenerator] — no bundled audio asset, no `SoundPool` warm-up,
 * and nothing to keep loaded between trips.
 *
 * Played on [AudioManager.STREAM_NOTIFICATION] rather than the music/alarm streams: this is a
 * notification, it follows the tablet's notification volume, and it will not duck or fight the
 * turn-by-turn navigation audio the meter screen also produces.
 *
 * [ToneGenerator] construction genuinely throws on some devices when the audio stream is
 * unavailable (a real, documented `RuntimeException`, not a defensive-coding habit), and a failure
 * to make a sound must never take down a live fare — so construction and playback are both
 * contained, and the meter simply falls back to the on-screen banner alone.
 */
class ToneGeneratorAlertTone : AlertTone {

    private val generator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_PERCENT)
    }.onFailure { Log.w(TAG, "Alert tones unavailable on this device; falling back to the on-screen banner", it) }
        .getOrNull()

    override fun tollDetected() {
        val tone = generator ?: return
        // Two short beeps rather than one, because a single notification blip is easy to mistake
        // for an unrelated system notification on a tablet mounted in a moving vehicle. ACK is a
        // brief double tone; ToneGenerator plays it asynchronously, so this returns immediately.
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_ACK, DURATION_MS) }
            .onFailure { Log.w(TAG, "Failed to play the toll-detected tone", it) }
    }

    override fun shutdown() {
        runCatching { generator?.release() }
    }

    private companion object {
        const val TAG = "AlertTone"

        /** Percent of the notification stream's volume. Loud enough to carry over road noise in a
         * moving vehicle, short of being startling — the tablet's own notification volume is still
         * the driver's master control. */
        const val VOLUME_PERCENT = 80

        /** Milliseconds. Long enough for both beeps of TONE_PROP_ACK to land, short enough that it
         * cannot overlap the spoken alert that follows it when speech is enabled. */
        const val DURATION_MS = 300
    }
}
