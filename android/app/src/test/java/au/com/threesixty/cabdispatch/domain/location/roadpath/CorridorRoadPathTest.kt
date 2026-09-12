package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.fare.CORRIDOR_PORTAL_MATCH_RADIUS_M
import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.tollHaversineM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CorridorRoadPath] over a synthetic Cross City Tunnel-shaped gantry chain -- the same "bent
 * road" shape [au.com.threesixty.cabdispatch.domain.fare.KnownCorridorTest] uses for
 * [au.com.threesixty.cabdispatch.domain.fare.knownCorridorDistanceKm] itself, reused here to prove
 * this class's [RoadPath] wraps the identical [au.com.threesixty.cabdispatch.domain.fare
 * .gantryChainPath] geometry, not an independently-reimplemented walk.
 */
class CorridorRoadPathTest {

    private fun fix(lat: Double, lng: Double) = LocationFix(
        lat = lat,
        lng = lng,
        speedKmh = 80.0,
        accuracyM = 10f,
        timestampMillis = 0L,
    )

    // Cross City Tunnel-shaped: a bent three-gantry chain, entry matches A, exit matches C.
    private val pointA = -33.8000 to 151.0000
    private val pointB = -33.7980 to 151.0050
    private val pointC = -33.8000 to 151.0100

    private fun crossCityRegistry(): TollRegistrySnapshot = TollRegistrySnapshot(
        roadsById = emptyMap(),
        gantries = listOf(
            TollGantryRef("XCT-A", "XCT", pointA.first, pointA.second),
            TollGantryRef("XCT-B", "XCT", pointB.first, pointB.second),
            TollGantryRef("XCT-C", "XCT", pointC.first, pointC.second),
        ),
    )

    @Test
    fun `pathAt returns null when the entry fix is nowhere near any known corridor`() {
        val source = CorridorRoadPath { crossCityRegistry() }
        val farAway = fix(-34.5, 152.0)
        assertNull(source.pathAt(farAway))
    }

    @Test
    fun `pathAt returns null when the registry provider itself has nothing cached yet`() {
        val source = CorridorRoadPath { null }
        assertNull(source.pathAt(fix(pointA.first, pointA.second)))
    }

    @Test
    fun `distanceTo resolves the real bent path length once the exit fix is known`() {
        val source = CorridorRoadPath { crossCityRegistry() }
        val path = source.pathAt(fix(pointA.first, pointA.second))
        assertTrue("entry near a known corridor must match", path != null)

        val distanceM = path!!.distanceTo(fix(pointC.first, pointC.second))
        assertTrue("a real bent-road match must never be null", distanceM != null)

        val expectedM = tollHaversineM(pointA.first, pointA.second, pointB.first, pointB.second) +
            tollHaversineM(pointB.first, pointB.second, pointC.first, pointC.second)
        assertEquals(expectedM, distanceM!!, 0.01)
    }

    @Test
    fun `distanceTo returns null when the exit fix does not plausibly continue the corridor`() {
        val source = CorridorRoadPath { crossCityRegistry() }
        val path = source.pathAt(fix(pointA.first, pointA.second))!!
        val wildlyOff = fix(pointA.first, pointA.second + 0.2) // ~18km away
        assertNull(path.distanceTo(wildlyOff))
    }

    @Test
    fun `advance holds at the entry fix until distanceTo has resolved a real match`() {
        val source = CorridorRoadPath { crossCityRegistry() }
        val entry = fix(pointA.first, pointA.second)
        val path = source.pathAt(entry)!!

        val beforeResolve = path.advance(500.0)
        assertEquals(entry.lat, beforeResolve.first, 1e-9)
        assertEquals(entry.lng, beforeResolve.second, 1e-9)

        path.distanceTo(fix(pointC.first, pointC.second))
        val afterResolve = path.advance(0.0)
        assertEquals(
            "once resolved, advance(0.0) must sit at the same start point distanceTo matched against",
            entry.lat,
            afterResolve.first,
            1e-6,
        )
    }

    @Test
    fun `entry match uses the same portal radius as the plain corridor lookup`() {
        val source = CorridorRoadPath { crossCityRegistry() }
        // Just inside the portal radius (~166m west of A).
        val justInside = fix(pointA.first, pointA.second - 0.0018)
        val gapM = tollHaversineM(justInside.lat, justInside.lng, pointA.first, pointA.second)
        assertTrue("fixture must actually sit inside the radius", gapM < CORRIDOR_PORTAL_MATCH_RADIUS_M)
        assertTrue(source.pathAt(justInside) != null)

        // Comfortably outside it.
        val outside = fix(pointA.first, pointA.second - 0.01)
        val outsideGapM = tollHaversineM(outside.lat, outside.lng, pointA.first, pointA.second)
        assertTrue(outsideGapM > CORRIDOR_PORTAL_MATCH_RADIUS_M)
        assertNull(source.pathAt(outside))
    }
}
