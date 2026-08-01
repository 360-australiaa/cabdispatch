package au.com.threesixty.cabdispatch.ui.screens.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.domain.format.asPaymentMethodLabel
import au.com.threesixty.cabdispatch.domain.format.asTripTypeLabel
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.TRIPS] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Trips: trip history rows — route, time, payment method, fare
 * amount") — a direct Compose port of the reference prototype's `tripRow(...)`-generated
 * `.list-row` markup for the Trips slot (docs/driver-dashboard-full-prototype.html lines
 * ~335-337, 353-357), newest trip first.
 *
 * Renders only the content-pane *body*, not the pane's eyebrow/hero title chrome — same
 * convention as [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent] (the
 * sibling Messages feature's equivalent), which the wheel-dashboard host screen owns uniformly
 * for every slot (spec §4 intro). See [TripsWheelViewModel] for the data flow.
 *
 * "Route" falls back to the trip's type label rather than a real origin->destination address —
 * see [au.com.threesixty.cabdispatch.domain.format.asTripTypeLabel]'s doc for why.
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * renders this composable for [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.TRIPS], wiring
 * [onTripClick] exactly as suggested below — see that screen's `TripsSlotContent`:
 * `TripsWheelContent(onTripClick = { uuid -> TripDetailHandoff.set(uuid); navController.navigate(CabDispatchRoutes.TRIP_DETAIL) })`
 * (see [au.com.threesixty.cabdispatch.domain.TripDetailHandoff]'s doc for why the hand-off object
 * instead of a nav-graph argument).
 */
@Composable
fun TripsWheelContent(
    modifier: Modifier = Modifier,
    onTripClick: (clientUuid: String) -> Unit = {},
    viewModel: TripsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    when {
        state.loading -> Text("Loading trips…", color = WheelColors.textSecondary, fontSize = 14.sp)
        state.trips.isEmpty() -> Text("No trips yet.", color = WheelColors.textSecondary, fontSize = 14.sp)
        else -> LazyColumn(modifier = modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.trips, key = { it.clientUuid }) { trip ->
                TripHistoryRow(trip) { onTripClick(trip.clientUuid) }
            }
        }
    }
}

@Composable
private fun TripHistoryRow(trip: TripEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColors.surfaceRaised.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(1.dp, WheelColors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                trip.type.asTripTypeLabel(),
                color = WheelColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${trip.startAt.asLocalTime()} · ${trip.paymentMethod.asPaymentMethodLabel()}",
                color = WheelColors.textSecondary,
                fontSize = 12.sp,
            )
        }
        Text(
            trip.deviceTotal.toBigDecimalOrZero().asMoney(),
            color = WheelColors.gold,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
