package au.com.threesixty.cabdispatch.ui.screens.earnings

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.dao.TripPeriod
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HUD_GLOW_BLUR_PX
import au.com.threesixty.cabdispatch.ui.theme.HudStatTile
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.EARNINGS] wheel-slot content — the
 * "EARNINGS OVERVIEW" screen, fed by [EarningsWheelViewModel] off the same real
 * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity] rows as the History pane (see that
 * ViewModel's doc for the exact date-range query, shared with the History pane's filter pills).
 *
 * **HUD kit rebuild (2026-09-04).** Previously a vertical stack of hand-rolled `CaptainPanel`s
 * with a file-local `neonGlow` copy; now the mockup's three-column layout built from the shared kit
 * in `ui/theme/Hud.kt`:
 * - left, a [GlassCard] with the TOTAL EARNINGS figure as a [RollingMoneyText] (it rolls when the
 *   period changes), the previous-period delta as a [HudStatusPill], and the Fares/Tolls/Tips/Other
 *   rows;
 * - centre, the EARNINGS TREND [GlassCard] — still the hand-drawn Compose `Canvas` (no charting
 *   library exists or is added; `build.gradle.kts` deliberately carries none), restyled to the
 *   blueprint: a gradient area fill under a glowing line whose glow is a real blurred native
 *   `Paint` stroke (`BlurMaskFilter`, the same technique as the kit's gauge arcs — not stacked
 *   alpha passes) under a crisp sweep-gradient stroke, day labels beneath;
 * - right, the SUMMARY [GlassCard] of [HudStatTile]s — Trips / Distance / Avg Fare.
 *
 * **What the mockup shows that this screen does not, and why.** "Online Time" is omitted: the only
 * shift-duration data this app has is [au.com.threesixty.cabdispatch.data.local.dao.ShiftDao.observeActiveShift]
 * (the single currently-open shift) — there is no shift-history date-range query to sum online
 * time across a WEEK/MONTH period, and faking it off one active shift would be a fabricated
 * number. A cancellation count is omitted for the same reason: [TripEntity] carries no
 * cancelled status. "Custom" period is a locked "SOON" pill, not a working date-range picker —
 * this app's standing rule for a mockup row that isn't cheaply buildable, rather than a fake
 * affordance that does nothing when tapped.
 *
 * The delta line keeps its existing honesty rule: a real "% vs yesterday/last week/last month"
 * only when a non-zero previous-period baseline exists, an absolute money delta when the baseline
 * is zero, and no line at all for [TripPeriod.ALL] (nothing to compare against). Never a
 * fabricated percentage. **Strictly visual** — every figure still comes from the same
 * [EarningsWheelUiState] field, computed the same way, and no control was added.
 * `PaneShell` supplies the screen title above this content.
 */
@Composable
fun EarningsWheelContent(
    modifier: Modifier = Modifier,
    viewModel: EarningsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CaptainPalette.hudAccent)
        }
        return
    }

    EarningsOverviewBody(state = state, onSelectPeriod = viewModel::setPeriod, modifier = modifier)
}

@Composable
private fun EarningsOverviewBody(
    state: EarningsWheelUiState,
    onSelectPeriod: (TripPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PeriodTabs(selected = state.period, onSelect = onSelectPeriod, modifier = Modifier.weight(1f))
            PeriodDateReadout(state.period)
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TotalEarningsCard(state, modifier = Modifier.weight(1.05f).fillMaxHeight())
            EarningsTrendCard(state.trend, modifier = Modifier.weight(1.5f).fillMaxHeight())
            SummaryCard(state, modifier = Modifier.weight(0.95f).fillMaxHeight())
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared typography
// ---------------------------------------------------------------------------------------------

private val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

private val EyebrowStyle = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 12.sp,
    letterSpacing = 1.5.sp,
    color = CaptainPalette.textMuted,
)

// ---------------------------------------------------------------------------------------------
// Period pills + date readout
// ---------------------------------------------------------------------------------------------

@Composable
private fun PeriodTabs(selected: TripPeriod, onSelect: (TripPeriod) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The mockup's pills are Today / This Week / This Month / Custom; ALL is this app's real
        // "no lower bound" query and stays reachable rather than being hidden behind a fake.
        TripPeriod.entries.forEach { p ->
            PeriodPill(label = p.label, selected = p == selected, enabled = true, onClick = { onSelect(p) })
        }
        // "Custom" — real date-range picking isn't built (no date-picker dependency exists yet to
        // build it on cheaply) — shown locked rather than fake-functional.
        PeriodPill(label = "Custom", selected = false, enabled = false, onClick = {})
    }
}

/**
 * Same pill as `ZonesTabPill` / `VoucherTabPill` / the Trips History filter pill — 44dp,
 * fully-round, 16dp padding, 13sp bold uppercase — in the kit's glow language (HUD accent fill +
 * [neonGlow] when selected, [gameClick] press). The locked "Custom" pill stays genuinely
 * non-clickable (no `gameClick`, no glow) so it cannot be mistaken for a live one.
 */
@Composable
private fun PeriodPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    val bg = if (selected) CaptainPalette.hudAccent else CaptainPalette.hudGlass
    val textColor = when {
        selected -> CaptainPalette.textPrimary
        !enabled -> CaptainPalette.textMuted
        else -> CaptainPalette.textSecondary
    }
    Box(
        modifier = Modifier
            .height(44.dp)
            .then(if (selected) Modifier.neonGlow(CaptainPalette.hudAccent, 999.dp, strength = 0.8f, spread = 4.dp) else Modifier)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.hudSweepMid else CaptainPalette.hudGlassBorderPurple, shape)
            .then(if (enabled) Modifier.gameClick(onClick = onClick, shape = shape, glowColor = CaptainPalette.hudSweepMid) else Modifier)
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
 * The mockup's date readout — the calendar window the selected [TripPeriod] actually queries,
 * by the same device-local rule as [TripPeriod.startEpochMillis] (Monday-start week,
 * first-of-month), ending today. A label for the real range, not a picker. (Mirrors the Trips
 * pane's private readout; both files are scoped edits, so the ten lines are repeated rather than
 * a new shared file added.)
 */
@Composable
private fun PeriodDateReadout(period: TripPeriod) {
    val readout = remember(period) { period.rangeReadout() }
    Text(
        readout,
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        color = CaptainPalette.textSecondary,
        style = TabularFigures,
        maxLines = 1,
        modifier = Modifier.padding(start = 16.dp),
    )
}

private val DAY_READOUT = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val SHORT_DAY_READOUT = DateTimeFormatter.ofPattern("EEE d MMM")
private val MONTH_READOUT = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun TripPeriod.rangeReadout(today: LocalDate = LocalDate.now()): String = when (this) {
    TripPeriod.ALL -> "All time"
    TripPeriod.TODAY -> today.format(DAY_READOUT)
    TripPeriod.WEEK -> "${today.with(DayOfWeek.MONDAY).format(SHORT_DAY_READOUT)} – ${today.format(DAY_READOUT)}"
    TripPeriod.MONTH -> today.format(MONTH_READOUT)
}

// ---------------------------------------------------------------------------------------------
// Left column — TOTAL EARNINGS + delta + Fares / Tolls / Tips / Other
// ---------------------------------------------------------------------------------------------

/**
 * "TOTAL EARNINGS" hero + the real previous-period comparison delta, computed here (not stored
 * pre-divided in [EarningsWheelUiState]) from [EarningsWheelUiState.totalEarnings] and
 * [EarningsWheelUiState.previousEarnings] — the one place that divides them, so there's no risk
 * of a stale percentage surviving a period change. `null` [EarningsWheelUiState.previousEarnings]
 * (the [TripPeriod.ALL] tab) omits the delta entirely; a zero baseline shows the absolute money
 * delta instead of a percentage nothing can be divided by.
 */
@Composable
private fun TotalEarningsCard(state: EarningsWheelUiState, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadiusDp = 20, glow = CaptainPalette.hudAccent) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("TOTAL EARNINGS · ${state.period.label.uppercase()}", style = EyebrowStyle)
            RollingMoneyText(
                amount = state.totalEarnings.asMoney(),
                fontSize = 48.sp,
                modifier = Modifier.padding(top = 6.dp),
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
                val sign = if (positive) "+" else ""
                HudStatusPill(
                    label = "vs ${state.period.previousLabel}",
                    value = if (deltaPct != null) "$sign$deltaPct%" else "$sign${delta.asMoney()}",
                    tone = if (positive) HudTone.Success else HudTone.Danger,
                    pulsing = false,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
            Spacer(Modifier.height(14.dp))

            // Fares/Tolls are already on TripEntity, Tips are real (`TripEntity.tip`), "Other" is
            // `extras` + `cleaningFee`. Rows sum exactly to totalEarnings — see
            // EarningsWheelViewModel.recompute's doc for how faresTotal is derived so that holds.
            val rows = listOf(
                Triple("Fares", state.faresTotal, CaptainPalette.hudSweepMid),
                Triple("Tolls", state.tollsTotal, CaptainPalette.warning),
                Triple("Tips", state.tipsTotal, CaptainPalette.success),
                Triple("Other", state.otherTotal, CaptainPalette.textSecondary),
            )
            val total = state.totalEarnings.takeIf { it.signum() != 0 } ?: BigDecimal.ONE
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** One breakdown line: label + tabular money + a slim proportion bar in the row's tone. */
@Composable
private fun BreakdownRow(label: String, amount: BigDecimal, fraction: Float, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                amount.asMoney(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = CaptainPalette.textPrimary,
                style = TabularFigures,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(CaptainPalette.hudTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Centre — EARNINGS TREND
// ---------------------------------------------------------------------------------------------

/**
 * Hand-drawn Canvas line/area chart of [EarningsWheelViewModel]'s real last-7-real-days totals —
 * see [EarningsWheelContent]'s class doc for why this is Canvas, not a library chart.
 */
@Composable
private fun EarningsTrendCard(trend: List<DailyEarnings>, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadiusDp = 20) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("EARNINGS TREND · LAST 7 DAYS", style = EyebrowStyle)
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
            EarningsTrendChart(trend = trend, modifier = Modifier.padding(top = 18.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                val dayFormatter = DateTimeFormatter.ofPattern("EEE")
                trend.forEach { d ->
                    Text(
                        d.date.format(dayFormatter).uppercase(),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The blueprint's glow line: a gradient area fill under the curve, then the curve drawn twice —
 * first through the native canvas with a remembered `BlurMaskFilter` stroke paint (the kit's
 * gauge technique — a mask-filter blur on a stroke is cheap; no full-layer `RenderEffect` blur
 * per the SM-T575 frame budget), then a crisp `hudSweep`-gradient stroke on top. The newest point
 * breathes so the eye lands on "today" first.
 */
@Composable
private fun EarningsTrendChart(trend: List<DailyEarnings>, modifier: Modifier = Modifier) {
    val maxTotal = trend.maxOf { it.total }.let { if (it.signum() == 0) BigDecimal.ONE else it }.toFloat()
    val breath by rememberInfiniteFloat(enabled = true, from = 0.35f, to = 1f, durationMs = 1800)
    val glowArgb = CaptainPalette.hudAccent.toArgb()
    // Remembered once per composable — the BlurMaskFilter (and its cached kernel) is never
    // allocated per frame. Stroke width is set at draw time (it's in px).
    val glowPaint = remember(glowArgb) {
        Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = glowArgb
            maskFilter = BlurMaskFilter(HUD_GLOW_BLUR_PX * 0.6f, BlurMaskFilter.Blur.NORMAL)
        }
    }
    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height
        val n = trend.size
        val sideInset = 8.dp.toPx()
        val stepX = if (n > 1) (w - sideInset * 2) / (n - 1) else 0f
        val topInset = 14.dp.toPx()
        val usableH = h - topInset
        val points = trend.mapIndexed { i, d ->
            val x = sideInset + i * stepX
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

        // Gradient area fill under the curve — accent at the crest fading to nothing at the floor.
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(
            areaPath,
            brush = Brush.verticalGradient(
                0f to CaptainPalette.hudAccent.copy(alpha = 0.45f),
                0.6f to CaptainPalette.hudSweepStart.copy(alpha = 0.16f),
                1f to Color.Transparent,
            ),
        )

        // Baseline — the floor the area sits on.
        drawLine(
            color = CaptainPalette.hudTrack,
            start = Offset(0f, h - 0.5f),
            end = Offset(w, h - 0.5f),
            strokeWidth = 1.dp.toPx(),
        )

        // Blurred glow stroke (native canvas) under the crisp line.
        drawIntoCanvas { canvas ->
            glowPaint.strokeWidth = 7.dp.toPx()
            glowPaint.alpha = (0.85f * 255f).roundToInt()
            canvas.nativeCanvas.drawPath(linePath.asAndroidPath(), glowPaint)
        }
        drawPath(
            linePath,
            brush = Brush.horizontalGradient(CaptainPalette.hudSweep),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        points.forEachIndexed { i, p ->
            val newest = i == points.lastIndex
            if (newest) {
                drawCircle(color = CaptainPalette.hudSweepMid.copy(alpha = 0.30f * breath), radius = 13.dp.toPx(), center = p)
                drawCircle(color = CaptainPalette.hudSweepMid.copy(alpha = 0.45f * breath), radius = 8.5.dp.toPx(), center = p)
            }
            drawCircle(color = CaptainPalette.hudBg, radius = 5.5.dp.toPx(), center = p)
            drawCircle(color = CaptainPalette.hudSweepMid, radius = if (newest) 4.dp.toPx() else 3.5.dp.toPx(), center = p)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Right column — SUMMARY
// ---------------------------------------------------------------------------------------------

/**
 * Trips / Distance / Avg Fare as stacked [HudStatTile]s — all computable from real
 * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity] rows. "Online Time" and a
 * cancellation count are deliberately absent — see [EarningsWheelContent]'s class doc.
 */
@Composable
private fun SummaryCard(state: EarningsWheelUiState, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadiusDp = 20) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SUMMARY", style = EyebrowStyle, modifier = Modifier.padding(bottom = 4.dp))
            HudStatTile(
                icon = Icons.Rounded.LocalTaxi,
                label = "Trips",
                value = state.tripsCount.toString(),
                sub = state.period.label,
                modifier = Modifier.fillMaxWidth(),
            )
            HudStatTile(
                icon = Icons.Rounded.Straighten,
                label = "Distance",
                value = "${state.distanceKm.toPlainString()} km",
                modifier = Modifier.fillMaxWidth(),
            )
            HudStatTile(
                icon = Icons.Rounded.AttachMoney,
                label = "Avg fare",
                value = state.avgFare.asMoney(),
                sub = "per trip",
                tone = HudTone.Success,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Preview — PREVIEW-ONLY fake state. Nothing here is read by the app; the live screen is fed by
// EarningsWheelViewModel. Figures are invented purely to exercise the three-column layout.
// ---------------------------------------------------------------------------------------------

@Preview(widthDp = 1040, heightDp = 560, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewEarningsOverview() {
    val today = LocalDate.of(2026, 9, 4)
    val previewState = EarningsWheelUiState(
        loading = false,
        period = TripPeriod.TODAY,
        totalEarnings = BigDecimal("312.40"),
        previousEarnings = BigDecimal("278.90"),
        tripsCount = 14,
        distanceKm = BigDecimal("86.3"),
        avgFare = BigDecimal("22.31"),
        faresTotal = BigDecimal("281.20"),
        tollsTotal = BigDecimal("12.70"),
        tipsTotal = BigDecimal("15.00"),
        otherTotal = BigDecimal("3.50"),
        trend = listOf("184.10", "240.55", "96.00", "310.20", "265.75", "402.30", "312.40").mapIndexed { i, v ->
            DailyEarnings(today.minusDays((6 - i).toLong()), BigDecimal(v))
        },
    )
    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        EarningsOverviewBody(state = previewState, onSelectPeriod = {})
    }
}
