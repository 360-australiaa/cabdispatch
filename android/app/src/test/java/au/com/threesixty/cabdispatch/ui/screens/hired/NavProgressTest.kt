package au.com.threesixty.cabdispatch.ui.screens.hired

import au.com.threesixty.cabdispatch.data.remote.DirectionsRoute
import au.com.threesixty.cabdispatch.data.remote.RoutePoint
import au.com.threesixty.cabdispatch.data.remote.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the navigator's arithmetic ([NavProgress]) so the meter screen's "next turn / distance /
 * ETA / off-route" readout can be trusted without a device. The fixture is a synthetic straight
 * north-south route through Sydney (one vertex every ~55 m, four maneuvers) whose step distances
 * are the real great-circle lengths between its maneuver points — so every expectation below is
 * derived from geometry, not typed in by hand.
 */
class NavProgressTest {

    // ------------------------------------------------------------------ fixture

    private companion object {
        const val LNG = 151.2093
        const val START_LAT = -33.8688
        /** ~55.6 m of latitude per vertex. */
        const val LAT_STEP = 0.0005
        const val VERTEX_COUNT = 40
        val MANEUVER_VERTICES = listOf(0, 10, 25, VERTEX_COUNT - 1)
    }

    private val points: List<RoutePoint> = List(VERTEX_COUNT) { i ->
        RoutePoint(lat = START_LAT - i * LAT_STEP, lng = LNG)
    }

    private val steps: List<RouteStep> = MANEUVER_VERTICES.mapIndexed { n, vertex ->
        val here = points[vertex]
        val next = MANEUVER_VERTICES.getOrNull(n + 1)?.let { points[it] }
        RouteStep(
            instruction = if (next == null) "You have arrived" else "Continue on step $n",
            lat = here.lat,
            lng = here.lng,
            distanceM = next?.let { NavProgress.haversineM(here.lat, here.lng, it.lat, it.lng) } ?: 0.0,
        )
    }

    private val route = DirectionsRoute(
        distanceM = steps.sumOf { it.distanceM },
        durationS = steps.sumOf { it.distanceM } / (40.0 / 3.6), // averages 40 km/h
        points = points,
        steps = steps,
    )

    // ------------------------------------------------------------------ haversine

    @Test
    fun `haversine matches one degree of latitude and is zero for a point`() {
        // One degree of latitude is 111.19 km on a 6371 km sphere, independent of longitude.
        val d = NavProgress.haversineM(-33.0, 151.0, -34.0, 151.0)
        assertEquals(111_195.0, d, 50.0)
        // One degree of longitude at 34 S is cos(34 deg) of that: ~92.2 km.
        val dLng = NavProgress.haversineM(-34.0, 151.0, -34.0, 152.0)
        assertEquals(92_190.0, dLng, 100.0)
        assertEquals(0.0, NavProgress.haversineM(-33.0, 151.0, -33.0, 151.0), 1e-9)
    }

    // ------------------------------------------------------------------ nearest vertex

    @Test
    fun `nearest vertex distance is zero on a vertex and grows off the line`() {
        val onVertex = points[7]
        assertEquals(0.0, NavProgress.distanceToNearestVertexM(onVertex.lat, onVertex.lng, points), 1e-6)

        // Midway between two vertices (~27.8 m from each) is still well inside OFF_ROUTE_M.
        val midLat = (points[7].lat + points[8].lat) / 2
        val mid = NavProgress.distanceToNearestVertexM(midLat, LNG, points)
        assertTrue("mid=$mid", mid in 25.0..30.0)
        assertTrue(mid < NavProgress.OFF_ROUTE_M)

        // 0.001 deg of longitude at this latitude is ~92 m sideways: past the threshold.
        val side = NavProgress.distanceToNearestVertexM(points[7].lat, LNG + 0.001, points)
        assertTrue("side=$side", side > NavProgress.OFF_ROUTE_M)
        assertTrue(side in 85.0..100.0)
    }

    @Test
    fun `empty polyline reads as infinitely far so it can never pass as on-route`() {
        assertEquals(
            Double.POSITIVE_INFINITY,
            NavProgress.distanceToNearestVertexM(START_LAT, LNG, emptyList()),
            0.0,
        )
    }

    // ------------------------------------------------------------------ step arrival

    @Test
    fun `arrival at a step flips exactly at the threshold`() {
        val step = steps[1]
        // ~22 m short of the maneuver: arrived.
        assertTrue(NavProgress.hasArrivedAtStep(step.lat + 0.0002, step.lng, step))
        // ~44 m short: not yet.
        assertFalse(NavProgress.hasArrivedAtStep(step.lat + 0.0004, step.lng, step))
        // The 30 m constant is what the ViewModel promises; guard it against silent drift.
        assertEquals(30.0, NavProgress.STEP_ARRIVE_M, 0.0)
    }

    @Test
    fun `step index advances only forward, through clustered maneuvers, never past arrive`() {
        // Sitting at vertex 5: still heading for maneuver 1 (vertex 10).
        assertEquals(1, NavProgress.advanceStepIndex(points[5].lat, LNG, steps, currentIndex = 1))
        // Reaching vertex 10 advances to maneuver 2.
        assertEquals(2, NavProgress.advanceStepIndex(points[10].lat, LNG, steps, currentIndex = 1))
        // Never backwards: at vertex 10 while already on step 2 stays on 2.
        assertEquals(2, NavProgress.advanceStepIndex(points[10].lat, LNG, steps, currentIndex = 2))
        // At the very end: parks on the arrive step, does not run off the list.
        val last = points.last()
        assertEquals(steps.lastIndex, NavProgress.advanceStepIndex(last.lat, last.lng, steps, currentIndex = steps.lastIndex))
        // Walking every vertex from the start lands on the arrive step.
        var index = 0
        for (p in points) index = NavProgress.advanceStepIndex(p.lat, p.lng, steps, index)
        assertEquals(steps.lastIndex, index)

        // Two maneuvers on top of each other are both consumed by one fix.
        val clustered = listOf(
            steps[0],
            RouteStep("a", points[10].lat, LNG, 5.0),
            RouteStep("b", points[10].lat + 0.00004, LNG, 100.0), // ~4 m further
            steps.last(),
        )
        assertEquals(3, NavProgress.advanceStepIndex(points[10].lat, LNG, clustered, currentIndex = 1))
        assertEquals(0, NavProgress.advanceStepIndex(START_LAT, LNG, emptyList(), currentIndex = 0))
    }

    @Test
    fun `a maneuver missed in a GPS gap is recovered at the next one, but no further`() {
        // Heading for maneuver 1 (vertex 10); the next fix is already at maneuver 2 (vertex 25),
        // as if the fixes between were dropped. One-step look-ahead: skip straight to step 3.
        assertEquals(3, NavProgress.advanceStepIndex(points[25].lat, LNG, steps, currentIndex = 1))
        // Two missed maneuvers (heading for 1, fix at the arrive point, vertex 39) is beyond the
        // look-ahead: stays put rather than guessing.
        val last = points.last()
        assertEquals(1, NavProgress.advanceStepIndex(last.lat, last.lng, steps, currentIndex = 1))
        // Halfway between maneuvers nothing is within the window: unchanged.
        assertEquals(1, NavProgress.advanceStepIndex(points[17].lat, LNG, steps, currentIndex = 1))
    }

    // ------------------------------------------------------------------ remaining distance / ETA

    @Test
    fun `remaining distance never increases as the fix walks the route`() {
        var index = 0
        var previous = Double.POSITIVE_INFINITY
        val seen = ArrayList<Double>()
        for (p in points) {
            index = NavProgress.advanceStepIndex(p.lat, p.lng, steps, index)
            val remaining = NavProgress.remainingDistanceM(p.lat, p.lng, steps, index)
            assertTrue("remaining rose from $previous to $remaining at $p (step $index)", remaining <= previous + 1e-6)
            seen += remaining
            previous = remaining
        }
        // Starts at (about) the full route length and ends at zero on the arrive step.
        assertEquals(route.distanceM, seen.first(), 1.0)
        assertEquals(0.0, seen.last(), 1e-6)
        // And it actually moved — this is not vacuously monotonic.
        assertTrue(seen.first() > 2_000.0)
        assertEquals(steps.lastIndex, index)
    }

    @Test
    fun `remaining duration scales the route average speed and ETA adds it to now`() {
        val half = route.distanceM / 2
        val seconds = NavProgress.remainingDurationS(half, route)
        assertEquals(route.durationS / 2, seconds, 1e-6)

        assertEquals(0.0, NavProgress.remainingDurationS(500.0, route.copy(durationS = 0.0)), 0.0)
        assertEquals(0.0, NavProgress.remainingDurationS(500.0, route.copy(distanceM = 0.0)), 0.0)

        assertEquals(1_000_000L + 90_500L, NavProgress.etaEpochMillis(1_000_000L, 90.5))
    }

    @Test
    fun `distance to the current maneuver is just the straight-line leg, not the whole trip`() {
        // Sitting at vertex 5, heading for maneuver 1 (vertex 10): ~5 * LAT_STEP degrees of
        // latitude away, independent of how much road remains after that maneuver.
        val expected = NavProgress.haversineM(points[5].lat, LNG, points[10].lat, LNG)
        assertEquals(expected, NavProgress.distanceToCurrentManeuverM(points[5].lat, LNG, steps, currentIndex = 1), 1e-6)
        assertTrue(expected < route.distanceM)

        // Standing exactly on the maneuver point: zero, not "whatever is left of the trip".
        assertEquals(0.0, NavProgress.distanceToCurrentManeuverM(points[10].lat, LNG, steps, currentIndex = 1), 1e-6)

        // An out-of-range index clamps to the last (arrive) step rather than throwing.
        assertEquals(
            NavProgress.distanceToCurrentManeuverM(START_LAT, LNG, steps, currentIndex = steps.lastIndex),
            NavProgress.distanceToCurrentManeuverM(START_LAT, LNG, steps, currentIndex = 99),
            1e-6,
        )
        assertEquals(0.0, NavProgress.distanceToCurrentManeuverM(START_LAT, LNG, emptyList(), currentIndex = 0), 0.0)
    }

    // ------------------------------------------------------------------ off-route

    @Test
    fun `off-route needs N consecutive far fixes and a near fix resets the count`() {
        val tracker = NavProgress.OffRouteTracker()
        val far = NavProgress.OFF_ROUTE_M + 1
        val near = NavProgress.OFF_ROUTE_M - 1

        assertFalse(tracker.onFix(far))
        assertFalse(tracker.onFix(far))
        // A single blip back on the line wipes the streak...
        assertFalse(tracker.onFix(near))
        assertEquals(0, tracker.consecutiveFar)
        assertFalse(tracker.onFix(far))
        assertFalse(tracker.onFix(far))
        // ...so it takes three uninterrupted far fixes.
        assertTrue(tracker.onFix(far))
        assertTrue(tracker.isOffRoute)
        // Stays off-route while it keeps missing the line.
        assertTrue(tracker.onFix(far))
        // Exactly on the threshold counts as on the line.
        assertFalse(tracker.onFix(NavProgress.OFF_ROUTE_M))
        tracker.onFix(far); tracker.onFix(far); tracker.onFix(far)
        assertTrue(tracker.isOffRoute)
        tracker.reset()
        assertFalse(tracker.isOffRoute)
        assertEquals(3, NavProgress.OFF_ROUTE_CONSECUTIVE)
        assertEquals(80.0, NavProgress.OFF_ROUTE_M, 0.0)
    }

    @Test
    fun `off-route detection over the fixture route uses the polyline not the maneuvers`() {
        // Halfway between maneuvers 1 and 2 the nearest *maneuver* is ~400 m away, but the
        // nearest *vertex* is on top of us: on route.
        val p = points[17]
        val toRoute = NavProgress.distanceToNearestVertexM(p.lat, p.lng, points)
        assertEquals(0.0, toRoute, 1e-6)
        val tracker = NavProgress.OffRouteTracker()
        repeat(5) { assertFalse(tracker.onFix(toRoute)) }

        // Drift ~185 m east of the line for three fixes: off route.
        val driftLng = LNG + 0.002
        repeat(NavProgress.OFF_ROUTE_CONSECUTIVE - 1) {
            assertFalse(tracker.onFix(NavProgress.distanceToNearestVertexM(p.lat, driftLng, points)))
        }
        assertTrue(tracker.onFix(NavProgress.distanceToNearestVertexM(p.lat, driftLng, points)))
    }
}
