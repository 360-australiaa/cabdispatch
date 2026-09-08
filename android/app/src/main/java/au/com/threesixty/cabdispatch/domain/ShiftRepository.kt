package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.ShiftConflictDetail
import au.com.threesixty.cabdispatch.data.remote.ShiftDto
import au.com.threesixty.cabdispatch.data.remote.ShiftStartDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * The vehicle a shift is starting on already has another driver's shift open under a different
 * driver (`POST /v1/shifts/start` 409 — see [ShiftConflictDetail]'s own doc for the wire shape).
 * [conflict] carries everything the UI needs to offer a real shift-changeover: who is on it now,
 * and since when. Retrying the identical start with `force_handover = true`
 * ([ShiftRepository.startShift]) ends their shift and opens the new one.
 */
class ShiftHandoverConflictException(val conflict: ShiftConflictDetail) : Exception(conflict.message)

/** Opens a shift, per spec B5 S1 ("pre-shift inspection checklist ... ends
 * in a call to open a shift"). */
interface ShiftRepository {
    suspend fun startShift(
        driverId: String,
        vehicleId: String,
        inspection: Map<String, String>,
        deviceAndroidId: String? = null,
        /** See [ShiftHandoverConflictException]'s doc. Defaulted false so every existing call
         * site — none of which know about handover — is unaffected. */
        forceHandover: Boolean = false,
    ): Result<ShiftDto>

    /** Plain re-read of one shift by id — added for the Plot Zone screen's "currently plotted
     * in" indicator ([au.com.threesixty.cabdispatch.ui.screens.zones.PlotZoneViewModel]), which
     * needs [ShiftDto.plottedZoneId] for the driver's own current shift
     * ([au.com.threesixty.cabdispatch.domain.SessionHolder.session]'s `shiftId`) on screen load. */
    suspend fun getShift(shiftId: String): Result<ShiftDto>
}

/**
 * Shift start that survives being offline (finding S3).
 *
 * ### What this replaces
 * The previous implementation, on any network failure, **fabricated** a `ShiftDto` with an id of
 * `local-<random uuid>` and returned `Result.success`. Its own doc admitted the consequence: the
 * synthetic shift was *"NOT persisted or retried and will be orphaned if the app process dies before
 * connectivity returns."* The driver was told their shift had started; the server had never heard of
 * it; and every trip closed during that shift was stamped with a `shift_id` that did not exist and
 * never would. On a regulated meter that is a whole shift of takings with no shift to reconcile
 * them against — the audit rates it a blocker, and it is the one bug in this workstream that
 * silently destroys money data rather than merely exposing it.
 *
 * ### How it works now
 * A shift start is a queued write, exactly like a trip:
 *
 * 1. A `clientUuid` is minted locally, up front, on every attempt — online or not.
 * 2. The row is written to the sync outbox (`entityType = SHIFT`) **before** the network is tried,
 *    so a process death between the attempt and its response cannot lose it.
 * 3. The network call is attempted. On success the row is deleted and the real [ShiftDto] returned.
 * 4. On a genuine connectivity failure the row stays queued and the driver gets a **locally-scoped**
 *    shift whose id is [localShiftId] — `local:<clientUuid>`, a form
 *    [au.com.threesixty.cabdispatch.sync.OutboxDrainer] recognises and rewrites to the real server
 *    id once the queued row drains.
 *
 * The returned shift is still synthetic in step 4 — that part is unavoidable, because the driver
 * has to be able to start earning — but it is now *persisted, retried, and reconciled*, which is
 * the entire difference. The `local:` prefix is deliberately a distinguishable marker rather than
 * the old opaque `local-<uuid>`: it is how the drainer knows which trips need their `shift_id`
 * rewritten, and how anything else reading a shift id can tell "not yet confirmed by the server"
 * from a real one.
 *
 * ### Which failures queue
 * Same distinction [SharedPreferencesDriverAuthRepository] draws for X1, for the same reason: an
 * [IOException] means we never reached the server, so queueing and retrying is right. A refusal the
 * server actually issued (a 4xx — the vehicle isn't yours, the driver is suspended) will be refused
 * identically on every retry, so it fails the shift start honestly instead of queueing a row that
 * would burn its five attempts and dead-letter.
 */
class OutboxBackedShiftRepository(
    private val apiService: ApiService,
    private val outbox: ShiftOutboxPort,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) : ShiftRepository {

    /** Narrow seam onto the outbox DAO, so this class is testable on a plain JVM without Room —
     * the same pattern [au.com.threesixty.cabdispatch.sync.OutboxDrainer] uses. */
    interface ShiftOutboxPort {
        suspend fun queueShiftStart(clientUuid: String, payloadJson: String, createdAt: Long)
        suspend fun deleteShiftStart(clientUuid: String)
    }

    override suspend fun getShift(shiftId: String): Result<ShiftDto> {
        // A local: id has never reached the server, so asking it about one is guaranteed to 404.
        // Fail with something the caller can read rather than passing it through as a real id.
        if (isLocalShiftId(shiftId)) {
            return Result.failure(
                IllegalStateException("Shift $shiftId has not synced to the server yet"),
            )
        }
        return runCatching { apiService.getShift(shiftId) }
    }

    override suspend fun startShift(
        driverId: String,
        vehicleId: String,
        inspection: Map<String, String>,
        deviceAndroidId: String?,
        forceHandover: Boolean,
    ): Result<ShiftDto> {
        val clientUuid = newUuid()
        val payload = ShiftStartDto(
            driverId = driverId,
            vehicleId = vehicleId,
            inspectionJson = inspection,
            deviceAndroidId = deviceAndroidId,
            clientUuid = clientUuid,
            forceHandover = forceHandover,
        )

        // Queue first, attempt second. The reverse order has a real hole in it: if the process dies
        // between a request being sent and its response arriving, a queue-after-failure scheme has
        // no record that the shift was ever started. Queueing first makes that window harmless —
        // the retry is idempotent on client_uuid, so at worst the server sees the same start twice
        // and returns the same shift.
        outbox.queueShiftStart(clientUuid, cabDispatchJson.encodeToString(payload), now())

        val result = runCatching { apiService.startShift(payload) }

        result.onSuccess { shift ->
            outbox.deleteShiftStart(clientUuid)
            return Result.success(shift)
        }

        val error = result.exceptionOrNull()!!
        if (error !is IOException) {
            // The server answered and said no. Retrying will get the same answer, so don't leave a
            // row behind to burn its attempts and dead-letter — report the refusal to the driver.
            outbox.deleteShiftStart(clientUuid)
            // A 409 handover conflict is a distinct, actionable refusal (see
            // ShiftHandoverConflictException's doc) — surface it typed so the UI can offer the
            // real fix (retry with force_handover=true) instead of a raw "HTTP 409 Conflict".
            // Anything that fails to parse (older/mismatched server, network proxy mangling the
            // body) falls back to the generic error exactly as before — never crash on it.
            return Result.failure(handoverConflictOrNull(error) ?: error)
        }

        // Offline. The row stays queued; hand back a shift the app can work under meanwhile.
        val startedAt = Instant.ofEpochMilli(now()).toString()
        return Result.success(
            ShiftDto(
                id = localShiftId(clientUuid),
                tenantId = "",
                driverId = driverId,
                vehicleId = vehicleId,
                startAt = startedAt,
                endAt = null,
                inspectionJson = inspection,
                tripsCount = 0,
                kmTotal = "0",
                cashTotal = "0",
                cardTotal = "0",
                pslOwed = "0",
                reconciled = false,
                createdAt = startedAt,
                updatedAt = startedAt,
            ),
        )
    }

    companion object {
        /** Marks a shift id the server has not issued. See the class doc. */
        const val LOCAL_SHIFT_ID_PREFIX = "local:"

        fun localShiftId(clientUuid: String) = "$LOCAL_SHIFT_ID_PREFIX$clientUuid"

        fun isLocalShiftId(shiftId: String) = shiftId.startsWith(LOCAL_SHIFT_ID_PREFIX)

        /** The `clientUuid` inside a [localShiftId], or null if this isn't one. */
        fun clientUuidOf(shiftId: String): String? =
            if (isLocalShiftId(shiftId)) shiftId.removePrefix(LOCAL_SHIFT_ID_PREFIX) else null

        /**
         * Extracts the `{"detail": {...}}` conflict body off a 409 [HttpException] — same
         * `errorBody().string()` + [cabDispatchJson] pattern
         * [au.com.threesixty.cabdispatch.domain.DevicePairingRepository.errorMessage] already uses
         * for this API's structured error shape, so both places decode the same way. Returns null
         * (never throws) for anything that isn't a 409, has no body, or doesn't parse as a
         * [ShiftConflictDetail] — the caller's fallback is the original error.
         */
        private fun handoverConflictOrNull(error: Throwable): ShiftHandoverConflictException? {
            val http = error as? HttpException ?: return null
            if (http.code() != 409) return null
            val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull() ?: return null
            val detail = runCatching {
                cabDispatchJson.decodeFromString<ShiftConflictErrorEnvelope>(body).detail
            }.getOrNull() ?: return null
            return ShiftHandoverConflictException(detail)
        }
    }
}

/** Wire shape of a 409's body from `POST /v1/shifts/start` — `{"detail": {ShiftConflictDetail}}`. */
@Serializable
private data class ShiftConflictErrorEnvelope(val detail: ShiftConflictDetail? = null)
