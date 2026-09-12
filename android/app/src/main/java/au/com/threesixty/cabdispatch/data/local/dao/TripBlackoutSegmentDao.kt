package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.threesixty.cabdispatch.data.local.entity.TripBlackoutSegmentEntity

/**
 * Persistence for [TripBlackoutSegmentEntity] — see that class's own doc for what this table is
 * and why it exists (GPS-blackout audit trail, W1, 2026-09-12).
 */
@Dao
interface TripBlackoutSegmentDao {
    /** Insert-or-replace so the SAME [TripBlackoutSegmentEntity.clientUuid] can be written twice
     * (once with [TripBlackoutSegmentEntity.endedAtIso] null while the blackout is still open,
     * once more when it closes) without a second row — see that class's own "written twice" doc. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(segment: TripBlackoutSegmentEntity)

    @Query("SELECT * FROM trip_blackout_segments WHERE tripClientUuid = :tripClientUuid ORDER BY startedAtIso ASC")
    suspend fun forTrip(tripClientUuid: String): List<TripBlackoutSegmentEntity>

    /** The blackout still open (no [TripBlackoutSegmentEntity.endedAtIso]) for this trip, if any —
     * read by [au.com.threesixty.cabdispatch.domain.MeterController.restoreOpenTripIfAny] to
     * re-seed the fare engine's in-memory blackout-catch-up state after a process death mid-gap.
     * At most one row can ever match: a trip's tick loop cannot be inside two blackouts at once. */
    @Query("SELECT * FROM trip_blackout_segments WHERE tripClientUuid = :tripClientUuid AND endedAtIso IS NULL LIMIT 1")
    suspend fun openSegmentFor(tripClientUuid: String): TripBlackoutSegmentEntity?
}
