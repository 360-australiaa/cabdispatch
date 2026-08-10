package au.com.threesixty.cabdispatch.ui.screens.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.ZoneDto
import au.com.threesixty.cabdispatch.domain.SessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Data layer for the Plot screen — a simple list of zones (name/number) the driver can tap to
 * plot into, a "currently plotted in: X" indicator, and an unplot action. Matches a real
 * competitor taxi meter's zone-plotting screen (backend/app/api/v1/zones.py's own doc). Structured
 * like [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel] — a one-shot
 * `driverId`/`shiftId` read off [SessionHolder.session] at construction, `refresh()`-driven
 * `MutableStateFlow`-backed [uiState] (no live-push counterpart here the way jobs/messages have a
 * WS feed — zone plotting is a driver-initiated action, not something dispatch pushes at them).
 */
sealed interface PlotZoneUiState {
    data object Loading : PlotZoneUiState

    data class Loaded(
        val zones: List<ZoneDto>,
        /** [au.com.threesixty.cabdispatch.data.remote.ZonePlotReadDto.plottedZoneId] for the
         * driver's own current shift — null means "not currently plotted into any zone". */
        val plottedZoneId: String? = null,
        /** True while a plot/unplot network call is in flight — disables every row + the unplot
         * button so a slow network can't double-submit. */
        val busy: Boolean = false,
        val error: String? = null,
    ) : PlotZoneUiState

    data class Error(val message: String) : PlotZoneUiState
}

class PlotZoneViewModel : ViewModel() {

    private val zonesRepository = AppContainer.zonesRepository
    private val shiftRepository = AppContainer.shiftRepository

    /** One-shot read at construction, same convention
     * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel]'s `driverId`
     * property already uses — this screen is only ever opened once a driver session exists (the
     * dashboard's "Zones" entry point is not reachable before S1 completes), so a stale value
     * across a mid-session driver switch is not a real risk in practice. */
    private val shiftId: String? = SessionHolder.session.value?.shiftId

    private val _uiState = MutableStateFlow<PlotZoneUiState>(PlotZoneUiState.Loading)
    val uiState: StateFlow<PlotZoneUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = PlotZoneUiState.Loading
            val zonesResult = zonesRepository.listZones()
            val zones = zonesResult.getOrNull()
            if (zones == null) {
                _uiState.value = PlotZoneUiState.Error(
                    zonesResult.exceptionOrNull()?.message ?: "Could not load zones",
                )
                return@launch
            }
            // Current plot state is read off the driver's own shift, not carried by the zone
            // list itself (GET /v1/zones has no per-caller plotting field — see ZoneRead's own
            // shape, backend/app/schemas/zones.py). Best-effort: a shift-read failure degrades to
            // "unknown/not plotted" rather than blocking the zone list from showing at all — a
            // driver can still plot from here even if this one read failed.
            val plottedZoneId = shiftId?.let { shiftRepository.getShift(it).getOrNull()?.plottedZoneId }
            _uiState.value = PlotZoneUiState.Loaded(zones = zones.items, plottedZoneId = plottedZoneId)
        }
    }

    fun plotInto(zone: ZoneDto) {
        val current = _uiState.value as? PlotZoneUiState.Loaded ?: return
        if (current.busy || zone.id == current.plottedZoneId) return
        viewModelScope.launch {
            _uiState.update { (it as? PlotZoneUiState.Loaded)?.copy(busy = true, error = null) ?: it }
            zonesRepository.plotIntoZone(zone.id)
                .onSuccess { plot ->
                    _uiState.update {
                        (it as? PlotZoneUiState.Loaded)?.copy(busy = false, plottedZoneId = plot.plottedZoneId) ?: it
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        (it as? PlotZoneUiState.Loaded)?.copy(
                            busy = false,
                            error = e.message ?: "Could not plot into ${zone.name}",
                        ) ?: it
                    }
                }
        }
    }

    fun unplot() {
        val current = _uiState.value as? PlotZoneUiState.Loaded ?: return
        if (current.busy || current.plottedZoneId == null) return
        viewModelScope.launch {
            _uiState.update { (it as? PlotZoneUiState.Loaded)?.copy(busy = true, error = null) ?: it }
            zonesRepository.unplot()
                .onSuccess { plot ->
                    _uiState.update {
                        (it as? PlotZoneUiState.Loaded)?.copy(busy = false, plottedZoneId = plot.plottedZoneId) ?: it
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        (it as? PlotZoneUiState.Loaded)?.copy(busy = false, error = e.message ?: "Could not unplot") ?: it
                    }
                }
        }
    }
}
