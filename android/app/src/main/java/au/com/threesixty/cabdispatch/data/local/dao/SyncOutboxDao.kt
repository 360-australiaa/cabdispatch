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

    /**
     * The rows a drain may attempt right now.
     *
     * Three conditions beyond `readyToSync`, all added by the S1/S2 pass — see
     * [SyncOutboxEntity.nextAttemptAt]/[SyncOutboxEntity.deadLettered] for the bugs each closes:
     * a dead-lettered row is never retried automatically, a row inside its backoff window waits,
     * and a row at the attempts cap is excluded even if something failed to dead-letter it.
     *
     * `SHIFT` rows sort ahead of `TRIP` rows within the batch (finding S3): a trip closed under an
     * unsynced shift carries that shift's *client* uuid, and the server can only accept it once the
     * shift exists and the drainer knows the real shift id to rewrite it to. Ordering here is a
     * cheap guarantee that a single batch cannot contain the trip without its shift; [OutboxDrainer]
     * still sequences the two phases explicitly rather than relying on it alone.
     */
    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE readyToSync = 1
          AND deadLettered = 0
          AND attempts < :maxAttempts
          AND nextAttemptAt <= :now
        ORDER BY (CASE entityType WHEN 'shift' THEN 0 ELSE 1 END) ASC, createdAt ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun getReadyBatch(limit: Int, now: Long, maxAttempts: Int): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE entityType = :entityType AND clientUuid = :clientUuid LIMIT 1")
    abstract suspend fun getByClientUuid(entityType: String, clientUuid: String): SyncOutboxEntity?

    /** Drain = delete. See [SyncOutboxEntity] doc for why we don't mark-and-keep. */
    @Query("DELETE FROM sync_outbox WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    /**
     * Records one failed attempt and pushes the row's next eligibility out — see [SyncBackoff].
     *
     * The caller supplies [nextAttemptAt] rather than this computing it, so the schedule stays in
     * one pure, directly-testable place instead of half in SQL.
     */
    @Query(
        """
        UPDATE sync_outbox
        SET attempts = attempts + 1, lastError = :error, nextAttemptAt = :nextAttemptAt
        WHERE id = :id
        """,
    )
    abstract suspend fun recordFailure(id: Long, error: String, nextAttemptAt: Long)

    /**
     * Rewrites a not-yet-synced shift id inside queued **trip** payloads (finding S3).
     *
     * A trip closed under a shift that started offline carries that shift's `local:<uuid>`
     * placeholder in its serialised `TripSyncItemDto`. Once the shift drains and the server issues
     * a real id, those payloads must be repointed before they are sent, or the server is asked to
     * attach a trip to a shift that does not exist.
     *
     * A substring `REPLACE` on the stored JSON rather than a decode/patch/re-encode: the
     * placeholder is a fixed prefix plus a random UUID, so it cannot collide with any other value
     * in the payload, and this stays one atomic statement instead of a read-modify-write that could
     * race a driver closing another trip mid-drain.
     */
    @Query(
        """
        UPDATE sync_outbox
        SET entityJson = REPLACE(entityJson, :localShiftId, :serverShiftId)
        WHERE entityType = 'trip' AND entityJson LIKE '%' || :localShiftId || '%'
        """,
    )
    abstract suspend fun rewriteShiftIdInPayloads(localShiftId: String, serverShiftId: String)

    /** Moves a row to the terminal dead-letter state — see [SyncOutboxEntity.deadLettered]. */
    @Query("UPDATE sync_outbox SET deadLettered = 1, lastError = :error WHERE id = :id")
    abstract suspend fun markDeadLettered(id: Long, error: String)

    /**
     * Undoes the dead-letter state and clears the backoff, so the row is eligible on the next
     * drain. Backs the "retry" action `OfflineSyncScreen` offers on an exhausted row — the driver
     * (or a depot tech who has just fixed whatever the server was rejecting) gets a way to ask for
     * one more go without reinstalling the app.
     */
    @Query("UPDATE sync_outbox SET deadLettered = 0, attempts = 0, nextAttemptAt = 0 WHERE id = :id")
    abstract suspend fun resetForRetry(id: Long)

    /** Every dead-lettered row, for `OfflineSyncScreen`'s exhausted-rows section. */
    @Query("SELECT * FROM sync_outbox WHERE deadLettered = 1 ORDER BY createdAt ASC")
    abstract fun observeDeadLettered(): Flow<List<SyncOutboxEntity>>

    /**
     * Drives the "N trips pending sync" indicator.
     *
     * Excludes dead-lettered rows as of the S2 pass: they are reported separately, as failures the
     * driver must act on, and counting them here was the dishonest half of that bug — the app
     * showed work as "pending" that it had already decided would never be attempted again.
     */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE deadLettered = 0")
    abstract fun observeOutboxSize(): Flow<Int>
}
