package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.remote.ShiftDto
import au.com.threesixty.cabdispatch.data.remote.ShiftStartDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncResponseDto
import au.com.threesixty.cabdispatch.domain.OutboxBackedShiftRepository
import kotlinx.serialization.decodeFromString

/**
 * Pure drain logic, factored out of [SyncWorker] so it is unit-testable on
 * a plain JVM — this sandbox has no Android SDK/emulator to run
 * instrumented tests against the real WorkManager/Room stack. [Ports] is the
 * seam: [SyncWorker] wires it to the real AppContainer DAOs/ApiService;
 * `OutboxDrainerTest` wires it to in-memory fakes to prove the idempotent
 * open->tick->close->kill->reconnect->drain->re-drain flow (see that file).
 *
 * ### Shifts drain before trips (finding S3)
 * A shift started offline is queued as a `SHIFT` row and the trips closed under it carry a
 * *local* shift id ([OutboxBackedShiftRepository.localShiftId]) that the server has never issued.
 * So the two entity types are not interchangeable work items: a trip cannot be accepted until its
 * shift exists server-side and the real shift id is known. [drainOnce] therefore runs two ordered
 * phases — every ready shift first, then trips — and rewrites the local shift id to the real one
 * (via [Ports.rewriteShiftId]) in between. Sending a trip that still carries a `local:` shift id
 * would put a dangling foreign key in the immutable trip log, which is the same class of damage S3
 * was raised for.
 *
 * ### Failure handling (findings S1, S2)
 * Failure still never drops data, but it is no longer unbounded. A thrown exception bumps
 * `attempts` and pushes `nextAttemptAt` out per [SyncBackoff]; a row that has burned
 * [SyncBackoff.MAX_ATTEMPTS] is moved to the dead-letter state rather than retried forever. That
 * matters because the batch is oldest-first and size-limited: before the cap, one permanently
 * rejected row (a 422 the server refuses identically every time) held a slot in front of newer
 * trips indefinitely and kept [SyncWorker] returning `Result.retry()` for the life of the install.
 *
 * Malformed `entity_json` is likewise dead-lettered rather than skipped. It used to be counted and
 * silently excluded on every drain, forever, while still inflating the "N trips pending sync"
 * figure the driver reads — the app reporting outstanding work it had quietly decided never to
 * attempt. No amount of retrying fixes a decode failure, so the honest outcome is a terminal state
 * that says so on screen.
 */
class OutboxDrainer(
    private val ports: Ports,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Drains up to [batchSize] ready rows in one call: shifts first, then trips.
     *
     * See the class doc for the ordering and failure rules.
     */
    suspend fun drainOnce(batchSize: Int = DEFAULT_BATCH_SIZE): DrainResult {
        val batch = ports.fetchReadyBatch(batchSize, now(), SyncBackoff.MAX_ATTEMPTS)
        if (batch.isEmpty()) return DrainResult(sent = 0, failed = 0, deadLettered = 0)

        val shiftOutcome = drainShifts(batch.filter { it.entityType == OutboxEntityType.SHIFT })

        // If any shift synced, trip rows may have just had their shift_id rewritten underneath us,
        // so the copies in `batch` are stale. Re-read rather than sending the pre-rewrite JSON —
        // sending a trip that still carries a `local:` shift id is the exact failure this ordering
        // exists to prevent.
        val tripSource = if (shiftOutcome.sent > 0) {
            ports.fetchReadyBatch(batchSize, now(), SyncBackoff.MAX_ATTEMPTS)
        } else {
            batch
        }

        val tripOutcome = drainTrips(tripSource.filter { it.entityType == OutboxEntityType.TRIP })

        return DrainResult(
            sent = shiftOutcome.sent + tripOutcome.sent,
            failed = shiftOutcome.failed + tripOutcome.failed,
            deadLettered = shiftOutcome.deadLettered + tripOutcome.deadLettered,
        )
    }

    /**
     * Phase one: post each queued shift start and reconcile the ids it unblocks.
     *
     * One row at a time rather than a batch endpoint, because `POST /v1/shifts/start` takes a
     * single shift — and because a shift start is rare (once per driver per shift) so there is no
     * batching win to chase. Each is idempotent on `client_uuid`, so a resend after a response we
     * never saw returns the original shift instead of opening a second one.
     */
    private suspend fun drainShifts(rows: List<SyncOutboxEntity>): Tally {
        var sent = 0
        var failed = 0
        var deadLettered = 0

        for (row in rows) {
            val payload = decodeOrDeadLetter<ShiftStartDto>(row)
            if (payload == null) {
                deadLettered++
                continue
            }

            val shift: ShiftDto = try {
                ports.sendShiftStart(payload)
            } catch (e: Exception) {
                if (recordFailure(row, e.message ?: e::class.java.simpleName)) deadLettered++ else failed++
                continue
            }

            // The shift is real now. Point every trip that was closed under the placeholder at the
            // id the server actually issued, BEFORE the trip phase reads those rows.
            ports.rewriteShiftId(OutboxBackedShiftRepository.localShiftId(row.clientUuid), shift.id)
            ports.deleteOutboxRow(row.id)
            sent++
        }

        return Tally(sent, failed, deadLettered)
    }

    /** Phase two: the pre-existing batched trip sync, plus S1/S2 failure handling. */
    private suspend fun drainTrips(rows: List<SyncOutboxEntity>): Tally {
        var deadLettered = 0
        val decoded = mutableListOf<Pair<SyncOutboxEntity, TripSyncItemDto>>()
        for (row in rows) {
            val item = decodeOrDeadLetter<TripSyncItemDto>(row)
            if (item == null) deadLettered++ else decoded += row to item
        }

        if (decoded.isEmpty()) return Tally(sent = 0, failed = 0, deadLettered = deadLettered)

        val response = try {
            ports.sendTrips(decoded.map { it.second })
        } catch (e: Exception) {
            var failed = 0
            for ((row, _) in decoded) {
                if (recordFailure(row, e.message ?: e::class.java.simpleName)) deadLettered++ else failed++
            }
            return Tally(sent = 0, failed = failed, deadLettered = deadLettered)
        }

        val resultsByUuid = response.results.associateBy { it.clientUuid }
        var sent = 0
        var failed = 0
        for ((row, item) in decoded) {
            val result = resultsByUuid[item.clientUuid]
            if (result != null) {
                // result.duplicate == true means the server already had this
                // client_uuid from an earlier, partially-failed sync attempt
                // (e.g. we sent it, the response never arrived, we retried).
                // That is the expected, safe outcome of an idempotent resend
                // — not an error — so it's still handled as success below.
                ports.markTripSynced(item.clientUuid, result.trip.id)
                ports.deleteOutboxRow(row.id)
                sent++
            } else {
                val error = "server returned no result for client_uuid=${item.clientUuid}"
                if (recordFailure(row, error)) deadLettered++ else failed++
            }
        }
        return Tally(sent, failed, deadLettered)
    }

    /** Decodes a row's payload, dead-lettering it and returning null if that is impossible. */
    private suspend inline fun <reified T> decodeOrDeadLetter(row: SyncOutboxEntity): T? {
        val decoded = runCatching { cabDispatchJson.decodeFromString<T>(row.entityJson) }.getOrNull()
        if (decoded == null) {
            ports.markDeadLettered(row.id, "malformed ${row.entityType} payload: cannot decode entityJson")
        }
        return decoded
    }

    /**
     * Records one failed attempt against [row], dead-lettering it if that was its last.
     *
     * @return true if the row was dead-lettered, false if it stays queued for a later retry.
     */
    private suspend fun recordFailure(row: SyncOutboxEntity, error: String): Boolean {
        val attempts = row.attempts + 1
        return if (SyncBackoff.isExhausted(attempts)) {
            // Record the attempt before dead-lettering, so the row's `attempts` honestly reads 5
            // rather than 4 — the count is shown to the driver, and "we tried 4 times" would be a
            // lie about the one that finally gave up. `nextAttemptAt` is irrelevant now (the row is
            // excluded by `deadLettered` regardless), so it is left where the backoff would put it.
            ports.recordFailure(row.id, error, SyncBackoff.nextAttemptAt(now(), attempts))
            ports.markDeadLettered(row.id, "gave up after $attempts attempts; last error: $error")
            true
        } else {
            ports.recordFailure(row.id, error, SyncBackoff.nextAttemptAt(now(), attempts))
            false
        }
    }

    private data class Tally(val sent: Int, val failed: Int, val deadLettered: Int)

    /** Dependency seam — see class doc. */
    interface Ports {
        suspend fun fetchReadyBatch(limit: Int, now: Long, maxAttempts: Int): List<SyncOutboxEntity>
        suspend fun sendTrips(items: List<TripSyncItemDto>): TripSyncResponseDto

        /** `POST /v1/shifts/start`, idempotent on [ShiftStartDto.clientUuid]. */
        suspend fun sendShiftStart(payload: ShiftStartDto): ShiftDto

        /**
         * Repoints everything that referenced a not-yet-synced shift at the real server id —
         * both the local trip rows and any queued outbox payloads that embed it. See the class
         * doc's ordering note.
         */
        suspend fun rewriteShiftId(localShiftId: String, serverShiftId: String)

        suspend fun markTripSynced(clientUuid: String, serverId: String)
        suspend fun deleteOutboxRow(id: Long)
        suspend fun recordFailure(id: Long, error: String, nextAttemptAt: Long)
        suspend fun markDeadLettered(id: Long, error: String)
    }

    data class DrainResult(val sent: Int, val failed: Int, val deadLettered: Int) {
        /**
         * Whether [SyncWorker] should ask WorkManager to retry.
         *
         * Deliberately excludes [deadLettered]: those rows are terminal and will not be picked up
         * by another attempt, so retrying on their account would spin the worker forever — which is
         * the S1 bug wearing a different hat. Only rows still eligible for a retry justify one.
         */
        val hadFailure: Boolean get() = failed > 0
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 20
    }
}
