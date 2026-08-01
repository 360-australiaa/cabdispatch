package au.com.threesixty.cabdispatch.ui.screens.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TripsWheelUiState(
    val loading: Boolean = true,
    val trips: List<TripEntity> = emptyList(),
)

/**
 * Wheel slot 3 — "Trips" content pane (spec §4: "trip history rows — route,
 * time, payment method, fare amount"). Reads straight off
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeRecentTrips] —
 * the same offline-first Room source [au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel]
 * already reads for S5, just not scoped to a single shift — rather than
 * re-fetching or inventing a second trip-history data path.
 */
class TripsWheelViewModel : ViewModel() {

    private val tripDao = AppContainer.tripDao

    private val _uiState = MutableStateFlow(TripsWheelUiState())
    val uiState: StateFlow<TripsWheelUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            tripDao.observeRecentTrips().collect { trips ->
                _uiState.value = TripsWheelUiState(loading = false, trips = trips)
            }
        }
    }
}
