package au.com.threesixty.cabdispatch.ui.screens.logoff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * 36 · Log Off — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `29:104`). Visual
 * layer only; the confirm/navigation contract is byte-identical to the previous version: this is
 * the confirmation step between the dashboard's log-off affordance and
 * [CabDispatchRoutes.SHIFT_REPORT] — LOG OFF navigates there exactly as before, CANCEL pops back
 * to the dashboard. Same [ShiftReportViewModel] instance supplies the real submitted/unsynced
 * state the subtitle reads (no new backend calls).
 *
 * v2 layout, literal from the frame: a `rgba(5,7,12,0.78)` dim over the canvas (the frame's
 * dimmed dashboard backdrop — nav rail/map/drive panel — is the dashboard screen itself, not
 * reproduced here since this is its own route), with the centered `logoffDialog` card: panel
 * surface, 1.5dp [Deck.strokeStrong] border, radius 24, 44/36 padding, 16 gap — H "Do you want
 * to log off?" (Inter Bold 32), a 480dp-wide real-state subtitle, then outline CANCEL (220×72)
 * + danger LOG OFF (300×72) via the shared `c/btn-*` [DeckButton]s.
 *
 * The frame's subtitle pattern ("Shift already reconciled · device stays bound to S5517 for the
 * next driver") maps 1:1 onto the real state the old screen already showed: submitted-or-not from
 * [ShiftReportViewModel], vehicle binding from [SessionHolder]. The old screen's extra summary
 * panel (takings/session/queue lines) is not in this frame and is dropped — except that a
 * non-empty offline queue still appends a real warning line, since silently hiding pending
 * unsynced trips at log-off would lose information the driver was previously shown.
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
            .background(Deck.canvas)
            .background(Color(0xC705070C)), // rgba(5,7,12,0.78) — frame 29:205 dim
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(Deck.R_XL.dp))
                .background(Deck.panel)
                .border(1.5.dp, Deck.strokeStrong, RoundedCornerShape(Deck.R_XL.dp))
                .padding(horizontal = 44.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Do you want to log off?",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = Deck.textPrimary,
            )
            Text(
                text = buildString {
                    append(if (state.submitted) "Shift already reconciled" else "Shift not yet submitted")
                    session?.vehicleId?.let { append(" · device stays bound to $it for the next driver") }
                    if (state.unsyncedTripsCount > 0) {
                        append("\n${state.unsyncedTripsCount} trip(s) still pending sync — they upload automatically on reconnect")
                    }
                },
                fontFamily = au.com.threesixty.cabdispatch.ui.theme.InterFamily,
                fontSize = 16.sp,
                color = Deck.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(480.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DeckButton(
                    text = "CANCEL",
                    kind = DeckButtonKind.Outline,
                    heightDp = 72,
                    modifier = Modifier.width(220.dp),
                ) {
                    navController.popBackStack()
                }
                DeckButton(
                    text = "LOG OFF",
                    kind = DeckButtonKind.Danger,
                    heightDp = 72,
                    modifier = Modifier.width(300.dp),
                ) {
                    navController.navigate(CabDispatchRoutes.SHIFT_REPORT)
                }
            }
        }
    }
}
