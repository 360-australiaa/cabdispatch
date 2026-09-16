package au.com.threesixty.cabdispatch.domain.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveLocationRequestMode] — the pure decision behind [RealLocationProvider]'s request
 * priority/interval gating (2026-09-12 optimisation plan, W4 task 2). See that function's own doc
 * for why hired/duress unconditionally wins over the on-shift check.
 */
class RealLocationProviderModeTest {

    @Test
    fun `not on shift, not hired -- no request at all`() {
        assertEquals(
            LocationRequestMode.OFF,
            resolveLocationRequestMode(onShift = false, hiredOrDuress = false),
        )
    }

    @Test
    fun `on shift, not hired -- balanced power`() {
        assertEquals(
            LocationRequestMode.BALANCED,
            resolveLocationRequestMode(onShift = true, hiredOrDuress = false),
        )
    }

    @Test
    fun `hired or duress -- high accuracy, unchanged fare-engine behaviour`() {
        assertEquals(
            LocationRequestMode.HIGH_ACCURACY,
            resolveLocationRequestMode(onShift = true, hiredOrDuress = true),
        )
    }

    @Test
    fun `hired wins even if the shift state somehow reads false`() {
        // The real flow can't produce a trip with no shift open, but this class must never be the
        // reason a fare loses 1 Hz fixes if that ever isn't true -- see the function's own doc.
        assertEquals(
            LocationRequestMode.HIGH_ACCURACY,
            resolveLocationRequestMode(onShift = false, hiredOrDuress = true),
        )
    }

    @Test
    fun `not on shift but charging -- balanced power, not OFF`() {
        // Owner decision, 2026-09-16 field report: this fleet's tablets are permanently mounted
        // and powered from the vehicle's ignition, so a charging tablet has no battery budget the
        // OFF tier was protecting -- and the dot going dark before a shift starts read to a driver
        // as "GPS is broken", not "not asked yet". See resolveLocationRequestMode's own doc.
        assertEquals(
            LocationRequestMode.BALANCED,
            resolveLocationRequestMode(onShift = false, hiredOrDuress = false, charging = true),
        )
    }

    @Test
    fun `not on shift and not charging -- still OFF, the original battery-saving case`() {
        // A genuinely unplugged, logged-off tablet (e.g. taken inside overnight) must keep the
        // original battery-saving behaviour -- charging is an independent, additional trigger, not
        // a replacement for the on-shift one.
        assertEquals(
            LocationRequestMode.OFF,
            resolveLocationRequestMode(onShift = false, hiredOrDuress = false, charging = false),
        )
    }

    @Test
    fun `hired still wins over charging`() {
        assertEquals(
            LocationRequestMode.HIGH_ACCURACY,
            resolveLocationRequestMode(onShift = true, hiredOrDuress = true, charging = true),
        )
    }
}
