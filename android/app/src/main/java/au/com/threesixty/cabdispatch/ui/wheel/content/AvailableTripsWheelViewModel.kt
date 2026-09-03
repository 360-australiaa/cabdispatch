package au.com.threesixty.cabdispatch.ui.wheel.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.remote.JobDto
import au.com.threesixty.cabdispatch.data.remote.JobOfferDto
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.util.UUID

/** One driver-facing job card — a job still in `offered` status paired with *this* driver's own
 * pending [JobOfferDto] for it (see [au.com.threesixty.cabdispatch.domain.JobOfferHandoff] doc). */
data class AvailableTripCard(val job: JobDto, val offer: JobOfferDto)

data class AvailableTripsUiState(
    val loading: Boolean = true,
    val cards: List<AvailableTripCard> = emptyList(),
    val error: String? = null,
    /** [JobOfferDto.id] currently mid-accept/decline — disables that card's buttons so a slow
     * network call can't double-submit against the ~20s offer expiry window. */
    val busyOfferId: String? = null,
    val actionError: String? = null,
    /** One-shot: true for exactly one recomposition after an accept succeeds, so the composable
     * can navigate to S3 (Hired) and then call [onNavigatedToHired] to clear it — same shape
     * [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen] uses for its `Done`
     * state -> `onDone()` callback. */
    val navigateToHired: Boolean = false,
)

/**
 * S11 — Available Trips wheel-slot content's data layer (spec TCT-DRIVER-APP-01.md §4:
 * "Available Trips: list of job cards — route, distance, estimated fare, time since requested"
 * and §8 row 11 "Job queue list" — ✅ Figma only, not in the HTML prototype; list-row visuals are
 * still ported 1:1 from the prototype's `.list-row` rule/`row()` helper, which the Figma-only
 * Available Trips screens also use per spec §4's own wording). Structured to mirror
 * [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel] (same sibling-foundation
 * pass, same WS-reconnect-loop shape) since both are thin
 * [au.com.threesixty.cabdispatch.domain.JobsRepository]/[au.com.threesixty.cabdispatch.domain.MessagesRepository]
 * wrappers with no offline queue.
 *
 * No bulk "my offers across every job" endpoint exists ([JobsRepository.listOffers] is per-job) —
 * [refresh] lists jobs still in `offered` status and fans out one `listOffers` call per job,
 * keeping only the offer that belongs to this driver and is still `pending`. Real-time delivery of
 * *new* offers is `WS /v1/jobs/live` (see [observeLive]), so this fan-out only runs on first
 * load/reconnect/explicit [refresh], not on a poll timer.
 */
class AvailableTripsWheelViewModel : ViewModel() {

    private val jobsRepository = AppContainer.jobsRepository
    private val driverId: String? = SessionHolder.session.value?.driverId

    private val _uiState = MutableStateFlow(AvailableTripsUiState(loading = driverId != null))
    val uiState: StateFlow<AvailableTripsUiState> = _uiState.asStateFlow()

    init {
        if (driverId == null) {
            // No signed-in driver session yet (e.g. previewing this screen before S1 completes) —
            // degrade to an empty, non-loading list rather than crashing, same convention
            // MessagesViewModel uses for the identical case.
            _uiState.update { it.copy(loading = false, error = "No active driver session.") }
        } else {
            refresh()
            observeLive(driverId)
        }
    }

    fun refresh() {
        val driverId = this.driverId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = it.cards.isEmpty(), error = null) }
            val jobsResult = jobsRepository.listJobs(status = "offered", limit = JOB_PAGE_LIMIT)
            val jobs = jobsResult.getOrNull()
            if (jobs == null) {
                _uiState.update {
                    it.copy(loading = false, error = jobsResult.exceptionOrNull()?.message ?: "Could not load available trips")
                }
                return@launch
            }
            val cards = mutableListOf<AvailableTripCard>()
            for (job in jobs.items) {
                val offer = jobsRepository.listOffers(job.id).getOrNull()
                    ?.firstOrNull { it.driverId == driverId && it.status == "pending" }
                if (offer != null) cards += AvailableTripCard(job, offer)
            }
            _uiState.update {
                it.copy(loading = false, error = null, cards = cards.sortedByDescending { c -> c.offer.offeredAt })
            }
            maybeAutoAccept()
        }
    }

    /**
     * Auto Accept Jobs (Settings -> General, 2026-09-03 Settings two-pane pass) — when the driver
     * has turned this on ([au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore]), the
     * next pending offer this ViewModel sees (via [refresh] or a live WS push in
     * [handleLiveFrame]) is accepted immediately, exactly as if the driver had tapped ACCEPT
     * themselves — reuses [acceptOffer] verbatim, no separate accept code path to keep honest.
     *
     * Only ever acts on a single card per call: a driver can only ever be on one job at a time,
     * and [acceptOffer]'s own hand-off navigates to S3/Hired the instant it succeeds, so
     * auto-accepting a second concurrently-listed offer in the same pass would be meaningless.
     * Skipped while a card is already mid-accept/decline ([AvailableTripsUiState.busyOfferId]) so
     * this can't double-submit against the same ~20s offer-expiry window every other accept path
     * in this class already guards against.
     */
    private fun maybeAutoAccept() {
        if (!AppContainer.settingsPreferencesStore.autoAcceptJobs.value) return
        if (_uiState.value.busyOfferId != null) return
        val card = _uiState.value.cards.firstOrNull() ?: return
        acceptOffer(card)
    }

    /**
     * Subscribes to `WS /v1/jobs/live` and reconnects with a flat 3s backoff on any
     * disconnect/failure — same shape as
     * [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel.observeLive], see that
     * method's doc for why (RealtimeSocket itself does not retry, per its own doc).
     */
    private fun observeLive(driverId: String) {
        viewModelScope.launch {
            while (isActive) {
                val token = AppContainer.accessToken
                if (token == null) {
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                runCatching {
                    jobsRepository.observeLiveOffers(token).collect { raw -> handleLiveFrame(raw, driverId) }
                }
                if (!isActive) break
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    /**
     * Best-effort decode of a `job_offer` push frame — schema is undocumented (see
     * [au.com.threesixty.cabdispatch.data.remote.RealtimeSocket] doc). Tries [JobOfferDto] first
     * (the smaller, more likely shape for a per-driver push); on any decode failure, or a decoded
     * offer that turns out not to be ours/pending, falls back to a full [refresh] rather than
     * guessing further — matches
     * [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel.handleLiveFrame]'s
     * philosophy exactly.
     */
    private fun handleLiveFrame(raw: String, driverId: String) {
        val offer = runCatching { cabDispatchJson.decodeFromString<JobOfferDto>(raw) }
            .getOrElse { e ->
                if (e !is SerializationException) throw e
                null
            }
        if (offer == null || offer.driverId != driverId || offer.status != "pending") {
            refresh()
            return
        }
        viewModelScope.launch {
            jobsRepository.getJob(offer.jobId)
                .onSuccess { job ->
                    _uiState.update { st ->
                        val without = st.cards.filterNot { it.offer.id == offer.id }
                        st.copy(cards = (without + AvailableTripCard(job, offer)).sortedByDescending { it.offer.offeredAt })
                    }
                    maybeAutoAccept()
                }
                .onFailure { refresh() }
        }
    }

    fun acceptOffer(card: AvailableTripCard) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyOfferId = card.offer.id, actionError = null) }
            jobsRepository.acceptOffer(card.job.id, card.offer.id)
                .onSuccess {
                    _uiState.update { st ->
                        st.copy(busyOfferId = null, cards = st.cards.filterNot { it.offer.id == card.offer.id })
                    }
                    beginHiredHandoff(card)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(busyOfferId = null, actionError = e.message ?: "Couldn't accept — offer may have expired")
                    }
                    refresh()
                }
        }
    }

    fun declineOffer(card: AvailableTripCard) {
        viewModelScope.launch {
            _uiState.update { it.copy(busyOfferId = card.offer.id, actionError = null) }
            jobsRepository.declineOffer(card.job.id, card.offer.id)
                .onSuccess {
                    _uiState.update { st ->
                        st.copy(busyOfferId = null, cards = st.cards.filterNot { it.offer.id == card.offer.id })
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busyOfferId = null, actionError = e.message ?: "Couldn't decline") }
                }
        }
    }

    fun onNavigatedToHired() {
        _uiState.update { it.copy(navigateToHired = false) }
    }

    /**
     * Hands the accepted job off to S3 (Hired/meter) the same way
     * [au.com.threesixty.cabdispatch.ui.screens.idle.IdleViewModel.startHire] hands off a
     * driver-initiated hire: populate [SessionHolder.pendingTrip], then flip [AvailableTripsUiState.navigateToHired]
     * so the composable navigates. Uses the job's own pickup coordinates
     * ([JobDto.originLat]/[JobDto.originLng]) for [TripContext.startLat]/[TripContext.startLng] —
     * an improvement over IdleViewModel's TODO(location sibling agent) 0.0/0.0 stub, since a
     * dispatched job actually carries a real pickup fix already, no GPS needed for this field
     * specifically.
     *
     * Verified (reconciliation pass): the composable navigates to
     * [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes.HIRED] (S3) directly, which
     * already knows how to consume [SessionHolder.pendingTrip] (see
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s init) — confirmed correct;
     * no intermediate "heading to pickup" screen was added. The row-28 "Navigate" affordance landed
     * instead on the pre-accept job-offer detail screen
     * ([au.com.threesixty.cabdispatch.ui.screens.availabletrips.AvailableTripOfferScreen]'s
     * "📍 Navigate to pickup"), consistent with spec §2's "no navigation while Hired" rule — see
     * [au.com.threesixty.cabdispatch.ui.overlays.NavigateOverlay]'s doc.
     */
    private fun beginHiredHandoff(card: AvailableTripCard) {
        val session = SessionHolder.session.value
        if (session == null) {
            _uiState.update {
                it.copy(actionError = "Trip accepted, but no active session — open it from the dashboard.")
            }
            return
        }
        viewModelScope.launch {
            // Pure local Room read, never blocks on network — see TariffCache doc. Region is a
            // real GPS-derived one-shot snapshot as of 2026-08-03 (see RegionResolver's doc for
            // why a distance-from-Sydney-CBD circle, not a real polygon/geofence lookup — neither
            // exists to call yet), replacing the former hardcoded DEFAULT_REGION constant.
            val region = RegionResolver.resolve(AppContainer.speedSource.locationFix.value)
            val tariff = AppContainer.tariffCache.getActiveTariff(region)
            if (tariff == null) {
                _uiState.update {
                    it.copy(actionError = "Trip accepted, but no cached tariff yet — open it from the dashboard.")
                }
                return@launch
            }
            SessionHolder.setPendingTrip(
                TripContext(
                    clientUuid = UUID.randomUUID().toString(),
                    tariff = tariff,
                    startLat = card.job.originLat,
                    startLng = card.job.originLng,
                    driverId = session.driverId,
                    vehicleId = session.vehicleId,
                    shiftId = session.shiftId,
                ),
            )
            _uiState.update { it.copy(navigateToHired = true) }
        }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 3000L
        private const val JOB_PAGE_LIMIT = 50
    }
}
