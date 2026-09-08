package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.AnnouncementDto
import au.com.threesixty.cabdispatch.data.remote.IncentiveProgressDto
import au.com.threesixty.cabdispatch.data.remote.RatingDto
import au.com.threesixty.cabdispatch.data.remote.WalletDto
import au.com.threesixty.cabdispatch.domain.DriverEngagementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

/**
 * One tile's worth of state. [data] is whatever the last *successful* load returned — it is kept
 * across a failed refresh (with [error] set alongside it) so a transient network blip degrades to
 * "last known real value + couldn't refresh", never to a blank tile; [data] is `null` only until
 * the first success. [loading] is true while a fetch is in flight, whether or not [data] exists.
 */
data class EngagementSection<T>(
    val loading: Boolean = false,
    val error: String? = null,
    val data: T? = null,
)

data class DriverEngagementUiState(
    val wallet: EngagementSection<WalletDto> = EngagementSection(),
    val rating: EngagementSection<RatingDto> = EngagementSection(),
    val announcements: EngagementSection<List<AnnouncementDto>> = EngagementSection(),
    val incentives: EngagementSection<List<IncentiveProgressDto>> = EngagementSection(),
) {
    /** Any section still fetching — drives the shared refresh spinner. */
    val refreshing: Boolean
        get() = wallet.loading || rating.loading || announcements.loading || incentives.loading
}

/**
 * Data layer for the dashboard's WALLET BALANCE / RATING / ANNOUNCEMENTS / INCENTIVE PROGRESS
 * tiles ([EngagementTiles]) — four independent sections over the four `GET /v1/me/{wallet,rating,announcements,incentives}` reads
 * (backend commit 58ccfcf), each with its own loading/error/data so one failing endpoint never
 * blanks the other three.
 *
 * No polling loop (unlike `ZoneStatisticsViewModel`): none of these change second-to-second, and
 * the balance/rating are server-derived aggregates that only move when a trip closes or an
 * operator posts a line. Instead [refreshAll] is called on every Dashboard-pane entry (see
 * [DriverEngagementTiles]'s `LaunchedEffect`) and from the tiles' manual refresh control; each
 * section also has its own retry. Real data only: nothing here ever seeds a default balance,
 * score, announcement or incentive.
 *
 * All constructor params default so `viewModel()` can construct it reflectively; the repository
 * param exists for previews/tests to inject a fake.
 */
class DriverEngagementViewModel(
    private val repository: DriverEngagementRepository = AppContainer.driverEngagementRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DriverEngagementUiState())
    val uiState: StateFlow<DriverEngagementUiState> = _uiState.asStateFlow()

    fun refreshAll() {
        refreshWallet()
        refreshRating()
        refreshAnnouncements()
        refreshIncentives()
    }

    fun refreshWallet() {
        if (_uiState.value.wallet.loading) return
        _uiState.update { it.copy(wallet = it.wallet.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = repository.wallet()
            _uiState.update { it.copy(wallet = it.wallet.settle(result)) }
        }
    }

    fun refreshRating() {
        if (_uiState.value.rating.loading) return
        _uiState.update { it.copy(rating = it.rating.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = repository.rating()
            _uiState.update { it.copy(rating = it.rating.settle(result)) }
        }
    }

    fun refreshAnnouncements() {
        if (_uiState.value.announcements.loading) return
        _uiState.update { it.copy(announcements = it.announcements.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = repository.announcements()
            _uiState.update { it.copy(announcements = it.announcements.settle(result)) }
        }
    }

    fun refreshIncentives() {
        if (_uiState.value.incentives.loading) return
        _uiState.update { it.copy(incentives = it.incentives.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = repository.incentives()
            _uiState.update { it.copy(incentives = it.incentives.settle(result)) }
        }
    }

    /** Success replaces [EngagementSection.data]; failure keeps the last real data and records
     * the error next to it (see [EngagementSection]'s doc). */
    private fun <T> EngagementSection<T>.settle(result: Result<T>): EngagementSection<T> =
        result.fold(
            onSuccess = { EngagementSection(loading = false, error = null, data = it) },
            onFailure = { copy(loading = false, error = describe(it)) },
        )

    private fun describe(e: Throwable): String = when (e) {
        is HttpException -> when (e.code()) {
            401, 403 -> "Sign in again to load this"
            404 -> "Not available on this server yet"
            else -> "Server error (${e.code()})"
        }
        is IOException -> "No connection"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Couldn't load"
    }
}
