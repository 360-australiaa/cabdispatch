package au.com.threesixty.cabdispatch.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [encodePolyline] (the Static Images API `path-` overlay's encoder, added for the "real
 * point-to-point map image" pass, 2026-09-05) — the exact inverse of [decodePolyline], which
 * [PolylineDecodeTest] already pins to the same canonical Google reference example. A subtly wrong
 * encoder would still produce a plausible-looking string that Mapbox might render as *some* line —
 * just not the real one — so this checks against known-correct output, not just "doesn't crash".
 */
class PolylineEncodeTest {

    @Test
    fun `encodes the canonical Google reference points to the canonical encoded string`() {
        // Same three points from Google's own polyline-algorithm spec that PolylineDecodeTest
        // decodes the other way: (38.5,-120.2), (40.7,-120.95), (43.252,-126.453).
        val encoded = encodePolyline(listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
    }

    @Test
    fun `round-trips through decodePolyline back to the original points`() {
        val original = listOf(-33.86902 to 151.20926, -33.94500 to 151.17000, -33.86882 to 151.21100)
        val decoded = decodePolyline(encodePolyline(original))

        assertEquals(original.size, decoded.size)
        original.forEachIndexed { i, (lat, lng) ->
            assertClose(lat, decoded[i].lat)
            assertClose(lng, decoded[i].lng)
        }
    }

    @Test
    fun `empty input encodes to an empty string`() {
        assertEquals("", encodePolyline(emptyList()))
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue("expected ~$expected but was $actual", kotlin.math.abs(expected - actual) < 1e-4)
    }
}
