package au.com.threesixty.cabdispatch.domain.location.inertial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InertialSpeedEstimator] — plain JVM, no Android dependency (see that class's own doc for why).
 * Every sample here is synthetic: a straight line of constructed [ImuSample]s, never a recorded
 * drive (task 7's real-drive fixtures are a separate, owner-supplied concern — see
 * [ImuTraceRecorder]'s doc).
 */
class InertialSpeedEstimatorTest {

    private val goodCalibration = VehicleFrameCalibration(
        forwardTablet = doubleArrayOf(1.0, 0.0, 0.0),
        gyroZBiasRadPerS = 0.0,
        forwardAccelBiasMps2 = 0.0,
        quality = CalibrationQuality.GOOD,
        confirmingEventCount = 8,
    )

    private fun sample(
        nanos: Long,
        forwardAccel: Float = 0f,
        gravity: FloatArray = floatArrayOf(0f, 0f, 9.81f),
        gyroZ: Float = 0f,
        verticalAccel: Float = 0f,
    ) = ImuSample(
        timestampNanos = nanos,
        linearAccelerationMps2 = floatArrayOf(forwardAccel, 0f, verticalAccel),
        gravityMps2 = gravity,
        gyroscopeRadPerS = floatArrayOf(0f, 0f, gyroZ),
        rotationVector = floatArrayOf(0f, 0f, 0f),
    )

    @Test
    fun uncalibrated_freezesSpeedAndReportsCalibrationGoodFalse() {
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 60.0, headingDegOrNull = 90.0)

        val result = estimator.step(sample(1_000_000_000L, forwardAccel = 2f), calibration = null)

        assertFalse(result.calibrationGood)
        assertEquals(60.0, result.speedKmh, 0.001)
    }

    @Test
    fun learningQualityCalibration_alsoNotUsable() {
        val estimator = InertialSpeedEstimator()
        estimator.seed(60.0, 90.0)
        val learning = goodCalibration.copy(quality = CalibrationQuality.LEARNING)

        val result = estimator.step(sample(1_000_000_000L, forwardAccel = 2f), learning)

        assertFalse(result.calibrationGood)
    }

    @Test
    fun forwardAcceleration_integratesSpeedWithinBounds() {
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 60.0, headingDegOrNull = 0.0) // seed sets the +30km/h bound at 90 km/h

        // The first step after a seed always has dt=0 (no prior sample to difference against --
        // see [InertialSpeedEstimator.step]'s own `lastSampleNanos` handling), so three samples one
        // second apart give exactly two real 1s integration steps: 1 m/s^2 forward for 2s -> +2 m/s
        // = +7.2 km/h -> ~67.2 km/h.
        estimator.step(sample(0L, forwardAccel = 1f), goodCalibration)
        estimator.step(sample(1_000_000_000L, forwardAccel = 1f), goodCalibration)
        val result = estimator.step(sample(2_000_000_000L, forwardAccel = 1f), goodCalibration)

        assertTrue(result.calibrationGood)
        assertEquals(67.2, result.speedKmh, 0.5)
        assertTrue("must never exceed the seed+30km/h bound", result.speedKmh <= 90.0 + 0.001)
    }

    @Test
    fun forwardAcceleration_neverExceedsSeedPlusBoundMargin() {
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 60.0, headingDegOrNull = 0.0) // bound = min(90, 110) = 90 km/h

        // A large, sustained forward acceleration for many seconds would integrate well past the
        // bound without the clamp -- 4 m/s^2 (the clamp ceiling itself) for 30s is +120 m/s if
        // unbounded.
        var t = 0L
        var last = estimator.step(sample(t, forwardAccel = 4f), goodCalibration)
        repeat(30) {
            t += 1_000_000_000L
            last = estimator.step(sample(t, forwardAccel = 4f), goodCalibration)
        }

        assertEquals(90.0, last.speedKmh, 0.01)
    }

    @Test
    fun zuptFires_whenStationaryAndQuiet_andResetsSpeedToZero() {
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 60.0, headingDegOrNull = 0.0)

        var t = 0L
        var last = estimator.step(sample(t), goodCalibration)
        // Feed ~2s of near-zero, low-variance samples at 100ms cadence (matches ImuSampler's own
        // publish rate) so the 1.5s ZUPT window fills with a consistent stationary signature.
        repeat(20) {
            t += 100_000_000L
            last = estimator.step(sample(t, forwardAccel = 0.01f), goodCalibration)
        }

        assertEquals(0.0, last.speedKmh, 0.001)
        assertTrue("ZUPT must have fired at least once", last.zuptCount > 0)
    }

    @Test
    fun steadyNonZeroAcceleration_neverFiresZuptFromLowVarianceAlone() {
        // Regression for the literal "variance < threshold" reading of task 4's rule: a CONSTANT
        // nonzero acceleration (steady acceleration, not a stop) has zero variance too, and must
        // never be mistaken for a ZUPT purely on that account -- see the estimator's own `isZupt`
        // comment.
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 0.0, headingDegOrNull = 0.0)

        var t = 0L
        var last = estimator.step(sample(t, forwardAccel = 1.0f), goodCalibration)
        repeat(20) {
            t += 100_000_000L
            last = estimator.step(sample(t, forwardAccel = 1.0f), goodCalibration)
        }

        assertTrue("a steady real acceleration must keep integrating, not ZUPT to zero", last.speedKmh > 1.0)
    }

    @Test
    fun confidenceDegradesOverTimeBetweenZupts_andLatchesUnreliableAfter120s() {
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 60.0, headingDegOrNull = 0.0)

        // Small, noisy-but-not-quiet accelerations, never triggering a ZUPT (magnitude kept above
        // the ZUPT floor) -- sigmaV grows monotonically at 0.05/s the whole time, crosses the LOW
        // threshold (3.0) at t=60s, and must accumulate > 120s in LOW before latching -- so this
        // needs to run well past 180s total, not just past the 120s figure itself.
        var t = 0L
        var last = estimator.step(sample(t, forwardAccel = 0.5f), goodCalibration)
        repeat(200) {
            t += 1_000_000_000L
            last = estimator.step(sample(t, forwardAccel = 0.5f), goodCalibration)
        }

        assertTrue("120s+ in LOW confidence without a ZUPT must latch UNRELIABLE", last.unreliable)
    }

    @Test
    fun seed_resetsErrorBudgetAndUnreliableLatch() {
        val estimator = InertialSpeedEstimator()
        estimator.seed(60.0, 0.0)
        var t = 0L
        var last = estimator.step(sample(t, forwardAccel = 0.5f), goodCalibration)
        repeat(200) {
            t += 1_000_000_000L
            last = estimator.step(sample(t, forwardAccel = 0.5f), goodCalibration)
        }
        assertTrue(last.unreliable)

        estimator.seed(65.0, 10.0) // a fresh GPS seed, e.g. shadow-mode reseed on reacquisition
        last = estimator.step(sample(t + 1_000_000_000L, forwardAccel = 0f), goodCalibration)

        assertFalse("a fresh seed must clear the UNRELIABLE latch", last.unreliable)
        assertEquals(InertialConfidence.HIGH, last.confidence)
    }

    @Test
    fun neverReportsNegativeSpeed() {
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 5.0, headingDegOrNull = 0.0)

        // Sustained heavy braking (negative forward acceleration) -- the estimator must clamp at
        // zero, never "reverse" (task 4: "0 <= vEst").
        var t = 0L
        var last = estimator.step(sample(t, forwardAccel = -4f), goodCalibration)
        repeat(10) {
            t += 1_000_000_000L
            last = estimator.step(sample(t, forwardAccel = -4f), goodCalibration)
        }

        assertEquals(0.0, last.speedKmh, 0.001)
    }

    @Test
    fun cruiseWithLightRoadVibration_neverZuptsFromCruisingSpeed() {
        // Bench finding, 2026-09-14 (Lane Cove Tunnel route on the Karachi tablet): ~0.2 m/s^2 of
        // vertical road vibration at a steady 90 km/h is under the 0.3 magnitude threshold and has
        // low variance, so the old rule zeroed the speed the instant GPS dropped. A car cannot stop
        // from 90 without a deceleration this integrator would have seen.
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 90.0, headingDegOrNull = 0.0)

        var t = 0L
        var last = estimator.step(sample(t, verticalAccel = 0.2f), goodCalibration)
        repeat(40) {
            t += 100_000_000L
            last = estimator.step(sample(t, verticalAccel = if (it % 2 == 0) 0.2f else -0.2f), goodCalibration)
        }

        assertEquals("cruising speed must survive a quiet window", 90.0, last.speedKmh, 0.001)
        assertEquals(0, last.zuptCount)
    }

    @Test
    fun quietWindow_stillZupts_onceTheIntegratedSpeedIsNearZero() {
        // The gate must not break the real stop: rolled down to ~10 km/h with the same light
        // vibration present, a quiet window IS a stop.
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 10.0, headingDegOrNull = 0.0)

        var t = 0L
        var last = estimator.step(sample(t, verticalAccel = 0.2f), goodCalibration)
        repeat(20) {
            t += 100_000_000L
            last = estimator.step(sample(t, verticalAccel = if (it % 2 == 0) 0.2f else -0.2f), goodCalibration)
        }

        assertEquals(0.0, last.speedKmh, 0.001)
        assertTrue(last.zuptCount > 0)
    }

    @Test
    fun trueStillnessFloor_zuptsEvenIfTheIntegratedSpeedDriftedHigh() {
        // An estimate that drifted (say a missed brake) must still be corrected when the sensors
        // read a genuinely stationary tablet -- the stillness floor is the escape hatch.
        val estimator = InertialSpeedEstimator()
        estimator.seed(speedKmh = 60.0, headingDegOrNull = 0.0)

        var t = 0L
        var last = estimator.step(sample(t, forwardAccel = 0.01f), goodCalibration)
        repeat(20) {
            t += 100_000_000L
            last = estimator.step(sample(t, forwardAccel = 0.01f), goodCalibration)
        }

        assertEquals(0.0, last.speedKmh, 0.001)
        assertTrue(last.zuptCount > 0)
    }
}
