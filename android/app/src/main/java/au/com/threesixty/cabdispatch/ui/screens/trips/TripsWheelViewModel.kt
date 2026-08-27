package au.com.threesixty.cabdispatch.ui.screens.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/**
 * Wheel slot 3 — "Trips" content pane (spec §4: "trip history rows — route,
 * time, payment method, fare amount"). Reads straight off
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeRecentTrips] —
 * the same offline-first Room source [au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel]
 * already reads for S5, just not scoped to a single shift — rather than
 * re-fetching or inventing a second trip-history data path. [activeTrip] additionally reads
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeActiveTrip] for the v2 "My Trips"
 * panel's real ACTIVE-trip row/CTA (see [TripsWheelUiState.activeTrip] doc).
 */
class TripsWheelViewModel : ViewModel() {

    private val tripDao = AppContainer.tripDao

    private val _uiState = MutableStateFlow(TripsWheelUiState())
    val uiState: StateFlow<TripsWheelUiState> = _uiState.asStateFlow()

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
    }
}
