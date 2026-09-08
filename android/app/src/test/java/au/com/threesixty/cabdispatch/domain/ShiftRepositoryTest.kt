package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.ShiftDto
import au.com.threesixty.cabdispatch.data.remote.ShiftStartDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Covers the urgent handover-conflict fix on `POST /v1/shifts/start` (409 with a
 * [au.com.threesixty.cabdispatch.data.remote.ShiftConflictDetail] body): a driver checking in on
 * a vehicle another driver's shift is still open on used to see a raw "HTTP 409 Conflict" with no
 * path forward. [OutboxBackedShiftRepository.startShift] now parses that body into a typed
 * [ShiftHandoverConflictException], and sends `force_handover = true` back through unchanged when
 * asked to.
 *
 * Fakes follow [au.com.threesixty.cabdispatch.data.repository.TripRepositoryOfflineTest]'s
 * convention (hand-rolled in-memory implementations of narrow interfaces, no Room) and
 * [DriverAuthRepositoryTest]'s convention for [ApiService] (a reflection proxy that throws on any
 * member this test doesn't configure, so a test that starts depending on an unconfigured call
 * fails loudly rather than silently no-opping) and for building a real [HttpException] with a
 * real JSON error body via `Response.error`.
 */
class ShiftRepositoryTest {

    // ========================================================================
    // 409 handover conflict — parsing
    // ========================================================================

    @Test
    fun `a 409 with a well-formed conflict body parses into ShiftHandoverConflictException`() = runTest {
        val api = FakeShiftApi().apply { failure = httpException(409, WELL_FORMED_CONFLICT_BODY) }
        val outbox = FakeOutboxPort()
        val repo = OutboxBackedShiftRepository(api, outbox)

        val result = repo.startShift(DRIVER_ID, VEHICLE_ID, emptyMap())

        assertTrue("a 409 conflict must fail, not succeed", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "must surface as the typed conflict, not the raw HttpException",
            error is ShiftHandoverConflictException,
        )
        val conflict = (error as ShiftHandoverConflictException).conflict
        assertEquals("shift-99", conflict.conflictingShiftId)
        assertEquals("driver-2", conflict.conflictingDriverId)
        assertEquals("Jane Smith", conflict.conflictingDriverName)
        assertEquals("2026-09-08T20:00:00Z", conflict.conflictingShiftStartAt)
        assertEquals(
            "This vehicle already has an open shift for Jane Smith, started 2026-09-08T20:00:00Z. " +
                "Set force_handover=true to end their shift and start yours.",
            conflict.message,
        )
        // Same disposal as any other refused (non-IOException) attempt — retrying without
        // force_handover would get the identical 409, so the queued row must not be left behind.
        assertTrue("the queued outbox row must be cleaned up on a refusal", outbox.rows.isEmpty())
    }

    @Test
    fun `a 409 with a malformed body falls back to the generic failure without crashing`() = runTest {
        val api = FakeShiftApi().apply { failure = httpException(409, "not json at all") }
        val repo = OutboxBackedShiftRepository(api, FakeOutboxPort())

        val result = repo.startShift(DRIVER_ID, VEHICLE_ID, emptyMap())

        assertTrue(result.isFailure)
        assertFalse(
            "an unparseable body must not be reported as a conflict",
            result.exceptionOrNull() is ShiftHandoverConflictException,
        )
        assertTrue("it must fall back to the original HttpException", result.exceptionOrNull() is HttpException)
    }

    @Test
    fun `a 409 whose detail is a plain string, not a conflict object, also falls back cleanly`() = runTest {
        // Older/other 409s on this API (e.g. DevicePairingRepository's own 409) carry a bare
        // string detail, not an object -- must not crash or be mistaken for a handover conflict.
        val api = FakeShiftApi().apply {
            failure = httpException(409, """{"detail": "some unrelated conflict"}""")
        }
        val repo = OutboxBackedShiftRepository(api, FakeOutboxPort())

        val result = repo.startShift(DRIVER_ID, VEHICLE_ID, emptyMap())

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is ShiftHandoverConflictException)
    }

    @Test
    fun `a non-409 4xx is never mistaken for a handover conflict`() = runTest {
        val api = FakeShiftApi().apply { failure = httpException(422, WELL_FORMED_CONFLICT_BODY) }
        val repo = OutboxBackedShiftRepository(api, FakeOutboxPort())

        val result = repo.startShift(DRIVER_ID, VEHICLE_ID, emptyMap())

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is ShiftHandoverConflictException)
    }

    // ========================================================================
    // force_handover — sent through on request
    // ========================================================================

    @Test
    fun `force_handover=true is actually sent on the retry call`() = runTest {
        val api = FakeShiftApi().apply { response = shiftDto() }
        val repo = OutboxBackedShiftRepository(api, FakeOutboxPort())

        val result = repo.startShift(DRIVER_ID, VEHICLE_ID, emptyMap(), forceHandover = true)

        assertTrue(result.isSuccess)
        assertTrue("the retry must carry force_handover=true", api.lastRequest!!.forceHandover)
    }

    @Test
    fun `force_handover defaults to false for a plain start`() = runTest {
        val api = FakeShiftApi().apply { response = shiftDto() }
        val repo = OutboxBackedShiftRepository(api, FakeOutboxPort())

        repo.startShift(DRIVER_ID, VEHICLE_ID, emptyMap())

        assertFalse("an ordinary start must not force a handover", api.lastRequest!!.forceHandover)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun shiftDto() = ShiftDto(
        id = "shift-1",
        tenantId = "tenant-1",
        driverId = DRIVER_ID,
        vehicleId = VEHICLE_ID,
        startAt = "2026-09-09T00:00:00Z",
        endAt = null,
        inspectionJson = emptyMap(),
        tripsCount = 0,
        kmTotal = "0",
        cashTotal = "0",
        cardTotal = "0",
        pslOwed = "0",
        reconciled = false,
        createdAt = "2026-09-09T00:00:00Z",
        updatedAt = "2026-09-09T00:00:00Z",
    )

    private fun httpException(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
    )

    private class FakeOutboxPort : OutboxBackedShiftRepository.ShiftOutboxPort {
        val rows = mutableMapOf<String, String>()

        override suspend fun queueShiftStart(clientUuid: String, payloadJson: String, createdAt: Long) {
            rows[clientUuid] = payloadJson
        }

        override suspend fun deleteShiftStart(clientUuid: String) {
            rows.remove(clientUuid)
        }
    }

    /**
     * Only [ApiService.startShift] is exercised here; every other member throws — same
     * `throwingApiService()` convention [DriverAuthRepositoryTest.FakeAuthApi] uses for
     * [ApiService.driverLogin], applied to this endpoint instead.
     */
    private class FakeShiftApi : ApiService by throwingApiService() {
        var response: ShiftDto? = null
        var failure: Exception? = null

        /** The last body actually sent, so a test can assert what went on the wire (force_handover). */
        var lastRequest: ShiftStartDto? = null
            private set

        override suspend fun startShift(body: ShiftStartDto): ShiftDto {
            lastRequest = body
            failure?.let { throw it }
            return response ?: error("test did not configure a response")
        }
    }

    private companion object {
        const val DRIVER_ID = "driver-1"
        const val VEHICLE_ID = "veh-1"

        const val WELL_FORMED_CONFLICT_BODY = """
            {
              "detail": {
                "message": "This vehicle already has an open shift for Jane Smith, started 2026-09-08T20:00:00Z. Set force_handover=true to end their shift and start yours.",
                "conflicting_shift_id": "shift-99",
                "conflicting_driver_id": "driver-2",
                "conflicting_driver_name": "Jane Smith",
                "conflicting_shift_start_at": "2026-09-08T20:00:00Z"
              }
            }
        """

        fun throwingApiService(): ApiService = java.lang.reflect.Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("ApiService.${method.name} not exercised by this test")
        } as ApiService
    }
}
