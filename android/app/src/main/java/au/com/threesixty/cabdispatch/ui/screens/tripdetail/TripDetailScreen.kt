package au.com.threesixty.cabdispatch.ui.screens.tripdetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.domain.format.asPaymentMethodLabel
import au.com.threesixty.cabdispatch.domain.format.asTripTypeLabel
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Row 16 — Trip detail (spec §8, "not designed" in the HTML prototype — see
 * that doc's screen inventory table). Reuses the fare-breakdown *display
 * pattern* from S4's `ReadyToCloseContent`/`ReceiptStepContent`
 * (CloseAndPayScreen.kt: a card of label/amount rows, divider, bold total,
 * "includes GST of ..." caption) but restyled with [WheelColors] to match the
 * new wheel-redesign visual language rather than plain Material3, since this
 * screen is reached from the "Trips" wheel content pane, not the legacy S1-S6
 * flow. See [TripDetailViewModel] for the data flow.
 *
 * Verified (reconciliation pass): reached exactly this way — the Trips wheel-slot content
 * ([au.com.threesixty.cabdispatch.ui.screens.trips.TripsWheelContent], hosted by
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]) navigates here via
 * [androidx.navigation.NavHostController.navigate] when a trip history row is tapped.
 */
@Composable
fun TripDetailScreen(
    navController: NavHostController,
    viewModel: TripDetailViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg)
            .padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                color = WheelColors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.clickable { navController.popBackStack() },
            )
            Text("Trip detail", color = WheelColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.widthIn(min = 48.dp))
        }
        Spacer(Modifier.height(20.dp))

        when (val s = state) {
            is TripDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is TripDetailUiState.Error -> Text(s.message, color = WheelColors.textSecondary, fontSize = 14.sp)
            is TripDetailUiState.Loaded -> TripDetailBody(s, viewModel)
        }
    }
}

@Composable
private fun TripDetailBody(state: TripDetailUiState.Loaded, vm: TripDetailViewModel) {
    val trip = state.trip
    val b = state.breakdown
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(trip.type.asTripTypeLabel(), color = WheelColors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${trip.startAt.asLocalTime()} · Paid by ${trip.paymentMethod.asPaymentMethodLabel()}",
            color = WheelColors.textSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(WheelColors.surfaceRaised.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .border(1.dp, WheelColors.border, RoundedCornerShape(14.dp))
                .padding(18.dp),
        ) {
            FareLineRow("Hiring charge", b.flagFall.asMoney())
            if (b.peakCharge.signum() > 0) FareLineRow("Peak time charge", b.peakCharge.asMoney())
            FareLineRow("Distance", b.distanceCharge.asMoney())
            FareLineRow("Waiting", b.waitingCharge.asMoney())
            if (b.tolls.signum() > 0) FareLineRow("Tolls", b.tolls.asMoney())
            if (b.psl.signum() > 0) FareLineRow("Point to Point Transport Levy", b.psl.asMoney())
            if (b.cleaningFee.signum() > 0) FareLineRow("Cleaning fee", b.cleaningFee.asMoney())
            if (b.extras.signum() > 0) FareLineRow("Extras", b.extras.asMoney())
            if (b.surcharge.signum() > 0) FareLineRow("Non-cash surcharge", b.surcharge.asMoney())

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .height(1.dp)
                    .background(WheelColors.border),
            )

            FareLineRow("Total", b.grandTotal.asMoney(), emphasize = true)
            Text(
                "includes GST of ${b.gstComponent.asMoney()}",
                color = WheelColors.textMuted,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        DisputeSection(state, vm)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Blueprint 5.2.5's "Dispute" button — reachable from a closed trip's detail screen (checked
 * ui/screens/hired first per the brief: S3/HIRED never shows a *closed* trip, only the live
 * in-progress one, so this screen — reached from the Trips wheel-slot content, see this file's own
 * doc — is the real place for it). Wired to [TripDetailViewModel.submitDispute] ->
 * `PATCH /v1/trips/{id}/flag`; see that function's own doc for the two client-side gates (non-blank
 * reason, a real synced [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.serverId])
 * before it calls the network at all.
 */
@Composable
private fun DisputeSection(state: TripDetailUiState.Loaded, vm: TripDetailViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColors.surfaceRaised.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .border(1.dp, WheelColors.border, RoundedCornerShape(14.dp))
            .padding(18.dp),
    ) {
        Text("DISPUTE THIS TRIP", color = WheelColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))

        if (state.disputeState == DisputeSubmitState.SUBMITTED) {
            Text(
                "Dispute submitted — flagged for operator review.",
                color = WheelColors.available,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            val notSynced = state.trip.serverId == null
            val inProgress = state.disputeState == DisputeSubmitState.IN_PROGRESS

            OutlinedTextField(
                value = state.disputeReason,
                onValueChange = vm::setDisputeReason,
                placeholder = { Text("What went wrong with this trip?") },
                minLines = 3,
                enabled = !inProgress,
                colors = disputeFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            if (notSynced) {
                Text(
                    "This trip hasn't synced to the server yet — try again once it has.",
                    color = WheelColors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val error = state.disputeError
            if (error != null) {
                Text(error, color = WheelColors.duress, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = vm::submitDispute,
                    enabled = !inProgress && !notSynced && state.disputeReason.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, WheelColors.duress),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = WheelColors.surface,
                        contentColor = WheelColors.duress,
                        disabledContentColor = WheelColors.textMuted,
                    ),
                ) { Text("SUBMIT DISPUTE", fontSize = 13.sp, fontWeight = FontWeight.Bold) }

                if (inProgress) {
                    CircularProgressIndicator(color = WheelColors.duress, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun disputeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WheelColors.textPrimary,
    unfocusedTextColor = WheelColors.textPrimary,
    focusedBorderColor = WheelColors.gold,
    unfocusedBorderColor = WheelColors.borderStrong,
    focusedContainerColor = WheelColors.surface,
    unfocusedContainerColor = WheelColors.surface,
    cursorColor = WheelColors.gold,
    focusedLabelColor = WheelColors.gold,
    unfocusedLabelColor = WheelColors.textSecondary,
    focusedPlaceholderColor = WheelColors.textMuted,
    unfocusedPlaceholderColor = WheelColors.textMuted,
)

@Composable
private fun FareLineRow(label: String, amount: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = if (emphasize) WheelColors.textPrimary else WheelColors.textSecondary,
            fontSize = if (emphasize) 16.sp else 14.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            amount,
            color = if (emphasize) WheelColors.gold else WheelColors.textPrimary,
            fontSize = if (emphasize) 16.sp else 14.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
