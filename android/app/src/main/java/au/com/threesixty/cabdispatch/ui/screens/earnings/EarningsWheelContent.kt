package au.com.threesixty.cabdispatch.ui.screens.earnings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.dao.TripPeriod
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
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
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.primary else CaptainPalette.panelBorder, RoundedCornerShape(999.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
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
 */
@Composable
private fun EarningsHeroCard(state: EarningsWheelUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(CaptainPalette.cardTop, CaptainPalette.cardBottom)), RoundedCornerShape(20.dp))
            .border(1.dp, Brush.linearGradient(listOf(CaptainPalette.accent.copy(alpha = 0.35f), CaptainPalette.panelBorder)), RoundedCornerShape(20.dp))
            .padding(vertical = 22.dp, horizontal = 20.dp),
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
            fontSize = 44.sp,
            color = CaptainPalette.textPrimary,
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
            val deltaColor = if (positive) CaptainPalette.success else CaptainPalette.danger
            val deltaText = when {
                deltaPct != null -> "${if (positive) "+" else ""}$deltaPct% vs ${state.period.previousLabel}"
                else -> "${if (positive) "+" else ""}${delta.asMoney()} vs ${state.period.previousLabel}"
            }
            Text(
                deltaText,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = deltaColor,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Hand-drawn Canvas line/area chart of [EarningsWheelViewModel]'s real last-7-real-days totals —
 * see [EarningsWheelContent]'s class doc for why this is Canvas, not a library chart.
 */
@Composable
private fun EarningsTrendCard(trend: List<DailyEarnings>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CaptainPalette.raised, RoundedCornerShape(20.dp))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text(
            "EARNINGS TREND · LAST 7 DAYS",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.6.sp,
            color = CaptainPalette.textSecondary,
        )
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

@Composable
private fun EarningsTrendChart(trend: List<DailyEarnings>, modifier: Modifier = Modifier) {
    val maxTotal = trend.maxOf { it.total }.let { if (it.signum() == 0) BigDecimal.ONE else it }.toFloat()
    Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        val n = trend.size
        val stepX = if (n > 1) w / (n - 1) else 0f
        val topInset = 8.dp.toPx()
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

        // Soft purple area fill under the curve — same "gradient wash" language as
        // MeterDialGlow's radial-gradient blob, adapted to a vertical area shape.
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(areaPath, brush = Brush.verticalGradient(listOf(CaptainPalette.glowPurpleStrong, Color.Transparent)))

        // Layered soft-alpha strokes under the sharp line — the same "stack alpha passes instead
        // of a real blur filter" glow technique MeterDialGlow's sweep arcs use.
        for (i in 4 downTo 1) {
            drawPath(
                linePath,
                color = CaptainPalette.accent.copy(alpha = 0.06f * i),
                style = Stroke(width = (3 + i * 3).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawPath(linePath, color = CaptainPalette.accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        points.forEach { p ->
            drawCircle(color = CaptainPalette.bg, radius = 5.dp.toPx(), center = p)
            drawCircle(color = CaptainPalette.accent, radius = 3.5.dp.toPx(), center = p)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CaptainPalette.raised, RoundedCornerShape(20.dp))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text(
            "SUMMARY",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.6.sp,
            color = CaptainPalette.textSecondary,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryStat(label = "TRIPS", value = state.tripsCount.toString(), modifier = Modifier.weight(1f))
            SummaryStat(label = "DISTANCE", value = "${state.distanceKm.toPlainString()} km", modifier = Modifier.weight(1f))
            SummaryStat(label = "AVG FARE", value = state.avgFare.asMoney(), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = CaptainPalette.textPrimary,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CaptainPalette.raised, RoundedCornerShape(20.dp))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text(
            "BREAKDOWN",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.6.sp,
            color = CaptainPalette.textSecondary,
        )
        val rows = listOf(
            Triple("Fares", state.faresTotal, CaptainPalette.accent),
            Triple("Tolls", state.tollsTotal, CaptainPalette.warning),
            Triple("Tips", state.tipsTotal, CaptainPalette.success),
            Triple("Other", state.otherTotal, CaptainPalette.textSecondary),
        )
        val total = state.totalEarnings.takeIf { it.signum() != 0 } ?: BigDecimal.ONE
        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { (label, amount, color) ->
                BreakdownRow(label = label, amount = amount, fraction = (amount.toFloat() / total.toFloat()).coerceIn(0f, 1f), color = color)
            }
        }
    }
}

@Composable
private fun BreakdownRow(label: String, amount: BigDecimal, fraction: Float, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textPrimary)
            Text(amount.asMoney(), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CaptainPalette.textPrimary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(CaptainPalette.inset),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color),
            )
        }
    }
}
