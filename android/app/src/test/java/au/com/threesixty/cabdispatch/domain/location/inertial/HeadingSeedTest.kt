package au.com.threesixty.cabdispatch.domain.location.inertial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pins [HeadingSeed]'s geometry with rotation vectors whose device->world orientation is known by
 * construction, so a sign or transpose slip in the quaternion maths shows up here rather than as
 * a tablet that integrates braking as acceleration on its first tunnel.
 */
class HeadingSeedTest {

    private fun assertVec(expected: DoubleArray, actual: DoubleArray?, tol: Double = 1e-6) {
        requireNotNull(actual) { "expected a forward axis, got null" }
        for (i in 0..2) assertEquals("component $i", expected[i], actual[i], tol)
    }

    @Test
    fun `identity rotation - device axes are world axes - heading north is device +Y`() {
        // Identity quaternion: device X=east, Y=north, Z=up. Forward = north => tablet +Y.
        val rv = floatArrayOf(0f, 0f, 0f, 1f)
        assertVec(doubleArrayOf(0.0, 1.0, 0.0), HeadingSeed.forwardAxisInTabletFrame(rv, 0.0))
        // Heading east => tablet +X.
        assertVec(doubleArrayOf(1.0, 0.0, 0.0), HeadingSeed.forwardAxisInTabletFrame(rv, 90.0))
    }

    @Test
    fun `three-component rotation vector reconstructs the scalar part`() {
        // Same identity, scalar omitted as many devices report it.
        val rv = floatArrayOf(0f, 0f, 0f)
        assertVec(doubleArrayOf(0.0, 1.0, 0.0), HeadingSeed.forwardAxisInTabletFrame(rv, 0.0))
    }

    @Test
    fun `device yawed 90deg about up - world north is device -X`() {
        // Rotation of +90deg about world Z: device +X points north, device +Y points west.
        // q = (0, 0, sin45, cos45). Forward=north => in device frame that is +X? Check: R maps
        // device +X to world (cos90, sin90, 0) = north. So world-north expressed in device frame
        // is +X.
        val s = sqrt(0.5).toFloat()
        val rv = floatArrayOf(0f, 0f, s, s)
        assertVec(doubleArrayOf(1.0, 0.0, 0.0), HeadingSeed.forwardAxisInTabletFrame(rv, 0.0))
        // And heading west (270) => device +Y.
        assertVec(doubleArrayOf(0.0, 1.0, 0.0), HeadingSeed.forwardAxisInTabletFrame(rv, 270.0))
    }

    @Test
    fun `tablet standing upright in a dash mount - forward is device -Z`() {
        // Pitch the tablet up 90deg about world X (screen facing the driver, top edge up):
        // device +Y now points up (world Z), device +Z points toward the driver, i.e. -north
        // if the car faces north. q = (sin45, 0, 0, cos45). World north in device frame => -Z.
        val s = sqrt(0.5).toFloat()
        val rv = floatArrayOf(s, 0f, 0f, s)
        assertVec(doubleArrayOf(0.0, 0.0, -1.0), HeadingSeed.forwardAxisInTabletFrame(rv, 0.0))
    }

    @Test
    fun `absent or zero rotation vector never guesses`() {
        assertNull(HeadingSeed.forwardAxisInTabletFrame(floatArrayOf(0f, 0f, 0f, 0f), 45.0))
        assertNull(HeadingSeed.forwardAxisInTabletFrame(floatArrayOf(), 45.0))
    }
}
