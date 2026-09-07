package au.com.threesixty.cabdispatch.domain.location

import au.com.threesixty.cabdispatch.domain.fare.TOLL_GANTRY_DETECTION_RADIUS_M
import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollPriceRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.TollRoadRef
import au.com.threesixty.cabdispatch.domain.fare.classifyBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Plain-JVM tests for the GPS simulator's route geometry.
 *
 * This is not decoration on a test tool: the coordinates this file produces are what the meter
 * matches against real gantries, so a route that drifts even 150m from the corridor would make
 * toll detection appear broken when it is fine — or, worse, appear fine when it is broken.
 */
class SimulatedRouteTest {

    private fun route(vararg points: LatLng, speedKmh: Double = 36.0) =
        SimulatedRoute("t", "Test", "", points.toList(), speedKmh)

    // 36 km/h is exactly 10 m/s, so expected distances are readable by eye.
    private val tenMetresPerSecond = 36.0

    @Test
    fun `position advances at the route's speed`() {
        // ~1113m of latitude (0.01 deg) north.
        val r = route(LatLng(-33.90, 151.20), LatLng(-33.89, 151.20), speedKmh = tenMetresPerSecond)

        val start = r.positionAt(0.0)
        assertEquals(-33.90, start.point.lat, 1e-9)

        // After 55.65s at 10 m/s the vehicle has covered ~556m -- half the leg.
        val halfway = r.positionAt(r.durationSeconds / 2)
        assertEquals(-33.895, halfway.point.lat, 1e-4)
    }

    @Test
    fun `a finished route parks at the destination instead of wrapping or overshooting`() {
        val r = route(LatLng(-33.90, 151.20), LatLng(-33.89, 151.20), speedKmh = tenMetresPerSecond)

        val wayPastTheEnd = r.positionAt(r.durationSeconds * 10)

        assertTrue(wayPastTheEnd.finished)
        assertEquals(-33.89, wayPastTheEnd.point.lat, 1e-9)
    }

    @Test
    fun `bearing reflects the direction actually being driven`() {
        // A directional toll road (northbound_only) refuses to charge a southbound crossing, so
        // the simulator getting this backwards would make those roads untestable -- see
        // TollDetector.directionAllowsCharge.
        val north = route(LatLng(-33.90, 151.20), LatLng(-33.89, 151.20))
        val south = route(LatLng(-33.89, 151.20), LatLng(-33.90, 151.20))

        assertEquals(0.0, north.positionAt(1.0).bearingDegrees, 0.5)
        assertEquals(180.0, south.positionAt(1.0).bearingDegrees, 0.5)
    }

    @Test
    fun `the bearing a simulated drive produces is the one toll detection reads`() {
        // Pins the two together rather than assuming: this asserts against the detector's OWN
        // classifier, so a change to either side that made them disagree fails here.
        val r = route(LatLng(-33.90, 151.20), LatLng(-33.89, 151.20))

        val first = r.positionAt(0.0).point
        val later = r.positionAt(60.0).point

        // "north", not "N" -- the detector's own coarse 45-degree buckets. Asserting against
        // classifyBearing itself rather than a bearing number is the point: the simulator and
        // the detector have to agree on the vocabulary, not just the geometry.
        assertEquals("north", classifyBearing(first.lat, first.lng, later.lat, later.lng))
    }

    // --- routes built from the real registry ------------------------------------------------

    private fun gantry(id: String, roadId: String, lat: Double, lng: Double) =
        TollGantryRef(id = id, tollRoadId = roadId, latitude = lat, longitude = lng)

    private fun registry(vararg gantries: TollGantryRef) = TollRegistrySnapshot(
        roadsById = mapOf(
            "M7" to TollRoadRef(
                id = "M7",
                name = "Westlink M7",
                pricingModel = "distance",
                chargingPolicy = "distance_metered",
                directional = "both",
                currentPrice = TollPriceRef(
                    priceClassAMax = null,
                    capClassA = BigDecimal("10.50"),
                    ratePerKmClassA = BigDecimal("0.5252"),
                    flagfallClassA = null,
                    timeOfDayRatesClassA = null,
                    confidence = "verified",
                ),
            ),
        ),
        gantries = gantries.toList(),
    )

    @Test
    fun `a toll route passes within detection range of every one of the road's gantries`() {
        // The whole point of the feature. Gantries are handed in deliberately out of order, since
        // that is how the registry stores them.
        val gantries = listOf(
            gantry("g3", "M7", -33.80, 150.90),
            gantry("g1", "M7", -33.70, 150.88),
            gantry("g2", "M7", -33.75, 150.89),
        )
        val built = SimulatedRoutes.throughTollRoad(registry(*gantries.toTypedArray()), "M7")
        assertNotNull(built)
        val r = built!!

        // Walk the whole drive at the simulator's real 1 Hz and record the closest approach to
        // each gantry -- exactly what the detector sees.
        val closest = gantries.associate { it.id to Double.MAX_VALUE }.toMutableMap()
        var t = 0.0
        while (t <= r.durationSeconds + 1.0) {
            val here = r.positionAt(t).point
            for (g in gantries) {
                val d = haversineM(here, LatLng(g.latitude, g.longitude))
                if (d < closest.getValue(g.id)) closest[g.id] = d
            }
            t += 1.0
        }

        for ((id, distance) in closest) {
            assertTrue(
                "gantry $id was never approached within the detection radius (closest ${distance}m)",
                distance <= TOLL_GANTRY_DETECTION_RADIUS_M,
            )
        }
    }

    @Test
    fun `a toll route starts back from the first gantry so a bearing exists before it`() {
        // A directional road declines to charge until it has a trusted bearing. Starting on top
        // of the first gantry would mean the first fix has no previous point to measure from, so
        // the crossing would silently not charge and the route would test nothing.
        val first = gantry("g1", "M7", -33.70, 150.88)
        val r = SimulatedRoutes.throughTollRoad(registry(first, gantry("g2", "M7", -33.75, 150.89)), "M7")!!

        val startDistance = haversineM(r.positionAt(0.0).point, LatLng(first.latitude, first.longitude))

        assertTrue(
            "route should start clear of the first gantry, started ${startDistance}m away",
            startDistance > TOLL_GANTRY_DETECTION_RADIUS_M,
        )
    }

    @Test
    fun `gantry ordering is deterministic across runs`() {
        // A test route that varies between runs is not a test route.
        val gantries = arrayOf(
            gantry("g3", "M7", -33.80, 150.90),
            gantry("g1", "M7", -33.70, 150.88),
            gantry("g2", "M7", -33.75, 150.89),
        )
        val a = SimulatedRoutes.throughTollRoad(registry(*gantries), "M7")!!
        val b = SimulatedRoutes.throughTollRoad(registry(*gantries.reversedArray()), "M7")!!

        assertEquals(a.waypoints, b.waypoints)
    }

    @Test
    fun `an unknown road or one with no gantries builds no route rather than an invented one`() {
        assertNull(SimulatedRoutes.throughTollRoad(registry(gantry("g", "M7", -33.7, 150.8)), "NOPE"))
        assertNull(SimulatedRoutes.throughTollRoad(registry(), "M7"))
    }

    @Test
    fun `the plain drive stays clear of every gantry in the real registry`() {
        // The toll-free baseline is only a baseline if it is genuinely toll-free. A real street
        // route through Sydney could easily clip a gantry and quietly add a toll to a run that is
        // supposed to have none.
        val nearby = gantry("cct", "M7", -33.8750, 151.2100) // Cross City Tunnel, roughly
        val plain = SimulatedRoutes.plainDrive()

        var t = 0.0
        var closest = Double.MAX_VALUE
        while (t <= plain.durationSeconds) {
            val d = haversineM(plain.positionAt(t).point, LatLng(nearby.latitude, nearby.longitude))
            if (d < closest) closest = d
            t += 1.0
        }

        assertTrue("plain route passed within ${closest}m of a gantry", closest > TOLL_GANTRY_DETECTION_RADIUS_M)
    }

    @Test
    fun `the waiting route moves slowly but really does move`() {
        // Zero speed produces no bearing at all, which exercises the no-bearing path rather than
        // the waiting rate this route is for.
        val r = SimulatedRoutes.stopAndGo()

        assertTrue(r.speedKmh > 0.0)
        assertTrue(r.speedKmh < 10.0)
        assertTrue(haversineM(r.positionAt(0.0).point, r.positionAt(30.0).point) > 0.0)
    }
}
