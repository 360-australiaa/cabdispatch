package au.com.threesixty.cabdispatch.domain.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins [TracePointRecorder.isNewFix] — the one real de-duplication [HiredViewModel][
 * au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s `nextTracePoint` applies before
 * appending a fix to a live trip's persisted GPS trace. See that object's own class doc for why
 * "record one point per fare-engine tick, de-duped by fix timestamp" — not a distance/time
 * threshold — is the correct policy here. */
class TracePointRecorderTest {

    @Test
    fun `first ever fix (no prior recorded point) is always new`() {
        assertTrue(TracePointRecorder.isNewFix(candidateTimestampMillis = 1_000L, lastRecordedTimestampMillis = null))
    }

    @Test
    fun `a fix with a later timestamp than the last recorded one is new`() {
        assertTrue(TracePointRecorder.isNewFix(candidateTimestampMillis = 2_000L, lastRecordedTimestampMillis = 1_000L))
    }

    @Test
    fun `the exact same fix reused across two ticks is not new`() {
        // The fare engine's 1s coroutine delay firing before the location provider's own ~1 Hz GPS
        // emission has — AppContainer.speedSource.locationFix.value still reads the previous tick's
        // fix, identified here by an unchanged timestamp.
        assertFalse(TracePointRecorder.isNewFix(candidateTimestampMillis = 1_000L, lastRecordedTimestampMillis = 1_000L))
    }

    @Test
    fun `an out-of-order or duplicate-time fix is not new`() {
        // Real GPS fixes never move backwards relative to what was already recorded; treat an
        // equal-or-earlier timestamp as "nothing new to record" rather than special-casing it.
        assertFalse(TracePointRecorder.isNewFix(candidateTimestampMillis = 500L, lastRecordedTimestampMillis = 1_000L))
    }
}
