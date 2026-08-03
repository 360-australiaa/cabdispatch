package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Local, offline-first record of a trip. This table is the *source of truth*
 * for the running meter (B7) — every screen (S3 HIRED, S4 CLOSE_PAY) reads
 * from here, never from the network, and every write lands here synchronously
 * before anything is queued for sync.
 *
 * Keyed by [clientUuid] (generated on-device with `UUID.randomUUID()` at
 * trip-open time, see [au.com.threesixty.cabdispatch.data.repository.TripRepository.openTrip])
 * rather than the server's `id`, because the server id doesn't exist yet
 * while offline — [serverId] is filled in once `/v1/trips/sync` (or the
 * live create/close endpoints, when online) confirms it. `client_uuid` is
 * also the idempotency key `/v1/trips/sync` dedupes on, so it must never
 * change for the lifetime of a trip.
 *
 * [gpsTraceJson] stores the full accumulated
 * `List<au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto>` as a
 * JSON blob (kotlinx.serialization) rather than a child table — trips are
 * short-lived (minutes) and the trace is only ever read/written as a whole
 * unit (append on tick, upload whole on close), so a normalized table would
 * add join complexity with no query benefit.
 *
 * Also `@Serializable` (kotlinx.serialization, independent of Room's own
 * annotations) so [au.com.threesixty.cabdispatch.data.repository.TripRepository]
 * can JSON-encode a draft snapshot into
 * [au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity.entityJson]
 * while a trip is still open/ticking (before enough fields exist to build the
 * real `TripSyncItemDto` the server expects — see that class's doc).
 *
 * Money/rate fields are NOT stored here pre-computed; [deviceTotal] is the
 * single on-device fare total, supplied by the fare-engine sibling agent at
 * [au.com.threesixty.cabdispatch.data.repository.TripRepository.closeTrip]
 * time, kept as `String` per the decimal-as-string API contract (never
 * Float/Double — see ApiService.kt header comment).
 */
@Entity(tableName = "trips")
@Serializable
data class TripEntity(
    @PrimaryKey val clientUuid: String,

    /** Null until the server has accepted this trip (live close or `/sync`). */
    val serverId: String? = null,

    val vehicleId: String,
    val driverId: String,
    val shiftId: String?,
    val tariffId: String,
    val type: String, // rank_hail | booked | airport_fixed | multi_hire

    /** [TripStatus]: OPEN while HIRED, CLOSED once fare is finalized on-device, SYNCED once the server has confirmed it. */
    val status: String,

    val timeClass: String, // day | night | holiday
    val isPeak: Boolean,
    val maxi: Boolean,

    val startAt: String, // ISO-8601, set at openTrip()
    val endAt: String? = null, // ISO-8601, set at closeTrip()

    val startLat: Double,
    val startLng: Double,
    val endLat: Double? = null,
    val endLng: Double? = null,

    /** Cumulative counters, refreshed wholesale on every tick() call by the fare engine. */
    val distanceM: Int = 0,
    val movingS: Int = 0,
    val waitingS: Int = 0,

    val paymentMethod: String = "cash", // cash | card | voucher | account | split_fare
    val tolls: String = "0",
    val extras: String = "0",
    val cleaningFee: String = "0",
    val surchargePct: String? = null,
    val includePsl: Boolean = false,
    val receiptRef: String? = null,

    /**
     * Voucher code redeemed for this trip — only meaningful when [paymentMethod] == "voucher"
     * (backend `TripCreate`/`TripCloseRequest.voucher_code`, `backend/app/schemas/trips.py`). Null
     * for every other payment method.
     */
    val voucherCode: String? = null,

    /**
     * Corporate/linked account reference this trip was billed to — only meaningful when
     * [paymentMethod] == "account" (backend `account_reference`). Null for every other payment method.
     */
    val accountReference: String? = null,

    /**
     * JSON-encoded `List<au.com.threesixty.cabdispatch.data.remote.SplitPaymentEntryDto>` — only
     * meaningful when [paymentMethod] == "split_fare" (backend `split_payments: list[SplitPaymentItem]`).
     * Same raw-JSON-blob convention [gpsTraceJson] already uses below for a small list that's only ever
     * read/written wholesale (never queried per-element), not a child table. Null for every other
     * payment method.
     */
    val splitPaymentsJson: String? = null,

    /** On-device computed fare total; "0" until closeTrip(). Decimal-as-string. */
    val deviceTotal: String = "0",

    /** JSON-encoded `List<TelemetryPointDto>`, appended to on every tick(). */
    val gpsTraceJson: String = "[]",

    val createdAt: Long,
    val updatedAt: Long,
)

/** String constants used for [TripEntity.status] — Room stores them as plain TEXT. */
object TripStatus {
    const val OPEN = "open"
    const val CLOSED = "closed"
    const val SYNCED = "synced"
}
