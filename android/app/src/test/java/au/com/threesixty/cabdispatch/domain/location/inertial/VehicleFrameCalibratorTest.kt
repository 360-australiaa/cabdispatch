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
        feedConfirmingEvents(
            calibrator,
            listOf(0.0, 1.0, 2.0, -1.0, 0.5, -0.5, 1.5, -1.5).take(VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS),
        )

        val result = calibrator.calibration.value
        assertNotNull(result)
        assertEquals(CalibrationQuality.GOOD, result?.quality)
        assertEquals(VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS, result?.confirmingEventCount)
    }

    @Test
    fun minEventsWithWideSpread_staysLearning() {
        val calibrator = newCalibrator()
        // Spread well past the 15 deg bar (0..70 deg across MIN_CALIBRATION_EVENTS events).
        val angles = (0 until VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS).map { it * 10.0 }
        feedConfirmingEvents(calibrator, angles)

        assertEquals(CalibrationQuality.LEARNING, calibrator.calibration.value?.quality)
    }

    @Test
    fun minEventsWithWideSpreadButAGoodSeed_fallsBackToSeededNotLearning() {
        // Real Sydney drive, 2026-09-15: speed froze mid-tunnel even though the tablet had a good
        // seed a moment earlier. Each of these four events is only 25 deg from the seed (well
        // inside MAX_SEED_DISAGREEMENT_DEG, so all four count as evidence) but they alternate
        // sides, so their spread against EACH OTHER is 50 deg -- past the 15 deg GOOD bar. Before
        // the fix this discarded the seed for CalibrationQuality.LEARNING, which
        // InertialSpeedEstimator treats exactly like no calibration at all and freezes the display.
        val calibrator = newCalibrator()
        seedEast(calibrator)
        feedConfirmingEvents(calibrator, listOf(-25.0, 25.0, -25.0, 25.0))

        val c = calibrator.calibration.value!!
        assertEquals(VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS, c.confirmingEventCount)
        assertEquals(
            "a noisy confirming spread must fall back to the seed, not discard it",
            CalibrationQuality.SEEDED,
            c.quality,
        )
        assertEquals("the seed's own east axis, not the noisy mean", 1.0, c.forwardTablet[0], 1e-6)
    }

    @Test
    fun minEventsWithWideSpreadAndNoSeed_staysLearning() {
        // The counterpart to the fix above: with no seed to fall back to, a noisy spread is
        // genuinely nothing trustworthy and must still read LEARNING, same as before.
        val calibrator = newCalibrator()
        feedConfirmingEvents(calibrator, listOf(-25.0, 25.0, -25.0, 25.0))

        assertEquals(CalibrationQuality.LEARNING, calibrator.calibration.value?.quality)
    }

    @Test
    fun mountDisturbance_invalidatesAGoodCalibration() {
        val calibrator = newCalibrator()
        feedConfirmingEvents(
            calibrator,
            listOf(0.0, 1.0, 2.0, -1.0, 0.5, -0.5, 1.5, -1.5).take(VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS),
        )
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
        feedConfirmingEvents(
            calibrator,
            listOf(0.0, 1.0, 2.0, -1.0, 0.5, -0.5, 1.5, -1.5).take(VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS),
        )
        assertEquals(CalibrationQuality.GOOD, calibrator.calibration.value?.quality)

        calibrator.invalidate()

        assertNull(calibrator.calibration.value)
    }

    // --- 2026-09-14 bench finding: the heading seed polices the learned axis ------------------

    /** Seeds heading 90 (east) with a zero rotation vector: the tablet frame IS the world frame,
     * so the seeded forward axis is +x = [accelAtAngleDeg] 0°. */
    private fun seedEast(calibrator: VehicleFrameCalibrator) {
        assertTrue(calibrator.seedFromHeading(imuSample(0L, floatArrayOf(0f, 0f, 0f)), headingDeg = 90.0))
        assertEquals(CalibrationQuality.SEEDED, calibrator.calibration.value?.quality)
    }

    @Test
    fun confirmingEvents_thatDisagreeWithTheSeed_areNotEvidence() {
        val calibrator = newCalibrator()
        seedEast(calibrator)
        // Eight "events" pointing straight backwards (a screen tap during every speed change) --
        // before the guard these made a GOOD calibration with a sign-flipped axis.
        feedConfirmingEvents(calibrator, List(8) { 180.0 })
        val c = calibrator.calibration.value!!
        assertEquals("nothing may be learned from events pointing away from the seed", 0, c.confirmingEventCount)
        assertEquals(CalibrationQuality.SEEDED, c.quality)
        assertEquals(1.0, c.forwardTablet[0], 1e-6)
    }

    @Test
    fun confirmingEvents_nearTheSeed_stillLearnAGoodAxis() {
        val calibrator = newCalibrator()
        seedEast(calibrator)
        feedConfirmingEvents(calibrator, listOf(5.0, -5.0, 8.0, -8.0))
        val c = calibrator.calibration.value!!
        assertEquals(CalibrationQuality.GOOD, c.quality)
        assertEquals(VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS, c.confirmingEventCount)
    }

    @Test
    fun aGoodAxisThatKeepsDisagreeingWithTheSeed_isDiscardedForTheSeed() {
        val calibrator = newCalibrator()
        // Learned (no seed yet) pointing WEST -- the persisted bogus axis of the bench finding.
        feedConfirmingEvents(calibrator, listOf(180.0, 178.0, 182.0, 180.0))
        assertEquals(CalibrationQuality.GOOD, calibrator.calibration.value?.quality)

        // Now real driving: the seed says EAST. One disagreement is not enough...
        repeat(VehicleFrameCalibrator.SEED_DISAGREEMENTS_TO_INVALIDATE - 1) {
            assertEquals(false, calibrator.seedFromHeading(imuSample(0L, floatArrayOf(0f, 0f, 0f)), headingDeg = 90.0))
        }
        assertEquals(CalibrationQuality.GOOD, calibrator.calibration.value?.quality)
        // ...a sustained one is.
        assertTrue(calibrator.seedFromHeading(imuSample(0L, floatArrayOf(0f, 0f, 0f)), headingDeg = 90.0))
        val c = calibrator.calibration.value!!
        assertEquals(CalibrationQuality.SEEDED, c.quality)
        assertEquals(0, c.confirmingEventCount)
        assertEquals("the seed's own east axis is now in force", 1.0, c.forwardTablet[0], 1e-6)
    }

    @Test
    fun aGoodAxisThatAgreesWithTheSeed_isKept() {
        val calibrator = newCalibrator()
        feedConfirmingEvents(calibrator, listOf(0.0, 3.0, -3.0, 2.0)) // east
        assertEquals(CalibrationQuality.GOOD, calibrator.calibration.value?.quality)
        repeat(VehicleFrameCalibrator.SEED_DISAGREEMENTS_TO_INVALIDATE + 2) {
            calibrator.seedFromHeading(imuSample(0L, floatArrayOf(0f, 0f, 0f)), headingDeg = 90.0)
        }
        assertEquals(CalibrationQuality.GOOD, calibrator.calibration.value?.quality)
    }

    @Test
    fun `a failed seed attempt does not block a later retry with the same heading from succeeding`() {
        // 2026-09-16 field report: onBlackoutEntered's own seed attempt is a single shot against
        // whichever IMU sample happened to be latest at that exact instant -- if THAT ONE sample's
        // rotation vector was absent (the sensor had not produced a reading yet), the seed failed
        // and nothing retried it for the rest of the blackout, freezing the display. The fix
        // (InertialSpeedSource.maybeRetrySeedDuringBlackout) retries the SAME frozen entry heading
        // against every later sample until one succeeds -- this pins the calibrator-level guarantee
        // that retry relies on: a failed attempt must not poison a later successful one.
        val calibrator = newCalibrator()
        val noSensorYet = imuSample(0L, floatArrayOf(0f, 0f, 0f), gyro = floatArrayOf(0f, 0f, 0f))
            .copy(rotationVector = floatArrayOf()) // too few components -- HeadingSeed's own "absent" case
        assertEquals(
            "an absent rotation vector must not seed anything",
            false,
            calibrator.seedFromHeading(noSensorYet, headingDeg = 90.0),
        )
        assertNull("a failed seed attempt must leave calibration untouched, not NONE", calibrator.calibration.value)

        // A later sample, same frozen heading, now carries a real (if identity) rotation vector.
        val sensorNowReporting = imuSample(1_000_000L, floatArrayOf(0f, 0f, 0f))
        assertTrue(calibrator.seedFromHeading(sensorNowReporting, headingDeg = 90.0))
        val c = calibrator.calibration.value!!
        assertEquals(CalibrationQuality.SEEDED, c.quality)
        assertEquals(1.0, c.forwardTablet[0], 1e-6)
    }
}
