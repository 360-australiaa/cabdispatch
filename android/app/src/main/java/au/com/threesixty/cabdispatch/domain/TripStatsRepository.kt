package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 *
 * No longer the default binding — see [RemoteTripStatsRepository] below and
 * [AppContainer.tripStatsRepository]'s own doc. Kept (not deleted) as an explicit zeroed
 * fallback for tests/previews that don't want a real network call.
 */
class StubTripStatsRepository : TripStatsRepository {
    override fun observeTodayStats(driverId: String): Flow<TodayStats> = flowOf(TodayStats())
}

/**
 * Real implementation (2026-09-02, Home-dashboard redesign pass) — fixes a genuine stub-data bug:
 * [AppContainer.tripStatsRepository] was wired to [StubTripStatsRepository] by default, so the
 * dashboard's "TRIPS — N Completed" and "EARNINGS — $N Today" tiles always rendered `0`/`$0`
 * regardless of the driver's real day, for every driver, always (see `DASHBOARD_REDESIGN_2026.md`
 * for the fix writeup).
 *
 * Backed by `GET /v1/trips/earnings/today` ([au.com.threesixty.cabdispatch.data.remote.ApiService.earningsToday]),
 * which already carries `trips_completed_today` and `today_total` — the exact two numbers these
 * tiles need — rather than a new Room DAO aggregate query: this is the smaller, already-existing
 * contract ([au.com.threesixty.cabdispatch.ui.screens.dashboard.HomeExtras] already calls this
 * same endpoint for its separate `pctChange` annotation, so this repository and that screen-local
 * fetch now simply read the same real backend day, from two independent calls — both eventually
 * consistent, no shared cache to keep in sync, matching this codebase's existing "screen-local
 * loader on top of a shared repository" convention rather than a bigger consolidation).
 *
 * [kmTotal][TodayStats.kmTotal] stays at whatever was last computed (zero on first load) — the
 * backend contract here has no km-total field, and no other real source exists to back it
 * (see `DASHBOARD_REDESIGN_2026.md`'s backend-requirements notes) — never fabricated, just not
 * threaded through this particular endpoint.
 *
 * Polls on a plain timer rather than emitting once — a shift's real earnings/trip count grows
 * through the day, and this backs a screen a driver is expected to glance at repeatedly, not open
 * once. A failed poll keeps whatever was last successfully loaded (network blip, brief backend
 * hiccup) rather than resetting to zero — the same "degrade to stale-but-honest, never fabricate"
 * posture every other best-effort read in this app already uses.
 */
class RemoteTripStatsRepository : TripStatsRepository {
    override fun observeTodayStats(driverId: String): Flow<TodayStats> = flow {
        var last = TodayStats()
        emit(last)
        while (true) {
            runCatching { AppContainer.apiService.earningsToday(driverId) }
                .onSuccess { dto ->
                    last = last.copy(
                        tripsCount = dto.tripsCompletedToday,
                        earningsTotal = dto.todayTotal.toBigDecimalOrNull() ?: last.earningsTotal,
                    )
                    emit(last)
                }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
    }
}
