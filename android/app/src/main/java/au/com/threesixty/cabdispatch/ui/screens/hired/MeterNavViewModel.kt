package au.com.threesixty.cabdispatch.ui.screens.hired

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.DirectionsRoute
import au.com.threesixty.cabdispatch.data.remote.GeocodeResult
import au.com.threesixty.cabdispatch.data.remote.MapboxDirections
import au.com.threesixty.cabdispatch.data.remote.MapboxGeocoding
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.SpeechAnnouncer
import au.com.threesixty.cabdispatch.domain.SpeechPriority
import au.com.threesixty.cabdispatch.domain.TextToSpeechAnnouncer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the meter screen's navigator pane renders, in one immutable snapshot. Every value
 * is derived from a real Mapbox response or a real GPS fix — a null/empty field means "not
 * known", never "made up".
 *
 * Search half ([query] ... [searchError]) and route half ([destination] ... [offRoute]) are
 * independent: the UI shows suggestions while typing, and the route card once a suggestion is
 * picked.
 */
data class MeterNavUiState(
    /** Exactly what the driver has typed (echoed back so the text field is state-driven). */
    val query: String = "",
    /** Real geocoder results for [query]; replaced wholesale on every lookup, empty on failure. */
    val suggestions: List<GeocodeResult> = emptyList(),
    val searching: Boolean = false,
    /** Non-null after a failed lookup (network/HTTP/token) — render it, don't fake a list. */
    val searchError: String? = null,

    val destination: GeocodeResult? = null,
    val route: DirectionsRoute? = null,
    val routing: Boolean = false,
    /** Non-null after a failed route request; [MeterNavViewModel.retryRoute] clears it. */
    val routeError: String? = null,

    /** Index into `route.steps` of the maneuver being driven towards. Meaningless while [route] is null. */
    val currentStepIndex: Int = 0,
    /** `route.steps[currentStepIndex].instruction`, or null with no route. */
    val currentInstruction: String? = null,
    /** Straight-line distance to the *upcoming* maneuver point — [NavProgress.distanceToCurrentManeuverM].
     * The real "next turn in 300 m" readout, distinct from [remainingDistanceM]'s whole-trip figure. */
    val distanceToNextManeuverM: Double? = null,
    /** See [NavProgress.remainingDistanceM] for the approximation. Null with no route. */
    val remainingDistanceM: Double? = null,
    /** See [NavProgress.remainingDurationS] for the approximation. Null with no route. */
    val remainingDurationS: Double? = null,
    /** Wall-clock arrival estimate (`now + remainingDurationS`), refreshed on every fix. */
    val etaEpochMillis: Long? = null,
    /** True once [NavProgress.OFF_ROUTE_CONSECUTIVE] fixes in a row were off the line; cleared by a reroute or a fix back on it. */
    val offRoute: Boolean = false,
    /** Whether nav instructions are spoken. Mirrors the fare-speech toggle — see [MeterNavViewModel.setVoiceEnabled]. */
    val voiceEnabled: Boolean = false,

    /**
     * The real pickup address for this trip, resolved once [selectDestination] is first called
     * (see [MeterNavViewModel.resolvePickupAddress]) — either the dispatch-offer address the trip
     * already carried, or a fresh reverse-geocode of where the meter actually started. Null until
     * resolved (render an honest "—", never fabricate a placeholder); this is a display-only
     * mirror of [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.pickupAddress], not a
     * second source of truth for it.
     */
    val pickupAddress: String? = null,
)

/**
 * The navigator's state machine for the meter screen (S3 HIRED) — search a drop-off, fetch a
 * route, follow it fix by fix, speak each turn once, reroute when the vehicle leaves the line.
 *
 * Deliberately a *separate* ViewModel from [HiredViewModel]: that class owns the regulated fare
 * maths and its persistence, and nothing in here may influence a fare. The two meet in exactly
 * one place, the shared TTS engine behind [TextToSpeechAnnouncer], where fare announcements are
 * always spoken before queued nav instructions (see `SpeechAnnouncer.kt`'s doc).
 *
 * Inputs are the already-built REST gateways ([MapboxGeocoding], [MapboxDirections]) and the
 * live fix feed (`AppContainer.speedSource.locationFix`); the arithmetic lives in [NavProgress]
 * so it is unit tested without any of this class's Android plumbing.
 *
 * Wiring notes for the screen that plugs this in:
 * - Instantiate with the usual `viewModel()` alongside [HiredViewModel]; both die with the screen.
 * - Forward the existing fare-speech toggle to [setVoiceEnabled] so one switch mutes both — this
 *   class cannot observe [HiredViewModel.speechEnabled] itself without coupling the two, and its
 *   default (`false`) matches that flag's default.
 */
class MeterNavViewModel(application: Application) : AndroidViewModel(application) {

    private val geocoding: MapboxGeocoding = AppContainer.mapboxGeocoding
    private val directions: MapboxDirections = AppContainer.mapboxDirections
    private val tripRepository: TripRepository = AppContainer.tripRepository
    private val locationFix: StateFlow<LocationFix?> = AppContainer.speedSource.locationFix
    private val announcer: SpeechAnnouncer = TextToSpeechAnnouncer(application)

    private val _uiState = MutableStateFlow(MeterNavUiState())
    val uiState: StateFlow<MeterNavUiState> = _uiState.asStateFlow()

    /** Raw typed text, fed through the debounce — kept separate from [MeterNavUiState.query] so
     * echoing the selected destination's name into the field does not trigger a lookup. */
    private val typedQuery = MutableStateFlow("")

    private var routeJob: Job? = null
    private val offRouteTracker = NavProgress.OffRouteTracker()

    /** Epoch millis of the last *automatic* reroute request, for the rate limit. */
    private var lastRerouteRequestAt = 0L

    /** Step index whose instruction has already been spoken for the current route (-1 = none). */
    private var lastSpokenStepIndex = -1

    init {
        observeQuery()
        observeFixes()
    }

    // ---------------------------------------------------------------- search

    fun onQueryChange(text: String) {
        _uiState.update { it.copy(query = text, searchError = null) }
        typedQuery.value = text
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            typedQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                // collectLatest: a newer query cancels the wait on the older one, so a stale
                // response can never land on top of a fresher one — results replace, never append.
                .collectLatest { raw ->
                    val query = raw.trim()
                    if (query.length < MIN_QUERY_CHARS) {
                        _uiState.update { it.copy(suggestions = emptyList(), searching = false, searchError = null) }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(searching = true) }
                    val fix = locationFix.value
                    geocoding.search(
                        query = query,
                        proximityLat = fix?.lat,
                        proximityLng = fix?.lng,
                        limit = SUGGESTION_LIMIT,
                    ).fold(
                        onSuccess = { results ->
                            _uiState.update { it.copy(suggestions = results, searching = false, searchError = null) }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    suggestions = emptyList(),
                                    searching = false,
                                    searchError = error.message ?: "Address lookup failed",
                                )
                            }
                        },
                    )
                }
        }
    }

    // ---------------------------------------------------------------- destination / route

    fun selectDestination(result: GeocodeResult) {
        // A new destination invalidates any instruction still waiting for (or holding) the speaker.
        announcer.flushAll()
        _uiState.update {
            it.copy(
                query = result.placeName,
                suggestions = emptyList(),
                searching = false,
                searchError = null,
                destination = result,
            )
        }
        // The echoed name must not re-trigger a lookup: the debounced source is reset to a
        // below-minimum query (which only clears the already-empty suggestions), NOT to the
        // place name — feeding that in would fire a fresh search and repopulate the list.
        typedQuery.value = ""
        persistDropoff(result)
        resolvePickupAddress()
        requestRoute(result, automatic = false)
    }

    fun clearDestination() {
        routeJob?.cancel()
        routeJob = null
        // Spec: stop *and* flush — drops queued instructions and cuts one mid-sentence.
        announcer.flushAll()
        offRouteTracker.reset()
        lastSpokenStepIndex = -1
        typedQuery.value = ""
        _uiState.value = MeterNavUiState(voiceEnabled = _uiState.value.voiceEnabled)
    }

    /** Re-requests the route to the current [MeterNavUiState.destination] after a failure. */
    fun retryRoute() {
        val destination = _uiState.value.destination ?: return
        requestRoute(destination, automatic = false)
    }

    /**
     * Mirrors the fare-speech toggle ([HiredViewModel.speechEnabled]); the screen forwards it so
     * one switch mutes both. Muting silences nav immediately — pending instructions are dropped
     * and one already being spoken is cut off. Fare announcements (the other owner's) are never
     * touched from here.
     */
    fun setVoiceEnabled(enabled: Boolean) {
        _uiState.update { it.copy(voiceEnabled = enabled) }
        if (!enabled) announcer.flushAll()
    }

    /**
     * Fetches a route from the latest fix to [destination]. [automatic] marks an off-route
     * reroute (rate-limited by the caller); a manual select/retry always goes through. A
     * previous in-flight request is cancelled so only the newest can land.
     */
    private fun requestRoute(destination: GeocodeResult, automatic: Boolean) {
        val fix = locationFix.value
        if (fix == null) {
            _uiState.update { it.copy(routing = false, routeError = "Waiting for a GPS fix") }
            return
        }
        if (automatic) lastRerouteRequestAt = System.currentTimeMillis()
        routeJob?.cancel()
        _uiState.update { it.copy(routing = true, routeError = null) }
        routeJob = viewModelScope.launch {
            directions.route(fix.lat, fix.lng, destination.lat, destination.lng).fold(
                onSuccess = { route -> installRoute(route, fix) },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(routing = false, routeError = error.message ?: "Route request failed")
                    }
                },
            )
        }
    }

    private fun installRoute(route: DirectionsRoute, fix: LocationFix) {
        offRouteTracker.reset()
        lastSpokenStepIndex = -1
        val stepIndex = 0
        val remainingM = NavProgress.remainingDistanceM(fix.lat, fix.lng, route.steps, stepIndex)
        val remainingS = NavProgress.remainingDurationS(remainingM, route)
        _uiState.update {
            it.copy(
                route = route,
                routing = false,
                routeError = null,
                offRoute = false,
                currentStepIndex = stepIndex,
                currentInstruction = route.steps.getOrNull(stepIndex)?.instruction,
                distanceToNextManeuverM = NavProgress.distanceToCurrentManeuverM(fix.lat, fix.lng, route.steps, stepIndex),
                remainingDistanceM = remainingM,
                remainingDurationS = remainingS,
                etaEpochMillis = NavProgress.etaEpochMillis(System.currentTimeMillis(), remainingS),
            )
        }
        speakStep(route, stepIndex)
    }

    // ---------------------------------------------------------------- live progression

    private fun observeFixes() {
        viewModelScope.launch {
            locationFix.filterNotNull().collect { fix -> onFix(fix) }
        }
    }

    private fun onFix(fix: LocationFix) {
        val state = _uiState.value
        val route = state.route ?: return
        val destination = state.destination ?: return

        // Off-route detection: N consecutive fixes farther than OFF_ROUTE_M from every vertex.
        val distanceToRoute = NavProgress.distanceToNearestVertexM(fix.lat, fix.lng, route.points)
        val offRoute = offRouteTracker.onFix(distanceToRoute)
        if (offRoute) {
            _uiState.update { it.copy(offRoute = true) }
            val now = System.currentTimeMillis()
            if (!state.routing && now - lastRerouteRequestAt >= NavProgress.REROUTE_MIN_INTERVAL_MS) {
                requestRoute(destination, automatic = true)
            }
            // Progress along a route we've left is not meaningful; keep the last good readout.
            return
        }

        // Step advance + spoken instruction (once per step).
        val nextIndex = NavProgress.advanceStepIndex(fix.lat, fix.lng, route.steps, state.currentStepIndex)
        val remainingM = NavProgress.remainingDistanceM(fix.lat, fix.lng, route.steps, nextIndex)
        val remainingS = NavProgress.remainingDurationS(remainingM, route)
        _uiState.update {
            it.copy(
                offRoute = false,
                currentStepIndex = nextIndex,
                currentInstruction = route.steps.getOrNull(nextIndex)?.instruction,
                distanceToNextManeuverM = NavProgress.distanceToCurrentManeuverM(fix.lat, fix.lng, route.steps, nextIndex),
                remainingDistanceM = remainingM,
                remainingDurationS = remainingS,
                etaEpochMillis = NavProgress.etaEpochMillis(System.currentTimeMillis(), remainingS),
            )
        }
        if (nextIndex != state.currentStepIndex) speakStep(route, nextIndex)
    }

    private fun speakStep(route: DirectionsRoute, index: Int) {
        if (index == lastSpokenStepIndex) return
        lastSpokenStepIndex = index
        val instruction = route.steps.getOrNull(index)?.instruction ?: return
        if (_uiState.value.voiceEnabled) announcer.announce(instruction, SpeechPriority.NAV)
    }

    // ---------------------------------------------------------------- persistence

    /**
     * Best-effort: writes the picked drop-off onto the ACTIVE trip row. No active trip (the
     * navigator used before the meter's Room row exists, or outside a trip) simply persists
     * nothing — the route still works, and nothing is fabricated later.
     */
    private fun persistDropoff(result: GeocodeResult) {
        viewModelScope.launch {
            runCatching {
                val active = tripRepository.observeActiveTrip().first() ?: return@launch
                tripRepository.updateDropoff(
                    clientUuid = active.clientUuid,
                    address = result.placeName,
                    lat = result.lat,
                    lng = result.lng,
                )
            }
        }
    }

    /**
     * Fills [MeterNavUiState.pickupAddress] the moment the nav pane's PICK UP card first needs
     * one — i.e. right when a destination is picked, not lazily waiting for trip close.
     * [TripEntity.pickupAddress][au.com.threesixty.cabdispatch.data.local.entity.TripEntity.pickupAddress]'s
     * own doc explains why it is usually blank at this point: it is only populated at open time
     * from a dispatch offer's address, so a street-hail/rank/Start-Meter trip (this app's most
     * common case) has nothing there yet. Reuses [TripRepository.fillPickupAddressIfMissing] —
     * the exact same idempotent, never-overwrite, never-fabricate write the close-time call site
     * already makes — so there is exactly one path that ever fills that column, just two moments
     * that can trigger it. A failed/no-result reverse-geocode leaves [MeterNavUiState.pickupAddress]
     * null; the card renders its existing honest "—", nothing invented.
     */
    private fun resolvePickupAddress() {
        if (_uiState.value.pickupAddress != null) return
        viewModelScope.launch {
            runCatching {
                val active = tripRepository.observeActiveTrip().first() ?: return@launch
                val existing = active.pickupAddress
                if (!existing.isNullOrBlank()) {
                    _uiState.update { it.copy(pickupAddress = existing) }
                    return@launch
                }
                val resolved = AppContainer.mapboxReverseGeocoding
                    .reverseGeocode(active.startLat, active.startLng)
                    .getOrNull()
                    ?: return@launch
                tripRepository.fillPickupAddressIfMissing(active.clientUuid, resolved)
                _uiState.update { it.copy(pickupAddress = resolved) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        routeJob?.cancel()
        // Drops every utterance this ViewModel queued and stops it if one is mid-sentence; the
        // shared engine itself is torn down once the last holder (HiredViewModel) releases it.
        announcer.flushAll()
        announcer.shutdown()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val MIN_QUERY_CHARS = 3
        const val SUGGESTION_LIMIT = 5
    }
}
