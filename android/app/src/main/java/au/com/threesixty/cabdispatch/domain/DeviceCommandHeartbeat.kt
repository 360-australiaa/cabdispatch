package au.com.threesixty.cabdispatch.domain

import android.content.Context
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DeviceHeartbeatRequestDto
import au.com.threesixty.cabdispatch.data.remote.PositionPublishRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Outcome of the most recent attempt to answer an admin's MDM "locate" request — see
 * [DeviceCommandHeartbeat.respondToLocateRequest]. Deliberately a domain type rather than reusing
 * S6's `LocateResponseState`: the loop that produces it now runs for the whole process lifetime,
 * with or without the Settings screen in existence, so the state cannot live in a screen's
 * package. [SettingsViewModel][au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel]
 * maps this 1:1 onto its existing UI type so the S6 tile renders exactly as it always did.
 */
sealed interface LocateOutcome {
    /** No locate request has been seen since this process started. */
    data object None : LocateOutcome
    data object Sent : LocateOutcome
    data object NoFixYet : LocateOutcome

    /**
     * No vehicle identity this app can honestly publish a position for. Covers both "no shift/
     * session at all" (a parked, logged-off tablet) *and* "a session exists but its rego -> fleet
     * UUID lookup never resolved" — see [respondToLocateRequest]'s doc for why publishing against
     * the rego string instead is not a usable fallback.
     */
    data object NoVehicleBound : LocateOutcome
    data class Failed(val message: String) : LocateOutcome
}

/**
 * Last known server-side device state, as read back off the device heartbeat. Everything here is a
 * *report* of what the backend said on the last successful poll — nothing on this device can write
 * any of these flags back (see [DeviceCommandHeartbeat]'s doc, "No client-side clear").
 *
 * In-memory, but no longer only in-memory: [kioskLocked] and [forceUpdatePending] are mirrored into
 * [DevicePairingStore] on every successful poll and seeded back in [DeviceCommandHeartbeat.start],
 * so a reboot resumes the last commanded state instead of the defaults below. [lastPollSucceeded]
 * and [locate] are deliberately *not* persisted — they describe this process's own attempts, and a
 * restored "Sent" would be a claim about a request that may no longer exist.
 */
data class DeviceCommandState(
    /** `null` until this tablet has been paired (`POST /v1/fleet/devices/register`). While null no
     * poll runs at all, so every other field here is meaningless — consumers must branch on this
     * first, exactly as S6's "device not paired, tap to pair" tile does. */
    val deviceId: String? = null,
    /** `null` = no poll has completed yet this process; `true`/`false` = the last poll succeeded /
     * failed. Distinguishes "we have not asked yet" from "we asked and could not reach the server",
     * so no consumer can present a stale default as a confirmed server answer. */
    val lastPollSucceeded: Boolean? = null,
    /** `Device.kiosk_locked`. Enforced app-wide in [au.com.threesixty.cabdispatch.MainActivity] —
     * Android *screen pinning*, not Device-Owner kiosk mode. See that file's doc for exactly what
     * that does and does not guarantee. */
    val kioskLocked: Boolean = false,
    /** `Device.force_update_pending`. Surfaced to the driver as a persistent, non-blocking banner
     * ([au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner]) — this app has no
     * self-update path whatsoever; see [DeviceCommandHeartbeat]'s "Force update" section. */
    val forceUpdatePending: Boolean = false,
    val locate: LocateOutcome = LocateOutcome.None,
)

/**
 * Polls `POST /v1/fleet/devices/{deviceId}/heartbeat` for the process lifetime and *acts* on the
 * MDM command flags the response carries back.
 *
 * ### Why polling — the backend has no push channel
 * Confirmed against the backend source by the backend team (2026-08-29):
 * `POST /devices/{id}/kiosk-lock`, `/force-update` and `/locate` are **pure flag-set endpoints** —
 * each writes one boolean column on `Device` and nothing else. There is no FCM/WebSocket/SSE fan-
 * out to the tablet anywhere in that path. The heartbeat is one-way *from* the device, and its
 * response body ([au.com.threesixty.cabdispatch.data.remote.DeviceDto]) is the only place those
 * four flags are ever handed to the client. So the device must poll the heartbeat and enforce the
 * flags itself; there is no arrangement of backend calls that would let an admin "push" anything.
 *
 * Before this class existed the heartbeat had exactly one call site — S6/Settings' ViewModel
 * `init` — so a dispatcher toggling `kiosk_locked` on the fleet dashboard changed nothing on the
 * physical tablet until a driver happened to open the Settings screen. That was the reported
 * production bug (real device `1c9211b61ae15c68`, SM-T575, vehicle KHI-01): flags set server-side,
 * sitting unread. This class is the fix.
 *
 * ### Shape
 * A process-lifetime [au.com.threesixty.cabdispatch.data.AppContainer] singleton with its own
 * `SupervisorJob`-backed scope, [start]ed once from
 * [au.com.threesixty.cabdispatch.data.AppContainer.init] — modelled directly on
 * [LivePositionHeartbeat], including its self-supervising collect, its act-then-delay loop, and
 * its best-effort/silent-on-failure `runCatching` posture. Nothing in any screen or ViewModel has
 * to remember to start, stop or nudge it.
 *
 * ### Gated on *paired*, deliberately NOT on *on shift*
 * [LivePositionHeartbeat] gates its loop on `session?.shiftId != null`, and copying that here would
 * have narrowed delivery for no reason: device commands are *device*-scoped, not shift-scoped — the
 * endpoint is addressed by `deviceId`, not by vehicle or shift, and a driver who is logged in but
 * between shifts (or whose shift-start call failed) is still someone an admin must be able to reach.
 * So the only gate here is [SessionHolder.deviceId] being non-null: "has this tablet ever been
 * paired". [SessionHolder.deviceIdFlow] was added in the same pass so this reacts to a mid-session
 * pairing without an app restart, and [SessionHolder.clear] was changed in the 2026-08-29 review
 * pass to stop nulling that id, because it also runs on the ordinary end-of-shift Log Off and was
 * therefore cancelling this poll for the rest of the process.
 *
 * ### The real precondition — CLOSED 2026-08-29, but only for a device that has re-paired since
 * An earlier version of this doc justified the gate above with "the tablets an admin most wants to
 * kiosk-lock, locate or flag for update are the ones sitting parked overnight, mid-provisioning, or
 * with the driver logged off", then had to retract that when it turned out
 * `POST /v1/fleet/devices/{id}/heartbeat` was bearer-only (`backend/app/api/v1/fleet.py:465` ->
 * `get_token_payload` -> `HTTPBearer(auto_error=True)`, `backend/app/core/security.py:214`) and
 * [au.com.threesixty.cabdispatch.data.AppContainer.accessToken] is an in-memory `var`, set only by
 * an *online* login or refresh, never persisted — so a parked/rebooted/offline-logged-in tablet had
 * a null token and every poll 401ed forever.
 *
 * The backend closed this properly rather than asking the client to persist a bearer token: a
 * device-scoped secret, issued once in [au.com.threesixty.cabdispatch.data.remote.DeviceDto.deviceSecret]
 * on a successful [au.com.threesixty.cabdispatch.data.remote.ApiService.registerDevice] call,
 * stored durably by [DevicePairingStore.saveDeviceSecret], and sent on every poll below as
 * `X-Device-Secret` — which the backend accepts as a complete substitute for the bearer token on
 * this one call. A parked, logged-off, or freshly-rebooted tablet with a secret authenticates and
 * receives commands with **no driver session in memory at all**, which is the scenario this class
 * exists for.
 *
 * The gap is not closed for every device, only every device that has *re-paired* since this
 * landed: [au.com.threesixty.cabdispatch.data.remote.DeviceDto.deviceSecret] is `null` on a device
 * registered before the backend added this field, and [DevicePairingStore.getDeviceSecret] is
 * likewise `null` until that device's next real [ApiService.registerDevice] call. Such a device
 * keeps relying on an in-memory access token for this call exactly as before — no forced migration,
 * no action needed unless the parked-tablet gap specifically must close for it, in which case it
 * needs one re-pair under this code.
 *
 * The *last commanded state* also still survives the gap independently, persisted by
 * [DevicePairingStore] and seeded back in [start] — see there.
 *
 * ### Interval
 * [POLL_INTERVAL_MS] (60s) is a command-latency budget, not a telemetry feed: an admin who taps
 * "kiosk lock" on the dashboard tolerates up to a minute, but does not tolerate today's unbounded
 * "whenever someone next opens Settings". Deliberately coarser than [LivePositionHeartbeat]'s 30s
 * — that figure is the blueprint's own §6.2.2 number for a map dot that has to move smoothly;
 * there is no blueprint figure for command polling, and these flags are set by a human, minutes
 * apart at most. Honest cost note: this is one HTTPS request per minute for the *entire* process
 * lifetime, including backgrounded and off-shift, which is strictly more than this app did before.
 * On a mains-powered, permanently-docked meter tablet that is negligible next to the 1 Hz GPS
 * engine; on a tablet running off its own battery in a glovebox it is a small but real new cost.
 * It does not wake the GPS radio.
 *
 * Act-then-delay (not delay-then-act) matters here specifically: screen pinning does **not**
 * survive a reboot or task removal, so a rebooted tablet comes up unpinned with `kiosk_locked`
 * still true server-side. Polling immediately on start re-applies it *as soon as there is a token
 * to poll with* — which, per the section above, is only once a driver has logged in online in this
 * process. It is the persisted flag seeded in [start] that covers the window before that.
 *
 * ### Battery / network
 * Every tick sends `battery` and `network` alongside `app_version` (via [DeviceTelemetry], shared
 * with [LivePositionHeartbeat]). The previous single call site sent `app_version` only, which is
 * why the fleet dashboard's Devices table showed both columns as `null` for the pilot tablet even
 * while it was plainly online.
 *
 * ### No client-side clear
 * `/kiosk-lock`, `/force-update` and `/locate` are all admin-only server-side
 * (`backend/app/api/v1/fleet.py`, `Depends(_require_admin)`), and this app's own JWT is a driver/
 * staff token — it structurally cannot call them. `record_heartbeat` (`backend/app/services/
 * fleet.py`) clears no flag either. So every flag stays set until an admin clears it from the
 * dashboard, and nothing here is or can be an "acknowledge"/"dismiss" action. Every behaviour
 * below is designed around flags that latch.
 *
 * ### Force update — no self-update path exists, and this does not pretend otherwise
 * [DeviceCommandState.forceUpdatePending] drives a persistent, non-blocking banner and nothing
 * else. The app genuinely cannot update itself: the fleet tablets are Samsung Knox Manage kiosks
 * with app install/uninstall *and* unknown-sources installs blocked from any source
 * (`docs/KNOX_LOCKDOWN_RUNBOOK.md` §3.2), the manifest holds no `REQUEST_INSTALL_PACKAGES`, Play
 * Store packaging has never been done (`PROJECT_HANDOFF.md`'s "Not done anywhere" list), and the
 * API carries no target-version or APK-URL field anywhere — so the device cannot even name the
 * version it is supposed to be on. Updates reach these tablets only through Knox Manage's console
 * app-deployment. This flag is therefore a *notification to a human*, in the same honesty register
 * as the backend's own HONESTY NOTE on `reboot_requested`.
 *
 * ### Reboot
 * `DeviceDto.rebootRequested` is deliberately NOT read here — see that DTO's doc and the backend's
 * HONESTY NOTE on `POST /v1/fleet/devices/{id}/reboot`: actually rebooting the OS needs
 * device-owner-level Android permissions (zero-touch/QR provisioning) this app does not hold and
 * that have never been set up, so it stays a backend-only command queue an admin can see pending.
 */
class DeviceCommandHeartbeat(
    private val apiService: ApiService,
    private val speedSource: SpeedSource,
    private val scope: CoroutineScope,
    private val appContext: Context,
    /** Durable half of the command state — see [start]'s seeding note and
     * [DevicePairingStore.getKioskLocked]. Injected rather than constructed here so this class
     * keeps the same "everything comes from [au.com.threesixty.cabdispatch.data.AppContainer]"
     * shape as [LivePositionHeartbeat]. */
    private val pairingStore: DevicePairingStore,
) {

    private val _state = MutableStateFlow(DeviceCommandState())

    /** Last known device state — observed app-wide by
     * [au.com.threesixty.cabdispatch.MainActivity] (kiosk lock + the force-update banner) and by
     * S6/Settings for its diagnostics tiles. Same `MutableStateFlow`/`asStateFlow()` producer
     * idiom as [DuressController.state]. */
    val state: StateFlow<DeviceCommandState> = _state.asStateFlow()

    /** The currently-running poll loop, or `null` while this tablet is unpaired. Only ever touched
     * from the single supervising coroutine [start] launches, so no extra synchronisation is
     * needed — same reasoning as [LivePositionHeartbeat.publishJob]. */
    private var pollJob: Job? = null

    /**
     * `locate_requested` as seen on the *previous* poll, for edge detection — see
     * [respondToLocateRequest]'s doc on why this loop must not answer a latched flag on every
     * single tick. Only touched from inside the poll loop, so no synchronisation is needed.
     */
    private var previousLocateRequested = false

    /**
     * Begins supervising [SessionHolder.deviceIdFlow] for the process lifetime. Call exactly once,
     * from [au.com.threesixty.cabdispatch.data.AppContainer.init].
     *
     * [kotlinx.coroutines.flow.StateFlow.collect] never completes, so this coroutine lives as long
     * as [scope] does. A cold start works because `AppContainer.init` restores the persisted device
     * id from [DevicePairingStore] *before* it reaches [start]; a mid-session pairing works because
     * `SessionHolder.deviceId = …` now emits on the flow, so no screen has to nudge this class
     * after pairing; and a factory reset (which nulls [SessionHolder.deviceId] explicitly, see
     * [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.attemptFactoryReset])
     * cancels the loop on its own.
     *
     * ### Seeding from the persisted last-known command state
     * The in-memory [DeviceCommandState] starts from the flags [DevicePairingStore] recorded on
     * this device's last *successful* poll, not from the `false` defaults. Without that seed the
     * anti-escape property [pollOnce] documents ("an offline tablet must not silently un-pin
     * itself") only held for one process lifetime: pull the SIM *and reboot*, and the app came back
     * with `kioskLocked = false`, which
     * [au.com.threesixty.cabdispatch.MainActivity]'s resume path then actively drives towards
     * unpinned. It still matters even with the device-secret fix below: a device that has not
     * re-paired since that landed has no secret, and for THAT device a rebooted tablet still cannot
     * poll at all until someone logs in online (see the "real precondition" section) — the seed is
     * the *only* thing carrying a command across that window for it.
     *
     * Honest about what it is not: a tablet that has never completed a poll (never paired, or
     * paired but never authenticated) comes up unlocked, because there is no known-good state to
     * restore and inventing one would be worse than admitting it. And this is still Android screen
     * pinning, not a lock Knox is enforcing — see [au.com.threesixty.cabdispatch.MainActivity]'s doc.
     *
     * ### Why the unpaired branch resets, where a failed poll does not
     * On `deviceId == null` the whole state is replaced with a fresh [DeviceCommandState] rather
     * than a partial copy. Unpairing is a *deliberate local act* — the admin-PIN-gated factory
     * reset is the only thing that does it — so, unlike a failed poll ("we could not reach the
     * server", which says nothing about what the admin wants), it is trustworthy evidence that the
     * previous commands no longer apply. Keeping them would strand the tablet: it would stay
     * pinned, re-pinned on every `onResume`, with the update banner stuck on and no poll left that
     * could ever clear either — and because pinning blocks outbound intents, the Play-services QR
     * scanner behind Pair Meter (`domain/QrScanner.kt`) is unusable, so it could not even be
     * re-paired. [DevicePairingStore.clear] drops the persisted copy in the same act, so the reset
     * survives the next cold start too.
     *
     * One limit on that, cross-referencing [au.com.threesixty.cabdispatch.MainActivity]'s deliberate
     * `LOCK_TASK_MODE_LOCKED` trade: this releases a pin the app owns in `PINNED` mode. On a
     * DPC-allowlisted Knox tablet this app's own `startLockTask` can land in `LOCKED`, which
     * `applyKioskLock` refuses to stop — there, the reset clears the flags and the banners, but the
     * pin itself waits for a reboot or a Knox-side release.
     */
    fun start() {
        _state.value = DeviceCommandState(
            deviceId = SessionHolder.deviceId,
            kioskLocked = pairingStore.getKioskLocked(),
            forceUpdatePending = pairingStore.getForceUpdatePending(),
        )
        scope.launch {
            SessionHolder.deviceIdFlow.collect { deviceId ->
                pollJob?.cancel()
                previousLocateRequested = false
                _state.value = if (deviceId == null) {
                    DeviceCommandState()
                } else {
                    _state.value.copy(deviceId = deviceId, lastPollSucceeded = null)
                }
                pollJob = deviceId?.let { id -> scope.launch { pollLoop(id) } }
            }
        }
    }

    /** Polls immediately and then every [POLL_INTERVAL_MS] — same "act then delay" shape as
     * [LivePositionHeartbeat.publishLoop]. The immediate first tick is load-bearing, not cosmetic:
     * screen pinning is task state that dies with the task, and the seeded state [start] comes up
     * with is only as fresh as the last successful poll, so the first authenticated tick after a
     * boot (or after a mid-session pairing) must confirm or correct it at once rather than leaving
     * the tablet acting on a stale command for up to a minute. */
    private suspend fun pollLoop(deviceId: String) {
        while (scope.isActive) {
            pollOnce(deviceId)
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * One heartbeat round trip. Best-effort and silent-on-failure, matching every other background
     * call in this app ([LivePositionHeartbeat.publishOnce], [DuressController]'s GPS relay): a
     * failed poll (offline, 401 on an unauthenticated cold start, server error) updates
     * [DeviceCommandState.lastPollSucceeded] for the S6 diagnostic and is otherwise swallowed — the
     * next tick simply tries again. Deliberately no outbox/retry queueing: a missed command poll is
     * a bounded delay, not lost money-adjacent data.
     *
     * Note the failure branch leaves [DeviceCommandState.kioskLocked] and
     * [DeviceCommandState.forceUpdatePending] at their last *known-good* values rather than
     * resetting them to `false`. An offline tablet must not silently un-pin itself: "we could not
     * reach the server" is not the same statement as "the admin unlocked this device", and treating
     * it as one would hand a determined driver a trivial way out of a kiosk lock (pull the SIM).
     * That property used to expire at the process boundary — a reboot brought the app back on the
     * `false` defaults, so the escape was simply "pull the SIM *and* reboot" — which is why the
     * success branch below also writes the flags through to [DevicePairingStore] and [start] seeds
     * from them. Written only on success, so what is persisted is always the server's last actual
     * answer and never a guess.
     *
     * (The `runCatching` around the call swallows [kotlinx.coroutines.CancellationException] along
     * with everything else — byte-identical to [LivePositionHeartbeat.publishOnce], which is why it
     * is left as-is: it is house-consistent, and the two would be fixed together or not at all.)
     */
    private suspend fun pollOnce(deviceId: String) {
        val result = runCatching {
            apiService.deviceHeartbeat(
                deviceId,
                DeviceHeartbeatRequestDto(
                    battery = DeviceTelemetry.readBatteryPercent(appContext),
                    network = DeviceTelemetry.readNetworkType(appContext),
                    appVersion = BuildConfig.VERSION_NAME,
                ),
                // Closes this class's own "real precondition" gap (2026-08-29 backend change): a
                // device secret authenticates this ONE call with no driver session in memory at
                // all, which is the entire reason a parked/logged-off/rebooted tablet can now
                // receive commands. `null` on a device paired before the backend added this (or
                // after a factory reset, before the next re-pair) — that tablet keeps relying on
                // AppContainer.accessToken via the interceptor exactly as before this change.
                deviceSecret = pairingStore.getDeviceSecret(),
            )
        }
        val device = result.getOrElse {
            _state.update { it.copy(lastPollSucceeded = false) }
            return
        }
        // Drop a response that outlived its pairing. Factory reset does `SessionHolder.deviceId =
        // null` and then `devicePairingStore.clear()` on the very next line, while the collector in
        // [start] that cancels this job is dispatched on our own scope — and past the HTTP call
        // there is no further suspension point here. Without this check a poll whose response had
        // already landed could resume and write kiosk_locked=true straight back into the prefs the
        // reset just cleared, silently re-arming the pin on a tablet that is no longer paired.
        if (SessionHolder.deviceId != deviceId) return
        _state.update {
            it.copy(
                lastPollSucceeded = true,
                kioskLocked = device.kioskLocked,
                forceUpdatePending = device.forceUpdatePending,
            )
        }
        // Durable copy of the last known-good command state, so a reboot/process kill comes back up
        // in the state the fleet last commanded rather than on the `false` defaults — see this
        // method's doc and [start]'s seeding note. Cheap: SharedPreferences.apply() on a 60s tick.
        pairingStore.saveCommandFlags(
            kioskLocked = device.kioskLocked,
            forceUpdatePending = device.forceUpdatePending,
        )
        // device.rebootRequested is deliberately NOT acted on (see DeviceDto's doc / backend
        // HONESTY NOTE) — real OS reboot needs device-owner permissions this app doesn't hold, so
        // it stays a backend-only queue. It is not even read here, on purpose.
        if (shouldAnswerLocate(device.locateRequested)) {
            respondToLocateRequest()
        }
        previousLocateRequested = device.locateRequested
    }

    /**
     * Edge detection for `locate_requested`. The backend never clears the flag on heartbeat
     * (`record_heartbeat` writes only `last_seen_at`/battery/network/app_version) and only an admin
     * can clear it, so a level-triggered response would turn one dispatcher click into a position
     * publish every [POLL_INTERVAL_MS], forever. That was harmless when the only caller ran once
     * per Settings open; it is not harmless in a permanent loop.
     *
     * So: answer on a `false -> true` transition, and keep retrying on later ticks *only* while the
     * last attempt has not actually landed — a locate asked for during a cold start with no GPS fix
     * yet ([LocateOutcome.NoFixYet]) or a publish that failed on a flaky link
     * ([LocateOutcome.Failed]) must still get answered once the device recovers, which is exactly
     * the case a plain edge trigger would drop on the floor.
     */
    private fun shouldAnswerLocate(locateRequested: Boolean): Boolean {
        if (!locateRequested) return false
        if (!previousLocateRequested) return true
        return _state.value.locate != LocateOutcome.Sent
    }

    /**
     * Answers an admin's MDM "locate" request (`Device.locateRequested`, read back on the heartbeat
     * above) by publishing this device's current real position through the same live-position
     * pipeline the fleet dashboard's Live Map already watches (`POST /v1/fleet/positions` —
     * [ApiService.publishPosition], shared/API_SUMMARY.md "Live Ops"), so a dispatcher sees a fresh
     * pin appear/move as evidence the request was answered.
     *
     * No acknowledge/clear step: the only endpoint that flips `locate_requested` back off is
     * `POST /v1/fleet/devices/{id}/locate`, which is admin-only server-side
     * (`backend/app/api/v1/fleet.py::set_device_locate`) — this device's own JWT (driver or staff
     * role) is never an admin, so it structurally cannot call it. Per that pass's brief, a fresh
     * position publish is treated as sufficient evidence on its own — the dispatcher watching Live
     * Map sees the pin, which is the actual thing they're waiting on. The flag simply stays set
     * server-side until an admin clears it from the dashboard; see [shouldAnswerLocate] for how
     * this loop avoids re-publishing on every tick because of that.
     *
     * Moved here from `SettingsViewModel` (2026-08-29) unchanged in intent — the limitation its
     * old doc flagged ("loadDeviceStatus only runs once ... there's no periodic/background
     * heartbeat anywhere in this app yet") is what this class closes, so that paragraph is gone
     * rather than left standing as a stale warning.
     *
     * ### One honest gap this does NOT close
     * Publishing is keyed off [DriverSession.vehicleUuid], not [DriverSession.vehicleId]: the
     * positions endpoint was found live to 404 "Vehicle not found" on the driver-entered rego
     * string and to accept only the real fleet-vehicle UUID (see [LivePositionHeartbeat.start]'s
     * own note, which is why that class was switched to the UUID). The old S6 implementation
     * published the rego and was therefore, in all likelihood, silently 404ing on the real device.
     * Using the UUID is strictly more correct, but it does not make locate work unconditionally:
     * with no session bound at all — the parked, logged-off tablet — there is no vehicle to publish
     * a position *for*, so locate reports [LocateOutcome.NoVehicleBound] and sends nothing.
     * Publishing against the rego as a fallback would only trade a silent skip for a guaranteed 404.
     *
     * In practice that gap is narrower than it looks, and not in a flattering way: an earlier
     * version of this paragraph said "this pass fixes kiosk-lock and force-update on an idle tablet,
     * but locate still needs a bound vehicle", which credited the pass with more than it delivers.
     * Nothing at all is delivered to an idle tablet — the heartbeat that carries *every* command is
     * bearer-authenticated and this app holds no token until an online login (see this class's "real
     * precondition" section). So by the time a locate request can even be read off a poll, a driver
     * is logged in; what remains genuinely missing is the narrower case of a driver logged in with
     * no vehicle bound, or with a rego whose fleet-UUID lookup never resolved.
     *
     * Best-effort and silent-on-failure beyond [LocateOutcome] diagnostics, matching every other
     * background call here: no vehicle bound, or [SpeedSource.locationFix] having no fix yet (no
     * permission, cold start, no signal), both mean there is nothing honest to publish, so this
     * skips rather than sending a fabricated position.
     */
    private suspend fun respondToLocateRequest() {
        val vehicleUuid = SessionHolder.session.value?.vehicleUuid
        if (vehicleUuid == null) {
            _state.update { it.copy(locate = LocateOutcome.NoVehicleBound) }
            return
        }
        val fix = speedSource.locationFix.value
        if (fix == null) {
            _state.update { it.copy(locate = LocateOutcome.NoFixYet) }
            return
        }
        runCatching {
            apiService.publishPosition(
                PositionPublishRequestDto(
                    vehicleId = vehicleUuid,
                    lat = fix.lat,
                    lng = fix.lng,
                    // Deliberately a fixed placeholder, not a guess: this call site only knows "an
                    // admin asked where this device is", not the driver's real
                    // available/on-trip/offline status — that's the Idle screen's separate,
                    // still-unwired "For Hire" toggle (HANDOFF.md "Availability broadcast not
                    // wired"). No server-side enum constraint on `status` (see
                    // PositionPublishRequestDto's doc), so this is a safe, honest value until that
                    // toggle publishes a real one.
                    status = LOCATE_RESPONSE_STATUS,
                ),
            )
        }.onSuccess {
            _state.update { it.copy(locate = LocateOutcome.Sent) }
        }.onFailure { error ->
            _state.update { it.copy(locate = LocateOutcome.Failed(error.message ?: "Unknown error")) }
        }
    }

    private companion object {
        /** See this class's "Interval" section for why 60s, and why deliberately coarser than
         * [LivePositionHeartbeat]'s 30s. */
        const val POLL_INTERVAL_MS = 60_000L

        /** See [respondToLocateRequest]'s doc on why this is a fixed placeholder rather than a
         * real availability status. Same value S6 used before this moved. */
        const val LOCATE_RESPONSE_STATUS = "unknown"
    }
}
