package au.com.threesixty.cabdispatch.ui.screens.tripdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.data.remote.TripFlagRequestDto
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.domain.fare.FareBreakdown
import au.com.threesixty.cabdispatch.domain.fare.Tariff
import au.com.threesixty.cabdispatch.domain.fare.reconstructFareState
import au.com.threesixty.cabdispatch.domain.fare.toDomainTariff
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

/** Submission state for the "Dispute" action ([TripDetailViewModel.submitDispute]) — a small local
 * enum rather than reusing [au.com.threesixty.cabdispatch.ui.screens.closepay.ActionState] (that
 * one's scoped to S4's receipt-delivery actions on a screen this one doesn't otherwise depend on —
 * keeping them separate avoids an odd cross-feature coupling for a 4-value enum). */
enum class DisputeSubmitState { IDLE, IN_PROGRESS, SUBMITTED, FAILED }

sealed interface TripDetailUiState {
    data object Loading : TripDetailUiState
    data class Error(val message: String) : TripDetailUiState
    data class Loaded(
        val trip: TripEntity,
        val breakdown: FareBreakdown,
        /** The tariff this trip was actually closed against — needed for the fare-card's own
         * maxi-multiplier display (see [au.com.threesixty.cabdispatch.ui.screens.tripdetail.FareCard]),
         * which must read [Tariff.maxiMultiplier] rather than hardcode "×1.5". */
        val tariff: Tariff,
        val disputeReason: String = "",
        val disputeState: DisputeSubmitState = DisputeSubmitState.IDLE,
        val disputeError: String? = null,
    ) : TripDetailUiState
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
        _uiState.value = TripDetailUiState.Loaded(trip, breakdown, tariff)
    }

    fun setDisputeReason(value: String) {
        val current = _uiState.value as? TripDetailUiState.Loaded ?: return
        _uiState.value = current.copy(disputeReason = value, disputeError = null)
    }

    /**
     * Blueprint 5.2.5's "Dispute" button — `PATCH /v1/trips/{id}/flag` (see
     * [au.com.threesixty.cabdispatch.data.remote.ApiService.flagTrip]'s own doc for the exact
     * backend contract). Two client-side gates before the network call, both mirroring real
     * backend rejections rather than guessing new ones:
     * - a non-blank reason (backend 422s `DisputeReasonRequiredError` without one), and
     * - a real server [TripEntity.serverId] — this screen can show a trip that closed offline and
     *   hasn't synced yet (see [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeRecentTrips]'s
     *   doc: "finalized" means closed-or-synced, not synced-only), and there is no server-side trip
     *   id to flag until [au.com.threesixty.cabdispatch.sync.SyncWorker] confirms one.
     *
     * **Not persisted locally, flagged rather than silently assumed richer:** a successful dispute
     * only updates this screen's in-memory [TripDetailUiState.Loaded.disputeState] — [TripEntity]
     * has no local "flagged" column (the backend's `flagged_for_review`/`review_notes` live
     * server-side only). Leaving this screen and reopening the same trip shows the dispute form
     * fresh again; re-submitting is safe (the backend just re-sets `flagged=true` with the latest
     * `reason`), it just doesn't show prior-submission history.
     */
    fun submitDispute() {
        val current = _uiState.value as? TripDetailUiState.Loaded ?: return
        if (current.disputeState == DisputeSubmitState.IN_PROGRESS) return

        val reason = current.disputeReason.trim()
        if (reason.isEmpty()) {
            _uiState.value = current.copy(disputeError = "Enter a reason before submitting a dispute.")
            return
        }
        val serverId = current.trip.serverId
        if (serverId == null) {
            _uiState.value = current.copy(
                disputeState = DisputeSubmitState.FAILED,
                disputeError = "This trip hasn't synced to the server yet — try again once it has " +
                    "(check the sync status on Settings).",
            )
            return
        }

        _uiState.value = current.copy(disputeState = DisputeSubmitState.IN_PROGRESS, disputeError = null)
        viewModelScope.launch {
            val result = runCatching {
                AppContainer.apiService.flagTrip(serverId, TripFlagRequestDto(flagged = true, reason = reason))
            }
            val latest = _uiState.value as? TripDetailUiState.Loaded ?: return@launch
            result.fold(
                onSuccess = {
                    _uiState.value = latest.copy(disputeState = DisputeSubmitState.SUBMITTED, disputeError = null)
                },
                onFailure = { e ->
                    _uiState.value = latest.copy(
                        disputeState = DisputeSubmitState.FAILED,
                        disputeError = e.message ?: "Could not submit dispute",
                    )
                },
            )
        }
    }
}
