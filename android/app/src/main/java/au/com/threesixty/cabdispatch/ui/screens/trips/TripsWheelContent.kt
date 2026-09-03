package au.com.threesixty.cabdispatch.ui.screens.trips

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(CaptainPalette.raised, RoundedCornerShape(14.dp))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            trip.startAt.asLocalTime(),
            color = CaptainPalette.textPrimary,
            fontWeight = FontWeight.Bold,
            fontFamily = ChakraPetch,
            fontSize = 16.sp,
        )
        Text(
            trip.type.asTripTypeLabel(),
            color = CaptainPalette.textPrimary,
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        TripStatusPill(status)
        Text(
            if (status == TripPillStatus.ACTIVE) {
                "${trip.deviceTotal.toBigDecimalOrZero().asMoney()} est"
            } else {
                trip.deviceTotal.toBigDecimalOrZero().asMoney()
            },
            color = CaptainPalette.textPrimary,
            fontWeight = FontWeight.Bold,
            fontFamily = ChakraPetch,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun TripStatusPill(status: TripPillStatus) {
    val bg = if (status == TripPillStatus.ACTIVE) CaptainPalette.warning else CaptainPalette.success
    Box(
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            if (status == TripPillStatus.ACTIVE) "ACTIVE" else "DONE",
            color = CaptainPalette.bg,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trips, key = { it.clientUuid }) { trip ->
                    TripHistoryRow(trip) { onTripClick(trip.clientUuid) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val total = trips.fold(BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${period.label} · total ${total.asMoney()} · ${trips.size} trips",
                fontFamily = InterFamily,
                color = CaptainPalette.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.raised)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                    .clickable(onClick = onShiftReportClick)
                    .padding(horizontal = 18.dp),
            ) {
                Text(
                    "SHIFT REPORT",
                    color = CaptainPalette.accent,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun HistoryFilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CaptainPalette.primary else CaptainPalette.raised
    val textColor = if (selected) CaptainPalette.textPrimary else CaptainPalette.textSecondary
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, if (selected) CaptainPalette.primary else CaptainPalette.panelBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), color = textColor, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CaptainPalette.raised, RoundedCornerShape(14.dp))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                trip.startAt.asLocalTime(),
                color = CaptainPalette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontFamily = ChakraPetch,
                fontSize = 15.sp,
            )
            Text(
                trip.type.asTripTypeLabel(),
                color = CaptainPalette.textPrimary,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusPillColor(trip.status).copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    trip.status.asTripStatusLabel().uppercase(),
                    color = statusPillColor(trip.status),
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
            Text(
                trip.deviceTotal.toBigDecimalOrZero().asMoney(),
                color = CaptainPalette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontFamily = ChakraPetch,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
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
                    .padding(horizontal = 10.dp, vertical = 5.dp),
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
