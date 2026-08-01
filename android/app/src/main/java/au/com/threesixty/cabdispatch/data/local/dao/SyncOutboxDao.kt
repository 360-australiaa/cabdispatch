package au.com.threesixty.cabdispatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

/**
 * Abstract class (not interface) because [upsert] composes two other DAO
 * calls inside a single `@Transaction` — the standard Room pattern for
 * "insert-or-update" against a non-primary-key unique index
 * (`entityType` + `clientUuid`, see [SyncOutboxEntity]).
 */
@Dao
abstract class SyncOutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoring(row: SyncOutboxEntity): Long

    @Query(
        """
        UPDATE sync_outbox
        SET entityJson = :entityJson, readyToSync = :readyToSync
        WHERE entityType = :entityType AND clientUuid = :clientUuid
        """,
    )
    protected abstract suspend fun updateExisting(
        entityType: String,
        clientUuid: String,
        entityJson: String,
        readyToSync: Boolean,
    )

    /**
     * Insert-or-update keyed by (entityType, clientUuid) — this is what makes
     * repeated calls from open()/tick()/close() collapse onto one row instead
     * of piling up duplicates. Deliberately leaves `attempts`/`lastError`
     * untouched on update: those belong to the sync side of the lifecycle
     * ([recordFailure]), not the local-write side, so a driver ticking a
     * trip doesn't erase a prior sync-failure history for that same row.
     */
    @Transaction
    open suspend fun upsert(row: SyncOutboxEntity) {
        val insertedRowId = insertIgnoring(row)
        if (insertedRowId == -1L) {
            updateExisting(row.entityType, row.clientUuid, row.entityJson, row.readyToSync)
        }
    }

    @Query("SELECT * FROM sync_outbox WHERE readyToSync = 1 ORDER BY createdAt ASC LIMIT :limit")
    abstract suspend fun getReadyBatch(limit: Int): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE entityType = :entityType AND clientUuid = :clientUuid LIMIT 1")
    abstract suspend fun getByClientUuid(entityType: String, clientUuid: String): SyncOutboxEntity?

    /** Drain = delete. See [SyncOutboxEntity] doc for why we don't mark-and-keep. */
    @Query("DELETE FROM sync_outbox WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    abstract suspend fun recordFailure(id: Long, error: String)

    /** Drives a "N trips pending sync" indicator in the UI (e.g. S2/S6). */
    @Query("SELECT COUNT(*) FROM sync_outbox")
    abstract fun observeOutboxSize(): Flow<Int>
}
