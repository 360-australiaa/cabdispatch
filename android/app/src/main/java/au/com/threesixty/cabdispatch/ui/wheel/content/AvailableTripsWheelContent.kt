package au.com.threesixty.cabdispatch.ui.wheel.content

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.JobOfferHandoff
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

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
 * **Premium pass (2026-09-03, plan Phase 4).** An arriving offer is the single most time-critical
 * event in this app and it looked like a grey list row. It is now an *event*: the card is a
 * gradient [CaptainPanel] surface ringed by an urgency-coloured `neonGlow` that breathes faster as
 * the offer ages, the countdown is a real drain bar plus a large monospaced seconds readout that
 * escalates green -> amber -> red, and ACCEPT is a wide, glowing, gradient-filled primary target
 * (the widest thing on the card) with DECLINE demoted to a quiet outline. Strictly visual: the
 * offer window, accept/decline calls, expiry rule and every displayed value are exactly what they
 * were — the urgency fraction is computed from the `offered_at`/`expires_at` pair the card already
 * carries, not from anything new.
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
            state.cards.isEmpty() -> EmptyOfferState()
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
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

/**
 * The "waiting for work" state. Same single sentence as before — given a real surface and a slow
 * breathing radar dot so an idle-but-listening pane doesn't read as a broken/blank one. The dot's
 * motion says "still listening"; it is not a claim about any offer.
 */
@Composable
private fun EmptyOfferState() {
    val breath by rememberInfiniteFloat(enabled = true, from = 0.2f, to = 0.6f, durationMs = 2200)
    CaptainPanel(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .neonGlow(CaptainPalette.accent, 99.dp, strength = breath, spread = 5.dp)
                    .clip(CircleShape)
                    .background(CaptainPalette.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Inbox, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(18.dp))
            }
            Text(
                "No job offers right now. New offers appear here automatically.",
                color = CaptainPalette.textSecondary,
                fontFamily = InterFamily,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

/**
 * Urgency tier for one live offer. Deliberately a *tier*, not a continuous value: the card's
 * breathing glow keys its period off this, and a period that changed every tick would restart the
 * infinite transition once a second. Thresholds keep the previous code's own "<= 8s is red" rule
 * as [CRITICAL] and add one intermediate step.
 */
private enum class OfferUrgency(val color: Color, val pulseMs: Int) {
    CALM(CaptainPalette.success, 1500),
    WARN(CaptainPalette.warning, 900),
    CRITICAL(CaptainPalette.danger, 460),
    GONE(CaptainPalette.textMuted, 0),
}

private fun urgencyOf(secondsLeft: Long?): OfferUrgency = when {
    secondsLeft == null -> OfferUrgency.CALM
    secondsLeft <= 0L -> OfferUrgency.GONE
    secondsLeft <= 8L -> OfferUrgency.CRITICAL
    secondsLeft <= 14L -> OfferUrgency.WARN
    else -> OfferUrgency.CALM
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
    val urgency = urgencyOf(secondsLeft)
    val live = !expired
    val breath by rememberInfiniteFloat(
        enabled = live && urgency != OfferUrgency.GONE,
        from = if (urgency == OfferUrgency.CRITICAL) 0.45f else 0.25f,
        to = 1f,
        durationMs = if (urgency.pulseMs > 0) urgency.pulseMs else 1500,
    )
    // Fraction of the offer's own window still remaining. Both ends come off the offer the card
    // already holds (`offered_at` / `expires_at`) — no new data, just a second reading of the same
    // countdown the label shows. Unparseable timestamps => no bar (never a guessed one).
    val window = remember(card.offer.offeredAt, card.offer.expiresAt) {
        offerWindowSeconds(card.offer.offeredAt, card.offer.expiresAt)
    }
    val remainingFraction = if (window != null && secondsLeft != null) {
        (secondsLeft!!.toFloat() / window.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

    CaptainPanel(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (live) Modifier.neonGlow(urgency.color, 18.dp, strength = breath, spread = 6.dp) else Modifier)
            .gameClick(
                onClick = onOpenDetail,
                shape = RoundedCornerShape(18.dp),
                glowColor = urgency.color,
                enabled = !expired,
            ),
        cornerRadiusDp = 18,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            urgency.color.copy(alpha = if (live) 0.55f else 0.2f),
                            CaptainPalette.panelBorder,
                        ),
                    ),
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            // --- line 1: job ref · route · estimated fare -------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CaptainPalette.inset)
                        .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        "#${card.job.id.takeLast(4)}",
                        color = CaptainPalette.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        card.job.originAddress,
                        color = CaptainPalette.textPrimary,
                        fontFamily = InterFamily,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        Icons.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = CaptainPalette.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        card.job.destAddress,
                        color = CaptainPalette.textSecondary,
                        fontFamily = InterFamily,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "EST. FARE",
                        color = CaptainPalette.textMuted,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        "$${card.job.fareEstimateLow}–${card.job.fareEstimateHigh}",
                        color = CaptainPalette.warning,
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                }
            }

            // --- line 2: the urgency strip ----------------------------------------------------
            Spacer(Modifier.height(14.dp))
            OfferUrgencyStrip(
                secondsLeft = secondsLeft,
                remainingFraction = remainingFraction,
                urgency = urgency,
                pulse = breath,
                offeredAt = card.offer.offeredAt,
            )

            // --- line 3: the actions ----------------------------------------------------------
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallOutlineButton(label = "Decline", enabled = !busy && !expired, onClick = onDecline)
                AcceptButton(
                    busy = busy,
                    enabled = !busy && !expired,
                    pulse = breath,
                    modifier = Modifier.weight(1f),
                    onClick = onAccept,
                )
            }
        }
    }
}

/**
 * The countdown, given the weight it deserves: a draining bar plus a large seconds readout that
 * escalates through [OfferUrgency]'s colours. Both encode the same single number the previous
 * "expires 12s" label showed — [rememberOfferCountdown] over the offer's own `expires_at`.
 */
@Composable
private fun OfferUrgencyStrip(
    secondsLeft: Long?,
    remainingFraction: Float?,
    urgency: OfferUrgency,
    pulse: Float,
    offeredAt: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(14.dp))
            Text(
                formatOfferRelativeTime(offeredAt),
                color = CaptainPalette.textMuted,
                fontFamily = InterFamily,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (secondsLeft != null) {
                if (urgency == OfferUrgency.GONE) {
                    Text(
                        "EXPIRED",
                        color = CaptainPalette.textMuted,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 0.6.sp,
                    )
                } else {
                    Text(
                        "EXPIRES IN",
                        color = CaptainPalette.textMuted,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Text(
                        "${secondsLeft}s",
                        color = urgency.color,
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                    )
                }
            }
        }
        if (remainingFraction != null) {
            Spacer(Modifier.height(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(7.dp)) {
                val r = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(color = CaptainPalette.inset, cornerRadius = r)
                val w = size.width * remainingFraction
                if (w > 0f) {
                    // A soft same-colour halo pass under the bar, then the bar itself — the same
                    // "stack alpha passes instead of a real blur" trick the meter dial uses.
                    drawRoundRect(
                        color = urgency.color.copy(alpha = 0.30f * pulse),
                        topLeft = Offset(-2f, -3f),
                        size = Size(w + 4f, size.height + 6f),
                        cornerRadius = CornerRadius(r.x + 3f, r.y + 3f),
                    )
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(urgency.color.copy(alpha = 0.65f), urgency.color),
                            startX = 0f,
                            endX = w,
                        ),
                        size = Size(w, size.height),
                        cornerRadius = r,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallOutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, CaptainPalette.panelBorder, RoundedCornerShape(16.dp))
            .gameClick(
                onClick = onClick,
                shape = RoundedCornerShape(16.dp),
                glowColor = CaptainPalette.danger,
                enabled = enabled,
            )
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Close, contentDescription = null, tint = CaptainPalette.textSecondary.copy(alpha = alpha), modifier = Modifier.size(18.dp))
        Text(
            label,
            color = CaptainPalette.textSecondary.copy(alpha = alpha),
            fontFamily = InterFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * The primary target. Widest control on the card, 64dp tall, gradient-filled and wearing a live
 * green glow that breathes in time with the card's urgency — so "accept" is unmistakably the thing
 * to hit and DECLINE reads as the quiet alternative. Disabled/expired collapses to the flat
 * neutral fill with no glow, so a dead offer never looks pressable.
 */
@Composable
private fun AcceptButton(
    busy: Boolean,
    enabled: Boolean,
    pulse: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .height(64.dp)
            .then(if (enabled) Modifier.neonGlow(CaptainPalette.success, 16.dp, strength = 0.35f + 0.65f * pulse, spread = 6.dp) else Modifier)
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(CaptainPalette.success, CaptainPalette.success.copy(alpha = 0.78f)),
                        ),
                    )
                } else {
                    Modifier.background(CaptainPalette.dialNeutral)
                },
            )
            .gameClick(onClick = onClick, shape = shape, glowColor = CaptainPalette.success, enabled = enabled)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CaptainPalette.bg)
        } else {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = if (enabled) CaptainPalette.bg else CaptainPalette.textMuted,
                modifier = Modifier.size(22.dp),
            )
            Text(
                "ACCEPT",
                color = if (enabled) CaptainPalette.bg else CaptainPalette.textMuted,
                fontFamily = InterFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Whole seconds an offer's window lasts (`expires_at` - `offered_at`), or `null` when either
 * timestamp doesn't parse. Local to this file rather than added to `AvailableTripsFormat.kt`
 * (whose own parser is private) — it exists purely so the countdown can also be drawn as a
 * fraction, and it reads only fields the card already carries.
 */
private fun offerWindowSeconds(offeredAt: String, expiresAt: String): Long? {
    val start = parseOfferTimestamp(offeredAt) ?: return null
    val end = parseOfferTimestamp(expiresAt) ?: return null
    return Duration.between(start, end).seconds.takeIf { it > 0L }
}

private fun parseOfferTimestamp(iso: String): Instant? =
    runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull()

/**
 * Soft outer glow around a rounded-rect surface — three expanding, fading rounded rects drawn
 * behind the content (cheap `drawBehind`, no blur/RenderEffect, per the SM-T575 frame budget).
 * Place BEFORE `.clip()`/`.background()` in the modifier chain so the glow lands outside the
 * surface's own bounds. [strength] 0..1 scales every layer's alpha (animate it for a pulse).
 *
 * NOTE: identical to `HiredScreen.kt`'s own `Modifier.neonGlow`, which is still `private` there.
 * The agreed Phase 4 enabler — one shared `neonGlow` promoted into `ui/theme/CaptainWidgets.kt` —
 * belongs to the workstream that owns that file and had not landed on this branch when this pass
 * ran, and this pass is scoped to three screen files only. Delete this local copy and import the
 * shared one the moment that promotion lands.
 */
private fun Modifier.neonGlow(color: Color, cornerRadius: Dp, strength: Float = 1f, spread: Dp = 5.dp): Modifier =
    drawBehind {
        if (strength <= 0.01f) return@drawBehind
        val step = spread.toPx()
        val r = cornerRadius.toPx()
        for (i in 3 downTo 1) {
            val inset = step * i
            drawRoundRect(
                color = color.copy(alpha = (0.22f / i) * strength),
                topLeft = Offset(-inset, -inset),
                size = Size(size.width + inset * 2, size.height + inset * 2),
                cornerRadius = CornerRadius(r + inset, r + inset),
            )
        }
    }
