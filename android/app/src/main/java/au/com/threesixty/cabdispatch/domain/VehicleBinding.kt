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
 * ### The pagination caveat, now closed (B5, 2026-09-08)
 * This used to carry the same "a tenant with more than one page of vehicles can have a rego this
 * never finds" caveat as every other caller of [ApiService.listVehicles]. B5 added `?rego_exact=`
 * for exactly this call site — a case-insensitive EXACT match returning 0 or 1 row, so the answer
 * no longer depends on the wanted car happening to fall inside the first 100-row window.
 *
 * [matchVehicleUuid] is still applied to whatever comes back, and that is deliberate rather than
 * redundant: a backend older than B5 ignores the unknown query parameter and answers with the
 * ordinary first page, in which case this degrades to precisely its previous behaviour instead of
 * failing. No capability probe is needed for that — an ignored filter is not an error.
 */
class ApiVehicleUuidResolver(private val apiService: ApiService) : VehicleUuidResolver {
    override suspend fun resolve(rego: String): String? =
        runCatching { apiService.listVehicles(regoExact = rego.trim()) }
            .getOrNull()
            ?.let { matchVehicleUuid(it.items, rego) }
}

/**
 * Whether the fleet's own record of which car this tablet sits in should replace the binding the
 * driver's session is currently holding — and if so, with what.
 *
 * ### Why this exists
 * [DeviceCommandHeartbeat]'s 60 s poll has always received `DeviceDto.vehicle_id` — the fleet's
 * *device* row's own vehicle binding, set by an admin on the dashboard — and always threw it away
 * unread. That left the self-heal channel this file's class doc describes half-built: the app could
 * notice a binding had gone stale (a `404` off `POST /v1/fleet/positions`) and re-resolve it from
 * the roster by rego, but it could not be *told* the right answer by the depot that actually knows
 * it. A wipe-and-reseed changes both halves at once, and a re-resolve by rego only recovers when
 * the new car kept the old rego. Reading `vehicle_id` closes that: the tablet adopts the depot's
 * answer on the next poll, with no driver action and no re-bind at login.
 *
 * Pure, and here rather than inside the heartbeat, for the same reason [matchVehicleUuid] is: this
 * is the part with real edge cases, and the coroutine around it has none worth a fake Retrofit
 * interface — the same split [KioskLockController.decideAction] already uses.
 *
 * ### The three cases that must NOT rebind
 * - **No session.** A parked, logged-off tablet has no [DriverSession] to correct. The heartbeat
 *   still runs (that is the whole point of the device secret), but there is nothing to write into.
 * - **`reported == null`.** The device row exists but the depot has not bound it to a vehicle.
 *   That is "unknown", not "no vehicle" — dropping a working binding the driver established at
 *   login because an admin never filled the device's vehicle field in would take a car OFF the
 *   Live Map, which is the exact failure this file exists to prevent, just from the other side.
 * - **Already equal.** No write, so [SessionHolder]'s durable store is not churned on every tick
 *   and nothing downstream re-collects a session that did not actually change.
 *
 * Note what is deliberately NOT compared: [DriverSession.vehicleId], the driver-typed/QR'd rego.
 * The device row carries a UUID, not a rego, and nothing here can turn one into the other without
 * the roster call [ApiVehicleUuidResolver] makes. The rego stays exactly as the driver entered it —
 * it is what every display and every rego-keyed API call still uses — and only
 * [DriverSession.vehicleUuid], the field `POST /v1/fleet/positions` actually 404s on, is healed.
 *
 * @return the UUID to adopt, or `null` for "leave the session alone".
 */
fun decideVehicleRebind(current: DriverSession?, reportedVehicleUuid: String?): String? {
    if (current == null) return null
    val reported = reportedVehicleUuid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (reported == current.vehicleUuid) null else reported
}
