package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.fare.tollBearingDegrees
import au.com.threesixty.cabdispatch.domain.location.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StyleRoadPath] over a synthetic style feature set -- task 4's own spike, exercised here with
 * fixture `LineString`s shaped like what `ui/screens/hired/MeterBackdropMap.kt`'s
 * `queryRoadSourceFeatures` would decode off a real vector-tile road source-layer, IF this
 * project's custom style is ever confirmed to expose one (see [StyleRoadPath]'s own class doc for
 * why that is UNVERIFIED on a real device). These tests only exercise the pure geometry this class
 * itself owns -- they say nothing about whether the real style has a matching layer.
 */
class StyleRoadPathTest {

    private fun fix(lat: Double, lng: Double, headingDeg: Double? = null) = LocationFix(
        lat = lat,
        lng = lng,
        speedKmh = 60.0,
        accuracyM = 10f,
        timestampMillis = 0L,
        heading = headingDeg,
    )

    // Three connected points, due east along one parallel -- p0->p1 (segment A), p1->p2 (segment
    // B, same name/class as A so it connects), p2->p3 (segment C, a DIFFERENT road, must not
    // connect even though it is geometrically adjacent).
    private val p0 = -33.8500 to 151.2000
    private val p1 = -33.8500 to 151.2010 // ~93m east of p0
    private val p2 = -33.8500 to 151.2020 // ~93m east of p1
    private val p3 = -33.8500 to 151.2030 // ~93m east of p2

    private fun segmentA() = StyleRoadFeature("Harbour Bridge Approach", "motorway", listOf(p0, p1))
    private fun segmentB() = StyleRoadFeature("Harbour Bridge Approach", "motorway", listOf(p1, p2))
    private fun segmentC() = StyleRoadFeature("Different Road", "trunk", listOf(p2, p3))

    private fun eastBearingDeg() = tollBearingDegrees(p0.first, p0.second, p1.first, p1.second)

    @Test
    fun `pathAt returns null when nothing has ever been queried`() {
        val source = StyleRoadPath { emptyList() }
        assertNull(source.pathAt(fix(p0.first, p0.second)))
    }

    @Test
    fun `pathAt returns null when nothing is within 300m of the entry fix`() {
        val source = StyleRoadPath { listOf(segmentA(), segmentB()) }
        val farAway = fix(-34.5, 152.0)
        assertNull(source.pathAt(farAway))
    }

    @Test
    fun `pathAt with no known bearing follows connected segments by name and class only`() {
        val source = StyleRoadPath { listOf(segmentA(), segmentB(), segmentC()) }
        val entry = fix(p0.first, p0.second) // no heading -- bearing gate disabled
        val path = source.pathAt(entry)
        assertTrue(path != null)

        val distanceM = path!!.distanceTo(fix(p2.first, p2.second))
        assertTrue("must have walked A then connected B, reaching p2", distanceM != null)

        val expectedM = (
            GeoMath.distanceKm(p0.first, p0.second, p1.first, p1.second) +
                GeoMath.distanceKm(p1.first, p1.second, p2.first, p2.second)
            ) * 1000.0
        assertEquals(expectedM, distanceM!!, 0.5)
    }

    @Test
    fun `pathAt never crosses into a differently-named-or-classed segment even when adjacent`() {
        val source = StyleRoadPath { listOf(segmentA(), segmentB(), segmentC()) }
        val path = source.pathAt(fix(p0.first, p0.second))!!

        // p3 is only reachable by continuing past segment C, which does not match A/B's own
        // name/class -- so a fix at p3 must never match this path.
        assertNull(path.distanceTo(fix(p3.first, p3.second)))
    }

    @Test
    fun `pathAt rejects every candidate whose bearing does not match the last GPS heading`() {
        val source = StyleRoadPath { listOf(segmentA()) }
        // A heading roughly perpendicular to the feature's own east-west bearing -- outside the
        // +/-20deg (or its 180deg-opposite) tolerance.
        val perpendicularHeading = (eastBearingDeg() + 90.0) % 360.0
        val entry = fix(p0.first, p0.second, headingDeg = perpendicularHeading)
        assertNull(source.pathAt(entry))
    }

    @Test
    fun `pathAt accepts a candidate whose bearing matches the last GPS heading closely`() {
        val source = StyleRoadPath { listOf(segmentA(), segmentB()) }
        val entry = fix(p0.first, p0.second, headingDeg = eastBearingDeg())
        val path = source.pathAt(entry)
        assertTrue(path != null)
        assertTrue(path!!.distanceTo(fix(p2.first, p2.second)) != null)
    }

    @Test
    fun `pathAt accepts a candidate running exactly opposite the last GPS heading too`() {
        // A road's own digitised direction is arbitrary (see StyleRoadFeature.points' own doc) --
        // a vehicle travelling the opposite way along the same physical road is still a real match.
        val source = StyleRoadPath { listOf(segmentA(), segmentB()) }
        val oppositeHeading = (eastBearingDeg() + 180.0) % 360.0
        val entry = fix(p0.first, p0.second, headingDeg = oppositeHeading)
        assertTrue(source.pathAt(entry) != null)
    }

    @Test
    fun `distanceTo returns null once the exit fix is more than 60m off the followed path`() {
        val source = StyleRoadPath { listOf(segmentA(), segmentB()) }
        val path = source.pathAt(fix(p0.first, p0.second))!!
        val wayOff = fix(p2.first + 0.02, p2.second) // a couple of km north of p2
        assertNull(path.distanceTo(wayOff))
    }
}
