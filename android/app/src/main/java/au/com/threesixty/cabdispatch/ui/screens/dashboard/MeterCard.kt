package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen
import androidx.compose.animation.core.spring
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.GlowingMeterGauge
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.hudSpring
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import java.math.RoundingMode

/**
 * The home screen's meter card: the night-fare tile, the circular meter dial, the two
 * quick-action tiles and the start-meter button, with the tariff-derived night-multiplier
 * helpers they read.
 *
 * Extracted from DeckHomeScreen.kt in Phase 0 (P0.4) — a mechanical move, same package,
 * same declarations, no behaviour change. The hardcoded sizes here are measured against
 * the fixed 1280x800dp design canvas; their original comments travel with them.
 */

// ============================================================================================
// Meter card (Figma left card — night fare tile, dial, Set Price / Vouchers quick actions)
// ============================================================================================

@Composable
internal fun MeterCard(
    state: WheelDashboardUiState,
    meterPhase: MeterStartPhase,
    // Real bug fixed (2026-09-02): the SET PRICE tile's subtitle used to be an unconditional
    // hardcoded "Fixed Fare · ACTIVE" literal regardless of actual state. `negotiatedTotal` is
    // `SessionHolder.pendingTrip.value?.negotiatedTotal`, collected by the caller — non-null only
    // when the driver actually used the Set Price flow for the trip about to start.
    negotiatedTotal: String?,
    onStartMeter: () -> Unit,
    onCancelStart: () -> Unit,
    onSetPrice: () -> Unit,
    onVouchers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixedFareActive = negotiatedTotal != null
    // Glassmorphism pass (2026-09-07, design brief item 3): the single most prominent dashboard
    // container — was its own one-off gradient-fill + solid-border panel; now the shared GlassCard
    // (see Hud.kt's own doc) so the meter panel matches Live Dispatch/Shift Time/Trips/Earnings
    // instead of being the one card left on the old flat-panel look.
    GlassCard(modifier = modifier, cornerRadiusDp = 18, glow = CaptainPalette.hudAccent) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        NightFareTile(tariff = state.tariff, modifier = Modifier.align(Alignment.TopStart))
        MeterDial(
            meterPhase = meterPhase,
            enabled = state.tariff != null,
            onStartMeter = onStartMeter,
            onCancelStart = onCancelStart,
            modifier = Modifier.align(Alignment.Center),
        )
        Column(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QuickActionTile(
                icon = Icons.Rounded.Sell,
                title = "SET PRICE",
                subtitle = if (fixedFareActive) "Fixed Fare · ACTIVE" else "Tap to set a price",
                subtitleColor = if (fixedFareActive) CaptainPalette.success else CaptainPalette.textSecondary,
                onClick = onSetPrice,
            )
            QuickActionTile(
                icon = Icons.Rounded.ConfirmationNumber,
                title = "VOUCHERS",
                subtitle = "Redeemed at payment",
                subtitleColor = CaptainPalette.textSecondary,
                onClick = onVouchers,
            )
        }
    }
    }
}

/** Real night-rate uplift and window, not Figma's mock "1.25× / 10PM–6AM" — see this file's class
 * doc. `null` tariff (not yet signed/cached) hides the numeric ratio rather than showing a bogus
 * one. */
@Composable
internal fun NightFareTile(tariff: au.com.threesixty.cabdispatch.data.remote.TariffDto?, modifier: Modifier = Modifier) {
    // 172dp -> 156dp (2026-08-29): pulled back slightly so this corner tile clears the also-bigger
    // meter dial behind it — see MeterCard's width comment. The window text now wraps to two
    // lines at this width, which is fine (the Column isn't height-constrained) — a real fix, not
    // a cosmetic call, since the alternative (172dp) visibly overlapped the dial's ring on-device.
    Column(
        modifier = modifier
            .width(156.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(18.dp))
            Text("NIGHT FARE", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textSecondary, modifier = Modifier.padding(start = 7.dp))
        }
        Text(
            nightMultiplierLabel(tariff),
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.padding(top = 6.dp),
        )
        // The backend's actual, authoritative night-rate window (confirmed directly by the
        // backend/architecture agent, 2026-08-29 contract Part 2.3/6): "10pm-6am ... hardcoded
        // server-side in TimeClass.NIGHT" and safe to display as-is since it's informational only
        // — the server enforces the real boundary at trip-tick/close time regardless. NOTE for the
        // record: this app's OWN local FareEngine.kt (used only for HiredScreen's live-ticking
        // display, a screen outside this pass's 3-screen scope) currently classifies night as
        // 8pm-6am, not 10pm-6am — a real discrepancy this pass found but does NOT fix here (fixing
        // the live meter's day/night boundary is a money-calculation change to a different screen,
        // out of this pass's mandate — flagged in the delivery notes instead).
        Text(
            "10:00 PM – 6:00 AM",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

internal fun nightMultiplierLabel(tariff: au.com.threesixty.cabdispatch.data.remote.TariffDto?): String {
    val t = tariff ?: return "—"
    val day = t.distRate1.toBigDecimalOrNull() ?: return "—"
    val night = t.nightRate1.toBigDecimalOrNull() ?: return "—"
    if (day.signum() <= 0) return "—"
    val ratio = night.divide(day, 2, RoundingMode.HALF_UP)
    return "${ratio}×"
}

private fun String.toBigDecimalOrNull(): java.math.BigDecimal? = runCatching { java.math.BigDecimal(this) }.getOrNull()

@Composable
internal fun QuickActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color,
    onClick: () -> Unit,
) {
    Column(
        // 126x118 -> 156x172 (2026-08-29, revised after a real on-device check): width pulled back
        // to clear the dial (see MeterCard's width comment); height grown MORE than first tried —
        // the first pass's 144dp visibly clipped the subtitle line, confirmed live, not assumed.
        // Press feedback moved onto the shared gameClick spring/glow (game-feel pass).
        modifier = Modifier
            .width(156.dp)
            .height(172.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .gameClick(onClick = onClick, shape = RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(CaptainPalette.raised, CaptainPalette.cardBottom)))
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
        Text(subtitle, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = subtitleColor, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * The circular meter gauge — the "Holographic Central Meter" (2026-09-07 futuristic-HUD reskin,
 * design brief item 5). Was its own bespoke [Canvas] with a continuously-rotating sweep highlight
 * and an idle breathing loop (`rememberInfiniteTransition`s on a 1.4-7s clock, plus five "spark"
 * points orbiting with it) — precisely the always-running, positionally-moving decorative pattern
 * that caused real user distress the one other time this app tried it on a meter dial (see
 * [au.com.threesixty.cabdispatch.ui.theme.GlowingSpeedometer]'s own `motion` doc for the reverted
 * "moving circle... pain in my head" incident and the ONE approved motion pattern since). Rebuilt
 * on the shared [GlowingMeterGauge] (full 360° circle: `sweepDeg = 360f, startDeg = -90f`, matching
 * this dial's original full-ring look) instead, which:
 * - draws the brief's holographic bezel — dashed cyan→purple gradient ring + secondary blurred
 *   glow + static scattered particle dots (see [au.com.threesixty.cabdispatch.ui.theme.drawHoloRing]) —
 *   for free, shared with every other gauge in the kit rather than a second bespoke implementation;
 * - replaces the rotating sweep/orbiting sparks with [GlowingMeterGauge]'s own progress arc, driven
 *   by real [meterPhase] state (`0f` idle, `1f` starting) through one settling [hudSpring] — a
 *   value-driven transition, not a clock;
 * - replaces the idle "breathing" alpha loop and the icon's continuous pulse with a single one-shot
 *   spring on the car icon's scale, tied to [starting] the same way — settles once and holds, never
 *   loops.
 *
 * No call site needs to change: same four parameters, same [Column] of METER STATUS / OFF-STARTING
 * label / sub-copy / Start-or-Cancel button underneath.
 */
@Composable
internal fun MeterDial(
    meterPhase: MeterStartPhase,
    enabled: Boolean,
    onStartMeter: () -> Unit,
    onCancelStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val starting = meterPhase is MeterStartPhase.Starting
    // Icon scale settles to a slightly larger size the moment Start Meter is in flight, then holds
    // — a real state-driven spring (see class doc), not a continuous pulse.
    val iconScale by animateFloatAsState(if (starting) 1.15f else 1f, animationSpec = hudSpring(), label = "meter-icon-scale")

    // 398dp -> 414dp (2026-08-29 prominence pass, kept): bigger than the original, but pulled back
    // from an earlier 430dp pass that visibly collided with the (also-bigger) corner tiles — see
    // MeterCard's own width comment; this size was picked by measuring the real overlap live.
    GlowingMeterGauge(
        progress = if (starting) 1f else 0f,
        modifier = modifier.size(414.dp),
        strokeWidthDp = 14,
        sweepDeg = 360f,
        startDeg = -90f,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.DirectionsCar,
                contentDescription = null,
                tint = CaptainPalette.hudAccent,
                modifier = Modifier.size(36.dp).scale(iconScale),
            )
            Text(
                "METER STATUS",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 0.5.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            val (label, sub) = when (meterPhase) {
                MeterStartPhase.Idle -> "OFF" to "Tap to start a new fare"
                MeterStartPhase.Starting -> "STARTING" to "Starting meter…"
                is MeterStartPhase.Failed -> "OFF" to meterPhase.message
            }
            // 62sp -> 76sp: the single biggest number on the screen, on purpose — an older driver
            // glancing over should never have to squint to know whether the meter is running. Bold
            // stark white (design brief item 6: primary data points in "a bold, stark white
            // sans-serif font") via CaptainPalette.textPrimary.
            AnimatedContent(
                targetState = label,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)).togetherWith(fadeOut() + scaleOut(targetScale = 1.1f)) },
                label = "meter-label",
            ) { animatedLabel ->
                Text(
                    animatedLabel,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 76.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                sub,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                color = if (meterPhase is MeterStartPhase.Failed) CaptainPalette.danger else CaptainPalette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).width(240.dp),
            )
            Spacer(Modifier.height(28.dp))
            // Primary action on the whole screen — widened and heightened well past the standard
            // button size (184x54 -> 240x76) so it reads as unmistakably THE thing to press.
            if (meterPhase is MeterStartPhase.Starting) {
                CaptainButton(text = "CANCEL", outline = true, widthDp = 240, heightDp = 76, fontSize = 22.sp, onClick = onCancelStart)
            } else {
                StartMeterButton(widthDp = 240, heightDp = 76, fontSize = 22.sp, enabled = enabled, onClick = onStartMeter)
            }
        }
    }
}

/**
 * The primary "Start Meter" CTA (2026-09-07 futuristic-HUD reskin, design brief item 4): "a
 * vibrant, horizontal gradient background that transitions from neon cyan to royal purple... a
 * custom drop shadow or glowing modifier with a large blur radius using the cyan hex code to
 * simulate a neon light emitting from the button." A dedicated composable rather than a new
 * [CaptainButton] parameter — every OTHER CaptainButton in the app (CANCEL here, ACCEPT on a
 * dispatch offer, every dialog confirm) keeps its existing flat [CaptainPalette.primary] fill; this
 * treatment is for the one button the brief singles out by name, not a global button restyle.
 * Static glow (`neonGlow` at a wide [spread] — see that modifier's own doc for the no-RenderEffect,
 * SM-T575 frame-budget reasoning): a fixed alpha, not a pulse — no new decorative loop.
 */
@Composable
private fun StartMeterButton(
    widthDp: Int,
    heightDp: Int,
    fontSize: androidx.compose.ui.unit.TextUnit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            .neonGlow(CaptainPalette.neonCyan, 16.dp, strength = if (enabled) 1f else 0.25f, spread = 14.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(CaptainPalette.neonCyan, CaptainPalette.hudAccent)))
            .alpha(if (enabled) 1f else 0.45f)
            .gameClick(onClick = onClick, shape = shape, glowColor = CaptainPalette.neonCyan, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Text("▶  START METER", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = fontSize, color = CaptainPalette.onAccent)
    }
}
