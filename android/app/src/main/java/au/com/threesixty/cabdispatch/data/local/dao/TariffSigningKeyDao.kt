package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.threesixty.cabdispatch.data.local.entity.TariffSigningKeyEntity

@Dao
interface TariffSigningKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: TariffSigningKeyEntity)

    /** The one cached signing key row, if a successful fetch has ever landed — purely local,
     * no network. See [au.com.threesixty.cabdispatch.sync.TariffSigningKeyCache.getCachedPublicKey]. */
    @Query("SELECT * FROM tariff_signing_keys WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TariffSigningKeyEntity?
}
