package au.com.threesixty.cabdispatch.data.repository

import au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao
import au.com.threesixty.cabdispatch.data.local.dao.TripDao
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.domain.fare.URBAN_TARIFF
import au.com.threesixty.cabdispatch.domain.fare.reconstructFareState
import au.com.threesixty.cabdispatch.domain.fare.FareEngine as PureFareEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [TripRepository] — the offline money path, which had **zero test coverage** before this
 * (architecture audit 2026-09-08, §6.2).
 *
 * This class is where the fare stops being a number on a screen and becomes the record the
 * passenger is billed from, the driver's shift is paid from, and the server audits against. Every
 * trip the app has ever taken went through it untested.
 *
 * The scenario each test below is built around is F4's: **open → tick → the process dies → restore
 * → close**. That sequence used to be unreachable — the fare engine could not outlive the screen,
 * so there was nothing to restore *into* and the question never arose. Now that the meter is
 * process-scoped and a restart genuinely resumes an OPEN trip, the durability of what the
 * repository wrote is what stands between a passenger and a fare that restarts from zero.
 *
 * Fakes rather than Room: [TripDao] is an interface and [SyncOutboxDao] an abstract class, both
 * implementable in-memory, which keeps this a plain JVM test (Room's own SQL is not what is under
 * test here — the repository's read-modify-write logic is). [ApiService] is a reflection proxy that
 * throws on any call, so these tests also prove the offline path never once reaches for the network.
 */
class TripRepositoryOfflineTest {

    // ================================================================================
    // fakes
    // ================================================================================

    private class FakeTripDao : TripDao {
        val rows = linkedMapOf<String, TripEntity>()
        private val changes = MutableStateFlow(0)

        override suspend fun insert(trip: TripEntity) {
            check(!rows.containsKey(trip.clientUuid)) { "ABORT on conflict, as Room would" }
            rows[trip.clientUuid] = trip
            changes.value++
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
        ): Flow<List<TripEntity>> = changes.map {
            rows.values.filter {
                it.status != openStatus && it.createdAt >= sinceEpochMillis &&
                    (beforeEpochMillis == null || it.createdAt < beforeEpochMillis)
            }
        }

        override suspend fun markSynced(clientUuid: String, serverId: String, updatedAt: Long, status: String) {
            rows[clientUuid]?.let { rows[clientUuid] = it.copy(serverId = serverId, status = status, updatedAt = updatedAt) }
            changes.value++
        }

        override fun observeUnsyncedCount(syncedStatus: String): Flow<Int> =
            changes.map { rows.values.count { it.status != syncedStatus } }
    }

    private class FakeOutboxDao : SyncOutboxDao() {
        val rows = mutableListOf<SyncOutboxEntity>()
        private val size = MutableStateFlow(0)

        // The real DAO's `upsert` is an open method over these two protected primitives; overriding
        // the primitives rather than `upsert` itself means the actual insert-or-update logic under
        // test is the production one, not a re-description of it.
        override suspend fun insertIgnoring(row: SyncOutboxEntity): Long {
            val clash = rows.any { it.entityType == row.entityType && it.clientUuid == row.clientUuid }
            if (clash) return -1L
            rows.add(row)
            size.value = rows.size
            return rows.size.toLong()
        }

        override suspend fun updateExisting(
            entityType: String,
            clientUuid: String,
            entityJson: String,
            readyToSync: Boolean,
        ) {
            val index = rows.indexOfFirst { it.entityType == entityType && it.clientUuid == clientUuid }
            if (index >= 0) rows[index] = rows[index].copy(entityJson = entityJson, readyToSync = readyToSync)
        }

        override suspend fun getReadyBatch(limit: Int): List<SyncOutboxEntity> =
            rows.filter { it.readyToSync }.take(limit)

        override suspend fun getByClientUuid(entityType: String, clientUuid: String): SyncOutboxEntity? =
            rows.firstOrNull { it.entityType == entityType && it.clientUuid == clientUuid }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
            size.value = rows.size
        }

        override suspend fun recordFailure(id: Long, error: String) = Unit

        override fun observeOutboxSize(): Flow<Int> = size
    }

    /** Any call at all fails the test: the offline path must never reach the network. */
    private fun neverCalledApi(): ApiService = Proxy.newProxyInstance(
        ApiService::class.java.classLoader,
        arrayOf(ApiService::class.java),
    ) { _, method, _ ->
        throw AssertionError("the offline trip path must not call the network, but called ApiService.${method.name}")
    } as ApiService

    private fun repository(tripDao: TripDao, outboxDao: SyncOutboxDao) =
        TripRepository(tripDao, outboxDao, neverCalledApi())

    private suspend fun openATrip(repo: TripRepository, clientUuid: String = "trip-1"): TripEntity =
        repo.openTrip(
            clientUuid = clientUuid,
            vehicleId = "veh-1",
            driverId = "drv-1",
            shiftId = "shift-1",
            tariffId = "tariff-urban-2026",
            type = "rank_hail",
            startLat = -33.87,
            startLng = 151.21,
            timeClass = "day",
            isPeak = false,
        )

    // ================================================================================
    // open -> tick -> kill -> restore -> close
    // ================================================================================

    @Test
    fun `a trip survives process death mid-fare and closes on the fare it had accrued`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = FakeOutboxDao()

        // --- open --------------------------------------------------------------------------
        run {
            val repo = repository(tripDao, outboxDao)
            val opened = openATrip(repo)
            assertEquals(TripStatus.OPEN, opened.status)
            assertEquals("0", opened.accruedDistanceCharge)
        }

        // --- tick: 8.4 km and 3 minutes of waiting, persisted as the meter goes -------------
        run {
            val repo = repository(tripDao, outboxDao)
            repo.tick(
                clientUuid = "trip-1",
                newPoints = listOf(TelemetryPointDto(-33.87, 151.21, 45.0, "2026-09-08T01:00:00Z")),
                distanceM = 8_412,
                movingS = 640,
                waitingS = 180,
                // F9: the charges themselves ride along, not only the counters they used to have to
                // be re-derived from.
                accruedDistanceCharge = "21.9553",
                accruedWaitingCharge = "3.39",
            )
        }

        // --- the process dies here. Nothing is in memory. ------------------------------------
        // Everything below reads only what was written to "disk" (the fake DAOs), through brand new
        // repository and DAO-facing objects, exactly as a cold start would.

        // --- restore -------------------------------------------------------------------------
        val restoredRepo = repository(tripDao, outboxDao)
        val recovered = restoredRepo.getTrip("trip-1")
        assertNotNull("the open trip must still be there after the process died", recovered)
        val openTrip = recovered!!
        assertEquals(TripStatus.OPEN, openTrip.status)
        assertEquals("the accrued distance must have survived", "21.9553", openTrip.accruedDistanceCharge)
        assertEquals("the accrued waiting must have survived", "3.39", openTrip.accruedWaitingCharge)
        assertEquals(8_412, openTrip.distanceM)
        assertEquals(180, openTrip.waitingS)

        // The live meter rebuilds from exactly this, via reconstructFareState — the F4 restart path.
        val restoredState = reconstructFareState(openTrip, URBAN_TARIFF)
        assertEquals(BigDecimal("21.9553"), restoredState.accruedDistanceCharge)
        assertEquals(BigDecimal("3.39"), restoredState.accruedWaitingCharge)

        // The running total the dial comes back showing: flagfall 5.17 + 21.9553 + 3.39 + psl 1.32
        // = 31.8353, truncated to cents per Act s76(5)/(6) = 31.83. Not zero, which is what the
        // driver used to be left holding.
        val resumed = PureFareEngine().close(restoredState, includePsl = true)
        assertEquals(BigDecimal("31.83"), resumed.grandTotal)

        // --- close ---------------------------------------------------------------------------
        val closed = restoredRepo.closeTrip(
            clientUuid = "trip-1",
            endLat = -33.90,
            endLng = 151.18,
            deviceTotal = resumed.grandTotal.toPlainString(),
            paymentMethod = "cash",
            includePsl = true,
        )

        assertEquals(TripStatus.CLOSED, closed.status)
        assertEquals("31.83", closed.deviceTotal)
        assertNotNull("a closed trip must carry an end timestamp", closed.endAt)
        // The fare the passenger pays is the fare the meter accrued before the crash — the whole
        // point of persisting it.
        assertEquals("21.9553", closed.accruedDistanceCharge)
    }

    @Test
    fun `the closed trip is the one thing handed to the outbox as ready to sync`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = FakeOutboxDao()
        val repo = repository(tripDao, outboxDao)

        openATrip(repo)
        // An open trip leaves a draft row: recoverable evidence the trip existed, but not something
        // SyncWorker may send — a half-driven fare must never reach the server as a finished one.
        assertEquals(1, outboxDao.rows.size)
        assertTrue("an open trip is never ready to sync", outboxDao.rows.single().readyToSync.not())

        repo.tick(clientUuid = "trip-1", newPoints = emptyList(), distanceM = 1_000, movingS = 60, waitingS = 10)
        assertEquals("repeated writes collapse onto one outbox row", 1, outboxDao.rows.size)
        assertTrue(outboxDao.rows.single().readyToSync.not())

        repo.closeTrip(clientUuid = "trip-1", endLat = null, endLng = null, deviceTotal = "12.34")

        assertEquals(1, outboxDao.rows.size)
        assertTrue("closing is what makes the trip sendable", outboxDao.rows.single().readyToSync)
        assertEquals(OutboxEntityType.TRIP, outboxDao.rows.single().entityType)
        assertEquals(1, outboxDao.getReadyBatch(10).size)
    }

    @Test
    fun `ticks overwrite cumulative counters rather than accumulating them twice`() = runTest {
        // The repository's tick() takes RUNNING TOTALS, not deltas, and the live meter calls it on
        // every emission. If it ever added instead of overwrote, a 20-minute trip would bill its
        // distance a thousand times over. The backend has the mirror-image version of exactly this
        // bug (audit, backend §4: a retried /tick double-counts distance), which is reason enough
        // to pin the client side explicitly.
        val tripDao = FakeTripDao()
        val repo = repository(tripDao, FakeOutboxDao())
        openATrip(repo)

        repo.tick(clientUuid = "trip-1", newPoints = emptyList(), distanceM = 1_000, movingS = 60, waitingS = 0)
        repo.tick(clientUuid = "trip-1", newPoints = emptyList(), distanceM = 2_000, movingS = 120, waitingS = 0)
        repo.tick(clientUuid = "trip-1", newPoints = emptyList(), distanceM = 3_000, movingS = 180, waitingS = 0)

        val trip = repo.getTrip("trip-1")!!
        assertEquals(3_000, trip.distanceM)
        assertEquals(180, trip.movingS)
    }

    @Test
    fun `the GPS trace appends across ticks, because the server replays it`() = runTest {
        // Unlike the counters above, the trace genuinely accumulates: `POST /v1/trips/sync`'s
        // recompute_from_trace replays these points to validate the device total within 1%. A trace
        // that overwrote would leave the server replaying one point and flagging every trip — which
        // is a real failure this project has already had (see HiredViewModel's nextTracePoint doc).
        val tripDao = FakeTripDao()
        val repo = repository(tripDao, FakeOutboxDao())
        openATrip(repo)

        repeat(5) { i ->
            repo.tick(
                clientUuid = "trip-1",
                newPoints = listOf(TelemetryPointDto(-33.87, 151.21 + i * 0.001, 50.0, "2026-09-08T01:00:0${i}Z")),
                distanceM = i * 100,
                movingS = i,
                waitingS = 0,
            )
        }

        val trip = repo.getTrip("trip-1")!!
        assertEquals(5, repo.decodeTraceForTest(trip.gpsTraceJson).size)
    }

    @Test
    fun `omitted accrued charges leave the stored values untouched`() = runTest {
        // The "`null` = leave alone" convention tick() uses for tolls and now for the F9 charges.
        // A caller that does not know the charges (any pre-existing call site) must not silently
        // zero the ones already persisted.
        val tripDao = FakeTripDao()
        val repo = repository(tripDao, FakeOutboxDao())
        openATrip(repo)

        repo.tick(
            clientUuid = "trip-1", newPoints = emptyList(), distanceM = 5_000, movingS = 300, waitingS = 0,
            accruedDistanceCharge = "13.05", accruedWaitingCharge = "0.00",
        )
        repo.tick(clientUuid = "trip-1", newPoints = emptyList(), distanceM = 6_000, movingS = 360, waitingS = 0)

        val trip = repo.getTrip("trip-1")!!
        assertEquals("13.05", trip.accruedDistanceCharge)
        assertEquals(6_000, trip.distanceM)
    }

    @Test
    fun `a pre-v11 row with no persisted charges still reconstructs by the old derivation`() = runTest {
        // Every trip taken before the F9 migration has "0" in these columns. Those rows must
        // reconstruct exactly as they did before, or a historical fare would change value on a
        // driver's history screen — see reconstructFareState's fallback branch.
        val tripDao = FakeTripDao()
        val repo = repository(tripDao, FakeOutboxDao())
        openATrip(repo)
        repo.tick(clientUuid = "trip-1", newPoints = emptyList(), distanceM = 10_000, movingS = 700, waitingS = 120)

        val legacy = repo.getTrip("trip-1")!!
        assertEquals("0", legacy.accruedDistanceCharge)

        val state = reconstructFareState(legacy, URBAN_TARIFF)
        // 10 km, all inside the 12 km band 1: 10 x 2.61 = 26.10.
        assertEquals(BigDecimal("26.10"), state.accruedDistanceCharge.setScale(2, RoundingMode.HALF_UP))
        // 120s = 2 min x 1.130 = 2.26.
        assertEquals(BigDecimal("2.26"), state.accruedWaitingCharge.setScale(2, RoundingMode.HALF_UP))
    }

    @Test
    fun `closing a trip twice is refused rather than silently rebilled`() = runTest {
        val tripDao = FakeTripDao()
        val repo = repository(tripDao, FakeOutboxDao())
        openATrip(repo)
        repo.closeTrip(clientUuid = "trip-1", endLat = null, endLng = null, deviceTotal = "20.00")

        val second = runCatching {
            repo.closeTrip(clientUuid = "trip-1", endLat = null, endLng = null, deviceTotal = "99.00")
        }
        assertTrue("a closed trip must not be closeable again", second.isFailure)
        assertEquals("20.00", repo.getTrip("trip-1")!!.deviceTotal)
    }

    @Test
    fun `only one trip is ever open, and it is the one the restore path finds`() = runTest {
        val tripDao = FakeTripDao()
        val repo = repository(tripDao, FakeOutboxDao())

        openATrip(repo, "trip-1")
        repo.closeTrip(clientUuid = "trip-1", endLat = null, endLng = null, deviceTotal = "10.00")
        openATrip(repo, "trip-2")

        val open = tripDao.rows.values.filter { it.status == TripStatus.OPEN }
        assertEquals(1, open.size)
        assertEquals("trip-2", open.single().clientUuid)
        assertNull("a closed trip is not a candidate for restore", open.singleOrNull { it.clientUuid == "trip-1" })
    }
}

/** Decodes a stored trace for assertion purposes — the repository's own encoding, read back. */
private fun TripRepository.decodeTraceForTest(json: String): List<TelemetryPointDto> =
    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(TelemetryPointDto.serializer()),
        json,
    )
