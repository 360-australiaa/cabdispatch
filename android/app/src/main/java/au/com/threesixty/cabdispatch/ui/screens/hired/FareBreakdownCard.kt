package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.FareBreakdown
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.hudSpring
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * The running fare breakdown card and the trip-details card beside it: the itemised rows,
 * their colour dots and timeline rows, and the negotiated-total formatting.
 *
 * Extracted from HiredScreen.kt in Phase 0 (P0.4) — a mechanical move, same package, same
 * declarations, no behaviour change. Presentation only — every figure shown here is
 * computed by the fare engine, which this file does not touch.
 */

/** `negotiatedTotal` is a decimal-as-string (this project's money-field convention, see
 * `ApiService.kt`'s header note) — reused here as plain display text, never re-parsed into a new
 * fare calculation. Falls back to the raw string (prefixed) on a malformed value rather than
 * crashing a dialog over a display nicety. */
internal fun formatNegotiatedTotal(raw: String): String =
    runCatching { BigDecimal(raw).toMoneyString() }.getOrDefault("$$raw")

// ============================================================================================
// Fare Breakdown card (HiredViewModel.breakdownExpanded / toggleBreakdown())
// ============================================================================================

@Composable
internal fun FareBreakdownCard(
    title: String,
    breakdown: FareBreakdown,
    timeClass: TimeClass,
    nightMultiplierLabel: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    /**
     * "Set Price" fix (product-reported, 2026-09) — non-null only for a fixed-fare trip
     * ([FareState.negotiatedTotal], threaded straight through). When set, the dial's ACTIVE FARE
     * figure is this amount plus tolls/PSL/extras (see [FareState.total]'s doc), NOT the sum of the
     * "Base fare"/"Distance"/"Time" rows below — those three keep showing the real metered accrual
     * for reference/compliance evidence (the meter genuinely keeps running, per the product
     * requirement), so without this parameter the card's itemisation would silently stop summing
     * to what the dial shows, with no explanation. The extra row this adds is the fix.
     */
    negotiatedTotal: BigDecimal? = null,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(title)
                Spacer(Modifier.weight(1f))
                val chevronRotation by animateFloatAsStateHud(if (expanded) 90f else -90f)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, CaptainPalette.hudAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (expanded) "HIDE" else "SHOW",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = CaptainPalette.hudSweepMid,
                    )
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = CaptainPalette.hudSweepMid,
                        modifier = Modifier.size(16.dp).padding(start = 2.dp).rotate(chevronRotation),
                    )
                }
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (negotiatedTotal != null) {
                        BreakdownRow("Fixed price (agreed)", negotiatedTotal.toMoneyString(), CaptainPalette.hudAccent)
                    }
                    // Base fare/Distance/Time keep showing the real metered accrual even on a
                    // fixed-price trip — "reference only" (not what's charged; the row above is)
                    // once negotiatedTotal is set, but still real numbers: the meter genuinely
                    // keeps running for the trip record/compliance evidence, per the product
                    // requirement — never frozen or hidden just because the price is fixed.
                    BreakdownRow(
                        if (negotiatedTotal != null) "Base fare (metered, reference)" else "Base fare",
                        breakdown.flagFall.toMoneyString(),
                        if (negotiatedTotal != null) CaptainPalette.textMuted else CaptainPalette.success,
                    )
                    BreakdownRow("Distance", breakdown.distanceAmount.toMoneyString(), CaptainPalette.success)
                    BreakdownRow("Time", breakdown.waitingAmount.toMoneyString(), CaptainPalette.success)
                    // Informational only: the night-rate uplift is already baked into Distance/Time
                    // above (FareEngineImpl applies the night per-km/per-min rate directly — there is
                    // no separate night-surcharge line item to show), so this never adds to `total`
                    // itself, only explains the higher Distance/Time figures when it applies.
                    if (timeClass == TimeClass.NIGHT) {
                        BreakdownRow("Night fare (${nightMultiplierLabel ?: "—"})", "included", CaptainPalette.hudSweepMid)
                    }
                    if (breakdown.peakAmount.signum() > 0) {
                        BreakdownRow("Peak hiring", breakdown.peakAmount.toMoneyString(), CaptainPalette.hudSweepMid)
                    }
                    BreakdownRow("Tolls", breakdown.tolls.toMoneyString(), CaptainPalette.warning)
                    BreakdownRow("Levy & charges", breakdown.psl.toMoneyString(), CaptainPalette.danger)
                    if (breakdown.extras.signum() > 0) {
                        BreakdownRow("Extras", breakdown.extras.toMoneyString(), CaptainPalette.warning)
                    }
                }
            }
            // No TOTAL row, deliberately: the dial's ACTIVE FARE figure IS the total; this card
            // owns the itemisation that makes it up.
        }
    }
}

/** The kit's one spring for a small UI transition (the HIDE/SHOW chevron). */
@Composable
private fun animateFloatAsStateHud(target: Float) =
    animateFloatAsState(target, animationSpec = hudSpring(), label = "hud-chevron")

@Composable
private fun BreakdownRow(label: String, value: String, dotColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(dotColor, 8.dp)
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}

/** Coloured bullet with a soft same-colour halo ring. */
@Composable
private fun ColorDot(color: Color, size: Dp) {
    Box(
        modifier = Modifier
            .size(size + 6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

// ============================================================================================
// Trip Details card (vertical pickup → drop-off timeline) — mockup #3 only
// ============================================================================================

/**
 * [startAtIso] is the persisted `TripEntity.startAt` (real open time) — `null` until Room has the
 * row, rendering "—". Drop-off time is always "—" here: the trip is in progress. Addresses are
 * `TripContext.originAddress`/`.destAddress` — "—" when absent (see that doc), never fabricated.
 * DISTANCE and DURATION live on the dial (its own readouts); AVG SPEED is genuinely only here.
 */
@Composable
internal fun TripDetailsCard(tripContext: TripContext?, fareState: FareState, startAtIso: String?) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("TRIP DETAILS")
                Spacer(Modifier.weight(1f))
                Text(
                    tripContext?.clientUuid?.take(8)?.uppercase() ?: "—",
                    fontFamily = RobotoMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            TimelineRow(
                dotColor = CaptainPalette.success,
                title = "PICKUP",
                address = tripContext?.originAddress ?: "—",
                time = startAtIso?.asLocalTime() ?: "—",
                connector = true,
            )
            TimelineRow(
                dotColor = CaptainPalette.danger,
                title = "DROP-OFF",
                address = tripContext?.destAddress ?: "—",
                time = "—",
                connector = false,
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
            Spacer(Modifier.height(10.dp))
            val avgSpeedKmh = if (fareState.movingSeconds > 0) {
                (fareState.distanceKm.toDouble() / (fareState.movingSeconds / 3600.0)).roundToInt()
            } else {
                0
            }
            Column {
                Text("AVG SPEED", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
                Text("$avgSpeedKmh km/h", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CaptainPalette.textPrimary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun TimelineRow(dotColor: Color, title: String, address: String, time: String, connector: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(16.dp)) {
            ColorDot(dotColor, 10.dp)
            if (connector) {
                Box(Modifier.padding(vertical = 3.dp).width(2.dp).height(20.dp).background(CaptainPalette.panelBorder))
            }
        }
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = dotColor)
                Text(time, fontFamily = RobotoMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = CaptainPalette.textSecondary)
            }
            Text(
                address,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = CaptainPalette.textPrimary,
                maxLines = 2,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
