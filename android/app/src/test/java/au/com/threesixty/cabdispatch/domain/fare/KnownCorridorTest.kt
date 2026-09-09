package au.com.threesixty.cabdispatch.domain.fare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Plain-JVM golden tests for [knownCorridorDistanceKm] (and its [greedyChainDistanceM] helper) —
 * the pure geometry decision behind the owner's 2026-09-09 known-corridor GPS-blackout billing rule.
 * See that function's own doc, and [au.com.threesixty.cabdispatch.domain.MeterAccuracyTest]'s
 * `known-corridor` section for the [au.com.threesixty.cabdispatch.domain.FareEngineImpl] integration
 * these are the geometry foundation for.
 */
class KnownCorridorTest {

    // A three-point road bent off a straight line -- entry matches A, exit matches C, and B sits
    // to the side between them, close enough to A that a nearest-neighbour walk from A visits B
    // before C. The whole point of this fixture: the real path A->B->C must be measurably LONGER
    // than the straight chord A->C, so a test can tell a real bug (falling back to the chord) apart
    // from the correct behaviour.
    private val pointA = -33.8000 to 151.0000
    private val pointB = -33.7980 to 151.0050 // ~222m north of the A-C line, roughly midway along it
    private val pointC = -33.8000 to 151.0100

    private fun bentRoad(id: String = "TUNNEL") = listOf(
        TollGantryRef("$id-A", id, pointA.first, pointA.second),
        TollGantryRef("$id-B", id, pointB.first, pointB.second),
        TollGantryRef("$id-C", id, pointC.first, pointC.second),
    )

    private fun registryOf(vararg gantries: List<TollGantryRef>): TollRegistrySnapshot =
        TollRegistrySnapshot(roadsById = emptyMap(), gantries = gantries.flatMap { it })

    // --- the headline case: a curving corridor is billed its real path length, not the chord ----

    @Test
    fun `a blackout across a bent corridor bills the real path through its own points, not the straight line`() {
        val registry = registryOf(bentRoad())

        val known = knownCorridorDistanceKm(
            registry,
            entryLat = pointA.first, entryLng = pointA.second,
            exitLat = pointC.first, exitLng = pointC.second,
        )

        val straightLineKm = BigDecimal.valueOf(
            tollHaversineM(pointA.first, pointA.second, pointC.first, pointC.second) / 1000.0,
        )

        assertTrue("a real corridor match must never be null here", known != null)
        assertTrue(
            "the bent path (through B) must bill MORE than the straight A-C chord " +
                "(path=$known km, chord=$straightLineKm km)",
            known!! > straightLineKm,
        )
        // Independently recomputed expected value: A-B + B-C (the greedy walk's own two legs),
        // entry/exit gaps are zero because the fixes sit exactly on A and C.
        val expected = BigDecimal.valueOf(
            (
                tollHaversineM(pointA.first, pointA.second, pointB.first, pointB.second) +
                    tollHaversineM(pointB.first, pointB.second, pointC.first, pointC.second)
                ) / 1000.0,
        )
        assertEquals(
            expected.setScale(6, RoundingMode.HALF_UP),
            known.setScale(6, RoundingMode.HALF_UP),
        )
    }

    @Test
    fun `entry and exit fixes offset from the gantries themselves still match, gaps included`() {
        val registry = registryOf(bentRoad())
        // ~46m west of A, ~46m east of C -- comfortably inside CORRIDOR_PORTAL_MATCH_RADIUS_M.
        val entryLat = pointA.first
        val entryLng = pointA.second - 0.0005
        val exitLat = pointC.first
        val exitLng = pointC.second + 0.0005

        val known = knownCorridorDistanceKm(registry, entryLat, entryLng, exitLat, exitLng)
        assertTrue(known != null)

        val entryGapM = tollHaversineM(entryLat, entryLng, pointA.first, pointA.second)
        val exitGapM = tollHaversineM(exitLat, exitLng, pointC.first, pointC.second)
        val pathM = tollHaversineM(pointA.first, pointA.second, pointB.first, pointB.second) +
            tollHaversineM(pointB.first, pointB.second, pointC.first, pointC.second)
        val expected = BigDecimal.valueOf((entryGapM + pathM + exitGapM) / 1000.0)

        assertEquals(expected.setScale(6, RoundingMode.HALF_UP), known!!.setScale(6, RoundingMode.HALF_UP))
    }

    // --- rejection: no known corridor here at all -------------------------------------------------

    @Test
    fun `an empty registry never fabricates a corridor`() {
        val known = knownCorridorDistanceKm(
            TollRegistrySnapshot.EMPTY,
            entryLat = pointA.first, entryLng = pointA.second,
            exitLat = pointC.first, exitLng = pointC.second,
        )
        assertNull(known)
    }

    @Test
    fun `a road with only one gantry has nothing to interpolate along`() {
        val registry = TollRegistrySnapshot(
            roadsById = emptyMap(),
            gantries = listOf(TollGantryRef("solo-A", "SOLO", pointA.first, pointA.second)),
        )
        val known = knownCorridorDistanceKm(registry, pointA.first, pointA.second, pointC.first, pointC.second)
        assertNull(known)
    }

    // --- rejection: reacquired fix does not plausibly continue the same road ----------------------

    @Test
    fun `an exit fix wildly off from where the corridor would put it is never falsely matched`() {
        val registry = registryOf(bentRoad())
        // Entry genuinely at A; "exit" is 5km away from every gantry on this road -- nothing like a
        // vehicle emerging from this corridor.
        val known = knownCorridorDistanceKm(
            registry,
            entryLat = pointA.first, entryLng = pointA.second,
            exitLat = pointA.first, exitLng = pointA.second + 0.05, // ~4.6km east
        )
        assertNull("a wildly-off reacquisition fix must never be treated as this road's exit", known)
    }

    @Test
    fun `entry and exit matching the very same single gantry is not a corridor`() {
        val registry = registryOf(bentRoad())
        val known = knownCorridorDistanceKm(
            registry,
            entryLat = pointA.first, entryLng = pointA.second,
            exitLat = pointA.first, exitLng = pointA.second, // identical fix, same nearest gantry
        )
        assertNull(known)
    }

    // --- multiple candidate roads: the more parsimonious match wins --------------------------------

    @Test
    fun `when two roads both plausibly match, the shorter known path is billed`() {
        // A second, unrelated road whose own two gantries sit much further from the fixes but still
        // just inside the match radius on one contrived pairing -- constructed only to prove the
        // selection rule, not to model a real interchange.
        val farRoad = listOf(
            TollGantryRef("FAR-A", "FAR", pointA.first + 0.0015, pointA.second), // ~167m north of A
            TollGantryRef("FAR-C", "FAR", pointC.first + 0.0015, pointC.second), // ~167m north of C
        )
        val registry = registryOf(bentRoad(), farRoad)

        val known = knownCorridorDistanceKm(
            registry,
            entryLat = pointA.first, entryLng = pointA.second,
            exitLat = pointC.first, exitLng = pointC.second,
        )

        // The bent TUNNEL road's own path (no entry/exit gap at all, since the fixes sit exactly on
        // its A/C gantries) must win over FAR's larger entry/exit gaps plus its own path.
        val tunnelExpected = BigDecimal.valueOf(
            (
                tollHaversineM(pointA.first, pointA.second, pointB.first, pointB.second) +
                    tollHaversineM(pointB.first, pointB.second, pointC.first, pointC.second)
                ) / 1000.0,
        )
        assertTrue(known != null)
        assertEquals(tunnelExpected.setScale(6, RoundingMode.HALF_UP), known!!.setScale(6, RoundingMode.HALF_UP))
    }

    // --- greedyChainDistanceM itself ---------------------------------------------------------------

    @Test
    fun `greedyChainDistanceM sums the real walked legs, not a straight line`() {
        val gantries = bentRoad()
        val start = gantries.first { it.id == "TUNNEL-A" }
        val end = gantries.first { it.id == "TUNNEL-C" }

        val walked = greedyChainDistanceM(gantries, start, end)
        val straight = tollHaversineM(start.latitude, start.longitude, end.latitude, end.longitude)

        assertTrue(walked != null)
        assertTrue("the walk must go through the intermediate point, so must exceed the chord", walked!! > straight)
    }

    @Test
    fun `greedyChainDistanceM from a point to itself is zero`() {
        val gantries = bentRoad()
        val a = gantries.first { it.id == "TUNNEL-A" }
        assertEquals(0.0, greedyChainDistanceM(gantries, a, a)!!, 0.0001)
    }
}
