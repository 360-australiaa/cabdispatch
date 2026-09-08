package au.com.threesixty.cabdispatch.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.remote.ShiftDto
import au.com.threesixty.cabdispatch.data.remote.ShiftStartDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncResponseDto
import java.util.concurrent.TimeUnit

/**
 * WorkManager glue only — all real drain logic lives in [OutboxDrainer]
 * (kept separate so the logic is unit-testable without an Android runtime).
 *
 * Two independent triggers keep the outbox draining promptly without
 * aggressive polling:
 * 1. [au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger] (registered
 *    from [AppContainer]) enqueues [enqueueOneTime] the instant a
 *    `ConnectivityManager.NetworkCallback.onAvailable` fires.
 * 2. [enqueuePeriodic] runs every ~15 min as a backstop — covers the case
 *    where the reconnect callback was missed (e.g. process was dead when
 *    connectivity returned) or the network was already up when a row became
 *    ready.
 *
 * Both request types set `NetworkType.CONNECTED` as a WorkManager constraint,
 * so the worker won't even be started by the OS while offline — but
 * [OutboxDrainer] itself also fails safe (see its class doc) if a request
 * still errors mid-flight (e.g. connectivity present but the API host
 * unreachable).
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // T2: the toll registry rides the same 15-minute periodic wakeup the outbox drain already
        // has. A price revision or a newly-opened road otherwise reaches this device only on the
        // next process restart, which on a kiosked tablet left running for a whole shift may be
        // days. Deliberately not allowed to fail the work: a registry refresh that errors must
        // never turn an otherwise-successful outbox drain into a retry.
        runCatching { AppContainer.tollRegistryCache.refresh() }
        // The airport-fee zones ride the same wakeup for the same reasons, and are equally not
        // allowed to fail the drain — see AirportZoneCache's own doc.
        runCatching { AppContainer.airportZoneCache.refresh() }
        // S5: the tariff rides the same wakeup for the same reasons — it already runs on a
        // schedule, already requires connectivity, and until this pass nothing outside the
        // dashboard's own composition ever refreshed the tariff at all. Also best-effort: neither
        // this nor the registry refresh above may affect whether the outbox drains.
        TariffRefresh.refreshBestEffort()

        val result = OutboxDrainer(appContainerPorts()).drainOnce()
        // WorkManager's own retry uses the BackoffCriteria configured below —
        // exponential backoff, so a flaky connection doesn't hammer the API.
        // Note `hadFailure` deliberately excludes dead-lettered rows (see its doc): retrying the
        // worker for a row that is terminal by definition is what finding S1 was about.
        return if (result.hadFailure) Result.retry() else Result.success()
    }

    private fun appContainerPorts(): OutboxDrainer.Ports = object : OutboxDrainer.Ports {
        override suspend fun fetchReadyBatch(limit: Int, now: Long, maxAttempts: Int): List<SyncOutboxEntity> =
            AppContainer.syncOutboxDao.getReadyBatch(limit, now, maxAttempts)

        override suspend fun sendTrips(items: List<TripSyncItemDto>): TripSyncResponseDto =
            AppContainer.apiService.syncTrips(items)

        override suspend fun sendShiftStart(payload: ShiftStartDto): ShiftDto =
            AppContainer.apiService.startShift(payload)

        /**
         * Repoints the local trip rows and any queued trip payloads at the shift id the server
         * actually issued (finding S3).
         *
         * The outbox half is a string substitution inside the stored JSON rather than a
         * decode/patch/re-encode cycle. That is safe here specifically because a local shift id is
         * a `local:<uuid>` token — 42 characters of prefix plus a random UUID, which cannot collide
         * with anything else in a trip payload — and it keeps the rewrite a single atomic UPDATE
         * rather than a read-modify-write race against a driver closing another trip.
         */
        override suspend fun rewriteShiftId(localShiftId: String, serverShiftId: String) {
            AppContainer.tripDao.rewriteShiftId(localShiftId, serverShiftId)
            AppContainer.syncOutboxDao.rewriteShiftIdInPayloads(localShiftId, serverShiftId)
        }

        override suspend fun markTripSynced(clientUuid: String, serverId: String) {
            AppContainer.tripDao.markSynced(clientUuid, serverId)
        }

        override suspend fun deleteOutboxRow(id: Long) = AppContainer.syncOutboxDao.deleteById(id)

        override suspend fun recordFailure(id: Long, error: String, nextAttemptAt: Long) =
            AppContainer.syncOutboxDao.recordFailure(id, error, nextAttemptAt)

        override suspend fun markDeadLettered(id: Long, error: String) =
            AppContainer.syncOutboxDao.markDeadLettered(id, error)
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "cabdispatch_sync_periodic"
        const val UNIQUE_ONE_TIME_NAME = "cabdispatch_sync_reconnect"

        fun enqueuePeriodic(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            // KEEP: only ever want one periodic backstop registered; re-calling
            // AppContainer.init() (e.g. across process restarts) must not
            // reset its schedule/backoff state.
            workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Called from [au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger] on reconnect. */
        fun enqueueOneTime(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            // REPLACE: if connectivity flaps rapidly we want the freshest
            // queued attempt, not a growing pile of redundant one-time work.
            workManager.enqueueUniqueWork(UNIQUE_ONE_TIME_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private fun networkConstraints(): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
