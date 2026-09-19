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
     * The server does not know this vehicle id (`404`). This outcome says the binding itself is
     * wrong, and is worth spending a roster lookup on.
     */
    UNKNOWN_VEHICLE,

    /**
     * The server knows this vehicle but will not accept a position for it from this tablet
     * (`403`). As of the 2026-09 backend, `POST /v1/fleet/positions` answers `403` for exactly one
     * situation: ANOTHER driver holds an open shift on the vehicle this tablet is publishing for.
     * Rare, but real — a relief driver who signed on before the previous one submitted their
     * shift, or a tablet moved between cars without re-binding.
     *
     * Kept distinct from [UNKNOWN_VEHICLE] because the two mean different things and deserve
     * different words in the log: `404` is "this uuid does not exist any more" (a fleet wipe),
     * `403` is "this uuid exists and is not yours" (a roster/shift conflict). Kept distinct from
     * [TRANSPORT_FAILURE] because *that* classification is the defect this outcome exists to fix
     * — see [classifyPublishError]'s own doc, "The 403 hole" (2026-09-19).
     */
    NOT_MY_VEHICLE,
}

/**
 * Reads a publish failure as an outcome.
 *
 * A `401` is an expired token (handled by AppContainer's authenticator, retried transparently), a
 * `422` would be a malformed body, and anything else — including no HTTP response at all — is
 * transport. Treating any of those as "the vehicle is gone" would throw away a correct binding over
 * a flat-spot in the mobile signal.
 *
 * ### The 403 hole (closed 2026-09-19)
 * Until this pass every non-`404` status fell into [PositionPublishOutcome.TRANSPORT_FAILURE],
 * whose contract is literally "try again next tick, the binding is not implicated" — and
 * `LivePositionHeartbeat.publishOnce` swallowed the whole thing inside a `runCatching`. So a tablet
 * that `403`s on EVERY heartbeat for a whole shift was indistinguishable, from inside the app,
 * from a perfectly healthy one: no log line, no UI, no alert, no recovery, and a dispatcher looking
 * at a Live Map with a car missing from it and no way to find out why. A permanent condition was
 * being classified as a transient one. [NOT_MY_VEHICLE] separates them so the heartbeat can at
 * minimum say so out loud (logging is the only signal that class has — it is deliberately silent to
 * the driver, see its "Failure handling" doc) and re-resolve the binding, throttled by
 * [RebindThrottle].
 */
fun classifyPublishError(error: Throwable): PositionPublishOutcome =
    when ((error as? HttpException)?.code()) {
        HTTP_NOT_FOUND -> PositionPublishOutcome.UNKNOWN_VEHICLE
        HTTP_FORBIDDEN -> PositionPublishOutcome.NOT_MY_VEHICLE
        else -> PositionPublishOutcome.TRANSPORT_FAILURE
    }

private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/**
 * How often to re-attempt a rego to UUID lookup while the binding is missing or rejected.
 *
 * Deliberately far slower than any `HeartbeatCadence` tier: this is a whole fleet-roster fetch, and
 * the conditions it recovers from (no signal at bind time, a fleet re-seeded mid-shift, another
 * driver's shift still open on this car) resolve on the scale of minutes, not seconds. 60s means a
 * wiped-and-restored fleet is back on the dispatcher's map within a minute, without a roster fetch
 * every 5 seconds all shift.
 *
 * Lives here rather than in `LivePositionHeartbeat`'s companion (where it used to) because
 * [RebindThrottle], the thing that now enforces it, lives here with the rest of the binding logic.
 */
internal const val REBIND_INTERVAL_MS = 60_000L

/**
 * The [REBIND_INTERVAL_MS] gate on roster lookups, as a tiny piece of state with an injected clock.
 *
 * ### Why this had to become a real object
 * `LivePositionHeartbeat.publishIfDue` used to answer `UNKNOWN_VEHICLE` by calling the roster
 * resolver *immediately, every single time*. That was survivable only because of a second defect:
 * the heartbeat recorded a failed publish as though it had succeeded, so the cadence timer pushed
 * the next attempt 5–120s away and the roster fetches were incidentally spaced out. Fixing that
 * (a failed publish must NOT reset the cadence — see `HeartbeatScheduler.recordAttempt`) removes
 * the accidental spacing, and a tablet 403-ing or 404-ing on every tick would otherwise hammer
 * `GET /v1/fleet/vehicles` at the poll cadence for the rest of the shift. The heartbeat's own
 * failure backoff already limits publish attempts; this limits the much more expensive roster
 * fetch on top of that, at the interval the class doc has claimed all along.
 *
 * Pure and clock-injected, for the same reason [matchVehicleUuid] and [classifyPublishError] are
 * pure: it is the part with an edge case worth pinning in a unit test (`VehicleBindingTest`), and
 * the coroutine around it has none.
 */
internal class RebindThrottle(private val nowMs: () -> Long = System::currentTimeMillis) {

    private var lastAttemptAtMs: Long? = null

    /** `true` when no lookup has been attempted yet (the first `403`/`404` of a shift recovers
     * immediately, as it always did), or when [REBIND_INTERVAL_MS] has elapsed since the last one. */
    fun shouldAttempt(): Boolean {
        val last = lastAttemptAtMs
        return last == null || nowMs() - last >= REBIND_INTERVAL_MS
    }

    /** Records that a roster lookup was just attempted — success or failure alike. A lookup that
     * failed to resolve anything cost exactly as much network as one that succeeded, which is what
     * this interval is rationing. */
    fun recordAttempt() {
        lastAttemptAtMs = nowMs()
    }
}

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
 * [DriverSession.vehicleId], the driver-typed/QR'd rego, is never overwritten by this — it is what
 * every display and every rego-keyed API call still uses. Only [DriverSession.vehicleUuid], the
 * field `POST /v1/fleet/positions` actually 404s on, is ever healed.
 *
 * ### The fourth case that must NOT rebind either (real bug, found live 2026-09-09)
 * This used to adopt [reportedVehicleUuid] unconditionally whenever it disagreed with
 * [DriverSession.vehicleUuid], on the reasoning that the device row's uuid is always the fresher
 * answer. That is only true for the fleet-wipe/reseed case this channel was built for — the SAME
 * car, under a NEW uuid, still wearing the SAME rego the driver bound to. It is not true when the
 * driver has deliberately bound to a DIFFERENT vehicle than this tablet's admin-configured device
 * pairing — a relief/spare tablet, a shared tablet moved between cars, or exactly the scenario
 * `LoginVehicleBindViewModel`'s own "Check tablet placement" warning exists to flag (a shift
 * started on vehicle A while the device row still says vehicle B). That warning's own copy reads
 * "nothing to fix here right now" — but this method was quietly proving it wrong within one 60s
 * heartbeat tick, rebinding the session to vehicle B and reattributing every trip on that shift's
 * billing to a car the driver was never actually in. [reportedVehicleRego] is what tells the two
 * cases apart: adopt the reported uuid when there is no working uuid to protect yet, OR when the
 * reported vehicle's own rego matches [DriverSession.vehicleId] (same car, healed uuid) — never
 * when the regos disagree (a genuinely different car, which is not this channel's business).
 *
 * @param reportedVehicleUuid the fleet device row's own `vehicle_id` (`DeviceDto.vehicleId`).
 * @param reportedVehicleRego that same vehicle's rego (`DeviceDto.vehicleRego`), joined in
 *   server-side — `null` if the backend predates that field or the vehicle has since been deleted;
 *   treated the same as "can't tell", which means "leave the session alone" unless there is no
 *   working uuid yet at all.
 * @return the UUID to adopt, or `null` for "leave the session alone".
 */
fun decideVehicleRebind(
    current: DriverSession?,
    reportedVehicleUuid: String?,
    reportedVehicleRego: String?,
): String? {
    if (current == null) return null
    val reported = reportedVehicleUuid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (reported == current.vehicleUuid) return null
    // Recovery case: nothing to protect yet (an offline bind, or a resolver failure at login) —
    // adopt the depot's answer outright, same as before this pass.
    if (current.vehicleUuid == null) return reported
    // Otherwise only heal when the depot's own vehicle carries the SAME rego the driver bound to
    // — see this function's own doc, "The fourth case" above.
    val rego = reportedVehicleRego?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (rego.equals(current.vehicleId.trim(), ignoreCase = true)) reported else null
}
