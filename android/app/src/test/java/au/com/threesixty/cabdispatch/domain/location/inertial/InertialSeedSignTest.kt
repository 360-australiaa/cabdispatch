package au.com.threesixty.cabdispatch.domain.location.inertial

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/**
 * End-to-end sign check of the heading-seeded chain, 2026-09-14: a forward acceleration laid
 * along [HeadingSeed]'s own axis (exactly what SimulatedImu does on the bench, and what a real
 * mount does physically) must integrate as an INCREASE in speed through [VehicleFrameCalibrator]
 * + [InertialSpeedEstimator], for a tablet in any orientation. Pins the contract the bench run
 * exercises so a sign slip in either half can never hide behind the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class InertialSeedSignTest {

    private fun run(rotationVector: FloatArray, gravity: FloatArray, headingDeg: Double, accelMps2: Double): Double {
        val calibrator =
            VehicleFrameCalibrator(ApplicationProvider.getApplicationContext(), deviceKey = "sign-${System.nanoTime()}")
        val estimator = InertialSpeedEstimator()
        val forward = HeadingSeed.forwardAxisInTabletFrame(rotationVector, headingDeg)!!
        val accel = FloatArray(3) { (forward[it] * accelMps2).toFloat() }
        fun sample(nanos: Long) = ImuSample(nanos, accel, gravity, floatArrayOf(0f, 0f, 0f), rotationVector)
        assertTrue(calibrator.seedFromHeading(sample(0L), headingDeg))
        assertEquals(CalibrationQuality.SEEDED, calibrator.calibration.value?.quality)
        estimator.seed(speedKmh = 60.0, headingDegOrNull = headingDeg)
        var t = 0L
        var last = estimator.step(sample(t), calibrator.calibration.value)
        repeat(50) { // 5 s at 10 Hz
            t += 100_000_000L
            last = estimator.step(sample(t), calibrator.calibration.value)
        }
        return last.speedKmh
    }

    @Test
    fun `flat tablet, decelerating along the seeded axis, slows down`() {
        val speed = run(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 9.81f), headingDeg = 295.0, accelMps2 = -0.5)
        assertEquals("60 km/h - 0.5 m/s^2 x 5 s = 51 km/h", 51.0, speed, 1.0)
    }

    @Test
    fun `tablet stood up in a mount, decelerating along the seeded axis, slows down`() {
        // 90 degrees about x: the screen faces the driver, gravity now along the tablet's -y.
        val s = sqrt(0.5).toFloat()
        val rv = floatArrayOf(s, 0f, 0f) // (x sin(45), 0, 0), w = cos(45) reconstructed
        val speed = run(rv, floatArrayOf(0f, -9.81f, 0f), headingDeg = 120.0, accelMps2 = -0.5)
        assertEquals(51.0, speed, 1.0)
    }

    @Test
    fun `accelerating along the seeded axis speeds up`() {
        val speed = run(floatArrayOf(0f, 0f, 0.3f), floatArrayOf(0f, 0f, 9.81f), headingDeg = 40.0, accelMps2 = 0.5)
        assertEquals(69.0, speed, 1.0)
    }
}
