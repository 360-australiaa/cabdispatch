package au.com.threesixty.cabdispatch.domain

import android.content.Context
import android.content.Intent
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DeviceHeartbeatRequestDto
import au.com.threesixty.cabdispatch.data.remote.DeviceCommandAckDto
import au.com.threesixty.cabdispatch.data.remote.DeviceLocateResponseDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.HttpException
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
    /**
     * The server answered this device's heartbeat with 404 — the record it is beating against no
     * longer exists (most often removed by a fleet wipe). Distinct from `lastPollSucceeded = false`,
     * which covers being offline: one needs re-pairing, the other needs patience, and they were
     * indistinguishable until 2026-09-07. Never set by a network failure.
     */
    val deviceRejected: Boolean = false,

    val kioskLocked: Boolean = false,
    /** `Device.force_update_pending`. Surfaced to the driver as a persistent, non-blocking banner
     * ([au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner]) — this app has no
     * self-update path whatsoever; see [DeviceCommandHeartbeat]'s "Force update" section. */
    val forceUpdatePending: Boolean = false,
    val locate: LocateOutcome = LocateOutcome.None,
    /** `DeviceDto.latestVersionCode`, as seen on the last successful poll — see that field's own
     * doc. Purely informational here (not persisted, not acted on by this class): the real update
     * flow is driven independently by
     * [au.com.threesixty.cabdispatch.data.AppContainer.appUpdateChecker]'s own
     * `GET /v1/app-releases/latest` call, not by this heartbeat hint. `null` means "no hint on the
     * last poll", never "you are up to date". */
    val latestVersionCode: Int? = null,
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
        scope.launch { recoverDeviceIdFromSecret() }
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

    /**
     * Recovers a lost `deviceId` from the device secret, via B5's `GET /v1/fleet/devices/me`.
     *
     * ### The gap this closes
     * Every command path in this class is gated on [SessionHolder.deviceId] being non-null, because
     * the heartbeat is addressed at `/v1/fleet/devices/{id}/heartbeat`. Before B5 there was no read
     * that did not need that id, so a tablet holding a valid secret but no id was permanently
     * unreachable: no kiosk lock, no locate, no forced update, and — from S6's point of view — not
     * paired at all, with the only remedy a physical re-pair at the depot. `devices/me` needs
     * nothing but the secret, so that tablet can now name itself and rejoin the fleet on its own.
     *
     * Not folded into [pollOnce]: once the id is known the heartbeat is the better read (it also
     * *writes* battery/network/app-version telemetry, which this does not), so this runs at most
     * once per process and only in the one state where the poll cannot run at all. Setting
     * [SessionHolder.deviceId] is what actually starts it — [start]'s collector is already
     * watching, so there is no second nudge to forget.
     *
     * Silent on failure by design, exactly like every other network call in this class: offline,
     * a secret the server has since rotated, or a backend older than B5 (404 on the path) all mean
     * "still no id", which is the state the tablet was already in. Nothing here fabricates one.
     */
    private suspend fun recoverDeviceIdFromSecret() {
        val needsDeviceId = SessionHolder.deviceId == null
        // Also recover the tenant slug. A tablet paired before that field existed has a perfectly
        // good pairing and still cannot log a driver in (422 Field required: tenant_slug), and
        // asking an operator to re-pair working tablets to fix a field they never knew about is
        // the wrong answer. `devices/me` carries it, authenticated by the device secret alone.
        val needsTenantSlug = pairingStore.getTenantSlug().isNullOrBlank()
        if (!needsDeviceId && !needsTenantSlug) return
        val secret = pairingStore.getDeviceSecret() ?: return
        val device = runCatching { apiService.deviceMe(secret) }.getOrNull() ?: return
        pairingStore.saveTenantSlug(device.tenantSlug)
        if (!needsDeviceId) return
        // Re-check: a pairing could have completed on S6 while this call was in flight, and that
        // id is the fresher of the two (registering rotates the secret this call authenticated
        // with). Never overwrite it.
        if (SessionHolder.deviceId != null) return
        pairingStore.saveDeviceId(device.id)
        SessionHolder.deviceId = device.id
        reconcileVehicleBinding(device.vehicleId, device.vehicleRego)
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
        val device = result.getOrElse { error ->
            // A 404 is the server saying "no such device", not "I am unreachable". Surfaced so the
            // app can stop presenting as paired -- see DevicePairingStatus.isUnpaired's overload.
            // Deliberately sticky in the other direction: only a successful poll clears it.
            val rejected = (error as? HttpException)?.code() == 404
            _state.update { it.copy(lastPollSucceeded = false, deviceRejected = rejected || it.deviceRejected) }
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
                deviceRejected = false,
                kioskLocked = device.kioskLocked,
                forceUpdatePending = device.forceUpdatePending,
                latestVersionCode = device.latestVersionCode,
            )
        }
        // Durable copy of the last known-good command state, so a reboot/process kill comes back up
        // in the state the fleet last commanded rather than on the `false` defaults — see this
        // method's doc and [start]'s seeding note. Cheap: SharedPreferences.apply() on a 60s tick.
        pairingStore.saveCommandFlags(
            kioskLocked = device.kioskLocked,
            forceUpdatePending = device.forceUpdatePending,
        )
        // The self-heal channel that was on the wire all along and never read — see
        // [reconcileVehicleBinding] and [decideVehicleRebind].
        reconcileVehicleBinding(device.vehicleId, device.vehicleRego)
        if (shouldAnswerLocate(device.locateRequested)) {
            respondToLocateRequest(deviceId)
        }
        previousLocateRequested = device.locateRequested

        // `rebootRequested` used to be deliberately unread, because a real OS reboot needs
        // device-owner permissions this app does not hold. That reasoning still stands for the OS
        // — what changed (2026-09-08) is that the flag is no longer treated as unactionable
        // because the strongest possible response is unavailable. Restarting the meter app's own
        // process IS available, and it is what an operator pressing this actually wants: the meter
        // is stuck, restart it. Reported from the field as "when I try to reboot or locate it's
        // showing pending, but nothing is working" — which is precisely what it did.
        if (device.rebootRequested) {
            actOnRestartRequest(deviceId)
        }
    }

    /**
     * Adopts the depot's own record of which vehicle this tablet sits in, when it disagrees with
     * the binding the driver's session is holding.
     *
     * ### The field failure this fixes
     * Reported on the test tablet (2026-09-08, and written up in [VehicleBinding]'s class doc):
     * a session still bound to a vehicle UUID that had been deleted in a fleet wipe published to
     * `POST /v1/fleet/positions` every tick and got `404 Vehicle not found` every tick — surfaced
     * to the driver as "Location request failed to send — HTTP 404" and to the dispatcher as a car
     * that simply never appeared on the Live Map. There was no path back short of the driver
     * logging out and re-binding, because nothing in the app ever re-read the binding. The answer
     * was arriving on this very poll, in `DeviceDto.vehicle_id`, and being discarded unread.
     *
     * The decision itself is [decideVehicleRebind] — pure, unit-tested, and documenting the three
     * cases that must NOT rebind. This method is only the part that touches live state.
     */
    private fun reconcileVehicleBinding(reportedVehicleUuid: String?, reportedVehicleRego: String?) {
        val adopt = decideVehicleRebind(SessionHolder.session.value, reportedVehicleUuid, reportedVehicleRego)
            ?: return
        SessionHolder.updateVehicleUuid(adopt)
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
     * above) by reporting this device's own real fix to
     * [ApiService.deviceLocateResponse] — which is also what CLEARS the flag server-side.
     *
     * ### Why not the vehicle-position pipeline it used to use
     * This published a vehicle position (`POST /v1/fleet/positions`) so a dispatcher would see a
     * pin move on Live Map. Two things were wrong with that, and both were reported from the field:
     *
     * 1. It needs the fleet UUID of the car this tablet is currently bound to, which means a live
     *    driver session AND a resolved binding. A parked, logged-off tablet has neither — and that
     *    is exactly the tablet an operator reaching for "locate" is trying to find. It answered
     *    [LocateOutcome.NoVehicleBound] and gave up.
     * 2. A tablet holding a binding to a since-deleted vehicle (a fleet wipe, say) got a 404 back,
     *    which Settings ▸ About surfaced verbatim as "Location request failed to send — HTTP 404
     *    not found". The tablet was working perfectly; the identity it was publishing against had
     *    been deleted out from under it.
     *
     * A tablet's location belongs to the tablet. Reporting it on the device route needs no session,
     * no vehicle, and no driver — and it is authenticated by the device's own secret, so a tablet
     * sitting in a drawer can still say where it is.
     *
     * ### It now acknowledges
     * The old note here recorded that there was no clear step, because the only endpoint that
     * flipped `locate_requested` off was admin-only and this device could never call it — so the
     * flag stayed set forever and the dashboard read "Pending" whether or not anyone had answered.
     * The device route above clears it as part of recording the answer, which is what turns that
     * permanent badge into a real position and a timestamp.
     */
    private suspend fun respondToLocateRequest(deviceId: String) {
        val fix = speedSource.locationFix.value
        if (fix == null) {
            _state.update { it.copy(locate = LocateOutcome.NoFixYet) }
            return
        }
        runCatching {
            apiService.deviceLocateResponse(
                deviceId,
                DeviceLocateResponseDto(lat = fix.lat, lng = fix.lng, accuracyM = fix.accuracyM.toDouble()),
                deviceSecret = pairingStore.getDeviceSecret(),
            )
        }.onSuccess {
            _state.update { it.copy(locate = LocateOutcome.Sent) }
        }.onFailure { error ->
            _state.update { it.copy(locate = LocateOutcome.Failed(error.message ?: "Unknown error")) }
        }
    }

    /**
     * Acts on a queued restart, then tells the server it did.
     *
     * This is NOT an OS reboot and never claims to be — that needs Device-Owner provisioning this
     * fleet does not have (see the server's `Device.reboot_requested`). Restarting the meter app's
     * own process is what this can genuinely do, and it is what the button is actually reached for:
     * the meter is stuck, restart it. Until now the flag was inert on this side, so an admin
     * queued a restart and watched it read "Pending" for the life of the row.
     *
     * Acknowledged BEFORE the process dies, deliberately. A restart that killed the app first
     * would leave the flag set, and the tablet would restart again on every poll for the rest of
     * its life — a reboot loop driven by an un-clearable flag is far worse than a missed restart.
     * If the ack fails the restart does not happen, and the next poll tries again.
     */
    private suspend fun actOnRestartRequest(deviceId: String) {
        val acked = runCatching {
            apiService.deviceCommandAck(
                deviceId,
                DeviceCommandAckDto(command = "restart"),
                deviceSecret = pairingStore.getDeviceSecret(),
            )
        }.isSuccess
        if (acked) restartApp()
    }

    /**
     * Restarts this app's process.
     *
     * Relaunches [MainActivity] on a fresh task and then ends the current process, so the app comes
     * back up cold — the same state a driver gets from force-stopping and reopening it, which is
     * the manual workaround this replaces.
     */
    private fun restartApp() {
        val intent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
        appContext.startActivity(intent)
        Runtime.getRuntime().exit(0)
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
