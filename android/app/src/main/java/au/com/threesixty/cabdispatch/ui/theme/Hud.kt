package au.com.threesixty.cabdispatch.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import au.com.threesixty.cabdispatch.domain.SpeedBand
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotation
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The shared automotive-cockpit / game-HUD component kit (2026-09-03) — every glowing gauge,
 * rolling money figure, glass card, status pill and stat tile the app's chrome is built from,
 * in one place, to one technical standard:
 *
 * - **Glow is real blur, on arcs only.** Every gauge draws a dark track arc, then a blurred neon
 *   glow arc through the native canvas (`drawIntoCanvas { nativeCanvas.drawArc(..., paint) }` with a
 *   `Paint().asFrameworkPaint()` carrying a `BlurMaskFilter(35f, NORMAL)`), then a crisp
 *   sweep-gradient foreground arc on top. A mask-filter blur on a stroked arc is cheap — it's the
 *   full-layer `RenderEffect` blur that is NOT used anywhere here, per the SM-T575 frame budget.
 * - **State changes are physics.** Gauge progress and speed move on one shared spring
 *   ([hudSpring]: `DampingRatioLowBouncy` / `StiffnessLow`), so a fare tick or a speed change
 *   visibly settles rather than lerps.
 * - **Digits roll.** [RollingMoneyText] slides each changed digit up (increase) or down (decrease)
 *   through a per-glyph `AnimatedContent`, on fixed-width digit slots so the figure never jitters.
 * - **Glass, not cards.** [GlassCard] is the one floating-over-map surface: [CaptainPalette.hudGlass]
 *   fill, 1dp purple→white low-alpha gradient border, optional [neonGlow] halo.
 *
 * Complements [CaptainWidgets.kt][neonGlow] — [neonGlow], [gameClick], [rememberInfiniteFloat] and
 * [PulsingDot] are reused here, not duplicated. Palette values are the `hud*` tokens on
 * [CaptainPalette]; nothing in this file hardcodes a colour. Screens are rebuilt on top of this kit
 * separately — this file deliberately contains no screen.
 */

// ============================================================================================
// Shared primitives
// ============================================================================================

/** Blur radius (px) of the neon glow arc — the blueprint's `BlurMaskFilter(35f, NORMAL)`. */
const val HUD_GLOW_BLUR_PX = 35f

/** Blur radius (px) of the light-mode "soft drop shadow" pass — a real, small elevation shadow,
 * not a neon glow (see [rememberHudGlowPaint]'s doc) — deliberately much tighter than
 * [HUD_GLOW_BLUR_PX] so it reads as lift off the page rather than another halo. */
const val HUD_DAY_SHADOW_BLUR_PX = 14f

/** Width (dp) of the light-mode crisp accent ring [drawHudArc] draws on top of the lit sweep —
 * the "still glowing" cue a neon sign gave for free in the dark, done here as a sharp, saturated
 * outline instead (see that function's doc). */
private val HUD_DAY_RING_WIDTH = 2.dp

/** The one physics spring every HUD state transition uses (gauge progress, speed needle, rings). */
fun hudSpring(): SpringSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)

/** Semantic tint for pills/tiles — resolves to a [CaptainPalette] token via [color]. */
enum class HudTone { Neutral, Accent, Success, Warning, Danger }

fun HudTone.color(): Color = when (this) {
    HudTone.Neutral -> CaptainPalette.textSecondary
    HudTone.Accent -> CaptainPalette.hudAccent
    HudTone.Success -> CaptainPalette.success
    HudTone.Warning -> CaptainPalette.warning
    HudTone.Danger -> CaptainPalette.danger
}

/**
 * The framework paint that produces the glow: a stroked, round-capped, blurred paint. Remembered
 * per colour so the `BlurMaskFilter` (and its cached blur kernel) is allocated once per composable,
 * never per frame. Stroke width is set at draw time (it depends on the arc's geometry).
 *
 * Light-mode day pass (2026-09-04): a saturated neon-coloured blur reads as "a lit sign" only
 * against a near-black background — the same blur painted in [CaptainPalette.hudAccent] on a light
 * background reads as a muddy purple smear, not a glow. So in light mode ([CaptainPalette.isLight])
 * this paints the arc's **shadow** pass instead of its **glow** pass: a small-radius, dark,
 * low-alpha blur (a real elevation shadow) rather than a big saturated one — [drawHudArc] then adds
 * a crisp, unblurred accent-coloured ring on top (see that function's doc) as the "still glowing"
 * cue a neon sign gave for free in the dark. Defaults still resolve to the dark-mode neon values so
 * an explicit caller override (there are none today) keeps working unchanged.
 */
@Composable
private fun rememberHudGlowPaint(
    color: Color = if (CaptainPalette.isLight) CaptainPalette.hudDayShadow else CaptainPalette.hudAccent,
    blurRadiusPx: Float = if (CaptainPalette.isLight) HUD_DAY_SHADOW_BLUR_PX else HUD_GLOW_BLUR_PX,
): android.graphics.Paint =
    remember(color, blurRadiusPx) {
        Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            this.color = color.toArgb()
            maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
        }
    }

/** Centre + centreline radius + stroke of an arc fitted into a [DrawScope]. */
private class HudArcGeometry(val center: Offset, val radius: Float, val strokePx: Float) {
    val topLeft: Offset get() = Offset(center.x - radius, center.y - radius)
    val size: Size get() = Size(radius * 2f, radius * 2f)

    companion object {
        /** Fits the arc's centreline inside the scope with half the stroke plus [insetPx] of
         * breathing room (the mask-filter glow bleeds outside the stroke and Compose's `Canvas`
         * doesn't clip, so the inset is for the crisp stroke, not the glow). */
        fun fit(scope: DrawScope, strokePx: Float, insetPx: Float): HudArcGeometry {
            val c = Offset(scope.size.width / 2f, scope.size.height / 2f)
            val r = min(scope.size.width, scope.size.height) / 2f - strokePx / 2f - insetPx
            return HudArcGeometry(c, r, strokePx)
        }
    }
}

/**
 * The blueprint's three-pass arc, in order: dark track → blurred glow (native canvas) → crisp
 * sweep-gradient foreground. Drawn inside a `rotate(startDeg)` so the sweep gradient's 0° lines
 * up with the arc's start regardless of where the caller anchors it — the gradient stops are
 * expressed as fractions of a full turn so `#5B3FD6` sits at the arc's start, `#9E77FF` mid-sweep
 * and `#6E3FF3` at full sweep.
 *
 * Light-mode day pass (2026-09-04): [CaptainPalette.isLight] swaps the middle pass from a
 * saturated neon blur to a tight, dark drop-shadow ([glowPaint] already carries the right colour
 * and blur radius for whichever mode is active — see [rememberHudGlowPaint]) at a dampened
 * [glowAlpha], and adds a fourth pass on top: a crisp, unblurred [CaptainPalette.hudAccent] line
 * traced back through the centre of the lit stroke — a bright "lit core" running down the middle of
 * the gradient arc. That line is the light-mode replacement for "this is the lit part of the gauge"
 * — a blur can't do that job on a light background (there's nothing for it to glow against), a
 * sharp saturated line can.
 */
private fun DrawScope.drawHudArc(
    g: HudArcGeometry,
    progress: Float,
    startDeg: Float,
    sweepDeg: Float,
    glowPaint: android.graphics.Paint,
    glowAlpha: Float = 0.85f,
    /** Sweep-gradient stops, start -> mid -> end. Defaults to the palette's own, which is what
     * every gauge other than the speedometer wants. */
    sweepStops: Triple<Color, Color, Color> = Triple(
        CaptainPalette.hudSweepStart,
        CaptainPalette.hudSweepMid,
        CaptainPalette.hudSweepEnd,
    ),
    /** Multiplier on the blurred glow pass's stroke width. The speedometer widens this at speed;
     * nothing else changes it. */
    glowStrokeMultiplier: Float = 1.5f,
) {
    drawArc(
        color = CaptainPalette.hudTrack,
        startAngle = startDeg,
        sweepAngle = sweepDeg,
        useCenter = false,
        topLeft = g.topLeft,
        size = g.size,
        style = Stroke(g.strokePx, cap = StrokeCap.Round),
    )
    val lit = sweepDeg * progress.coerceIn(0f, 1f)
    if (lit < 0.5f) return
    val isLight = CaptainPalette.isLight
    val effectiveGlowAlpha = if (isLight) glowAlpha * 0.4f else glowAlpha
    rotate(degrees = startDeg, pivot = g.center) {
        drawIntoCanvas { canvas ->
            glowPaint.strokeWidth = g.strokePx * glowStrokeMultiplier
            glowPaint.alpha = (effectiveGlowAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
            canvas.nativeCanvas.drawArc(
                g.topLeft.x, g.topLeft.y, g.topLeft.x + g.size.width, g.topLeft.y + g.size.height,
                0f, lit, false, glowPaint,
            )
        }
        val full = sweepDeg / 360f
        drawArc(
            brush = Brush.sweepGradient(
                colorStops = arrayOf(
                    0f to sweepStops.first,
                    full * 0.5f to sweepStops.second,
                    full to sweepStops.third,
                    1f to sweepStops.third,
                ),
                center = g.center,
            ),
            startAngle = 0f,
            sweepAngle = lit,
            useCenter = false,
            topLeft = g.topLeft,
            size = g.size,
            style = Stroke(g.strokePx, cap = StrokeCap.Round),
        )
        if (isLight) {
            drawArc(
                color = CaptainPalette.hudAccent,
                startAngle = 0f,
                sweepAngle = lit,
                useCenter = false,
                topLeft = g.topLeft,
                size = g.size,
                style = Stroke(HUD_DAY_RING_WIDTH.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------
// Holographic bezel (2026-09-07, futuristic-HUD reskin, design brief item 5) — a fixed, static
// decorative ring drawn just outside every gauge's own track/sweep arc. See [drawHoloRing]'s own
// doc for the three passes; [rememberHoloParticles]'s own doc for why the scatter is computed once
// and never re-seeded or animated, matching [GlowingSpeedometer]'s "no new decorative loop" rule.
// ------------------------------------------------------------------------------------------

private const val HUD_HOLO_PARTICLE_COUNT = 14
private const val HUD_HOLO_DASH_ON_PX = 14f
private const val HUD_HOLO_DASH_GAP_PX = 10f
private const val HUD_HOLO_OUTER_GLOW_BLUR_PX = 46f

/** One scattered perimeter dot: a fixed angle/radius/alpha/size baked in at creation — see
 * [rememberHoloParticles]. */
private data class HoloParticle(val angleDeg: Float, val radiusFraction: Float, val alpha: Float, val radiusPx: Float)

/**
 * A fixed scatter of [count] dots around the holographic bezel, computed once via a seeded
 * [Random] and `remember`-cached for the composable's lifetime — **not** reseeded or advanced on
 * any clock, so the dots sit exactly where they were first placed for as long as the gauge is
 * composed. This is the literal "static/scattered, not animated/twinkling" requirement from this
 * pass's own constraints (see [GlowingSpeedometer]'s motion doc for the one already-approved motion
 * pattern in this file — this is not it, and doesn't try to be).
 */
@Composable
private fun rememberHoloParticles(count: Int = HUD_HOLO_PARTICLE_COUNT, seed: Int = 1): List<HoloParticle> =
    remember(count, seed) {
        val rng = Random(seed)
        List(count) {
            HoloParticle(
                angleDeg = rng.nextFloat() * 360f,
                radiusFraction = 0.86f + rng.nextFloat() * 0.24f,
                alpha = 0.22f + rng.nextFloat() * 0.45f,
                radiusPx = 1.2f + rng.nextFloat() * 1.8f,
            )
        }
    }

/** The holo bezel's own blurred paint — same [BlurMaskFilter] technique as [rememberHudGlowPaint],
 * fixed to [CaptainPalette.neonCyan] regardless of theme (the outer glow is a decorative accent,
 * not a state indicator, so it doesn't need the light-mode shadow substitution [rememberHudGlowPaint]
 * makes for the real progress arc). */
@Composable
private fun rememberHoloGlowPaint(): android.graphics.Paint = remember {
    Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        color = CaptainPalette.neonCyan.toArgb()
        maskFilter = BlurMaskFilter(HUD_HOLO_OUTER_GLOW_BLUR_PX, BlurMaskFilter.Blur.NORMAL)
    }
}

/**
 * The design brief's "Holographic Central Meter" bezel — three static passes drawn as a full circle
 * just outside [g]'s own track/sweep arc, never touching or replacing that arc's own progress
 * drawing:
 * 1. A secondary, blurred, thicker stroke (full circle, [glowPaint]) — the brief's own "outer glow".
 * 2. A crisp, **dashed** stroke (`PathEffect.dashPathEffect` — a real dashed line, not a solid ring
 *    that merely looks segmented) swept through a cyan → purple → cyan gradient — the brief's own
 *    "dashed stroke colored with a cyan-to-purple gradient".
 * 3. [particles] — [rememberHoloParticles]'s fixed scatter of dots around the bezel's radius.
 */
private fun DrawScope.drawHoloRing(g: HudArcGeometry, particles: List<HoloParticle>, glowPaint: android.graphics.Paint) {
    val cx = g.center.x
    val cy = g.center.y
    val bezelRadius = g.radius + g.strokePx * 0.9f

    drawIntoCanvas { canvas ->
        glowPaint.strokeWidth = g.strokePx * 0.6f
        glowPaint.alpha = 130
        canvas.nativeCanvas.drawCircle(cx, cy, bezelRadius, glowPaint)
    }

    drawCircle(
        brush = Brush.sweepGradient(
            colorStops = arrayOf(
                0f to CaptainPalette.neonCyan,
                0.5f to CaptainPalette.hudAccent,
                1f to CaptainPalette.neonCyan,
            ),
            center = Offset(cx, cy),
        ),
        radius = bezelRadius,
        center = Offset(cx, cy),
        style = Stroke(
            width = g.strokePx * 0.22f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(HUD_HOLO_DASH_ON_PX, HUD_HOLO_DASH_GAP_PX), 0f),
        ),
    )

    particles.forEach { p ->
        val rad = Math.toRadians(p.angleDeg.toDouble())
        val r = bezelRadius * p.radiusFraction
        val x = cx + (cos(rad) * r).toFloat()
        val y = cy + (sin(rad) * r).toFloat()
        drawCircle(color = CaptainPalette.neonCyan.copy(alpha = p.alpha), radius = p.radiusPx, center = Offset(x, y))
    }
}

// ============================================================================================
// 1. GlowingMeterGauge / GlowingSpeedometer
// ============================================================================================

/**
 * The blueprint's gauge: a [sweepDeg]° arc anchored at [startDeg] (defaults: the 270° "7:30 →
 * 4:30 o'clock" speedometer arc), drawn track → blurred glow → sweep-gradient foreground, with
 * [progress] (0..1) spring-animated so every change settles with a little bounce. [content] is the
 * centre overlay (the fare figure, a label, anything) and is laid out centred over the arc.
 *
 * Size comes from [modifier] (`.size(…)`); the arc fits the smaller dimension.
 */
@Composable
fun GlowingMeterGauge(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidthDp: Int = 14,
    sweepDeg: Float = 270f,
    startDeg: Float = 135f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = hudSpring(),
        label = "hud-gauge-progress",
    )
    val glowPaint = rememberHudGlowPaint()
    val holoGlowPaint = rememberHoloGlowPaint()
    val holoParticles = rememberHoloParticles()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val g = HudArcGeometry.fit(this, strokeWidthDp.dp.toPx(), HUD_ARC_INSET.toPx())
            drawHoloRing(g, holoParticles, holoGlowPaint)
            drawHudArc(g, animated, startDeg, sweepDeg, glowPaint)
        }
        content()
    }
}

/**
 * Speedometer variant of [GlowingMeterGauge]: the same three-pass arc driven by
 * `speedKmh / maxKmh`, plus the scale ported from the meter screen's on-device-tuned
 * `MeterDialArt` (`HiredScreen.kt`): a tick every 5 km/h (major every 20, i.e. every 4th tick),
 * numeric labels at the majors, major ticks 11dp/2.5dp and minor 5dp/1.5dp (round caps), labels
 * 10sp bold via a native text paint. Ticks at or below the (spring-animated) speed light up in
 * [CaptainPalette.hudAccent]; the rest sit in [CaptainPalette.hudTrack]. The only geometry change
 * from the port is that the label radius is expressed relative to the tick ring
 * (`tickOuter - majorLen - 9dp`) rather than to the track (`trackR - 24dp`), because this arc's
 * default stroke (14dp) is nearly three times `MeterDialArt`'s 5dp and the original constant would
 * put labels under the major ticks.
 *
 * **Calm glow pass (2026-09-05).** A first attempt here added a travelling highlight that
 * continuously circled the ring — reverted immediately on direct feedback ("this circle is
 * moving continuously, its doing pain in my head... calm animations"). What stayed: the glow
 * arc's own brightness now scales gently with real speed (`0.55 + 0.45 * speed/max` instead of a
 * flat `0.85`, itself spring-smoothed since it rides [animatedSpeed]) — a quiet, real readout, not
 * a decorative loop, and it never moves *positionally* the way the reverted highlight did. Sitting
 * still or crawling in traffic, the ring simply sits at its calm resting brightness.
 *
 * **Ember motion, take two (2026-09-06, opt-in via [motion]).** Direct request to try real motion
 * again, on the explicit understanding it gets tested live and reverted instantly if it reproduces
 * the same distress. The design is deliberately NOT the reverted effect: that one orbited the full
 * ring continuously, on its own clock, whether the vehicle was moving or not — a treadmill running
 * under a parked car. This one is [rememberEmberPhase]'s single soft spark, confined to the
 * *already-lit* stretch of the arc (never touches the dark, off track), drifting slowly back and
 * forth rather than orbiting, at a speed that scales with real `speedKmh` and drops to a dead stop
 * within its own spring settle time once the vehicle stops — parked or waiting at a light shows a
 * static ring, exactly like the calm-only version, because there is nothing happening to animate.
 * [motion] defaults to `false`; every existing call site (previews, tests, anywhere not the running
 * meter) is unaffected. The one call site that turns it on is [au.com.threesixty.cabdispatch.ui.screens.hired.MeterDial]'s [GlowingSpeedometer].
 */
@Composable
fun GlowingSpeedometer(
    speedKmh: Float,
    maxKmh: Float = 120f,
    modifier: Modifier = Modifier,
    strokeWidthDp: Int = 14,
    sweepDeg: Float = 270f,
    startDeg: Float = 135f,
    showLabels: Boolean = true,
    motion: Boolean = false,
    /** The tariff's own waiting/distance line, marked on the dial and used to band the animation.
     * Defaults to the Fares Order urban figure for previews. */
    thresholdKmh: Double = SpeedBand.DEFAULT_THRESHOLD_KMH,
    /** The band to render. Pass the one the caller already computed with [rememberSpeedBand] so the
     * ring and the caller's own readout cannot disagree; null computes it here, which is what the
     * previews want. */
    band: SpeedBand? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val safeMax = maxKmh.coerceAtLeast(5f)
    // Kept as a State and read only inside the draw lambda. Delegating it here with `by` would
    // recompose this whole composable on every frame of the spring; reading it in draw invalidates
    // the draw phase alone.
    val animatedSpeed = animateFloatAsState(
        targetValue = speedKmh.coerceIn(0f, safeMax),
        animationSpec = hudSpring(),
        label = "hud-speed",
    )
    val effectiveBand = band ?: rememberSpeedBand(animatedSpeed, thresholdKmh).value

    // One scalar carries the whole band character, so a change is a continuous crossfade rather
    // than a jump. A tween, deliberately not hudSpring(): a spring overshooting past 0.5 on a
    // WAITING -> DISTANCE change would flash the FAST colouring on the way.
    val energy = remember { Animatable(effectiveBand.energy) }
    // One bloom on a band change, then hold. This is the entire "something just happened" signal —
    // there is no repeating pulse anywhere in this composable.
    val flash = remember { Animatable(0f) }
    var seenFirstBand by remember { mutableStateOf(false) }
    LaunchedEffect(effectiveBand) {
        if (!seenFirstBand) {
            seenFirstBand = true
            energy.snapTo(effectiveBand.energy)
            return@LaunchedEffect
        }
        launch { energy.animateTo(effectiveBand.energy, tween(BAND_FADE_MS, easing = FastOutSlowInEasing)) }
        flash.snapTo(1f)
        flash.animateTo(0f, tween(BAND_FLASH_MS, easing = FastOutSlowInEasing))
    }

    val glowPaint = rememberHudGlowPaint()
    val holoGlowPaint = rememberHoloGlowPaint()
    val holoParticles = rememberHoloParticles()
    val emberPaint = rememberEmberPaint()
    val clock = rememberSpeedClock(enabled = motion, speed = animatedSpeed, maxKmh = safeMax)
    val labelArgb = CaptainPalette.textSecondary.toArgb()
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val g = HudArcGeometry.fit(this, strokeWidthDp.dp.toPx(), HUD_ARC_INSET.toPx())
            drawHoloRing(g, holoParticles, holoGlowPaint)

            val speed = animatedSpeed.value
            val speedFraction = (speed / safeMax).coerceIn(0f, 1f)
            val e = energy.value
            // Two 0..1 ramps out of the single energy scalar: WAITING->DISTANCE, then
            // DISTANCE->FAST. Every band-dependent value below lerps against these, so nothing
            // branches on the band and the crossfade is continuous.
            val toDistance = (e * 2f).coerceIn(0f, 1f)
            val toFast = (e * 2f - 1f).coerceIn(0f, 1f)

            val litColor = lerp(CaptainPalette.hudAccent, CaptainPalette.neonCyan, toDistance)
            val hotColor = lerp(litColor, CaptainPalette.hudSweepHot, toFast)
            glowPaint.color = hotColor.toArgb()

            // The hum is the FAST band's only new motion, and it is brightness, never position.
            // Amplitude is zero below FAST and its rate is speed-tied, so it stops dead at a stop.
            val hum = HUM_AMPLITUDE * toFast * kotlin.math.sin(clock.hum.value)
            val glowAlpha = 0.55f + 0.45f * speedFraction + BAND_FLASH_GLOW * flash.value + hum

            drawHudArc(
                g, speedFraction, startDeg, sweepDeg, glowPaint,
                glowAlpha = glowAlpha,
                sweepStops = Triple(
                    lerp(CaptainPalette.hudSweepStart, CaptainPalette.hudSweepMid, toFast),
                    lerp(CaptainPalette.hudSweepMid, CaptainPalette.neonCyan, toFast),
                    lerp(lerp(CaptainPalette.hudSweepEnd, CaptainPalette.neonCyan, toDistance),
                        CaptainPalette.hudSweepHot, toFast),
                ),
                glowStrokeMultiplier = 1.5f + 0.3f * toFast,
            )

            if (motion) {
                drawEmber(
                    g, clock.ember.value, speedFraction, startDeg, sweepDeg, emberPaint,
                    // Hidden entirely in WAITING: a taxi charging waiting time shows a calm,
                    // still ring, which is the state the "calm animations" feedback was about.
                    color = lerp(CaptainPalette.hudSweepMid, CaptainPalette.hudSweepHot, toFast),
                    alpha = (235f * toDistance + 20f * toFast).roundToInt().coerceIn(0, 255),
                    strokeMultiplier = 0.9f + 0.2f * toFast,
                )
            }

            // The lit end cap: the "pointer" affordance, welded to the arc end so it introduces no
            // motion of its own — it only moves when the speed does. Chosen over a needle, which
            // would hunt on the spring against a 1 Hz staircase and would have to cross the fare
            // disc at the centre.
            val litDeg = sweepDeg * speedFraction
            if (toDistance > 0f && litDeg >= EMBER_MIN_LIT_DEG) {
                val capRad = Math.toRadians((startDeg + litDeg).toDouble())
                drawCircle(
                    color = hotColor.copy(alpha = toDistance),
                    radius = g.strokePx * 0.45f,
                    center = Offset(
                        g.center.x + cos(capRad).toFloat() * g.radius,
                        g.center.y + sin(capRad).toFloat() * g.radius,
                    ),
                )
            }

            // Ticks + labels — MeterDialArt's geometry.
            val cx = g.center.x
            val cy = g.center.y
            val tickOuter = g.radius - g.strokePx
            val majorLen = 11.dp.toPx()
            val minorLen = 5.dp.toPx()
            // Label size/margin bumped 2026-09-06 (direct feedback: "the speedometer ten, twenty,
            // sixty... should be very prominent, make it big") — the extra 2dp of margin (9dp ->
            // 11dp) gives the bigger glyphs the same clearance from the tick ring the old 10sp
            // size had at 9dp, rather than letting them crowd the major ticks.
            val labelR = tickOuter - majorLen - 11.dp.toPx()
            labelPaint.textSize = 13.sp.toPx()
            labelPaint.color = labelArgb
            val steps = (safeMax / 5f).toInt() // one tick per 5 km/h
            for (i in 0..steps) {
                val kmh = i * 5f
                val major = i % 4 == 0
                val angleDeg = startDeg + sweepDeg * (kmh / safeMax)
                val rad = Math.toRadians(angleDeg.toDouble())
                val dirX = cos(rad).toFloat()
                val dirY = sin(rad).toFloat()
                val len = if (major) majorLen else minorLen
                val lit = kmh <= speed + 0.01f
                drawLine(
                    color = if (lit) hotColor else CaptainPalette.hudTrack,
                    start = Offset(cx + dirX * tickOuter, cy + dirY * tickOuter),
                    end = Offset(cx + dirX * (tickOuter - len), cy + dirY * (tickOuter - len)),
                    strokeWidth = (if (major) 2.5.dp else 1.5.dp).toPx() * (1f + 0.25f * toFast),
                    cap = StrokeCap.Round,
                )
                if (major && showLabels) {
                    val lx = cx + dirX * labelR
                    val ly = cy + dirY * labelR - (labelPaint.ascent() + labelPaint.descent()) / 2f
                    drawContext.canvas.nativeCanvas.drawText(kmh.roundToInt().toString(), lx, ly, labelPaint)
                }
            }

            // A static marker at the tariff's own waiting/distance line, so the speed where the
            // meter changes what it charges is visible on the dial rather than only implied by the
            // colour change. Drawn last so it sits over the tick it shares a position with.
            val thresholdFraction = (thresholdKmh.toFloat() / safeMax).coerceIn(0f, 1f)
            if (thresholdFraction > 0f && thresholdFraction < 1f) {
                val tRad = Math.toRadians((startDeg + sweepDeg * thresholdFraction).toDouble())
                val tx = cos(tRad).toFloat()
                val ty = sin(tRad).toFloat()
                drawLine(
                    color = CaptainPalette.textSecondary,
                    start = Offset(cx + tx * (tickOuter + g.strokePx * 0.35f), cy + ty * (tickOuter + g.strokePx * 0.35f)),
                    end = Offset(cx + tx * (tickOuter - majorLen), cy + ty * (tickOuter - majorLen)),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        content()
    }
}

/**
 * The [SpeedBand] for a smoothed speed, with its hysteresis carried across frames.
 *
 * Collected off a `snapshotFlow` on the spring's own [State], so the policy is evaluated on
 * animation frames rather than on recomposition — and banded on the SMOOTHED speed, never the raw
 * 1 Hz GPS staircase, which would flap across a threshold several times a second.
 *
 * Public so [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen]'s dial can compute it once
 * and pass it both to [GlowingSpeedometer] and to its own numeric readout — one band, one truth,
 * rather than two evaluations that could disagree mid-transition.
 */
@Composable
fun rememberSpeedBand(speed: State<Float>, thresholdKmh: Double): State<SpeedBand> {
    val band = remember { mutableStateOf(SpeedBand.initial(speed.value.toDouble(), thresholdKmh)) }
    LaunchedEffect(thresholdKmh) {
        snapshotFlow { speed.value }.collect { current ->
            band.value = SpeedBand.next(band.value, current.toDouble(), thresholdKmh)
        }
    }
    return band
}

/** Band crossfade. Long enough to read as a change of state rather than a flicker, short enough
 * that a driver accelerating through 26 km/h sees it happen. */
private const val BAND_FADE_MS = 700

/** The single bloom on a band change. */
private const val BAND_FLASH_MS = 500
private const val BAND_FLASH_GLOW = 0.25f

/** How far the FAST band's brightness hum swings, as a fraction of glow alpha. Small on purpose:
 * the passenger is reading the fare in the middle of this ring. */
private const val HUM_AMPLITUDE = 0.06f

// ------------------------------------------------------------------------------------------
// Ember motion (2026-09-06) — see GlowingSpeedometer's [motion] doc for why this is safe to
// try again after the reverted always-orbiting highlight.
// ------------------------------------------------------------------------------------------

/** How far the ember can drift per second at full speed, as a fraction of the *lit* arc's own
 * length (not the whole ring) — 0.35 laps/sec of the lit stretch at 120 km/h, scaling linearly
 * down to a dead stop at 0 km/h. Deliberately slow: this is a shimmer inside the neon tube, not
 * a marker racing around the dial. */
private const val EMBER_MAX_CYCLES_PER_SEC = 0.35f

/**
 * Two phases that only advance while the vehicle is moving — the dial's single frame loop.
 *
 * ### The bug this replaces
 * `rememberEmberPhase` took `speedKmh` as a plain `Float` parameter and read it inside a
 * `withFrameNanos` loop launched by `LaunchedEffect(enabled)`. The coroutine captured whatever the
 * speed was at the composition that started it — on the meter screen, 0 — and every later
 * recomposition passed a fresh value to a fresh call while the running loop kept the stale one. So
 * `cyclesPerSec` was `0.35 * 0` for the life of the screen and the ember never moved. The one
 * speed-reactive animation the dial had was static on the tablet.
 *
 * [speed] is a [State] here, read inside the loop, so it tracks.
 *
 * ### Why an accumulator rather than an animation spec
 * Both phases advance at a *rate* proportional to speed, and a rate is not something
 * `animateFloatAsState` or an `infiniteRepeatable` can express — those animate a value toward a
 * target over a duration. More importantly, an accumulator makes the calm-motion rule structural:
 * every delta is multiplied by `speedFraction`, so at a standstill both phases stop advancing
 * exactly, and the dial is pixel-static. There is no decorative loop anywhere in this file that
 * could keep running with the vehicle stopped.
 */
@Composable
private fun rememberSpeedClock(
    enabled: Boolean,
    speed: State<Float>,
    maxKmh: Float,
): SpeedClock {
    val ember = remember { mutableFloatStateOf(0f) }
    val hum = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled, maxKmh) {
        if (!enabled) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { nowNanos ->
                val dtSeconds = ((nowNanos - lastNanos).coerceAtLeast(0)) / 1_000_000_000f
                lastNanos = nowNanos
                val speedFraction = (speed.value / maxKmh).coerceIn(0f, 1f)
                val twoPiDt = 2f * Math.PI.toFloat() * dtSeconds
                ember.floatValue += EMBER_MAX_CYCLES_PER_SEC * speedFraction * twoPiDt
                hum.floatValue += HUM_MAX_CYCLES_PER_SEC * speedFraction * twoPiDt
            }
        }
    }
    return remember(ember, hum) { SpeedClock(ember, hum) }
}

/** The two phases [rememberSpeedClock] advances. Read in the draw lambda, never in composition. */
@Stable
private class SpeedClock(val ember: State<Float>, val hum: State<Float>)

/** Top rate of the FAST band's brightness hum. Faster than the ember because it is a change in
 * intensity rather than position — the eye tolerates that far better, which is the whole reason
 * the energetic band brightens rather than moving anything new. */
private const val HUM_MAX_CYCLES_PER_SEC = 0.8f

/** The ember's own small blurred paint — same [BlurMaskFilter] technique as [rememberHudGlowPaint]
 * but a tighter radius and the sweep's mid colour, so it reads as one bright bead of light inside
 * the existing glow rather than a second, competing halo. */
@Composable
private fun rememberEmberPaint(): android.graphics.Paint = remember {
    Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        color = CaptainPalette.hudSweepMid.toArgb()
        maskFilter = BlurMaskFilter(HUD_GLOW_BLUR_PX / 2f, BlurMaskFilter.Blur.NORMAL)
    }
}

/**
 * Draws one soft spark at a position oscillating along the *lit* stretch of the arc only
 * (`[startDeg, startDeg + sweepDeg * speedFraction]`) — never on the dark, off-track portion, so
 * it always reads as motion inside the existing glow, never a marker escaping it. `sin(phase)`
 * maps to `[0, 1]` for a smooth back-and-forth drift (never a hard reset/jump at the ends, unlike
 * a sawtooth). Skipped entirely below [EMBER_MIN_LIT_DEG] of lit arc — at a near-standstill there
 * is nothing to travel along, matching the calm-glow pass's "sitting still shows a static ring".
 */
private fun DrawScope.drawEmber(
    g: HudArcGeometry,
    phase: Float,
    speedFraction: Float,
    startDeg: Float,
    sweepDeg: Float,
    paint: android.graphics.Paint,
    color: Color,
    alpha: Int,
    strokeMultiplier: Float,
) {
    if (alpha <= 0) return
    val litDeg = sweepDeg * speedFraction
    if (litDeg < EMBER_MIN_LIT_DEG) return
    val t = (kotlin.math.sin(phase) + 1f) / 2f
    val angleDeg = startDeg + litDeg * t
    val rad = Math.toRadians(angleDeg.toDouble())
    val x = g.center.x + cos(rad).toFloat() * g.radius
    val y = g.center.y + sin(rad).toFloat() * g.radius
    drawIntoCanvas { canvas ->
        paint.strokeWidth = g.strokePx * strokeMultiplier
        paint.color = color.toArgb()
        paint.alpha = alpha
        canvas.nativeCanvas.drawPoint(x, y, paint)
    }
}

private const val EMBER_MIN_LIT_DEG = 8f

/** Breathing room between the arc's outer stroke edge and the composable's bounds. */
private val HUD_ARC_INSET: Dp = 6.dp

// ============================================================================================
// 2. RollingMoneyText
// ============================================================================================

/** Which way a digit slot rolls when its glyph changes. */
enum class RollDirection { UP, DOWN, NONE }

/**
 * The pure digit-diff logic behind [RollingMoneyText] — a plain object (its own class file, no
 * Android/Compose dependency) so `HudRollTest` can exercise it on the JVM.
 *
 * Slots are compared **right-aligned**: money strings change length on the left ("$9.95" →
 * "$10.05") while the cents stay put, so the units/tens/cents columns keep their identity.
 */
object HudRoll {
    /** Direction for one slot: digit-vs-digit compares numerically; a non-digit target never
     * rolls; a digit appearing where there was no/none-digit glyph rolls [RollDirection.UP]. */
    fun direction(from: Char?, to: Char): RollDirection {
        if (!to.isDigit()) return RollDirection.NONE
        if (from == null || !from.isDigit()) return RollDirection.UP
        return when {
            to > from -> RollDirection.UP
            to < from -> RollDirection.DOWN
            else -> RollDirection.NONE
        }
    }

    /** One [RollDirection] per character of [current], comparing each slot to the right-aligned
     * counterpart in [previous] (or none, when [previous] is null or shorter). */
    fun plan(previous: String?, current: String): List<RollDirection> =
        current.indices.map { i ->
            val fromEnd = current.length - 1 - i
            val prevIndex = if (previous == null) -1 else previous.length - 1 - fromEnd
            val from = if (previous != null && prevIndex >= 0) previous[prevIndex] else null
            direction(from, current[i])
        }
}

/**
 * Slot-machine money figure. Every **digit** sits in its own fixed-width slot (width measured
 * from "0" in the same style, plus `tnum` tabular figures for fonts that support it) and swaps
 * through `AnimatedContent`: rolling up when the digit increases, down when it decreases, with
 * `SizeTransform(clip = false)` so the sliding glyph is never cropped. Non-digits (`$`, `.`, `,`)
 * are plain, un-animated text at their natural width. Slots are keyed from the **right** so a
 * length change ("$9.95" → "$10.05") shifts nothing in the cents columns.
 */
@Composable
fun RollingMoneyText(
    amount: String,
    fontSize: TextUnit = 44.sp,
    color: Color = CaptainPalette.textPrimary,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily = ChakraPetch,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    val style = remember(fontSize, color, fontFamily, fontWeight) {
        TextStyle(
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            fontSize = fontSize,
            color = color,
            fontFeatureSettings = "tnum",
        )
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digitWidth: Dp = remember(style, density) {
        with(density) { measurer.measure(AnnotatedString("0"), style, softWrap = false).size.width.toDp() }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        amount.forEachIndexed { index, ch ->
            val slot = amount.length - 1 - index
            key(slot) {
                if (ch.isDigit()) {
                    Box(modifier = Modifier.width(digitWidth), contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = ch,
                            transitionSpec = {
                                val sign = if (HudRoll.direction(initialState, targetState) == RollDirection.DOWN) -1 else 1
                                (slideInVertically(tween(HUD_ROLL_MS)) { h -> sign * h } + fadeIn(tween(HUD_ROLL_MS)))
                                    .togetherWith(slideOutVertically(tween(HUD_ROLL_MS)) { h -> -sign * h } + fadeOut(tween(HUD_ROLL_MS * 2 / 3)))
                                    .using(SizeTransform(clip = false))
                            },
                            label = "hud-roll-$slot",
                        ) { glyph ->
                            Text(glyph.toString(), style = style, softWrap = false, maxLines = 1, overflow = TextOverflow.Visible)
                        }
                    }
                } else {
                    Text(ch.toString(), style = style, softWrap = false, maxLines = 1)
                }
            }
        }
    }
}

private const val HUD_ROLL_MS = 220

// ============================================================================================
// 3. GlassCard
// ============================================================================================

/**
 * The floating-over-map glass surface: [CaptainPalette.hudGlass] fill (80% alpha, so the map
 * reads through), a fixed decorative blurred "sheen" corner highlight, a 1dp purple→cyan low-alpha
 * gradient border, and an optional outer neon halo via the existing [neonGlow] (placed before
 * `clip` so it lands outside the surface).
 *
 * **No backdrop blur of the content behind the card, deliberately.** Compose's `Modifier.blur()`
 * blurs the content of the layer it's applied to — there is no "blur what's behind me" modifier —
 * and the map behind a card is a separate `AndroidView` (`MapView`) whose pixels a Compose layer
 * can't sample. The only ways to fake THAT are (a) blurring the card *including* its own text,
 * which is unacceptable, or (b) a `RenderEffect` blur on the full-screen map layer, which is
 * banned for the SM-T575 frame budget. So the glass surface's own text/content is never blurred.
 *
 * **[sheenBlurDp] is a real, small, bounded `Modifier.blur()`** (2026-09-07 futuristic-HUD pass,
 * design brief item 3: "a background blur effect (`Modifier.blur`)") — a fixed-size ([SHEEN_SIZE])
 * soft cyan/purple highlight blob in the card's top-left corner, clipped to the card's own shape so
 * it never spills over real content. Its cost is bounded by its own small, constant size regardless
 * of the card's size, unlike blurring the whole panel (or the map behind it), so it doesn't reopen
 * the SM-T575 frame-budget concern above — it's a decorative accent layer, not a backdrop blur.
 * Static (no animation): drawn once per composition, never re-blurred on a clock.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadiusDp: Int = 20,
    glow: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    Box(
        modifier = modifier
            .then(if (glow != null) Modifier.neonGlow(glow, cornerRadiusDp.dp) else Modifier)
            .clip(shape)
            .background(CaptainPalette.hudGlass),
    ) {
        Box(
            modifier = Modifier
                .size(GLASS_SHEEN_SIZE)
                .align(Alignment.TopStart)
                .offset(x = -(GLASS_SHEEN_SIZE / 3), y = -(GLASS_SHEEN_SIZE / 3))
                .blur(GLASS_SHEEN_BLUR)
                .background(
                    Brush.radialGradient(listOf(CaptainPalette.neonCyanGlowStrong, Color.Transparent)),
                    CircleShape,
                ),
        )
        // Deliberately NOT matchParentSize(): a matchParentSize child is excluded from Compose's
        // own Box-sizing pass, so a caller that (like the header status pill and the GPS/network
        // strip below it) sets only a height and relies on its content to determine width would
        // collapse to whatever the fixed-size sheen blob above dictates instead -- which is exactly
        // the bug this fixes (2026-09-07: "ON BREAK" wrapping to one letter per line, and the
        // GPS/network/printer/battery strip clipped down to just "GPS", both live on-device right
        // after the futuristic-HUD reskin merged). Plain content-sized Box: a caller that DOES force
        // an explicit size via its own `modifier` (most cards) behaves identically either way, since
        // Box passes the same resolved constraints to every non-matchParentSize child regardless.
        Box(content = content)
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(listOf(CaptainPalette.hudGlassBorderPurple, CaptainPalette.hudGlassBorderWhite)),
                    shape = shape,
                ),
        )
    }
}

/** Fixed size of [GlassCard]'s decorative blurred sheen corner highlight — small and constant
 * regardless of the card's own size, so its `Modifier.blur()` cost stays bounded (see that
 * composable's own doc). */
private val GLASS_SHEEN_SIZE = 96.dp
private val GLASS_SHEEN_BLUR = 28.dp

// ============================================================================================
// 4. GlowLineLayers (Mapbox)
// ============================================================================================

/**
 * The blueprint's polyline-glow rule for Mapbox: a route is TWO line annotations on one
 * [PolylineAnnotationManager] — a wide, low-alpha "glow" line under a thin bright line — using
 * the same annotation-manager API the meter backdrop map already uses
 * (`ui/screens/hired/MeterBackdropMap.kt`), with explicit `lineSortKey`s so the bright line is
 * always on top regardless of creation order. Defaults are that map's on-device-tuned widths.
 *
 * Not a composable (it has no UI of its own), so it has no `@Preview`; a `MapView` can't be
 * previewed anyway.
 */
object GlowLineLayers {
    const val GLOW_WIDTH = 14.0
    const val GLOW_OPACITY = 0.28
    const val LINE_WIDTH = 4.0
    const val LINE_OPACITY = 0.95

    /** The two annotation option sets (glow first, line second) for [points]. */
    fun options(
        points: List<Point>,
        color: Color = CaptainPalette.hudAccent,
        glowWidth: Double = GLOW_WIDTH,
        glowOpacity: Double = GLOW_OPACITY,
        lineWidth: Double = LINE_WIDTH,
        lineOpacity: Double = LINE_OPACITY,
    ): List<PolylineAnnotationOptions> {
        val hex = color.toMapboxHex()
        return listOf(
            PolylineAnnotationOptions()
                .withPoints(points)
                .withLineColor(hex)
                .withLineWidth(glowWidth)
                .withLineOpacity(glowOpacity)
                .withLineSortKey(0.0),
            PolylineAnnotationOptions()
                .withPoints(points)
                .withLineColor(hex)
                .withLineWidth(lineWidth)
                .withLineOpacity(lineOpacity)
                .withLineSortKey(1.0),
        )
    }

    /** Creates both layers on [manager]; returns nothing for fewer than two points (a line needs
     * two vertices — Mapbox would otherwise log and drop it). */
    fun create(
        manager: PolylineAnnotationManager,
        points: List<Point>,
        color: Color = CaptainPalette.hudAccent,
        glowWidth: Double = GLOW_WIDTH,
        glowOpacity: Double = GLOW_OPACITY,
        lineWidth: Double = LINE_WIDTH,
        lineOpacity: Double = LINE_OPACITY,
    ): List<PolylineAnnotation> {
        if (points.size < 2) return emptyList()
        return manager.create(options(points, color, glowWidth, glowOpacity, lineWidth, lineOpacity))
    }
}

/** Extension form of [GlowLineLayers.create] for call sites holding the manager. */
fun PolylineAnnotationManager.createGlowLine(
    points: List<Point>,
    color: Color = CaptainPalette.hudAccent,
    glowWidth: Double = GlowLineLayers.GLOW_WIDTH,
    glowOpacity: Double = GlowLineLayers.GLOW_OPACITY,
    lineWidth: Double = GlowLineLayers.LINE_WIDTH,
    lineOpacity: Double = GlowLineLayers.LINE_OPACITY,
): List<PolylineAnnotation> = GlowLineLayers.create(this, points, color, glowWidth, glowOpacity, lineWidth, lineOpacity)

/** Mapbox annotation colour strings are CSS hex (alpha goes through the opacity property). */
fun Color.toMapboxHex(): String = "#%06X".format(0xFFFFFF and toArgb())

// ============================================================================================
// 5. HudStatusPill / HudStatTile
// ============================================================================================

/**
 * Small glass status pill — a [PulsingDot] in the tone colour, a muted upper-case [label] and the
 * [value] in the tone colour ("SYSTEM STATUS · ONLINE", "GPS · 12 sats"). 56dp tall for the
 * elderly-friendly touch standard the rest of the app's chips follow. Non-neutral tones get a
 * matching outer halo via [GlassCard]'s `glow`.
 */
@Composable
fun HudStatusPill(
    label: String,
    value: String,
    tone: HudTone = HudTone.Neutral,
    modifier: Modifier = Modifier,
    pulsing: Boolean = tone != HudTone.Neutral,
) {
    val toneColor = tone.color()
    GlassCard(
        modifier = modifier.height(56.dp),
        cornerRadiusDp = 28,
        glow = if (tone == HudTone.Neutral) null else toneColor,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulsingDot(color = toneColor, animated = pulsing, size = 10.dp)
            Text(
                label.uppercase(),
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(start = 10.dp),
            )
            Text(
                value,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = toneColor,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/**
 * Glass stat tile for the header/bottom bar cells (SHIFT TIME / TRIPS / EARNINGS / NEXT BREAK):
 * icon + muted upper-case [label] on top, a large [value], an optional [sub] line, and — when
 * [ring] (0..1) is given — a small spring-animated full-circle HUD ring on the right (the NEXT
 * BREAK countdown), drawn with the same track → blur glow → sweep passes as the big gauge.
 *
 * Chrome pass (2026-09-04), additive: [valueFontSize] (default unchanged, 24sp — the dashboard's
 * bottom bar reads at arm's length and asks for 32sp) and an optional [footer] slot laid out under
 * the sub line inside the text column, for the one extra element a bar cell carries (SHIFT TIME's
 * thin elapsed-vs-limit bar, TRIPS' "N Active" pill, EARNINGS' day-over-day delta). The inner row
 * also now fills the host's bounds so a tile given a fixed height centres its content instead of
 * hugging the top edge; with no height given it still wraps exactly as before.
 */
@Composable
fun HudStatTile(
    icon: ImageVector,
    label: String,
    value: String,
    sub: String? = null,
    ring: Float? = null,
    tone: HudTone = HudTone.Accent,
    modifier: Modifier = Modifier,
    valueFontSize: TextUnit = 24.sp,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) = HudStatTile(
    icon = icon,
    label = label,
    sub = sub,
    ring = ring,
    tone = tone,
    modifier = modifier,
    footer = footer,
    value = {
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = valueFontSize,
            color = CaptainPalette.textPrimary,
            maxLines = 1,
        )
    },
)

/**
 * [HudStatTile] with the value as a **composable slot** rather than a `String` (A4, 2026-09-08).
 *
 * WHY THE SLOT EXISTS. A3's home-screen rebuild needed three stat cells whose value is not a
 * static string: EARNINGS rolls per digit through [RollingMoneyText] when a fare closes, TRIPS
 * carries a one-shot scale pop the moment the count increments, and SHIFT TIME renders its elapsed
 * clock on the shared [Type] scale. The `value: String` overload above cannot express any of them.
 * Because `ui/theme/Hud.kt` belongs to this workstream and not to A3's, that pass could not reach
 * across the ownership boundary and instead took a **local copy** — `HomeStatTile` in
 * `ShiftStatsBar.kt` — with a doc that said, verbatim, *"when A3 and A4 have both merged, fold this
 * back into HudStatTile and delete it"*. This is that fold: the slot lands here, the three call
 * sites move onto it, and the local copy is gone. There is now one stat tile in the app again.
 *
 * The `String` overload above is kept and delegates here, so the fifteen-odd existing call sites
 * that genuinely do just want a static numeral are untouched.
 *
 * Two details carried over from A3's copy rather than from the older shared one, because both were
 * fixes rather than preferences:
 * - the icon's `contentDescription` is the tile's own [label], not `null`. The audit (§6) counted
 *   85 `null` descriptions across `ui/` and called the result "a wall of unlabelled buttons"; the
 *   label is available right here and is the correct spoken name for the glyph beside it.
 * - the label renders at [Type.tiny] — **12sp**, the accessibility floor — where the older shared
 *   tile hardcoded 11sp.
 */
@Composable
fun HudStatTile(
    icon: ImageVector,
    label: String,
    value: @Composable () -> Unit,
    sub: String? = null,
    ring: Float? = null,
    tone: HudTone = HudTone.Accent,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val toneColor = tone.color()
    GlassCard(modifier = modifier, cornerRadiusDp = 18) {
        // fillMaxWidth + align(Center), NOT fillMaxSize (2026-09-08). fillMaxSize makes this Row
        // take the FULL height it is offered, so the tile is only as tall as its content while a
        // caller pins an exact height -- and grows without limit the moment one doesn't. A3
        // rightly moved ShiftStatsBar from `height(120.dp)` to `heightIn(min = 120.dp)` so the
        // NEXT BREAK cell can grow at a large system font scale; the two together made the stats
        // bar swallow the entire screen. Column measures that unweighted bar before the weighted
        // row above it, so the meter pane -- START METER included -- was measured at zero height
        // and never drawn. Wrapping the height keeps the font-scale growth and gives the space
        // back. Centering preserves the look for the callers that DO pin a height.
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = label, tint = toneColor, modifier = Modifier.size(16.dp))
                    Text(
                        label.uppercase(),
                        style = Type.tiny,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Box(modifier = Modifier.padding(top = 4.dp)) { value() }
                if (sub != null) {
                    Text(
                        sub,
                        style = Type.tiny,
                        color = CaptainPalette.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (footer != null) footer()
            }
            if (ring != null) {
                Spacer(Modifier.width(12.dp))
                HudRing(progress = ring, modifier = Modifier.size(52.dp))
            }
        }
    }
}

/** A full-circle mini gauge (12 o'clock start) — [GlowingMeterGauge]'s three passes at tile
 * scale; used by [HudStatTile]'s `ring`, exposed for any other "small progress halo" need. */
@Composable
fun HudRing(progress: Float, modifier: Modifier = Modifier, strokeWidthDp: Int = 5) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = hudSpring(),
        label = "hud-ring",
    )
    val glowPaint = rememberHudGlowPaint(blurRadiusPx = HUD_GLOW_BLUR_PX / 3f)
    Canvas(modifier = modifier) {
        val g = HudArcGeometry.fit(this, strokeWidthDp.dp.toPx(), 2.dp.toPx())
        drawHudArc(g, animated, startDeg = -90f, sweepDeg = 360f, glowPaint = glowPaint, glowAlpha = 0.7f)
    }
}

// ============================================================================================
// Previews — one per component, on the HUD background
//
// Light/Dark day pass (2026-09-04): every preview below now explicitly calls
// CaptainPalette.applyTheme(...) as its first statement (dark previews included) rather than
// relying on whatever the object's ambient state happens to be — Compose Preview can render
// multiple @Preview functions in one process (interactive/gallery preview), and CaptainPalette is
// global mutable state, so a preview that assumed "the default is dark" could silently show the
// wrong theme depending on render order. The *Light previews are the direct side-by-side proof the
// glow-technique swap in drawHudArc/rememberHudGlowPaint (neon blur -> crisp lit core + soft
// shadow) reads correctly against a real light background, not just in isolation.
// ============================================================================================

@Preview(name = "Meter gauge — dark", widthDp = 320, heightDp = 320, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewGlowingMeterGauge() {
    CaptainPalette.applyTheme(isLight = false)
    GlowingMeterGauge(progress = 0.62f, modifier = Modifier.size(300.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FARE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = CaptainPalette.textMuted)
            RollingMoneyText(amount = "\$18.65", fontSize = 48.sp)
        }
    }
}

@Preview(name = "Meter gauge — light", widthDp = 320, heightDp = 320, backgroundColor = 0xFFF4F3F8, showBackground = true)
@Composable
private fun PreviewGlowingMeterGaugeLight() {
    CaptainPalette.applyTheme(isLight = true)
    GlowingMeterGauge(progress = 0.62f, modifier = Modifier.size(300.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FARE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = CaptainPalette.textMuted)
            RollingMoneyText(amount = "\$18.65", fontSize = 48.sp)
        }
    }
}

@Preview(name = "Speedometer — dark", widthDp = 360, heightDp = 360, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewGlowingSpeedometer() {
    CaptainPalette.applyTheme(isLight = false)
    GlowingSpeedometer(speedKmh = 57f, modifier = Modifier.size(340.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("57", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 56.sp, color = CaptainPalette.textPrimary)
            Text("km/h", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CaptainPalette.textMuted)
        }
    }
}

@Preview(name = "Speedometer — light", widthDp = 360, heightDp = 360, backgroundColor = 0xFFF4F3F8, showBackground = true)
@Composable
private fun PreviewGlowingSpeedometerLight() {
    CaptainPalette.applyTheme(isLight = true)
    GlowingSpeedometer(speedKmh = 57f, modifier = Modifier.size(340.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("57", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 56.sp, color = CaptainPalette.textPrimary)
            Text("km/h", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CaptainPalette.textMuted)
        }
    }
}

@Preview(widthDp = 320, heightDp = 120, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewRollingMoneyText() {
    CaptainPalette.applyTheme(isLight = false)
    // Tap-free demo: the figure steps once shortly after composition so the roll is visible in an
    // interactive preview; a static render shows the resting state.
    var amount by remember { mutableStateOf("\$18.65") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(900)
        amount = "\$19.05"
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        RollingMoneyText(amount = amount, fontSize = 56.sp)
    }
}

@Preview(name = "Glass card — dark", widthDp = 360, heightDp = 200, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewGlassCard() {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxSize(), glow = CaptainPalette.hudAccent) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NEXT PICKUP", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = CaptainPalette.textMuted)
                Text("12 Bay St, Glebe", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
                Text("4 min · 1.8 km", fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textSecondary)
            }
        }
    }
}

/** Light-mode side-by-side of [PreviewGlassCard] — the direct proof [neonGlow]'s crisp-ring +
 * soft-shadow substitution (see that function's doc) reads correctly against a real light
 * background instead of the dark-mode halo. */
@Preview(name = "Glass card — light", widthDp = 360, heightDp = 200, backgroundColor = 0xFFF4F3F8, showBackground = true)
@Composable
private fun PreviewGlassCardLight() {
    CaptainPalette.applyTheme(isLight = true)
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxSize(), glow = CaptainPalette.hudAccent) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("NEXT PICKUP", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = CaptainPalette.textMuted)
                Text("12 Bay St, Glebe", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
                Text("4 min · 1.8 km", fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textSecondary)
            }
        }
    }
}

@Preview(widthDp = 560, heightDp = 96, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewHudStatusPill() {
    CaptainPalette.applyTheme(isLight = false)
    Row(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        HudStatusPill(label = "System", value = "ONLINE", tone = HudTone.Success)
        HudStatusPill(label = "GPS", value = "12 sats", tone = HudTone.Neutral)
        HudStatusPill(label = "Net", value = "WEAK", tone = HudTone.Warning)
    }
}

@Preview(widthDp = 600, heightDp = 120, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewHudStatTile() {
    CaptainPalette.applyTheme(isLight = false)
    Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HudStatTile(icon = Icons.Rounded.Schedule, label = "Shift time", value = "4h 12m", sub = "Started 06:40", modifier = Modifier.weight(1f))
        HudStatTile(icon = Icons.Rounded.LocalTaxi, label = "Trips", value = "9", sub = "\$212.40 earned", tone = HudTone.Success, modifier = Modifier.weight(1f))
        HudStatTile(icon = Icons.Rounded.Schedule, label = "Next break", value = "38m", ring = 0.7f, tone = HudTone.Warning, modifier = Modifier.weight(1f))
    }
}

@Preview(widthDp = 120, heightDp = 120, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewHudRing() {
    CaptainPalette.applyTheme(isLight = false)
    val p = 0.7f
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HudRing(progress = p, modifier = Modifier.size(80.dp))
    }
}
