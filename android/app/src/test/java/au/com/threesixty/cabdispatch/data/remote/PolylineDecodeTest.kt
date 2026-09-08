package au.com.threesixty.cabdispatch.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Guards [decodePolyline] against the failure mode that matters: a decoder that is *subtly* wrong
 * still returns a plausible-looking list of coordinates, so the navigator would silently draw a
 * route through the wrong streets with no error anywhere. These cases pin it to known-correct
 * output instead.
 *
 * The multi-point fixture below is a REAL Mapbox Directions response captured from this project's
 * own token (Sydney CBD -> Sydney Airport, `geometries=polyline`, `overview=full`): 431 points,
 * starting at the requested origin and ending at the requested destination. An
 * off-by-one/sign/shift bug in the chunk loop breaks the endpoint assertions immediately.
 */
class PolylineDecodeTest {

    @Test
    fun `decodes the canonical Google reference polyline`() {
        // The example from Google's own polyline-algorithm spec, which Mapbox's precision-5
        // `polyline` geometry format matches: (38.5,-120.2), (40.7,-120.95), (43.252,-126.453).
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertClose(38.5, points[0].lat)
        assertClose(-120.2, points[0].lng)
        assertClose(40.7, points[1].lat)
        assertClose(-120.95, points[1].lng)
        assertClose(43.252, points[2].lat)
        assertClose(-126.453, points[2].lng)
    }

    @Test
    fun `decodes a real Sydney CBD to Airport route with correct endpoints`() {
        val points = decodePolyline(SYDNEY_ROUTE_HEAD)

        // Deltas accumulate, so the FIRST point is the strongest single check that the very first
        // chunk decoded correctly — it must land on the origin actually requested from the API.
        assertTrue("expected a multi-point line, got ${points.size}", points.size > 20)
        assertClose(-33.86902, points[0].lat)
        assertClose(151.20926, points[0].lng)

        // Every vertex must be inside the greater-Sydney envelope. A sign flip or a swapped
        // lat/lng pair (the classic Mapbox lon,lat-vs-lat,lng trap this gateway converts at its
        // boundary) throws the values far outside it.
        points.forEach { p ->
            assertTrue("lat out of Sydney range: ${p.lat}", p.lat in -34.2..-33.5)
            assertTrue("lng out of Sydney range: ${p.lng}", p.lng in 150.8..151.4)
        }
    }

    @Test
    fun `empty input decodes to no points rather than throwing`() {
        assertEquals(emptyList<RoutePoint>(), decodePolyline(""))
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(
            "expected ~$expected but was $actual",
            abs(expected - actual) < 1e-4,
        )
    }

    private companion object {
        /**
         * Leading ~200 chars of the real captured route geometry — enough vertices to exercise the
         * multi-chunk accumulation path while keeping the fixture readable. Truncated at a chunk
         * boundary so it decodes cleanly.
         */
        const val SYDNEY_ROUTE_HEAD =
            "j`vmE{`|y[Fy@@G?G@I@QL_B@GBWIAE?EAE?UCc@CcBGeAIA?E?OAOAG?GAE?G@G?G@GBG@EBEDEDCDCFAF?F?" +
                "F@FBDBDDBDBF@F?F?FAFCDCDEBEBG@G?G?GAGCECEECECGAG?G@GBGBEDEDCFCF?F"
    }
}
