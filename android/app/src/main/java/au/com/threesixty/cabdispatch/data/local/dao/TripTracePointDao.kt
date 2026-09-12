package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.threesixty.cabdispatch.data.local.entity.TripTracePointEntity

/**
 * Persistence for [TripTracePointEntity] — see that class's own doc for what this table is and
 * why it replaced the old per-tick `gpsTraceJson` rewrite (G8, W4 §3, 2026-09-12 optimisation
 * plan).
 */
@Dao
interface TripTracePointDao {

    /** Appends a batch — see [au.com.threesixty.cabdispatch.data.repository.TripRepository]'s
     * `bufferTracePoints`/`flushTraceBuffer` doc for the 5s-or-10-points batching this is called
     * from. `REPLACE` rather than `ABORT`: a retried flush after a partial failure (the process
     * died mid-batch) must be able to re-insert the same `(tripClientUuid, seq)` rows without a
     * conflict crash — the values are identical either way, since `seq` is assigned once, at
     * buffer time, not re-derived per attempt. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<TripTracePointEntity>)

    /** The full trace, in driven order — read once at
     * [au.com.threesixty.cabdispatch.data.repository.TripRepository.closeTrip] time to materialise
     * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.gpsTraceJson] for the sync
     * payload, and by [au.com.threesixty.cabdispatch.data.repository.TripRepository.observeActiveTripGpsTrace]
     * for the live route-polyline overlay while a trip is still open. */
    @Query("SELECT * FROM trip_trace_points WHERE tripClientUuid = :tripClientUuid ORDER BY seq ASC")
    suspend fun forTrip(tripClientUuid: String): List<TripTracePointEntity>

    /** The highest [TripTracePointEntity.seq] already on disk for this trip, or `null` if none —
     * how [au.com.threesixty.cabdispatch.data.repository.TripRepository] resumes numbering after a
     * process restart mid-trip rather than starting back at 0 and colliding with rows already
     * flushed before the crash. */
    @Query("SELECT MAX(seq) FROM trip_trace_points WHERE tripClientUuid = :tripClientUuid")
    suspend fun maxSeq(tripClientUuid: String): Int?

    /** Deletes every row for one trip — called once a trip's sync to the server is CONFIRMED
     * (`TripDao.markSynced`), never before: until then this table is the only place a partially-
     * flushed trace lives that [gpsTraceJson] has not yet been asked to materialise from (see
     * class doc). Also reachable implicitly via the `ON DELETE CASCADE` foreign key if a
     * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity] row is ever deleted outright,
     * but nothing in this app does that today — this explicit call is the real cleanup path. */
    @Query("DELETE FROM trip_trace_points WHERE tripClientUuid = :tripClientUuid")
    suspend fun deleteForTrip(tripClientUuid: String)

    /** Row count for one trip — used only by tests to assert the O(n) write-cost claim (W4 §3
     * acceptance: "simulate 7,200 ticks and confirm O(n) total write cost, not O(n^2)"); no
     * production call site needs a count on its own. */
    @Query("SELECT COUNT(*) FROM trip_trace_points WHERE tripClientUuid = :tripClientUuid")
    suspend fun countForTrip(tripClientUuid: String): Int
}
