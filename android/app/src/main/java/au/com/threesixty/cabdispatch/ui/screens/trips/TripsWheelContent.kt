package au.com.threesixty.cabdispatch.ui.screens.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.dao.TripPeriod
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.domain.format.asPaymentMethodLabel
import au.com.threesixty.cabdispatch.domain.format.asTripTypeLabel
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.TRIPS] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Trips: trip history rows — route, time, payment method, fare
 * amount"). Reused for BOTH the "My Trips" and "History" dock destinations (this app has one real
 * trips data source, not two — see [au.com.threesixty.cabdispatch.ui.screens.dashboard.HomeDashboardV2ChromeOverlay]'s
 * `dockTiles` doc for why), presented with two different v2 layouts per [TripsPaneVariant] to match
 * the Figma file's two distinct screens (fileKey `JhEhok3n9bntRNS5Y1u3Yc`, node `34:2` "My Trips" —
 * focused active/recent cards + an "OPEN ACTIVE TRIP" CTA — vs node `35:170` "Trip History" — a
 * filterable flat list). [TripsWheelUiState.activeTrip] is real (backed by
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeActiveTrip], the same query
 * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] is keyed off), not fabricated —
 * see that field's own doc for the one honest caveat (re-opening `HIRED` on an already-open trip
 * with no fresh [au.com.threesixty.cabdispatch.domain.SessionHolder.pendingTrip] hand-off does not
 * resume the live fare-engine state; that gap pre-dates this pass and is a product decision, not
 * something this visual reskin can silently fix).
 *
 * "Route" falls back to the trip's type label rather than a real origin->destination address —
 * see [au.com.threesixty.cabdispatch.domain.format.asTripTypeLabel]'s doc for why.
 *
 * Captain Taxis purple-theme pass (2026-08-29): re-themed off the legacy glass/gold wheel-content
 * palette onto [CaptainPalette] to match the purple `PaneShell` chrome this content is embedded in from
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen] — colors/typography/shapes
 * only, no behavior change. ACTIVE/DONE status semantics keep their original warning/success
 * meaning (amber "in progress" -> [CaptainPalette.warning], green "completed" ->
 * [CaptainPalette.success]) rather than being flattened to one accent colour.
 *
 * **Premium pass (2026-09-03, plan Phase 4).** This screen used *none* of the app's shared visual
 * vocabulary — no [CaptainPanel], no [gameClick], no glow, no animation — which is exactly why it
 * read as cheap next to the Meter screen. It now composes from the same primitives every premium
 * surface uses: rows are real [CaptainPanel] surfaces with a status-coloured spine, a dot-led
 * status chip and a clear time/route/fare hierarchy; every tappable row/pill/button gets
 * [gameClick]'s press-scale + glow ring; an in-progress row breathes a `neonGlow` pulse driven by
 * [rememberInfiniteFloat]; and the filter pills now match, to the dp, the pill already shared by
 * the Zones (`ZonesPaneContent.ZonesTabPill`) and Vouchers (`VouchersPaneContent.VoucherTabPill`)
 * tab rows so the app reads as one system. **Strictly visual** — every value below still comes
 * from exactly the same [TripEntity] field / [TripsWheelViewModel] state it came from before, and
 * no new affordance was added (the standing no-decorative-controls rule).
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * renders this composable for [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.TRIPS], wiring
 * [onTripClick] exactly as suggested below — see that screen's `TripsSlotContent`.
 */
enum class TripsPaneVariant { MY_TRIPS, HISTORY }

@Composable
fun TripsWheelContent(
    modifier: Modifier = Modifier,
    variant: TripsPaneVariant = TripsPaneVariant.MY_TRIPS,
    onTripClick: (clientUuid: String) -> Unit = {},
    onOpenActiveTrip: () -> Unit = {},
    onShiftReportClick: () -> Unit = {},
    viewModel: TripsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    when {
        variant == TripsPaneVariant.MY_TRIPS && state.loading ->
            Text("Loading trips…", fontFamily = InterFamily, color = CaptainPalette.textSecondary, fontSize = 16.sp)
        variant == TripsPaneVariant.MY_TRIPS && state.trips.isEmpty() && state.activeTrip == null ->
            Text("No trips yet.", fontFamily = InterFamily, color = CaptainPalette.textSecondary, fontSize = 16.sp)
        variant == TripsPaneVariant.MY_TRIPS -> MyTripsBody(
            modifier = modifier,
            activeTrip = state.activeTrip,
            recentTrips = state.trips,
            onTripClick = onTripClick,
            onOpenActiveTrip = onOpenActiveTrip,
        )
        state.historyLoading -> Text("Loading trips…", fontFamily = InterFamily, color = CaptainPalette.textSecondary, fontSize = 16.sp)
        else -> TripHistoryBody(
            modifier = modifier,
            trips = state.historyTrips,
            period = state.historyPeriod,
            onPeriodChange = viewModel::setHistoryPeriod,
            onTripClick = onTripClick,
            onShiftReportClick = onShiftReportClick,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// My Trips variant — Figma node 34:2: focused ACTIVE + recent DONE cards, "OPEN ACTIVE TRIP" CTA.
// ---------------------------------------------------------------------------------------------

@Composable
private fun MyTripsBody(
    activeTrip: TripEntity?,
    recentTrips: List<TripEntity>,
    onTripClick: (String) -> Unit,
    onOpenActiveTrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (activeTrip != null) {
                item(key = "active-${activeTrip.clientUuid}") {
                    MyTripRow(trip = activeTrip, status = TripPillStatus.ACTIVE) { onTripClick(activeTrip.clientUuid) }
                }
            }
            items(recentTrips.take(6), key = { it.clientUuid }) { trip ->
                MyTripRow(trip = trip, status = TripPillStatus.DONE) { onTripClick(trip.clientUuid) }
            }
        }
        if (activeTrip != null) {
            Spacer(Modifier.height(14.dp))
            CaptainButton(
                text = "OPEN ACTIVE TRIP",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 16.sp,
                onClick = onOpenActiveTrip,
            )
        }
    }
}

private enum class TripPillStatus { ACTIVE, DONE }

@Composable
private fun MyTripRow(trip: TripEntity, status: TripPillStatus, onClick: () -> Unit) {
    val active = status == TripPillStatus.ACTIVE
    val accent = if (active) CaptainPalette.warning else CaptainPalette.success
    // Only a genuinely live row breathes; a finished row is static — no idle animation cost, and
    // the motion stays meaningful rather than decorative.
    val breath by rememberInfiniteFloat(enabled = active, from = 0.25f, to = 0.75f, durationMs = 1600)

    CaptainPanel(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.neonGlow(accent, 14.dp, strength = breath, spread = 4.dp) else Modifier)
            .gameClick(onClick = onClick, shape = RoundedCornerShape(14.dp), glowColor = accent),
        cornerRadiusDp = 14,
        raised = true,
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatusSpine(accent)
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    trip.startAt.asLocalTime(),
                    color = CaptainPalette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = ChakraPetch,
                    fontSize = 17.sp,
                )
                Text(
                    trip.type.asTripTypeLabel(),
                    color = CaptainPalette.textPrimary,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    label = if (active) "ACTIVE" else "DONE",
                    color = accent,
                    pulse = active,
                )
                Text(
                    if (active) {
                        "${trip.deviceTotal.toBigDecimalOrZero().asMoney()} est"
                    } else {
                        trip.deviceTotal.toBigDecimalOrZero().asMoney()
                    },
                    color = if (active) CaptainPalette.warning else CaptainPalette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = ChakraPetch,
                    fontSize = 18.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Trip History variant — Figma node 35:170: filter pills + flat rows + shift-report footer.
//
// Filter pills are now real (Phase C, 2026-09-03): [TripPeriod] (shared with the Earnings pane's
// period tabs, see [au.com.threesixty.cabdispatch.data.local.dao.TripDao]) drives a genuine
// [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeTripsInRange] query per pill —
// WEEK/MONTH/ALL now return real, different result sets instead of all four silently rendering
// the same on-device recent-trips list (this file's own prior doc comment for why that used to be
// the honest, deliberate placeholder).
//
// Columns now also include PICKUP/DROPOFF/DISTANCE/DURATION/STATUS to match the mockup's table.
// Distance/duration/status are plain arithmetic over [TripEntity.distanceM]/`.movingS`+`.waitingS`/
// `.status` — already-persisted fields, no new plumbing. Pickup/dropoff read
// [TripEntity.pickupAddress]/`.dropoffAddress` (new columns, Phase C) — "—" for a `null` value
// (a trip with no dispatch-offer address to carry), never a fabricated address.
// ---------------------------------------------------------------------------------------------

@Composable
private fun TripHistoryBody(
    trips: List<TripEntity>,
    period: TripPeriod,
    onPeriodChange: (TripPeriod) -> Unit,
    onTripClick: (String) -> Unit,
    onShiftReportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TripPeriod.entries.forEach { p ->
                HistoryFilterPill(label = p.label, selected = p == period, onClick = { onPeriodChange(p) })
            }
        }
        Spacer(Modifier.height(14.dp))
        if (trips.isEmpty()) {
            Text(
                "No trips in this period.",
                fontFamily = InterFamily,
                color = CaptainPalette.textSecondary,
                fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(trips, key = { it.clientUuid }) { trip ->
                    TripHistoryRow(trip) { onTripClick(trip.clientUuid) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        HistoryTotalsBar(
            period = period,
            tripCount = trips.size,
            total = trips.fold(BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() },
            onShiftReportClick = onShiftReportClick,
        )
    }
}

/**
 * Footer summary + the real SHIFT REPORT action. Exactly the same three values as before
 * ([TripPeriod.label], the row count, and the summed [TripEntity.deviceTotal]) — promoted out of
 * one grey run-on sentence into a [CaptainPanel] with a proper money-figure hierarchy, because the
 * period total is the number a driver actually opens this screen to read.
 */
@Composable
private fun HistoryTotalsBar(
    period: TripPeriod,
    tripCount: Int,
    total: BigDecimal,
    onShiftReportClick: () -> Unit,
) {
    CaptainPanel(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16, raised = false) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${period.label.uppercase()} · $tripCount TRIPS",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    color = CaptainPalette.textMuted,
                )
                Text(
                    total.asMoney(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .neonGlow(CaptainPalette.accent, 14.dp, strength = 0.5f, spread = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.raised)
                    .border(1.dp, CaptainPalette.accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .gameClick(onClick = onShiftReportClick, shape = RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "SHIFT REPORT",
                    color = CaptainPalette.accent,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * Period filter pill. Geometry/typography deliberately identical to `ZonesTabPill`
 * (`ui/screens/zones/ZonesPaneContent.kt`) and `VoucherTabPill`
 * (`ui/screens/vouchers/VouchersPaneContent.kt`) — 44dp tall, fully-round, 16dp horizontal
 * padding, 13sp bold uppercase, `primary`-on-selected — so all three tab rows in the app are one
 * control rather than three near-misses (this pill was previously 48dp/18dp/14sp, subtly off).
 * The only additions are shared feel, not new shape: [gameClick] press-scale and a soft glow on
 * the selected pill.
 */
@Composable
private fun HistoryFilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CaptainPalette.primary else CaptainPalette.raised
    val textColor = if (selected) CaptainPalette.textPrimary else CaptainPalette.textSecondary
    Box(
        modifier = Modifier
            .height(44.dp)
            .then(if (selected) Modifier.neonGlow(CaptainPalette.primary, 999.dp, strength = 0.8f, spread = 4.dp) else Modifier)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.primary else CaptainPalette.panelBorder, RoundedCornerShape(999.dp))
            .gameClick(onClick = onClick, shape = RoundedCornerShape(999.dp), glowColor = CaptainPalette.accent)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = textColor, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** "3.2 km" from [TripEntity.distanceM]. */
private fun Int.asKm(): String =
    BigDecimal(this).divide(BigDecimal(1000), 1, RoundingMode.HALF_UP).toPlainString() + " km"

/** "12 min" from cumulative [TripEntity.movingS] + [TripEntity.waitingS]. */
private fun asDuration(movingS: Int, waitingS: Int): String {
    val totalMin = (movingS + waitingS) / 60
    return if (totalMin < 1) "<1 min" else "$totalMin min"
}

/** [TripEntity.status] -> display label ("open"/"closed"/"synced", see [TripStatus]). */
private fun String.asTripStatusLabel(): String = when (this) {
    TripStatus.OPEN -> "In Progress"
    TripStatus.CLOSED -> "Completed"
    TripStatus.SYNCED -> "Synced"
    else -> this.replaceFirstChar { it.uppercase() }
}

@Composable
private fun TripHistoryRow(trip: TripEntity, onClick: () -> Unit) {
    val accent = statusPillColor(trip.status)
    val live = trip.status == TripStatus.OPEN
    val breath by rememberInfiniteFloat(enabled = live, from = 0.25f, to = 0.8f, durationMs = 1600)

    CaptainPanel(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (live) Modifier.neonGlow(accent, 16.dp, strength = breath, spread = 4.dp) else Modifier)
            .gameClick(onClick = onClick, shape = RoundedCornerShape(16.dp), glowColor = accent),
        cornerRadiusDp = 16,
        raised = true,
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            StatusSpine(accent)
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        trip.startAt.asLocalTime(),
                        color = CaptainPalette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = ChakraPetch,
                        fontSize = 17.sp,
                    )
                    Text(
                        trip.type.asTripTypeLabel(),
                        color = CaptainPalette.textPrimary,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        label = trip.status.asTripStatusLabel().uppercase(),
                        color = accent,
                        pulse = live,
                    )
                    Text(
                        trip.deviceTotal.toBigDecimalOrZero().asMoney(),
                        color = CaptainPalette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = ChakraPetch,
                        fontSize = 20.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HistoryColumn(label = "PICKUP", value = trip.pickupAddress ?: "—", modifier = Modifier.weight(1.4f))
                    HistoryColumn(label = "DROPOFF", value = trip.dropoffAddress ?: "—", modifier = Modifier.weight(1.4f))
                    HistoryColumn(label = "DISTANCE", value = trip.distanceM.asKm(), modifier = Modifier.weight(0.8f))
                    HistoryColumn(label = "DURATION", value = asDuration(trip.movingS, trip.waitingS), modifier = Modifier.weight(0.8f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CaptainPalette.inset)
                            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            trip.paymentMethod.asPaymentMethodLabel().uppercase(),
                            color = CaptainPalette.textSecondary,
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The 4dp status-coloured spine down a row's leading edge — the cheapest way to make a long list
 * scannable by state at a glance, drawn as a plain gradient-filled box (one draw, no extra layer,
 * no blur) rather than a second glow pass.
 */
@Composable
private fun StatusSpine(color: Color) {
    Box(
        modifier = Modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.35f)))),
    )
}

/**
 * Dot-led status chip — replaces the previous flat coloured text / solid pill. The dot uses the
 * same "solid core over a soft same-colour halo" construction as the Meter screen's
 * fare-breakdown bullets (`HiredScreen.BreakdownRow`), and pulses only when the row is genuinely
 * live, so the animation carries state rather than decorating.
 */
@Composable
private fun StatusChip(label: String, color: Color, pulse: Boolean) {
    val dotAlpha by rememberInfiniteFloat(enabled = pulse, from = 0.35f, to = 1f, durationMs = 1100)
    val alpha = if (pulse) dotAlpha else 1f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .drawBehind { drawCircle(color.copy(alpha = 0.35f * alpha), radius = size.minDimension) }
                .clip(CircleShape)
                .background(color.copy(alpha = alpha)),
        )
        Text(
            label,
            color = color,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
private fun HistoryColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 0.4.sp,
            color = CaptainPalette.textMuted,
        )
        Text(
            value,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = CaptainPalette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun statusPillColor(status: String): Color = when (status) {
    TripStatus.OPEN -> CaptainPalette.warning
    TripStatus.CLOSED -> CaptainPalette.success
    TripStatus.SYNCED -> CaptainPalette.accent
    else -> CaptainPalette.textSecondary
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
