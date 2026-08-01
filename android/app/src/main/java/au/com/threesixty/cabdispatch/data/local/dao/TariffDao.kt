package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.threesixty.cabdispatch.data.local.entity.TariffEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TariffDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tariff: TariffEntity)

    /**
     * The tariff whose validity window covers [atIso], for [region] — read by
     * [au.com.threesixty.cabdispatch.sync.TariffCache], purely local, no
     * network. `effective_to IS NULL` means "still current".
     */
    @Query(
        """
        SELECT * FROM tariffs
        WHERE region = :region
          AND effectiveFrom <= :atIso
          AND (effectiveTo IS NULL OR effectiveTo >= :atIso)
        ORDER BY effectiveFrom DESC
        LIMIT 1
        """,
    )
    suspend fun getActiveForRegion(region: String, atIso: String): TariffEntity?

    @Query(
        """
        SELECT * FROM tariffs
        WHERE region = :region
          AND effectiveFrom <= :atIso
          AND (effectiveTo IS NULL OR effectiveTo >= :atIso)
        ORDER BY effectiveFrom DESC
        LIMIT 1
        """,
    )
    fun observeActiveForRegion(region: String, atIso: String): Flow<TariffEntity?>

    @Query("SELECT * FROM tariffs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TariffEntity?
}
