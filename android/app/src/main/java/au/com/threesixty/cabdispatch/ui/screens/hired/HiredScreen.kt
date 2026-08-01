package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.AccrualMode
import au.com.threesixty.cabdispatch.domain.FareBreakdown
import au.com.threesixty.cabdispatch.domain.TariffBand
import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TollPresets
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CabDispatchColors
import java.math.BigDecimal
import java.util.Locale

@Composable
fun HiredScreen(
    navController: NavHostController,
    viewModel: HiredViewModel = viewModel(),
) {
    val fareState by viewModel.fareState.collectAsState()
    val speechEnabled by viewModel.speechEnabled.collectAsState()
    val breakdownExpanded by viewModel.breakdownExpanded.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CabDispatchColors.Indigo),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusRow(fareState.status, fareState.band, fareState.timeClass)

            Spacer(modifier = Modifier.height(24.dp))

            // Large high-contrast running fare display, bound to the fare
            // engine's live state — per spec B5 S3.
            Text(
                text = fareState.total.toMoneyString(),
                color = CabDispatchColors.Gold,
                style = MaterialTheme.typography.displayLarge,
            )

            ModeIndicator(fareState.mode, fareState.currentSpeedKmh)

            Spacer(modifier = Modifier.height(16.dp))

            TollButtonsRow(onAddToll = viewModel::addToll)

            Spacer(modifier = Modifier.height(16.dp))

            BreakdownDrawer(
                expanded = breakdownExpanded,
                onToggle = viewModel::toggleBreakdown,
                breakdown = fareState.breakdown,
            )

            Spacer(modifier = Modifier.weight(1f))

            SpeechToggleRow(speechEnabled, viewModel::toggleSpeech)

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = viewModel::togglePause,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (fareState.status == TripStatus.STOPPED) "Resume" else "Stop")
                }
                Button(
                    onClick = {
                        viewModel.endTrip {
                            navController.navigate(CabDispatchRoutes.CLOSE_PAY)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("End trip")
                }
            }
        }

        // Hidden triple-tap-corner duress gesture — deliberately no visible
        // affordance, per spec B5 S3 ("hidden duress gesture").
        DuressCorner(
            modifier = Modifier.align(Alignment.TopEnd),
            onTriggered = viewModel::onDuressTriggered,
        )

        // Settings (S6) must be reachable from anywhere (spec B5). Kept
        // deliberately small/low-contrast in the opposite corner from the
        // duress hit-target so it doesn't compete with this screen's
        // distraction-minimal running-fare display.
        Text(
            "⚙",
            color = CabDispatchColors.Lavender,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.TopStart)
                .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) }
                .padding(12.dp),
        )
    }
}

@Composable
private fun StatusRow(status: TripStatus, band: TariffBand, timeClass: TimeClass) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MeterChip(text = if (status == TripStatus.STOPPED) "STOPPED" else "HIRED")
        MeterChip(text = band.label)
        MeterChip(text = timeClass.label)
    }
}

@Composable
private fun MeterChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CabDispatchColors.Purple)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ModeIndicator(mode: AccrualMode, speedKmh: Double) {
    val label = if (mode == AccrualMode.DISTANCE) "DISTANCE" else "WAITING"
    Text(
        text = String.format(Locale.US, "%s  ·  %.0f km/h", label, speedKmh),
        color = CabDispatchColors.Lavender,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun TollButtonsRow(onAddToll: (TollPreset) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TollPresets.ALL.forEach { preset ->
            OutlinedButton(onClick = { onAddToll(preset) }) {
                Text(preset.label)
            }
        }
    }
}

@Composable
private fun BreakdownDrawer(
    expanded: Boolean,
    onToggle: () -> Unit,
    breakdown: FareBreakdown,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CabDispatchColors.Purple)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Fare breakdown", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text(if (expanded) "▲" else "▼", color = Color.White)
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            BreakdownRow("Flag fall", breakdown.flagFall)
            BreakdownRow("Distance", breakdown.distanceAmount)
            BreakdownRow("Waiting", breakdown.waitingAmount)
            BreakdownRow("Peak", breakdown.peakAmount)
            BreakdownRow("Tolls", breakdown.tolls)
            BreakdownRow("PSL", breakdown.psl)
        }
    }
}

@Composable
private fun BreakdownRow(label: String, amount: BigDecimal) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = CabDispatchColors.Lavender)
        Text(amount.toMoneyString(), color = Color.White)
    }
}

@Composable
private fun SpeechToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Spoken fare announcements", color = CabDispatchColors.Lavender)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/**
 * Invisible 56dp hit-target in the corner; three taps within 800ms fires
 * [onTriggered]. No visible content by design — see spec B5 S3 ("hidden
 * duress gesture").
 */
@Composable
private fun DuressCorner(modifier: Modifier = Modifier, onTriggered: () -> Unit) {
    var tapTimestamps by remember { mutableStateOf(listOf<Long>()) }
    Box(
        modifier = modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    val recent = (tapTimestamps + now).filter { now - it < 800 }
                    if (recent.size >= 3) {
                        tapTimestamps = emptyList()
                        onTriggered()
                    } else {
                        tapTimestamps = recent
                    }
                })
            },
    )
}
