package au.com.threesixty.cabdispatch.domain.location.inertial

import androidx.test.core.app.ApplicationProvider
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [VehicleFrameCalibrator] — task 3. Robolectric (`SecurePrefs`/`EncryptedSharedPreferences` need
 * a real `Context`), same reasoning `RoomMigrationTest` in this same module is Robolectric rather
 * than plain JUnit or a `connectedAndroidTest` (no `adb`/emulator access in this workstream).
 *
 * Every "confirming event" below is manufactured the same way: one GPS speed jump that clears
 * task 3's `0.5 m/s^2` sustained-change bar, followed by two [ImuSample]s 1.1s apart carrying a
 * horizontal acceleration in a chosen direction — see [VehicleFrameCalibrator.trackConfirmingWindow]'s
 * own logic for why exactly two samples (start the window, then close it once `SUSTAINED_SECONDS`
 * has elapsed) is enough to produce exactly one confirming direction per call.
 */
@RunWith(RobolectricTestRunner::class)
// Same reasoning as `RoomMigrationTest`'s own identical annotation: the real `CabDispatchApp`
// initialises the Mapbox SDK's native library in `onCreate`, which Robolectric cannot load and
// this test has no need of -- a plain stub `Application` is all `SecurePrefs`/`EncryptedShared
// Preferences` need a real `Context` for.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class VehicleFrameCalibratorTest {

    private companion object {
        const val TEN_SECONDS_NANOS = 10_000_000_000L
    }

    private fun newCalibrator() =
        VehicleFrameCalibrator(ApplicationProvider.getApplicationContext(), deviceKey = "test-${System.nanoTime()}")

    private fun gravity(): FloatArray = floatArrayOf(0f, 0f, 9.81f)

    private fun accelAtAngleDeg(deg: Double, magnitude: Float = 2f): FloatArray {
        val x = (magnitude * cos(Math.toRadians(deg))).toFloat()
        val y = (magnitude * sin(Math.toRadians(deg))).toFloat()
        return floatArrayOf(x, y, 0f)
    }

    private fun imuSample(nanos: Long, accel: FloatArray, gyro: FloatArray = floatArrayOf(0f, 0f, 0f)) = ImuSample(
        timestampNanos = nanos,
        linearAccelerationMps2 = accel,
        gravityMps2 = gravity(),
        gyroscopeRadPerS = gyro,
        rotationVector = floatArrayOf(0f, 0f, 0f),
    )

    /** One confirming event at [angleDeg], at a fresh time base ([baseNanos]) so successive calls
     * never overlap windows. */
    private fun feedConfirmingEvent(calibrator: VehicleFrameCalibrator, baseNanos: Long, angleDeg: Double) {
        val accel = accelAtAngleDeg(angleDeg)
        calibrator.onGpsSpeedSample(0.0, baseNanos)
        calibrator.onGpsSpeedSample(50.0, baseNanos + 1_000_000_000L) // 13.9 m/s in 1s >> 0.5 m/s^2 bar
        calibrator.onImuSample(imuSample(baseNanos + 2_000_000_000L, accel))
        calibrator.onImuSample(imuSample(baseNanos + 3_100_000_000L, accel)) // +1.1s, closes the window
    }

    /** Feeds one confirming event per angle in [angles], each at its own 10s-spaced time base so
     * consecutive events never overlap windows — the repeated shape every test below needs. */
    private fun feedConfirmingEvents(calibrator: VehicleFrameCalibrator, angles: List<Double>) {
        angles.forEachIndexed { i, angle ->
            feedConfirmingEvent(calibrator, baseNanos = i * TEN_SECONDS_NANOS, angleDeg = angle)
        }
    }

    @Test
    fun fewerThanMinEvents_stillNoQuality() {
        val calibrator = newCalibrator()
        repeat(VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS - 1) { i ->
            feedConfirmingEvent(calibrator, baseNanos = i * 10_000_000_000L, angleDeg = 0.0)
        }

        assertEquals(CalibrationQuality.NONE, calibrator.calibration.value?.quality)
    }

    @Test
    fun minEventsWithTightSpread_reachesGood() {
        val calibrator = newCalibrator()
        // All within a couple of degrees of 0 -- well under the 15 deg bar.
        feedConfirmingEvents(calibrator, listOf(0.0, 1.0, 2.0, -1.0, 0.5, -0.5, 1.5, -1.5))

        val result = calibrator.calibration.value
        assertNotNull(result)
        assertEquals(CalibrationQuality.GOOD, result?.quality)
        assertEquals(VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS, result?.confirmingEventCount)
    }

    @Test
    fun minEventsWithWideSpread_staysLearning() {
        val calibrator = newCalibrator()
        // Spread well past the 15 deg bar (0..70 deg across 8 events).
        val angles = (0 until VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS).map { it * 10.0 }
        feedConfirmingEvents(calibrator, angles)

        assertEquals(CalibrationQuality.LEARNING, calibrator.calibration.value?.quality)
    }

    @Test
    fun mountDisturbance_invalidatesAGoodCalibration() {
        val calibrator = newCalibrator()
        feedConfirmingEvents(calibrator, listOf(0.0, 1.0, 2.0, -1.0, 0.5, -0.5, 1.5, -1.5))
        assertEquals(CalibrationQuality.GOOD, calibrator.calibration.value?.quality)

        // Cools down `pendingSpeedChange` from the 8th confirming event's own GPS jump -- without
        // this, it would still read as "sustained" and the very next onImuSample call would open
        // (and, a second later, close) a spurious 9th confirming window off whatever sample happens
        // to arrive next, polluting the calibration this test means to hold steady at GOOD while it
        // exercises disturbance tracking, which is a wholly separate concern.
        calibrator.onGpsSpeedSample(50.0, 90_000_000_000L)

        // Establishes the reference gravity direction (first sample after GOOD).
        var t = 100_000_000_000L
        calibrator.onImuSample(imuSample(t, accelAtAngleDeg(0.0)))

        // Tablet disturbed: gravity rotates ~30 deg away from the reference, sustained > 5s.
        val disturbedGravitySample = ImuSample(
            timestampNanos = 0L,
            linearAccelerationMps2 = accelAtAngleDeg(0.0),
            gravityMps2 = floatArrayOf(4.9f, 0f, 8.49f), // ~30 deg tilt from straight-down
            gyroscopeRadPerS = floatArrayOf(0f, 0f, 0f),
            rotationVector = floatArrayOf(0f, 0f, 0f),
        )
        repeat(7) {
            t += 1_000_000_000L
            calibrator.onImuSample(disturbedGravitySample.copy(timestampNanos = t))
        }

        assertNull("a sustained > 10deg/5s gravity shift must invalidate calibration", calibrator.calibration.value)
    }

    @Test
    fun invalidate_clearsCalibrationAndConfirmingHistory() {
        val calibrator = newCalibrator()
        feedConfirmingEvents(calibrator, listOf(0.0, 1.0, 2.0, -1.0, 0.5, -0.5, 1.5, -1.5))
        assertEquals(CalibrationQuality.GOOD, calibrator.calibration.value?.quality)

        calibrator.invalidate()

        assertNull(calibrator.calibration.value)
    }
}
