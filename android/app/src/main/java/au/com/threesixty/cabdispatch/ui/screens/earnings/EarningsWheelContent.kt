package au.com.threesixty.cabdispatch.ui.screens.earnings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.domain.format.asMoney

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.EARNINGS] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Earnings: stat grid — today's total, card split, cash split, trip
 * count") — a direct Compose port of the reference prototype's Earnings `.stat-grid`
 * (docs/driver-dashboard-full-prototype.html lines ~359-365).
 *
 * Renders only the content-pane *body* (the stat grid itself), not the pane's eyebrow/hero title
 * chrome — same convention as [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent],
 * which the wheel-dashboard host screen owns uniformly for every slot (spec §4 intro).
 *
 * See [EarningsWheelViewModel] for the data flow (same offline Room source the "Trips" wheel
 * content pane reads, aggregated to today's calendar day).
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * renders this composable for [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.EARNINGS] — see
 * that screen's `EarningsSlotContent`.
 */
@Composable
fun EarningsWheelContent(
    modifier: Modifier = Modifier,
    viewModel: EarningsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.loading) {
        CircularProgressIndicator()
        return
    }

    StatGrid(
        listOf(
            state.todayTotal.asMoney() to "Today",
            state.cardTotal.asMoney() to "Card",
            state.cashTotal.asMoney() to "Cash",
            state.tripsCount.toString() to "Trips",
        ),
        modifier = modifier,
    )
}
