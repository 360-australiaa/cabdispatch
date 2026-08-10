package au.com.threesixty.cabdispatch.ui.screens.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ZoneStatisticsUiState(
    val loading: Boolean = true,
    val stats: List<ZoneStatsDto> = emptyList(),
    val error: String? = null,
)

/**
 * Data layer for the Statistics screen: a table of zones and their live stats (plotted/vacant/
 * busy vehicles, jobs holding, bookings/street-hails last hour), auto-refreshing every 15-30s
 * while visible, matching a real competitor taxi meter zone-demand screen
 * (backend/app/api/v1/zones.py doc). Uses the exact same coroutine polling pattern
 * SettingsViewModel's periodic GPS/network checks already use
 * (viewModelScope.launch { while (isActive) { poll(); delay(interval) } }) rather than inventing
 * a new refresh mechanism, per the task brief. "While visible" falls out of ViewModel lifecycle
 * for free: this screen has its own back-stack entry with its own ViewModel scope, so navigating
 * away cancels viewModelScope and the loop stops with it -- no explicit start/stop wiring needed.
 */
class ZoneStatisticsViewModel : ViewModel() {

    private val zonesRepository = AppContainer.zonesRepository

    private val _uiState = MutableStateFlow(ZoneStatisticsUiState())
    val uiState: StateFlow<ZoneStatisticsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshOnce() {
        _uiState.update { it.copy(loading = it.stats.isEmpty(), error = null) }
        zonesRepository.getZoneStats()
            .onSuccess { stats -> _uiState.update { it.copy(loading = false, error = null, stats = stats) } }
            .onFailure { e ->
                _uiState.update { it.copy(loading = false, error = e.message ?: "Could not load zone stats") }
            }
    }

    companion object {
        /** 20s -- within the task brief's 15-30s auto-refresh window, same order of magnitude as
         * SettingsViewModel.GPS_NETWORK_POLL_INTERVAL_MS's own poll cadence. */
        private const val REFRESH_INTERVAL_MS = 20_000L
    }
}
