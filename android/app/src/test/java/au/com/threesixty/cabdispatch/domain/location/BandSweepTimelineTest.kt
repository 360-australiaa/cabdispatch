package au.com.threesixty.cabdispatch.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the band-sweep route's timeline against what the tablet showed (2026-09-08): motion for
 * ~29 s, then 0 km/h and "finished" for the rest of the run. If these pass, the route maths are
 * not the cause and the fault is at runtime (the simulator job, or the source switch).
 */
class BandSweepTimelineTest {
    private val route = SimulatedRoutes.bandSweep()

    @Test
    fun `the route is far longer than the profile drives, so it never finishes early`() {
        val profile = route.speedProfile!!
        val driven = profile.distanceM(profile.totalDurationS)
        assertTrue("route ${route.lengthM} m must exceed driven ${driven} m", route.lengthM > driven * 2)
        for (t in listOf(10.0, 29.0, 30.0, 45.0, 60.0, 90.0, 120.0, 150.0, 170.0)) {
            val p = route.positionAt(t)
            assertFalse("finished at t=$t", p.finished)
            assertFalse("NaN lat at t=$t", p.point.lat.isNaN())
        }
    }

    @Test
    fun `speed follows the profile through both bands and back`() {
        assertEquals(3.0, route.speedAt(10.0), 0.01)            // WAITING crawl
        assertTrue(route.speedAt(45.0) > 26.0)                  // past the tariff line
        assertEquals(45.0, route.speedAt(50.0), 0.01)           // DISTANCE hold
        assertEquals(90.0, route.speedAt(80.0), 0.01)           // FAST hold
        assertTrue(route.speedAt(120.0) in 56.0..58.0)          // dwell at 57
        assertEquals(0.0, route.speedAt(140.0), 0.01)           // stopped at the end
    }
}
