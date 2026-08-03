package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.domain.duress.DuressAudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI-facing duress lifecycle state (spec §8 rows 29-30, the "Duress triggered"/"Duress active"
 * contextual overlays). See [DuressController] for the state machine that drives it.
 */
sealed interface DuressUiState {
    data object Idle : DuressUiState

    /**
     * Brief full-screen confirmation shown the instant the hidden gesture fires (spec row 29:
     * "shown right after the ... gesture fires, before it escalates"). [secondsRemaining] counts
     * down from [DuressController.CANCEL_WINDOW_SECONDS] to 1; a driver who taps Cancel in time
     * never reaches [Active]. [eventId] is `null` until `POST /v1/duress/trigger` resolves — the
     * local countdown/UI is never gated on that network round-trip (see [DuressController.trigger]'s
     * doc: this is a safety feature, the confirmation must appear even fully offline).
     */
    data class Triggered(val eventId: String?, val secondsRemaining: Int) : DuressUiState

    /**
     * Ongoing indicator/banner (spec row 30) — the cancel window elapsed without the driver
     * cancelling. [eventId] may still be `null` here if the trigger call hadn't succeeded by the
     * time the window elapsed (e.g. offline at gesture time); [DuressController] keeps retrying
     * in the background regardless, since the banner reflects local safety state, not network
     * success.
     */
    data class Active(val eventId: String?) : DuressUiState
}

/**
 * Screen-agnostic duress state machine — owns the confirmation countdown, the trigger/cancel
 * network calls (best-effort + retried, never blocking the local UI state — see [DuressUiState]
 * docs), and, once [DuressUiState.Active], a best-effort GPS-fix relay plus a poll for
 * dispatcher-side resolution so the banner clears itself once dispatch/backend closes the event
 * out from under the driver, no action required on-device.
 *
 * Deliberately NOT scoped to [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel] —
 * registered as a process-lifetime singleton on
 * [au.com.threesixty.cabdispatch.data.AppContainer] instead, so any screen can fire [trigger]
 * and/or render [state] without owning this lifecycle logic itself. Per spec §2 ("hidden duress
 * gesture ... active throughout", not just during an active trip), the hidden hit-target is wired
 * on both [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] (S3/Hired) and
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen] (the wheel-dashboard
 * shell) — see [au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone], the shared
 * gesture-detector composable both use. Each screen owns only the *gesture detector* + rendering
 * [state]; this class owns the actual state machine both share.
 *
 * Real backend wiring (was previously log-only — see `android/README.md`'s mock-surface table,
 * "Duress networking" row, now stale as of this pass): `trigger`/`cancel`/`gps` are driver-callable
 * per `backend/app/api/v1/duress.py`'s role policy; `escalate`/`close` are dispatcher-only and are
 * deliberately never called from this device — see [DuressRepository]/`ApiService`'s duress
 * endpoints for the exact split.
 *
 * ### Audio recording (Blueprint §4.3/§8.3, added alongside this doc's dated entry in
 * `android/HANDOFF.md`)
 * [audioRecorder] is optional (`null` in any test/preview construction that doesn't have a real
 * `Context` to build one from — mirrors [locationProvider] being nullable for the same reason,
 * except the recorder is a constructor dependency rather than a settable `var` since, unlike a
 * per-screen `LocationManager` read, [DuressAudioRecorder] only ever needs the process-lifetime
 * application `Context` [au.com.threesixty.cabdispatch.data.AppContainer] already holds — see
 * that object's wiring). [runActivePhase] starts it once [DuressUiState.Active] is reached with a
 * real event id and enforces [DuressAudioRecorder.MAX_RECORDING_DURATION_MS] itself (the recorder
 * only knows how to start/stop a file, not the duress lifecycle around it — see that class's doc
 * for the full "single capped file, not a true rolling buffer" write-up), uploading via
 * [DuressRepository.uploadAudio] either once that cap elapses or the event reaches a terminal
 * status, whichever comes first.
 */
class DuressController(
    private val repository: DuressRepository,
    private val scope: CoroutineScope,
    private val audioRecorder: DuressAudioRecorder? = null,
) {
    private val _state = MutableStateFlow<DuressUiState>(DuressUiState.Idle)
    val state: StateFlow<DuressUiState> = _state.asStateFlow()

    /**
     * Optional last-known-fix supplier `(lat, lng, accuracyMetres)` — set by whichever screen
     * currently holds an Android `Context` capable of a location read (see [HiredViewModel]'s
     * `LocationManager` use, the same read pattern
     * `ui/screens/settings/SettingsViewModel.kt#pollGps` already uses). Purely best-effort:
     * leaving this `null` just means the GPS relay is skipped while [DuressUiState.Active] —
     * never blocks trigger/cancel, which don't need a location fix at all.
     */
    var locationProvider: (() -> Triple<Double, Double, Float?>?)? = null

    private var cancelRequested = false

    /**
     * Fires the hidden gesture's duress event. A no-op if a cycle is already in flight
     * ([DuressUiState.Triggered] or [DuressUiState.Active]) — the triple-tap gesture can't spawn
     * two overlapping events. [vehicleId]/[driverId] come from [SessionHolder.session]; either
     * being `null` (session lost) still shows the local confirmation/active UI — see this class's
     * doc — it just means there is nothing to report server-side until a session exists again.
     */
    fun trigger(vehicleId: String?, driverId: String?) {
        if (_state.value != DuressUiState.Idle) return
        cancelRequested = false
        _state.value = DuressUiState.Triggered(eventId = null, secondsRemaining = CANCEL_WINDOW_SECONDS)
        scope.launch { runLifecycle(vehicleId, driverId) }
    }

    /**
     * Cancels within the confirmation window. A no-op once [state] has moved past
     * [DuressUiState.Triggered] — per the backend's role policy, past the window resolution is
     * dispatcher/backend-only (`escalate`/`close`), never a driver self-cancel (see this class's
     * doc). The "Duress active" banner therefore has no cancel affordance by design, not by
     * omission.
     */
    fun cancel() {
        if (_state.value !is DuressUiState.Triggered) return
        cancelRequested = true
    }

    private suspend fun runLifecycle(vehicleId: String?, driverId: String?) {
        val countdownJob: Job = scope.launch {
            for (remaining in CANCEL_WINDOW_SECONDS downTo 1) {
                val current = _state.value as? DuressUiState.Triggered ?: return@launch
                _state.value = current.copy(secondsRemaining = remaining)
                delay(1000)
            }
        }

        // Trigger call, retried a few times in case the gesture fired while offline. Runs
        // concurrently with `countdownJob` above (both real-time), not in series with it — a
        // driver who cancels a couple seconds in should not have to wait out the full retry
        // budget for the on-screen state to react (the `cancelRequested` checks below bound the
        // *network* tail, not the visible countdown, which already reacted instantly via `cancel()`).
        var eventId: String? = null
        if (vehicleId != null && driverId != null) {
            for (attempt in 1..TRIGGER_MAX_ATTEMPTS) {
                if (cancelRequested) break
                eventId = repository.trigger(vehicleId, driverId).getOrNull()?.id
                if (eventId != null || cancelRequested) break
                delay(TRIGGER_RETRY_DELAY_MS)
            }
        }

        if (cancelRequested) {
            countdownJob.cancel()
            if (eventId != null) repository.cancel(eventId)
            _state.value = DuressUiState.Idle
            return
        }

        countdownJob.join()

        if (cancelRequested) {
            if (eventId != null) repository.cancel(eventId)
            _state.value = DuressUiState.Idle
            return
        }

        _state.value = DuressUiState.Active(eventId)
        runActivePhase(vehicleId, driverId, eventId)
    }

    /** [DuressUiState.Active]: keep trying to obtain an [DuressEventDto] id if the initial
     * trigger never landed, then relay best-effort GPS fixes + poll for dispatcher-side
     * resolution until the event reaches a terminal status, at which point this clears the
     * banner back to [DuressUiState.Idle] on its own. Also starts/caps/uploads the duress audio
     * recording — see class doc's "Audio recording" section. */
    private suspend fun runActivePhase(vehicleId: String?, driverId: String?, initialEventId: String?) {
        var pendingEventId = initialEventId
        while (pendingEventId == null) {
            if (vehicleId == null || driverId == null) return // no session identity to report against — stays Active locally; driver/dispatch must resolve out-of-band
            delay(TRIGGER_RETRY_DELAY_MS)
            pendingEventId = repository.trigger(vehicleId, driverId).getOrNull()?.id
            pendingEventId?.let { _state.value = DuressUiState.Active(it) }
        }
        // Stable `val` (not the `var` above) so every use below is unambiguously non-null,
        // including inside the `let` lambda's inline-captured scope.
        val eventId: String = pendingEventId

        // Audio recording starts here, i.e. the moment a real event id is known — in the
        // overwhelmingly common case that coincides with reaching Active at all (the trigger
        // call has 3 retries spread across the same 10s cancel-window countdown, see
        // TRIGGER_MAX_ATTEMPTS/TRIGGER_RETRY_DELAY_MS above, so it has almost always already
        // resolved by the time Active is reached). Documented, honest gap: if the device is
        // still fully offline at this point, recording doesn't start until/unless the retry loop
        // just above eventually gets an id — there is no event id to file a local-only recording
        // under otherwise, and nothing to upload it against even if there were.
        val recordingStartedAtMillis = System.currentTimeMillis()
        var audioPending = audioRecorder?.start(eventId) == true

        while (true) {
            locationProvider?.invoke()?.let { (lat, lng, accuracyM) ->
                repository.postGps(eventId, lat, lng, speedKmh = null, accuracyM = accuracyM?.toDouble())
            }

            if (audioPending &&
                System.currentTimeMillis() - recordingStartedAtMillis >= DuressAudioRecorder.MAX_RECORDING_DURATION_MS
            ) {
                stopAndUploadAudio(eventId)
                audioPending = false
            }

            val event = repository.getEvent(eventId).getOrNull()
            if (event != null && event.status in TERMINAL_STATUSES) {
                if (audioPending) stopAndUploadAudio(eventId)
                _state.value = DuressUiState.Idle
                return
            }
            delay(ACTIVE_POLL_INTERVAL_MS)
        }
    }

    /** Stops whatever [audioRecorder] recording is in progress (a no-op, per that class's own
     * doc, if none is) and uploads the resulting file via [DuressRepository.uploadAudio] —
     * best-effort, same as [DuressController]'s every other network call in this phase: a
     * failure here never blocks the GPS relay/resolution poll loop that called it. */
    private suspend fun stopAndUploadAudio(eventId: String) {
        val file = audioRecorder?.stop() ?: return
        repository.uploadAudio(eventId, file)
    }

    companion object {
        /** Mirrors backend `app.models.duress.CANCEL_WINDOW_SECONDS`. Kept as a plain client-side
         * constant rather than round-tripped from a live event read — [DuressEventDto]
         * deliberately omits `escalation_log_json` (the field that actually carries it
         * server-side, see that DTO's doc) since it's a fixed product decision, not a
         * per-tenant/per-event configurable one; if that ever changes, this is the one place to
         * update. */
        const val CANCEL_WINDOW_SECONDS = 10
        private const val TRIGGER_MAX_ATTEMPTS = 3
        private const val TRIGGER_RETRY_DELAY_MS = 4000L
        private const val ACTIVE_POLL_INTERVAL_MS = 5000L
        private val TERMINAL_STATUSES = setOf("cancelled", "resolved")
    }
}
