package au.com.threesixty.cabdispatch.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [MapboxStaticImage.downsampleForOverlay] — the cap that keeps a long trip's real GPS trace
 * (now genuinely populated, see [au.com.threesixty.cabdispatch.data.repository.TripRepository.tick]'s
 * doc) from building an overlong Static Images API request URL. See that function's own doc for
 * why this is display-only (never applied to the trace this app actually persists/syncs).
 */
class MapboxStaticImageDownsampleTest {

    @Test
    fun `a trace at or under the cap is returned unchanged`() {
        val points = (0 until 300).map { it.toDouble() to it.toDouble() }

        val result = MapboxStaticImage.downsampleForOverlay(points)

        assertEquals(points, result)
    }

    @Test
    fun `a trace over the cap is downsampled to the cap, keeping the first and last point`() {
        val points = (0 until 3600).map { it.toDouble() to it.toDouble() }

        val result = MapboxStaticImage.downsampleForOverlay(points)

        assertEquals(300, result.size)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }

    @Test
    fun `downsampled points stay in the original order`() {
        val points = (0 until 1000).map { it.toDouble() to it.toDouble() }

        val result = MapboxStaticImage.downsampleForOverlay(points)

        // Every kept point's first coordinate (the synthetic index, by construction above) is
        // strictly increasing — i.e. this never reorders or duplicates out of sequence.
        assertTrue(result.zipWithNext().all { (a, b) -> a.first < b.first })
    }
}
