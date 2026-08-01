package au.com.threesixty.cabdispatch.ui.screens.idle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TodayStats
import au.com.threesixty.cabdispatch.domain.TripContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Default region until the geofencing sibling agent wires GPS -> urban/
 * country/exempt polygon detection (spec B5 S2). */
private const val DEFAULT_REGION = "urban"

data class IdleUiState(
    val session: DriverSession? = null,
    val isAvailable: Boolean = false,
    val todayStats: TodayStats = TodayStats(),
    val tariff: TariffDto? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class IdleViewModel : ViewModel() {

    private val _isAvailable = MutableStateFlow(false)

    private val todayStats: StateFlow<TodayStats> = SessionHolder.session
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(TodayStats())
            } else {
                AppContainer.tripStatsRepository.observeTodayStats(session.driverId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayStats())

    val uiState: StateFlow<IdleUiState> = combine(
        SessionHolder.session,
        _isAvailable,
        // Real, Room-backed, signed-payload tariff cache (see
        // au.com.threesixty.cabdispatch.sync.TariffCache) — this used to read
        // a second, unrelated in-memory-only TariffCache the S1-S3 screens
        // agent stood up under domain/TariffCache.kt because the sync
        // engine's version didn't exist yet when that pass started. That stub
        // is gone; this is a pure local Room read (never blocks on network —
        // see [refresh] below for the one place that touches it), so it also
        // now survives a process restart, unlike the old stub.
        AppContainer.tariffCache.observeActiveTariff(DEFAULT_REGION),
        todayStats,
    ) { session, isAvailable, tariff, stats ->
        IdleUiState(session = session, isAvailable = isAvailable, todayStats = stats, tariff = tariff)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IdleUiState())

    init {
        viewModelScope.launch {
            // Best-effort: refresh() throws on failure (see TariffCache doc);
            // S2 already has a cached tariff to render from observeActiveTariff
            // above in the common case, so a failed refresh here just means
            // "using whatever's already cached" rather than a screen error.
            runCatching { AppContainer.tariffCache.refresh(DEFAULT_REGION) }
        }
    }

    /** TODO(dispatch sibling agent): broadcast availability to the backend
     * (device heartbeat / dispatch channel) — currently local-only. */
    fun setAvailable(value: Boolean) {
        _isAvailable.value = value
    }

    fun startHire(navigateToHired: () -> Unit) {
        val session = SessionHolder.session.value ?: return
        val tariff = uiState.value.tariff ?: return
        SessionHolder.setPendingTrip(
            TripContext(
                clientUuid = UUID.randomUUID().toString(),
                tariff = tariff,
                // TODO(location sibling agent): real GPS fix at hire start.
                startLat = 0.0,
                startLng = 0.0,
                driverId = session.driverId,
                vehicleId = session.vehicleId,
                shiftId = session.shiftId,
            ),
        )
        navigateToHired()
    }
}
