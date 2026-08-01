package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local, offline-first record of a driver shift (S1 open / S5 close+report).
 * Same pattern as [TripEntity]: keyed by an on-device [clientUuid], written
 * to Room first, [serverId] filled in once the server confirms it.
 *
 * No `ShiftRepository`/sync wiring is built in this pass — only
 * [au.com.threesixty.cabdispatch.data.repository.TripRepository] is in scope
 * here. A sibling agent adding shift start/end UI should follow the exact
 * same shape as `TripRepository` (write-to-Room-first, then upsert a
 * [au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity] row
 * keyed by this `clientUuid`) and register it in
 * [au.com.threesixty.cabdispatch.data.AppContainer] per the pattern
 * documented there.
 */
@Entity(tableName = "shifts")
data class ShiftEntity(
    @PrimaryKey val clientUuid: String,

    /** Null until the server has accepted this shift. */
    val serverId: String? = null,

    val driverId: String,
    val vehicleId: String,

    /** [ShiftStatus]: OPEN while on-shift, CLOSED once ended on-device, SYNCED once server-confirmed. */
    val status: String,

    val startAt: String, // ISO-8601
    val endAt: String? = null, // ISO-8601

    /** JSON-encoded `Map<String, String>` vehicle inspection checklist, or null. */
    val inspectionJson: String? = null,

    val tripsCount: Int = 0,
    val kmTotal: String = "0",
    val cashTotal: String = "0",
    val cardTotal: String = "0",
    val pslOwed: String = "0",
    val reconciled: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long,
)

object ShiftStatus {
    const val OPEN = "open"
    const val CLOSED = "closed"
    const val SYNCED = "synced"
}
