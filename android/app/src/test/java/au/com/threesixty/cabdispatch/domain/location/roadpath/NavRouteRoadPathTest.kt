package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.location.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NavRouteRoadPath] over a synthetic Directions-API-shaped fixture -- a short, real-looking
 * decoded polyline (the shape `data/remote/MapboxDirections.kt`'s own `decodePolyline` produces),
 * not an actual network fetch (this source, like every [RoadPathSource], is pure over already-
 * cached data).
 */
class NavRouteRoadPathTest {

    private fun fix(lat: Double, lng: Double) = LocationFix(
        lat = lat,
        lng = lng,
        speedKmh = 60.0,
        accuracyM = 10f,
        timestampMillis = 0L,
    )

    // A short, slightly bent route -- three decoded Directions-API points.
    private val start = -33.8600 to 151.2000
    private val mid = -33.8580 to 151.2050
    private val end = -33.8600 to 151.2100

    private fun fixtureRoute(): List<Pair<Double, Double>> = listOf(start, mid, end)

    @Test
    fun `pathAt returns null when no route is active`() {
        val source = NavRouteRoadPath { null }
        assertNull(source.pathAt(fix(start.first, start.second)))
    }

    @Test
    fun `pathAt returns null when the entry fix is not on the active route`() {
        val source = NavRouteRoadPath { fixtureRoute() }
        val farAway = fix(-34.0, 152.0)
        assertNull(source.pathAt(farAway))
    }

    @Test
    fun `pathAt matches an entry fix within 40m of the route and distanceTo sums the real bend`() {
        val source = NavRouteRoadPath { fixtureRoute() }
        val entry = fix(start.first, start.second)
        val path = source.pathAt(entry)
        assertTrue(path != null)

        val distanceM = path!!.distanceTo(fix(end.first, end.second))
        assertTrue(distanceM != null)

        val expectedM = (
            GeoMath.distanceKm(start.first, start.second, mid.first, mid.second) +
                GeoMath.distanceKm(mid.first, mid.second, end.first, end.second)
            ) * 1000.0
        assertEquals(expectedM, distanceM!!, 0.5)

        val straightLineM = GeoMath.distanceKm(start.first, start.second, end.first, end.second) * 1000.0
        assertTrue("the bent route must bill more than the straight chord", distanceM > straightLineM)
    }

    @Test
    fun `distanceTo returns null once the exit fix has drifted off the route`() {
        val source = NavRouteRoadPath { fixtureRoute() }
        val path = source.pathAt(fix(start.first, start.second))!!
        val wayOff = fix(end.first + 0.05, end.second) // ~5.5km off the route
        assertNull(path.distanceTo(wayOff))
    }

    @Test
    fun `advance walks forward along the route from its own start`() {
        val source = NavRouteRoadPath { fixtureRoute() }
        val path = source.pathAt(fix(start.first, start.second))!!

        val atStart = path.advance(0.0)
        assertEquals(start.first, atStart.first, 1e-9)
        assertEquals(start.second, atStart.second, 1e-9)

        val legOneM = GeoMath.distanceKm(start.first, start.second, mid.first, mid.second) * 1000.0
        val atMid = path.advance(legOneM)
        assertEquals(mid.first, atMid.first, 1e-6)
        assertEquals(mid.second, atMid.second, 1e-6)
    }

    @Test
    fun `a single-point route never matches -- nothing to project onto`() {
        val source = NavRouteRoadPath { listOf(start) }
        assertNull(source.pathAt(fix(start.first, start.second)))
    }
}
