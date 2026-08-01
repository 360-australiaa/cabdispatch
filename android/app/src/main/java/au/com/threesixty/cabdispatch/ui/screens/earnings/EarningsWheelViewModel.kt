package au.com.threesixty.cabdispatch.ui.screens.earnings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class EarningsWheelUiState(
    val loading: Boolean = true,
    val todayTotal: BigDecimal = BigDecimal.ZERO,
    val cardTotal: BigDecimal = BigDecimal.ZERO,
    val cashTotal: BigDecimal = BigDecimal.ZERO,
    val tripsCount: Int = 0,
)

/**
 * Wheel slot 4 — "Earnings" content pane (spec §4: "stat grid — today's
 * total, card split, cash split, trip count"). Same data source as
 * [au.com.threesixty.cabdispatch.ui.screens.trips.TripsWheelViewModel] (finalized
 * [TripEntity] rows via
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeRecentTrips]),
 * filtered to the driver's local calendar day and aggregated the same way
 * [au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel.recomputeAggregates]
 * aggregates a shift's trips — just scoped to "today" instead of "the active
 * shift", since Earnings is explicitly a daily view (spec §5's "Quick stats,
 * top-right: today's trips / hours / earnings" matches this same framing).
 */
class EarningsWheelViewModel : ViewModel() {

    private val tripDao = AppContainer.tripDao

    private val _uiState = MutableStateFlow(EarningsWheelUiState())
    val uiState: StateFlow<EarningsWheelUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            tripDao.observeRecentTrips(limit = 200).collect { trips -> recompute(trips) }
        }
    }

    private fun recompute(trips: List<TripEntity>) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val todaysTrips = trips.filter { it.startedOn(today) }

        val cashTotal = todaysTrips.filter { it.paymentMethod == "cash" }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() }
        val cardTotal = todaysTrips.filter { it.paymentMethod == "card" }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() }

        _uiState.value = EarningsWheelUiState(
            loading = false,
            todayTotal = cashTotal + cardTotal,
            cardTotal = cardTotal,
            cashTotal = cashTotal,
            tripsCount = todaysTrips.size,
        )
    }

    private fun TripEntity.startedOn(date: LocalDate): Boolean = runCatching {
        Instant.parse(startAt).atZone(ZoneId.systemDefault()).toLocalDate() == date
    }.getOrDefault(false)
}
