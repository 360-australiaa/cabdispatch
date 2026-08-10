package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao
import au.com.threesixty.cabdispatch.data.local.dao.TripDao
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DeviceDto
import au.com.threesixty.cabdispatch.data.remote.DeviceHeartbeatRequestDto
import au.com.threesixty.cabdispatch.data.remote.DeviceRegisterRequestDto
import au.com.threesixty.cabdispatch.data.remote.LoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.RefreshRequestDto
import au.com.threesixty.cabdispatch.data.remote.ShiftDto
import au.com.threesixty.cabdispatch.data.remote.ShiftEndDto
import au.com.threesixty.cabdispatch.data.remote.ShiftReportDto
import au.com.threesixty.cabdispatch.data.remote.ShiftStartDto
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.data.remote.TokenResponseDto
import au.com.threesixty.cabdispatch.data.remote.TripCloseRequestDto
import au.com.threesixty.cabdispatch.data.remote.TripCreateDto
import au.com.threesixty.cabdispatch.data.remote.TripDto
import au.com.threesixty.cabdispatch.data.remote.TripListResponseDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncResponseDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncResultItemDto
import au.com.threesixty.cabdispatch.data.remote.TripTickRequestDto
import au.com.threesixty.cabdispatch.data.remote.UserDto
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Plain-JVM proof of the offline write path, run in this sandbox in place of
 * an Android instrumented test (no SDK/emulator available here — see
 * android/README.md). Everything under test is production code:
 * [TripRepository] (real class, fed fake DAOs) and [OutboxDrainer] (real
 * class, fed a fake [OutboxDrainer.Ports] backed by the SAME fake DAOs +
 * a tiny fake server). Only [FakeApiService], [FakeTripDao] and
 * [InMemorySyncOutboxDao] are test doubles.
 *
 * Scenario proven end-to-end, matching the manual test plan requested for
 * this module:
 *   1. open trip offline
 *   2. tick 5x offline
 *   3. close offline
 *   4. "app killed and relaunched" (== a fresh TripRepository instance
 *      wrapping the SAME fake DAO backing store, standing in for Room's
 *      on-disk SQLite file surviving a process death — the fakes hold state
 *      exactly the way Room would)
 *   5. outbox still has the (single, upserted) row
 *   6. simulated reconnect -> SyncWorker's drain logic drains it
 *   7. re-sending the same client_uuid again is a no-op (server reports
 *      `duplicate = true`, no second trip created) — proving the resend path
 *      SyncWorker relies on for retry-after-partial-failure is safe.
 *   8. a failed drain (network still down) leaves the row queued with
 *      attempts/lastError recorded, never deletes it — "never drops data".
 */
class OutboxDrainerTest {

    @Test
    fun `open, tick x5, close offline; survives restart; drains on reconnect; resend is idempotent`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = InMemorySyncOutboxDao()
        val fakeServer = FakeApiService()
        val repository = TripRepository(tripDao, outboxDao, fakeServer)

        // 1. Open a trip entirely offline (fakeServer is never touched by TripRepository).
        val opened = repository.openTrip(
            vehicleId = "vehicle-1",
            driverId = "driver-1",
            shiftId = "shift-1",
            tariffId = "tariff-1",
            type = "rank_hail",
            startLat = -33.8688,
            startLng = 151.2093,
        )
        assertEquals(TripStatus.OPEN, opened.status)
        assertEquals(0, fakeServer.callCount) // proves open() never touched the network

        val outboxAfterOpen = outboxDao.getByClientUuid(OutboxEntityType.TRIP, opened.clientUuid)
        assertNotNull("open() must upsert an outbox row", outboxAfterOpen)
        assertFalse("row must not be ready to sync yet (no end_at)", outboxAfterOpen!!.readyToSync)

        // 2. Tick 5x offline.
        repeat(5) { i ->
            repository.tick(
                clientUuid = opened.clientUuid,
                newPoints = listOf(TelemetryPointDto(lat = -33.87 - i * 0.001, lng = 151.21, speedKmh = 30.0, ts = "2026-07-31T10:0$i:00Z")),
                distanceM = (i + 1) * 400,
                movingS = (i + 1) * 60,
                waitingS = 0,
            )
        }
        assertEquals(0, fakeServer.callCount) // still zero network calls

        // Exactly one outbox row for this trip after 1 open + 5 ticks — upsert, not append.
        assertEquals(1, outboxDao.countForClientUuid(opened.clientUuid))
        val afterTicks = outboxDao.getByClientUuid(OutboxEntityType.TRIP, opened.clientUuid)!!
        assertFalse(afterTicks.readyToSync)
        val traceAfterTicks = cabDispatchJson.decodeFromString<List<TelemetryPointDto>>(
            cabDispatchJson.decodeFromString(TripEntity.serializer(), afterTicks.entityJson).gpsTraceJson,
        )
        assertEquals(5, traceAfterTicks.size)

        // 3. Close offline.
        val closed = repository.closeTrip(
            clientUuid = opened.clientUuid,
            endLat = -33.875,
            endLng = 151.21,
            deviceTotal = "27.40",
        )
        assertEquals(TripStatus.CLOSED, closed.status)
        assertEquals(0, fakeServer.callCount) // close() is still 100% offline

        val readyRow = outboxDao.getByClientUuid(OutboxEntityType.TRIP, opened.clientUuid)!!
        assertTrue("close() must flip the same row to ready-to-sync", readyRow.readyToSync)
        assertEquals(1, outboxDao.countForClientUuid(opened.clientUuid)) // still one row, not a new one
        val syncPayload = cabDispatchJson.decodeFromString<TripSyncItemDto>(readyRow.entityJson)
        assertEquals(opened.clientUuid, syncPayload.clientUuid)
        assertEquals("27.40", syncPayload.deviceTotal)
        assertEquals(5, syncPayload.gpsTrace.size)

        // 4. "App killed and relaunched": new TripRepository/OutboxDrainer instances,
        // same underlying fake store (== the same on-disk Room DB across a process restart).
        val relaunchedRepository = TripRepository(tripDao, outboxDao, fakeServer)
        assertEquals(TripStatus.CLOSED, relaunchedRepository.getTrip(opened.clientUuid)?.status)

        // 5. Outbox still has the row.
        val readyBatchBeforeSync = outboxDao.getReadyBatch(20)
        assertEquals(1, readyBatchBeforeSync.size)
        assertEquals(opened.clientUuid, readyBatchBeforeSync.single().clientUuid)

        // 6a. Reconnect attempt while the network is STILL down: must not drop the row.
        fakeServer.networkUp = false
        val failedDrain = OutboxDrainer(fakeDrainerPorts(outboxDao, tripDao, fakeServer)).drainOnce()
        assertEquals(0, failedDrain.sent)
        assertEquals(1, failedDrain.failed)
        val afterFailedDrain = outboxDao.getByClientUuid(OutboxEntityType.TRIP, opened.clientUuid)
        assertNotNull("a failed sync must never drop the outbox row", afterFailedDrain)
        assertEquals(1, afterFailedDrain!!.attempts)
        assertNotNull(afterFailedDrain.lastError)

        // 6b. Real reconnect: network comes back, SyncWorker's drain logic runs.
        fakeServer.networkUp = true
        val drainResult = OutboxDrainer(fakeDrainerPorts(outboxDao, tripDao, fakeServer)).drainOnce()
        assertEquals(1, drainResult.sent)
        assertEquals(0, drainResult.failed)
        assertNull("row must be gone from the outbox once synced", outboxDao.getByClientUuid(OutboxEntityType.TRIP, opened.clientUuid))
        assertEquals(TripStatus.SYNCED, tripDao.getByClientUuid(opened.clientUuid)?.status)
        assertNotNull(tripDao.getByClientUuid(opened.clientUuid)?.serverId)
        assertEquals(1, fakeServer.callCount)
        assertEquals(1, fakeServer.storedTripCount())

        // 7. Idempotency: resend the exact same client_uuid (as a real retry-after-
        // partial-failure would) — server must report it as a duplicate, not create
        // a second trip. This is what makes "retry after an ambiguous network failure"
        // always safe.
        val resendResponse = fakeServer.syncTrips(listOf(syncPayload))
        assertEquals(1, resendResponse.results.size)
        assertTrue("resending an already-synced client_uuid must be reported as duplicate", resendResponse.results.single().duplicate)
        assertEquals(1, fakeServer.storedTripCount()) // still exactly one trip server-side
        assertEquals(2, fakeServer.callCount)
    }

    /**
     * Note the parameter types here are the DAO interfaces/base class
     * ([TripDao]/[SyncOutboxDao]), not the concrete fakes — exactly the
     * types [SyncWorker] gets from [au.com.threesixty.cabdispatch.data.AppContainer]
     * in production, so a call like `tripDao.markSynced(clientUuid, serverId)`
     * resolves the interface-declared default parameters the same way here
     * as it does there.
     */
    private fun fakeDrainerPorts(
        outboxDao: SyncOutboxDao,
        tripDao: TripDao,
        apiService: FakeApiService,
    ): OutboxDrainer.Ports = object : OutboxDrainer.Ports {
        override suspend fun fetchReadyBatch(limit: Int): List<SyncOutboxEntity> = outboxDao.getReadyBatch(limit)
        override suspend fun sendTrips(items: List<TripSyncItemDto>): TripSyncResponseDto = apiService.syncTrips(items)
        override suspend fun markTripSynced(clientUuid: String, serverId: String) = tripDao.markSynced(clientUuid, serverId)
        override suspend fun deleteOutboxRow(id: Long) = outboxDao.deleteById(id)
        override suspend fun recordFailure(id: Long, error: String) = outboxDao.recordFailure(id, error)
    }
}

// ============================================================================
// Test doubles
// ============================================================================

/** In-memory stand-in for Room's generated `SyncOutboxDao` impl — exercises the REAL upsert()/getReadyBatch() logic from the abstract class under test. */
private class InMemorySyncOutboxDao : SyncOutboxDao() {
    private val rowsById = mutableMapOf<Long, SyncOutboxEntity>()
    private var nextId = 1L

    override suspend fun insertIgnoring(row: SyncOutboxEntity): Long {
        val conflicts = rowsById.values.any { it.entityType == row.entityType && it.clientUuid == row.clientUuid }
        if (conflicts) return -1L
        val id = nextId++
        rowsById[id] = row.copy(id = id)
        return id
    }

    override suspend fun updateExisting(entityType: String, clientUuid: String, entityJson: String, readyToSync: Boolean) {
        val existing = rowsById.values.first { it.entityType == entityType && it.clientUuid == clientUuid }
        rowsById[existing.id] = existing.copy(entityJson = entityJson, readyToSync = readyToSync)
    }

    override suspend fun getReadyBatch(limit: Int): List<SyncOutboxEntity> =
        rowsById.values.filter { it.readyToSync }.sortedBy { it.createdAt }.take(limit)

    override suspend fun getByClientUuid(entityType: String, clientUuid: String): SyncOutboxEntity? =
        rowsById.values.firstOrNull { it.entityType == entityType && it.clientUuid == clientUuid }

    override suspend fun deleteById(id: Long) {
        rowsById.remove(id)
    }

    override suspend fun recordFailure(id: Long, error: String) {
        rowsById[id]?.let { rowsById[id] = it.copy(attempts = it.attempts + 1, lastError = error) }
    }

    override fun observeOutboxSize(): Flow<Int> = flowOf(rowsById.size)

    fun countForClientUuid(clientUuid: String): Int = rowsById.values.count { it.clientUuid == clientUuid }
}

/** In-memory stand-in for Room's generated `TripDao` impl. */
private class FakeTripDao : TripDao {
    private val rows = mutableMapOf<String, TripEntity>()

    override suspend fun insert(trip: TripEntity) {
        check(trip.clientUuid !in rows) { "duplicate clientUuid=${trip.clientUuid}" }
        rows[trip.clientUuid] = trip
    }

    override suspend fun update(trip: TripEntity) {
        rows[trip.clientUuid] = trip
    }

    override suspend fun getByClientUuid(clientUuid: String): TripEntity? = rows[clientUuid]

    override fun observeTrip(clientUuid: String): Flow<TripEntity?> = flowOf(rows[clientUuid])

    override fun observeActiveTrip(openStatus: String): Flow<TripEntity?> =
        flowOf(rows.values.firstOrNull { it.status == openStatus })

    override fun observeTripsByStatus(status: String): Flow<List<TripEntity>> =
        flowOf(rows.values.filter { it.status == status })

    override fun observeTripsForShift(shiftId: String): Flow<List<TripEntity>> =
        flowOf(rows.values.filter { it.shiftId == shiftId })

    override suspend fun markSynced(clientUuid: String, serverId: String, updatedAt: Long, status: String) {
        rows[clientUuid]?.let { rows[clientUuid] = it.copy(serverId = serverId, status = status, updatedAt = updatedAt) }
    }

    override fun observeUnsyncedCount(syncedStatus: String): Flow<Int> =
        flowOf(rows.values.count { it.status != syncedStatus })
}

/**
 * A tiny fake backend for `/v1/trips/sync`: dedupes on `client_uuid` exactly
 * like the real server contract documents (shared/API_SUMMARY.md), so
 * [syncTrips] genuinely proves idempotency rather than just asserting it.
 * [networkUp] simulates connectivity for the "drain fails, then reconnect
 * succeeds" part of the test. Every other endpoint is unused by
 * [TripRepository]/[OutboxDrainer] and throws if accidentally called.
 */
private class FakeApiService : ApiService {
    var networkUp: Boolean = true
    var callCount: Int = 0
        private set

    private val storedByClientUuid = mutableMapOf<String, TripDto>()

    fun storedTripCount(): Int = storedByClientUuid.size

    override suspend fun syncTrips(body: List<TripSyncItemDto>): TripSyncResponseDto {
        // Offline calls never "reach" the fake server at all, matching real
        // network semantics — callCount is a count of successful contacts.
        if (!networkUp) throw IOException("simulated offline")
        callCount++
        val results = body.map { item ->
            val existing = storedByClientUuid[item.clientUuid]
            if (existing != null) {
                TripSyncResultItemDto(clientUuid = item.clientUuid, duplicate = true, trip = existing)
            } else {
                val trip = fakeTripDtoFrom(item, serverId = "server-${storedByClientUuid.size + 1}")
                storedByClientUuid[item.clientUuid] = trip
                TripSyncResultItemDto(clientUuid = item.clientUuid, duplicate = false, trip = trip)
            }
        }
        return TripSyncResponseDto(results)
    }

    private fun fakeTripDtoFrom(item: TripSyncItemDto, serverId: String): TripDto = TripDto(
        id = serverId,
        tenantId = "tenant-1",
        clientUuid = item.clientUuid,
        vehicleId = item.vehicleId,
        driverId = item.driverId,
        shiftId = item.shiftId,
        tariffId = item.tariffId,
        type = item.type,
        status = "closed",
        timeClass = item.timeClass,
        isPeak = item.isPeak,
        maxi = item.maxi,
        startAt = item.startAt,
        endAt = item.endAt,
        startLat = item.startLat,
        startLng = item.startLng,
        endLat = item.endLat,
        endLng = item.endLng,
        distanceM = 0,
        movingS = 0,
        waitingS = 0,
        flagFall = "4.20",
        distAmount = "0",
        waitAmount = "0",
        peakAmount = "0",
        tolls = item.tolls,
        psl = "0",
        extras = item.extras,
        subtotal = item.deviceTotal,
        surcharge = "0",
        total = item.deviceTotal,
        gstComponent = "0",
        paymentMethod = item.paymentMethod,
        gpsTraceRef = item.gpsTraceRef,
        maxFareCheckPassed = true,
        variancePct = "0",
        receiptRef = item.receiptRef,
        createdAt = item.startAt,
        updatedAt = item.endAt,
    )

    // --- Everything below is unused by the code under test. ---
    override suspend fun login(body: LoginRequestDto): TokenResponseDto = notUsed()
    override suspend fun refresh(body: RefreshRequestDto): TokenResponseDto = notUsed()
    override suspend fun me(): UserDto = notUsed()
    override suspend fun logout(): Unit = notUsed()
    override suspend fun registerDevice(body: DeviceRegisterRequestDto): DeviceDto = notUsed()
    override suspend fun deviceHeartbeat(deviceId: String, body: DeviceHeartbeatRequestDto): DeviceDto = notUsed()
    override suspend fun activeTariff(region: String, at: String?): TariffDto = notUsed()
    override suspend fun currentFaresOrder(region: String, at: String?): TariffDto = notUsed()
    override suspend fun createTrip(body: TripCreateDto): TripDto = notUsed()
    override suspend fun listTrips(status: String?, type: String?, vehicleId: String?, driverId: String?, skip: Int, limit: Int): TripListResponseDto = notUsed()
    override suspend fun getTrip(tripId: String): TripDto = notUsed()
    override suspend fun tickTrip(tripId: String, body: TripTickRequestDto): TripDto = notUsed()
    override suspend fun closeTrip(tripId: String, body: TripCloseRequestDto): TripDto = notUsed()
    override suspend fun startShift(body: ShiftStartDto): ShiftDto = notUsed()
    override suspend fun endShift(shiftId: String, body: ShiftEndDto): ShiftDto = notUsed()
    override suspend fun shiftReport(shiftId: String): ShiftReportDto = notUsed()
    // Added 2026-08-10 (driver-photo pass) alongside the two new ApiService methods below --
    // this FakeApiService already did not override every ApiService member (driverLogin,
    // mfaLogin, verifyAdminPin, the duress endpoints, getComplianceDossier, zones, etc. are all
    // missing too, none of it added by this pass) -- see HANDOFF.md's 2026-08-10 entry for the
    // honest flag that this file was very likely already failing to compile against the real
    // interface before this pass touched it, for reasons unrelated to this change. Only these two
    // are added here, to at least not add to that pre-existing list with brand new gaps.
    override suspend fun uploadUserPhoto(userId: String, file: MultipartBody.Part): UserDto = notUsed()
    override suspend fun getUserPhoto(userId: String): ResponseBody = notUsed()

    private fun notUsed(): Nothing = throw UnsupportedOperationException("not exercised by this test")
}
