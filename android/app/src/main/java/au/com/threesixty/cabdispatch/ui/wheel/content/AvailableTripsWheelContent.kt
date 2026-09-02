package au.com.threesixty.cabdispatch.ui.wheel.content

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.JobOfferHandoff
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Wheel slot 1 — "Available Trips" content pane (spec §4: "list of job cards — route, distance,
 * estimated fare, time since requested"; §8 row 11 "Job queue list", Figma-only — this pass ports
 * the list-row visuals from the reference prototype's `.list-row`/`row()` helper, the same as
 * every other list-shaped wheel slot, since §4 explicitly describes Available Trips using that
 * shape even though the HTML prototype itself never rendered this specific slot's data).
 *
 * **2026-08-29 Captain Taxis reskin:** this pane is embedded inside
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s purple `PaneShell` chrome
 * (see [au.com.threesixty.cabdispatch.ui.theme.CaptainWidgets]) — row visuals moved off the
 * previous v2 glass-row + green ACCEPT pill look (the legacy glass/gold palette used elsewhere in
 * `ui/theme`) onto [CaptainPalette] so this pane's own content doesn't clash with the purple panel
 * wrapped around it. Visual-only: every state field/action below ([AvailableTripsWheelViewModel],
 * accept/decline, offer countdown, live-offer WS feed) is unchanged.
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = CaptainPalette.danger, modifier = Modifier.size(16.dp))
                Text(
                    actionError,
                    color = CaptainPalette.danger,
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        val error = state.error
        when {
            state.loading && state.cards.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = CaptainPalette.accent)
            }
            error != null -> Text(error, color = CaptainPalette.danger, fontFamily = InterFamily, fontSize = 15.sp)
            state.cards.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                Icon(Icons.Rounded.Inbox, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(20.dp))
                Text(
                    "No job offers right now. New offers appear here automatically.",
                    color = CaptainPalette.textSecondary,
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
            .clip(RoundedCornerShape(16.dp))
            .background(CaptainPalette.raised)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(16.dp))
            .clickable(enabled = !expired, onClick = onOpenDetail)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "#${card.job.id.takeLast(4)}",
                color = CaptainPalette.textMuted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                "${card.job.originAddress} → ${card.job.destAddress}",
                color = CaptainPalette.textPrimary,
                fontFamily = InterFamily,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Est. $${card.job.fareEstimateLow}–${card.job.fareEstimateHigh}",
                color = CaptainPalette.warning,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(15.dp))
            Text(
                formatOfferRelativeTime(card.offer.offeredAt),
                color = CaptainPalette.textMuted,
                fontFamily = InterFamily,
                fontSize = 13.sp,
            )
            CountdownLabel(secondsLeft, modifier = Modifier.weight(1f))
            SmallOutlineButton(label = "Decline", enabled = !busy && !expired, onClick = onDecline)
            AcceptButton(busy = busy, enabled = !busy && !expired, onClick = onAccept)
        }
    }
}

@Composable
private fun SmallOutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, animationSpec = tween(120), label = "decline-press")
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .scale(scale)
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Close, contentDescription = null, tint = CaptainPalette.textSecondary.copy(alpha = alpha), modifier = Modifier.size(18.dp))
        Text(
            label,
            color = CaptainPalette.textSecondary.copy(alpha = alpha),
            fontFamily = InterFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun AcceptButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, animationSpec = tween(120), label = "accept-press")
    Row(
        modifier = Modifier
            .scale(scale)
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) CaptainPalette.success else CaptainPalette.dialNeutral)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CaptainPalette.bg)
        } else {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = if (enabled) CaptainPalette.bg else CaptainPalette.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "ACCEPT",
                color = if (enabled) CaptainPalette.bg else CaptainPalette.textMuted,
                fontFamily = InterFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp),
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
        expired -> CaptainPalette.danger
        secondsLeft <= 8 -> CaptainPalette.danger
        else -> CaptainPalette.textMuted
    }
    Text(text, color = color, fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}
