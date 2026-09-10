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

    // ---- decideVehicleRebind: the heartbeat's self-heal (A7, 2026-09-08) ----
    //
    // `DeviceCommandHeartbeat` receives the fleet's own device row every 60 s and, until this pass,
    // never read `DeviceDto.vehicle_id` off it. These pin the decision that closes that: which
    // reported vehicle id is worth writing into the driver's session, and — more important — which
    // ones must leave a working binding alone.

    private val session = DriverSession(
        driverId = "driver-1",
        driverName = "A. Driver",
        vehicleId = "KHI-01",
        vehicleUuid = "uuid-khi",
        shiftId = "shift-1",
    )

    /**
     * The field failure, in one test: a session still bound to a vehicle deleted in a fleet wipe,
     * and a depot that has since re-bound the tablet to the re-seeded car UNDER THE SAME REGO.
     * Before this the tablet published to the dead uuid forever ("Location request failed to send
     * — HTTP 404") with no path back short of a driver re-binding at login.
     */
    @Test
    fun `a different vehicle id on the heartbeat rebinds the session when the rego still matches`() {
        assertEquals("uuid-khi-v2", decideVehicleRebind(session, "uuid-khi-v2", "KHI-01"))
        // Case/whitespace-insensitive, same as matchVehicleUuid/bindVehicle.
        assertEquals("uuid-khi-v2", decideVehicleRebind(session, "uuid-khi-v2", " khi-01 "))
    }

    /**
     * The real bug, found live (2026-09-09): a driver's shift+trip explicitly bound to a different
     * vehicle than this tablet's admin-configured device pairing — the exact "Check tablet
     * placement" scenario `LoginVehicleBindViewModel` itself warns about — used to get silently
     * rebound to the device's own paired car within one heartbeat tick, reattributing every trip's
     * billing to a car the driver was never actually in. A working binding to a genuinely different
     * car must never be overwritten just because the device row disagrees.
     */
    @Test
    fun `a different vehicle id is NOT adopted when the reported rego is a different car`() {
        assertNull(decideVehicleRebind(session, "uuid-t22123", "T22123"))
    }

    /** No rego to compare against (an older backend, or the reported vehicle has since been
     * deleted) is "can't tell", not "assume it's fine" — same as a genuinely different rego. */
    @Test
    fun `a different vehicle id is NOT adopted when no rego was reported to compare`() {
        assertNull(decideVehicleRebind(session, "uuid-khi-v2", null))
        assertNull(decideVehicleRebind(session, "uuid-khi-v2", "   "))
    }

    /** The ordinary tick, 1439 times a day: same answer, no write, no store churn — regardless of
     * what rego comes with it, since there is nothing to decide once the uuid already matches. */
    @Test
    fun `the same vehicle id is not a rebind`() {
        assertNull(decideVehicleRebind(session, "uuid-khi", "KHI-01"))
        assertNull(decideVehicleRebind(session, "uuid-khi", null))
    }

    /**
     * The opposite-direction failure this must not cause. An admin who has never filled in the
     * device row's vehicle field leaves `vehicle_id` null; treating that as "no vehicle" would drop
     * a binding the driver established correctly at login and take the car off the Live Map.
     */
    @Test
    fun `a device row with no vehicle bound never clears a working binding`() {
        assertNull(decideVehicleRebind(session, null, null))
        assertNull(decideVehicleRebind(session, "   ", null))
    }

    /** A parked, logged-off tablet still heartbeats on its device secret — there is simply no
     * session to correct, and inventing one is not on the table. */
    @Test
    fun `no session means nothing to rebind`() {
        assertNull(decideVehicleRebind(null, "uuid-khi-v2", "KHI-01"))
    }

    /** A bind performed offline resolves to a null uuid and used to stay null for the whole shift.
     * The heartbeat can now fill it in without the driver doing anything — this recovery case has
     * no working binding to protect, so it adopts the depot's answer regardless of rego. */
    @Test
    fun `a session that never resolved a uuid adopts the depot's answer regardless of rego`() {
        assertEquals("uuid-khi", decideVehicleRebind(session.copy(vehicleUuid = null), "uuid-khi", "KHI-01"))
        assertEquals("uuid-khi", decideVehicleRebind(session.copy(vehicleUuid = null), "uuid-khi", null))
        assertEquals(
            "uuid-t22123",
            decideVehicleRebind(session.copy(vehicleUuid = null), "uuid-t22123", "T22123"),
        )
    }

    /** Only the uuid is ever written back: the rego stays exactly as the driver typed it, and every
     * display and rego-keyed API call still uses that, never the reported one. */
    @Test
    fun `the session's own rego is never overwritten, only the uuid`() {
        val healed = session.copy(vehicleId = "khi-01") // driver typed lowercase
        assertEquals("uuid-khi-v2", decideVehicleRebind(healed, "uuid-khi-v2", "KHI-01"))
    }
}
