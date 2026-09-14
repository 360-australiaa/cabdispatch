package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.location.inertial.CalibrationQuality
import au.com.threesixty.cabdispatch.domain.location.inertial.VehicleFrameCalibrator
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Task 8 of W2: Settings ▸ Diagnostics ▸ "Motion sensors" — the shadow-mode evidence **OWNER G3**
 * (enabling real inertial billing) is gated on, visible without a debug rebuild or a log pull.
 *
 * Read-only except for the debug-only "Record IMU trace" action (task 7) — this panel never
 * changes calibration or billing itself; `SIMULATOR_REQUIRES_ADMIN_PIN`-style gating does not apply
 * here because nothing here can fabricate a fare (see `AppContainer.inertialSpeedSource`'s own doc
 * for why the estimate is never billed unless `BuildConfig.INERTIAL_BILLING_ENABLED` is on, which
 * this panel neither reads nor changes).
 */
// FunctionNaming: PascalCase is the official Jetpack Compose naming convention for a @Composable
// function (it reads as a UI element, not a verb) -- every pre-existing composable in this
// codebase follows it too; only the ones baselined before this file existed are silent about it.
@Suppress("FunctionNaming")
@Composable
fun InertialDiagnosticsPanel(modifier: Modifier = Modifier) {
    val calibration by AppContainer.vehicleFrameCalibrator.calibration.collectAsState()
    val estimate by AppContainer.inertialSpeedSource.estimate.collectAsState()
    val residuals by AppContainer.inertialSpeedSource.residualStats.collectAsState()
    val gpsSpeedKmh by AppContainer.speedSource.speedKmh.collectAsState()

    val scope = rememberCoroutineScope()
    // Initial value is the recorder's OWN isRecording (not always false) so re-entering this
    // panel while a recording from a previous composition is still running (e.g. a config change)
    // shows the correct toggle state immediately.
    var recording by remember { mutableStateOf(AppContainer.imuTraceRecorder.isRecording) }

    Column(modifier = modifier) {
        DiagnosticsRow("Calibration", calibrationLabel(calibration?.quality))
        DiagnosticsRow(
            "Confirming events",
            "${calibration?.confirmingEventCount ?: 0} of ${VehicleFrameCalibrator.MIN_CALIBRATION_EVENTS}",
        )
        DiagnosticsRow("Live estimate vs GPS", "%.1f km/h vs %.1f km/h".format(estimate?.speedKmh ?: 0.0, gpsSpeedKmh))
        DiagnosticsRow("Confidence", estimate?.confidence?.name ?: "—")
        DiagnosticsRow("ZUPT count (this seed)", (estimate?.zuptCount ?: 0).toString())
        DiagnosticsRow(
            "Shadow-mode residual (median / p95)",
            residuals?.let { "%.1f / %.1f km/h (n=${it.sampleCount})".format(it.medianAbsKmh, it.p95AbsKmh) }
                ?: "Collecting…",
        )

        Spacer(Modifier.height(16.dp))

        RecordTraceButton(
            recording = recording,
            onToggle = {
                if (recording) {
                    AppContainer.imuTraceRecorder.stop()
                    recording = false
                } else {
                    // start() is not itself suspending -- it launches its own collection job on
                    // the scope handed to it. rememberCoroutineScope's scope is cancelled when
                    // this panel leaves composition, which correctly stops an in-progress
                    // recording rather than leaking it past the screen that started it.
                    AppContainer.imuTraceRecorder.start(
                        scope = scope,
                        imuSampler = AppContainer.imuSampler,
                        locationFix = AppContainer.speedSource.locationFix,
                    )
                    recording = true
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        // Technician escape hatch (2026-09-14 bench finding: a persisted axis learned from bogus
        // events drove the dial the wrong way through a whole tunnel). Drops the learned frame AND
        // its persisted copy; the heading seed takes over on the next moving fix.
        ResetCalibrationButton(onReset = { AppContainer.vehicleFrameCalibrator.invalidate() })
        AppContainer.imuTraceRecorder.lastRecordingFile?.let { file ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Last trace: ${file.name}",
                fontFamily = InterFamily,
                fontSize = 12.sp,
                color = CaptainPalette.textMuted,
            )
        }
    }
}

private fun calibrationLabel(quality: CalibrationQuality?): String = when (quality) {
    CalibrationQuality.GOOD -> "Calibrated"
    CalibrationQuality.SEEDED -> "Seeded from GPS heading — learning"
    CalibrationQuality.LEARNING -> "Learning — drive normally"
    CalibrationQuality.NONE, null -> "Not yet calibrated"
}

@Composable
// FunctionNaming: see InertialDiagnosticsPanel's own suppress comment above.
@Suppress("FunctionNaming")
private fun DiagnosticsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted)
        Text(
            value,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}

// FunctionNaming: see InertialDiagnosticsPanel's own suppress comment above.
@Suppress("FunctionNaming")
@Composable
private fun RecordTraceButton(recording: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (recording) CaptainPalette.danger.copy(alpha = 0.15f) else CaptainPalette.raised)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            if (recording) "STOP RECORDING" else "RECORD IMU TRACE (DEBUG)",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = if (recording) CaptainPalette.danger else CaptainPalette.textPrimary,
        )
    }
}

// FunctionNaming: see InertialDiagnosticsPanel's own suppress comment above.
@Suppress("FunctionNaming")
@Composable
private fun ResetCalibrationButton(onReset: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CaptainPalette.raised)
            .clickable(onClick = onReset)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "RESET MOTION CALIBRATION",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}
