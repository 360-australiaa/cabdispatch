package au.com.threesixty.cabdispatch.ui.screens.tripdetail

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.domain.format.asPaymentMethodLabel
import au.com.threesixty.cabdispatch.domain.format.asTripTypeLabel
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 27 · Trip Detail & Dispute — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `26:30`).
 * Presentation-only rewrite: all data flow is unchanged [TripDetailViewModel] (fare reconstruction,
 * dispute submission via `PATCH /v1/trips/{id}/flag` with its two client-side gates).
 *
 * Layout: two 560-ish columns on the 1280×800 canvas, ZERO vertical scroll. Left — trip title +
 * COMPLETED pill, pickup/drop-off timeline card, and the frame's blue EVIDENCE PACK card rendered
 * from REAL trip fields only (gps-trace point count from [TripEntity.gpsTraceJson], tick counters,
 * [TripEntity.tariffId], payment method + [TripEntity.receiptRef]) — no fabricated "duress log"
 * style entries. Right — fare breakdown card (ledGreen Chakra total) and the red-outline
 * DISPUTE / FLAG FOR REVIEW tile which expands into the same dispute form as before.
 *
 * Honesty deviations from the frame, flagged: the frame's "🖨 REPRINT RECEIPT" button has no
 * backing action anywhere on [TripDetailViewModel] (receipt reprint lives in the Close & Pay
 * flow), so it is omitted rather than rendered dead. The frame's status strip is dashboard-owned
 * state (see DeckHomeScreen) with no honest source on this route screen — omitted, matching the
 * ShiftStart/Permissions port precedent. Pickup/drop-off "Area 1 — Sydney City" style addresses
 * don't exist ([TripEntity] persists lat/lng only, no geocoder — see TripDisplayFormat's doc), so
 * the timeline shows real times/coordinates/distance instead.
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
            .background(Deck.canvas)
            .padding(start = 64.dp, end = 64.dp, top = 40.dp, bottom = 36.dp),
    ) {
        when (val s = state) {
            is TripDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Deck.yellow)
            }
            is TripDetailUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, fontFamily = InterFamily, fontSize = 16.sp, color = Deck.textSecondary)
            }
            is TripDetailUiState.Loaded -> TripDetailBody(s, viewModel, navController)
        }
    }
}

@Composable
private fun TripDetailBody(state: TripDetailUiState.Loaded, vm: TripDetailViewModel, navController: NavHostController) {
    val trip = state.trip
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // --- Left column: title, timeline, evidence pack ---
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        trip.type.asTripTypeLabel(),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        color = Deck.textPrimary,
                    )
                    // Every trip reaching this screen is closed (see the pre-port doc history) —
                    // unconditional, not new state.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Deck.forHire.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text("COMPLETED", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Deck.forHire)
                    }
                }

                TimelineCard(trip)
                EvidencePackCard(trip)
            }

            // --- Right column: fare breakdown + dispute ---
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FareCard(state)
                DisputeSection(state, vm)
            }
        }

        Spacer(Modifier.height(16.dp))
        DeckButton(text = "← Back to trips", kind = DeckButtonKind.Ghost, modifier = Modifier.width(240.dp)) {
            navController.popBackStack()
        }
    }
}

@Composable
private fun TimelineCard(trip: TripEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimelineEntry(
            label = "① PICKUP — ${trip.type.asTripTypeLabel().uppercase()}",
            value = trip.startAt.asLocalTime(),
            caption = "%.5f, %.5f".format(trip.startLat, trip.startLng),
        )
        Box(
            Modifier
                .padding(start = 6.dp)
                .width(2.dp)
                .height(28.dp)
                .background(Deck.strokeStrong),
        )
        val km = BigDecimal(trip.distanceM).divide(BigDecimal(1000), 1, RoundingMode.HALF_UP).toPlainString()
        val totalS = trip.movingS + trip.waitingS
        TimelineEntry(
            label = "② DROP OFF",
            value = trip.endAt?.asLocalTime() ?: "—",
            caption = "$km km · ${totalS / 60} min ${totalS % 60} s",
        )
    }
}

@Composable
private fun TimelineEntry(label: String, value: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Deck.textMuted)
        Text(value, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Deck.textPrimary)
        Text(caption, fontFamily = RobotoMonoFamily, fontSize = 14.sp, color = Deck.textMuted)
    }
}

/**
 * The frame's blue "🗂 EVIDENCE PACK" card, built strictly from real persisted trip fields:
 * gps-trace point count (a cheap object count over the raw [TripEntity.gpsTraceJson] blob —
 * one `{` per [au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto], which is flat),
 * the meter tick counters, the signed tariff id, and the payment record/receipt ref.
 */
@Composable
private fun EvidencePackCard(trip: TripEntity) {
    val gpsPoints = if (trip.gpsTraceJson == "[]") 0 else trip.gpsTraceJson.count { it == '{' }
    val evidence = buildList {
        add(if (gpsPoints > 0) "GPS trace ($gpsPoints pts)" else "GPS trace (none)")
        add("meter tick log (${trip.movingS + trip.waitingS} s)")
        add("signed tariff ${trip.tariffId.take(8)}")
        add("payment record — ${trip.paymentMethod.asPaymentMethodLabel()}" + (trip.receiptRef?.let { " · $it" } ?: ""))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Deck.info.copy(alpha = 0.08f))
            .border(1.dp, Deck.info.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "🗂 EVIDENCE PACK — attached to any dispute",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Deck.info,
        )
        Text(
            evidence.joinToString(" · "),
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = Deck.textSecondary,
        )
    }
}

@Composable
private fun FareCard(state: TripDetailUiState.Loaded) {
    val trip = state.trip
    val b = state.breakdown
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FareLineRow("Flagfall", b.flagFall.asMoney())
        if (b.peakCharge.signum() > 0) FareLineRow("Peak time charge", b.peakCharge.asMoney())
        FareLineRow("Distance", b.distanceCharge.asMoney())
        FareLineRow("Waiting", b.waitingCharge.asMoney())
        if (b.tolls.signum() > 0) FareLineRow("Tolls", b.tolls.asMoney())
        if (b.psl.signum() > 0) FareLineRow("PSL levy", b.psl.asMoney())
        if (b.cleaningFee.signum() > 0) FareLineRow("Cleaning fee", b.cleaningFee.asMoney())
        if (b.extras.signum() > 0) FareLineRow("Extras", b.extras.asMoney())
        if (b.surcharge.signum() > 0) FareLineRow("Non-cash surcharge", b.surcharge.asMoney())
        FareLineRow("GST included", b.gstComponent.asMoney())

        Box(Modifier.fillMaxWidth().height(1.dp).background(Deck.strokeSubtle))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TOTAL", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Deck.textMuted)
            Text(
                b.grandTotal.asMoney(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 40.sp,
                color = Deck.ledGreen,
            )
        }
        Text(
            "PAID — ${trip.paymentMethod.asPaymentMethodLabel().uppercase()}" +
                (trip.receiptRef?.let { " · Receipt $it" } ?: ""),
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = Deck.forHire,
        )
    }
}

@Composable
private fun FareLineRow(label: String, amount: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 15.sp, color = Deck.textSecondary)
        Text(amount, fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Deck.textPrimary)
    }
}

/**
 * The frame's red "⚑ DISPUTE / FLAG FOR REVIEW" tile — collapsed by default (UI-only expand
 * state), expanding into the same dispute form wired to [TripDetailViewModel.submitDispute] /
 * [TripDetailViewModel.setDisputeReason]; error, not-synced-yet, in-progress, and submitted
 * states all render exactly as the ViewModel reports them.
 */
@Composable
private fun DisputeSection(state: TripDetailUiState.Loaded, vm: TripDetailViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    if (state.disputeState == DisputeSubmitState.SUBMITTED) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Deck.forHire.copy(alpha = 0.12f))
                .border(1.5.dp, Deck.forHire.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "⚑ DISPUTE SUBMITTED — flagged for operator review",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Deck.forHire,
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Deck.card)
            .border(1.5.dp, Deck.hired.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "⚑ DISPUTE / FLAG FOR REVIEW",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = Deck.hired,
        )
    }

    if (!expanded) return

    val notSynced = state.trip.serverId == null
    val inProgress = state.disputeState == DisputeSubmitState.IN_PROGRESS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = state.disputeReason,
            onValueChange = vm::setDisputeReason,
            placeholder = { Text("What went wrong with this trip?", fontFamily = InterFamily) },
            minLines = 2,
            enabled = !inProgress,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Deck.textPrimary,
                unfocusedTextColor = Deck.textPrimary,
                focusedBorderColor = Deck.yellow,
                unfocusedBorderColor = Deck.strokeStrong,
                focusedContainerColor = Deck.inset,
                unfocusedContainerColor = Deck.inset,
                cursorColor = Deck.yellow,
                focusedPlaceholderColor = Deck.textMuted,
                unfocusedPlaceholderColor = Deck.textMuted,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (notSynced) {
            Text(
                "This trip hasn't synced to the server yet — try again once it has.",
                fontFamily = InterFamily,
                fontSize = 12.sp,
                color = Deck.textMuted,
            )
        }
        state.disputeError?.let { error ->
            Text(error, fontFamily = InterFamily, fontSize = 12.sp, color = Deck.hired)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeckButton(
                text = "SUBMIT DISPUTE",
                kind = DeckButtonKind.Danger,
                heightDp = 56,
                fontSize = 15,
                enabled = !inProgress && !notSynced && state.disputeReason.isNotBlank(),
                modifier = Modifier.width(260.dp),
                onClick = vm::submitDispute,
            )
            if (inProgress) {
                CircularProgressIndicator(color = Deck.hired, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}
