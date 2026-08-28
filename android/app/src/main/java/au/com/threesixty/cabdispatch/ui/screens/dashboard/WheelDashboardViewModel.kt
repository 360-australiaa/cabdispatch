package au.com.threesixty.cabdispatch.ui.screens.dashboard

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TodayStats
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

private const val STATUS_POLL_INTERVAL_MS = 4000L

/**
 * Simplified glance-zone status for the dashboard's top strip (spec §5: "GPS, 4G/network,
 * Printer, Battery — each a colored dot"). Deliberately a coarse ok/warn boolean per dot, not a
 * detail level — [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel] (S6) owns
 * the full diagnostics screen (GPS accuracy tiers, force-update status, printer pairing flow
 * etc.); this is just "is it currently fine, glance and move on".
 */
data class DashboardStatusStrip(
    val gpsOk: Boolean = false,
    val networkOk: Boolean = false,
    val printerOk: Boolean = false,
    val batteryOk: Boolean = true,
    val batteryPercent: Int? = null,
)

data class WheelDashboardUiState(
    val session: DriverSession? = null,
    val isAvailable: Boolean = false,
    val availabilityError: String? = null,
    val todayStats: TodayStats = TodayStats(),
    val tariff: TariffDto? = null,
    val status: DashboardStatusStrip = DashboardStatusStrip(),
)

/**
 * Backs the wheel dashboard (new home surface, replaces the old S2/Idle screen — see
 * `ui/screens/idle/IdleViewModel.kt`, whose available-toggle + today's-stats wiring this
 * preserves functionally). [AndroidViewModel] (not plain [androidx.lifecycle.ViewModel]) because
 * the status-strip polling needs a [Context], same reason
 * [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel] is one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelDashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _isAvailable = MutableStateFlow(false)
    private val _availabilityError = MutableStateFlow<String?>(null)
    private val _status = MutableStateFlow(DashboardStatusStrip())

    private val todayStats: StateFlow<TodayStats> = SessionHolder.session
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(TodayStats())
            } else {
                AppContainer.tripStatsRepository.observeTodayStats(session.driverId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayStats())

    /**
     * Real GPS-derived tariff region (2026-08-03, location/region-detection pass) — replaces the
     * former hardcoded `DEFAULT_REGION = "urban"` constant (same gap/constant the old
     * [au.com.threesixty.cabdispatch.ui.screens.idle.IdleViewModel] and
     * [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel] used to hardcode too —
     * see [RegionResolver]'s doc for why a simple distance-from-Sydney-CBD circle, not a real
     * polygon/geofence lookup: neither exists server- or client-side yet to call instead).
     * [distinctUntilChanged] so crossing the urban/country boundary re-subscribes
     * [AppContainer.tariffCache]'s Room read below exactly once, not on every ~1Hz location tick.
     */
    private val region: StateFlow<String> = AppContainer.speedSource.locationFix
        // Explicit lambda, not a `RegionResolver::resolve` method reference — [RegionResolver]
        // has two overloads (`LocationFix?` and `lat: Double?, lng: Double?`); spelling the
        // parameter out here removes any doubt about which one binds, in an environment where
        // nothing has ever actually been run through kotlinc to confirm overload resolution.
        .map { fix -> RegionResolver.resolve(fix) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RegionResolver.REGION_URBAN)

    private val baseState: StateFlow<WheelDashboardUiState> = combine(
        SessionHolder.session,
        _isAvailable,
        _availabilityError,
        // Real, Room-backed, signed-payload tariff cache — see IdleViewModel's doc for the
        // now-deleted in-memory stub this used to compete with. flatMapLatest on [region] so a
        // real region change (driver crosses the urban/country boundary) re-subscribes to the
        // correct cached row instead of staying pinned to whichever region was active at launch.
        region.flatMapLatest { r -> AppContainer.tariffCache.observeActiveTariff(r) },
        todayStats,
    ) { session, isAvailable, availabilityError, tariff, stats ->
        WheelDashboardUiState(
            session = session,
            isAvailable = isAvailable,
            availabilityError = availabilityError,
            todayStats = stats,
            tariff = tariff,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WheelDashboardUiState())

    val uiState: StateFlow<WheelDashboardUiState> = combine(baseState, _status) { base, status ->
        base.copy(status = status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WheelDashboardUiState())

    init {
        viewModelScope.launch {
            // Best-effort, and re-run on every real region change (not just once at launch) — so
            // if a driver genuinely crosses the urban/country boundary mid-shift, the newly
            // relevant region's tariff gets fetched/cached too, not just whichever region was
            // active when this ViewModel was created. refresh() throws on failure — the dashboard
            // already has a cached tariff to render from observeActiveTariff above in the common
            // case, so a failed refresh here just means "using whatever's already cached".
            region.collect { r ->
                runCatching { AppContainer.tariffCache.refresh(r) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _status.value = pollStatus()
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Off Duty/Available wheel-slot toggle (spec §4/§5). Unlike the old IdleViewModel's
     * TODO-stub version, this now really broadcasts to dispatch via the wheel-redesign
     * foundation's [au.com.threesixty.cabdispatch.domain.JobsRepository.setAvailability] (the
     * "write half of the offer-matching rule" per that file's doc) — optimistic update, reverted
     * with a surfaced error on failure since a stale/wrong client-side availability value is a
     * real dispatch-correctness bug, not cosmetic.
     */
    fun setAvailable(value: Boolean) {
        val previous = _isAvailable.value
        _isAvailable.value = value
        _availabilityError.value = null
        viewModelScope.launch {
            AppContainer.jobsRepository.setAvailability(value)
                .onFailure { error ->
                    _isAvailable.value = previous
                    _availabilityError.value = error.message ?: "Could not update availability — try again"
                }
        }
    }

    /**
     * Hands off to S3 (Hired/meter) exactly like the old IdleViewModel.startHire, minus that
     * version's `isAvailable` gate: per spec §6.1 the Start Meter button is available whenever
     * Off Duty/Available (i.e. whenever not already Hired), independent of the dispatch
     * availability toggle — a driver picking up a street hail doesn't need to be marked
     * "available for offers" first. Still requires a loaded [TariffDto] (can't compute a fare
     * without one) and a real [DriverSession]. Returns `false` (no-op) if either is missing so
     * the caller (the Start Meter button's tap animation) can skip navigating.
     */
    fun startMeter(negotiatedTotal: String? = null): Boolean {
        val session = SessionHolder.session.value ?: return false
        val tariff = uiState.value.tariff ?: return false
        SessionHolder.setPendingTrip(
            TripContext(
                clientUuid = UUID.randomUUID().toString(),
                tariff = tariff,
                // TODO(location sibling agent): real GPS fix at hire start — pre-existing gap,
                // not introduced by this pass (see old IdleViewModel.startHire).
                startLat = 0.0,
                startLng = 0.0,
                driverId = session.driverId,
                vehicleId = session.vehicleId,
                shiftId = session.shiftId,
                // "Set Price" entry point (2026-08-10 meter-polish pass) — see
                // TripContext.negotiatedTotal's doc. Null (the default) for every ordinary
                // metered Start Meter tap, exactly as before this pass.
                negotiatedTotal = negotiatedTotal,
            ),
        )
        return true
    }

    // --- Status strip polling ---

    private fun pollStatus(): DashboardStatusStrip {
        val context = getApplication<Application>()
        val batteryPercent = readBatteryPercent(context)
        return DashboardStatusStrip(
            gpsOk = hasFineLocationPermission(context) && isLocationEnabled(context),
            networkOk = hasActiveInternet(context),
            printerOk = AppContainer.receiptPrinterGateway.pairedDevice != null,
            batteryOk = batteryPercent == null || batteryPercent > 15,
            batteryPercent = batteryPercent,
        )
    }

    private fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return runCatching {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }.getOrDefault(false)
    }

    private fun hasActiveInternet(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = connectivityManager?.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun readBatteryPercent(context: Context): Int? {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = runCatching { batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()
        return pct?.takeIf { it in 0..100 }
    }
}
