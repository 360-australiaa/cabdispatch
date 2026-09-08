package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DriverLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.DriverLoginResponseDto
import au.com.threesixty.cabdispatch.data.remote.UserDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Security findings X1 and X2 — the authorisation bypass in the offline-PIN fallback, and the
 * unsalted hash it compared against.
 *
 * The X1 tests are the ones that matter most in this workstream. The bug was not that offline login
 * existed; it was that the code could not tell "the tablet has no signal" apart from "the depot has
 * revoked this driver", and treated both as grounds to accept a cached PIN. So each branch is
 * asserted explicitly and separately, exactly as the workstream brief requires.
 */
class DriverAuthRepositoryTest {

    // ========================================================================
    // X1 — offline fallback fires ONLY on genuine connectivity failure
    // ========================================================================

    @Test
    fun `offline - a cached driver logs in when the server is unreachable`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi()
        val repo = SharedPreferencesDriverAuthRepository(prefs, api)

        // One successful online login to populate the cache.
        api.response = successResponse()
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Success)

        // Now the tablet loses signal entirely.
        api.failure = IOException("no route to host")
        val result = repo.login(DRIVER, PIN)

        assertTrue("an offline driver must still be able to start work", result is DriverLoginResult.Success)
        assertEquals(DRIVER_NAME, (result as DriverLoginResult.Success).user.name)
    }

    @Test
    fun `offline - a socket timeout also falls back, and a wrong PIN still fails`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi()
        val repo = SharedPreferencesDriverAuthRepository(prefs, api)

        api.response = successResponse()
        repo.login(DRIVER, PIN)

        // SocketTimeoutException is an IOException — the brief calls it out explicitly.
        api.failure = SocketTimeoutException("timed out")
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Success)

        // The cache is not a free pass: the PIN still has to be right.
        assertTrue(repo.login(DRIVER, "999999") is DriverLoginResult.Failure)
    }

    @Test
    fun `revoked - a 401 clears the cached hash and fails the login`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi()
        val repo = SharedPreferencesDriverAuthRepository(prefs, api)

        // The driver has logged in here before, so the tablet holds their credential.
        api.response = successResponse()
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Success)
        assertNotNull("precondition: the cache is populated", prefs.getString("hash_$DRIVER", null))

        // The depot deactivates them. The next login attempt gets a real 401.
        api.failure = httpException(401)
        val result = repo.login(DRIVER, PIN)

        assertTrue("a server rejection must fail the login", result is DriverLoginResult.Failure)
        // ...and the cached credential must be GONE, or airplane mode becomes the bypass.
        assertNull("the cached hash must be cleared", prefs.getString("hash_$DRIVER", null))
        assertNull("the cached user must be cleared", prefs.getString("user_$DRIVER", null))
    }

    @Test
    fun `revoked - a 403 clears the cache too`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi()
        val repo = SharedPreferencesDriverAuthRepository(prefs, api)

        api.response = successResponse()
        repo.login(DRIVER, PIN)

        api.failure = httpException(403)
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
        assertNull(prefs.getString("hash_$DRIVER", null))
    }

    /**
     * The whole point of X1, end to end: the exact sequence in the workstream's OWNER acceptance
     * gate — log in, get deactivated, then go offline and try again.
     *
     * Before the fix the final step SUCCEEDED, forever, on any tablet the driver had ever used.
     */
    @Test
    fun `revoked then offline - the driver cannot get back in`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi()
        val repo = SharedPreferencesDriverAuthRepository(prefs, api)

        // 1. A normal, successful login. The tablet caches the credential.
        api.response = successResponse()
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Success)

        // 2. The depot deactivates the driver; they try again while still online.
        api.failure = httpException(401)
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)

        // 3. They kill Wi-Fi and try once more with the correct PIN.
        api.failure = IOException("airplane mode")
        val offlineAttempt = repo.login(DRIVER, PIN)

        assertTrue(
            "a revoked driver must NOT be able to log in offline — this is the X1 bypass",
            offlineAttempt is DriverLoginResult.Failure,
        )
    }

    @Test
    fun `a 500 fails closed but does not destroy the cached credential`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi()
        val repo = SharedPreferencesDriverAuthRepository(prefs, api)

        api.response = successResponse()
        repo.login(DRIVER, PIN)

        // A server outage is not evidence of authority, so it must not grant a login...
        api.failure = httpException(500)
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)

        // ...but neither is it evidence of revocation, so it must not lock the driver out either.
        assertNotNull("a 5xx must not clear the cache", prefs.getString("hash_$DRIVER", null))
        api.failure = IOException("offline")
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Success)
    }

    @Test
    fun `a malformed 2xx does not fall through to the offline cache`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi()
        val repo = SharedPreferencesDriverAuthRepository(prefs, api)

        api.response = successResponse()
        repo.login(DRIVER, PIN)

        // 2xx, but with neither tokens nor an MFA challenge. Pre-X1 this fell through to the cache
        // "exactly as a network failure would" — but we DID reach the server, so it is not one.
        api.response = DriverLoginResponseDto(accessToken = null, refreshToken = null, user = null)
        api.failure = null
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
    }

    @Test
    fun `with no cached credential at all, an offline login simply fails`() = runTest {
        val repo = SharedPreferencesDriverAuthRepository(FakeSharedPreferences(), FakeAuthApi().apply {
            failure = IOException("offline")
        })
        assertTrue(repo.login("never-seen-here", PIN) is DriverLoginResult.Failure)
    }

    // ========================================================================
    // X2 — PBKDF2 round-trip, and lazy migration off the unsalted SHA-256
    // ========================================================================

    @Test
    fun `X2 - the stored record is salted PBKDF2, not a bare SHA-256 digest`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi().apply { response = successResponse() }
        SharedPreferencesDriverAuthRepository(prefs, api).login(DRIVER, PIN)

        val record = prefs.getString("hash_$DRIVER", null)!!
        assertTrue("must be a self-describing PBKDF2 record", record.startsWith("pbkdf2_sha256$"))
        assertEquals("iterations must be encoded in the record", 4, record.split("$").size)
        assertEquals(OfflinePinHasher.ITERATIONS.toString(), record.split("$")[1])
        assertTrue(OfflinePinHasher.ITERATIONS >= 120_000)
        assertFalse("the pre-X2 unsalted form must be gone", OfflinePinHasher.isLegacy(record))
        assertNotNull("a per-device salt must have been generated", prefs.getString("pin_salt", null))
    }

    @Test
    fun `X2 - hash then verify round-trips, and rejects the wrong PIN`() {
        val salt = OfflinePinHasher.newSalt()
        val record = OfflinePinHasher.hash(DRIVER, PIN, salt)

        assertTrue(OfflinePinHasher.verify(DRIVER, PIN, record))
        assertFalse(OfflinePinHasher.verify(DRIVER, "000000", record))
        // The driver id is bound into the digest, so another driver's PIN doesn't match.
        assertFalse(OfflinePinHasher.verify("other-driver", PIN, record))
    }

    @Test
    fun `X2 - two devices hashing the same PIN produce different records`() {
        // This is what the salt buys: one precomputed table cannot crack a whole fleet.
        val a = OfflinePinHasher.hash(DRIVER, PIN, OfflinePinHasher.newSalt())
        val b = OfflinePinHasher.hash(DRIVER, PIN, OfflinePinHasher.newSalt())
        assertFalse("salted hashes of the same PIN must differ", a == b)
        assertTrue(OfflinePinHasher.verify(DRIVER, PIN, a))
        assertTrue(OfflinePinHasher.verify(DRIVER, PIN, b))
    }

    @Test
    fun `X2 - a malformed record fails verification instead of throwing`() {
        // A corrupted cache entry must fail the login honestly, not crash the login screen.
        assertFalse(OfflinePinHasher.verify(DRIVER, PIN, "pbkdf2_sha256\$not-a-number\$xx\$yy"))
        assertFalse(OfflinePinHasher.verify(DRIVER, PIN, "pbkdf2_sha256\$120000\$!!!\$!!!"))
        assertFalse(OfflinePinHasher.verify(DRIVER, PIN, ""))
    }

    @Test
    fun `X2 - a legacy unsalted entry still works offline, then migrates on the next online login`() = runTest {
        val prefs = FakeSharedPreferences()
        val api = FakeAuthApi()
        val repo = SharedPreferencesDriverAuthRepository(prefs, api)

        // Simulate a tablet provisioned before this pass: the pre-X2 SHA-256(driverId:pin) hex.
        val legacyRecord = legacySha256(DRIVER, PIN)
        prefs.edit()
            .putString("hash_$DRIVER", legacyRecord)
            .putString("user_$DRIVER", USER_JSON)
            .apply()
        assertTrue(OfflinePinHasher.isLegacy(legacyRecord))

        // It must keep working offline — refusing would lock out every already-provisioned driver
        // in exactly the situation offline login exists for.
        api.failure = IOException("offline")
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Success)
        assertTrue(
            "an offline login must NOT upgrade the weak hash",
            OfflinePinHasher.isLegacy(prefs.getString("hash_$DRIVER", null)!!),
        )

        // The next ONLINE login rewrites it at full cost. No migration pass, nothing for the
        // driver to do.
        api.failure = null
        api.response = successResponse()
        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Success)

        val migrated = prefs.getString("hash_$DRIVER", null)!!
        assertFalse("the legacy entry must be gone after an online login", OfflinePinHasher.isLegacy(migrated))
        assertTrue(migrated.startsWith("pbkdf2_sha256$"))
        assertTrue(OfflinePinHasher.verify(DRIVER, PIN, migrated))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun legacySha256(driverId: String, pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest("$driverId:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun successResponse() = DriverLoginResponseDto(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        user = UserDto(
            id = DRIVER,
            tenantId = "tenant-1",
            role = "driver",
            name = DRIVER_NAME,
            email = "d@example.com",
            status = "active",
        ),
    )

    private fun httpException(code: Int): HttpException = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())),
    )

    /**
     * Only [ApiService.driverLogin] is exercised here; every other member throws, so a test that
     * starts depending on one fails loudly rather than silently no-opping — the convention
     * [FakeSharedPreferences] and this repo's other fakes already follow.
     *
     * The base is a reflection proxy rather than a hand-written stub because [ApiService] has ~100
     * members and this test needs exactly one of them; spelling out ninety-nine `notUsed()`
     * overrides (as `OutboxDrainerTest`'s own fake has to, since it implements several) would bury
     * the one line that matters. Throwing from the invocation handler propagates to the caller
     * normally, suspend members included — a suspend function is just a method with a trailing
     * `Continuation` at the JVM level.
     */
    private class FakeAuthApi : ApiService by throwingApiService() {
        var response: DriverLoginResponseDto? = null
        var failure: Exception? = null

        override suspend fun driverLogin(body: DriverLoginRequestDto): DriverLoginResponseDto {
            failure?.let { throw it }
            return response ?: error("test did not configure a response")
        }
    }

    private companion object {
        /** See [FakeAuthApi]. */
        fun throwingApiService(): ApiService = java.lang.reflect.Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("ApiService.${method.name} not exercised by this test")
        } as ApiService

        const val DRIVER = "driver-1"
        const val DRIVER_NAME = "Test Driver"
        const val PIN = "123456"
        const val USER_JSON =
            """{"id":"driver-1","tenant_id":"tenant-1","role":"driver","name":"Test Driver","email":"d@example.com","status":"active"}"""
    }
}
