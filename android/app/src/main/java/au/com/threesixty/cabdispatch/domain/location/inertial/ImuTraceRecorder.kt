package au.com.threesixty.cabdispatch.domain.location.inertial

import android.content.Context
import au.com.threesixty.cabdispatch.domain.LocationFix
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Task 7 of W2: debug-only recorder pairing [ImuSample]s with the concurrent [LocationFix], to a
 * CSV file on the tablet, for a drive the owner records deliberately (Settings > Diagnostics >
 * "Record IMU trace", wired by `InertialDiagnosticsPanel.kt`).
 *
 * **What this produces, and what it is for.** One row per published [ImuSample] (~10 Hz): the
 * sample's own fields, plus whatever [LocationFix] was current at that instant (nullable — a real
 * recording is USELESS without a blackout in it, and a blackout is exactly the interval this has
 * no fix to attach). `test/resources/imu/` fixture files this format matches are replayed by
 * [InertialSpeedEstimator]/[VehicleFrameCalibrator] unit tests (`TripTraceReplayFidelityTest`-style
 * — see the plan's task 7). **Real recordings from actual Sydney drives (>= 3, including a Harbour
 * Tunnel or Lane Cove Tunnel run) are an OWNER field task** — nothing in this codebase can produce
 * one without a physical tablet in a moving vehicle; every fixture shipped with this pass is
 * synthetic (generated from the simulator's own speed profile plus modelled sensor noise/bias),
 * and is labelled as such in its own file header, never presented as a real drive.
 *
 * Never wired into a release build's default path — this file exists to be invoked from a debug-
 * gated UI action, and writes only to [Context.getExternalFilesDir] (app-private, no storage
 * permission needed, auto-cleared on uninstall).
 */
class ImuTraceRecorder(private val context: Context) {

    private var job: Job? = null
    private var writer: FileWriter? = null

    val isRecording: Boolean get() = job != null

    /** File the current (or most recent) recording was/is written to — surfaced so the diagnostics
     * panel can show the driver where it landed for hand-off. */
    var lastRecordingFile: File? = null
        private set

    fun start(
        scope: CoroutineScope,
        imuSampler: ImuSampler,
        locationFix: kotlinx.coroutines.flow.StateFlow<LocationFix?>,
    ) {
        if (isRecording) return
        val dir = File(context.getExternalFilesDir(null), "imu_traces").apply { mkdirs() }
        val file = File(dir, "imu_trace_${System.currentTimeMillis()}.csv")
        lastRecordingFile = file
        val fileWriter = FileWriter(file, false)
        fileWriter.appendLine(
            "timestampNanos,accelX,accelY,accelZ,gravX,gravY,gravZ,gyroX,gyroY,gyroZ," +
                "rotX,rotY,rotZ,fixLat,fixLng,fixSpeedKmh,fixHeading",
        )
        writer = fileWriter

        job = scope.launch {
            combine(imuSampler.samples, locationFix) { sample, fix -> sample to fix }.collect { (sample, fix) ->
                if (sample == null) return@collect
                val row = buildString {
                    append(sample.timestampNanos).append(',')
                    append(sample.linearAccelerationMps2.joinToString(",")).append(',')
                    append(sample.gravityMps2.joinToString(",")).append(',')
                    append(sample.gyroscopeRadPerS.joinToString(",")).append(',')
                    append(sample.rotationVector.joinToString(",")).append(',')
                    append(fix?.lat ?: "").append(',')
                    append(fix?.lng ?: "").append(',')
                    append(fix?.speedKmh ?: "").append(',')
                    append(fix?.heading ?: "")
                }
                runCatching { writer?.appendLine(row) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { writer?.flush(); writer?.close() }
        writer = null
    }
}
