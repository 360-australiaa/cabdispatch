package au.com.threesixty.cabdispatch.domain.location.inertial

import android.content.Context
import androidx.core.content.edit
import au.com.threesixty.cabdispatch.domain.SecurePrefs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Task 3 of W2: learns the vehicle's forward direction in the tablet's own reference frame, purely
 * from sensor evidence gathered while GPS is live — no manual "mount the tablet facing forward"
 * step for the driver, since a real dash mount is never perfectly square to the vehicle's own
 * axis, and this needs to be right to within a few degrees for [InertialSpeedEstimator] to bill a
 * meaningful speed.
 *
 * ### The geometry
 * [android.hardware.Sensor.TYPE_GRAVITY] gives "down" in tablet frame at every sample, so the
 * horizontal plane (everything orthogonal to gravity) is known immediately, with no learning
 * required. What is NOT known without learning is which direction *within* that horizontal plane
 * is "forward" — that is what a confirming event teaches: during a GPS-observed speed change
 * (`|dv/dt| > 0.5 m/s^2` sustained for >= 1s — a real, deliberate acceleration or braking, not
 * cornering or road noise), the horizontal component of `TYPE_LINEAR_ACCELERATION` points along
 * the vehicle's own forward/backward axis, and its SIGN (relative to whether GPS speed rose or
 * fell) resolves forward from backward. Averaging several such events, weighted equally, and
 * requiring their individual directions to agree within a 15 degree spread before trusting the
 * average, is task 3's calibration bar.
 *
 * ### Persistence and invalidation
 * The learned frame survives a process restart (`SecurePrefs`-backed, per device — same store
 * class the app's credential stores use, chosen for the encrypted-at-rest property, not because
 * this is secret; there was no unencrypted store worth adding just for one more file). It is
 * invalidated — thrown away, back to [CalibrationQuality.NONE] — the moment
 * [au.com.threesixty.cabdispatch.domain.location.inertial.ImuSampler] reports the rotation vector
 * has moved more than 10 degrees relative to gravity for more than 5 seconds: the tablet has
 * plausibly been taken out of its mount and put back at a different angle, and an old forward axis
 * from before that is now actively wrong, worse than having none.
 *
 * Not thread-safe by itself; [InertialSpeedSource] serialises all calls onto one `HandlerThread`,
 * same as [InertialSpeedEstimator].
 */
class VehicleFrameCalibrator(private val context: Context, private val deviceKey: String = "default") {

    private val _calibration = MutableStateFlow<VehicleFrameCalibration?>(loadPersisted())
    val calibration: StateFlow<VehicleFrameCalibration?> = _calibration.asStateFlow()

    // --- confirming-event accumulation state ---------------------------------------------------
    private data class PendingWindow(val startNanos: Long, val startSpeedKmh: Double, val gravity: FloatArray)
    private var pendingWindow: PendingWindow? = null
    private val confirmingDirections = mutableListOf<DoubleArray>() // unit vectors, tablet frame

    // --- stationary-window bias accumulation state ---------------------------------------------
    private val stationaryAccelSamples = mutableListOf<Double>() // forward-axis accel, raw
    private val stationaryGyroZSamples = mutableListOf<Double>() // vertical-axis gyro, raw

    // --- mount-disturbance tracking ---------------------------------------------------------------
    private var disturbanceStartNanos: Long? = null

    /** Fed by whatever observes the real, accepted GPS speed while a hiring is open — see
     * [InertialSpeedSource]'s wiring doc for why this is a push, not a pull: the calibrator must
     * see every accepted fix's speed, not just whatever happened to be current when an IMU sample
     * arrived. */
    private var lastGpsSpeedKmh: Double? = null
    private var lastGpsSpeedAtNanos: Long? = null

    fun onGpsSpeedSample(speedKmh: Double, nowNanos: Long) {
        val prevSpeed = lastGpsSpeedKmh
        val prevAt = lastGpsSpeedAtNanos
        lastGpsSpeedKmh = speedKmh
        lastGpsSpeedAtNanos = nowNanos
        if (prevSpeed == null || prevAt == null) return
        val dtSeconds = (nowNanos - prevAt) / NANOS_PER_SECOND
        if (dtSeconds <= 0.0) return
        val dvDt = ((speedKmh - prevSpeed) / KMH_PER_MPS) / dtSeconds
        if (kotlin.math.abs(dvDt) <= SPEED_CHANGE_THRESHOLD_MPS2) {
            pendingWindow = null // the change dropped below the bar — the window did not sustain
        }
        // Sustained-start detection and closing happen in onImuSample, which has the actual
        // per-sample acceleration vectors this needs to average over the window.
        pendingSpeedChange = dvDt
    }

    private var pendingSpeedChange: Double = 0.0

    /**
     * One [ImuSample] arriving from [ImuSampler] — accumulates confirming-event evidence, the
     * stationary bias windows, and the mount-disturbance watchdog. Never itself computes a speed
     * (that is [InertialSpeedEstimator.step]'s job) — this class only ever updates
     * [calibration].
     */
    fun onImuSample(sample: ImuSample) {
        trackConfirmingWindow(sample)
        trackMountDisturbance(sample)
    }

    // ReturnCount: guard-clause style, same accepted pattern this codebase already uses
    // (GpsQualityClassifier.classify, FareEngineImpl.resolveBlackout) over a nested-if pyramid.
    @Suppress("ReturnCount")
    private fun trackConfirmingWindow(sample: ImuSample) {
        val sustained = kotlin.math.abs(pendingSpeedChange) > SPEED_CHANGE_THRESHOLD_MPS2
        val window = pendingWindow
        if (sustained && window == null) {
            pendingWindow = PendingWindow(sample.timestampNanos, lastGpsSpeedKmh ?: 0.0, sample.gravityMps2)
            return
        }
        if (!sustained) {
            pendingWindow = null
            return
        }
        val started = window ?: return
        val elapsedSeconds = (sample.timestampNanos - started.startNanos) / NANOS_PER_SECOND
        if (elapsedSeconds < SUSTAINED_SECONDS) return // not sustained long enough yet

        val horizontal = projectOntoHorizontalPlane(sample.linearAccelerationMps2, started.gravity)
        val mag = sqrt(horizontal[0] * horizontal[0] + horizontal[1] * horizontal[1] + horizontal[2] * horizontal[2])
        pendingWindow = null // one confirming event consumed per sustained window
        if (mag < MIN_HORIZONTAL_ACCEL_MPS2) return // too weak a signal to trust the direction from

        val sign = if (pendingSpeedChange >= 0) 1.0 else -1.0 // GPS speed rising ⇒ this IS forward
        val unit = doubleArrayOf(sign * horizontal[0] / mag, sign * horizontal[1] / mag, sign * horizontal[2] / mag)
        confirmingDirections += unit
        recomputeCalibration()
    }

    // ReturnCount: same guard-clause reasoning as [trackConfirmingWindow] above.
    @Suppress("ReturnCount")
    private fun recomputeCalibration(): VehicleFrameCalibration? {
        if (confirmingDirections.size < MIN_CALIBRATION_EVENTS) {
            val partial = VehicleFrameCalibration(
                forwardTablet = averageDirection(confirmingDirections) ?: doubleArrayOf(0.0, 0.0, 0.0),
                gyroZBiasRadPerS = stationaryGyroZSamples.average0(),
                forwardAccelBiasMps2 = stationaryAccelSamples.average0(),
                quality = CalibrationQuality.NONE,
                confirmingEventCount = confirmingDirections.size,
            )
            _calibration.value = partial
            return partial
        }
        val mean = averageDirection(confirmingDirections) ?: return _calibration.value
        val spreadDeg = confirmingDirections.maxOf { angleBetweenDeg(it, mean) }
        val quality = if (spreadDeg < MAX_ANGULAR_SPREAD_DEG) CalibrationQuality.GOOD else CalibrationQuality.LEARNING
        val result = VehicleFrameCalibration(
            forwardTablet = mean,
            gyroZBiasRadPerS = stationaryGyroZSamples.average0(),
            forwardAccelBiasMps2 = stationaryAccelSamples.average0(),
            quality = quality,
            confirmingEventCount = confirmingDirections.size,
        )
        _calibration.value = result
        if (quality == CalibrationQuality.GOOD) persist(result)
        return result
    }

    // ReturnCount: same guard-clause reasoning as [trackConfirmingWindow] above.
    @Suppress("ReturnCount")
    private fun trackMountDisturbance(sample: ImuSample) {
        val current = _calibration.value ?: return
        if (current.quality != CalibrationQuality.GOOD) return
        val gravityUnit = unit(sample.gravityMps2.toDoubleArray()) ?: return
        val referenceUnit = referenceGravityUnit ?: run { referenceGravityUnit = gravityUnit; return }
        val angleDeg = angleBetweenDeg(gravityUnit, referenceUnit)
        if (angleDeg > MOUNT_DISTURBANCE_DEG) {
            val startedAt = disturbanceStartNanos ?: run {
                disturbanceStartNanos = sample.timestampNanos
                sample.timestampNanos
            }
            val elapsed = (sample.timestampNanos - startedAt) / NANOS_PER_SECOND
            if (elapsed > MOUNT_DISTURBANCE_SECONDS) invalidate()
        } else {
            disturbanceStartNanos = null
            referenceGravityUnit = gravityUnit
        }
    }

    private var referenceGravityUnit: DoubleArray? = null

    /** Called on a stationary window (task 3/4: bias re-estimation piggybacks on the same
     * ZUPT-style stillness [InertialSpeedEstimator] detects independently — this method takes the
     * raw forward-axis accel / vertical gyro readings directly rather than re-deriving stillness
     * itself, so the two classes' notions of "stationary" cannot silently diverge). */
    fun onStationaryWindow(forwardAccelMps2: Double, verticalGyroRadPerS: Double) {
        stationaryAccelSamples.add(forwardAccelMps2)
        stationaryGyroZSamples.add(verticalGyroRadPerS)
        while (stationaryAccelSamples.size > MAX_BIAS_SAMPLES) stationaryAccelSamples.removeAt(0)
        while (stationaryGyroZSamples.size > MAX_BIAS_SAMPLES) stationaryGyroZSamples.removeAt(0)
        recomputeCalibration()
    }

    /** Discards the learned frame entirely — task 3's mount-disturbance rule, and available to
     * diagnostics for a manual "recalibrate" action. */
    fun invalidate() {
        confirmingDirections.clear()
        stationaryAccelSamples.clear()
        stationaryGyroZSamples.clear()
        pendingWindow = null
        disturbanceStartNanos = null
        referenceGravityUnit = null
        _calibration.value = null
        // Best-effort, matching [loadPersisted]'s own reasoning and this codebase's established
        // pattern for non-billing persistence (e.g. `MeterController.persistBlackout`): a Keystore
        // failure here must never propagate up through `onImuSample`'s call chain and take the
        // estimator down mid-hiring over a diagnostics-adjacent write. Production risk is genuinely
        // low ([SecurePrefs.open] already recovers a corrupted/reset Keystore on its own), but a
        // provider that is simply ABSENT (Robolectric's unit-test JVM; conceivably a locked-down
        // OEM build) is a real, if rare, failure this must survive too.
        runCatching { SecurePrefs.open(context, PREFS_NAME).edit { remove(keyFor(deviceKey)) } }
    }

    private fun persist(calibration: VehicleFrameCalibration) {
        val encoded = listOf(
            calibration.forwardTablet[0], calibration.forwardTablet[1], calibration.forwardTablet[2],
            calibration.gyroZBiasRadPerS, calibration.forwardAccelBiasMps2,
            calibration.confirmingEventCount.toDouble(),
        ).joinToString(",")
        // See [invalidate]'s own comment -- same best-effort reasoning applies here.
        runCatching { SecurePrefs.open(context, PREFS_NAME).edit { putString(keyFor(deviceKey), encoded) } }
    }

    // ReturnCount: same guard-clause reasoning as [trackConfirmingWindow] above.
    @Suppress("ReturnCount")
    private fun loadPersisted(): VehicleFrameCalibration? {
        val raw = runCatching { SecurePrefs.open(context, PREFS_NAME).getString(keyFor(deviceKey), null) }
            .getOrNull() ?: return null
        val parts = raw.split(",").mapNotNull { it.toDoubleOrNull() }
        if (parts.size != ENCODED_FIELD_COUNT) return null
        return VehicleFrameCalibration(
            forwardTablet = doubleArrayOf(parts[0], parts[1], parts[2]),
            gyroZBiasRadPerS = parts[3],
            forwardAccelBiasMps2 = parts[4],
            quality = CalibrationQuality.GOOD, // only a GOOD calibration is ever persisted — see [persist]
            confirmingEventCount = parts[5].toInt(),
        )
    }

    private fun keyFor(device: String) = "vehicle_frame_$device"

    companion object {
        private const val PREFS_NAME = "inertial_calibration"
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val KMH_PER_MPS = 3.6

        /** [persist]/[loadPersisted]'s encoded field count -- forwardTablet's 3 components, the two
         * bias figures, and the confirming-event count. */
        private const val ENCODED_FIELD_COUNT = 6

        /** Task 3: "during GPS-observed speed changes (|dv/dt| > 0.5 m/s^2 sustained >= 1s)". */
        const val SPEED_CHANGE_THRESHOLD_MPS2 = 0.5
        const val SUSTAINED_SECONDS = 1.0

        /** Task 3: "Requires >= 8 such events with angular spread < 15deg for quality = GOOD". */
        const val MIN_CALIBRATION_EVENTS = 8
        const val MAX_ANGULAR_SPREAD_DEG = 15.0

        /** Task 3: "invalidates ... if the rotation vector shows the tablet's orientation relative
         * to gravity has changed by > 10 deg for > 5s". */
        const val MOUNT_DISTURBANCE_DEG = 10.0
        const val MOUNT_DISTURBANCE_SECONDS = 5.0

        private const val MIN_HORIZONTAL_ACCEL_MPS2 = 0.2
        private const val MAX_BIAS_SAMPLES = 200
    }
}

private const val MIN_VECTOR_MAGNITUDE = 1e-6

private fun List<Double>.average0(): Double = if (isEmpty()) 0.0 else average()

private fun FloatArray.toDoubleArray(): DoubleArray =
    doubleArrayOf(this[0].toDouble(), this[1].toDouble(), this[2].toDouble())

private fun unit(v: DoubleArray): DoubleArray? {
    val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    if (mag < MIN_VECTOR_MAGNITUDE) return null
    return doubleArrayOf(v[0] / mag, v[1] / mag, v[2] / mag)
}

/**
 * Projects [accel] (tablet frame) onto the plane orthogonal to [gravity] (tablet frame, same
 * units) — the "horizontal plane" task 3's confirming-event direction is measured within, without
 * requiring the tablet be mounted dead level.
 */
internal fun projectOntoHorizontalPlane(accel: FloatArray, gravity: FloatArray): DoubleArray {
    val g = unit(gravity.toDoubleArray())
        ?: return doubleArrayOf(accel[0].toDouble(), accel[1].toDouble(), accel[2].toDouble())
    val a = doubleArrayOf(accel[0].toDouble(), accel[1].toDouble(), accel[2].toDouble())
    val along = a[0] * g[0] + a[1] * g[1] + a[2] * g[2]
    return doubleArrayOf(a[0] - along * g[0], a[1] - along * g[1], a[2] - along * g[2])
}

internal fun averageDirection(vectors: List<DoubleArray>): DoubleArray? {
    if (vectors.isEmpty()) return null
    var x = 0.0; var y = 0.0; var z = 0.0
    for (v in vectors) { x += v[0]; y += v[1]; z += v[2] }
    val n = vectors.size
    return unit(doubleArrayOf(x / n, y / n, z / n)) ?: doubleArrayOf(0.0, 0.0, 0.0)
}

// ReturnCount: two guard clauses (degenerate zero-length input vectors) plus the one real result.
@Suppress("ReturnCount")
internal fun angleBetweenDeg(a: DoubleArray, b: DoubleArray): Double {
    val ua = unit(a) ?: return 0.0
    val ub = unit(b) ?: return 0.0
    val cos = (ua[0] * ub[0] + ua[1] * ub[1] + ua[2] * ub[2]).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cos))
}
