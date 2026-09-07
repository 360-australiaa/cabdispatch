package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import au.com.threesixty.cabdispatch.data.local.entity.TollGantryEntity
import au.com.threesixty.cabdispatch.data.local.entity.TollRoadEntity

@Dao
interface TollRegistryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoads(roads: List<TollRoadEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGantries(gantries: List<TollGantryEntity>)

    @Query("DELETE FROM toll_roads")
    suspend fun clearRoads()

    @Query("DELETE FROM toll_gantries")
    suspend fun clearGantries()

    /** Pure local read — no network, ever. Used by
     * [au.com.threesixty.cabdispatch.sync.TollRegistryCache.snapshot] to build the immutable
     * in-memory registry [au.com.threesixty.cabdispatch.domain.fare.onFix] detects against. */
    @Query("SELECT * FROM toll_roads")
    suspend fun getAllRoads(): List<TollRoadEntity>

    @Query("SELECT * FROM toll_gantries")
    suspend fun getAllGantries(): List<TollGantryEntity>

    /**
     * Atomically replaces the ENTIRE cached registry with a freshly fetched one — see
     * [au.com.threesixty.cabdispatch.sync.TollRegistryCache.refresh]'s doc for why this is called
     * only once, after every road/gantry has been fetched successfully (never incrementally): a
     * refresh that fails partway through must leave the previously cached (fully consistent)
     * registry untouched, not a half-updated mix of old and new rows. Gantries cleared/inserted
     * around roads (not the other way around) so the `toll_gantries.tollRoadId` foreign key is
     * never briefly left dangling mid-transaction.
     */
    @Transaction
    suspend fun replaceAll(roads: List<TollRoadEntity>, gantries: List<TollGantryEntity>) {
        clearGantries()
        clearRoads()
        upsertRoads(roads)
        upsertGantries(gantries)
    }
}
