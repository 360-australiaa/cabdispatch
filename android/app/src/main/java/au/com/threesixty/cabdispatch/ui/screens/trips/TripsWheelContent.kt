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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.domain.format.asPaymentMethodLabel
import au.com.threesixty.cabdispatch.domain.format.asTripTypeLabel
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import au.com.threesixty.cabdispatch.ui.theme.WheelColorsV2

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
        state.loading -> Text("Loading trips…", color = WheelColorsV2.mutedFigure, fontSize = 14.sp)
        state.trips.isEmpty() && state.activeTrip == null ->
            Text("No trips yet.", color = WheelColorsV2.mutedFigure, fontSize = 14.sp)
        variant == TripsPaneVariant.MY_TRIPS -> MyTripsBody(
            modifier = modifier,
            activeTrip = state.activeTrip,
            recentTrips = state.trips,
            onTripClick = onTripClick,
            onOpenActiveTrip = onOpenActiveTrip,
        )
        else -> TripHistoryBody(
            modifier = modifier,
            trips = state.trips,
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(WheelColorsV2.goldCtaBrush)
                    .clickable(onClick = onOpenActiveTrip),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(WheelColorsV2.bevelHighlightBrush))
                Text("OPEN ACTIVE TRIP", color = WheelColorsV2.onGoldCta, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

private enum class TripPillStatus { ACTIVE, DONE }

@Composable
private fun MyTripRow(trip: TripEntity, status: TripPillStatus, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColorsV2.rowGlass, RoundedCornerShape(14.dp))
            .border(1.dp, WheelColorsV2.rowBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            trip.startAt.asLocalTime(),
            color = Color(0xFFF4FAFF),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
        )
        Text(
            trip.type.asTripTypeLabel(),
            color = Color.White.copy(alpha = 0.94f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        TripStatusPill(status)
        Text(
            if (status == TripPillStatus.ACTIVE) {
                "${trip.deviceTotal.toBigDecimalOrZero().asMoney()} est"
            } else {
                trip.deviceTotal.toBigDecimalOrZero().asMoney()
            },
            color = WheelColorsV2.amberFigure,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun TripStatusPill(status: TripPillStatus) {
    val brush = if (status == TripPillStatus.ACTIVE) WheelColorsV2.goldCtaBrush else WheelColorsV2.greenCtaBrush
    val textColor = if (status == TripPillStatus.ACTIVE) WheelColorsV2.onGoldCta else WheelColorsV2.onGreenCta
    Box(
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(brush).padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            if (status == TripPillStatus.ACTIVE) "ACTIVE" else "DONE",
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Trip History variant — Figma node 35:170: filter pills + flat rows + shift-report footer.
// Filters are presentational only ([TripEntity] carries no client-side date field cheaper than
// parsing [TripEntity.startAt], and this app has no server-side date-range trip query yet — see
// [au.com.threesixty.cabdispatch.data.local.dao.TripDao] — so WEEK/MONTH/ALL currently show the
// same on-device recent-trips list as TODAY rather than silently fabricating a filtered result).
// ---------------------------------------------------------------------------------------------

private enum class HistoryFilter(val label: String) { TODAY("TODAY"), WEEK("WEEK"), MONTH("MONTH"), ALL("ALL") }

@Composable
private fun TripHistoryBody(
    trips: List<TripEntity>,
    onTripClick: (String) -> Unit,
    onShiftReportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(HistoryFilter.TODAY) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HistoryFilter.entries.forEach { f ->
                HistoryFilterPill(label = f.label, selected = f == filter, onClick = { filter = f })
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(trips, key = { it.clientUuid }) { trip ->
                TripHistoryRow(trip) { onTripClick(trip.clientUuid) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val total = trips.fold(java.math.BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Showing last ${trips.size} · shift total ${total.asMoney()} · ${trips.size} trips",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(WheelColorsV2.steelTileBrush)
                    .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(14.dp))
                    .clickable(onClick = onShiftReportClick)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text("SHIFT REPORT", color = WheelColorsV2.steelTileText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HistoryFilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val brush: Brush = if (selected) WheelColorsV2.goldCtaBrush else WheelColorsV2.steelTileBrush
    val textColor = if (selected) WheelColorsV2.onGoldCta else WheelColorsV2.steelTileText
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(brush)
            .border(1.dp, if (selected) WheelColorsV2.activeTileBorder else WheelColorsV2.glassBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun TripHistoryRow(trip: TripEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColorsV2.rowGlass, RoundedCornerShape(14.dp))
            .border(1.dp, WheelColorsV2.rowBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            trip.startAt.asLocalTime(),
            color = Color(0xFFF4FAFF),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
        Text(
            trip.type.asTripTypeLabel(),
            color = Color.White.copy(alpha = 0.92f),
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xE6221B3E))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                trip.paymentMethod.asPaymentMethodLabel().uppercase(),
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
        Text(
            trip.deviceTotal.toBigDecimalOrZero().asMoney(),
            color = WheelColorsV2.amberFigure,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
    }
}
