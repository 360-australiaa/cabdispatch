package au.com.threesixty.cabdispatch.ui.wheel.content

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.JobOfferHandoff
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.WheelColorsV2

/**
 * Wheel slot 1 — "Available Trips" content pane (spec §4: "list of job cards — route, distance,
 * estimated fare, time since requested"; §8 row 11 "Job queue list", Figma-only — this pass ports
 * the list-row visuals from the reference prototype's `.list-row`/`row()` helper, the same as
 * every other list-shaped wheel slot, since §4 explicitly describes Available Trips using that
 * shape even though the HTML prototype itself never rendered this specific slot's data).
 *
 * Phase B v2 reskin (2026-08-26 dock-menu pass, Figma fileKey `JhEhok3n9bntRNS5Y1u3Yc` node
 * `34:328`): row visuals restyled to the v2 glass-row + green ACCEPT pill look (matching Statistics/
 * Plot/My Trips' shared row language, [WheelColorsV2]); every state field/action below
 * ([AvailableTripsWheelViewModel], accept/decline, offer countdown, live-offer WS feed) is
 * unchanged.
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
        val actionError = state.actionError
        if (actionError != null) {
            Text(
                actionError,
                color = WheelColorsV2.dangerText,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        val error = state.error
        when {
            state.loading && state.cards.isEmpty() -> CircularProgressIndicator(color = WheelColorsV2.amberFigure)
            error != null -> Text(error, color = WheelColorsV2.dangerText, fontSize = 14.sp)
            state.cards.isEmpty() -> Text(
                "No job offers right now. New offers appear here automatically.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
            )
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
            .background(WheelColorsV2.rowGlassStrong, RoundedCornerShape(16.dp))
            .border(1.dp, WheelColorsV2.rowBorder, RoundedCornerShape(16.dp))
            .clickable(enabled = !expired, onClick = onOpenDetail)
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "#${card.job.id.takeLast(4)}",
                color = WheelColorsV2.mutedFigure,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                "${card.job.originAddress} → ${card.job.destAddress}",
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Est. $${card.job.fareEstimateLow}–${card.job.fareEstimateHigh}",
                color = WheelColorsV2.amberFigure,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                formatOfferRelativeTime(card.offer.offeredAt),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
            CountdownLabel(secondsLeft, modifier = Modifier.weight(1f))
            SmallOutlineButton(label = "Decline", enabled = !busy && !expired, onClick = onDecline)
            AcceptButton(busy = busy, enabled = !busy && !expired, onClick = onAccept)
        }
    }
}

@Composable
private fun SmallOutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.75f * alpha), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AcceptButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) WheelColorsV2.greenCtaBrush else WheelColorsV2.steelTileBrush)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = WheelColorsV2.onGreenCta)
        } else {
            Text(
                "ACCEPT",
                color = if (enabled) WheelColorsV2.onGreenCta else WheelColorsV2.steelTileText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CountdownLabel(secondsLeft: Long?, modifier: Modifier = Modifier) {
    if (secondsLeft == null) {
        Spacer(modifier)
        return
    }
    val expired = secondsLeft <= 0
    val text = if (expired) "Expired" else "expires ${secondsLeft}s"
    val color = when {
        expired -> WheelColorsV2.dangerText
        secondsLeft <= 8 -> WheelColorsV2.dangerText
        else -> Color.White.copy(alpha = 0.5f)
    }
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}
