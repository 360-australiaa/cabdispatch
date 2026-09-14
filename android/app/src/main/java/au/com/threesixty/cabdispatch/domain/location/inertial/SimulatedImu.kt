package au.com.threesixty.cabdispatch.domain.location.inertial

import au.com.threesixty.cabdispatch.domain.location.GpsSimulator
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthesises accelerometer/gyroscope samples consistent with whatever route the GPS simulator is
 * driving -- INCLUDING through its blackout windows, where the simulator withholds fixes but still
 * knows the true motion ([GpsSimulator.kinematics]). This is the bench-test half of the inertial
 * dead-reckoning program: a tablet on a desk in Karachi can then exercise the identical chain a
 * real Sydney tunnel drives -- heading-seeded forward axis ([HeadingSeed]), free-run integration
 * ([InertialSpeedEstimator]), dead-reckoned position ([InertialSpeedSource]), `estimated` position
 * publish, corridor/dead-reckoned toll sweep -- with a speed that actually changes underground.
 *
 * How it stays honest:
 * - Returns `null` (real sensors) whenever the simulator is not active. The simulator already
 *   stamps every trip it drives as `simulated`, so no real fare can ever be billed off this.
 * - Gravity and the rotation vector are the REAL ones from the tablet -- the synthetic forward
 *   acceleration is laid along the SAME tablet-frame axis [HeadingSeed] derives for the route's
 *   bearing, so the estimator's seeded axis and the fabricated acceleration agree by construction,
 *   exactly as a correctly-seeded real drive would.
 * - Cruising at constant speed in a real car still carries ~0.4-1.4 m/s^2 of road vibration, which
 *   is precisely what keeps [InertialSpeedEstimator]'s ZUPT (zero-velocity update) from firing
 *   while moving. That vibration is reproduced as noise on the tablet's vertical axis only --
 *   orthogonal to the forward axis, so it never leaks into the integrated speed -- and dropped to
 *   near-zero when the route is stopped, so ZUPT fires exactly when a real stop would fire it.
 * - Yaw rate is laid along the gravity axis with the sign convention `verticalComponent` in
 *   InertialSpeedEstimator.kt reads (positive = heading increasing), so the integrated heading --
 *   and therefore the dead-reckoned path -- follows the route's real bearing changes.
 */
class SimulatedImu(private val simulator: GpsSimulator) : SyntheticImuSource {

    private val rng = Random(RNG_SEED)

    // ReturnCount: four "use the real sensors" guards, guard-clause style like the rest of this package.
    @Suppress("ReturnCount")
    override fun synthesize(nowNanos: Long, gravityMps2: FloatArray, rotationVector: FloatArray): SyntheticImuSample? {
        if (!simulator.active.value) return null
        val k = simulator.kinematics.value ?: return null
        val forward = HeadingSeed.forwardAxisInTabletFrame(rotationVector, k.bearingDeg) ?: return null

        val gMag = sqrt(
            gravityMps2[0].toDouble() * gravityMps2[0] +
                gravityMps2[1].toDouble() * gravityMps2[1] +
                gravityMps2[2].toDouble() * gravityMps2[2],
        )
        if (gMag < MIN_GRAVITY) return null
        // "Up" exactly as InertialSpeedEstimator's verticalComponent defines it: -gravity/|gravity|.
        val up = doubleArrayOf(-gravityMps2[0] / gMag, -gravityMps2[1] / gMag, -gravityMps2[2] / gMag)

        val moving = k.speedKmh > STOPPED_BELOW_KMH
        // Moving: a vertical jolt of random sign whose magnitude sits in the ROAD_VIBRATION band on
        // every sample -- never near zero, the way a live mount never is. (A symmetric
        // uniform(-x, x) draw, the first cut of this, put half of all samples under the ZUPT
        // magnitude threshold with a window variance of only x^2/12: 2026-09-14 bench finding.)
        val vibration = if (moving) {
            val jolt = rng.nextDouble(ROAD_VIBRATION_MIN_MPS2, ROAD_VIBRATION_MAX_MPS2)
            if (rng.nextBoolean()) jolt else -jolt
        } else {
            rng.nextDouble(-STOPPED_NOISE_MPS2, STOPPED_NOISE_MPS2)
        }
        val a = k.forwardAccelMps2
        val accel = floatArrayOf(
            (forward[0] * a + up[0] * vibration).toFloat(),
            (forward[1] * a + up[1] * vibration).toFloat(),
            (forward[2] * a + up[2] * vibration).toFloat(),
        )
        val w = k.yawRateRadPerS
        val gyro = floatArrayOf((up[0] * w).toFloat(), (up[1] * w).toFloat(), (up[2] * w).toFloat())
        return SyntheticImuSample(linearAccelerationMps2 = accel, gyroscopeRadPerS = gyro)
    }

    private companion object {
        const val RNG_SEED = 7
        const val MIN_GRAVITY = 0.1
        const val STOPPED_BELOW_KMH = 0.5
        /** Vertical road-vibration magnitude band while moving: what a dash-mounted tablet's
         * TYPE_LINEAR_ACCELERATION carries at suburban/motorway speed (engine + road, well below a
         * real bump). Its floor is above the ZUPT stillness floor, and the band's variance is above
         * the ZUPT variance threshold, so a moving vehicle is never read as stopped. */
        const val ROAD_VIBRATION_MIN_MPS2 = 0.4
        const val ROAD_VIBRATION_MAX_MPS2 = 1.4
        /** Residual sensor noise at a standstill -- under the ZUPT magnitude threshold. */
        const val STOPPED_NOISE_MPS2 = 0.02
    }
}
