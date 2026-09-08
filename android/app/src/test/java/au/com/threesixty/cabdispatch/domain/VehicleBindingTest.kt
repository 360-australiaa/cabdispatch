package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.VehicleDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Covers the two decisions in `VehicleBinding.kt` that have real edge cases: which roster row a
 * driver-typed rego means, and which publish failures mean the binding itself is wrong.
 *
 * The recovery loop that consumes both lives in [LivePositionHeartbeat] and is not covered here —
 * it is a coroutine over a Retrofit interface with no fake in this codebase, and inventing one for
 * a single loop would be a bigger, less honest test than the loop deserves. What IS pinned is that
 * a 404 (and only a 404) triggers it, and that the lookup it then performs matches the way a driver
 * actually types a rego.
 */
class VehicleBindingTest {

    private val roster = listOf(
        VehicleDto(id = "uuid-khi", rego = "KHI-01"),
        VehicleDto(id = "uuid-ghp", rego = "GHP-1"),
    )

    private fun httpError(code: Int) =
        HttpException(Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())))

    @Test
    fun `matches an exact rego`() {
        assertEquals("uuid-ghp", matchVehicleUuid(roster, "GHP-1"))
    }

    @Test
    fun `matches regardless of case`() {
        assertEquals("uuid-khi", matchVehicleUuid(roster, "khi-01"))
    }

    @Test
    fun `ignores whitespace either side, as typed on a tablet keyboard`() {
        assertEquals("uuid-khi", matchVehicleUuid(roster, "  KHI-01 "))
    }

    @Test
    fun `a rego nobody in the fleet answers to resolves to nothing`() {
        assertNull(matchVehicleUuid(roster, "T99001"))
    }

    /** The guard that stops an empty bind field silently adopting the first car on the roster. */
    @Test
    fun `a blank rego matches nothing rather than the first row`() {
        assertNull(matchVehicleUuid(roster, ""))
        assertNull(matchVehicleUuid(roster, "   "))
    }

    @Test
    fun `an empty roster matches nothing`() {
        assertNull(matchVehicleUuid(emptyList(), "KHI-01"))
    }

    /** The fleet-wipe case: the rego survives, the uuid behind it does not. */
    @Test
    fun `the same rego can resolve to a new uuid after a fleet is re-seeded`() {
        val reseeded = listOf(VehicleDto(id = "uuid-khi-v2", rego = "KHI-01"))
        assertEquals("uuid-khi-v2", matchVehicleUuid(reseeded, "KHI-01"))
    }

    @Test
    fun `404 means the server does not know this vehicle`() {
        assertEquals(PositionPublishOutcome.UNKNOWN_VEHICLE, classifyPublishError(httpError(404)))
    }

    /** An expired token is refreshed by the auth interceptor; it says nothing about the binding. */
    @Test
    fun `401 is transport, not a broken binding`() {
        assertEquals(PositionPublishOutcome.TRANSPORT_FAILURE, classifyPublishError(httpError(401)))
    }

    @Test
    fun `5xx is transport, not a broken binding`() {
        assertEquals(PositionPublishOutcome.TRANSPORT_FAILURE, classifyPublishError(httpError(503)))
    }

    /** The one that matters most in a taxi: no signal must never discard a correct binding. */
    @Test
    fun `an offline device is transport, not a broken binding`() {
        assertEquals(
            PositionPublishOutcome.TRANSPORT_FAILURE,
            classifyPublishError(IOException("Unable to resolve host")),
        )
    }
}
