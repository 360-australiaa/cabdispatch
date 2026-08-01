package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import kotlinx.coroutines.flow.Flow

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
