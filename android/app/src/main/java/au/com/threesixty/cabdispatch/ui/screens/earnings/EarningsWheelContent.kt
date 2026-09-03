package au.com.threesixty.cabdispatch.ui.screens.earnings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.dao.TripPeriod
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.EARNINGS] wheel-slot content — Phase C
 * (2026-09-03) real-data rebuild. Was 4 flat stat tiles (spec TCT-DRIVER-APP-01.md §4's
 * "today's total, card split, cash split, trip count") with no period selection, trend, or
 * breakdown; now a real period-tab hero + hand-drawn trend chart + summary + breakdown, all fed by
 * [EarningsWheelViewModel] off the same real [au.com.threesixty.cabdispatch.data.local.entity.TripEntity]
 * rows (see that ViewModel's doc for the exact date-range query, shared with the History pane's
 * filter pills).
 *
 * The trend chart is hand-drawn Compose `Canvas` — no charting library exists or is added here
 * (`build.gradle.kts` deliberately carries none); the glow/gradient technique below follows this
 * app's existing radial-gauge Canvas precedent (`HiredScreen.kt`'s `MeterDialGlow`: a soft
 * gradient wash plus layered alpha strokes, not a real blur filter) adapted to a line/area shape
 * rather than a dial.
 *
 * "Custom" period is shown as a locked/disabled pill, not a working date-range picker — this
 * app's existing ethos for a mockup row that isn't cheaply buildable this pass (matching the
 * Settings screen's "Coming soon" treatment for its own decorative rows) rather than a fake
 * affordance that does nothing when tapped.
 *
 * **Premium pass (2026-09-03, plan Phase 4).** The screen used none of the app's shared
 * vocabulary — no [CaptainPanel], no [gameClick], no glow, no motion — so it read flat beside the
 * Meter screen. Now: the hero figure sits on a radial accent bloom, carries a text glow and is
 * sized to be the unmistakable focal point; the trend chart keeps its hand-drawn Canvas but gains
 * a deeper two-stop area fill, a baseline, a gradient-stroked brighter line and a breathing halo
 * on the latest point; summary and breakdown are real [CaptainPanel]s, and breakdown rows are now
 * dot-bulleted in exactly the construction the Meter screen's fare breakdown uses
 * (`HiredScreen.BreakdownRow`). **Strictly visual** — every figure still comes from the same
 * [EarningsWheelUiState] field, computed the same way, and no new control was added.
 */
@Composable
fun EarningsWheelContent(
    modifier: Modifier = Modifier,
    viewModel: EarningsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CaptainPalette.accent)
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PeriodTabs(selected = state.period, onSelect = viewModel::setPeriod)
        EarningsHeroCard(state)
        EarningsTrendCard(state.trend)
        SummaryCard(state)
        BreakdownCard(state)
    }
}

@Composable
private fun PeriodTabs(selected: TripPeriod, onSelect: (TripPeriod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TripPeriod.entries.forEach { p ->
            PeriodPill(label = p.label, selected = p == selected, enabled = true, onClick = { onSelect(p) })
        }
        // "Custom" — real date-range picking isn't built this pass (no charting/date-picker
        // dependency exists yet to build it on cheaply) — shown locked rather than fake-functional.
        PeriodPill(label = "Custom", selected = false, enabled = false, onClick = {})
    }
}

/**
 * Same pill as `ZonesTabPill` / `VoucherTabPill` / the Trips History filter pill — 44dp,
 * fully-round, 16dp padding, 13sp bold uppercase. The premium pass adds only shared feel: a
 * [gameClick] press on a real pill and a soft glow on the selected one. The locked "Custom" pill
 * stays genuinely non-clickable (no `gameClick`, no glow) so it cannot be mistaken for a live one.
 */
@Composable
private fun PeriodPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CaptainPalette.primary else CaptainPalette.raised
    val textColor = when {
        selected -> CaptainPalette.textPrimary
        !enabled -> CaptainPalette.textMuted
        else -> CaptainPalette.textSecondary
    }
    Box(
        modifier = Modifier
            .height(44.dp)
            .then(if (selected) Modifier.neonGlow(CaptainPalette.primary, 999.dp, strength = 0.8f, spread = 4.dp) else Modifier)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.primary else CaptainPalette.panelBorder, RoundedCornerShape(999.dp))
            .then(
                if (enabled) {
                    Modifier.gameClick(onClick = onClick, shape = RoundedCornerShape(999.dp), glowColor = CaptainPalette.accent)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label.uppercase(), color = textColor, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (!enabled) {
                Text("SOON", color = CaptainPalette.textMuted, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
    }
}

/**
 * Hero "TOTAL EARNINGS" figure + a real previous-period comparison delta, computed here (not
 * stored pre-divided in [EarningsWheelUiState]) from [EarningsWheelUiState.totalEarnings] and
 * [EarningsWheelUiState.previousEarnings] — the one place that divides them, so there's no risk of
 * a stale percentage surviving a period change. `null` [EarningsWheelUiState.previousEarnings]
 * (the [TripPeriod.ALL] tab, or a zero-signum baseline nothing meaningful can be divided by) omits
 * the delta line entirely rather than fabricating a percentage.
 *
 * Premium pass: a radial accent bloom behind the figure plus a same-colour text shadow (the cheap
 * "bloom" the Meter screen uses on its fare readout — no blur filter), the figure up from 44sp to
 * 56sp, and the delta promoted into a tinted up/down chip.
 */
@Composable
private fun EarningsHeroCard(state: EarningsWheelUiState) {
    val breath by rememberInfiniteFloat(enabled = true, from = 0.55f, to = 1f, durationMs = 2600)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neonGlow(CaptainPalette.primary, 20.dp, strength = 0.35f + 0.35f * breath, spread = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)))
            .drawBehind {
                // Soft purple bloom under the figure — one radial gradient, the same wash
                // language as the meter dial's backdrop, not a blur.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CaptainPalette.glowPurpleSoft, Color.Transparent),
                        center = Offset(size.width * 0.22f, size.height * 0.62f),
                        radius = size.height * 1.5f,
                    ),
                    radius = size.height * 1.5f,
                    center = Offset(size.width * 0.22f, size.height * 0.62f),
                )
            }
            .border(
                1.dp,
                Brush.linearGradient(listOf(CaptainPalette.accent.copy(alpha = 0.55f), CaptainPalette.panelBorder)),
                RoundedCornerShape(20.dp),
            )
            .padding(vertical = 24.dp, horizontal = 22.dp),
    ) {
        Text(
            "TOTAL EARNINGS · ${state.period.label.uppercase()}",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.6.sp,
            color = CaptainPalette.textSecondary,
        )
        Text(
            state.totalEarnings.asMoney(),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 56.sp,
            color = CaptainPalette.textPrimary,
            style = TextStyle(
                shadow = Shadow(
                    color = CaptainPalette.accent.copy(alpha = 0.55f * breath),
                    offset = Offset.Zero,
                    blurRadius = 26f,
                ),
            ),
            modifier = Modifier.padding(top = 4.dp),
        )
        val previous = state.previousEarnings
        if (previous != null) {
            val delta = state.totalEarnings - previous
            val deltaPct = if (previous.signum() != 0) {
                delta.divide(previous, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)
            } else {
                null
            }
            val positive = delta.signum() >= 0
            val deltaColor = if (positive) CaptainPalette.success else CaptainPalette.danger
            val deltaText = when {
                deltaPct != null -> "${if (positive) "+" else ""}$deltaPct% vs ${state.period.previousLabel}"
                else -> "${if (positive) "+" else ""}${delta.asMoney()} vs ${state.period.previousLabel}"
            }
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(deltaColor.copy(alpha = 0.14f))
                    .border(1.dp, deltaColor.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    deltaText,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = deltaColor,
                )
            }
        }
    }
}

/**
 * Hand-drawn Canvas line/area chart of [EarningsWheelViewModel]'s real last-7-real-days totals —
 * see [EarningsWheelContent]'s class doc for why this is Canvas, not a library chart.
 */
@Composable
private fun EarningsTrendCard(trend: List<DailyEarnings>) {
    CaptainPanel(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 20, raised = true) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            SectionHeading("EARNINGS TREND · LAST 7 DAYS", CaptainPalette.accent)
            if (trend.isEmpty() || trend.all { it.total.signum() == 0 }) {
                Text(
                    "No earnings yet this week.",
                    fontFamily = InterFamily,
                    color = CaptainPalette.textMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 24.dp, bottom = 24.dp),
                )
                return@Column
            }
            EarningsTrendChart(trend = trend, modifier = Modifier.padding(top = 14.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                val dayFormatter = DateTimeFormatter.ofPattern("EEE")
                trend.forEach { d ->
                    Text(
                        d.date.format(dayFormatter).uppercase(),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun EarningsTrendChart(trend: List<DailyEarnings>, modifier: Modifier = Modifier) {
    val maxTotal = trend.maxOf { it.total }.let { if (it.signum() == 0) BigDecimal.ONE else it }.toFloat()
    // The newest point breathes so the eye lands on "today" first. One infinite float, no
    // per-frame allocation in the draw pass.
    val breath by rememberInfiniteFloat(enabled = true, from = 0.35f, to = 1f, durationMs = 1800)
    Canvas(modifier = modifier.fillMaxWidth().height(132.dp)) {
        val w = size.width
        val h = size.height
        val n = trend.size
        val stepX = if (n > 1) w / (n - 1) else 0f
        val topInset = 10.dp.toPx()
        val usableH = h - topInset
        val points = trend.mapIndexed { i, d ->
            val x = i * stepX
            val normalized = (d.total.toFloat() / maxTotal).coerceIn(0f, 1f)
            Offset(x, topInset + usableH * (1f - normalized))
        }

        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val curr = points[i]
                val midX = (prev.x + curr.x) / 2f
                cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
            }
        }

        // Two-stop purple area fill under the curve — accent at the crest fading through the
        // primary into nothing at the baseline. Same "gradient wash" language as MeterDialGlow's
        // radial blob, adapted to a vertical area shape.
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(
            areaPath,
            brush = Brush.verticalGradient(
                0f to CaptainPalette.accent.copy(alpha = 0.42f),
                0.55f to CaptainPalette.primary.copy(alpha = 0.20f),
                1f to Color.Transparent,
            ),
        )

        // Baseline — a hairline the area sits on, so the fill has a floor instead of bleeding out.
        drawLine(
            color = CaptainPalette.panelBorder,
            start = Offset(0f, h - 0.5f),
            end = Offset(w, h - 0.5f),
            strokeWidth = 1.dp.toPx(),
        )

        // Layered soft-alpha strokes under the sharp line — the same "stack alpha passes instead
        // of a real blur filter" glow technique MeterDialGlow's sweep arcs use, one pass deeper
        // and brighter than before so the line genuinely reads as lit.
        for (i in 5 downTo 1) {
            drawPath(
                linePath,
                color = CaptainPalette.accent.copy(alpha = 0.075f * i),
                style = Stroke(width = (3 + i * 3).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawPath(
            linePath,
            brush = Brush.horizontalGradient(listOf(CaptainPalette.primary, CaptainPalette.accent)),
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        points.forEachIndexed { i, p ->
            val newest = i == points.lastIndex
            if (newest) {
                drawCircle(color = CaptainPalette.accent.copy(alpha = 0.30f * breath), radius = 13.dp.toPx(), center = p)
                drawCircle(color = CaptainPalette.accent.copy(alpha = 0.45f * breath), radius = 8.5.dp.toPx(), center = p)
            }
            drawCircle(color = CaptainPalette.bg, radius = 5.5.dp.toPx(), center = p)
            drawCircle(color = CaptainPalette.accent, radius = if (newest) 4.dp.toPx() else 3.5.dp.toPx(), center = p)
        }
    }
}

/**
 * Trips/Distance/Avg Fare — all computable now from real [au.com.threesixty.cabdispatch.data.local.entity.TripEntity]
 * rows. "Online Time" (per the mockup) is deliberately OMITTED: the only shift-duration data this
 * app has is [au.com.threesixty.cabdispatch.data.local.dao.ShiftDao.observeActiveShift] (the
 * single currently-open shift) — there is no shift-history date-range query to sum online time
 * across a WEEK/MONTH period the way [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeTripsInRange]
 * does for trips, and adding one is a `ShiftDao`/`ShiftEntity` change outside this pass's edit
 * scope. Per this pass's own instruction ("if a real number isn't cheaply available, omit the row
 * rather than fabricate one") this row is left out rather than faked off a single active shift.
 */
@Composable
private fun SummaryCard(state: EarningsWheelUiState) {
    CaptainPanel(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 20, raised = true) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            SectionHeading("SUMMARY", CaptainPalette.primary)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryStat(label = "TRIPS", value = state.tripsCount.toString(), modifier = Modifier.weight(1f))
                StatDivider()
                SummaryStat(label = "DISTANCE", value = "${state.distanceKm.toPlainString()} km", modifier = Modifier.weight(1f))
                StatDivider()
                SummaryStat(label = "AVG FARE", value = state.avgFare.asMoney(), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A hairline between summary stats, so three numbers read as three cells instead of one blur. */
@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(40.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, CaptainPalette.panelBorder, Color.Transparent),
                ),
            ),
    )
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = CaptainPalette.textPrimary,
            style = TextStyle(
                shadow = Shadow(color = CaptainPalette.accent.copy(alpha = 0.35f), offset = Offset.Zero, blurRadius = 14f),
            ),
        )
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 0.4.sp,
            color = CaptainPalette.textMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Fares/Tolls/Tips/Other breakdown — Fares/Tolls are already on [au.com.threesixty.cabdispatch.data.local.entity.TripEntity],
 * Tips are real (Close & Pay tips pass, `TripEntity.tip`), "Other" maps to `extras` +
 * `cleaningFee` (both already on the entity). Rows sum exactly to [EarningsWheelUiState.totalEarnings]
 * — see [EarningsWheelViewModel.recompute]'s doc for how `faresTotal` is derived so that holds.
 */
@Composable
private fun BreakdownCard(state: EarningsWheelUiState) {
    CaptainPanel(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 20, raised = true) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            SectionHeading("BREAKDOWN", CaptainPalette.warning)
            val rows = listOf(
                Triple("Fares", state.faresTotal, CaptainPalette.accent),
                Triple("Tolls", state.tollsTotal, CaptainPalette.warning),
                Triple("Tips", state.tipsTotal, CaptainPalette.success),
                Triple("Other", state.otherTotal, CaptainPalette.textSecondary),
            )
            val total = state.totalEarnings.takeIf { it.signum() != 0 } ?: BigDecimal.ONE
            Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { (label, amount, color) ->
                    BreakdownRow(
                        label = label,
                        amount = amount,
                        fraction = (amount.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                        color = color,
                    )
                }
            }
        }
    }
}

/**
 * A card heading with the app's dot-led convention (`HiredScreen.BreakdownRow`'s bullet): a solid
 * core over a soft same-colour halo, so every panel on this screen announces itself the same way.
 */
@Composable
private fun SectionHeading(title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .drawBehind { drawCircle(color.copy(alpha = 0.35f), radius = size.minDimension) }
                .clip(CircleShape)
                .background(color),
        )
        Text(
            title,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.8.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}

/**
 * One breakdown line: dot bullet + label + money + proportion bar. The bullet is the identical
 * construction the Meter screen's fare breakdown uses (`HiredScreen.BreakdownRow`), so the two
 * money breakdowns in this app finally look like the same component.
 */
@Composable
private fun BreakdownRow(label: String, amount: BigDecimal, fraction: Float, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .drawBehind { drawCircle(color.copy(alpha = 0.35f), radius = size.minDimension) }
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                label,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            Text(
                amount.asMoney(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = CaptainPalette.textPrimary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(CaptainPalette.inset),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))),
            )
        }
    }
}

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
