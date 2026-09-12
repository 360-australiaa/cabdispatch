package au.com.threesixty.cabdispatch.domain.fare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W3 (road-geometry constraint sources, 2026-09-12 plan): [gantryChainPath] and [greedyChainPoints]
 * -- the point-sequence-returning siblings [knownCorridorDistanceKm]/[greedyChainDistanceM] were
 * refactored to sit on top of (see `KnownCorridor.kt`'s own doc). [KnownCorridorTest] already
 * proves the LENGTH these two functions produce is unchanged; this file proves the actual POINT
 * SEQUENCE they hand `domain/location/roadpath/CorridorRoadPath.kt` is correct, since that is the
 * part [KnownCorridorTest] itself has no reason to look at.
 */
class GantryChainPathTest {

    private val pointA = -33.8000 to 151.0000
    private val pointB = -33.7980 to 151.0050
    private val pointC = -33.8000 to 151.0100

    private fun bentRoad(id: String = "TUNNEL") = listOf(
        TollGantryRef("$id-A", id, pointA.first, pointA.second),
        TollGantryRef("$id-B", id, pointB.first, pointB.second),
        TollGantryRef("$id-C", id, pointC.first, pointC.second),
    )

    private fun registryOf(vararg gantries: List<TollGantryRef>): TollRegistrySnapshot =
        TollRegistrySnapshot(roadsById = emptyMap(), gantries = gantries.flatMap { it })

    @Test
    fun `gantryChainPath returns the fixes with the real walked chain between them, in order`() {
        val registry = registryOf(bentRoad())

        val path = gantryChainPath(
            registry,
            entryLat = pointA.first, entryLng = pointA.second,
            exitLat = pointC.first, exitLng = pointC.second,
        )

        assertTrue(path != null)
        assertEquals(listOf(pointA, pointA, pointB, pointC, pointC), path)
    }

    @Test
    fun `gantryChainPath prepends and appends the real fixes, not the nearest gantries, when offset`() {
        val registry = registryOf(bentRoad())
        val entryLat = pointA.first
        val entryLng = pointA.second - 0.0005 // ~46m west of A
        val exitLat = pointC.first
        val exitLng = pointC.second + 0.0005 // ~46m east of C

        val path = gantryChainPath(registry, entryLat, entryLng, exitLat, exitLng)

        assertTrue(path != null)
        assertEquals(entryLat to entryLng, path!!.first())
        assertEquals(exitLat to exitLng, path.last())
        // The interior is still the real gantry chain, unaffected by the fixes' own offset.
        assertEquals(listOf(pointA, pointB, pointC), path.subList(1, path.size - 1))
    }

    @Test
    fun `gantryChainPath returns null under the exact same rejections as knownCorridorDistanceKm`() {
        assertNull(
            gantryChainPath(
                TollRegistrySnapshot.EMPTY,
                entryLat = pointA.first, entryLng = pointA.second,
                exitLat = pointC.first, exitLng = pointC.second,
            ),
        )
        assertNull(
            gantryChainPath(
                registryOf(bentRoad()),
                entryLat = pointA.first, entryLng = pointA.second,
                exitLat = pointA.first, exitLng = pointA.second, // identical fix, same nearest gantry
            ),
        )
    }

    @Test
    fun `greedyChainPoints walks the real intermediate points, not a straight line`() {
        val gantries = bentRoad()
        val start = gantries.first { it.id == "TUNNEL-A" }
        val end = gantries.first { it.id == "TUNNEL-C" }

        val points = greedyChainPoints(gantries, start, end)

        assertEquals(listOf(pointA, pointB, pointC), points)
    }

    @Test
    fun `greedyChainPoints from a point to itself is just that one point`() {
        val gantries = bentRoad()
        val a = gantries.first { it.id == "TUNNEL-A" }
        assertEquals(listOf(pointA), greedyChainPoints(gantries, a, a))
    }

    @Test
    fun `knownCorridorDistanceKm sums exactly gantryChainPath's own returned points`() {
        val registry = registryOf(bentRoad())
        val path = gantryChainPath(
            registry,
            entryLat = pointA.first, entryLng = pointA.second,
            exitLat = pointC.first, exitLng = pointC.second,
        )!!

        var expectedM = 0.0
        for (i in 0 until path.size - 1) {
            expectedM += tollHaversineM(path[i].first, path[i].second, path[i + 1].first, path[i + 1].second)
        }

        val km = knownCorridorDistanceKm(
            registry,
            entryLat = pointA.first, entryLng = pointA.second,
            exitLat = pointC.first, exitLng = pointC.second,
        )!!
        assertEquals(expectedM / 1000.0, km.toDouble(), 1e-9)
    }
}
