package au.com.threesixty.cabdispatch.domain.location.inertial

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A zero-event vehicle-frame seed from the tablet's own absolute orientation plus the last real
 * GPS bearing — the answer to the first real Sydney tunnel drive (T5453, 2026-09-14), where the
 * speed sat locked at the 48 km/h GPS last reported for the whole tunnel because
 * [VehicleFrameCalibrator] had not yet seen its confirming events and
 * [InertialSpeedEstimator.step] therefore (correctly) refused to integrate raw tablet-frame
 * acceleration against an unknown forward axis.
 *
 * The forward axis is not actually unknown on a tablet with a rotation-vector sensor. Android's
 * `TYPE_ROTATION_VECTOR` is the platform's own accelerometer+gyro+magnetometer fusion giving the
 * tablet's orientation in the world frame (X east, Y north, Z up), and the last GPS fix before
 * the sky closed over carries a real bearing. "Which tablet-frame direction is the vehicle's
 * forward" is then one rotation: the world-frame forward vector `(sin h, cos h, 0)` for heading
 * `h` (degrees clockwise from north), rotated INTO the tablet frame by the transpose of the
 * device->world rotation matrix. That is a one-shot, physically grounded estimate of exactly the
 * quantity task 3's eight-event calibration learns statistically — usable from the very first
 * minute of the very first drive, with no manual "mount the tablet facing forward" step.
 *
 * It is a SEED, not a replacement: [CalibrationQuality.SEEDED] is deliberately ranked below
 * [CalibrationQuality.GOOD] and is never persisted, so the moment enough real GPS-confirmed
 * accelerate/brake events have been observed the learned axis (which also carries measured
 * bias terms) takes over, exactly as before. What changes is only what happens BEFORE that
 * point: a bounded, live accelerometer-integrated speed instead of a frozen entry speed.
 *
 * Pure functions, no Android dependency — the quaternion->matrix form below is the same one
 * `SensorManager.getRotationMatrixFromVector` implements, written out so a JVM unit test can
 * pin the geometry (see `HeadingSeedTest`).
 */
object HeadingSeed {

    /** Below this, a rotation vector is treated as "sensor absent / not yet reporting". */
    private const val MIN_QUATERNION_NORM = 0.5

    /** `TYPE_ROTATION_VECTOR` reports at least the three vector components... */
    private const val VECTOR_COMPONENTS = 3

    /** ...and, on most devices, the scalar (cos(theta/2)) as a fourth. */
    private const val WITH_SCALAR_COMPONENTS = 4
    private const val SCALAR_INDEX = 3

    /**
     * The vehicle's forward direction as a unit vector in the TABLET's frame, or `null` when
     * [rotationVector] is missing/degenerate (a tablet with no rotation-vector sensor, or one
     * that has not produced a reading yet — never guessed).
     *
     * @param rotationVector `TYPE_ROTATION_VECTOR` values: `[x, y, z]` or `[x, y, z, w]`; the
     *   scalar `w` is reconstructed from the unit-quaternion constraint when absent, exactly as
     *   the platform does.
     * @param headingDeg the vehicle's true bearing, degrees clockwise from north — the last live
     *   GPS `LocationFix.heading` before a blackout.
     */
    // ReturnCount: guard clauses for the three "no honest answer" cases, same accepted style as the
    // rest of this package.
    @Suppress("ReturnCount")
    fun forwardAxisInTabletFrame(rotationVector: FloatArray, headingDeg: Double): DoubleArray? {
        if (rotationVector.size < VECTOR_COMPONENTS) return null
        val x = rotationVector[0].toDouble()
        val y = rotationVector[1].toDouble()
        val z = rotationVector[2].toDouble()
        val w = if (rotationVector.size >= WITH_SCALAR_COMPONENTS) {
            rotationVector[SCALAR_INDEX].toDouble()
        } else {
            val rest = 1.0 - (x * x + y * y + z * z)
            if (rest > 0.0) sqrt(rest) else 0.0
        }
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (norm < MIN_QUATERNION_NORM) return null // all-zero placeholder from ImuSampler, no sensor

        // Device -> world rotation matrix R (rows = world axes east/north/up, columns = device
        // axes), the standard unit-quaternion form; normalised first so a slightly-off-unit
        // sensor reading cannot scale the result.
        val qx = x / norm
        val qy = y / norm
        val qz = z / norm
        val qw = w / norm
        val r00 = 1.0 - 2.0 * (qy * qy + qz * qz)
        val r01 = 2.0 * (qx * qy - qz * qw)
        val r02 = 2.0 * (qx * qz + qy * qw)
        val r10 = 2.0 * (qx * qy + qz * qw)
        val r11 = 1.0 - 2.0 * (qx * qx + qz * qz)
        val r12 = 2.0 * (qy * qz - qx * qw)
        val r20 = 2.0 * (qx * qz - qy * qw)
        val r21 = 2.0 * (qy * qz + qx * qw)
        val r22 = 1.0 - 2.0 * (qx * qx + qy * qy)

        // World-frame forward for a compass heading: east component sin(h), north component
        // cos(h), no vertical component.
        val h = Math.toRadians(headingDeg)
        val fe = sin(h)
        val fn = cos(h)
        val fu = 0.0

        // Tablet-frame forward = R^T * worldForward (R is orthonormal, so its transpose is its
        // inverse; R^T's rows are R's columns).
        val tx = r00 * fe + r10 * fn + r20 * fu
        val ty = r01 * fe + r11 * fn + r21 * fu
        val tz = r02 * fe + r12 * fn + r22 * fu
        val mag = sqrt(tx * tx + ty * ty + tz * tz)
        if (mag < MIN_QUATERNION_NORM) return null
        return doubleArrayOf(tx / mag, ty / mag, tz / mag)
    }
}
