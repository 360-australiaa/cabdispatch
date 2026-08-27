package au.com.threesixty.cabdispatch.ui.screens.shiftreport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import java.math.RoundingMode

/**
 * 28 · Shift Report & Reconciliation — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node
 * `26:108`). Presentation-only: [ShiftReportViewModel] (offline Room aggregates + submit) and the
 * `onDone` contract are untouched — `LaunchedEffect(state.submitted)` still fires [onDone].
 *
 * The frame's 2×4 KPI grid shows TRIPS / KM DRIVEN / KM PAID / HOURS / CASH TAKEN / CARD TAKEN /
 * LEVIES OWED (PSL) / TIPS. As the previous version of this file already documented, this app has
 * no "km paid", shift-hours, or tips tracking anywhere ([ShiftReportUiState] carries exactly
 * tripsCount/kmTotal/cashTotal/cardTotal/pslAccrued/totalTakings/unsyncedTripsCount) — so this
 * layout renders only the tiles with real backing data, in the frame's tile style, rather than
 * fabricating numbers: TRIPS · KM DRIVEN · CASH TAKEN · CARD TAKEN on row one, TOTAL TAKINGS ·
 * LEVIES OWED (PSL) · PENDING SYNC (only when > 0) on row two.
 *
 * Other deliberate deviations: the frame's status strip is dashboard-owned state — omitted (same
 * precedent as the ShiftStart/Permissions ports). The header's "Wed 11:42 → Wed 17:48" range is
 * sample copy — no shift start/end is tracked here (see the ViewModel's own TODO on ShiftEntity),
 * so the subtitle shows the real driver/vehicle ids plus the earliest trip's real start time. The
 * old ⚙ settings shortcut isn't on the frame and was dropped (Settings keeps its own route).
 */
@Composable
fun ShiftReportScreen(
    navController: NavHostController,
    onDone: () -> Unit,
    viewModel: ShiftReportViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.submitted) {
        if (state.submitted) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Deck.canvas)
            .padding(start = 72.dp, end = 72.dp, top = 48.dp, bottom = 44.dp),
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Deck.yellow)
            }
            state.shiftClientUuid == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active shift.", fontFamily = InterFamily, fontSize = 16.sp, color = Deck.textSecondary)
            }
            else -> ShiftReportContent(state, viewModel, navController)
        }
    }
}

@Composable
private fun ShiftReportContent(state: ShiftReportUiState, vm: ShiftReportViewModel, navController: NavHostController) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "End of shift — reconciliation",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            color = Deck.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        val firstTripAt = state.trips.minOfOrNull { it.startAt }?.asLocalTime()
        Text(
            buildString {
                firstTripAt?.let { append("First trip $it · ") }
                append("Driver ${state.driverId?.take(8) ?: "—"} · Vehicle ${state.vehicleId ?: "—"}")
            },
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = Deck.textMuted,
        )
        Spacer(Modifier.height(28.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ReportTile("${state.tripsCount}", "TRIPS", modifier = Modifier.weight(1f))
            ReportTile(state.kmTotal.setScale(1, RoundingMode.HALF_UP).toPlainString(), "KM DRIVEN", modifier = Modifier.weight(1f))
            ReportTile(state.cashTotal.asMoney(), "CASH TAKEN", valueColor = Deck.forHire, modifier = Modifier.weight(1f))
            ReportTile(state.cardTotal.asMoney(), "CARD TAKEN", valueColor = Deck.info, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ReportTile(state.totalTakings.asMoney(), "TOTAL TAKINGS", modifier = Modifier.weight(1f))
            ReportTile(state.pslAccrued.asMoney(), "LEVIES OWED (PSL)", valueColor = Deck.stopped, modifier = Modifier.weight(1f))
            if (state.unsyncedTripsCount > 0) {
                ReportTile("${state.unsyncedTripsCount}", "PENDING SYNC", valueColor = Deck.stopped, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Deck.card)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                "Submitting reconciles cash vs card, posts ${state.pslAccrued.asMoney()} in PSL levies to the " +
                    "ledger, and prints the shift report once the thermal printer is fitted. The report is also " +
                    "available on the fleet dashboard.",
                fontFamily = InterFamily,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = Deck.textSecondary,
            )
        }

        if (state.unsyncedTripsCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${state.unsyncedTripsCount} trip(s) still pending sync — will upload automatically once online.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = Deck.textMuted,
            )
        }
        state.submitError?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(error, fontFamily = InterFamily, fontSize = 13.sp, color = Deck.hired)
        }

        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DeckButton(text = "← Not yet", kind = DeckButtonKind.Ghost, heightDp = 72, modifier = Modifier.width(220.dp)) {
                navController.popBackStack()
            }
            Spacer(Modifier.weight(1f))
            DeckButton(
                text = if (state.submitting) "SUBMITTING…" else "SUBMIT SHIFT & LOG OFF",
                kind = DeckButtonKind.Success,
                heightDp = 80,
                fontSize = 22,
                enabled = !state.submitting,
                modifier = Modifier.width(560.dp),
                onClick = vm::submitShift,
            )
        }
    }
}

/** The frame's 130dp KPI tile (node 26:139 etc): Chakra Medium 40 value over a 12sp bold caps label. */
@Composable
private fun ReportTile(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Deck.textPrimary) {
    Column(
        modifier = modifier
            .height(130.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(18.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 40.sp, color = valueColor)
        Spacer(Modifier.height(4.dp))
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Deck.textMuted)
    }
}
