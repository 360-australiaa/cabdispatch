package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao
import au.com.threesixty.cabdispatch.data.local.dao.TripDao
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.ComplianceDossierDto
import au.com.threesixty.cabdispatch.data.remote.ComplianceExpiryPageDto
import au.com.threesixty.cabdispatch.data.remote.CorporateAccountPageDto
import au.com.threesixty.cabdispatch.data.remote.DeviceDto
import au.com.threesixty.cabdispatch.data.remote.DeviceCommandAckDto
import au.com.threesixty.cabdispatch.data.remote.DeviceHeartbeatRequestDto
import au.com.threesixty.cabdispatch.data.remote.DeviceLocateResponseDto
import au.com.threesixty.cabdispatch.data.remote.DeviceRegisterRequestDto
import au.com.threesixty.cabdispatch.data.remote.DriverAvailabilityDto
import au.com.threesixty.cabdispatch.data.remote.DriverAvailabilityUpdateDto
import au.com.threesixty.cabdispatch.data.remote.DriverEarningsTodayReadDto
import au.com.threesixty.cabdispatch.data.remote.DriverLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.DriverLoginResponseDto
import au.com.threesixty.cabdispatch.data.remote.DuressCancelRequestDto
import au.com.threesixty.cabdispatch.data.remote.DuressEventDto
import au.com.threesixty.cabdispatch.data.remote.DuressGpsPointDto
import au.com.threesixty.cabdispatch.data.remote.DuressSnapshotDto
import au.com.threesixty.cabdispatch.data.remote.DuressTriggerRequestDto
import au.com.threesixty.cabdispatch.data.remote.FatigueAlertPageDto
import au.com.threesixty.cabdispatch.data.remote.JobCreateDto
import au.com.threesixty.cabdispatch.data.remote.JobDto
import au.com.threesixty.cabdispatch.data.remote.JobListResponseDto
import au.com.threesixty.cabdispatch.data.remote.JobOfferDto
import au.com.threesixty.cabdispatch.data.remote.LoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.MessageCreateDto
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.data.remote.MessageListResponseDto
import au.com.threesixty.cabdispatch.data.remote.MessageTemplateDto
import au.com.threesixty.cabdispatch.data.remote.MfaLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.PositionPublishRequestDto
import au.com.threesixty.cabdispatch.data.remote.PositionPublishResponseDto
import au.com.threesixty.cabdispatch.data.remote.ReceiptEmailRequestDto
import au.com.threesixty.cabdispatch.data.remote.ReceiptEmailResponseDto
import au.com.threesixty.cabdispatch.data.remote.ReceiptSmsRequestDto
import au.com.threesixty.cabdispatch.data.remote.ReceiptSmsResponseDto
import au.com.threesixty.cabdispatch.data.remote.RefreshRequestDto
import au.com.threesixty.cabdispatch.data.remote.RefreshResponseDto
import au.com.threesixty.cabdispatch.data.remote.ShiftDto
import au.com.threesixty.cabdispatch.data.remote.ShiftEndDto
import au.com.threesixty.cabdispatch.data.remote.ShiftReportDto
import au.com.threesixty.cabdispatch.data.remote.ShiftStartDto
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.data.remote.TariffPresetDto
import au.com.threesixty.cabdispatch.data.remote.TariffSigningPublicKeyDto
import au.com.threesixty.cabdispatch.data.remote.TariffSuggestionDto
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.data.remote.TemplateMessageCreateDto
import au.com.threesixty.cabdispatch.data.remote.TokenResponseDto
import au.com.threesixty.cabdispatch.data.remote.TripCloseRequestDto
import au.com.threesixty.cabdispatch.data.remote.TripCreateDto
import au.com.threesixty.cabdispatch.data.remote.TripDto
import au.com.threesixty.cabdispatch.data.remote.TripFlagRequestDto
import au.com.threesixty.cabdispatch.data.remote.TripListResponseDto
import au.com.threesixty.cabdispatch.data.remote.TripRatingCreateDto
import au.com.threesixty.cabdispatch.data.remote.TripRatingDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncResponseDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncResultItemDto
import au.com.threesixty.cabdispatch.data.remote.TripTickRequestDto
import au.com.threesixty.cabdispatch.data.remote.UserDto
import au.com.threesixty.cabdispatch.data.remote.VehiclePageDto
import au.com.threesixty.cabdispatch.data.remote.VerifyAdminPinRequestDto
import au.com.threesixty.cabdispatch.data.remote.VerifyAdminPinResponseDto
import au.com.threesixty.cabdispatch.data.remote.VoucherPageDto
import au.com.threesixty.cabdispatch.data.remote.ZoneListResponseDto
import au.com.threesixty.cabdispatch.data.remote.ZonePlotReadDto
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import au.com.threesixty.cabdispatch.domain.OutboxBackedShiftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    // Real compile bug fixed 2026-08-29 (found while chasing an unrelated FareEngine fix, then
    // fixed since it blocked this whole test source set from compiling): a backtick-quoted Kotlin
    // identifier still has to be a legal JVM name underneath, and `;` is one of the characters the
    // JVM spec forbids in a method name (it's a descriptor separator) — javac/kotlinc accept the
    // source but the class file is invalid. Commas read just as clearly and compile.
    fun `open, tick x5, close offline, survives restart, drains on reconnect, resend is idempotent`() = runTest {
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
        val readyBatchBeforeSync = outboxDao.getReadyBatch(20, Long.MAX_VALUE, SyncBackoff.MAX_ATTEMPTS)
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
        //
        // The clock is advanced past the row's backoff window here (S1, 2026-09-08): a failed row
        // is no longer eligible again immediately — `recordFailure` above pushed `nextAttemptAt`
        // 30 s out. That is the intended new behaviour, not an obstacle to this test; the drain
        // itself is unchanged, so this just skips forward to when the retry is genuinely due.
        fakeServer.networkUp = true
        val afterBackoff = System.currentTimeMillis() + SyncBackoff.delayMsAfter(1) + 1
        val drainResult = OutboxDrainer(fakeDrainerPorts(outboxDao, tripDao, fakeServer)) { afterBackoff }.drainOnce()
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
     * Regression test for the maxi-at-airport-rank fare-integrity fix (2026-09-05):
     * [TripRepository.openTrip]'s `airportRankRequestedMaxi` parameter used to be silently dropped
     * — it was accepted by [au.com.threesixty.cabdispatch.domain.FareEngine.startTrip] (so the live
     * on-device meter correctly charged the maxi rate) but never persisted to
     * [TripEntity.airportRankRequestedMaxi] or threaded into [TripSyncItemDto], so a synced trip's
     * `device_total` (which correctly included the maxi surcharge) could diverge from the server's
     * independent recompute (which had no way to know the flag was set) and get rejected for
     * exceeding the sync variance tolerance. Proves the fix: the flag survives open -> Room ->
     * close -> the wire payload [TripRepository.closeTrip] queues for `POST /v1/trips/sync`.
     */
    @Test
    fun `airportRankRequestedMaxi survives open, close, and reaches the sync wire payload`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = InMemorySyncOutboxDao()
        val fakeServer = FakeApiService()
        val repository = TripRepository(tripDao, outboxDao, fakeServer)

        val opened = repository.openTrip(
            vehicleId = "vehicle-1",
            driverId = "driver-1",
            shiftId = "shift-1",
            tariffId = "tariff-1",
            type = "rank_hail",
            startLat = -33.8688,
            startLng = 151.2093,
            maxi = true,
            passengerCount = 1,
            airportRankRequestedMaxi = true,
        )
        assertTrue("openTrip() must persist the flag onto the Room row", opened.airportRankRequestedMaxi)

        val closed = repository.closeTrip(
            clientUuid = opened.clientUuid,
            endLat = -33.875,
            endLng = 151.21,
            deviceTotal = "27.40",
        )
        assertTrue("closeTrip() must not clear the flag set at open", closed.airportRankRequestedMaxi)

        val readyRow = outboxDao.getByClientUuid(OutboxEntityType.TRIP, opened.clientUuid)!!
        val syncPayload = cabDispatchJson.decodeFromString<TripSyncItemDto>(readyRow.entityJson)
        assertEquals(
            "the flag must reach the exact payload POST /v1/trips/sync sends -- the ONLY " +
                "network call this app's offline-first close flow makes",
            true,
            syncPayload.airportRankRequestedMaxi,
        )
    }

    /**
     * Regression test for the "drop-off silently overwritten with the start point at close"
     * bug: [TripRepository.closeTrip] used to take non-null `endLat`/`endLng` and the only
     * caller ([au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel]) had no
     * real end fix to pass, so it fell back to `trip.startLat`/`startLng` — clobbering whatever
     * real destination [TripRepository.updateDropoff] had already written. Proves the fix:
     * `closeTrip(endLat = null, endLng = null, ...)` must leave the coordinates
     * [updateDropoff] wrote untouched, never fall back to the start point, and a real fix passed
     * to [TripRepository.closeTrip] must still win over both.
     */
    @Test
    fun `closeTrip with no live fix preserves the navigator's drop-off, never the start point`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = InMemorySyncOutboxDao()
        val repository = TripRepository(tripDao, outboxDao, FakeApiService())

        val opened = repository.openTrip(
            vehicleId = "vehicle-1",
            driverId = "driver-1",
            shiftId = "shift-1",
            tariffId = "tariff-1",
            type = "rank_hail",
            startLat = -33.8688,
            startLng = 151.2093,
        )

        // Navigator picks a real destination mid-trip.
        repository.updateDropoff(
            clientUuid = opened.clientUuid,
            address = "1 Example St, Sydney",
            lat = -33.9,
            lng = 151.25,
        )

        // Close with no live GPS fix available (the honest case at this call site today).
        val closedNoFix = repository.closeTrip(
            clientUuid = opened.clientUuid,
            endLat = null,
            endLng = null,
            deviceTotal = "20.00",
        )
        assertEquals("must keep the navigator's drop-off latitude, not the start point", -33.9, closedNoFix.endLat!!, 0.0)
        assertEquals("must keep the navigator's drop-off longitude, not the start point", 151.25, closedNoFix.endLng!!, 0.0)
        assertFalse("must not silently equal the start point", closedNoFix.endLat == opened.startLat && closedNoFix.endLng == opened.startLng)

        // A second trip proves a REAL live fix at close time still wins over the navigator's
        // intended destination — closeTrip's whole point is "where the fare actually ended".
        val opened2 = repository.openTrip(
            vehicleId = "vehicle-1",
            driverId = "driver-1",
            shiftId = "shift-1",
            tariffId = "tariff-1",
            type = "rank_hail",
            startLat = -33.8688,
            startLng = 151.2093,
        )
        repository.updateDropoff(clientUuid = opened2.clientUuid, address = "Intended dest", lat = -33.9, lng = 151.25)
        val closedWithFix = repository.closeTrip(
            clientUuid = opened2.clientUuid,
            endLat = -33.95,
            endLng = 151.30,
            deviceTotal = "25.00",
        )
        assertEquals(-33.95, closedWithFix.endLat!!, 0.0)
        assertEquals(151.30, closedWithFix.endLng!!, 0.0)
    }

    // ========================================================================
    // S1 — attempts cap and exponential backoff
    // ========================================================================

    @Test
    fun `a permanently rejected row backs off, then dead-letters after five attempts`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = InMemorySyncOutboxDao()
        val fakeServer = FakeApiService()
        val repository = TripRepository(tripDao, outboxDao, fakeServer)

        val opened = openTestTrip(repository, shiftId = "shift-1")
        closeTestTrip(repository, opened.clientUuid)

        // The server is reachable but refuses this row every time — the "422 forever" case S1 is
        // about, as opposed to simply being offline.
        fakeServer.networkUp = true
        fakeServer.rejectAllTrips = true

        var clock = 1_000_000L
        val drainer = OutboxDrainer(fakeDrainerPorts(outboxDao, tripDao, fakeServer)) { clock }

        // Attempts 1..4: each records a failure and pushes the row out by a DOUBLING delay.
        val expectedDelays = listOf(30_000L, 60_000L, 120_000L, 240_000L)
        for ((index, expectedDelay) in expectedDelays.withIndex()) {
            val result = drainer.drainOnce()
            assertEquals("attempt ${index + 1} is a retryable failure", 1, result.failed)
            assertEquals(0, result.deadLettered)

            val row = outboxDao.rowFor(OutboxEntityType.TRIP, opened.clientUuid)!!
            assertEquals("attempts after failure ${index + 1}", index + 1, row.attempts)
            assertEquals(
                "backoff after failure ${index + 1} should double",
                clock + expectedDelay,
                row.nextAttemptAt,
            )
            assertFalse("must not dead-letter before the cap", row.deadLettered)

            // Still inside the backoff window: the row must NOT be eligible yet. This is the half
            // of S1 that stops a flapping connection hammering the API.
            assertEquals(
                "row must be ineligible during its backoff window",
                0,
                outboxDao.getReadyBatch(20, clock + expectedDelay - 1, SyncBackoff.MAX_ATTEMPTS).size,
            )

            clock += expectedDelay
        }

        // Attempt 5 is the last. It dead-letters rather than scheduling a sixth.
        val final = drainer.drainOnce()
        assertEquals(1, final.deadLettered)
        assertEquals("a dead-lettered row is not a retryable failure", 0, final.failed)
        assertFalse(
            "the worker must not keep retrying for a terminal row — that is the S1 bug",
            final.hadFailure,
        )

        val dead = outboxDao.rowFor(OutboxEntityType.TRIP, opened.clientUuid)!!
        assertTrue(dead.deadLettered)
        assertEquals(SyncBackoff.MAX_ATTEMPTS, dead.attempts)

        // Never picked up again, however far the clock runs.
        assertEquals(0, outboxDao.getReadyBatch(20, clock + 999_999_999L, SyncBackoff.MAX_ATTEMPTS).size)
        // ...no longer counted as "pending sync" (S2's dishonest-count half)...
        assertEquals(0, outboxDao.observeOutboxSize().first())
        // ...but reported as failed, with the reason, for OfflineSyncScreen.
        val surfaced = outboxDao.observeDeadLettered().first()
        assertEquals(1, surfaced.size)
        assertTrue(surfaced.single().lastError!!.contains("attempts"))
    }

    @Test
    fun `a poisoned row does not block newer trips behind it`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = InMemorySyncOutboxDao()
        val fakeServer = FakeApiService()
        val repository = TripRepository(tripDao, outboxDao, fakeServer)

        // The poisoned row is the OLDEST, which is what made it so damaging: the batch is
        // oldest-first, so before the cap it sat at the head of the queue forever.
        val poisoned = openTestTrip(repository, shiftId = "s")
        closeTestTrip(repository, poisoned.clientUuid)
        outboxDao.markDeadLettered(
            outboxDao.rowFor(OutboxEntityType.TRIP, poisoned.clientUuid)!!.id,
            "422 unprocessable: voucher rejected",
        )

        val good = openTestTrip(repository, shiftId = "s")
        closeTestTrip(repository, good.clientUuid)

        val result = OutboxDrainer(fakeDrainerPorts(outboxDao, tripDao, fakeServer)).drainOnce()

        assertEquals("the healthy trip syncs despite the poisoned row ahead of it", 1, result.sent)
        assertEquals(0, result.failed)
        assertEquals(1, fakeServer.storedTripCount())
        // The poisoned row is still there — reported, not deleted. Nothing is lost.
        assertEquals(1, outboxDao.observeDeadLettered().first().size)
    }

    @Test
    fun `a malformed row is dead-lettered, not skipped forever`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = InMemorySyncOutboxDao()
        val fakeServer = FakeApiService()

        outboxDao.upsert(
            SyncOutboxEntity(
                entityType = OutboxEntityType.TRIP,
                clientUuid = "corrupt-1",
                entityJson = "{ this is not valid json",
                readyToSync = true,
                createdAt = 1L,
            ),
        )

        val drainer = OutboxDrainer(fakeDrainerPorts(outboxDao, tripDao, fakeServer))
        val first = drainer.drainOnce()
        assertEquals(1, first.deadLettered)

        // S2: before the fix this row was skipped and re-counted on EVERY drain, forever, while
        // still inflating the pending count. Now it is terminal and reported once.
        val second = drainer.drainOnce()
        assertEquals("a dead-lettered row must not be re-examined", 0, second.deadLettered)
        assertEquals(0, second.sent)
        assertEquals(0, outboxDao.observeOutboxSize().first())
        assertEquals(1, outboxDao.observeDeadLettered().first().size)
    }

    @Test
    fun `retrying a dead-lettered row makes it eligible again`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = InMemorySyncOutboxDao()
        val fakeServer = FakeApiService()
        val repository = TripRepository(tripDao, outboxDao, fakeServer)

        val opened = openTestTrip(repository, shiftId = "s")
        closeTestTrip(repository, opened.clientUuid)
        val rowId = outboxDao.rowFor(OutboxEntityType.TRIP, opened.clientUuid)!!.id
        outboxDao.markDeadLettered(rowId, "gave up after 5 attempts")

        // What OfflineSyncScreen's RETRY button does, once the depot has fixed the cause.
        outboxDao.resetForRetry(rowId)

        val result = OutboxDrainer(fakeDrainerPorts(outboxDao, tripDao, fakeServer)).drainOnce()
        assertEquals(1, result.sent)
        assertEquals(1, fakeServer.storedTripCount())
    }

    // ========================================================================
    // S3 — an offline shift start is queued, drains BEFORE its trips, and its
    //      trips have their shift_id rewritten to the real server id
    // ========================================================================

    @Test
    fun `shift starts offline, then drains before its trips and rewrites their shift id`() = runTest {
        val tripDao = FakeTripDao()
        val outboxDao = InMemorySyncOutboxDao()
        val fakeServer = FakeApiService()

        // 1. Start the shift with no connectivity.
        fakeServer.networkUp = false
        val shiftRepo = OutboxBackedShiftRepository(
            apiService = fakeServer,
            outbox = fakeShiftOutboxPort(outboxDao),
            now = { 1_000L },
            newUuid = { "shift-uuid-1" },
        )
        val started = shiftRepo.startShift("driver-1", "vehicle-1", emptyMap()).getOrThrow()

        // The driver is not blocked, but the id is honestly marked as not-yet-real...
        assertEquals("local:shift-uuid-1", started.id)
        assertTrue(OutboxBackedShiftRepository.isLocalShiftId(started.id))
        // ...and unlike the old fabricated shift, it is PERSISTED and will be retried.
        val queued = outboxDao.rowFor(OutboxEntityType.SHIFT, "shift-uuid-1")
        assertTrue("the offline shift start must be queued, not orphaned", queued != null)
        // The agreed idempotency field name must be on the wire payload.
        assertTrue("payload must carry client_uuid", queued!!.entityJson.contains("client_uuid"))

        // 2. Close two trips under that not-yet-real shift, still offline.
        val repository = TripRepository(tripDao, outboxDao, fakeServer)
        val tripA = openTestTrip(repository, shiftId = started.id)
        closeTestTrip(repository, tripA.clientUuid)
        val tripB = openTestTrip(repository, shiftId = started.id)
        closeTestTrip(repository, tripB.clientUuid)
        assertEquals(started.id, tripDao.shiftIdOf(tripA.clientUuid))

        // 3. Reconnect and drain.
        fakeServer.networkUp = true
        val result = OutboxDrainer(fakeDrainerPorts(outboxDao, tripDao, fakeServer)).drainOnce()

        // The shift went FIRST. This is the ordering the whole fix turns on: a trip sent before its
        // shift exists carries a shift_id the server has never issued.
        assertEquals(listOf("startShift", "syncTrips"), fakeServer.callLog)

        assertEquals("one shift + two trips", 3, result.sent)
        assertEquals(0, result.failed)

        // Every trip that reached the wire carries the REAL shift id, never the placeholder.
        val serverShiftId = fakeServer.startedShiftsByClientUuid.getValue("shift-uuid-1").id
        assertTrue(fakeServer.tripPayloadLog.isNotEmpty())
        for (payload in fakeServer.tripPayloadLog) {
            assertEquals(serverShiftId, payload.shiftId)
            assertFalse(
                "no trip may reach the server carrying a local: shift id",
                OutboxBackedShiftRepository.isLocalShiftId(payload.shiftId ?: ""),
            )
        }
        // ...and the local trip rows were repointed too.
        assertEquals(serverShiftId, tripDao.shiftIdOf(tripA.clientUuid))
        assertEquals(serverShiftId, tripDao.shiftIdOf(tripB.clientUuid))

        // Nothing left queued.
        assertEquals(0, outboxDao.allRows().size)
    }

    @Test
    fun `an online shift start never leaves a row behind`() = runTest {
        val outboxDao = InMemorySyncOutboxDao()
        val fakeServer = FakeApiService()

        val shiftRepo = OutboxBackedShiftRepository(
            apiService = fakeServer,
            outbox = fakeShiftOutboxPort(outboxDao),
            newUuid = { "shift-uuid-online" },
        )
        val started = shiftRepo.startShift("driver-1", "vehicle-1", emptyMap()).getOrThrow()

        assertFalse(
            "an online start returns the server's real id",
            OutboxBackedShiftRepository.isLocalShiftId(started.id),
        )
        assertEquals("the queued row must be cleaned up on success", 0, outboxDao.allRows().size)
    }

    /** Opens a trip with the boilerplate these S1/S3 tests don't care about filled in — the
     * trip's own fields are irrelevant to queue mechanics, only its shift id and uuid are. */
    private suspend fun openTestTrip(repository: TripRepository, shiftId: String?) = repository.openTrip(
        vehicleId = "vehicle-1",
        driverId = "driver-1",
        shiftId = shiftId,
        tariffId = "tariff-1",
        type = "rank_hail",
        startLat = -33.8688,
        startLng = 151.2093,
    )

    /** Closes a trip, which is what flips its outbox row to `readyToSync`. */
    private suspend fun closeTestTrip(repository: TripRepository, clientUuid: String) = repository.closeTrip(
        clientUuid = clientUuid,
        endLat = -33.8700,
        endLng = 151.2100,
        deviceTotal = "25.00",
    )

    /** Mirrors the adapter [au.com.threesixty.cabdispatch.data.AppContainer] wires in production. */
    private fun fakeShiftOutboxPort(outboxDao: InMemorySyncOutboxDao) =
        object : OutboxBackedShiftRepository.ShiftOutboxPort {
            override suspend fun queueShiftStart(clientUuid: String, payloadJson: String, createdAt: Long) {
                outboxDao.upsert(
                    SyncOutboxEntity(
                        entityType = OutboxEntityType.SHIFT,
                        clientUuid = clientUuid,
                        entityJson = payloadJson,
                        readyToSync = true,
                        createdAt = createdAt,
                    ),
                )
            }

            override suspend fun deleteShiftStart(clientUuid: String) {
                outboxDao.getByClientUuid(OutboxEntityType.SHIFT, clientUuid)
                    ?.let { outboxDao.deleteById(it.id) }
            }
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
        override suspend fun fetchReadyBatch(limit: Int, now: Long, maxAttempts: Int): List<SyncOutboxEntity> =
            outboxDao.getReadyBatch(limit, now, maxAttempts)
        override suspend fun sendTrips(items: List<TripSyncItemDto>): TripSyncResponseDto = apiService.syncTrips(items)
        override suspend fun sendShiftStart(payload: ShiftStartDto): ShiftDto = apiService.startShift(payload)
        override suspend fun rewriteShiftId(localShiftId: String, serverShiftId: String) {
            tripDao.rewriteShiftId(localShiftId, serverShiftId)
            outboxDao.rewriteShiftIdInPayloads(localShiftId, serverShiftId)
        }
        override suspend fun markTripSynced(clientUuid: String, serverId: String) = tripDao.markSynced(clientUuid, serverId)
        override suspend fun deleteOutboxRow(id: Long) = outboxDao.deleteById(id)
        override suspend fun recordFailure(id: Long, error: String, nextAttemptAt: Long) =
            outboxDao.recordFailure(id, error, nextAttemptAt)
        override suspend fun markDeadLettered(id: Long, error: String) = outboxDao.markDeadLettered(id, error)
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

    // Mirrors the real @Query's WHERE/ORDER BY, including the S1 backoff/attempts gates and the
    // S3 shift-before-trip ordering, so these tests exercise the same eligibility rules the
    // device does.
    override suspend fun getReadyBatch(limit: Int, now: Long, maxAttempts: Int): List<SyncOutboxEntity> =
        rowsById.values
            .filter { it.readyToSync && !it.deadLettered && it.attempts < maxAttempts && it.nextAttemptAt <= now }
            .sortedWith(compareBy({ if (it.entityType == OutboxEntityType.SHIFT) 0 else 1 }, { it.createdAt }))
            .take(limit)

    override suspend fun getByClientUuid(entityType: String, clientUuid: String): SyncOutboxEntity? =
        rowsById.values.firstOrNull { it.entityType == entityType && it.clientUuid == clientUuid }

    override suspend fun deleteById(id: Long) {
        rowsById.remove(id)
    }

    override suspend fun recordFailure(id: Long, error: String, nextAttemptAt: Long) {
        rowsById[id]?.let {
            rowsById[id] = it.copy(attempts = it.attempts + 1, lastError = error, nextAttemptAt = nextAttemptAt)
        }
    }

    override suspend fun markDeadLettered(id: Long, error: String) {
        rowsById[id]?.let { rowsById[id] = it.copy(deadLettered = true, lastError = error) }
    }

    override suspend fun resetForRetry(id: Long) {
        rowsById[id]?.let { rowsById[id] = it.copy(deadLettered = false, attempts = 0, nextAttemptAt = 0) }
    }

    override suspend fun rewriteShiftIdInPayloads(localShiftId: String, serverShiftId: String) {
        for ((id, row) in rowsById.toMap()) {
            if (row.entityType == OutboxEntityType.TRIP && row.entityJson.contains(localShiftId)) {
                rowsById[id] = row.copy(entityJson = row.entityJson.replace(localShiftId, serverShiftId))
            }
        }
    }

    override fun observeDeadLettered(): Flow<List<SyncOutboxEntity>> =
        flowOf(rowsById.values.filter { it.deadLettered }.sortedBy { it.createdAt })

    override fun observeOutboxSize(): Flow<Int> = flowOf(rowsById.values.count { !it.deadLettered })

    fun countForClientUuid(clientUuid: String): Int = rowsById.values.count { it.clientUuid == clientUuid }

    fun rowFor(entityType: String, clientUuid: String): SyncOutboxEntity? =
        rowsById.values.firstOrNull { it.entityType == entityType && it.clientUuid == clientUuid }

    fun allRows(): List<SyncOutboxEntity> = rowsById.values.toList()
}

/** In-memory stand-in for Room's generated `TripDao` impl. */
private class FakeTripDao : TripDao {
    private val rows = mutableMapOf<String, TripEntity>()

    override suspend fun insert(trip: TripEntity) {
        check(trip.clientUuid !in rows) { "duplicate clientUuid=${trip.clientUuid}" }
        rows[trip.clientUuid] = trip
    }

    override suspend fun rewriteShiftId(localShiftId: String, serverShiftId: String) {
        for ((uuid, trip) in rows.toMap()) {
            if (trip.shiftId == localShiftId) rows[uuid] = trip.copy(shiftId = serverShiftId)
        }
    }

    fun shiftIdOf(clientUuid: String): String? = rows[clientUuid]?.shiftId

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

    // Added 2026-08-29 alongside the OutboxDrainerTest compile-fix pass — real TripDao member,
    // not exercised by this test (OutboxDrainer never calls it), but a fake implementing an
    // interface must implement every member regardless of whether the test under scope reaches it.
    override fun observeRecentTrips(openStatus: String, limit: Int): Flow<List<TripEntity>> =
        flowOf(rows.values.filter { it.status != openStatus }.sortedByDescending { it.startAt }.take(limit))

    // Added Phase C (2026-09-03, History/Earnings real-data pass) alongside the new
    // TripDao.observeTripsInRange query — same "not exercised by this test, but a fake must
    // implement every interface member" note as observeRecentTrips above.
    override fun observeTripsInRange(sinceEpochMillis: Long, beforeEpochMillis: Long?, openStatus: String): Flow<List<TripEntity>> =
        flowOf(
            rows.values.filter {
                it.status != openStatus && it.createdAt >= sinceEpochMillis && (beforeEpochMillis == null || it.createdAt < beforeEpochMillis)
            }.sortedByDescending { it.startAt },
        )
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
    var rejectAllTrips: Boolean = false
    var callCount: Int = 0
        private set

    private val storedByClientUuid = mutableMapOf<String, TripDto>()

    /** Every trip payload the drainer has actually put on the wire, so a test can assert what the
     * server would have seen — specifically, that no trip arrived carrying a `local:` shift id. */
    val tripPayloadLog = mutableListOf<TripSyncItemDto>()

    fun storedTripCount(): Int = storedByClientUuid.size

    override suspend fun syncTrips(body: List<TripSyncItemDto>): TripSyncResponseDto {
        // Offline calls never "reach" the fake server at all, matching real
        // network semantics — callCount is a count of successful contacts.
        if (!networkUp) throw IOException("simulated offline")
        // Reachable, but refusing. Distinct from `networkUp = false` on purpose: S1 is about the
        // row the server ANSWERS about and rejects identically every time, which is what made
        // unbounded retry so useless.
        if (rejectAllTrips) throw IllegalStateException("422 unprocessable")
        callLog += "syncTrips"
        callCount++
        tripPayloadLog += body
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
    override suspend fun refresh(body: RefreshRequestDto): RefreshResponseDto = notUsed()
    override suspend fun me(): UserDto = notUsed()
    override suspend fun logout(): Unit = notUsed()
    override suspend fun registerDevice(body: DeviceRegisterRequestDto): DeviceDto = notUsed()
    // deviceSecret param added 2026-08-29 (device-scoped heartbeat auth pass) — see
    // ApiService.deviceHeartbeat's own doc; not exercised by this test, default kept.
    override suspend fun deviceHeartbeat(deviceId: String, body: DeviceHeartbeatRequestDto, deviceSecret: String?): DeviceDto = notUsed()
    override suspend fun deviceLocateResponse(deviceId: String, body: DeviceLocateResponseDto, deviceSecret: String?): DeviceDto = notUsed()
    override suspend fun deviceCommandAck(deviceId: String, body: DeviceCommandAckDto, deviceSecret: String?): DeviceDto = notUsed()
    override suspend fun activeTariff(region: String, at: String?): TariffDto = notUsed()
    override suspend fun currentFaresOrder(region: String, at: String?): TariffDto = notUsed()
    override suspend fun createTrip(body: TripCreateDto): TripDto = notUsed()
    // shiftId/startAtFrom/startAtTo params added 2026-08-29 (Captain Taxis dashboard pass) — see
    // ApiService.listTrips's own doc; not exercised by this test, defaults kept.
    override suspend fun listTrips(
        status: String?,
        type: String?,
        vehicleId: String?,
        driverId: String?,
        shiftId: String?,
        startAtFrom: String?,
        startAtTo: String?,
        skip: Int,
        limit: Int,
    ): TripListResponseDto = notUsed()
    override suspend fun getTrip(tripId: String): TripDto = notUsed()
    // Added 2026-08-29 alongside the listTrips/deviceHeartbeat signature changes above — same
    // "new interface member, not exercised by this test" situation.
    override suspend fun earningsToday(driverId: String): DriverEarningsTodayReadDto = notUsed()
    // Pre-existing gap (predates every change in this session) surfaced only once the two fixes
    // above got this file compiling far enough to reach it.
    override suspend fun acceptJobOffer(jobId: String, offerId: String): JobOfferDto = notUsed()
    override suspend fun tickTrip(tripId: String, body: TripTickRequestDto): TripDto = notUsed()
    override suspend fun closeTrip(tripId: String, body: TripCloseRequestDto): TripDto = notUsed()
    /** Records the shift starts the drainer posts, and answers with a server-issued id, so the S3
     * ordering test can assert both that the shift went first and what the trips were rewritten to.
     * Honours `client_uuid` idempotency: a repeat returns the same shift, as the real endpoint does. */
    val startedShiftsByClientUuid = mutableMapOf<String, ShiftDto>()
    var shiftStartFailure: Exception? = null
    val callLog = mutableListOf<String>()

    override suspend fun startShift(body: ShiftStartDto): ShiftDto {
        // Same offline semantics as syncTrips: an offline call never reaches the fake server at
        // all. Without this, a "shift started offline" test would silently start it online.
        if (!networkUp) throw IOException("simulated offline")
        callLog += "startShift"
        shiftStartFailure?.let { throw it }
        val key = body.clientUuid ?: error("drainer must send client_uuid")
        return startedShiftsByClientUuid.getOrPut(key) {
            fakeShiftDto(id = "srv-shift-${startedShiftsByClientUuid.size + 1}", body = body)
        }
    }

    private fun fakeShiftDto(id: String, body: ShiftStartDto): ShiftDto = ShiftDto(
        id = id,
        tenantId = "tenant-1",
        driverId = body.driverId,
        vehicleId = body.vehicleId,
        startAt = "2026-09-08T00:00:00Z",
        endAt = null,
        inspectionJson = body.inspectionJson,
        tripsCount = 0,
        kmTotal = "0",
        cashTotal = "0",
        cardTotal = "0",
        pslOwed = "0",
        reconciled = false,
        createdAt = "2026-09-08T00:00:00Z",
        updatedAt = "2026-09-08T00:00:00Z",
    )
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

    // --- Full-interface compile fix, 2026-08-29: FakeApiService had drifted to implementing only
    // 41 of ApiService's 58 members (confirmed by diffing the two), predating every change in this
    // session — each of several past passes added just the one or two methods it happened to
    // touch and left the rest. None of these 37 are exercised by OutboxDrainerTest (it only ever
    // calls syncTrips on this fake); every one throws via notUsed(), matching the existing
    // convention for every other unused override above. Signatures copied verbatim from
    // ApiService.kt's real declarations, not guessed. ---
    override suspend fun driverLogin(body: DriverLoginRequestDto): DriverLoginResponseDto = notUsed()
    override suspend fun mfaLogin(body: MfaLoginRequestDto): TokenResponseDto = notUsed()
    override suspend fun verifyAdminPin(deviceId: String, body: VerifyAdminPinRequestDto): VerifyAdminPinResponseDto = notUsed()
    override suspend fun setDriverAvailability(body: DriverAvailabilityUpdateDto): DriverAvailabilityDto = notUsed()
    override suspend fun createJob(body: JobCreateDto): JobDto = notUsed()
    override suspend fun listJobs(status: String?, skip: Int, limit: Int): JobListResponseDto = notUsed()
    override suspend fun getJob(jobId: String): JobDto = notUsed()
    override suspend fun cancelJob(jobId: String): JobDto = notUsed()
    override suspend fun listJobOffers(jobId: String): List<JobOfferDto> = notUsed()
    override suspend fun declineJobOffer(jobId: String, offerId: String): JobOfferDto = notUsed()
    override suspend fun publishPosition(body: PositionPublishRequestDto): PositionPublishResponseDto = notUsed()
    override suspend fun listVehicles(skip: Int, limit: Int): VehiclePageDto = notUsed()
    override suspend fun getShift(shiftId: String): ShiftDto = notUsed()
    override suspend fun flagTrip(tripId: String, body: TripFlagRequestDto): TripDto = notUsed()
    override suspend fun rateTrip(tripId: String, body: TripRatingCreateDto): TripRatingDto = notUsed()
    override suspend fun emailReceipt(tripId: String, body: ReceiptEmailRequestDto): ReceiptEmailResponseDto = notUsed()
    override suspend fun smsReceipt(tripId: String, body: ReceiptSmsRequestDto): ReceiptSmsResponseDto = notUsed()
    override suspend fun suggestTariff(lat: Double, lng: Double, vehicleClass: String?): TariffSuggestionDto = notUsed()
    override suspend fun tariffPresets(): List<TariffPresetDto> = notUsed()
    override suspend fun tariffSigningPublicKey(): TariffSigningPublicKeyDto = notUsed()
    override suspend fun triggerDuress(body: DuressTriggerRequestDto): DuressEventDto = notUsed()
    override suspend fun cancelDuress(eventId: String, body: DuressCancelRequestDto): DuressEventDto = notUsed()
    override suspend fun getDuressEvent(eventId: String): DuressEventDto = notUsed()
    override suspend fun postDuressGps(eventId: String, body: DuressGpsPointDto) = notUsed()
    override suspend fun uploadDuressAudio(eventId: String, file: MultipartBody.Part): DuressEventDto = notUsed()
    override suspend fun uploadDuressSnapshot(eventId: String, file: MultipartBody.Part, capturedAt: String?): DuressSnapshotDto = notUsed()
    override suspend fun listVouchers(redeemed: Boolean?, skip: Int, limit: Int): VoucherPageDto = notUsed()
    override suspend fun listCorporateAccounts(active: Boolean?, skip: Int, limit: Int): CorporateAccountPageDto = notUsed()
    override suspend fun complianceExpiry(skip: Int, limit: Int): ComplianceExpiryPageDto = notUsed()
    override suspend fun getComplianceDossier(vehicleId: String): ComplianceDossierDto = notUsed()
    override suspend fun fatigueAlerts(skip: Int, limit: Int): FatigueAlertPageDto = notUsed()
    override suspend fun listMessages(driverId: String, skip: Int, limit: Int): MessageListResponseDto = notUsed()
    override suspend fun sendMessage(body: MessageCreateDto): MessageDto = notUsed()
    override suspend fun markMessageRead(messageId: String): MessageDto = notUsed()
    override suspend fun listMessageTemplates(): List<MessageTemplateDto> = notUsed()
    override suspend fun sendTemplateMessage(code: String, body: TemplateMessageCreateDto): MessageDto = notUsed()
    override suspend fun listZones(skip: Int, limit: Int): ZoneListResponseDto = notUsed()
    override suspend fun plotIntoZone(zoneId: String): ZonePlotReadDto = notUsed()
    override suspend fun unplotZone(): ZonePlotReadDto = notUsed()
    override suspend fun zoneStats(): List<ZoneStatsDto> = notUsed()

    // Driver engagement (`/v1/me/wallet|rating|announcements|incentives`, 2026-09-04) — read by
    // DriverEngagementRepository only, never by the outbox drainer under test here.
    override suspend fun myWallet(limit: Int): au.com.threesixty.cabdispatch.data.remote.WalletDto = notUsed()
    override suspend fun myRating(limit: Int): au.com.threesixty.cabdispatch.data.remote.RatingDto = notUsed()
    override suspend fun myAnnouncements(): au.com.threesixty.cabdispatch.data.remote.AnnouncementListDto = notUsed()
    override suspend fun myIncentives(): au.com.threesixty.cabdispatch.data.remote.IncentiveProgressListDto = notUsed()

    // App releases (real OTA self-update, 2026-09-06) — read by AppUpdateChecker only, never by
    // the outbox drainer under test here.
    override suspend fun latestAppRelease(): au.com.threesixty.cabdispatch.data.remote.LatestAppReleaseDto = notUsed()

    // NSW toll-road registry (automatic toll detection, 2026-09) — pulled by
    // TollRegistryCache on its own schedule, never by the outbox drainer under test here.
    override suspend fun tollRoads(): List<au.com.threesixty.cabdispatch.data.remote.TollRoadDto> = notUsed()
    override suspend fun tollRoadDetail(roadId: String): au.com.threesixty.cabdispatch.data.remote.TollRoadDetailDto = notUsed()

    private fun notUsed(): Nothing = throw UnsupportedOperationException("not exercised by this test")
}
