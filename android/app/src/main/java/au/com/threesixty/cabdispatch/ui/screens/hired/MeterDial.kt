package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.ActiveBlackout
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.SpeedBand
import au.com.threesixty.cabdispatch.domain.SpeedZone
import au.com.threesixty.cabdispatch.ui.theme.speedZoneColor
import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.toMeterDisplayString
import au.com.threesixty.cabdispatch.domain.toMoneyString
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.GlowingSpeedometer
import au.com.threesixty.cabdispatch.ui.theme.rememberSpeedBand
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.Radius
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import au.com.threesixty.cabdispatch.ui.theme.Type
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.hudSpring
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/**
 * The meter pane's centrepiece: the circular fare dial and its readout, the night-fare
 * tile, the speech toggle, and the square action tiles ringing it (set price, add toll,
 * pause fare, more).
 *
 * Extracted from HiredScreen.kt in Phase 0 (P0.4) — a mechanical move, same package, same
 * declarations, no behaviour change. The night-multiplier helpers travel with the tile
 * that reads them so the file-private `String.toBigDecimalOrNull` stays file-private.
 */

/** The circular glass disc inside the speedometer ring, as a fraction of the ring's diameter —
 * sized so it sits just inside the kit's tick-label radius (`tickOuter − 11dp − 9dp`). */
private const val DIAL_INNER_FRACTION = 0.70f

/** RUNNING's glow radius at a standstill — the floor, and the value a stationary running meter
 * sits at permanently. Non-zero on purpose: a running meter must still read as live when the cab
 * is stopped at a light. It simply does not *move* there. See the call site's own doc. */
private const val RUNNING_GLOW_MIN_BLUR = 12f

/** RUNNING's glow radius at or above the distance-rate threshold. The old looping animation's
 * upper bound, kept — the range is unchanged, only what drives position within it. */
private const val RUNNING_GLOW_MAX_BLUR = 28f

/** Real night-rate uplift, not a fabricated multiplier — same ratio-of-signed-tariff computation
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `NightFareTile` uses (that
 * one is `private` to a different file, so this is a small, deliberate duplicate of the same
 * formula rather than a cross-file reach-around). `null` tariff (no pending-trip hand-off to read
 * it from) hides the ratio rather than showing a bogus one. */
internal fun nightMultiplierLabel(tariff: TariffDto?): String? {
    val t = tariff ?: return null
    val day = t.distRate1.toBigDecimalOrNull() ?: return null
    val night = t.nightRate1.toBigDecimalOrNull() ?: return null
    if (day.signum() <= 0) return null
    return "${night.divide(day, 2, RoundingMode.HALF_UP)}×"
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

// ============================================================================================
// GPS LOST pill — blackout in progress (W1/W5 GPS blackout program, 2026-09-12)
// ============================================================================================

/**
 * "m:ss" since [ActiveBlackout.startedAtIso] — the wall-clock instant a real blackout began.
 * Takes `nowMillis` as an explicit parameter rather than reading `Instant.now()` internally —
 * see [rememberNowTick]'s doc for why: this used to call `Instant.now()` straight from the
 * composable body on the assumption that [MeterDial] "recomposes once a second for the whole
 * hiring (the fare tick)", which is false for exactly the span this pill is on screen for. A
 * malformed/unparseable instant (should never happen — see the caller) renders "0:00" rather
 * than crashing the dial.
 */
private fun blackoutElapsedLabel(startedAtIso: String, nowMillis: Long): String {
    val started = runCatching { Instant.parse(startedAtIso) }.getOrNull() ?: return "0:00"
    val rawElapsed = Duration.between(started, Instant.ofEpochMilli(nowMillis))
    val elapsed = if (rawElapsed.isNegative) Duration.ZERO else rawElapsed
    val totalSeconds = elapsed.seconds
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Ticks once a second for as long as the caller stays composed — the same "must advance even when
 * nothing else does" shape
 * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsFormat.rememberOfferCountdown]
 * already uses for an offer countdown.
 *
 * Real bug, found live on-device (2026-09-14, GPS-blackout tunnel test): [FareState] is a
 * `data class`, and [au.com.threesixty.cabdispatch.domain.FareEngine] publishes it as
 * `_state.value = current.copy(...)`. A `MutableStateFlow` conflates consecutive equal values —
 * it never emits to collectors when the new value `.equals()` the one already held — and during a
 * blackout that is billing nothing (a "no charge yet" moving entry, or any blackout once its
 * catch-up has already resolved) *every field* of that `copy(...)` is identical tick after tick:
 * same distance, same moving/waiting seconds, same [ActiveBlackout] (same segment id, same
 * `startedAtIso`, same entry fix). So the flow stops emitting, `collectAsStateWithLifecycle()`
 * on [HiredViewModel.fareState] stops delivering new values, [MeterDial] stops recomposing, and
 * this pill's old `Instant.now()`-in-the-composable-body read froze at whatever wall-clock instant
 * the last *actual* fare-affecting tick happened to land on — for as long as the blackout lasted.
 * Every other figure on the dial (fare/distance/time) freezing at the same moment is CORRECT — no
 * money accrues during an untracked gap, so nothing on screen should move — but this pill is
 * explicitly a live "how long has this been going on" readout with no such excuse, and it is the
 * one thing a driver watching the dial during a long tunnel queue has to reassure them the meter
 * itself is not the thing that has hung. Driving it off its own clock, independent of whatever
 * [FareState] does or does not do this second, fixes that without touching a single line of fare
 * math — a pure display fix for a pure display bug.
 *
 * [runningKey] is the current blackout's own id ([ActiveBlackout.segmentId]) — restarting the loop
 * on a fresh key when one blackout ends and a new one begins is inert (the clock free-runs off
 * wall time regardless) but keeps this composable's lifetime obviously scoped to "one blackout",
 * matching every other keyed effect on this screen.
 */
private const val NOW_TICK_INTERVAL_MS = 1000L

@Composable
private fun rememberNowTick(runningKey: Any?): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), runningKey) {
        while (true) {
            value = System.currentTimeMillis()
            delay(NOW_TICK_INTERVAL_MS)
        }
    }

/**
 * The GPS-lost pill's full text while a blackout is in progress — "GPS LOST 1:23 · waiting only"
 * or "GPS LOST 1:23 · no charge yet". The suffix is decided by [ActiveBlackout.entryWasMoving],
 * NOT by [FareState.gpsLost] alone (that flag only says a fix is missing, not what the meter is
 * doing about it): a blackout that started while STATIONARY can only ever resolve to STATIONARY
 * (waiting time billed throughout, per [au.com.threesixty.cabdispatch.data.local.entity
 * .BlackoutResolution]'s doc) — "waiting only" is the whole, final story for that case, so it is
 * said plainly. A blackout that started while MOVING might still resolve to a real corridor
 * distance once the vehicle reacquires GPS in a mapped tunnel, or to nothing at all if it never
 * does — neither is known yet while the blackout is still open, so "no charge yet" says exactly
 * that (a promise about what is NOT being billed right now, not a claim about what the receipt
 * will eventually show).
 */
private fun gpsLostPillText(blackout: ActiveBlackout, nowMillis: Long): String {
    // Since the 2026-09-14 owner decision the meter keeps billing through a blackout off the
    // tablet's own motion sensors whenever an estimate exists -- "no charge yet" would then be a
    // lie to the passenger. Say what is actually happening.
    val suffix = when {
        blackout.lockedCorridorName != null -> blackout.lockedCorridorName.substringBefore(" (")
        blackout.estimatedSpeedKmh != null -> "sensor speed"
        blackout.entryWasMoving -> "no charge yet"
        else -> "waiting only"
    }
    return "GPS LOST ${blackoutElapsedLabel(blackout.startedAtIso, nowMillis)} · $suffix"
}

// ============================================================================================
// NIGHT / DAY FARE tile
// ============================================================================================

/**
 * The mockup's NIGHT FARE tile, state-driven off the engine's own [TimeClass] (the same field the
 * breakdown's "Night Fare" row keys on — never a local clock check that could disagree with what
 * is actually being charged). NIGHT: moon, the real night/day ratio off the signed tariff, the
 * 10 PM – 6 AM window (the local engine's own boundary, `FareEngine.kt#resolveTimeClass`), on a
 * [GlassCard] with the kit's accent halo. DAY/HOLIDAY: a calmer "DAY FARE · 1.00×" variant rather
 * than an empty slot — 1.00× is literally true (day rate is the baseline the night ratio is
 * measured against).
 */
@Composable
internal fun NightFareTile(timeClass: TimeClass, tariff: TariffDto?) {
    val night = timeClass == TimeClass.NIGHT
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadiusDp = 18,
        glow = if (night) CaptainPalette.hudAccent else null,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (night) Icons.Rounded.Bedtime else Icons.Rounded.WbSunny,
                    contentDescription = if (night) "Night fare" else "Day fare",
                    tint = if (night) CaptainPalette.hudAccent else CaptainPalette.warning,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    if (night) "NIGHT FARE" else "DAY FARE",
                    // 10sp -> 12sp (A4: Type.tiny, the accessibility floor).
                    style = Type.tiny,
                    letterSpacing = 1.sp,
                    color = CaptainPalette.textSecondary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                if (night) nightMultiplierLabel(tariff) ?: "—" else "1.00×",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                color = CaptainPalette.textPrimary,
                style = if (night) glowStyle(CaptainPalette.hudAccent, 22f) else TextStyle.Default,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                if (night) "10:00 PM – 6:00 AM" else "6:00 AM – 10:00 PM",
                // 10sp -> 12sp (A4: Type.tiny, the accessibility floor).
                style = Type.tiny,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * Real ≥56dp circular icon button replacing the previous bare-emoji `Text.clickable` (a real
 * small-touch-target accessibility problem for an elderly driver base) — same
 * `toggleSpeech(!speechEnabled)` call site (mockup #3, via [MoreActionsSheet]) or the mirrored
 * `onToggleVoice` (mockup #4's nav pane) — just a legible Material icon and a proper hit area.
 */
@Suppress("DEPRECATION")
@Composable
internal fun SpeechToggleButton(enabled: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) CaptainPalette.raised else CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, CircleShape)
            .clickable(onClick = onToggle)
            // W5 a11y pass, 2026-09-12: `Modifier.clickable()`'s own `role` parameter defaults to
            // `null` -- it makes this Box focusable/actionable but never announces it as a
            // BUTTON the way a real `Button`/`IconButton` would. Same custom-composed-tappable
            // fix as PAUSE/RESUME and END FARE below, and the ActionTile helper further down this
            // file.
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            // AutoMirrored.Filled.VolumeUp/VolumeOff, suggested by the deprecation warning on the
            // plain Filled.* versions, does not actually exist in this project's resolved
            // material-icons-extended version (verified: a real compile failure, "receiver type
            // mismatch", not a typo here) -- and a speaker icon has no inherent left/right meaning
            // to mirror in the first place, unlike the directional icons (back arrows, call split)
            // this AutoMirrored rename genuinely applies to elsewhere in this codebase. Kept on the
            // plain Filled icon, deprecation suppressed at this composable's own scope below.
            if (enabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
            contentDescription = if (enabled) "Speech announcements on" else "Speech announcements off",
            tint = if (enabled) CaptainPalette.accent else CaptainPalette.textMuted,
            modifier = Modifier.size(26.dp),
        )
    }
}

// ============================================================================================
// The dial — GlowingSpeedometer + inner glass disc
// ============================================================================================

/**
 * The speedometer/fare dial, entirely from the kit: [GlowingSpeedometer] is the outer ring (its
 * arc sweeps to `fareState.currentSpeedKmh` on [au.com.threesixty.cabdispatch.ui.theme.hudSpring]
 * — the live engine's own speed field, the same number FareEngineImpl decides DISTANCE-vs-WAITING
 * accrual on; it honestly sits at 0 with no fix). Its `content` slot holds a circular [GlassCard]
 * disc (so the figures sit on a stable surface over the map) carrying, top to bottom: car icon ·
 * ACTIVE FARE · the fare as [RollingMoneyText] · RUNNING (accent, glowing) / PAUSED (amber) ·
 * TARIFF + EXTRAS · DISTANCE / TIME / WAITING · END FARE. The numeric speed sits in the ring's
 * bottom gap. The dial is sized to the smaller of the slot's dimensions so it stays a circle
 * whatever the maxi/wheelchair banners do to the pane's height.
 */
@Composable
internal fun MeterDial(
    fareState: FareState,
    isPaused: Boolean,
    onEndFare: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateColor by animateColorAsState(
        targetValue = if (isPaused) CaptainPalette.warning else CaptainPalette.hudAccent,
        animationSpec = tween(300),
        label = "state-color",
    )
    // The band, computed once here and handed to the ring, so the ring's colour and this screen's
    // own readout can never disagree mid-crossfade. Smoothed with the same spring the ring uses --
    // banding the raw 1 Hz GPS staircase would flap several times a second in traffic.
    val smoothedSpeed = animateFloatAsState(
        targetValue = fareState.currentSpeedKmh.toFloat().coerceAtLeast(0f),
        animationSpec = hudSpring(),
        label = "dial-speed",
    )
    val band by rememberSpeedBand(smoothedSpeed, fareState.speedThresholdKmh)
    // Gaming zones (owner, 2026-09-14): the big km/h figure wears the road-speed zone's colour
    // (LOW violet / GOOD green / CAUTION amber), with hysteresis so a cruise on a boundary never
    // flickers. The tariff band still drives the size pop and the caption underneath.
    var zone by remember { mutableStateOf(SpeedZone.initial(fareState.currentSpeedKmh)) }
    LaunchedEffect(smoothedSpeed.value.roundToInt()) { zone = SpeedZone.next(zone, smoothedSpeed.value.toDouble()) }
    val bandColor by animateColorAsState(
        targetValue = speedZoneColor(zone),
        animationSpec = tween(700),
        label = "band-color",
    )
    val speedSize by animateFloatAsState(
        targetValue = if (band == SpeedBand.FAST) 38f else 32f,
        animationSpec = tween(700),
        label = "band-speed-size",
    )
    // One pop when the band changes — the same snapTo/animateTo the fare figure already uses. Not a
    // repeating pulse: it fires on a change and settles.
    val bandPop = remember { Animatable(1f) }
    var seenBand by remember { mutableStateOf(false) }
    LaunchedEffect(band) {
        if (!seenBand) { seenBand = true; return@LaunchedEffect }
        bandPop.snapTo(1.12f)
        bandPop.animateTo(1f, hudSpring())
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val d: Dp = minOf(maxWidth, maxHeight)
        val inner: Dp = d * DIAL_INNER_FRACTION
        GlowingSpeedometer(
            speedKmh = fareState.currentSpeedKmh.toFloat(),
            modifier = Modifier.size(d),
            // 14dp -> 18dp ring (owner, 2026-09-08: "the meter design is not looking good ... good
            // prominent font, good colors"). The ring is the meter's one big shape; a thin one read
            // as a placeholder on the 1280x800 canvas.
            strokeWidthDp = 18,
            // Real-motion take two (2026-09-06), explicitly requested and explicitly opt-in here
            // only — see GlowingSpeedometer's [motion] doc. Flip this to false to revert instantly
            // if it reproduces the earlier "moving circle" distress; nothing else needs to change.
            motion = true,
            thresholdKmh = fareState.speedThresholdKmh,
            band = band,
        ) {
            GlassCard(
                modifier = Modifier.size(inner),
                cornerRadiusDp = (inner.value / 2f).roundToInt(),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.DirectionsCar,
                        contentDescription = "Active fare",
                        tint = stateColor,
                        modifier = Modifier.size(26.dp),
                    )
                    Text(
                        "ACTIVE FARE",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp,
                        color = CaptainPalette.textSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // Display-only "classic meter" rounding (driver request, 2026-09-05): floors
                    // to the nearest dime so the cents digit always reads "0" and only the dimes
                    // digit visibly rolls, instead of every-cent jitter. See toMeterDisplayString's
                    // own doc — this never touches the real fare fareState carries; Close & Pay,
                    // receipts, and sync all keep reading fareState.total/.toMoneyString() exactly.
                    val totalText = fareState.total.toMeterDisplayString()
                    // A quick spring "pop" every time the displayed fare actually ticks over, on
                    // top of RollingMoneyText's own per-digit roll — the "high level animation"
                    // request: the whole figure punches out a touch, then springs back to size.
                    val tickScale = remember { Animatable(1f) }
                    LaunchedEffect(totalText) {
                        tickScale.snapTo(1.16f)
                        tickScale.animateTo(1f, animationSpec = hudSpring())
                    }
                    RollingMoneyText(
                        amount = totalText,
                        // Bumped 2026-09-06 (direct passenger-readability feedback — see
                        // MeterPaneLayout's DIAL_COL_WEIGHT doc for the matching 50/50 layout
                        // change this pairs with): a passenger seated beside the dial should be
                        // able to read the running total at a glance, not just the driver.
                        fontSize = if (totalText.length > 8) 68.sp else 88.sp,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .scale(tickScale.value),
                    )
                    // RUNNING's glow, driven by SPEED rather than by a clock (A4, 2026-09-08).
                    //
                    // WHAT THIS REPLACED, and why it had to go. This was a
                    // `rememberInfiniteTransition` breathing the blur radius 12 → 28px on a 900ms
                    // `RepeatMode.Reverse` loop, and it was **unconditional** — it kept pulsing
                    // while the meter was PAUSED, and it kept pulsing while the cab sat at a rank
                    // with the engine off. The 2026-09-08 UI audit (§2) singled it out as "the one
                    // loop contradicting the calm rule inside the fare dial", and the calm-motion
                    // rule it contradicts is committed in this repo's own words at
                    // `ui/theme/Hud.kt:437-455`: *"this circle is moving continuously, its doing
                    // pain in my head... calm animations"*. A decorative loop in the driver's
                    // direct eyeline, running for an entire twelve-hour shift, is exactly the
                    // thing that feedback was about.
                    //
                    // WHAT DRIVES IT NOW. The same input [GlowingSpeedometer]'s own ember and
                    // brightness already use — the engine's real `currentSpeedKmh`, normalised
                    // against the band threshold and smoothed through the shared [hudSpring] so it
                    // tracks the ring rather than flickering on the raw 1 Hz GPS staircase. The
                    // result is the property the rule actually asks for:
                    // - moving: the glow is brighter the faster the cab is going, and every frame
                    //   it changes, it changes because the vehicle's speed changed;
                    // - stationary but running: a **still** glow at the floor radius — the meter
                    //   still reads as live, it simply is not animating;
                    // - PAUSED: no speed term at all, a flat amber static — a paused meter must
                    //   not look alive.
                    // Nothing here animates on a timer, so a parked cab is a still screen.
                    val speedFraction = (smoothedSpeed.value / fareState.speedThresholdKmh.toFloat().coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                    val runningBlur = RUNNING_GLOW_MIN_BLUR +
                        (RUNNING_GLOW_MAX_BLUR - RUNNING_GLOW_MIN_BLUR) * speedFraction
                    Text(
                        if (isPaused) "PAUSED" else "RUNNING",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 3.sp,
                        color = stateColor,
                        style = glowStyle(stateColor, if (isPaused) 20f else runningBlur),
                        modifier = Modifier.semantics {
                            // The dial's single most important piece of state, and previously
                            // spoken as a bare word with no context. TalkBack now says which meter
                            // this is describing.
                            contentDescription = if (isPaused) "Meter paused" else "Meter running"
                        },
                    )
                    Text(
                        // "Set Price" fix (product-reported, 2026-09): a fixed-fare trip's dial
                        // shows FIXED PRICE here instead of the tariff band — the figure above is
                        // the agreed amount (+ tolls/PSL/extras, see FareState.total's doc), not a
                        // metered band total, so labelling it as one would be actively misleading.
                        if (fareState.negotiatedTotal != null) "FIXED PRICE" else "${fareState.band.label.uppercase()} + EXTRAS",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // GPS LOST — the honest half of A1's F3 fix (A4, 2026-09-08; extended for the
                    // W1/W5 blackout program, 2026-09-12).
                    //
                    // The engine now *stops accruing distance* when the newest fix is older than
                    // `MAX_FIX_AGE_MS` (FareEngine.kt#tick) — the tunnel that used to bill phantom
                    // kilometres at the last known speed now bills waiting time only. That change
                    // is invisible without this pill, and an invisible fare change is the worst
                    // kind: the driver sees the distance figure stop moving and has no way to tell
                    // a correct hold from a frozen meter. So this states exactly what is and is
                    // not being charged, in the words of the rule being applied.
                    //
                    // Gated on `fareState.blackout` (not `gpsLost` alone) so the pill can show the
                    // live "how long" figure and the entry-motion-specific wording — see
                    // [gpsLostPillText]'s own doc. `gpsLost` without a `blackout` should never
                    // happen (`FareState.blackout`'s doc: "the two are always set and cleared
                    // together") but the plain fallback text below is kept as a defensive floor —
                    // an honest generic warning is still better than the pill silently vanishing
                    // if that invariant is ever violated by a future change.
                    //
                    // PERSISTENT, not a self-dismissing banner like METER STARTED or TOLL ADDED:
                    // it is a live condition, not an event, and it must stay on screen for as long
                    // as it is true. It carries no animation of its own — the TEXT it shows updates
                    // once a second along with the rest of the dial's per-tick figures, but nothing
                    // here fades, slides, pulses or otherwise animates, which is exactly what the
                    // calm-motion rule (ui/theme/Hud.kt) requires of a condition that can sit on
                    // screen, unattended, for the length of an entire tunnel queue.
                    if (fareState.gpsLost) {
                        val blackout = fareState.blackout
                        // See [rememberNowTick]'s own doc for the bug this fixes — this pill must
                        // keep advancing every second even on a tick where [fareState] itself does
                        // not change at all (the common case for a "no charge yet" blackout).
                        val nowMillis by rememberNowTick(blackout?.segmentId)
                        Row(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(CaptainPalette.warning.copy(alpha = 0.18f))
                                .border(1.dp, CaptainPalette.warning, RoundedCornerShape(Radius.pill))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Decorative (A4 a11y pass, reviewed): the text beside this glyph already IS its label,
                            // and Compose merges this node's semantics into one announcement -- a description here
                            // would make TalkBack read the same words twice. The audit's finding was unlabelled
                            // *controls*; every control on this screen now carries a real name. This is a reviewed
                            // null, not an overlooked one.
                            Icon(
                                Icons.Rounded.GpsOff,
                                contentDescription = null,
                                tint = CaptainPalette.warning,
                                modifier = Modifier.size(14.dp),
                            )
                            val pillText = blackout?.let { gpsLostPillText(it, nowMillis) }
                                ?: "GPS LOST — WAITING TIME ONLY"
                            Text(
                                pillText.uppercase(),
                                style = Type.tiny,
                                letterSpacing = 1.sp,
                                color = CaptainPalette.warning,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    // The dial OWNS these three live readouts (dedupe pass) — they appear nowhere
                    // else on the screen. WAITING goes amber while actually accruing.
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // DISTANCE dims while the fix is stale, because it is the one readout that
                        // has genuinely stopped moving. Dimming it says "this number is holding on
                        // purpose" in the same glance that the pill above says why.
                        DialReadout(
                            "DISTANCE",
                            fareState.distanceKm.setScale(1, RoundingMode.HALF_UP).toPlainString() + " KM",
                            valueColor = if (fareState.gpsLost) CaptainPalette.textMuted else CaptainPalette.textPrimary,
                        )
                        DialReadout("TIME", "%d:%02d".format(fareState.movingSeconds / 60, fareState.movingSeconds % 60))
                        DialReadout(
                            "WAITING",
                            "%d:%02d".format(fareState.waitingSeconds / 60, fareState.waitingSeconds % 60),
                            valueColor = if (isPaused) CaptainPalette.warning else CaptainPalette.textPrimary,
                        )
                    }
                    // PAUSE/RESUME + END FARE — both inside the dial now (2026-09-06, direct
                    // feedback: "there should be one button, pause the meter and resume the
                    // meter... on the speedometer" — PAUSE FARE already existed, but only inside
                    // the ControlsHandle drawer, an extra tap away). This is the SAME
                    // onTogglePause/togglePause() call PauseFareTile (the drawer's own 2×2 grid)
                    // already makes — one real toggle, now reachable from two places rather than a
                    // second, competing pause state.
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 44dp -> 56dp (A4, 2026-09-08). The audit (§6) listed both of these under
                        // "touch targets below the 48dp minimum"; 56dp is the size the rest of
                        // this app's chips already use for the elderly-driver standard the home
                        // screen's class doc commits to (see [HudStatusPill], also 56dp), so this
                        // brings the two most-tapped controls on the meter into line with it
                        // rather than just scraping over the Material minimum.
                        Box(
                            modifier = Modifier
                                .width(96.dp)
                                .height(56.dp)
                                .neonGlow(stateColor, 18.dp, strength = 0.7f, spread = 3.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(CaptainPalette.hudGlass)
                                .border(1.dp, stateColor.copy(alpha = 0.9f), RoundedCornerShape(18.dp))
                                .gameClick(onClick = onTogglePause, shape = RoundedCornerShape(18.dp))
                                .semantics {
                                    role = Role.Button
                                    contentDescription = if (isPaused) "Resume fare" else "Pause fare"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (isPaused) "RESUME" else "PAUSE",
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                letterSpacing = 1.sp,
                                color = stateColor,
                            )
                        }
                        // END FARE — inside the dial, per the mockup. Same
                        // endTrip { navigate(CLOSE_PAY) } call the old full-width END TRIP bar made
                        // (see the caller).
                        // 44dp -> 56dp (A4, 2026-09-08). This is the single most consequential
                        // control in the app — it ends the hiring and commits the fare the
                        // passenger is about to be charged — and it was the same 44dp as
                        // everything else. A mis-tap here is not a cosmetic problem.
                        Box(
                            modifier = Modifier
                                .width(150.dp)
                                .height(56.dp)
                                .neonGlow(CaptainPalette.primary, 18.dp, strength = 0.9f, spread = 4.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Brush.horizontalGradient(listOf(CaptainPalette.primary, CaptainPalette.hudAccent)))
                                .border(1.dp, CaptainPalette.hudSweepMid.copy(alpha = 0.9f), RoundedCornerShape(18.dp))
                                .gameClick(onClick = onEndFare, shape = RoundedCornerShape(18.dp))
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "End fare and go to payment"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "END FARE",
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                letterSpacing = 2.sp,
                                // onAccent (fixed white), not textPrimary — this label sits on a
                                // solid primary/hudAccent gradient fill (see
                                // CaptainPalette.onAccent's doc).
                                color = CaptainPalette.onAccent,
                            )
                        }
                    }
                }
            }
            // Numeric speed in the ring's bottom gap (the 270° arc leaves 7:30 → 4:30 open),
            // with the charging mode named underneath it. The caption is the point: it says which
            // rate the passenger is being charged right now, which is what the ring's colour is
            // already showing and what a driver would otherwise have to infer.
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        fareState.currentSpeedKmh.roundToInt().toString(),
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = speedSize.sp,
                        color = bandColor,
                        style = when (zone) {
                            SpeedZone.LOW -> TextStyle.Default
                            SpeedZone.GOOD -> glowStyle(bandColor, 14f)
                            SpeedZone.CAUTION -> glowStyle(bandColor, 22f)
                        },
                        modifier = Modifier.scale(bandPop.value),
                    )
                    Text(
                        " km/h",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                // Suppressed on a fixed price: neither rate is accruing, so naming one would be a
                // lie about what the passenger is paying.
                if (fareState.negotiatedTotal == null) {
                    Text(
                        if (band == SpeedBand.WAITING) "WAITING TIME" else "DISTANCE RATE",
                        // 10sp -> 12sp (A4: Type.tiny, the accessibility floor).
                        style = Type.tiny,
                        letterSpacing = 2.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DialReadout(label: String, value: String, valueColor: Color = CaptainPalette.textPrimary) {
    // Bumped alongside the rest of MeterDial (2026-09-06 passenger-readability pass) — width grown
    // to match so three of these in a Row don't crowd the bigger value text.
    Column(modifier = Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = Type.tiny, letterSpacing = 1.sp, color = CaptainPalette.textSecondary)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = valueColor, maxLines = 1)
    }
}

// ============================================================================================
// Action tiles (SET PRICE / ADD TOLL / PAUSE FARE / MORE) — a 2×2 grid in MeterPaneLayout's
// right column (both tiles-per-row calls live there now; no separate vertical-stack variant).
// ============================================================================================

@Composable
internal fun SetPriceTile(a: MeterActions, modifier: Modifier) = MeterActionTile(
    icon = Icons.Rounded.Sell,
    label = "SET PRICE",
    // Honest status line, not a fake "tap to edit" — see SetPriceInfoDialog's doc for why this
    // button is informational only during an active trip.
    value = if (a.negotiatedTotal != null) "Fixed · ${formatNegotiatedTotal(a.negotiatedTotal)}" else "Metered fare",
    onClick = a.onSetPrice,
    modifier = modifier,
)

@Composable
internal fun AddTollTile(a: MeterActions, modifier: Modifier) = MeterActionTile(
    icon = Icons.Rounded.ConfirmationNumber,
    label = "ADD TOLL",
    value = "${a.tollsTotal.toMoneyString()} · ${a.tollCount} toll${if (a.tollCount == 1) "" else "s"} added",
    accentColor = CaptainPalette.warning,
    onClick = a.onAddToll,
    modifier = modifier,
)

@Composable
internal fun PauseFareTile(a: MeterActions, modifier: Modifier) = MeterActionTile(
    icon = if (a.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
    label = if (a.isPaused) "RESUME FARE" else "PAUSE FARE",
    value = if (a.isPaused) "Waiting — tap to resume" else "Tap when the trip stops",
    accentColor = if (a.isPaused) CaptainPalette.warning else CaptainPalette.success,
    active = a.isPaused,
    onClick = a.onTogglePause,
    modifier = modifier,
)

@Composable
internal fun MoreTile(a: MeterActions, modifier: Modifier) = MeterActionTile(
    icon = Icons.Rounded.MoreHoriz,
    label = "MORE",
    value = "Destination · extras · passengers",
    onClick = a.onMore,
    modifier = modifier,
)

/** Compact [GlassCard] tile: icon in a coloured rounded square, bold label, one-line subtext.
 * [active] lights the kit's halo in the accent colour (PAUSE FARE while paused). */
@Composable
private fun MeterActionTile(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = CaptainPalette.hudAccent,
    active: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val labelColor by animateColorAsState(
        targetValue = if (active) accentColor else CaptainPalette.textPrimary,
        animationSpec = tween(250),
        label = "tile-label",
    )
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .gameClick(onClick = onClick, shape = shape, glowColor = accentColor)
            .semantics { role = Role.Button },
        cornerRadiusDp = 16,
        glow = if (active) accentColor else null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            IconSquare(icon = icon, tint = accentColor, size = 32.dp, lit = active, label = label)
            Text(
                label,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                color = labelColor,
                style = if (active) glowStyle(accentColor, 14f) else TextStyle.Default,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                value,
                // 9.5sp -> 12sp (A4: Type.tiny, the accessibility floor). This was the smallest
                // type anywhere on the meter screen and it carried the tile's only real status
                // line ("Fixed - $52.00", "3 tolls added").
                style = Type.tiny,
                color = CaptainPalette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Icon in a tinted, rounded square — the mockup's action-tile / stop-card glyph treatment. */
@Composable
private fun IconSquare(icon: ImageVector, tint: Color, size: Dp, lit: Boolean = false, label: String? = null) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = if (lit) 0.32f else 0.18f))
            .border(1.dp, tint.copy(alpha = if (lit) 0.9f else 0.4f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.6f))
    }
}
