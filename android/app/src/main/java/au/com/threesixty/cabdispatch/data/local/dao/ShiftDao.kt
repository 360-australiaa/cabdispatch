package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import au.com.threesixty.cabdispatch.data.local.entity.ShiftEntity
import au.com.threesixty.cabdispatch.data.local.entity.ShiftStatus
import kotlinx.coroutines.flow.Flow

/**
 * CRUD + observers only in this pass — no `ShiftRepository` is wired yet
 * (see [au.com.threesixty.cabdispatch.data.local.entity.ShiftEntity] doc).
 */
@Dao
interface ShiftDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(shift: ShiftEntity)

    @Update
    suspend fun update(shift: ShiftEntity)

    @Query("SELECT * FROM shifts WHERE clientUuid = :clientUuid LIMIT 1")
    suspend fun getByClientUuid(clientUuid: String): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE status = :openStatus ORDER BY startAt DESC LIMIT 1")
    fun observeActiveShift(openStatus: String = ShiftStatus.OPEN): Flow<ShiftEntity?>

    @Query("UPDATE shifts SET status = :status, serverId = :serverId, updatedAt = :updatedAt WHERE clientUuid = :clientUuid")
    suspend fun markSynced(
        clientUuid: String,
        serverId: String,
        updatedAt: Long = System.currentTimeMillis(),
        status: String = ShiftStatus.SYNCED,
    )
}
