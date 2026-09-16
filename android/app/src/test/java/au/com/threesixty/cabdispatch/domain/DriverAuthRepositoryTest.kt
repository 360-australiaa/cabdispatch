package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DriverLoginRequestDto
import au.com.threesixty.cabdispatch.data.remote.DriverLoginResponseDto
import au.com.threesixty.cabdispatch.data.remote.UserDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Owner decision, 2026-09-16: every driver sign-in is a genuine, server-verified login — there is
 * no offline PIN cache any more (see [DriverAuthRepository]'s own doc, and the X1/X2 security
 * findings this file used to cover in git history before that date). What is left to assert is
 * simpler and narrower: every kind of failure -- no connectivity, a server rejection, a server
 * outage -- uniformly fails the login with no cache of any kind to fall back to or clear, and the
 * tenant-slug handling a driver-rank tablet actually depends on still works.
 */
class DriverAuthRepositoryTest {

    // ========================================================================
    // Every failure mode fails closed, uniformly -- there is nothing left to fall back to.
    // ========================================================================

    @Test
    fun `no connectivity fails the login`() = runTest {
        val api = FakeAuthApi().apply { failure = IOException("no route to host") }
        val repo = OnlineDriverAuthRepository(api) { "demo-operator" }

        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
    }

    @Test
    fun `a socket timeout fails the login`() = runTest {
        val api = FakeAuthApi().apply { failure = SocketTimeoutException("timed out") }
        val repo = OnlineDriverAuthRepository(api) { "demo-operator" }

        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
    }

    @Test
    fun `a 401 fails the login`() = runTest {
        val api = FakeAuthApi().apply { failure = httpException(401) }
        val repo = OnlineDriverAuthRepository(api) { "demo-operator" }

        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
    }

    @Test
    fun `a 403 fails the login`() = runTest {
        val api = FakeAuthApi().apply { failure = httpException(403) }
        val repo = OnlineDriverAuthRepository(api) { "demo-operator" }

        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
    }

    @Test
    fun `a 500 fails the login`() = runTest {
        val api = FakeAuthApi().apply { failure = httpException(500) }
        val repo = OnlineDriverAuthRepository(api) { "demo-operator" }

        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
    }

    @Test
    fun `a malformed 2xx with neither tokens nor an MFA challenge fails the login`() = runTest {
        val api = FakeAuthApi().apply {
            response = DriverLoginResponseDto(accessToken = null, refreshToken = null, user = null)
        }
        val repo = OnlineDriverAuthRepository(api) { "demo-operator" }

        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
    }

    @Test
    fun `a genuine online success logs the driver in`() = runTest {
        val api = FakeAuthApi().apply { response = successResponse() }
        val repo = OnlineDriverAuthRepository(api) { "demo-operator" }

        val result = repo.login(DRIVER, PIN)

        assertTrue(result is DriverLoginResult.Success)
        assertEquals(DRIVER_NAME, (result as DriverLoginResult.Success).user.name)
    }

    // ========================================================================
    // Tenant slug -- driver codes are unique per tenant, not platform-wide.
    // ========================================================================

    /**
     * Regression, 2026-09-08. `POST /v1/auth/driver-login` requires `tenant_slug` -- driver codes
     * are unique per tenant, not platform-wide -- and the app once sent only `driver_code` and
     * `pin`. Every driver on every tablet got `422 Field required: tenant_slug`; login was
     * impossible.
     *
     * Two things are asserted, and the second matters as much as the first: the request must not
     * be attempted at all, because a 422 naming an internal field is not something a driver
     * standing at a taxi rank can act on. An unpaired tablet should be told it is unpaired.
     */
    @Test
    fun `without a tenant slug the login fails locally and never reaches the API`() = runTest {
        val api = FakeAuthApi()
        val repo = OnlineDriverAuthRepository(api) { null }

        val result = repo.login(DRIVER, PIN)

        assertTrue(result is DriverLoginResult.Failure)
        assertEquals(0, api.loginCallCount)
    }

    @Test
    fun `a blank tenant slug is treated as missing, not sent as an empty field`() = runTest {
        val api = FakeAuthApi()
        val repo = OnlineDriverAuthRepository(api) { "   " }

        assertTrue(repo.login(DRIVER, PIN) is DriverLoginResult.Failure)
        assertEquals(0, api.loginCallCount)
    }

    @Test
    fun `the slug the tablet learned at pairing is what gets sent`() = runTest {
        val api = FakeAuthApi().apply { response = successResponse() }
        val repo = OnlineDriverAuthRepository(api) { "captain-taxis" }

        repo.login(DRIVER, PIN)

        assertEquals("captain-taxis", api.lastLoginRequest?.tenantSlug)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

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
     * starts depending on one fails loudly rather than silently no-opping.
     *
     * The base is a reflection proxy rather than a hand-written stub because [ApiService] has ~100
     * members and this test needs exactly one of them; spelling out ninety-nine `notUsed()`
     * overrides would bury the one line that matters. Throwing from the invocation handler
     * propagates to the caller normally, suspend members included — a suspend function is just a
     * method with a trailing `Continuation` at the JVM level.
     */
    private class FakeAuthApi : ApiService by throwingApiService() {
        var response: DriverLoginResponseDto? = null
        var failure: Exception? = null

        /** How many times the network was actually reached, so a test can assert it was NOT. */
        var loginCallCount: Int = 0
            private set

        /** The last body sent, so a test can assert what the tablet put on the wire. */
        var lastLoginRequest: DriverLoginRequestDto? = null
            private set

        override suspend fun driverLogin(body: DriverLoginRequestDto): DriverLoginResponseDto {
            loginCallCount++
            lastLoginRequest = body
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
    }
}
