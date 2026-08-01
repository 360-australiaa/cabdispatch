package au.com.threesixty.cabdispatch.ui.screens.tripdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.domain.fare.FareBreakdown
import au.com.threesixty.cabdispatch.domain.fare.reconstructFareState
import au.com.threesixty.cabdispatch.domain.fare.toDomainTariff
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

sealed interface TripDetailUiState {
    data object Loading : TripDetailUiState
    data class Error(val message: String) : TripDetailUiState
    data class Loaded(val trip: TripEntity, val breakdown: FareBreakdown) : TripDetailUiState
}

/**
 * Row 16 — Trip detail (spec §8: "tap a trip history row to see its full
 * fare breakdown"). Reads the tapped trip via [TripDetailHandoff] (no
 * nav-graph args, same convention [au.com.threesixty.cabdispatch.domain.SessionHolder.pendingTrip]
 * already uses) and re-derives the exact same [FareBreakdown] S4 (Close &
 * Pay) computed at close time, via
 * [au.com.threesixty.cabdispatch.domain.fare.reconstructFareState] +
 * [au.com.threesixty.cabdispatch.domain.fare.FareEngine.close] — the same
 * pattern [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel]
 * uses, just fed the trip's *persisted* paymentMethod/surchargePct/
 * cleaningFee/includePsl (all fixed by the time a trip is closed) instead of
 * live-editable UI state.
 */
class TripDetailViewModel : ViewModel() {

    private val tripDao = AppContainer.tripDao
    private val tariffDao = AppContainer.tariffDao
    private val fareEngine = AppContainer.pureFareEngine

    private val _uiState = MutableStateFlow<TripDetailUiState>(TripDetailUiState.Loading)
    val uiState: StateFlow<TripDetailUiState> = _uiState.asStateFlow()

    init {
        val clientUuid = TripDetailHandoff.pendingClientUuid.value
        if (clientUuid == null) {
            _uiState.value = TripDetailUiState.Error("No trip selected.")
        } else {
            viewModelScope.launch { load(clientUuid) }
        }
    }

    private suspend fun load(clientUuid: String) {
        val trip = tripDao.getByClientUuid(clientUuid)
        if (trip == null) {
            _uiState.value = TripDetailUiState.Error("Trip not found (id=$clientUuid).")
            return
        }

        val tariffEntity = tariffDao.getById(trip.tariffId)
        if (tariffEntity == null) {
            _uiState.value = TripDetailUiState.Error("No cached tariff for this trip (tariffId=${trip.tariffId}).")
            return
        }
        val tariffDto = runCatching { cabDispatchJson.decodeFromString<TariffDto>(tariffEntity.rawJson) }.getOrNull()
        if (tariffDto == null) {
            _uiState.value = TripDetailUiState.Error("Cached tariff payload is corrupt (tariffId=${trip.tariffId}).")
            return
        }

        val tariff = tariffDto.toDomainTariff()
        val state = reconstructFareState(trip, tariff)
        val breakdown = fareEngine.close(
            state = state,
            paymentMethod = trip.paymentMethod,
            surchargePct = trip.surchargePct?.toBigDecimalOrZero(),
            cleaningFee = trip.cleaningFee.toBigDecimalOrZero(),
            includePsl = trip.includePsl,
        )
        _uiState.value = TripDetailUiState.Loaded(trip, breakdown)
    }
}
