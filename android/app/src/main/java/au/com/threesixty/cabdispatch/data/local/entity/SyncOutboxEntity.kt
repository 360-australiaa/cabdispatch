package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The offline write queue. Every trip (and, in future, shift) mutation goes
 * through here: Room ([au.com.threesixty.cabdispatch.data.local.entity.TripEntity])
 * is the source of truth for "what the UI shows right now"; this table is the
 * source of truth for "what still needs to reach the server".
 *
 * One row per (entityType, clientUuid) — open/tick/close all upsert the SAME
 * row (enforced by the unique index below) rather than appending a new one
 * each time, so the outbox always holds the *current* snapshot of a trip, not
 * a log of every intermediate tick.
 *
 * [readyToSync] is the one field beyond the base spec
 * (id, entity_type, entity_json, client_uuid, created_at, attempts,
 * last_error): a trip is upserted into this table at open time (so a crash
 * mid-trip is recoverable) and on every tick, but
 * `POST /v1/trips/sync` requires a non-null `end_at` — the server DTO simply
 * can't represent an in-progress trip. Rather than only writing the outbox
 * row at close (which would silently drop the "trip exists" fact if the app
 * is killed mid-fare), we keep the row updated throughout and gate what
 * [au.com.threesixty.cabdispatch.sync.SyncWorker] actually POSTs on this
 * flag — it flips true exactly once, in
 * [au.com.threesixty.cabdispatch.data.repository.TripRepository.closeTrip].
 *
 * [entityJson] is a JSON-encoded
 * [au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto] once ready
 * (built fresh on every upsert from the current [TripEntity] state) — see
 * `TripRepository.buildSyncPayload`.
 *
 * On successful sync the row is deleted (drain = remove, not mark-and-keep):
 * nothing downstream needs the queue history, and an unbounded "sent" log
 * would grow forever for no benefit — [TripEntity.status] already becomes
 * `synced` as the durable record.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["entityType", "clientUuid"], unique = true)],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [OutboxEntityType.TRIP] or [OutboxEntityType.SHIFT] — both are live as of the S3 pass. */
    val entityType: String,
    val clientUuid: String,
    val entityJson: String,
    val readyToSync: Boolean,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,

    /**
     * Epoch millis before which this row must not be retried (finding S1).
     *
     * `0` means "eligible now", which is the correct value for a freshly-queued row and is why it
     * is also the migration's default for every existing row. [SyncOutboxDao.recordFailure] sets it
     * forward on each failure per [au.com.threesixty.cabdispatch.sync.SyncBackoff].
     *
     * Without this the queue had no backoff of any kind at the row level: a row that failed
     * retried on the very next trigger, and since a reconnect fires a trigger immediately, a flaky
     * connection meant a tight retry loop against the API.
     */
    val nextAttemptAt: Long = 0,

    /**
     * Terminal state for a row that can never succeed (findings S1/S2).
     *
     * Set when the attempts cap is reached ([au.com.threesixty.cabdispatch.sync.SyncBackoff.MAX_ATTEMPTS])
     * or when `entityJson` cannot be decoded at all. A dead-lettered row is excluded from
     * [SyncOutboxDao.getReadyBatch] and from the "N pending sync" count, but is **not deleted** —
     * it is surfaced in `OfflineSyncScreen` with a retry action, because the row is the only
     * remaining record that this trip or shift ever needed to reach the server.
     *
     * This is the fix for two distinct silent-failure bugs. S1: with no cap, a permanently-rejected
     * row (a 422 the server will refuse identically forever) was retried on every trigger for the
     * life of the install, and because the batch is oldest-first and size-limited it occupied a
     * slot ahead of newer, perfectly good trips — one poisoned row could starve the whole queue.
     * S2: a row whose JSON failed to decode was counted and skipped on every single drain, forever,
     * while still inflating the pending count the driver reads — the app reported work outstanding
     * that it had silently decided never to attempt.
     */
    val deadLettered: Boolean = false,
)

object OutboxEntityType {
    const val TRIP = "trip"

    /**
     * Shift-start rows (finding S3). Reserved but unused until this pass — see
     * [au.com.threesixty.cabdispatch.domain.OutboxBackedShiftRepository] for what now writes them
     * and [au.com.threesixty.cabdispatch.sync.OutboxDrainer] for why they must drain before trips.
     */
    const val SHIFT = "shift"
}
