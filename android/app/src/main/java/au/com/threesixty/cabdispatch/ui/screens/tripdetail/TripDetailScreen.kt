package au.com.threesixty.cabdispatch.ui.screens.tripdetail

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PaneShell
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 27 · Trip Detail & Dispute — Captain Taxis purple redesign, moved off the yellow/black `Deck`
 * palette onto [CaptainPalette] to match the rest of this dispatch-journey group (message thread,
 * incoming trip offer, offline sync). Presentation-only: all data flow is unchanged
 * [TripDetailViewModel] (fare reconstruction, dispute submission via `PATCH /v1/trips/{id}/flag`
 * with its two client-side gates).
 *
 * Layout: a shared [PaneShell] back+title header, then two columns — left the pickup/drop-off
 * timeline card and the evidence-pack card (built strictly from real persisted trip fields, see
 * [EvidencePackCard]'s doc); right the fare breakdown card and the dispute expand/collapse tile.
 * The dispute toggle and its "Submit dispute" action are the important-to-hit controls on this
 * screen, so both use a real [Icons.Rounded.Flag] glyph, ≥64dp height and ≥20sp bold text rather
 * than the previous smaller/emoji-labelled versions.
 */
@Composable
fun TripDetailScreen(
    navController: NavHostController,
    viewModel: TripDetailViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    PaneShell(title = "Trip Detail", onBack = { navController.popBackStack() }) {
        when (val s = state) {
            is TripDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CaptainPalette.primary)
            }
            is TripDetailUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
            }
            is TripDetailUiState.Loaded -> TripDetailBody(s, viewModel)
        }
    }
}

@Composable
private fun TripDetailBody(state: TripDetailUiState.Loaded, vm: TripDetailViewModel) {
    val trip = state.trip
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // --- Left column: title, timeline, evidence pack ---
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    trip.type.asTripTypeLabel(),
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = CaptainPalette.textPrimary,
                )
                // Every trip reaching this screen is closed (see the pre-port doc history) —
                // unconditional, not new state.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(CaptainPalette.success.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text("COMPLETED", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.success)
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
}

@Composable
private fun TimelineCard(trip: TripEntity) {
    CaptainPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TimelineEntry(
                icon = Icons.Rounded.TripOrigin,
                iconTint = CaptainPalette.success,
                label = "PICKUP — ${trip.type.asTripTypeLabel().uppercase()}",
                value = trip.startAt.asLocalTime(),
                caption = "%.5f, %.5f".format(trip.startLat, trip.startLng),
            )
            Box(
                Modifier
                    .padding(start = 11.dp)
                    .width(2.dp)
                    .height(28.dp)
                    .background(CaptainPalette.panelBorder),
            )
            val km = BigDecimal(trip.distanceM).divide(BigDecimal(1000), 1, RoundingMode.HALF_UP).toPlainString()
            val totalS = trip.movingS + trip.waitingS
            TimelineEntry(
                icon = Icons.Rounded.Flag,
                iconTint = CaptainPalette.accent,
                label = "DROP OFF",
                value = trip.endAt?.asLocalTime() ?: "—",
                caption = "$km km · ${totalS / 60} min ${totalS % 60} s",
            )
        }
    }
}

@Composable
private fun TimelineEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color, label: String, value: String, caption: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CaptainPalette.textMuted)
            Text(value, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = CaptainPalette.textPrimary)
            Text(caption, fontFamily = RobotoMonoFamily, fontSize = 14.sp, color = CaptainPalette.textMuted)
        }
    }
}

/**
 * The "EVIDENCE PACK" card, built strictly from real persisted trip fields: gps-trace point count
 * (a cheap object count over the raw [TripEntity.gpsTraceJson] blob — one `{` per
 * [au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto], which is flat), the meter tick
 * counters, the signed tariff id, and the payment record/receipt ref.
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
            .background(CaptainPalette.accent.copy(alpha = 0.1f))
            .border(1.dp, CaptainPalette.accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(18.dp))
            Text(
                "EVIDENCE PACK — attached to any dispute",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = CaptainPalette.accent,
            )
        }
        Text(
            evidence.joinToString(" · "),
            fontFamily = InterFamily,
            fontSize = 15.sp,
            color = CaptainPalette.textSecondary,
        )
    }
}

@Composable
private fun FareCard(state: TripDetailUiState.Loaded) {
    val trip = state.trip
    val b = state.breakdown
    CaptainPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Negotiated ("Set Price") trips billed the agreed amount, not the metered accrual —
            // show that instead of a flagfall/distance/waiting breakdown that would contradict what
            // was actually charged (see FareBreakdown.negotiatedTotal's doc). Mirrors the same
            // display-layer choice made in CloseAndPayScreen.kt's TotalCol.
            if (b.negotiatedTotal != null) {
                FareLineRow("Agreed price (Set Price)", b.negotiatedTotal.asMoney())
            } else {
                FareLineRow("Flagfall", b.flagFall.asMoney())
                if (b.peakCharge.signum() > 0) FareLineRow("Peak time charge", b.peakCharge.asMoney())
                FareLineRow("Distance", b.distanceCharge.asMoney())
                FareLineRow("Waiting", b.waitingCharge.asMoney())
                if (b.maxiRateApplied) {
                    // Pre-multiplier components only ("the fare" per the Fares Order) — real
                    // breakdown fields times the tariff's own real maxiMultiplier, never a
                    // hardcoded ×1.5. See CloseAndPayScreen.kt's TotalCol for the identical logic.
                    val meteredBase = b.flagFall + b.peakCharge + b.distanceCharge + b.waitingCharge
                    val uplift = meteredBase * (state.tariff.maxiMultiplier - java.math.BigDecimal.ONE)
                    val multiplierLabel = state.tariff.maxiMultiplier.stripTrailingZeros().toPlainString()
                    FareLineRow("Maxi-cab rate (×$multiplierLabel, 5+ passengers)", uplift.asMoney())
                }
            }
            if (b.tolls.signum() > 0) FareLineRow("Tolls", b.tolls.asMoney())
            if (b.psl.signum() > 0) FareLineRow("Point to Point Transport Levy", b.psl.asMoney())
            if (b.cleaningFee.signum() > 0) FareLineRow("Cleaning fee", b.cleaningFee.asMoney())
            if (b.extras.signum() > 0) FareLineRow("Extras", b.extras.asMoney())
            if (b.surcharge.signum() > 0) {
                // trip.surchargePct is the real percentage this trip was actually closed with
                // (persisted on TripEntity at close time) — not re-derived/guessed here. A non-zero
                // surcharge always implies a real non-null/non-zero persisted percentage (the
                // engine's surcharge formula is `fareTotal * pct / 100`; pct=0 would mean 0
                // surcharge), so no null branch is needed.
                val pct = trip.surchargePct?.toBigDecimalOrZero() ?: java.math.BigDecimal.ZERO
                FareLineRow("Non-cash payment surcharge (${pct.stripTrailingZeros().toPlainString()}%)", b.surcharge.asMoney())
            }
            FareLineRow("GST included", b.gstComponent.asMoney())

            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TOTAL", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.textMuted)
                Text(
                    b.grandTotal.asMoney(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 40.sp,
                    color = CaptainPalette.success,
                )
            }
            Text(
                "PAID — ${trip.paymentMethod.asPaymentMethodLabel().uppercase()}" +
                    (trip.receiptRef?.let { " · Receipt $it" } ?: ""),
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = CaptainPalette.success,
            )
        }
    }
}

@Composable
private fun FareLineRow(label: String, amount: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
        Text(amount, fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = CaptainPalette.textPrimary)
    }
}

/**
 * The "DISPUTE / FLAG FOR REVIEW" tile — collapsed by default (UI-only expand state), expanding
 * into the same dispute form wired to [TripDetailViewModel.submitDispute] /
 * [TripDetailViewModel.setDisputeReason]; error, not-synced-yet, in-progress, and submitted
 * states all render exactly as the ViewModel reports them. This is one of the two
 * important-to-hit controls on this screen's journey (the other being Accept on the trip-offer
 * screen), so the tile and its submit button are both large (≥64dp) with bold ≥20sp text.
 */
@Composable
private fun DisputeSection(state: TripDetailUiState.Loaded, vm: TripDetailViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    if (state.disputeState == DisputeSubmitState.SUBMITTED) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CaptainPalette.success.copy(alpha = 0.12f))
                .border(1.5.dp, CaptainPalette.success.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CaptainPalette.success, modifier = Modifier.size(26.dp))
            Text(
                "DISPUTE SUBMITTED — flagged for operator review",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = CaptainPalette.success,
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CaptainPalette.raised)
                .border(1.5.dp, CaptainPalette.danger.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Flag, contentDescription = null, tint = CaptainPalette.danger, modifier = Modifier.size(26.dp))
            Text(
                "DISPUTE / FLAG FOR REVIEW",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = CaptainPalette.danger,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = CaptainPalette.danger,
                modifier = Modifier.size(26.dp),
            )
        }

        if (expanded) {
            val notSynced = state.trip.serverId == null
            val inProgress = state.disputeState == DisputeSubmitState.IN_PROGRESS

            CaptainPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.disputeReason,
                        onValueChange = vm::setDisputeReason,
                        placeholder = { Text("What went wrong with this trip?", fontFamily = InterFamily, fontSize = 16.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = InterFamily, fontSize = 16.sp),
                        minLines = 2,
                        enabled = !inProgress,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CaptainPalette.textPrimary,
                            unfocusedTextColor = CaptainPalette.textPrimary,
                            focusedBorderColor = CaptainPalette.accent,
                            unfocusedBorderColor = CaptainPalette.panelBorder,
                            focusedContainerColor = CaptainPalette.inset,
                            unfocusedContainerColor = CaptainPalette.inset,
                            cursorColor = CaptainPalette.accent,
                            focusedPlaceholderColor = CaptainPalette.textMuted,
                            unfocusedPlaceholderColor = CaptainPalette.textMuted,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (notSynced) {
                        Text(
                            "This trip hasn't synced to the server yet — try again once it has.",
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
                            color = CaptainPalette.textMuted,
                        )
                    }
                    state.disputeError?.let { error ->
                        Text(error, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.danger)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        DangerButton(
                            text = "Submit dispute",
                            enabled = !inProgress && !notSynced && state.disputeReason.isNotBlank(),
                            modifier = Modifier.width(260.dp),
                            onClick = vm::submitDispute,
                        )
                        if (inProgress) {
                            CircularProgressIndicator(color = CaptainPalette.danger, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Local button variant mirroring [au.com.threesixty.cabdispatch.ui.theme.CaptainButton]'s
 * press-scale/shape exactly but filled with [CaptainPalette.danger] — for the one destructive,
 * consequential action on this screen (submitting a dispute), which the shared button's
 * primary/outline pair doesn't cover.
 */
@Composable
private fun DangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, animationSpec = tween(120), label = "danger-btn-press")
    Box(
        modifier = modifier
            .height(64.dp)
            .scale(scale)
            .clip(shape)
            .background(if (pressed && enabled) CaptainPalette.danger.copy(alpha = 0.85f) else CaptainPalette.danger)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = CaptainPalette.onAccent)
    }
}
