package au.com.threesixty.cabdispatch.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.math.BigDecimal

data class TodayStats(
    val tripsCount: Int = 0,
    val kmTotal: BigDecimal = BigDecimal.ZERO,
    val earningsTotal: BigDecimal = BigDecimal.ZERO,
)

/**
 * Backs S2's "today's stats (trip count, km, earnings pulled from local Room
 * aggregates)". Real implementation belongs to the offline sync-engine
 * sibling agent once TripEntity/TripDao exist — a SUM/COUNT aggregate query
 * over today's closed trips for this driver.
 */
interface TripStatsRepository {
    fun observeTodayStats(driverId: String): Flow<TodayStats>
}

/**
 * TODO(sync-engine sibling agent): replace with a real Room DAO aggregate
 * query once TripEntity/TripDao land — see
 * [au.com.threesixty.cabdispatch.data.local.AppDatabase]'s doc comment for
 * the registration steps. Stub returns a fixed zeroed flow so S2 renders
 * without crashing before that lands.
 */
class StubTripStatsRepository : TripStatsRepository {
    override fun observeTodayStats(driverId: String): Flow<TodayStats> = flowOf(TodayStats())
}
