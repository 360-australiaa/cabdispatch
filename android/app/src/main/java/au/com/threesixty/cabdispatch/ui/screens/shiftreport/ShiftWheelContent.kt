package au.com.threesixty.cabdispatch.ui.screens.shiftreport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionSummary
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.screens.earnings.StatGrid
import au.com.threesixty.cabdispatch.ui.theme.WheelColorsV2
import java.time.Duration
import java.time.Instant

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.SHIFT] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Shift: stat grid — hours on shift, trip count, cash to reconcile,
 * card settled, + Submit Shift action") — a direct Compose port of the reference prototype's
 * Shift `.stat-grid` + `.shift-btn` (docs/driver-dashboard-full-prototype.html lines ~367-374).
 *
 * Deliberately reuses [ShiftReportViewModel] (S5's existing ViewModel, same package) rather than
 * duplicating its Room-aggregate + outbox-queuing logic — see that class's doc for why it drives
 * off [au.com.threesixty.cabdispatch.domain.SessionHolder]'s `shiftId` rather than
 * [au.com.threesixty.cabdispatch.data.local.dao.ShiftDao] directly. Only "hours on shift" is new
 * here — [ShiftReportUiState] doesn't carry it, so it's derived in this composable from the same
 * `trips` list the ViewModel already exposes (earliest trip's `startAt` -> now), the identical
 * approximation [ShiftReportViewModel]'s `persistShiftClose` already makes for a shift's start
 * time.
 *
 * Renders only the content-pane *body*, not eyebrow/hero title chrome — same convention as
 * [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent].
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * renders this composable for [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.SHIFT], wiring
 * [onSubmitted] exactly as suggested below — see that screen's `ShiftSlotContent`:
 * `ShiftWheelContent(onSubmitted = { summary -> ShiftSubmissionHandoff.set(summary); navController.navigate(CabDispatchRoutes.SHIFT_SUBMITTED) })`
 * (see [au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff]'s doc for why the hand-off
 * object instead of a nav-graph argument).
 */
@Composable
fun ShiftWheelContent(
    modifier: Modifier = Modifier,
    onSubmitted: (ShiftSubmissionSummary) -> Unit = {},
    viewModel: ShiftReportViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.submitted) {
        if (state.submitted) onSubmitted(state.toSubmissionSummary())
    }

    when {
        state.loading -> CircularProgressIndicator()
        state.shiftClientUuid == null -> Text("No active shift.", color = WheelColorsV2.mutedFigure, fontSize = 14.sp)
        else -> ShiftWheelBody(state, viewModel, modifier)
    }
}

/**
 * **2026-08-27 fidelity pass:** was the second v1-[WheelColors] straggler found alongside
 * [StatGrid] (both flagged in the same audit) — migrated to [WheelColorsV2]. Also replaced the
 * plain gold text pill (labeled "SUBMIT SHIFT") with Figma's actual CTA for this action (node
 * 8:305, "Chip / CTA_ENDSHIFT"): a full-width red button reading "END SHIFT & RECONCILE" — a
 * more honest visual signal for what is, functionally, an irreversible end-of-shift action
 * ([ShiftReportViewModel.submitShift], unchanged).
 */
@Composable
private fun ShiftWheelBody(state: ShiftReportUiState, vm: ShiftReportViewModel, modifier: Modifier) {
    Column(modifier = modifier) {
        StatGrid(
            listOf(
                "%.1f".format(state.hoursOnShift()) to "Hours on shift",
                state.tripsCount.toString() to "Trips",
                state.cashTotal.asMoney() to "Cash to reconcile",
                state.cardTotal.asMoney() to "Card settled",
            ),
        )

        if (state.submitError != null) {
            Text(state.submitError, color = WheelColorsV2.dangerText, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }

        if (state.submitting) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp), color = WheelColorsV2.dangerText)
        } else {
            Text(
                "END SHIFT & RECONCILE",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(WheelColorsV2.dangerText, RoundedCornerShape(12.dp))
                    .clickable(onClick = vm::submitShift)
                    .padding(vertical = 16.dp),
            )
        }
    }
}

/** Earliest trip's startAt -> now, in hours — see [ShiftWheelContent]'s doc for why this is the same approximation [ShiftReportViewModel] itself makes for a shift's start time. */
private fun ShiftReportUiState.hoursOnShift(): Double {
    val earliest = trips.minOfOrNull(TripEntity::startAt) ?: return 0.0
    val start = runCatching { Instant.parse(earliest) }.getOrNull() ?: return 0.0
    return Duration.between(start, Instant.now()).toMinutes() / 60.0
}

private fun ShiftReportUiState.toSubmissionSummary(): ShiftSubmissionSummary = ShiftSubmissionSummary(
    tripsCount = tripsCount,
    kmTotal = kmTotal,
    cashTotal = cashTotal,
    cardTotal = cardTotal,
    pslAccrued = pslAccrued,
    hoursOnShift = hoursOnShift(),
)
