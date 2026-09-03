package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Real date-range periods for both the "Trips" pane's History filter pills and the "Earnings"
 * pane's period tabs (Phase C, 2026-09-03) — one shared implementation rather than each pane
 * independently guessing at "start of this week" math. Replaces the History pane's former
 * presentational-only WEEK/MONTH/ALL pills (they used to all render the exact same on-device
 * recent-trips list as TODAY — see this project's git history) with real boundaries fed into
 * [TripDao.observeTripsInRange].
 */
enum class TripPeriod(val label: String) {
    ALL("All"),
    TODAY("Today"),
    WEEK("This Week"),
    MONTH("This Month"),
    ;

    /** Human label for [previousRangeEpochMillis]'s window — feeds the Earnings pane's real
     * "+N% vs yesterday/last week/last month" delta line. Empty for [ALL] (that delta is never
     * shown, see this enum's other doc). */
    val previousLabel: String
        get() = when (this) {
            ALL -> ""
            TODAY -> "yesterday"
            WEEK -> "last week"
            MONTH -> "last month"
        }

    /** Inclusive lower bound (epoch millis, device-local calendar) for "this [label]" — 0L for
     * [ALL] (no lower bound; [TripDao.observeTripsInRange] treats that as "since the epoch"). */
    fun startEpochMillis(zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Long =
        when (this) {
            ALL -> 0L
            TODAY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
            WEEK -> today.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli()
            MONTH -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }

    /**
     * `[start, end)` bounds for the period immediately BEFORE "this [label]" — yesterday for
     * [TODAY], the prior Monday-Sunday week for [WEEK], the prior calendar month for [MONTH].
     * `null` for [ALL] (there is no "period before all time"). Backs the Earnings pane's real
     * previous-period comparison delta — never a fabricated percentage, see
     * [au.com.threesixty.cabdispatch.ui.screens.earnings.EarningsWheelViewModel].
     */
    fun previousRangeEpochMillis(zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Pair<Long, Long>? =
        when (this) {
            ALL -> null
            TODAY -> {
                val end = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val start = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                start to end
            }
            WEEK -> {
                val thisWeekStart = today.with(DayOfWeek.MONDAY)
                val end = thisWeekStart.atStartOfDay(zone).toInstant().toEpochMilli()
                val start = thisWeekStart.minusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
                start to end
            }
            MONTH -> {
                val thisMonthStart = today.withDayOfMonth(1)
                val end = thisMonthStart.atStartOfDay(zone).toInstant().toEpochMilli()
                val start = thisMonthStart.minusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
                start to end
            }
        }
}

@Dao
interface TripDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trip: TripEntity)

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE clientUuid = :clientUuid LIMIT 1")
    suspend fun getByClientUuid(clientUuid: String): TripEntity?

    @Query("SELECT * FROM trips WHERE clientUuid = :clientUuid LIMIT 1")
    fun observeTrip(clientUuid: String): Flow<TripEntity?>

    /** The single in-progress trip, if any — drives S3 HIRED. Meter UX assumes at most one open trip at a time. */
    @Query("SELECT * FROM trips WHERE status = :openStatus ORDER BY startAt DESC LIMIT 1")
    fun observeActiveTrip(openStatus: String = TripStatus.OPEN): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE status = :status ORDER BY startAt DESC")
    fun observeTripsByStatus(status: String): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE shiftId = :shiftId ORDER BY startAt DESC")
    fun observeTripsForShift(shiftId: String): Flow<List<TripEntity>>

    /**
     * Finalized (closed or synced — anything but the single in-progress trip)
     * trips, most recent first. Backs the wheel's "Trips" content pane (spec
     * §4: "trip history rows — route, time, payment method, fare amount") and
     * the "Earnings" pane's today-aggregate, both of which need every
     * finalized trip regardless of which shift it belonged to, not just the
     * active shift ([observeTripsForShift]'s scope).
     */
    @Query("SELECT * FROM trips WHERE status != :openStatus ORDER BY startAt DESC LIMIT :limit")
    fun observeRecentTrips(openStatus: String = TripStatus.OPEN, limit: Int = 50): Flow<List<TripEntity>>

    /**
     * Finalized trips whose [TripEntity.createdAt] falls within [sinceEpochMillis] (inclusive)
     * and, when given, before [beforeEpochMillis] (exclusive) — the one real date-range query
     * behind both the "Trips" pane's History filter pills ([TripPeriod.startEpochMillis],
     * [beforeEpochMillis] left `null`) and the "Earnings" pane's period tabs + previous-period
     * delta ([TripPeriod.previousRangeEpochMillis], bounded on both ends to isolate e.g.
     * "yesterday" from "today"). Filters on [TripEntity.createdAt] — a plain epoch-millis `Long`
     * set once at [au.com.threesixty.cabdispatch.data.repository.TripRepository.openTrip] time —
     * rather than parsing [TripEntity.startAt]'s ISO-8601 string in SQL (exact, no locale/format
     * edge cases), and unlike [observeRecentTrips] carries no arbitrary row-count cap that would
     * silently under-count a busy period.
     */
    @Query(
        "SELECT * FROM trips WHERE status != :openStatus AND createdAt >= :sinceEpochMillis " +
            "AND (:beforeEpochMillis IS NULL OR createdAt < :beforeEpochMillis) ORDER BY startAt DESC",
    )
    fun observeTripsInRange(
        sinceEpochMillis: Long,
        beforeEpochMillis: Long? = null,
        openStatus: String = TripStatus.OPEN,
    ): Flow<List<TripEntity>>

    @Query("UPDATE trips SET status = :status, serverId = :serverId, updatedAt = :updatedAt WHERE clientUuid = :clientUuid")
    suspend fun markSynced(
        clientUuid: String,
        serverId: String,
        updatedAt: Long = System.currentTimeMillis(),
        status: String = TripStatus.SYNCED,
    )

    @Query("SELECT COUNT(*) FROM trips WHERE status != :syncedStatus")
    fun observeUnsyncedCount(syncedStatus: String = TripStatus.SYNCED): Flow<Int>
}
