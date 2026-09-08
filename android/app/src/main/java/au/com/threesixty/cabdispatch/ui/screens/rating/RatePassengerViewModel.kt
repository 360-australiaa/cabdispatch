package au.com.threesixty.cabdispatch.ui.screens.rating

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TripRatingDto
import au.com.threesixty.cabdispatch.domain.RatePassengerHandoff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Backend max for `GET /v1/me/rating`'s `limit` query param — see [RatePassengerViewModel]'s own
 * doc for exactly what this bounds. */
private const val RATING_LOOKUP_LIMIT = 100

/**
 * Pure star-rating validation/lookup logic behind [RatePassengerViewModel] — plain objects (their
 * own top-level declarations, no Android/Compose/ViewModel dependency) so `RatingValidationTest`
 * can exercise them on the JVM, same rationale [au.com.threesixty.cabdispatch.ui.theme.HudRoll]'s
 * own doc gives for splitting pure logic out of a Composable/ViewModel this way. Mirrors the
 * backend's own bounds exactly: `RATING_MIN_STARS`/`RATING_MAX_STARS` = 1/5
 * (`backend/app/models/driver_engagement.py`), enforced again here so a bad value never leaves the
 * device — the backend's own 422 on an out-of-range `stars` is the real backstop either way.
 */
object RatingValidation {
    const val MIN_STARS = 1
    const val MAX_STARS = 5

    /** Whether [stars] is a postable rating (1-5 inclusive) — `0` (nothing picked yet) is
     * deliberately NOT valid, matching [RatePassengerUiState.ReadyToRate]'s own "0 = unset"
     * default. */
    fun isSubmittable(stars: Int): Boolean = stars in MIN_STARS..MAX_STARS

    /** Clamps a tapped star position into the valid display range — used by
     * [RatePassengerViewModel.setStars] so a caller can never push the control into an
     * out-of-range state even by construction. */
    fun clamp(stars: Int): Int = stars.coerceIn(0, MAX_STARS)
}

/**
 * Finds an existing rating for [tripId] in a driver's recent-ratings list — the pure half of
 * [RatePassengerViewModel]'s duplicate-detection (see that class's doc for the real, honestly
 * bounded limits of this check: it only ever sees the [RATING_LOOKUP_LIMIT] most recent ratings).
 */
object RatingLookup {
    fun findExisting(recent: List<TripRatingDto>, tripId: String): TripRatingDto? =
        recent.firstOrNull { it.tripId == tripId }
}

sealed interface RatePassengerUiState {
    data object Loading : RatePassengerUiState

    /** No trip was handed off — this screen was reached some way other than straight out of
     * Close & Pay's receipt step (its only real entry point today). */
    data class NoTrip(val message: String) : RatePassengerUiState

    /** The just-closed trip hasn't synced to the server yet, so there is no real server trip id to
     * rate against — same honest gate [au.com.threesixty.cabdispatch.ui.screens.tripdetail.TripDetailViewModel.submitDispute]
     * uses for its own "needs a real server id" precondition. [retry] re-runs the load. */
    data class NotSynced(val message: String) : RatePassengerUiState

    data class ReadyToRate(
        val stars: Int = 0,
        val comment: String = "",
        val submitting: Boolean = false,
        val error: String? = null,
    ) : RatePassengerUiState

    /** This trip already has a rating (detected from the driver's own recent-ratings list, see
     * [RatePassengerViewModel]'s class doc) — shown read-only rather than letting a duplicate be
     * posted. */
    data class AlreadyRated(val rating: TripRatingDto) : RatePassengerUiState

    data class Submitted(val rating: TripRatingDto) : RatePassengerUiState
}

/**
 * New post-trip "Rate Passenger" screen/ViewModel (2026-09-04) — the ratings backend just landed
 * (`backend/app/api/v1/ratings.py`); there was no passenger-rating screen anywhere in this app
 * before this pass. Reads the just-closed trip via [RatePassengerHandoff] (same no-nav-graph-
 * argument convention [au.com.threesixty.cabdispatch.domain.TripDetailHandoff] already
 * established for this app's route constants — see that object's own doc) and posts through
 * [au.com.threesixty.cabdispatch.domain.DriverEngagementRepository.submitRating]
 * (`POST /v1/trips/{trip_id}/rating`).
 *
 * **Duplicate-rating detection, honestly bounded.** The backend exposes no
 * `GET /v1/trips/{id}/rating` single-trip lookup — only `GET /v1/me/rating`, an aggregate plus the
 * driver's [RATING_LOOKUP_LIMIT] most recent ratings (see
 * [au.com.threesixty.cabdispatch.domain.DriverEngagementRepository.rating]). On load this fetches
 * that list and checks whether the current trip's server id is already in it; if so the screen
 * renders [RatePassengerUiState.AlreadyRated] read-only instead of letting a second POST fire
 * (which the backend would 409 anyway — see [au.com.threesixty.cabdispatch.data.remote.ApiService.rateTrip]'s
 * doc — but that's a worse UX than not offering the form at all). This is a real, bounded check: a
 * trip rated long enough ago to have scrolled off the most-recent-100 window will NOT be caught
 * here, and [submit] then surfaces the backend's own 409 as an honest error instead of a silent
 * failure. Never a lie in either direction — a *failed* lookup (network error) falls through to
 * [RatePassengerUiState.ReadyToRate] exactly as if the trip were genuinely unrated, since the
 * backend's own duplicate check on submit is the real backstop either way.
 */
class RatePassengerViewModel : ViewModel() {

    private val tripDao = AppContainer.tripDao
    private val engagementRepository = AppContainer.driverEngagementRepository

    private val _uiState = MutableStateFlow<RatePassengerUiState>(RatePassengerUiState.Loading)
    val uiState: StateFlow<RatePassengerUiState> = _uiState.asStateFlow()

    /** The trip's real server id, resolved once [load] succeeds past [RatePassengerUiState.NotSynced] —
     * needed by [submit], which is otherwise only ever reached from [RatePassengerUiState.ReadyToRate]. */
    private var serverTripId: String? = null
    private var pendingClientUuid: String? = null

    init {
        val clientUuid = RatePassengerHandoff.pendingClientUuid.value
        if (clientUuid == null) {
            _uiState.value = RatePassengerUiState.NoTrip("No trip to rate.")
        } else {
            pendingClientUuid = clientUuid
            viewModelScope.launch { load(clientUuid) }
        }
    }

    /** Re-attempts [load] after a [RatePassengerUiState.NotSynced] result — e.g. once the driver
     * has checked Settings and the outbox has since drained. A no-op from any other state. */
    fun retry() {
        val clientUuid = pendingClientUuid ?: return
        if (_uiState.value !is RatePassengerUiState.NotSynced) return
        _uiState.value = RatePassengerUiState.Loading
        viewModelScope.launch { load(clientUuid) }
    }

    private suspend fun load(clientUuid: String) {
        val trip = tripDao.getByClientUuid(clientUuid)
        if (trip == null) {
            _uiState.value = RatePassengerUiState.NoTrip("Trip not found (id=$clientUuid).")
            return
        }
        val serverId = trip.serverId
        if (serverId == null) {
            _uiState.value = RatePassengerUiState.NotSynced(
                "This trip hasn't synced to the server yet — try again once it has " +
                    "(check the sync status on Settings).",
            )
            return
        }
        serverTripId = serverId

        val existing = engagementRepository.rating(recentLimit = RATING_LOOKUP_LIMIT)
            .getOrNull()
            ?.recent
            ?.let { RatingLookup.findExisting(it, serverId) }
        _uiState.value = if (existing != null) {
            RatePassengerUiState.AlreadyRated(existing)
        } else {
            RatePassengerUiState.ReadyToRate()
        }
    }

    fun setStars(stars: Int) {
        val current = _uiState.value as? RatePassengerUiState.ReadyToRate ?: return
        _uiState.value = current.copy(stars = RatingValidation.clamp(stars), error = null)
    }

    fun setComment(value: String) {
        val current = _uiState.value as? RatePassengerUiState.ReadyToRate ?: return
        _uiState.value = current.copy(comment = value, error = null)
    }

    fun submit() {
        val current = _uiState.value as? RatePassengerUiState.ReadyToRate ?: return
        if (current.submitting) return
        if (!RatingValidation.isSubmittable(current.stars)) {
            _uiState.value = current.copy(error = "Pick 1-5 stars before submitting.")
            return
        }
        val tripId = serverTripId
        if (tripId == null) {
            _uiState.value = current.copy(error = "This trip hasn't synced to the server yet.")
            return
        }
        _uiState.value = current.copy(submitting = true, error = null)
        viewModelScope.launch {
            val result = engagementRepository.submitRating(tripId, current.stars, current.comment.trim())
            val latest = _uiState.value as? RatePassengerUiState.ReadyToRate ?: return@launch
            result.fold(
                onSuccess = { rating -> _uiState.value = RatePassengerUiState.Submitted(rating) },
                onFailure = { e ->
                    _uiState.value = latest.copy(submitting = false, error = e.message ?: "Could not submit rating")
                },
            )
        }
    }

    /** Clears the hand-off so a stale trip id never leaks into a future visit — mirrors
     * [au.com.threesixty.cabdispatch.domain.TripDetailHandoff]'s own doc for why this pattern
     * exists at all (route constants in [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes]
     * carry no nav-graph arguments). Harmless even though this screen's only real caller
     * ([au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost]) always sets a fresh value
     * before navigating here again. */
    override fun onCleared() {
        super.onCleared()
        RatePassengerHandoff.clear()
    }
}
