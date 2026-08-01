package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncResponseDto
import kotlinx.serialization.decodeFromString

/**
 * Pure drain logic, factored out of [SyncWorker] so it is unit-testable on
 * a plain JVM — this sandbox has no Android SDK/emulator to run
 * instrumented tests against the real WorkManager/Room stack. [Ports] is the
 * seam: [SyncWorker] wires it to the real AppContainer DAOs/ApiService;
 * `OutboxDrainerTest` wires it to in-memory fakes to prove the idempotent
 * open->tick->close->kill->reconnect->drain->re-drain flow (see that file).
 */
class OutboxDrainer(private val ports: Ports) {

    /**
     * Drains up to [batchSize] ready rows in one call.
     *
     * Failure handling never drops data: a thrown exception (network down,
     * timeout, 5xx) bumps `attempts`/`lastError` on every row in the batch
     * and leaves them in the outbox for the next trigger (reconnect callback
     * or the periodic backstop) to retry — see [SyncOutboxEntity] doc for why
     * rows are deleted only on confirmed success, never on failure.
     *
     * Malformed `entity_json` (should never happen — it's written by our own
     * [au.com.threesixty.cabdispatch.data.repository.TripRepository]) is
     * counted separately and simply excluded from the batch sent to the
     * server, rather than infinitely retried: no amount of retrying fixes a
     * decode failure, and it must not block the rest of a healthy batch.
     */
    suspend fun drainOnce(batchSize: Int = DEFAULT_BATCH_SIZE): DrainResult {
        val batch = ports.fetchReadyBatch(batchSize)
        if (batch.isEmpty()) return DrainResult(sent = 0, failed = 0, skippedMalformed = 0)

        val tripRows = batch.filter { it.entityType == OutboxEntityType.TRIP }

        var skippedMalformed = 0
        val decoded = mutableListOf<Pair<SyncOutboxEntity, TripSyncItemDto>>()
        for (row in tripRows) {
            val item = runCatching {
                cabDispatchJson.decodeFromString<TripSyncItemDto>(row.entityJson)
            }.getOrNull()
            if (item == null) skippedMalformed++ else decoded += row to item
        }

        if (decoded.isEmpty()) return DrainResult(sent = 0, failed = 0, skippedMalformed = skippedMalformed)

        val response = try {
            ports.sendTrips(decoded.map { it.second })
        } catch (e: Exception) {
            for ((row, _) in decoded) {
                ports.recordFailure(row.id, e.message ?: e::class.java.simpleName)
            }
            return DrainResult(sent = 0, failed = decoded.size, skippedMalformed = skippedMalformed)
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
                ports.recordFailure(row.id, "server returned no result for client_uuid=${item.clientUuid}")
                failed++
            }
        }
        return DrainResult(sent = sent, failed = failed, skippedMalformed = skippedMalformed)
    }

    /** Dependency seam — see class doc. */
    interface Ports {
        suspend fun fetchReadyBatch(limit: Int): List<SyncOutboxEntity>
        suspend fun sendTrips(items: List<TripSyncItemDto>): TripSyncResponseDto
        suspend fun markTripSynced(clientUuid: String, serverId: String)
        suspend fun deleteOutboxRow(id: Long)
        suspend fun recordFailure(id: Long, error: String)
    }

    data class DrainResult(val sent: Int, val failed: Int, val skippedMalformed: Int) {
        val hadFailure: Boolean get() = failed > 0
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 20
    }
}
