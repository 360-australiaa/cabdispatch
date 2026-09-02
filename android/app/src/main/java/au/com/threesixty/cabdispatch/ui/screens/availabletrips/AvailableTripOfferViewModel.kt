package au.com.threesixty.cabdispatch.ui.screens.availabletrips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.JobOfferHandoff
import au.com.threesixty.cabdispatch.domain.PendingJobOffer
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AvailableTripOfferUiState(
    val pending: PendingJobOffer? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** One-shot: true for exactly one recomposition after accept succeeds — see
     * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsUiState.navigateToHired]'s
     * doc for the identical shape/rationale. */
    val navigateToHired: Boolean = false,
    /** One-shot: true for exactly one recomposition after decline succeeds, so the screen can pop
     * back to the wheel-slot list. */
    val declined: Boolean = false,
)

/**
 * Row 12 — job-offer accept/decline detail screen's data layer (spec TCT-DRIVER-APP-01.md §8:
 * "Job queue list, offer accept/decline" — ✅ Figma only). Reads the tapped offer via
 * [JobOfferHandoff] (no nav-graph args — same convention
 * [au.com.threesixty.cabdispatch.domain.TripDetailHandoff] uses, see its doc), same overall shape
 * as [au.com.threesixty.cabdispatch.ui.screens.tripdetail.TripDetailViewModel].
 *
 * Deliberately a separate, smaller ViewModel from
 * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel] (the list) rather
 * than reusing it here the way
 * [au.com.threesixty.cabdispatch.ui.screens.messages.MessageThreadScreen] reuses
 * [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel]: a fresh instance of the
 * list ViewModel would re-run its jobs list-fetch + WS subscribe on every detail-screen open for no
 * benefit here, since [JobOfferHandoff] already carries the exact job+offer this screen needs. The
 * accept/decline network calls and the accept -> S3 hand-off are intentionally duplicated (not
 * shared) between the two ViewModels for the same reason — each is ~10 lines and independently
 * trivial to verify, vs. inventing a shared-instance-across-nav-entries mechanism this codebase
 * doesn't otherwise use.
 */
class AvailableTripOfferViewModel : ViewModel() {

    private val jobsRepository = AppContainer.jobsRepository

    private val _uiState = MutableStateFlow(AvailableTripOfferUiState(pending = JobOfferHandoff.pending.value))
    val uiState: StateFlow<AvailableTripOfferUiState> = _uiState.asStateFlow()

    fun accept() {
        val pending = _uiState.value.pending ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            jobsRepository.acceptOffer(pending.job.id, pending.offer.id)
                .onSuccess { beginHiredHandoff(pending) }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "Couldn't accept — offer may have expired") }
                }
        }
    }

    fun decline() {
        val pending = _uiState.value.pending ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            jobsRepository.declineOffer(pending.job.id, pending.offer.id)
                .onSuccess {
                    JobOfferHandoff.clear()
                    _uiState.update { it.copy(busy = false, declined = true) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, error = e.message ?: "Couldn't decline") }
                }
        }
    }

    /** Same hand-off shape as
     * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel.beginHiredHandoff]
     * — see that method's doc for the full rationale, including why
     * [TripContext.startLat]/[TripContext.startLng] use the job's own pickup fix rather than a
     * live GPS read, and the TODO on verifying the final nav route with the integration agent. */
    private fun beginHiredHandoff(pending: PendingJobOffer) {
        val session = SessionHolder.session.value
        if (session == null) {
            _uiState.update { it.copy(busy = false, error = "Accepted, but no active session — open it from the dashboard.") }
            return
        }
        viewModelScope.launch {
            // Real GPS-derived one-shot region snapshot (2026-08-03) — replaces the former
            // hardcoded DEFAULT_REGION; see RegionResolver's doc for why a distance-from-Sydney-
            // CBD circle, not a real polygon/geofence lookup (neither exists to call yet).
            val region = RegionResolver.resolve(AppContainer.speedSource.locationFix.value)
            val tariff = AppContainer.tariffCache.getActiveTariff(region)
            if (tariff == null) {
                _uiState.update { it.copy(busy = false, error = "Accepted, but no cached tariff yet — open it from the dashboard.") }
                return@launch
            }
            SessionHolder.setPendingTrip(
                TripContext(
                    clientUuid = UUID.randomUUID().toString(),
                    tariff = tariff,
                    startLat = pending.job.originLat,
                    startLng = pending.job.originLng,
                    driverId = session.driverId,
                    vehicleId = session.vehicleId,
                    shiftId = session.shiftId,
                    // Real address plumbing (Phase A.4, 2026-09-03): this job already carries both
                    // — see TripContext.originAddress/.destAddress's own doc for why the dashboard's
                    // Start Meter flow (and, separately, the Dispatch wheel-content pane's own
                    // accept path) still can't set these.
                    originAddress = pending.job.originAddress,
                    destAddress = pending.job.destAddress,
                ),
            )
            JobOfferHandoff.clear()
            _uiState.update { it.copy(busy = false, navigateToHired = true) }
        }
    }

}
