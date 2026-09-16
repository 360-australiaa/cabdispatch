package au.com.threesixty.cabdispatch.domain.location.inertial

import kotlin.math.sqrt

/**
 * Task 4 of W2 (`docs/plans/2026-09-12-android-meter-optimisation-and-gps-blackout-plan.md`):
 * integrates [ImuSample]s, projected through a [VehicleFrameCalibration], into one scalar forward
 * speed — the ONLY thing this class ever produces. No position, no free-space integration; see
 * `ImuTypes.kt`'s package doc for why that boundary is load-bearing, not stylistic.
 *
 * Deliberately a plain, dependency-free class (no `SensorManager`, no coroutines, no `SpeedSource`)
 * so every rule below — ZUPT, bounds, error budget, seeding — is exercised in a JVM unit test
 * against a synthetic or recorded sample sequence, the same reasoning
 * [au.com.threesixty.cabdispatch.domain.fare.FareEngine] (the pure money engine) is kept separate
 * from [au.com.threesixty.cabdispatch.domain.FareEngineImpl] (the stateful, sensor-adjacent shell).
 *
 * **Not thread-safe.** [step] mutates internal state and must only ever be called from one thread
 * — [InertialSpeedSource] owns exactly one instance and drives it from the same `HandlerThread`
 * [ImuSampler] publishes on.
 *
 * ### Shadow mode vs. billing mode
 * This class has no notion of "GPS is lost" — it always integrates whatever samples it is fed, all
 * the time an instance exists. [InertialSpeedSource] is what decides whether a given tick's output
 * is (a) shadow-mode telemetry (task 4's "the estimator always runs during a hiring, re-seeded from
 * each accepted fix" — logged for residual evidence, never billed) or (b) the live estimate used to
 * bill through a blackout: it [seed]s this estimator from the last accepted GPS fix continuously
 * while GPS is live, and stops reseeding — letting the integral run free — only once a blackout
 * starts. Both modes are the exact same [step] call; the difference lives entirely in when the
 * caller chooses to call [seed] again.
 */
class InertialSpeedEstimator {

    /** Current speed estimate, m/s. Always `>= 0` — task 4's bounds rule; a vehicle backing up
     * during a blackout is under-billed (never over-billed) rather than risk a signed heading error
     * doubling a distance. */
    private var vEstMps: Double = 0.0

    /** Current heading estimate, degrees, or `null` before the first [seed]. */
    private var headingDeg: Double? = null

    /** Task 4's error budget, m/s. Grows between ZUPTs, reset to zero by one. */
    private var sigmaV: Double = 0.0

    /** How many consecutive seconds [sigmaV] has been in the `LOW`-confidence band — the input to
     * the 120 s [InertialEstimate.unreliable] rule. */
    private var lowConfidenceSeconds: Double = 0.0

    /** Latches once [lowConfidenceSeconds] crosses 120 s; only [seed] clears it — task 4: "the
     * estimator declares UNRELIABLE and the engine falls back ... for the rest of that blackout". */
    private var latchedUnreliable: Boolean = false

    /** How many ZUPTs have fired since the last [seed] — [InertialEstimate.zuptCount]. */
    private var zuptCount: Int = 0

    /** Rolling 1.5 s window of `|linear acceleration|` samples, for the ZUPT variance test. Each
     * entry is (timestampNanos, magnitude). */
    private val accelMagnitudeWindow = ArrayDeque<Pair<Long, Double>>()

    /** Rolling 1.5 s window of `|gyro|` samples, same purpose. */
    private val gyroMagnitudeWindow = ArrayDeque<Pair<Long, Double>>()

    private var lastSampleNanos: Long? = null

    /** The most recent [seed] call's speed, independent of [vEstMps]'s own later drift — task 4's
     * bound (`vGpsAtLoss + 30 km/h`) is re-derived from this on every [step], so a blackout entered
     * at low speed can never "estimate" a highway sprint just because the running estimate itself
     * drifted upward first. */
    private var seedSpeedMps: Double = 0.0

    /**
     * Task 4's "Seed at blackout start" — also the shadow-mode reseed while GPS is live. Resets
     * every piece of accumulated drift state: a fresh seed means the estimator's error budget
     * starts again from a position it actually trusts (a live GPS fix), not from wherever a stale
     * integral happened to leave it.
     */
    fun seed(speedKmh: Double, headingDegOrNull: Double?) {
        vEstMps = (speedKmh / KMH_PER_MPS).coerceAtLeast(0.0)
        seedSpeedMps = vEstMps
        headingDeg = headingDegOrNull
        sigmaV = 0.0
        lowConfidenceSeconds = 0.0
        latchedUnreliable = false
        zuptCount = 0
        accelMagnitudeWindow.clear()
        gyroMagnitudeWindow.clear()
        lastSampleNanos = null
    }

    /**
     * Advances the estimate by one [ImuSample], projected through [calibration]. Returns the
     * estimate as it stands AFTER this sample — [InertialSpeedSource] calls this at the ~10 Hz
     * [ImuSampler] publishes at, both in shadow mode and during a real blackout.
     */
    fun step(sample: ImuSample, calibration: VehicleFrameCalibration?): InertialEstimate {
        val previousNanos = lastSampleNanos
        lastSampleNanos = sample.timestampNanos
        val dt = if (previousNanos == null) {
            0.0
        } else {
            ((sample.timestampNanos - previousNanos) / NANOS_PER_SECOND).coerceIn(0.0, MAX_STEP_SECONDS)
        }

        val usableAxis = calibration != null &&
            (calibration.quality == CalibrationQuality.GOOD || calibration.quality == CalibrationQuality.SEEDED)
        if (calibration == null || !usableAxis) {
            // No usable forward axis at all: publish the last speed we had (frozen, like the W1
            // rule this falls back to) rather than integrating raw tablet-frame acceleration
            // against no known forward direction, which would be noise, not a speed. SEEDED
            // (2026-09-14, [HeadingSeed]) counts as usable: a physically-derived axis, just not
            // yet a statistically confirmed one -- see CalibrationQuality.SEEDED's own doc.
            return snapshot(calibrationGood = false)
        }

        val forward = calibration.forwardTablet
        val aForwardRaw = dot(sample.linearAccelerationMps2, forward) - calibration.forwardAccelBiasMps2
        val gyroZ = verticalComponent(sample.gyroscopeRadPerS, sample.gravityMps2) - calibration.gyroZBiasRadPerS

        val accelMagnitude = magnitude(sample.linearAccelerationMps2)
        val gyroMagnitude = magnitude(sample.gyroscopeRadPerS)
        pushWindowed(accelMagnitudeWindow, sample.timestampNanos, accelMagnitude)
        pushWindowed(gyroMagnitudeWindow, sample.timestampNanos, gyroMagnitude)

        // Task 4's rule, read literally ("variance of |linear accel| over 1.5s < 0.05 m/s^2"), would
        // also fire on a perfectly STEADY highway cruise -- constant nonzero acceleration has zero
        // variance too. Added, not in the plan's literal wording but necessary for the rule to mean
        // what it is FOR: the magnitude itself must also be small (this sample, not just its
        // variance) -- a genuine stop has both a near-zero reading AND a steady one; steady cruise
        // has only the second.
        val isZupt = isZeroVelocity(accelMagnitude)

        if (isZupt) {
            vEstMps = 0.0
            sigmaV = 0.0
            zuptCount += 1
            // Task 3/4's "re-estimate biases from the window" happens in
            // VehicleFrameCalibrator.onImuSample, not here — this class has no handle to persist a
            // new VehicleFrameCalibration, and duplicating the re-estimate here would give the same
            // number two independent sources of truth. This branch's own job is only ever the speed.
        } else {
            val aForwardClamped = aForwardRaw.coerceIn(-MAX_FORWARD_ACCEL_MPS2, MAX_FORWARD_ACCEL_MPS2)
            vEstMps = (vEstMps + aForwardClamped * dt).coerceIn(0.0, maxAllowedSpeedMps())
            sigmaV += SIGMA_GROWTH_PER_SECOND * dt
            headingDeg = ((headingDeg ?: 0.0) + Math.toDegrees(gyroZ * dt)).mod(FULL_TURN_DEG)
        }

        val confidenceNow = confidenceFor(sigmaV)
        lowConfidenceSeconds = if (confidenceNow == InertialConfidence.LOW) lowConfidenceSeconds + dt else 0.0
        if (lowConfidenceSeconds > UNRELIABLE_AFTER_SECONDS) latchedUnreliable = true

        return snapshot(calibrationGood = calibration.quality == CalibrationQuality.GOOD)
    }

    /** Task 4's ZUPT (zero-velocity update) decision for the current sample -- see the body's own
     * comments for each clause. Extracted from [step] purely for its complexity budget. */
    private fun isZeroVelocity(accelMagnitude: Double): Boolean {
        val quietWindow = accelMagnitude < ZUPT_ACCEL_MAGNITUDE_THRESHOLD &&
            windowVariance(accelMagnitudeWindow) < ZUPT_ACCEL_VARIANCE_THRESHOLD &&
            (gyroMagnitudeWindow.lastOrNull()?.second ?: 0.0) < ZUPT_GYRO_THRESHOLD_RAD_S &&
            accelMagnitudeWindow.size >= MIN_ZUPT_WINDOW_SAMPLES
        // Bench finding, Karachi tablet, 2026-09-14 (Lane Cove Tunnel route): a quiet window is
        // NOT proof of a stop. Light road vibration (|a| ~0.2-0.3 m/s^2, low variance) at a steady
        // 90 km/h cruise satisfied every clause above, the ZUPT zeroed the speed the moment GPS
        // dropped, and the meter spent the whole tunnel at 0 km/h billing waiting time. A vehicle
        // cannot come to rest from cruising speed without a sustained deceleration this integrator
        // would have seen -- so a quiet window only counts as a stop when the integrated speed is
        // already near zero, OR when the window is at the sensor's own stillness floor (a level a
        // moving car's mount never holds for a full window: engine + road always exceed it).
        //
        // Real field report, T5453, 2026-09-16 (M4 East, a real ~7.5 min blackout): the "already
        // near zero" half of that fix is itself exploitable once vEstMps has drifted there on its
        // own, with no real deceleration involved -- pure double-integration error, the SAME
        // uncorrected drift this class's error budget (sigmaV) exists to flag, working alone for
        // long enough on an ordinary highway cruise to walk the estimate under ZUPT_SPEED_GATE_MPS.
        // The very next quiet window (routine at cruising speed, per the 2026-09-14 finding above)
        // then read that drifted-low value as proof of a stop and hard-reset it to exactly zero --
        // billed distance self-corrected afterwards from the known road geometry (BlackoutReconciler
        // never trusts this estimate blindly), but the LIVE speed/position shown to the driver and
        // the dispatcher dashboard was wrong for the rest of the blackout: decaying toward zero,
        // then pinned at it, while the car kept doing ~90 km/h the whole time.
        //
        // The fix does NOT freeze or give up on the estimate once confidence degrades -- the owner
        // explicitly rejected that trade on 2026-09-14 (see FareEngineImpl's own "DIRECT OWNER
        // DECISION" comment: "never let the meter sit at a flat number through a blackout"). It
        // narrows what ZUPT is allowed to TRUST: "the integrated speed already reads low" is only
        // real evidence of a stop while sigmaV itself is still trustworthy (confidence above LOW) --
        // once confidence has already degraded, a low vEstMps could just as easily be the drift, not
        // the truth, so a quiet window must not be allowed to treat it as confirmation. The
        // stillness-floor escape hatch is untouched: a fully stationary tablet's own sensor reading
        // is real, independent physical evidence regardless of how much the estimate has drifted,
        // and keeps correcting a genuine missed stop exactly as before.
        val driftedLowReadingIsTrustworthy =
            vEstMps < ZUPT_SPEED_GATE_MPS && confidenceFor(sigmaV) != InertialConfidence.LOW
        return quietWindow &&
            (driftedLowReadingIsTrustworthy || windowMax(accelMagnitudeWindow) < ZUPT_STILLNESS_FLOOR_MPS2)
    }

    /** The highest speed this estimator will ever report — task 4's bound: the GPS speed at the
     * moment signal was lost, plus a margin, capped at a hard ceiling no NSW road legally exceeds
     * by enough to matter for a taxi fare. */
    private fun maxAllowedSpeedMps(): Double =
        minOf(seedSpeedMps + BOUND_MARGIN_KMH / KMH_PER_MPS, HARD_CEILING_KMH / KMH_PER_MPS)

    private fun confidenceFor(sigma: Double): InertialConfidence = when {
        sigma < HIGH_CONFIDENCE_SIGMA -> InertialConfidence.HIGH
        sigma < MEDIUM_CONFIDENCE_SIGMA -> InertialConfidence.MEDIUM
        else -> InertialConfidence.LOW
    }

    private fun snapshot(calibrationGood: Boolean): InertialEstimate = InertialEstimate(
        speedKmh = vEstMps * KMH_PER_MPS,
        headingDegOrNull = headingDeg,
        sigmaVMetresPerSecond = sigmaV,
        confidence = confidenceFor(sigmaV),
        zuptCount = zuptCount,
        calibrationGood = calibrationGood,
        unreliable = latchedUnreliable,
    )

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MAX_STEP_SECONDS = 1.0 // a stalled sensor thread must not integrate a huge dt
        private const val KMH_PER_MPS = 3.6
        private const val FULL_TURN_DEG = 360.0

        /** Task 4 ZUPT rule: `variance(|linear accel|) over 1.5s < 0.05 m/s^2 AND |gyro| < 0.02 rad/s`. */
        private const val ZUPT_ACCEL_VARIANCE_THRESHOLD = 0.05
        private const val ZUPT_GYRO_THRESHOLD_RAD_S = 0.02

        /** Not in the plan's literal wording -- see the `isZupt` computation's own comment for why
         * the variance test alone is insufficient. Sized generously above ordinary sensor noise
         * floor (a stationary tablet's `TYPE_LINEAR_ACCELERATION` typically reads well under
         * 0.1 m/s^2) but well below [MAX_FORWARD_ACCEL_MPS2], so a real, deliberate acceleration or
         * brake is never mistaken for a stop. */
        private const val ZUPT_ACCEL_MAGNITUDE_THRESHOLD = 0.3

        /** At ~10 Hz publish rate, 1.5s is ~15 samples; require most of a full window before trusting
         * the variance figure at all, so the very first couple of samples after a [seed] cannot
         * spuriously fire a ZUPT off a near-empty window. */
        private const val MIN_ZUPT_WINDOW_SAMPLES = 10

        /** See the `isZupt` computation: the ordinary quiet-window ZUPT is trusted only once the
         * integrated speed is already this low (~14 km/h -- a real stop's final roll-out, with room
         * for the drift a few seconds of free-run accumulates). */
        private const val ZUPT_SPEED_GATE_MPS = 4.0

        /** ...or when the whole 1.5 s window sits at the sensor's stillness floor. A stationary
         * tablet's TYPE_LINEAR_ACCELERATION reads well under 0.1 m/s^2; a moving vehicle's mount
         * does not hold under this for a full window. Sized above the floor, below light road
         * vibration. */
        private const val ZUPT_STILLNESS_FLOOR_MPS2 = 0.12

        /** Task 4 bounds: `|aForward| <= 4 m/s^2` — anything larger is mount vibration, clamped. */
        private const val MAX_FORWARD_ACCEL_MPS2 = 4.0

        /** Task 4 bounds margin/ceiling: `0 <= vEst <= min(vGpsAtLoss + 30 km/h, 110 km/h)`. */
        private const val BOUND_MARGIN_KMH = 30.0
        private const val HARD_CEILING_KMH = 110.0

        /** Task 4 error budget: `sigmaV += 0.05 m/s^2 * dt` between ZUPTs (the constant is stated in
         * the plan as an acceleration-like units figure — applied directly to the per-second growth
         * of the velocity-error budget, consistent with how the plan's own confidence thresholds
         * (`< 1 m/s`, `< 3 m/s`) are expressed in m/s, not m/s^2). */
        private const val SIGMA_GROWTH_PER_SECOND = 0.05

        private const val HIGH_CONFIDENCE_SIGMA = 1.0
        private const val MEDIUM_CONFIDENCE_SIGMA = 3.0

        /** Task 4: "LOW for more than 120 s => UNRELIABLE". */
        private const val UNRELIABLE_AFTER_SECONDS = 120.0
    }
}

/** [dot], [magnitude], [verticalComponent], [pushWindowed], [windowVariance] are free functions
 * (not private members of [InertialSpeedEstimator]) purely so they can each carry one focused unit
 * test without instantiating the whole estimator — small, pure numeric helpers, the same reasoning
 * [au.com.threesixty.cabdispatch.domain.fare.KnownCorridor]'s `greedyChainDistanceM` is `internal`
 * and free-standing rather than a private method of a larger class. */
internal fun dot(a: FloatArray, b: DoubleArray): Double =
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

internal fun magnitude(v: FloatArray): Double =
    sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())

/**
 * The gyro's rotation about the vehicle's own vertical (yaw) axis — approximated here as the
 * gyro vector's component along the (upward) gravity direction, since a taxi-mounted tablet's own
 * vertical axis and the vehicle's are the same axis whenever the tablet sits flat in its mount
 * (the calibrator's own invalidation rule, task 3, is exactly what guarantees this holds: a mount
 * that has been disturbed enough to break this approximation is the same disturbance that
 * invalidates calibration).
 */
internal fun verticalComponent(gyro: FloatArray, gravity: FloatArray): Double {
    val gMag = magnitude(gravity)
    if (gMag < MIN_GRAVITY_MAGNITUDE) return 0.0
    val upX = -gravity[0] / gMag
    val upY = -gravity[1] / gMag
    val upZ = -gravity[2] / gMag
    return gyro[0] * upX + gyro[1] * upY + gyro[2] * upZ
}

private const val MIN_GRAVITY_MAGNITUDE = 0.1

/** Task 4's ZUPT window length -- "variance of |linear accel| OVER 1.5s". File-scope (not a
 * companion member of [InertialSpeedEstimator]) so the free function [pushWindowed] below, which
 * is what actually enforces it, can see it without a class instance. */
private const val ZUPT_WINDOW_SECONDS = 1.5
private const val NANOS_PER_SECOND_LONG = 1_000_000_000L

internal fun pushWindowed(window: ArrayDeque<Pair<Long, Double>>, timestampNanos: Long, value: Double) {
    window.addLast(timestampNanos to value)
    val cutoff = timestampNanos - (ZUPT_WINDOW_SECONDS * NANOS_PER_SECOND_LONG).toLong()
    while (window.isNotEmpty() && window.first().first < cutoff) window.removeFirst()
}

internal fun windowMax(window: ArrayDeque<Pair<Long, Double>>): Double =
    window.maxOfOrNull { it.second } ?: Double.MAX_VALUE

internal fun windowVariance(window: ArrayDeque<Pair<Long, Double>>): Double {
    if (window.size < 2) return Double.MAX_VALUE // an under-full window must never look like a ZUPT
    val values = window.map { it.second }
    val mean = values.sum() / values.size
    return values.sumOf { (it - mean) * (it - mean) } / values.size
}
