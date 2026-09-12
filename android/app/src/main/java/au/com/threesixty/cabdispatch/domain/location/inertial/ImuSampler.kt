package au.com.threesixty.cabdispatch.domain.location.inertial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Task 2 of W2: the only file in this package that touches `android.hardware.*` directly — every
 * other class in `domain/location/inertial/` takes an [ImuSample] and knows nothing about
 * `SensorManager`, so [InertialSpeedEstimator]/[VehicleFrameCalibrator] stay plain-JVM-testable.
 *
 * ### Lifecycle: on only while a hiring is open
 * [start] is called from `MeterController.openTrip`, [stop] from `MeterController.closeTrip` — see
 * that class's own wiring. Registering four sensors at `SENSOR_DELAY_GAME` costs real battery for
 * as long as they are live (this is exactly what W4's battery workstream is auditing elsewhere in
 * this program), so the cost is paid only for the ~fraction of a shift a vehicle is actually
 * hired, never for the idle majority of it. [android.hardware.SensorManager] handles duplicate
 * register/unregister calls safely, but [start]/[stop] are still idempotent here (a second [start]
 * while already running is a no-op) so a caller never has to track whether it already called one.
 *
 * ### Threading
 * All four listeners are registered against a dedicated [HandlerThread] (`imu-sampler`), NEVER the
 * main thread — task 2's explicit requirement. `onSensorChanged` runs on that thread and only ever
 * writes into a couple of `Volatile`-free plain fields it alone touches (single-threaded by
 * construction, since the platform only ever calls a listener back on the `Looper` it was
 * registered against), then publishes a combined [ImuSample] onto [samples], a
 * [kotlinx.coroutines.flow.StateFlow] any thread may safely collect.
 *
 * ### Downsampling
 * Raw events arrive close to `SENSOR_DELAY_GAME`'s ~50 Hz, four independent streams that do not
 * arrive together. Rather than publish on every single event (four times the downstream work for
 * no accuracy this estimator needs, since [InertialSpeedEstimator] integrates over its own `dt`
 * regardless of how often it is called), a lightweight internal timer publishes the most recently
 * seen value of each of the four vectors together, at [PUBLISH_INTERVAL_MS] (~10 Hz) — exactly the
 * cadence task 2 specifies.
 */
class ImuSampler(private val context: Context) {

    private val _samples = MutableStateFlow<ImuSample?>(null)
    val samples: StateFlow<ImuSample?> = _samples.asStateFlow()

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var listener: SensorEventListener? = null

    @Volatile private var latestLinearAccel: FloatArray = ZERO_VECTOR
    @Volatile private var latestGravity: FloatArray = DOWN_VECTOR
    @Volatile private var latestGyro: FloatArray = ZERO_VECTOR
    @Volatile private var latestRotationVector: FloatArray = ZERO_VECTOR

    val isRunning: Boolean
        get() = handlerThread != null

    fun start() {
        if (isRunning) return
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val thread = HandlerThread("imu-sampler").also { it.start() }
        val threadHandler = Handler(thread.looper)
        handlerThread = thread
        handler = threadHandler

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LINEAR_ACCELERATION -> latestLinearAccel = event.values.copyOf(VECTOR_SIZE)
                    Sensor.TYPE_GRAVITY -> latestGravity = event.values.copyOf(VECTOR_SIZE)
                    Sensor.TYPE_GYROSCOPE -> latestGyro = event.values.copyOf(VECTOR_SIZE)
                    Sensor.TYPE_ROTATION_VECTOR ->
                        latestRotationVector = event.values.copyOf(minOf(VECTOR_SIZE, event.values.size))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = sensorListener

        for (type in SENSOR_TYPES) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME, threadHandler)
            }
        }

        schedulePublish(threadHandler)
    }

    private fun schedulePublish(threadHandler: Handler) {
        val publisher = object : Runnable {
            override fun run() {
                if (!isRunning) return // stop() may have already torn this down on another thread
                _samples.value = ImuSample(
                    timestampNanos = System.nanoTime(),
                    linearAccelerationMps2 = latestLinearAccel,
                    gravityMps2 = latestGravity,
                    gyroscopeRadPerS = latestGyro,
                    rotationVector = latestRotationVector,
                )
                threadHandler.postDelayed(this, PUBLISH_INTERVAL_MS)
            }
        }
        threadHandler.post(publisher)
    }

    fun stop() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        _samples.value = null
        latestLinearAccel = ZERO_VECTOR
        latestGravity = DOWN_VECTOR
        latestGyro = ZERO_VECTOR
        latestRotationVector = ZERO_VECTOR
    }

    companion object {
        private const val PUBLISH_INTERVAL_MS = 100L // ~10 Hz, task 2

        /** Every `SensorEvent.values` this class reads is a 3-axis vector (x, y, z) — the rotation
         * vector sensor can report a 4th (scalar) component on some devices, hence the `minOf`
         * clamp at each of its own read sites rather than assuming a fixed array length there. */
        private const val VECTOR_SIZE = 3

        private val SENSOR_TYPES = intArrayOf(
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR,
        )

        private val ZERO_VECTOR = floatArrayOf(0f, 0f, 0f)

        /** Sensible default for [latestGravity] before the first real gravity event arrives — "down"
         * in an untouched frame — so an [ImuSample] published in the brief window before the very
         * first gravity event is at least not all-zero (which [VehicleFrameCalibrator]'s horizontal-
         * plane projection would divide by). Overwritten by the first real event immediately. */
        private val DOWN_VECTOR = floatArrayOf(0f, 0f, 9.81f)
    }
}
