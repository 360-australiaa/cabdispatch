package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import au.com.threesixty.cabdispatch.ui.theme.Radius
import au.com.threesixty.cabdispatch.ui.theme.Space
import au.com.threesixty.cabdispatch.ui.theme.Type
import au.com.threesixty.cabdispatch.domain.fare.AreaClass
import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.resolveTimeClassFor
import java.math.RoundingMode
import java.time.ZoneId
import java.time.ZonedDateTime

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
    // A fare is OPEN in Room right now (DeckHomeScreen's real observeActiveTrip read). Seen on
    // the tablet, 2026-09-08: after the process was killed mid-fare the app came back on the
    // dashboard with this card reading OFF / START METER while the header said HIRED and the
    // rail said FARE RUNNING. Two of three surfaces told the truth; the one with the biggest
    // button did not, and that button would have started a second fare on top of the first.
    hasActiveTrip: Boolean = false,
    onResumeMeter: () -> Unit = {},
) {
    val fixedFareActive = negotiatedTotal != null
    // Glassmorphism pass (2026-09-07, design brief item 3): the single most prominent dashboard
    // container — was its own one-off gradient-fill + solid-border panel; now the shared GlassCard
    // (see Hud.kt's own doc) so the meter panel matches Live Dispatch/Shift Time/Trips/Earnings
    // instead of being the one card left on the old flat-panel look.
    GlassCard(modifier = modifier, cornerRadiusDp = 18, glow = CaptainPalette.hudAccent) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Space.mlg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // THE COLLISION LAYOUT IS GONE (A3, 2026-09-08).
            //
            // What used to be here: a Box with NightFareTile pinned TopStart, a fixed .size(414.dp)
            // MeterDial pinned Center, and a Column of two 156x172dp QuickActionTiles pinned
            // CenterEnd - three absolutely-positioned children overlapping each other's bounding
            // boxes by ~53dp per side, surviving only because a circle does not reach the corners
            // of its square. Every one of those numbers carries a comment saying it was arrived at
            // by measuring the overlap on a physical tablet, and re-measuring it after each
            // change. That is not a layout; it is a standoff, and it is precisely why the card
            // could never shrink below 660dp.
            //
            // Replaced by the obvious thing: the dial gets the space that is left, and the three
            // secondary actions sit in an honest Row underneath it. The card is now 560dp (see the
            // call site), the dial sizes itself to fit whatever it is given, and nothing overlaps
            // anything, so nothing has to be re-measured on-device the next time a font changes.
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // Responsive, and this fixes a real latent bug (audit section 0). The dial was a
                // hardcoded .size(414.dp) inside a ~421dp-tall area - a 7dp margin. On a 16:9
                // tablet FixedDesignCanvas produces a 1280x720dp canvas rather than 1280x800, the
                // content pane drops to ~381dp, and 414dp of dial simply got clipped. Sizing from
                // the constraints we are actually given cannot clip, whatever the panel shape.
                val dialSize = minOf(maxWidth, maxHeight).coerceIn(MIN_DIAL_SIZE, MAX_DIAL_SIZE)
                MeterDial(
                    meterPhase = meterPhase,
                    enabled = state.tariff != null,
                    onStartMeter = onStartMeter,
                    onCancelStart = onCancelStart,
                    hasActiveTrip = hasActiveTrip,
                    onResumeMeter = onResumeMeter,
                    size = dialSize,
                )
            }
            Spacer(Modifier.height(Space.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.smd),
            ) {
                NightFareChip(tariff = state.tariff, modifier = Modifier.weight(1f))
                QuickActionChip(
                    icon = Icons.Rounded.Sell,
                    title = "SET PRICE",
                    subtitle = if (fixedFareActive) "Fixed Fare \u00b7 ACTIVE" else "Tap to set a price",
                    subtitleColor = if (fixedFareActive) CaptainPalette.success else CaptainPalette.textSecondary,
                    onClick = onSetPrice,
                    modifier = Modifier.weight(1f),
                )
                QuickActionChip(
                    icon = Icons.Rounded.ConfirmationNumber,
                    title = "VOUCHERS",
                    subtitle = "Redeemed at payment",
                    subtitleColor = CaptainPalette.textSecondary,
                    onClick = onVouchers,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The chip row's height (A3). 92dp comfortably carries an icon, a title and a subtitle line, and
 * is a generous touch target; `heightIn` rather than `height` so a 1.3x system font scale grows it
 * instead of clipping the subtitle - the exact failure the old 172dp fixed tile hit twice. */
private val CHIP_MIN_HEIGHT = 92.dp

/** The dial's responsive range (A3) - see [MeterCard]'s BoxWithConstraints for why it is a range
 * and not a number. 300dp is the smallest that keeps the 64sp status word legible at arm's length;
 * 380dp is where it starts crowding the chip row on a full-height 800dp canvas. */
private val MIN_DIAL_SIZE = 300.dp

private val MAX_DIAL_SIZE = 380.dp

/**
 * NIGHT FARE, as a chip in the row under the dial (A3 - was a 156dp tile absolutely positioned in
 * the card's top-left corner, overlapping the dial; see [MeterCard]).
 *
 * The multiplier is real and unchanged: [nightMultiplierLabel] derives it from the signed tariff's
 * own `nightRate1 / distRate1`, and a null tariff shows an honest dash rather than a plausible
 * invented "1.25x".
 *
 * THE WINDOW STRING IS NO LONGER A LITERAL. It used to be the hardcoded text "10:00 PM - 6:00 AM",
 * carrying a long comment recording that this app's own [FareEngine] disagreed with it (8pm vs
 * 10pm) - a real discrepancy that was found but deliberately not fixed at the time. That comment
 * is now **stale in the good direction**: the engine was corrected on 2026-08-29 (see
 * `resolveTimeClassFor`, which reads `hour >= 22 || hour < 6`) and the two now agree at 10pm.
 *
 * Rather than restate the literal and hope it keeps agreeing, this asks the engine
 * ([nightWindowLabel]). If anyone ever moves the boundary again - and a jurisdiction outside NSW
 * certainly will - the tile follows it, and cannot silently start lying to a driver about when
 * their rate goes up.
 */
@Composable
internal fun NightFareChip(tariff: au.com.threesixty.cabdispatch.data.remote.TariffDto?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = CHIP_MIN_HEIGHT)
            .clip(RoundedCornerShape(Radius.lg))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(Radius.lg))
            .padding(Space.smd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(16.dp))
            Text(
                "NIGHT FARE",
                style = Type.label,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(start = Space.xs),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = Space.xs)) {
            Text(
                nightMultiplierLabel(tariff),
                // 32sp -> Type.numeral (28sp). Still the chip's headline figure.
                style = Type.numeral,
                color = CaptainPalette.textPrimary,
            )
            Text(
                nightWindowLabel(),
                style = Type.tiny,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(start = Space.sm, bottom = 3.dp),
            )
        }
    }
}

/**
 * The night-rate window, **derived from the fare engine rather than written out** - see
 * [NightFareChip]'s doc for why that matters.
 *
 * HOW. `resolveTimeClassFor` is a pure top-level function taking an explicit [ZonedDateTime], so
 * the window can simply be *measured*: ask it, for each of the 24 hours of one ordinary reference
 * day, whether that hour is [TimeClass.NIGHT], then report the contiguous run. No new constant is
 * introduced anywhere and no engine file is edited (that file is another workstream's to own), yet
 * the string provably cannot drift from the code that actually bills the driver.
 *
 * The reference day is a plain midweek date evaluated as [AreaClass.URBAN] on purpose: urban has
 * no holiday distance rate at all, so this probe sees only the DAY/NIGHT boundary and can never be
 * perturbed by a Sunday or a gazetted public holiday.
 */
internal fun nightWindowLabel(): String {
    val reference = ZonedDateTime.of(2026, 1, 7, 0, 0, 0, 0, ZoneId.systemDefault()) // a Wednesday
    val nightHours = (0..23).filter {
        resolveTimeClassFor(reference.withHour(it), AreaClass.URBAN) == TimeClass.NIGHT
    }
    if (nightHours.isEmpty() || nightHours.size == 24) return ""
    // The night window wraps midnight, so the run is "the hours after the last daytime hour" -
    // start = the first hour whose predecessor is NOT night; end = the first hour that is not.
    val start = nightHours.first { (it - 1 + 24) % 24 !in nightHours }
    val end = ((nightHours.first { (it + 1) % 24 !in nightHours }) + 1) % 24
    return "${formatHourLabel(start)}\u2013${formatHourLabel(end)}"
}

/** "10pm" / "6am" / "12pm" - the compact form the chip has room for. */
private fun formatHourLabel(hour: Int): String {
    val suffix = if (hour < 12) "am" else "pm"
    val h = when {
        hour % 12 == 0 -> 12
        else -> hour % 12
    }
    return "$h$suffix"
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

/**
 * SET PRICE / VOUCHERS, as chips in the row under the dial (A3).
 *
 * Was `QuickActionTile`: a fixed 156x172dp tile, absolutely positioned over the dial's bounding
 * box. Both of its dimensions carry comments recording that they were set by measuring a real
 * collision on a real tablet, and that an earlier 144dp attempt "visibly clipped the subtitle
 * line, confirmed live". Sized by weight in a Row with a `heightIn` minimum, neither of those
 * failure modes is reachable: the chip takes a third of the row and grows if its text needs it.
 */
@Composable
internal fun QuickActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = CHIP_MIN_HEIGHT)
            .clip(RoundedCornerShape(Radius.lg))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(Radius.lg))
            .gameClick(onClick = onClick, shape = RoundedCornerShape(Radius.lg))
            .padding(Space.smd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm))
                .background(Brush.verticalGradient(listOf(CaptainPalette.raised, CaptainPalette.cardBottom)))
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(Radius.sm)),
            contentAlignment = Alignment.Center,
        ) {
            // A3 a11y pass: was contentDescription = null on a real, tappable control.
            Icon(icon, contentDescription = title, tint = CaptainPalette.accent, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.padding(start = Space.sm).weight(1f)) {
            Text(title, style = Type.h3, color = CaptainPalette.textPrimary, maxLines = 1)
            Text(
                subtitle,
                style = Type.tiny,
                color = subtitleColor,
                maxLines = 2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
    /** See [MeterCard]'s parameter of the same name: an open fare must never be offered START. */
    hasActiveTrip: Boolean = false,
    onResumeMeter: () -> Unit = {},
    /** Measured by the caller from its own constraints (A3) rather than hardcoded here - see
     * [MeterCard]'s BoxWithConstraints for the 16:9 clipping bug that fixed. */
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val starting = meterPhase is MeterStartPhase.Starting
    // Icon scale settles to a slightly larger size the moment Start Meter is in flight, then holds
    // — a real state-driven spring (see class doc), not a continuous pulse.
    val iconScale by animateFloatAsState(if (starting) 1.15f else 1f, animationSpec = hudSpring(), label = "meter-icon-scale")

    // Sized by the caller (A3). The long line of hand-measured values this replaced - 398dp,
    // then 414dp, pulled back from 430dp "by measuring the real overlap live" - existed only to
    // keep the dial from colliding with tiles that are no longer positioned on top of it.
    GlowingMeterGauge(
        progress = if (starting) 1f else 0f,
        modifier = modifier.size(size),
        strokeWidthDp = 14,
        sweepDeg = 360f,
        startDeg = -90f,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.DirectionsCar,
                contentDescription = null, // decorative: the METER STATUS label below carries the meaning
                tint = CaptainPalette.hudAccent,
                modifier = Modifier.size(28.dp).scale(iconScale),
            )
            Text(
                "METER STATUS",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(top = Space.xs),
            )
            val resumable = hasActiveTrip && meterPhase !is MeterStartPhase.Starting
            val (label, sub) = when {
                resumable -> "RUNNING" to "A fare is open — return to the meter"
                meterPhase is MeterStartPhase.Starting -> "STARTING" to "Starting meter…"
                meterPhase is MeterStartPhase.Failed -> "OFF" to meterPhase.message
                else -> "OFF" to "Tap to start a new fare"
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
                    // 76sp -> Type.display (64sp). Still by far the largest thing on the screen and
                    // still the point (a driver glancing over must never squint to know whether the
                    // meter is running); 76sp was sized for a dial that was 414dp and is now at
                    // most 380dp, and at 76sp "STARTING" no longer fits inside the smaller ring.
                    style = Type.display,
                    color = CaptainPalette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = Space.sm),
                )
            }
            Text(
                sub,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = if (meterPhase is MeterStartPhase.Failed) CaptainPalette.danger else CaptainPalette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.sm).width(220.dp),
            )
            Spacer(Modifier.height(Space.md))
            // Primary action on the whole screen — widened and heightened well past the standard
            // button size (184x54 -> 240x76) so it reads as unmistakably THE thing to press.
            when {
                meterPhase is MeterStartPhase.Starting ->
                    CaptainButton(text = "CANCEL", outline = true, widthDp = 220, heightDp = 64, fontSize = 20.sp, onClick = onCancelStart)
                // Never offer START METER over an open fare -- see hasActiveTrip's doc.
                resumable ->
                    CaptainButton(text = "RETURN TO METER", widthDp = 240, heightDp = 64, fontSize = 18.sp, onClick = onResumeMeter)
                else ->
                    StartMeterButton(widthDp = 220, heightDp = 64, fontSize = 20.sp, enabled = enabled, onClick = onStartMeter)
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
