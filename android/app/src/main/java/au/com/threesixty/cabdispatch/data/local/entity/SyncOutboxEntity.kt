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
    val entityType: String, // "trip" (only type wired in this pass; "shift" reserved for a future sibling)
    val clientUuid: String,
    val entityJson: String,
    val readyToSync: Boolean,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

object OutboxEntityType {
    const val TRIP = "trip"
    const val SHIFT = "shift"
}
