package au.com.threesixty.cabdispatch.ui.screens.logoff

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * 36 · Log Off — reskinned onto the [CaptainPalette] purple design system (2026-08-29 pass).
 * Visual layer only; the confirm/navigation contract is byte-identical to the previous version:
 * this is the confirmation step between the dashboard's log-off affordance and
 * [CabDispatchRoutes.SHIFT_REPORT] — LOG OFF navigates there exactly as before, CANCEL pops back
 * to the dashboard. Same [ShiftReportViewModel] instance supplies the real submitted/unsynced
 * state the subtitle reads (no new backend calls).
 *
 * Layout unchanged from the previous port: a dim scrim over the canvas (the dashboard backdrop
 * behind it is the dashboard screen itself, not reproduced here since this is its own route),
 * with the centered dialog card: [CaptainPalette.panel] surface, 1.5dp
 * [CaptainPalette.panelBorder] border, radius 24, 44/36 padding, 16 gap — H "Do you want to log
 * off?" (Inter Bold 32), a 480dp-wide real-state subtitle, then outline CANCEL (220×72) + a
 * danger-tinted LOG OFF (300×72) — log-off is a real state transition away from work, so it keeps
 * its own distinct colour rather than reading identically to every other primary CTA.
 *
 * The subtitle pattern ("Shift already reconciled · device stays bound to S5517 for the next
 * driver") maps 1:1 onto the real state the old screen already showed: submitted-or-not from
 * [ShiftReportViewModel], vehicle binding from [SessionHolder]. The old screen's extra summary
 * panel (takings/session/queue lines) is not reproduced here — except that a non-empty offline
 * queue still appends a real warning line, since silently hiding pending unsynced trips at
 * log-off would lose information the driver was previously shown.
 */
@Composable
fun LogOffScreen(
    navController: NavHostController,
    viewModel: ShiftReportViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val session by SessionHolder.session.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .background(Color(0xC705070C)), // rgba(5,7,12,0.78) dim over the dashboard backdrop
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(CaptainPalette.panel)
                .border(1.5.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 44.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Do you want to log off?",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = CaptainPalette.textPrimary,
            )
            Text(
                text = buildString {
                    append(if (state.submitted) "Shift already reconciled" else "Shift not yet submitted")
                    session?.vehicleId?.let { append(" · device stays bound to $it for the next driver") }
                    if (state.unsyncedTripsCount > 0) {
                        append("\n${state.unsyncedTripsCount} trip(s) still pending sync — they upload automatically on reconnect")
                    }
                },
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(480.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CaptainButton(
                    text = "CANCEL",
                    outline = true,
                    heightDp = 72,
                    modifier = Modifier.width(220.dp),
                ) {
                    navController.popBackStack()
                }
                LogOffDangerButton(
                    text = "LOG OFF",
                    modifier = Modifier.width(300.dp),
                ) {
                    navController.navigate(CabDispatchRoutes.SHIFT_REPORT)
                }
            }
        }
    }
}

/**
 * [CaptainButton]-shaped (same press-scale, radius, height) but filled with
 * [CaptainPalette.danger] instead of [CaptainPalette.primary] — [CaptainButton] itself only ships
 * a primary/outline pair, and LOG OFF is the one action on this screen that genuinely warrants a
 * visually distinct, cautionary colour rather than reading as just another primary CTA.
 */
@Composable
private fun LogOffDangerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, animationSpec = tween(120), label = "logoff-btn-press")
    Box(
        modifier = modifier
            .height(72.dp)
            .scale(scale)
            .clip(shape)
            .background(if (pressed) CaptainPalette.danger.copy(alpha = 0.85f) else CaptainPalette.danger)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = CaptainPalette.textPrimary)
    }
}
