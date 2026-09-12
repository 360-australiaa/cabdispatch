package au.com.threesixty.cabdispatch.data.repository

import au.com.threesixty.cabdispatch.data.local.dao.TripTracePointDao
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripBlackoutSegmentEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripTracePointEntity
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * [TripRepository]'s append-only trace storage (G8, architecture audit 2026-09-08 §2.1 / the
 * 2026-09-12 optimisation plan's W4 §3) — the O(n) write-cost claim, the batching thresholds, and
 * `observeActiveTripGpsTrace`'s correctness. See [TripRepositoryOfflineTest] for the general
 * offline-lifecycle coverage this file does not repeat.
 *
 * Fakes rather than Room — same reasoning [TripRepositoryOfflineTest]'s own doc gives: this is
 * plain-Kotlin read-modify-write logic, not Room's SQL, and a reflection-proxy [ApiService] proves
 * this path never reaches the network either way.
 */
class TripRepositoryTraceStorageTest {

    private class FakeTripDao : au.com.threesixty.cabdispatch.data.local.dao.TripDao {
        val rows = linkedMapOf<String, TripEntity>()
        private val changes = MutableStateFlow(0)

        override suspend fun insert(trip: TripEntity) {
            rows[trip.clientUuid] = trip
            changes.value++
        }

        override suspend fun rewriteShiftId(localShiftId: String, serverShiftId: String) {
            // Not exercised by these tests.
        }
        override suspend fun update(trip: TripEntity) {
            rows[trip.clientUuid] = trip
            changes.value++
        }

        override suspend fun getByClientUuid(clientUuid: String): TripEntity? = rows[clientUuid]

        override fun observeTrip(clientUuid: String): Flow<TripEntity?> = changes.map { rows[clientUuid] }

        override fun observeActiveTrip(openStatus: String): Flow<TripEntity?> =
            changes.map { rows.values.filter { it.status == openStatus }.maxByOrNull { it.startAt } }

        override fun observeTripsByStatus(status: String): Flow<List<TripEntity>> =
            changes.map { rows.values.filter { it.status == status } }

        override fun observeTripsForShift(shiftId: String): Flow<List<TripEntity>> =
            changes.map { rows.values.filter { it.shiftId == shiftId } }

        override fun observeRecentTrips(openStatus: String, limit: Int): Flow<List<TripEntity>> =
            changes.map { rows.values.filter { it.status != openStatus }.take(limit) }

        override fun observeTripsInRange(
            sinceEpochMillis: Long,
            beforeEpochMillis: Long?,
            openStatus: String,
        ): Flow<List<TripEntity>> = changes.map { emptyList() }

        override suspend fun markSynced(clientUuid: String, serverId: String, updatedAt: Long, status: String) {
            rows[clientUuid]?.let {
                rows[clientUuid] = it.copy(serverId = serverId, status = status, updatedAt = updatedAt)
            }
            changes.value++
        }

        override fun observeUnsyncedCount(syncedStatus: String): Flow<Int> =
            changes.map { rows.values.count { it.status != syncedStatus } }
    }

    /** Counts `insertAll` CALLS separately from total rows written, so a test can assert the
     * number of Room write operations is O(n / batch size), not O(n). */
    private class CountingTracePointDao : TripTracePointDao {
        val rows = mutableListOf<TripTracePointEntity>()
        var insertAllCallCount = 0
            private set
        var totalPointsWritten = 0
            private set

        override suspend fun insertAll(points: List<TripTracePointEntity>) {
            insertAllCallCount++
            totalPointsWritten += points.size
            rows.addAll(points)
        }

        override suspend fun forTrip(tripClientUuid: String): List<TripTracePointEntity> =
            rows.filter { it.tripClientUuid == tripClientUuid }.sortedBy { it.seq }

        override suspend fun maxSeq(tripClientUuid: String): Int? =
            rows.filter { it.tripClientUuid == tripClientUuid }.maxOfOrNull { it.seq }

        override suspend fun deleteForTrip(tripClientUuid: String) {
            rows.removeAll { it.tripClientUuid == tripClientUuid }
        }

        override suspend fun countForTrip(tripClientUuid: String): Int =
            rows.count { it.tripClientUuid == tripClientUuid }
    }

    private class FakeBlackoutSegmentDao : au.com.threesixty.cabdispatch.data.local.dao.TripBlackoutSegmentDao {
        override suspend fun upsert(segment: TripBlackoutSegmentEntity) {
            // Not exercised by these tests.
        }
        override suspend fun forTrip(tripClientUuid: String): List<TripBlackoutSegmentEntity> = emptyList()
        override suspend fun openSegmentFor(tripClientUuid: String): TripBlackoutSegmentEntity? = null
    }

    private class FakeOutboxDao : au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao() {
        override suspend fun insertIgnoring(row: SyncOutboxEntity): Long = 1L
        override suspend fun updateExisting(
            entityType: String,
            clientUuid: String,
            entityJson: String,
            readyToSync: Boolean,
        ) {
            // Not exercised by these tests.
        }
        override suspend fun getReadyBatch(
            limit: Int,
            now: Long,
            maxAttempts: Int,
        ): List<SyncOutboxEntity> = emptyList()
        override suspend fun getByClientUuid(entityType: String, clientUuid: String): SyncOutboxEntity? = null
        override suspend fun deleteById(id: Long) {
            // Not exercised by these tests.
        }
        override suspend fun recordFailure(id: Long, error: String, nextAttemptAt: Long) {
            // Not exercised by these tests.
        }
        override suspend fun markDeadLettered(id: Long, error: String) {
            // Not exercised by these tests.
        }
        override suspend fun rewriteShiftIdInPayloads(localShiftId: String, serverShiftId: String) {
            // Not exercised by these tests.
        }
        override suspend fun resetForRetry(id: Long) {
            // Not exercised by these tests.
        }
        override fun observeDeadLettered(): Flow<List<SyncOutboxEntity>> = MutableStateFlow(emptyList())
        override fun observeOutboxSize(): Flow<Int> = MutableStateFlow(0)
    }

    private fun neverCalledApi(): ApiService = Proxy.newProxyInstance(
        ApiService::class.java.classLoader,
        arrayOf(ApiService::class.java),
    ) { _, method, _ ->
        throw AssertionError("the offline trip path must not call the network, but called ApiService.${method.name}")
    } as ApiService

    private suspend fun openATrip(repo: TripRepository, clientUuid: String = "trip-1") {
        repo.openTrip(
            clientUuid = clientUuid,
            vehicleId = "veh-1",
            driverId = "drv-1",
            shiftId = "shift-1",
            tariffId = "tariff-urban-2026",
            type = "rank_hail",
            startLat = -33.87,
            startLng = 151.21,
        )
    }

    @Test
    fun `7,200 one-point ticks write O(n) total trace rows, batched every 10 points`() = runTest {
        // The acceptance test named directly in the plan: "simulate 7,200 ticks and confirm O(n)
        // total write cost, not O(n^2)". The OLD implementation decoded+re-encoded the ENTIRE
        // accumulated trace on every tick -- point k's tick did O(k) work, so the total across
        // 7,200 ticks was O(n^2) (~25.9 million point-operations). This asserts the NEW
        // implementation does exactly n point-writes in total, spread across n/10 batched Room
        // calls, not n on every single tick.
        val tripDao = FakeTripDao()
        val tracePointDao = CountingTracePointDao()
        val repo = TripRepository(tripDao, FakeOutboxDao(), neverCalledApi(), FakeBlackoutSegmentDao(), tracePointDao)
        openATrip(repo)

        val tickCount = 7_200
        repeat(tickCount) { i ->
            repo.tick(
                clientUuid = "trip-1",
                newPoints = listOf(TelemetryPointDto(-33.87, 151.21, 40.0, "2026-09-08T01:00:00Z")),
                distanceM = i,
                movingS = i,
                waitingS = 0,
            )
        }

        // O(n) total rows written -- exactly one row per tick, never more (never re-writing an
        // earlier point twice, which is what an accumulate-then-full-rewrite bug would do).
        assertEquals(tickCount, tracePointDao.totalPointsWritten)

        // Batched, not one Room write per tick: at most ceil(n / 10) insertAll calls (the time
        // trigger COULD fire earlier than every 10 points under real wall-clock delay, but never
        // more often than the count trigger allows, and this loop runs fast enough in a unit test
        // that the 5s time trigger should not fire at all).
        assertTrue(
            "expected roughly n/10 batched writes, got ${tracePointDao.insertAllCallCount} for $tickCount ticks",
            tracePointDao.insertAllCallCount <= (tickCount / 10) + 1,
        )
        // And meaningfully fewer than one write per tick -- the whole point of batching.
        assertTrue(tracePointDao.insertAllCallCount < tickCount / 5)

        // The trip's own fare-affecting counters (never touched by the trace-buffering change)
        // still land correctly on every tick.
        assertEquals(tickCount - 1, tripDao.rows.getValue("trip-1").distanceM)
    }

    @Test
    fun `a batch of 10 points flushes in one insertAll call, in order`() = runTest {
        val tripDao = FakeTripDao()
        val tracePointDao = CountingTracePointDao()
        val repo = TripRepository(tripDao, FakeOutboxDao(), neverCalledApi(), FakeBlackoutSegmentDao(), tracePointDao)
        openATrip(repo)

        repeat(10) { i ->
            repo.tick(
                clientUuid = "trip-1",
                newPoints = listOf(TelemetryPointDto(-33.87, 151.21 + i * 0.001, 40.0, "ts-$i")),
                distanceM = i,
                movingS = i,
                waitingS = 0,
            )
        }

        assertEquals(1, tracePointDao.insertAllCallCount)
        assertEquals(10, tracePointDao.rows.size)
        assertEquals((0..9).toList(), tracePointDao.rows.map { it.seq })
    }

    @Test
    fun `a process restart resumes seq numbering from what's already on disk, never colliding`() = runTest {
        val tripDao = FakeTripDao()
        val tracePointDao = CountingTracePointDao()

        // "process 1": ticks 10 points (one full batch), then dies.
        run {
            val repo = TripRepository(
                tripDao, FakeOutboxDao(), neverCalledApi(), FakeBlackoutSegmentDao(), tracePointDao,
            )
            openATrip(repo)
            repeat(10) { i ->
                repo.tick(
                    clientUuid = "trip-1",
                    newPoints = listOf(TelemetryPointDto(-33.87, 151.21, 40.0, "ts-$i")),
                    distanceM = i,
                    movingS = i,
                    waitingS = 0,
                )
            }
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), tracePointDao.rows.map { it.seq }.sorted())

        // "process 2": a brand new TripRepository instance (same underlying DAOs, simulating a
        // cold start) resumes ticking the SAME still-open trip.
        run {
            val repo = TripRepository(
                tripDao, FakeOutboxDao(), neverCalledApi(), FakeBlackoutSegmentDao(), tracePointDao,
            )
            repeat(10) { i ->
                repo.tick(
                    clientUuid = "trip-1",
                    newPoints = listOf(TelemetryPointDto(-33.87, 151.21, 40.0, "ts2-$i")),
                    distanceM = 10 + i,
                    movingS = 10 + i,
                    waitingS = 0,
                )
            }
        }

        val seqs = tracePointDao.rows.map { it.seq }.sorted()
        assertEquals("20 total points, no gaps, no duplicate seq values", (0..19).toList(), seqs)
    }

    @Test
    fun `observeActiveTripGpsTrace reflects flushed points while the trip is still open`() = runTest {
        val tripDao = FakeTripDao()
        val tracePointDao = CountingTracePointDao()
        val repo = TripRepository(tripDao, FakeOutboxDao(), neverCalledApi(), FakeBlackoutSegmentDao(), tracePointDao)
        openATrip(repo)

        // Exactly one full batch (10), so it is guaranteed to have flushed already.
        repeat(10) { i ->
            repo.tick(
                clientUuid = "trip-1",
                newPoints = listOf(TelemetryPointDto(-33.87, 151.21 + i * 0.001, 40.0, "ts-$i")),
                distanceM = i,
                movingS = i,
                waitingS = 0,
            )
        }

        // `first()`, not `collect` -- the underlying Flow is `changes.map { ... }` over a
        // MutableStateFlow that never completes on its own; `first()` takes the current snapshot
        // (a StateFlow always replays its latest value to a new collector) and cancels cleanly,
        // rather than hanging forever waiting for a completion that would never come.
        val trace = repo.observeActiveTripGpsTrace().first()
        assertEquals(10, trace.size)
        assertEquals(
            "order must survive the read (seq ascending)",
            (0..9).map { i -> 151.21 + i * 0.001 },
            trace.map { it.lng },
        )
    }

    @Test
    fun `closing a trip force-flushes any points still buffered short of a full batch`() = runTest {
        val tripDao = FakeTripDao()
        val tracePointDao = CountingTracePointDao()
        val repo = TripRepository(tripDao, FakeOutboxDao(), neverCalledApi(), FakeBlackoutSegmentDao(), tracePointDao)
        openATrip(repo)

        // Only 3 points -- short of the 10-point batch trigger, and not enough real wall-clock
        // time will have elapsed for the 5s time trigger either.
        repeat(3) { i ->
            repo.tick(
                clientUuid = "trip-1",
                newPoints = listOf(TelemetryPointDto(-33.87, 151.21 + i * 0.001, 40.0, "ts-$i")),
                distanceM = i,
                movingS = i,
                waitingS = 0,
            )
        }
        assertEquals("nothing flushed yet -- short of both thresholds", 0, tracePointDao.rows.size)

        repo.closeTrip(clientUuid = "trip-1", endLat = null, endLng = null, deviceTotal = "5.00")

        assertEquals("closeTrip must force-flush the partial batch", 3, tracePointDao.rows.size)
        val closed = tripDao.rows.getValue("trip-1")
        assertEquals(3, decodeTrace(closed.gpsTraceJson).size)
    }
}

private val traceDecodeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Decodes a materialised [TripEntity.gpsTraceJson] for assertion purposes — the repository's own
 * encoding ([TripRepository.closeTrip]), read back. */
private fun decodeTrace(json: String): List<TelemetryPointDto> = traceDecodeJson.decodeFromString(
    kotlinx.serialization.builtins.ListSerializer(TelemetryPointDto.serializer()),
    json,
)
