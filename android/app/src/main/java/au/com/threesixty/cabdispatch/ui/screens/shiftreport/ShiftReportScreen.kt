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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import java.math.RoundingMode

/**
 * 28 · Shift Report & Reconciliation — reskinned onto the [CaptainPalette] purple design system
 * (2026-08-29 pass). Presentation-only: [ShiftReportViewModel] (offline Room aggregates + submit)
 * and the `onDone` contract are untouched — `LaunchedEffect(state.submitted)` still fires
 * [onDone].
 *
 * The KPI grid shows TRIPS / KM DRIVEN / CASH TAKEN / CARD TAKEN / TOTAL TAKINGS / LEVIES OWED
 * (PSL) / PENDING SYNC. As the previous version of this file already documented, this app has no
 * "km paid", shift-hours, or tips tracking anywhere ([ShiftReportUiState] carries exactly
 * tripsCount/kmTotal/cashTotal/cardTotal/pslAccrued/totalTakings/unsyncedTripsCount) — so this
 * layout renders only the tiles with real backing data rather than fabricating numbers: TRIPS ·
 * KM DRIVEN · CASH TAKEN · CARD TAKEN on row one, TOTAL TAKINGS · LEVIES OWED (PSL) · PENDING
 * SYNC (only when > 0) on row two.
 *
 * Other deliberate deviations, carried over from the previous port: the dashboard-owned status
 * strip is omitted (same precedent as the ShiftStart/Permissions screens). No shift start/end is
 * tracked here (see the ViewModel's own TODO on ShiftEntity), so the subtitle shows the real
 * driver/vehicle ids plus the earliest trip's real start time instead of a start/end range. There
 * is no settings shortcut on this screen (Settings keeps its own route).
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
            .background(CaptainPalette.bg)
            .padding(start = 72.dp, end = 72.dp, top = 48.dp, bottom = 44.dp),
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CaptainPalette.primary)
            }
            state.shiftClientUuid == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active shift.", fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
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
            color = CaptainPalette.textPrimary,
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
            color = CaptainPalette.textMuted,
        )
        Spacer(Modifier.height(28.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ReportTile(Icons.Rounded.DirectionsCar, "${state.tripsCount}", "TRIPS", modifier = Modifier.weight(1f))
            ReportTile(Icons.Rounded.Map, state.kmTotal.setScale(1, RoundingMode.HALF_UP).toPlainString(), "KM DRIVEN", modifier = Modifier.weight(1f))
            ReportTile(Icons.Rounded.AttachMoney, state.cashTotal.asMoney(), "CASH TAKEN", valueColor = CaptainPalette.success, modifier = Modifier.weight(1f))
            ReportTile(Icons.Rounded.CreditCard, state.cardTotal.asMoney(), "CARD TAKEN", valueColor = CaptainPalette.accent, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ReportTile(Icons.Rounded.Receipt, state.totalTakings.asMoney(), "TOTAL TAKINGS", modifier = Modifier.weight(1f))
            ReportTile(Icons.Rounded.Sell, state.pslAccrued.asMoney(), "LEVIES OWED (PSL)", valueColor = CaptainPalette.warning, modifier = Modifier.weight(1f))
            if (state.unsyncedTripsCount > 0) {
                ReportTile(Icons.Rounded.Sync, "${state.unsyncedTripsCount}", "PENDING SYNC", valueColor = CaptainPalette.warning, modifier = Modifier.weight(1f))
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
                .background(CaptainPalette.raised)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                "Submitting reconciles cash vs card, posts ${state.pslAccrued.asMoney()} in PSL levies to the " +
                    "ledger, and prints the shift report once the thermal printer is fitted. The report is also " +
                    "available on the fleet dashboard.",
                fontFamily = InterFamily,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = CaptainPalette.textSecondary,
            )
        }

        if (state.unsyncedTripsCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${state.unsyncedTripsCount} trip(s) still pending sync — will upload automatically once online.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = CaptainPalette.textMuted,
            )
        }
        state.submitError?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(error, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.danger)
        }

        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CaptainButton(text = "← Not yet", outline = true, heightDp = 72, modifier = Modifier.width(220.dp)) {
                navController.popBackStack()
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(
                text = if (state.submitting) "SUBMITTING…" else "SUBMIT SHIFT & LOG OFF",
                heightDp = 80,
                fontSize = 22.sp,
                enabled = !state.submitting,
                modifier = Modifier.width(560.dp),
                onClick = vm::submitShift,
            )
        }
    }
}

/** The KPI tile: an icon + Chakra Medium 40 value over a 12sp bold caps label, on a
 * [CaptainPalette]-tokened panel. */
@Composable
private fun ReportTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = CaptainPalette.textPrimary) {
    Column(
        modifier = modifier
            .height(130.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(18.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 40.sp, color = valueColor)
        Spacer(Modifier.height(4.dp))
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CaptainPalette.textMuted)
    }
}
