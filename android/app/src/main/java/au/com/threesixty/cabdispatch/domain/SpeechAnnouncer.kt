package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken fare announcements, per spec B5 S3 ("optional spoken fare
 * announcements (MTI parity, WCAG)"). Backed by Android's built-in TTS
 * engine — no special hardware needed, so unlike [QrScanner] this is a real
 * implementation rather than a stub.
 */
interface SpeechAnnouncer {
    fun announce(text: String)
    fun shutdown()
}

class TextToSpeechAnnouncer(context: Context) : SpeechAnnouncer {

    private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.getDefault()
            }
        }
    }

    override fun announce(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cabdispatch-fare-announcement")
    }

    override fun shutdown() {
        if (ready) tts.stop()
        tts.shutdown()
    }
}
