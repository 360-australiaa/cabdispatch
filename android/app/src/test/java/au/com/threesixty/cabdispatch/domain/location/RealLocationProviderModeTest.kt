package au.com.threesixty.cabdispatch.domain.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveLocationRequestMode] — the pure decision behind [RealLocationProvider]'s request
 * priority/interval gating. Owner decision, 2026-09-17: location is requested unconditionally
 * (on shift or not, charging or on battery) — the only remaining branch is whether a fare or
 * duress event escalates the request to high accuracy. See that function's own doc for the
 * two earlier, now-superseded on-shift/charging tiers this replaced.
 */
class RealLocationProviderModeTest {

    @Test
    fun `not hired -- balanced power, regardless of shift or charging state`() {
        assertEquals(LocationRequestMode.BALANCED, resolveLocationRequestMode(hiredOrDuress = false))
    }

    @Test
    fun `hired or duress -- high accuracy, unchanged fare-engine behaviour`() {
        assertEquals(LocationRequestMode.HIGH_ACCURACY, resolveLocationRequestMode(hiredOrDuress = true))
    }
}
