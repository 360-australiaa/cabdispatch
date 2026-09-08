package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.VehicleDto
import retrofit2.HttpException

/**
 * The rego → fleet-vehicle-UUID binding, and what to do when the server stops recognising it.
 *
 * ### The defect this exists to fix
 * [DriverSession.vehicleUuid] is resolved exactly once, at vehicle-bind time
 * ([au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindViewModel.bindVehicle]), and
 * then persisted by [SessionStore] across process death. That made it permanent in the one case it
 * must not be: when the vehicle it points at stops existing. A depot wiping and re-seeding its
 * fleet gives every car a NEW uuid under the SAME rego, and a tablet holding the old one publishes
 * to `POST /v1/fleet/positions` for the rest of that session and gets `404 Vehicle not found`
 * every single tick — silently, because [LivePositionHeartbeat] swallows publish failures by
 * design. The driver sees a working meter; the dispatcher sees a car that never appears on the
 * Live Map. Observed on the test tablet (2026-09-08): a session still bound to
 * `d5707862-…` for a vehicle deleted in a fleet wipe, reporting nothing for hours.
 *
 * The second, quieter half of the same defect: a bind performed with no network resolves to `null`
 * and, before this pass, [LivePositionHeartbeat] ran no publish loop at all for that session — one
 * momentary lookup failure at login cost the depot visibility of that car for the whole shift.
 *
 * Both are the same missing capability: nothing ever re-resolved the binding. This file adds it,
 * split so the two parts with real edge cases ([matchVehicleUuid], [classifyPublishError]) are
 * pure and unit-tested, and the network call is a one-line interface the heartbeat can be given.
 */

/** How one `POST /v1/fleet/positions` attempt ended. */
enum class PositionPublishOutcome {
    PUBLISHED,

    /** No GPS fix yet — nothing honest to send. Not a failure; the binding is not implicated. */
    NO_FIX,

    /** Offline, timeout, 5xx — try again next tick. The binding is not implicated. */
    TRANSPORT_FAILURE,

    /**
     * The server does not know this vehicle id (`404`). This is the ONLY outcome that says the
     * binding itself is wrong, and the only one worth spending a roster lookup on.
     */
    UNKNOWN_VEHICLE,
}

/**
 * Reads a publish failure as an outcome.
 *
 * Only `404` invalidates a binding. A `401` is an expired token (handled by AppContainer's
 * authenticator, retried transparently), a `422` would be a malformed body, and anything else —
 * including no HTTP response at all — is transport. Treating any of those as "the vehicle is gone"
 * would throw away a correct binding over a flat-spot in the mobile signal.
 */
fun classifyPublishError(error: Throwable): PositionPublishOutcome =
    if ((error as? HttpException)?.code() == HTTP_NOT_FOUND) {
        PositionPublishOutcome.UNKNOWN_VEHICLE
    } else {
        PositionPublishOutcome.TRANSPORT_FAILURE
    }

private const val HTTP_NOT_FOUND = 404

/**
 * Picks the fleet-vehicle UUID for [rego] out of a roster page.
 *
 * Case- and whitespace-insensitive: [rego] is whatever a driver typed into the bind field or a QR
 * code carried, and `"khi-01 "` and `"KHI-01"` are the same car. A blank rego matches nothing
 * rather than the first row — an empty binding must stay empty, not silently adopt a stranger.
 */
fun matchVehicleUuid(vehicles: List<VehicleDto>, rego: String): String? {
    val wanted = rego.trim()
    if (wanted.isEmpty()) return null
    return vehicles.firstOrNull { it.rego.trim().equals(wanted, ignoreCase = true) }?.id
}

/** Looks a rego up against the live fleet roster. `null` for "offline, or no such rego". */
fun interface VehicleUuidResolver {
    suspend fun resolve(rego: String): String?
}

/**
 * The real resolver: `GET /v1/fleet/vehicles`, matched client-side by [matchVehicleUuid].
 *
 * Same no-pagination-loop caveat as every other caller of [ApiService.listVehicles] (see its own
 * doc): a tenant with more than one page of vehicles can have a rego this never finds. Left as it
 * is rather than quietly fixed here, because changing it would change three call sites' behaviour
 * at once and this pass is about the binding going stale, not about fleet size.
 */
class ApiVehicleUuidResolver(private val apiService: ApiService) : VehicleUuidResolver {
    override suspend fun resolve(rego: String): String? =
        runCatching { apiService.listVehicles() }
            .getOrNull()
            ?.let { matchVehicleUuid(it.items, rego) }
}
