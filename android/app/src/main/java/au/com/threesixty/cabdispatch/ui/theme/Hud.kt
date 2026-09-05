package au.com.threesixty.cabdispatch.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
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
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotation
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
            glowPaint.strokeWidth = g.strokePx * 1.5f
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
                    0f to CaptainPalette.hudSweepStart,
                    full * 0.5f to CaptainPalette.hudSweepMid,
                    full to CaptainPalette.hudSweepEnd,
                    1f to CaptainPalette.hudSweepEnd,
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
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val g = HudArcGeometry.fit(this, strokeWidthDp.dp.toPx(), HUD_ARC_INSET.toPx())
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
    content: @Composable BoxScope.() -> Unit = {},
) {
    val safeMax = maxKmh.coerceAtLeast(5f)
    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh.coerceIn(0f, safeMax),
        animationSpec = hudSpring(),
        label = "hud-speed",
    )
    val glowPaint = rememberHudGlowPaint()
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
            val speedFraction = (animatedSpeed / safeMax).coerceIn(0f, 1f)
            drawHudArc(g, speedFraction, startDeg, sweepDeg, glowPaint, glowAlpha = 0.55f + 0.45f * speedFraction)

            // Ticks + labels — MeterDialArt's geometry.
            val cx = g.center.x
            val cy = g.center.y
            val tickOuter = g.radius - g.strokePx
            val majorLen = 11.dp.toPx()
            val minorLen = 5.dp.toPx()
            val labelR = tickOuter - majorLen - 9.dp.toPx()
            labelPaint.textSize = 10.sp.toPx()
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
                val lit = kmh <= animatedSpeed + 0.01f
                drawLine(
                    color = if (lit) CaptainPalette.hudAccent else CaptainPalette.hudTrack,
                    start = Offset(cx + dirX * tickOuter, cy + dirY * tickOuter),
                    end = Offset(cx + dirX * (tickOuter - len), cy + dirY * (tickOuter - len)),
                    strokeWidth = (if (major) 2.5.dp else 1.5.dp).toPx(),
                    cap = StrokeCap.Round,
                )
                if (major && showLabels) {
                    val lx = cx + dirX * labelR
                    val ly = cy + dirY * labelR - (labelPaint.ascent() + labelPaint.descent()) / 2f
                    drawContext.canvas.nativeCanvas.drawText(kmh.roundToInt().toString(), lx, ly, labelPaint)
                }
            }
        }
        content()
    }
}

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
 * reads through), a 1dp purple→white low-alpha gradient border, and an optional outer neon halo
 * via the existing [neonGlow] (placed before `clip` so it lands outside the surface).
 *
 * **No backdrop blur, deliberately.** Compose's `Modifier.blur(16.dp)` blurs the content of the
 * layer it's applied to — there is no "blur what's behind me" modifier — and the map behind a
 * card is a separate `AndroidView` (`MapView`) whose pixels a Compose layer can't sample. The only
 * ways to fake it are (a) blurring the card *including* its own text, which is unacceptable, or
 * (b) a `RenderEffect` blur on the full-screen map layer, which is banned for the SM-T575 frame
 * budget. So the glass effect is the alpha fill + gradient border alone.
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
            .background(CaptainPalette.hudGlass)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(CaptainPalette.hudGlassBorderPurple, CaptainPalette.hudGlassBorderWhite)),
                shape = shape,
            ),
        content = content,
    )
}

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
) {
    val toneColor = tone.color()
    GlassCard(modifier = modifier, cornerRadiusDp = 18) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = toneColor, modifier = Modifier.size(16.dp))
                    Text(
                        label.uppercase(),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    value,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = valueFontSize,
                    color = CaptainPalette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (sub != null) {
                    Text(
                        sub,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
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
