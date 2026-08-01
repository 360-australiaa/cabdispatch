package au.com.threesixty.cabdispatch.ui.wheel.content

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.JobOfferHandoff
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Wheel slot 1 — "Available Trips" content pane (spec §4: "list of job cards — route, distance,
 * estimated fare, time since requested"; §8 row 11 "Job queue list", Figma-only — this pass ports
 * the list-row visuals from the reference prototype's `.list-row`/`row()` helper, the same as
 * every other list-shaped wheel slot, since §4 explicitly describes Available Trips using that
 * shape even though the HTML prototype itself never rendered this specific slot's data).
 *
 * "Distance" (spec §4's third column) is deliberately omitted from each card: it would need the
 * driver's own live position, and GPS is still stubbed project-wide (see `HANDOFF.md` "GPS is
 * stubbed, not real" / `domain/FareEngine.kt`'s `StubSpeedSource`) — showing a fabricated number
 * would be worse than showing none. Swap in once a real location provider lands.
 *
 * Accept/Decline are inline on each row (not only in the tap-through detail screen,
 * [au.com.threesixty.cabdispatch.ui.screens.availabletrips.AvailableTripOfferScreen]): offers
 * expire ~20s after being sent (see [au.com.threesixty.cabdispatch.domain.JobsRepository] doc), so
 * a "tap card, then tap accept" two-step would burn meaningfully into that window on a slow
 * network — the fast path has to be one tap from the list itself.
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * embeds this composable directly with the shared [NavHostController] for the
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.AVAILABLE_TRIPS] slot — see that screen's
 * `AvailableTripsSlotContent`, matching how every existing S1-S6 screen in this repo takes
 * `navController` (see CloseAndPayScreen.kt etc.).
 */
@Composable
fun AvailableTripsWheelContent(
    navController: NavHostController,
    viewModel: AvailableTripsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // One-shot: navigate to S3 the instant an accept lands, then let the ViewModel clear the
    // flag — see AvailableTripsUiState.navigateToHired's doc for why this shape.
    LaunchedEffect(state.navigateToHired) {
        if (state.navigateToHired) {
            navController.navigate(CabDispatchRoutes.HIRED)
            viewModel.onNavigatedToHired()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.actionError != null) {
            Text(
                state.actionError,
                color = WheelColors.duress,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        when {
            state.loading && state.cards.isEmpty() -> CircularProgressIndicator(color = WheelColors.gold)
            state.error != null -> Text(state.error, color = WheelColors.duress, fontSize = 14.sp)
            state.cards.isEmpty() -> Text(
                "No job offers right now. New offers appear here automatically.",
                color = WheelColors.textSecondary,
                fontSize = 14.sp,
            )
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.cards, key = { it.offer.id }) { card ->
                    JobOfferRow(
                        card = card,
                        busy = state.busyOfferId == card.offer.id,
                        onOpenDetail = {
                            JobOfferHandoff.set(card.job, card.offer)
                            navController.navigate(CabDispatchRoutes.AVAILABLE_TRIP_OFFER)
                        },
                        onAccept = { viewModel.acceptOffer(card) },
                        onDecline = { viewModel.declineOffer(card) },
                    )
                }
            }
        }
    }
}

@Composable
private fun JobOfferRow(
    card: AvailableTripCard,
    busy: Boolean,
    onOpenDetail: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val secondsLeft by rememberOfferCountdown(card.offer.expiresAt)
    val expired = (secondsLeft ?: 1L) <= 0L

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColors.surfaceRaised.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(1.dp, WheelColors.border, RoundedCornerShape(12.dp))
            .clickable(enabled = !expired, onClick = onOpenDetail)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${card.job.originAddress} → ${card.job.destAddress}",
                    color = WheelColors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Est. $${card.job.fareEstimateLow}–${card.job.fareEstimateHigh}",
                    color = WheelColors.textSecondary,
                    fontSize = 12.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatOfferRelativeTime(card.offer.offeredAt), color = WheelColors.textMuted, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                CountdownLabel(secondsLeft)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onDecline,
                enabled = !busy && !expired,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, WheelColors.borderStrong),
                colors = OutlinedButtonDefaults.outlinedButtonColors(contentColor = WheelColors.textSecondary),
            ) { Text("Decline", fontSize = 13.sp) }
            Button(
                onClick = onAccept,
                enabled = !busy && !expired,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WheelColors.gold, contentColor = CabIndigoOnGold),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CabIndigoOnGold)
                } else {
                    Text("Accept", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CountdownLabel(secondsLeft: Long?) {
    if (secondsLeft == null) return
    val expired = secondsLeft <= 0
    val text = if (expired) "Expired" else "Expires in ${secondsLeft}s"
    val color = when {
        expired -> WheelColors.duress
        secondsLeft <= 8 -> WheelColors.waiting
        else -> WheelColors.textMuted
    }
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

/** #2A1C58 — matches the prototype's `.pay-btn-primary{ color:#2A1C58 }` gold-button text color;
 * kept as its own constant rather than reusing [WheelColors.surfaceRaised] since the coincidence
 * (same hex) is about that specific button-text choice, not a semantic link to the "raised
 * surface" token — same rationale
 * [au.com.threesixty.cabdispatch.ui.screens.messages.MessageThreadScreen]'s identical constant
 * documents. */
private val CabIndigoOnGold = Color(0xFF2A1C58)
