package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-signs a driver out of an unattended tablet — owner's own words: "if driver signed in, and
 * there is no activity or we close the tablet... automatically log out."
 *
 * ### The gap this closes
 * A signed-in driver session left on this kiosk tablet — set down at a rank, handed to a relief
 * driver without tapping "Log Off", or simply forgotten — stayed logged in indefinitely, with no
 * path back to the sign-in screen short of a manual log-off. On shared hardware that is a real
 * exposure: whoever picks the tablet up next is the driver of record for anything they do with
 * it.
 *
 * ### What "unattended" means here
 * Tracked as one [lastActiveAt] timestamp, refreshed by exactly two signals, both fed in from
 * [au.com.threesixty.cabdispatch.MainActivity]:
 * - [recordInteraction] — any touch anywhere in the app (`dispatchTouchEvent`, so this catches a
 *   dialog, the on-screen keypad, the map, anywhere a finger actually lands — not only Compose's
 *   own gesture system, which a raw `pointerInput` on one screen's root would miss for content
 *   hosted in a different window, e.g. a system dialog).
 * - [recordForegroundResume] — the app returning to the foreground (`Activity.onStart`). Time
 *   spent with the screen off or the app backgrounded — the owner's own "we close the tablet" —
 *   counts against the SAME budget as time spent untouched in the foreground, rather than needing
 *   a second, independently-tuned timer a driver would have to reason about separately.
 *
 * ### The one case this deliberately never fires on
 * A fare that is [TripStatus.OPEN] (HIRED, actively metering) always resets the clock on every
 * check — see [shouldAutoLogOut]. Signing a driver out mid-fare would stop nothing useful (the
 * meter itself has no concept of "logged in" once a trip is running) while actively confusing a
 * driver whose hands are reasonably on the wheel, not the tablet, for real stretches of a real
 * trip. A driver who is on shift but simply AVAILABLE between jobs is exactly the "walked away"
 * case this exists to catch, and is not exempted the same way.
 *
 * ### What this deliberately does NOT key off
 * [au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat]'s server-reachability signal is
 * NOT a third trigger here, even though "no heartbeat" was the owner's own third example. A
 * tablet with no signal for hours — a tunnel, a dead zone, a whole shift worked offline — is
 * exactly the scenario this app's offline-first meter is built to keep working through (see the
 * outbox/sync architecture throughout this codebase), not a signal that the driver has walked
 * away. Conflating "lost connectivity" with "lost attention" would auto-log-out a driver for
 * driving somewhere with bad reception — the opposite of what "make this software friendly" asked
 * for. A driver actually working offline is touching the tablet (meter dial, navigator, Close &
 * Pay) throughout a real trip regardless, which already keeps [lastActiveAt] fresh through
 * [recordInteraction] with no network involved at all.
 *
 * ### Mechanism
 * Self-supervising, same shape as [LivePositionHeartbeat]/[DeviceCommandHeartbeat]: [start] reacts
 * to [SessionHolder.session] for the rest of the process lifetime with no per-screen wiring. A
 * fresh sign-in (or a foreground resume) resets the clock before the first check can ever fire —
 * this is deliberately conservative about false positives; missing a genuinely-abandoned tablet
 * for one [CHECK_INTERVAL_MS] tick costs nothing, logging a driver out while they are mid-glance
 * at a passenger's phone would be the wrong trade.
 */
class IdleLogoutSupervisor(
    private val scope: CoroutineScope,
    private val tripRepository: TripRepository,
    /** Injectable for tests; defaults to the real wall clock. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = IDLE_LOGOUT_TIMEOUT_MS,
) {
    @Volatile
    private var lastActiveAt: Long = nowMillis()

    /** Call from [au.com.threesixty.cabdispatch.MainActivity.dispatchTouchEvent] — any touch,
     * anywhere in the app. */
    fun recordInteraction() {
        lastActiveAt = nowMillis()
    }

    /** Call from [au.com.threesixty.cabdispatch.MainActivity.onStart] — the app (re)entering the
     * foreground, including the very first launch. */
    fun recordForegroundResume() {
        lastActiveAt = nowMillis()
    }

    /**
     * Begins supervising [SessionHolder.session] for the process lifetime. Call exactly once,
     * from [au.com.threesixty.cabdispatch.data.AppContainer.init] — same "must start
     * unconditionally, not on first property access" reasoning as [LivePositionHeartbeat.start].
     */
    fun start() {
        scope.launch {
            SessionHolder.session.collect { session ->
                if (session == null) return@collect
                // A fresh sign-in must not be judged against however long the PREVIOUS driver's
                // session sat idle before this one started (or against a cold app-process start,
                // where lastActiveAt otherwise defaults to whenever this object was constructed).
                lastActiveAt = nowMillis()
                while (scope.isActive) {
                    delay(CHECK_INTERVAL_MS)
                    val hasActiveFare = tripRepository.observeActiveTrip().first()?.status == TripStatus.OPEN
                    if (hasActiveFare) {
                        // A running fare IS activity — see this class's own "one case this never
                        // fires on" doc. Reset rather than merely skip, so the driver gets a full
                        // fresh timeout budget from the moment the fare actually ends.
                        lastActiveAt = nowMillis()
                        continue
                    }
                    if (shouldAutoLogOut(nowMillis(), lastActiveAt, hasActiveFare, timeoutMillis)) {
                        SessionHolder.clear()
                        // SessionHolder.session emitting null re-enters this collector's lambda
                        // from the top (see the `if (session == null) return@collect` guard above)
                        // and cancels this specific while-loop coroutine — no manual break needed.
                    }
                }
            }
        }
    }

    private companion object {
        /** How often to check — deliberately much finer-grained than [IDLE_LOGOUT_TIMEOUT_MS]
         * itself, so the actual log-out lands within a few seconds of the real timeout rather
         * than a coarse multi-minute polling error. Cheap: one Room read every 30s. */
        const val CHECK_INTERVAL_MS = 30_000L
    }
}

/** See [IdleLogoutSupervisor]'s own doc, "Mechanism". Chosen, not derived from any spec — a
 * plain business/UX judgement call, same flagging convention this codebase uses elsewhere for
 * such a constant (e.g. [LivePositionHeartbeat.HEARTBEAT_INTERVAL_MS]'s own doc). Long enough
 * that a driver glancing away for a minute mid-rank-wait is never caught by it; short enough that
 * a genuinely forgotten tablet does not stay signed in for the rest of a shift. */
const val IDLE_LOGOUT_TIMEOUT_MS = 20 * 60 * 1000L

/**
 * Pure decision: given [lastActiveAtMillis] and the current time [nowMillis], has the tablet been
 * unattended long enough to sign the driver out — never true while [hasActiveFare] is true. See
 * [IdleLogoutSupervisor]'s own class doc for the full reasoning; kept as a free function, no
 * dependency on the supervisor's clock/coroutine machinery, so it is unit-testable on the JVM the
 * same way [au.com.threesixty.cabdispatch.domain.decideVehicleRebind]/
 * [au.com.threesixty.cabdispatch.domain.KioskLockController.decideAction] already are.
 */
fun shouldAutoLogOut(
    nowMillis: Long,
    lastActiveAtMillis: Long,
    hasActiveFare: Boolean,
    timeoutMillis: Long = IDLE_LOGOUT_TIMEOUT_MS,
): Boolean {
    if (hasActiveFare) return false
    return (nowMillis - lastActiveAtMillis) >= timeoutMillis
}
