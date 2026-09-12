package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CompositeRoadPath]'s own precedence rule: highest-confidence source first, first non-null
 * match wins outright, never blended. */
class CompositeRoadPathTest {

    private fun fix() = LocationFix(lat = -33.87, lng = 151.21, speedKmh = 60.0, accuracyM = 10f, timestampMillis = 0L)

    private class FakeSource(private val result: RoadPath?) : RoadPathSource {
        var callCount = 0
            private set

        override fun pathAt(entryFix: LocationFix): RoadPath? {
            callCount += 1
            return result
        }
    }

    private class FakePath(private val distanceM: Double) : RoadPath {
        override fun advance(metres: Double) = 0.0 to 0.0
        override fun distanceTo(exitFix: LocationFix): Double = distanceM
    }

    @Test
    fun `an empty source list never matches anything`() {
        val composite = CompositeRoadPath(emptyList())
        assertNull(composite.pathAt(fix()))
    }

    @Test
    fun `the first matching source wins outright over a later one that would also match`() {
        val higherPriority = FakeSource(FakePath(100.0))
        val lowerPriority = FakeSource(FakePath(200.0))
        val composite = CompositeRoadPath(listOf(higherPriority, lowerPriority))

        val result = composite.pathAt(fix())

        assertTrue(result != null)
        assertEquals(100.0, result!!.distanceTo(fix())!!, 0.0)
        assertEquals(1, higherPriority.callCount)
        assertEquals(0, lowerPriority.callCount)
    }

    @Test
    fun `an unmatched higher-priority source falls through to the next one`() {
        val unmatched = FakeSource(null)
        val matched = FakeSource(FakePath(300.0))
        val composite = CompositeRoadPath(listOf(unmatched, matched))

        val result = composite.pathAt(fix())

        assertTrue(result != null)
        assertEquals(300.0, result!!.distanceTo(fix())!!, 0.0)
        assertEquals(1, unmatched.callCount)
        assertEquals(1, matched.callCount)
    }

    @Test
    fun `every source unmatched means the composite itself is unmatched`() {
        val composite = CompositeRoadPath(listOf(FakeSource(null), FakeSource(null)))
        assertNull(composite.pathAt(fix()))
    }

    @Test
    fun `the named three-source constructor tries navRoute, then corridor, then style, in order`() {
        val navRoute = FakeSource(null)
        val corridor = FakeSource(FakePath(50.0))
        val style = FakeSource(FakePath(999.0))
        val composite = CompositeRoadPath(navRoute = navRoute, corridor = corridor, style = style)

        val result = composite.pathAt(fix())

        assertEquals(50.0, result!!.distanceTo(fix())!!, 0.0)
        assertEquals(1, navRoute.callCount)
        assertEquals(1, corridor.callCount)
        assertEquals(0, style.callCount)
    }

    @Test
    fun `a null named source is simply skipped, not an error`() {
        val style = FakeSource(FakePath(42.0))
        val composite = CompositeRoadPath(navRoute = null, corridor = null, style = style)

        val result = composite.pathAt(fix())

        assertEquals(1, style.callCount)
        assertEquals(42.0, result!!.distanceTo(fix())!!, 0.0)
    }
}
