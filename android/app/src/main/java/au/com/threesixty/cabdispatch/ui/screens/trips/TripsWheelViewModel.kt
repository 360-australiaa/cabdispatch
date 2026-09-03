package au.com.threesixty.cabdispatch.ui.screens.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.local.dao.TripPeriod
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripsWheelUiState(
    val loading: Boolean = true,
    val trips: List<TripEntity> = emptyList(),
    /** [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeActiveTrip] — the single
     * in-progress (HIRED) trip, if any. Real state (2026-08-26 dock-menu v2 reskin pass, added so
     * the "My Trips" panel's ACTIVE row + "OPEN ACTIVE TRIP" CTA reflect an actual open trip rather
     * than fabricated mock data), not a new business concept: this is the exact same query
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen]'s flow is keyed off already. */
    val activeTrip: TripEntity? = null,
    /** Selected History-pane filter pill (Phase C, 2026-09-03) — real state, see [historyTrips]. */
    val historyPeriod: TripPeriod = TripPeriod.TODAY,
    /** [historyPeriod]'s real date-range query result (
     * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeTripsInRange]) — replaces the
     * former "every filter pill shows the same [trips] list" placeholder; TODAY/WEEK/MONTH/ALL now
     * genuinely differ. */
    val historyTrips: List<TripEntity> = emptyList(),
    val historyLoading: Boolean = true,
)

/**
 * Wheel slot 3 — "Trips" content pane (spec §4: "trip history rows — route,
 * time, payment method, fare amount"). Reads straight off
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeRecentTrips] —
 * the same offline-first Room source [au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel]
 * already reads for S5, just not scoped to a single shift — rather than
 * re-fetching or inventing a second trip-history data path. [TripsWheelUiState.activeTrip] additionally reads
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeActiveTrip] for the v2 "My Trips"
 * panel's real ACTIVE-trip row/CTA (see that field's doc).
 *
 * History-pane real filters (Phase C, 2026-09-03): [TripsWheelUiState.historyTrips] is a
 * *separate* stream from [TripsWheelUiState.trips], keyed off [TripsWheelUiState.historyPeriod]
 * via [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeTripsInRange] — kept apart from
 * the "My Trips" panel's fixed-size [TripsWheelUiState.trips] (used for that panel's ACTIVE+recent
 * cards, unaffected by the History pane's filter pill) rather than repointing the one shared field,
 * since the two panes have genuinely different data needs (a capped "recent" window vs. an
 * unbounded period range) even though they're rendered by the same [TripsWheelViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripsWheelViewModel : ViewModel() {

    private val tripDao = AppContainer.tripDao

    private val _uiState = MutableStateFlow(TripsWheelUiState())
    val uiState: StateFlow<TripsWheelUiState> = _uiState.asStateFlow()

    private val _historyPeriod = MutableStateFlow(TripPeriod.TODAY)

    init {
        viewModelScope.launch {
            tripDao.observeRecentTrips().collect { trips ->
                _uiState.update { it.copy(loading = false, trips = trips) }
            }
        }
        viewModelScope.launch {
            tripDao.observeActiveTrip().collect { active ->
                _uiState.update { it.copy(activeTrip = active) }
            }
        }
        viewModelScope.launch {
            _historyPeriod.flatMapLatest { period ->
                tripDao.observeTripsInRange(sinceEpochMillis = period.startEpochMillis())
            }.collect { trips ->
                _uiState.update { it.copy(historyLoading = false, historyTrips = trips) }
            }
        }
    }

    /** Called by the History pane's filter pills — see [TripsWheelUiState.historyPeriod]'s doc. */
    fun setHistoryPeriod(period: TripPeriod) {
        if (_historyPeriod.value == period) return
        _historyPeriod.value = period
        _uiState.update { it.copy(historyPeriod = period, historyLoading = true) }
    }
}
