package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import au.com.threesixty.cabdispatch.data.local.entity.TrafficHazardEntity

@Dao
interface TrafficHazardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(hazards: List<TrafficHazardEntity>)

    @Query("DELETE FROM traffic_hazards")
    suspend fun clear()

    /** Pure local read — no network, ever. */
    @Query("SELECT * FROM traffic_hazards")
    suspend fun getAll(): List<TrafficHazardEntity>

    /** Same "only after the whole fetch succeeded, never incrementally" contract
     * [AirportZoneDao.replaceAll]/[TollRegistryDao.replaceAll] document. A hazard that has ended
     * (or simply dropped out of the live feed) is never carried over: [au.com.threesixty.cabdispatch.sync.TrafficCache.refresh]
     * always calls this with the FULL current `active_only=true` answer, so anything not in that
     * answer is cleared, matching the backend's own hard-delete-on-end policy. */
    @Transaction
    suspend fun replaceAll(hazards: List<TrafficHazardEntity>) {
        clear()
        upsertAll(hazards)
    }
}
