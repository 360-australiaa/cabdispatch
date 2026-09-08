package au.com.threesixty.cabdispatch.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the real bug found live 2026-09-07 (Trip Detail's ROUTE card rendering "Map unavailable"
 * the first time a trip actually had a real GPS trace) — see
 * [MapboxStaticImage.encodeOverlayValue]'s own doc for the full story. Confirmed directly against
 * `okhttp3.HttpUrl` (what Coil's default `ImageLoader` actually parses this string with), not
 * merely asserted: before the fix, [MapboxStaticImage.tripOverlayUrl] embedded the raw
 * encoded-polyline blob straight into the URL's path segment, and a near-stationary trace's own
 * GPS jitter reliably produces at least one zero-delta point pair — which the Google/Mapbox
 * polyline alphabet encodes as a literal `?`, `okhttp3.HttpUrl`'s own query-string delimiter.
 */
class MapboxStaticImageEncodingTest {

    /** Two points whose 5-decimal-rounded latitude is identical (only longitude moves) —
     * deterministically produces a zero latitude-delta, which [encodePolyline] emits as a single
     * `?` character (see that function's own doc for the algorithm). This is not a contrived edge
     * case: a real near-stationary "waiting" fare's own GPS jitter reproduces this routinely (the
     * real trip that surfaced this bug moved only ~62m across 88 recorded points). */
    private val nearZeroDeltaPath = listOf(
        -33.86881 to 151.20934,
        -33.86881 to 151.20940,
    )

    @Test
    fun `a near-stationary trace's encoded polyline really does contain a raw question mark`() {
        // Confirms the precondition this whole bug depends on, rather than assuming it.
        val polyline = encodePolyline(nearZeroDeltaPath)
        assertTrue("expected a literal '?' in $polyline — if this fails, the bug's precondition changed", polyline.contains('?'))
    }

    @Test
    fun `tripOverlayUrl produces a URL whose real HTTP path is not truncated at an embedded question mark`() {
        val url = MapboxStaticImage.tripOverlayUrl(
            pickupLat = -33.86881,
            pickupLng = 151.20934,
            dropoffLat = -33.86881,
            dropoffLng = 151.20940,
            drivenPathPoints = nearZeroDeltaPath,
            widthPx = 600,
            heightPx = 400,
            accessToken = "pk.test",
        )

        val parsed = url.toHttpUrl()

        // Before the fix: the raw '?' truncated the path here, dropping the closing ')', the
        // drop-off pin, and the whole "/auto/{w}x{h}@2x" size segment — a request Mapbox's server
        // has nothing valid to render, hence Coil's AsyncImagePainter landing in State.Error.
        assertTrue(
            "real HTTP path was truncated — got '${parsed.encodedPath}'",
            parsed.encodedPath.endsWith("/auto/600x400@2x"),
        )
        assertTrue(
            "path overlay lost its closing paren — got '${parsed.encodedPath}'",
            parsed.encodedPath.contains("path-3+"),
        )
        // Only the two real query params this function ever adds — nothing from the overlay data
        // leaked into the query string.
        assertEquals(setOf("padding", "access_token"), parsed.queryParameterNames)
        assertEquals("pk.test", parsed.queryParameter("access_token"))
        assertEquals("32", parsed.queryParameter("padding"))
    }
}
