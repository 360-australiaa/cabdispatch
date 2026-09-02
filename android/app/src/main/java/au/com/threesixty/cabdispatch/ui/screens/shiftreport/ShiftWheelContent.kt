package au.com.threesixty.cabdispatch.ui.screens.shiftreport

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionSummary
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
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
 *
 * **2026-08-29 Captain Taxis reskin:** this pane is embedded inside
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s purple `PaneShell` chrome
 * (see [au.com.threesixty.cabdispatch.ui.theme.CaptainWidgets]) — its own internal colours/type
 * moved off the legacy glass/gold palette onto [CaptainPalette] so the content doesn't
 * clash with the panel wrapped around it. Visual-only: every state field read and the
 * [onSubmitted]/`vm::submitShift` call sites below are unchanged.
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
        state.loading -> Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = CaptainPalette.accent)
        }
        state.shiftClientUuid == null -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.EventBusy, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(22.dp))
            Text(
                "No active shift.",
                color = CaptainPalette.textSecondary,
                fontFamily = InterFamily,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        else -> ShiftWheelBody(state, viewModel, modifier)
    }
}

/**
 * The full-width CTA reads "END SHIFT & RECONCILE" — Figma node 8:305 ("Chip / CTA_ENDSHIFT"),
 * kept as a solid [CaptainPalette.danger] fill (rather than [CaptainPalette.primary]) since this
 * is, functionally, an irreversible end-of-shift action ([ShiftReportViewModel.submitShift],
 * unchanged) and deserves the same "this is different from a normal action" visual signal danger
 * colouring gives elsewhere in the app.
 */
@Composable
private fun ShiftWheelBody(state: ShiftReportUiState, vm: ShiftReportViewModel, modifier: Modifier) {
    Column(modifier = modifier) {
        ShiftStatGrid(
            listOf(
                "%.1f".format(state.hoursOnShift()) to "Hours on shift",
                state.tripsCount.toString() to "Trips",
                state.cashTotal.asMoney() to "Cash to reconcile",
                state.cardTotal.asMoney() to "Card settled",
            ),
        )

        if (state.submitError != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CaptainPalette.danger, modifier = Modifier.size(18.dp))
                Text(
                    state.submitError,
                    color = CaptainPalette.danger,
                    fontFamily = InterFamily,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (state.submitting) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 20.dp), color = CaptainPalette.danger)
        } else {
            EndShiftButton(onClick = vm::submitShift, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

/** Stat row (hours / trips / cash / card) reskinned onto [CaptainPalette] — same 4-value shape
 * [au.com.threesixty.cabdispatch.ui.screens.earnings.StatGrid] renders elsewhere, kept as a local
 * copy here rather than reusing that shared composable because it still renders on the legacy
 * glass/gold palette tokens and this migration is scoped to this file only. */
@Composable
private fun ShiftStatGrid(items: List<Pair<String, String>>) {
    CaptainPanel(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            items.forEach { (value, label) -> ShiftStatBox(value, label) }
        }
    }
}

@Composable
private fun ShiftStatBox(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = CaptainPalette.textPrimary, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 22.sp)
        Text(
            label,
            color = CaptainPalette.textSecondary,
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Full-width danger-filled CTA — mirrors [au.com.threesixty.cabdispatch.ui.theme.CaptainButton]'s
 * press-scale tactile feedback exactly, just on [CaptainPalette.danger] rather than `.primary`
 * (that shared composable has no danger variant, and this is the only full-width danger CTA in
 * the app so far). */
@Composable
private fun EndShiftButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, animationSpec = tween(120), label = "end-shift-press")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .scale(scale)
            .clip(shape)
            .background(if (pressed) CaptainPalette.danger.copy(alpha = 0.85f) else CaptainPalette.danger)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Flag, contentDescription = null, tint = CaptainPalette.textPrimary, modifier = Modifier.size(20.dp))
        Text(
            "END SHIFT & RECONCILE",
            color = CaptainPalette.textPrimary,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 10.dp),
        )
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
