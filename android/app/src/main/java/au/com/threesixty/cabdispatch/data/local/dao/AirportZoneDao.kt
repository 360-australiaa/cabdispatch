package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import au.com.threesixty.cabdispatch.data.local.entity.AirportZoneEntity

@Dao
interface AirportZoneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(zones: List<AirportZoneEntity>)

    @Query("DELETE FROM airport_zones")
    suspend fun clear()

    /** Pure local read — no network, ever. */
    @Query("SELECT * FROM airport_zones")
    suspend fun getAll(): List<AirportZoneEntity>

    /**
     * Atomically replaces the ENTIRE cached zone set — same "only after the whole fetch
     * succeeded, never incrementally" contract [TollRegistryDao.replaceAll] documents, so a
     * refresh that fails mid-flight leaves the previous, consistent set untouched.
     */
    @Transaction
    suspend fun replaceAll(zones: List<AirportZoneEntity>) {
        clear()
        upsertAll(zones)
    }
}
