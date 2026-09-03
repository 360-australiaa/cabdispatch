package au.com.threesixty.cabdispatch.ui.screens.earnings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.local.dao.TripPeriod
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.domain.format.toBigDecimalOrZero
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day's real earnings total — backs [EarningsWheelUiState.trend]'s hand-drawn chart. */
data class DailyEarnings(val date: LocalDate, val total: BigDecimal)

data class EarningsWheelUiState(
    val loading: Boolean = true,
    val period: TripPeriod = TripPeriod.TODAY,
    /** Total money earned in [period] — [TripEntity.deviceTotal] (the regulated fare, tolls and
     * extras already folded in) plus [TripEntity.tip] (deliberately kept OUT of `deviceTotal`
     * itself, see that field's doc — but real driver earnings include it). */
    val totalEarnings: BigDecimal = BigDecimal.ZERO,
    /** Same total for the period immediately before [period] (yesterday/last week/last month —
     * see [TripPeriod.previousRangeEpochMillis]) — real data, never fabricated. `null` for
     * [TripPeriod.ALL] (no "period before all time" to compare against) or while it's still
     * loading; [EarningsWheelContent] derives the % delta from this and [totalEarnings] itself
     * (kept as two plain numbers, not a pre-computed percentage, so there's only ever one place —
     * render time — that divides them; see that file's `earningsDeltaPct`). */
    val previousEarnings: BigDecimal? = null,
    val tripsCount: Int = 0,
    val distanceKm: BigDecimal = BigDecimal.ZERO,
    val avgFare: BigDecimal = BigDecimal.ZERO,
    val faresTotal: BigDecimal = BigDecimal.ZERO,
    val tollsTotal: BigDecimal = BigDecimal.ZERO,
    val tipsTotal: BigDecimal = BigDecimal.ZERO,
    val otherTotal: BigDecimal = BigDecimal.ZERO,
    /** Last 7 real calendar days' totals, oldest first — always this fixed 7-day window regardless
     * of [period] (a single-point "trend" for a TODAY selection would be meaningless), see
     * [EarningsWheelContent]'s trend chart. */
    val trend: List<DailyEarnings> = emptyList(),
)

/**
 * Wheel slot 4 — "Earnings" content pane. Same offline [TripEntity] source
 * [au.com.threesixty.cabdispatch.ui.screens.trips.TripsWheelViewModel] reads, via
 * [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeTripsInRange] — the same real
 * date-range query the History pane's filter pills now use (Phase C, 2026-09-03) — rather than the
 * former fixed "today only" aggregate over [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeRecentTrips]'s
 * capped recent-trips window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EarningsWheelViewModel : ViewModel() {

    private val tripDao = AppContainer.tripDao
    private val zone = ZoneId.systemDefault()

    private val _period = MutableStateFlow(TripPeriod.TODAY)
    private val _uiState = MutableStateFlow(EarningsWheelUiState())
    val uiState: StateFlow<EarningsWheelUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _period.flatMapLatest { period ->
                tripDao.observeTripsInRange(sinceEpochMillis = period.startEpochMillis(zone))
            }.collect { trips -> recompute(trips) }
        }
        viewModelScope.launch {
            _period.flatMapLatest { period ->
                val prev = period.previousRangeEpochMillis(zone)
                if (prev == null) {
                    flowOf(null)
                } else {
                    tripDao.observeTripsInRange(sinceEpochMillis = prev.first, beforeEpochMillis = prev.second)
                }
            }.collect { previousTrips -> recomputePrevious(previousTrips) }
        }
        viewModelScope.launch {
            val sevenDaysAgo = LocalDate.now(zone).minusDays(6)
            tripDao.observeTripsInRange(
                sinceEpochMillis = sevenDaysAgo.atStartOfDay(zone).toInstant().toEpochMilli(),
            ).collect { trips -> recomputeTrend(trips, sevenDaysAgo) }
        }
    }

    fun setPeriod(period: TripPeriod) {
        if (_period.value == period) return
        _period.value = period
        _uiState.update { it.copy(period = period, loading = true, previousEarnings = null) }
    }

    private fun recompute(trips: List<TripEntity>) {
        val tollsTotal = trips.fold(BigDecimal.ZERO) { acc, t -> acc + t.tolls.toBigDecimalOrZero() }
        val otherTotal = trips.fold(BigDecimal.ZERO) { acc, t -> acc + t.extras.toBigDecimalOrZero() + t.cleaningFee.toBigDecimalOrZero() }
        val tipsTotal = trips.fold(BigDecimal.ZERO) { acc, t -> acc + (t.tip?.toBigDecimalOrZero() ?: BigDecimal.ZERO) }
        val deviceTotalSum = trips.fold(BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() }
        // Fares = the bare metered/negotiated fare component of deviceTotal, with tolls/extras/
        // cleaning fee (already folded into deviceTotal, see TripEntity.deviceTotal's doc) taken
        // back out — so Fares + Tolls + Tips + Other sums exactly to totalEarnings below, matching
        // the natural "the breakdown adds up to the hero total" expectation.
        val faresTotal = (deviceTotalSum - tollsTotal - otherTotal).let { if (it.signum() < 0) BigDecimal.ZERO else it }
        val totalEarnings = deviceTotalSum + tipsTotal
        val distanceKm = trips.fold(BigDecimal.ZERO) { acc, t -> acc + BigDecimal(t.distanceM) }
            .divide(BigDecimal(1000), 2, RoundingMode.HALF_UP)
        val avgFare = if (trips.isNotEmpty()) totalEarnings.divide(BigDecimal(trips.size), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO

        _uiState.update {
            it.copy(
                loading = false,
                totalEarnings = totalEarnings,
                tripsCount = trips.size,
                distanceKm = distanceKm,
                avgFare = avgFare,
                faresTotal = faresTotal,
                tollsTotal = tollsTotal,
                tipsTotal = tipsTotal,
                otherTotal = otherTotal,
            )
        }
    }

    private fun recomputePrevious(previousTrips: List<TripEntity>?) {
        val previousEarnings = previousTrips?.fold(BigDecimal.ZERO) { acc, t ->
            acc + t.deviceTotal.toBigDecimalOrZero() + (t.tip?.toBigDecimalOrZero() ?: BigDecimal.ZERO)
        }
        _uiState.update { it.copy(previousEarnings = previousEarnings) }
    }

    private fun recomputeTrend(trips: List<TripEntity>, sevenDaysAgo: LocalDate) {
        val byDay = trips.groupBy { it.tripLocalDate() }
        val days = (0..6).map { offset -> sevenDaysAgo.plusDays(offset.toLong()) }
        val trend = days.map { day ->
            val total = byDay[day]?.fold(BigDecimal.ZERO) { acc, t ->
                acc + t.deviceTotal.toBigDecimalOrZero() + (t.tip?.toBigDecimalOrZero() ?: BigDecimal.ZERO)
            } ?: BigDecimal.ZERO
            DailyEarnings(day, total)
        }
        _uiState.update { it.copy(trend = trend) }
    }

    private fun TripEntity.tripLocalDate(): LocalDate = runCatching {
        Instant.parse(startAt).atZone(zone).toLocalDate()
    }.getOrDefault(Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate())
}
