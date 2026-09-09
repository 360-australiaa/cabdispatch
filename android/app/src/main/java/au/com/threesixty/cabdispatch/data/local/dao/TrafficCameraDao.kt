package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import au.com.threesixty.cabdispatch.data.local.entity.TrafficCameraEntity

@Dao
interface TrafficCameraDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cameras: List<TrafficCameraEntity>)

    @Query("DELETE FROM traffic_cameras")
    suspend fun clear()

    /** Pure local read — no network, ever. */
    @Query("SELECT * FROM traffic_cameras")
    suspend fun getAll(): List<TrafficCameraEntity>

    /** Same "only after the whole fetch succeeded, never incrementally" contract
     * [AirportZoneDao.replaceAll]/[TollRegistryDao.replaceAll] document. */
    @Transaction
    suspend fun replaceAll(cameras: List<TrafficCameraEntity>) {
        clear()
        upsertAll(cameras)
    }
}
