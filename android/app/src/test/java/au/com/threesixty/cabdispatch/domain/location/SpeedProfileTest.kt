package au.com.threesixty.cabdispatch.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The varying-speed profile behind the band-sweep simulator route.
 *
 * Worth testing rather than eyeballing on the tablet: `distanceM` is an integral, and getting it
 * wrong would put the simulated taxi in the wrong PLACE — a subtler failure than the wrong speed,
 * because the route would still look like it was driving.
 */
class SpeedProfileTest {

    private val ramp = SpeedProfile(
        listOf(
            SpeedProfile.Segment(10.0, 0.0, 36.0),   // 0 -> 10 m/s over 10s
            SpeedProfile.Segment(10.0, 36.0, 36.0),  // hold 10 m/s
            SpeedProfile.Segment(10.0, 36.0, 0.0),   // back down
        ),
    )

    @Test
    fun `speed follows the ramp, including at the segment boundaries`() {
        assertEquals(0.0, ramp.speedAt(0.0), 1e-9)
        assertEquals(18.0, ramp.speedAt(5.0), 1e-9)
        assertEquals(36.0, ramp.speedAt(10.0), 1e-9)
        assertEquals(36.0, ramp.speedAt(15.0), 1e-9)
        assertEquals(18.0, ramp.speedAt(25.0), 1e-9)
        assertEquals(0.0, ramp.speedAt(30.0), 1e-9)
    }

    @Test
    fun `past the end it holds the final speed rather than wrapping`() {
        // Matches the constant-speed path, where a finished route leaves the vehicle where it
        // stopped instead of teleporting back to the start.
        assertEquals(0.0, ramp.speedAt(120.0), 1e-9)
    }

    @Test
    fun `distance is the area under the ramp`() {
        // 10s accelerating 0 -> 10 m/s is a triangle: 50m.
        assertEquals(50.0, ramp.distanceM(10.0), 1e-6)
        // Plus 10s at 10 m/s.
        assertEquals(150.0, ramp.distanceM(20.0), 1e-6)
        // Plus the decelerating triangle.
        assertEquals(200.0, ramp.distanceM(30.0), 1e-6)
    }

    @Test
    fun `distance mid-segment integrates the partial ramp, not the whole one`() {
        // Halfway up the first ramp: 5s at an average of 2.5 m/s = 12.5m. Multiplying the
        // instantaneous speed by elapsed time instead would give 25m and put the taxi twice as far
        // along the route as it really is.
        assertEquals(12.5, ramp.distanceM(5.0), 1e-6)
    }

    @Test
    fun `distance never goes backwards`() {
        var previous = -1.0
        for (tenths in 0..400) {
            val d = ramp.distanceM(tenths / 10.0)
            assertTrue("t=${tenths / 10.0}", d >= previous)
            previous = d
        }
    }

    @Test
    fun `a constant-speed route is untouched by any of this`() {
        // Every existing toll route has no profile, and must behave exactly as it did before.
        val plain = SimulatedRoutes.plainDrive(speedKmh = 50.0)

        assertEquals(50.0, plain.speedAt(0.0), 1e-9)
        assertEquals(50.0, plain.speedAt(37.5), 1e-9)
    }

    @Test
    fun `the band sweep crosses both thresholds twice and ends stopped`() {
        val route = SimulatedRoutes.bandSweep()
        val profile = requireNotNull(route.speedProfile)
        val samples = (0..profile.totalDurationS.toInt()).map { profile.speedAt(it.toDouble()) }

        // Up through 26 and 60, and back down through both.
        assertTrue(samples.any { it < 26.0 })
        assertTrue(samples.any { it in 26.0..59.9 })
        assertTrue(samples.any { it >= 60.0 })
        // The hysteresis dwell: time spent between FAST's exit (55) and its entry (60), which is
        // where the dial must be seen HOLDING the fast character rather than dropping out of it.
        assertTrue(samples.count { it in 55.0..59.9 } >= 5)
        // And it finishes stopped -- the state the calm-motion rule is judged on.
        assertEquals(0.0, profile.speedAt(profile.totalDurationS), 1e-9)
    }

    @Test
    fun `the sweep route has enough road for the distance it covers`() {
        // A route that ran out of waypoints would clamp to the last one and stop early, which
        // would look like the profile failing rather than the geometry.
        val route = SimulatedRoutes.bandSweep()
        val profile = requireNotNull(route.speedProfile)

        assertTrue(
            "route is ${route.lengthM}m but the profile covers ${profile.distanceM(profile.totalDurationS)}m",
            route.lengthM >= profile.distanceM(profile.totalDurationS),
        )
    }
}
