package au.com.threesixty.cabdispatch.ui.screens.hired

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.FareEngine
import au.com.threesixty.cabdispatch.domain.FareEngineImpl
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TextToSpeechAnnouncer
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.TripStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

class HiredViewModel(application: Application) : AndroidViewModel(application) {

    // TODO(integration agent): see FareEngine's doc comment — this instance
    // is recreated per nav entry, not process-scoped.
    private val fareEngine: FareEngine = FareEngineImpl(AppContainer.speedSource, viewModelScope)

    val fareState: StateFlow<FareState> = fareEngine.state

    /**
     * True only when this ViewModel instance was created for a trip that's
     * being opened right now (S2 handed off a [TripContext] via
     * [SessionHolder.pendingTrip] — the same gate [init] below uses to decide
     * whether to call [fareEngine]`.startTrip()`/[openTripInRoom]). Read once
     * by [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] to gate
     * the once-per-trip fare-ramp + "METER STARTED" banner sequence (spec §6
     * step 3) so it doesn't replay on every recomposition or if this screen
     * is ever re-entered without a fresh trip hand-off.
     */
    val isNewTripStart: Boolean = SessionHolder.pendingTrip.value != null

    private val _speechEnabled = MutableStateFlow(false)
    val speechEnabled: StateFlow<Boolean> = _speechEnabled.asStateFlow()

    private val _breakdownExpanded = MutableStateFlow(false)
    val breakdownExpanded: StateFlow<Boolean> = _breakdownExpanded.asStateFlow()

    /** Shared with every other screen via [AppContainer.duressController] — see that class's doc
     * for why the state machine lives there and not here. This is a straight passthrough so
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] has a single `StateFlow` to
     * `collectAsState()`, same as every other field on this ViewModel. */
    val duressState: StateFlow<DuressUiState> = AppContainer.duressController.state

    private val speechAnnouncer = TextToSpeechAnnouncer(application)
    private var lastAnnouncedDollar = -1

    // --- Room persistence (integration pass) ---
    //
    // Was entirely missing: this ViewModel drove [fareEngine]'s in-memory
    // state and nothing else, so no TripEntity row ever existed for S4/S5 to
    // read (see AppContainer.kt's note by [pureFareEngine] and the
    // now-resolved TODO that used to be in CloseAndPayViewModel.kt). Opens
    // the trip in Room the moment the live engine starts, then keeps it
    // updated on every live-engine emission via [persistTick] so
    // [au.com.threesixty.cabdispatch.domain.fare.reconstructFareState] has
    // real distanceM/movingS/waitingS to read once the trip reaches S4.
    private val tripRepository: TripRepository = AppContainer.tripRepository
    private var persistedTripClientUuid: String? = null

    init {
        // Best-effort GPS supplier for AppContainer.duressController's Active-phase relay — see
        // [lastKnownFix]'s doc. Only ever *read* while a duress event is Active; harmless to
        // leave wired for this VM's whole lifetime (cleared in [onCleared] regardless, so a
        // later screen with no Context installed doesn't call back into a destroyed VM).
        AppContainer.duressController.locationProvider = ::lastKnownFix

        val tripContext = SessionHolder.pendingTrip.value
        if (tripContext != null) {
            fareEngine.startTrip(
                tripContext.tariff,
                tripContext.startLat,
                tripContext.startLng,
                isMaxiVehicle = tripContext.isMaxiVehicle,
                passengerCount = tripContext.passengerCount,
                wheelchairHiring = tripContext.wheelchairHiring,
                airportRankRequestedMaxi = tripContext.airportRankRequestedMaxi,
            )
            openTripInRoom(tripContext)

            fareState
                .onEach { state -> persistTick(state) }
                .launchIn(viewModelScope)
        }

        fareState
            .map { it.total.setScale(0, RoundingMode.DOWN).toInt() }
            .distinctUntilChanged()
            .onEach { wholeDollars ->
                if (_speechEnabled.value && wholeDollars != lastAnnouncedDollar && wholeDollars > 0) {
                    lastAnnouncedDollar = wholeDollars
                    speechAnnouncer.announce("Fare now $wholeDollars dollars")
                }
            }
            .launchIn(viewModelScope)
    }

    private fun openTripInRoom(tripContext: TripContext) {
        // Generated here, synchronously, and marked live BEFORE the coroutine below ever touches
        // Room — see SessionHolder.liveTripClientUuid's doc for the real race this closes. Room's
        // observeActiveTrip() Flow can emit the new OPEN row the instant tripDao.insert() commits,
        // which is BEFORE tripRepository.openTrip() (two suspend DB writes: insert, then the
        // outbox upsert) returns to this coroutine — so setting SessionHolder.markTripLive only
        // after openTrip() returns left a real, if narrow, window where DeckHomeScreen could see
        // `activeTrip != null` and `liveTripClientUuid` still stale, misreading a fare that is
        // still opening as an orphaned one and redirecting straight to Close & Pay (found live,
        // 2026-09-06, on the very first trip started after the fix that introduced the check).
        // Generating the id here and threading it into tripRepository.openTrip(clientUuid = ...)
        // instead of letting that function mint its own means the two never have a chance to
        // disagree in the first place.
        val clientUuid = UUID.randomUUID().toString()
        SessionHolder.markTripLive(clientUuid)
        viewModelScope.launch {
            // fareEngine.startTrip() above set the initial state synchronously
            // (status/timeClass/peak breakdown), so this read is safe here.
            val initial = fareState.value
            val trip = tripRepository.openTrip(
                clientUuid = clientUuid,
                vehicleId = tripContext.vehicleId,
                driverId = tripContext.driverId,
                shiftId = tripContext.shiftId,
                tariffId = tripContext.tariff.id,
                // rank/hail is the only flow S1-S4 currently drive (no
                // booked/airport_fixed/multi_hire entry point exists yet —
                // see TripEntity/TripCreateDto's `type` doc).
                type = "rank_hail",
                startLat = tripContext.startLat,
                startLng = tripContext.startLng,
                timeClass = initial.timeClass.name.lowercase(),
                // FareState has no standalone `isPeak` flag (see
                // domain/TripModels.kt) — a non-zero peakAmount in the initial
                // breakdown is exactly and only true when FareEngineImpl
                // applied the peak-hiring charge at startTrip() time.
                isPeak = initial.breakdown.peakAmount.signum() > 0,
                // "Set Price" entry point (2026-08-10 meter-polish pass) — see
                // TripContext.negotiatedTotal's doc. Null for every ordinary metered trip
                // (the pre-existing, unchanged default).
                negotiatedTotal = tripContext.negotiatedTotal,
                // Point to Point Transport (Fares) Order 2026 UI-wiring pass — see
                // TripContext.isMaxiVehicle/.passengerCount/.wheelchairHiring's docs. `maxi` here
                // means "vehicle has 5+ seats" (TripEntity.maxi's doc), fed from the driver's local
                // self-declaration ([au.com.threesixty.cabdispatch.domain.MaxiVehicleStore]), not
                // real fleet-registry data. Every default (false/1/false) matches this method's
                // pre-existing behavior for a call site that never sets them.
                maxi = tripContext.isMaxiVehicle,
                passengerCount = tripContext.passengerCount,
                wheelchairHiring = tripContext.wheelchairHiring,
                // Maxi-at-airport-rank fare-integrity fix (2026-09-05): this flag was already
                // passed to fareEngine.startTrip() above (so the live on-device meter charged the
                // maxi rate correctly) but was never persisted to Room, so it never reached the
                // server via toSyncItemDto — see TripEntity.airportRankRequestedMaxi's doc.
                airportRankRequestedMaxi = tripContext.airportRankRequestedMaxi,
            )
            persistedTripClientUuid = trip.clientUuid
        }
    }

    /**
     * Refreshes the Room row's cumulative counters from the live engine's
     * current (not delta) totals — safe to call on every emission since
     * [TripRepository.tick] overwrites, it doesn't append (see its doc).
     * A no-op until [openTripInRoom]'s write completes (guarded by the
     * nullable [persistedTripClientUuid]); the next emission after that
     * catches Room up to the latest cumulative state, so nothing is lost.
     *
     * [nextTracePoint] is read synchronously here, not inside the launched coroutine below — it
     * reads real, current state ([AppContainer.speedSource.locationFix]), so there's no reason to
     * defer it into the coroutine, and doing it here keeps its read as close as possible to the
     * exact moment [FareEngineImpl.tick] made its own accrual decision for this same emission.
     */
    private fun persistTick(state: FareState) {
        val clientUuid = persistedTripClientUuid ?: return
        val point = nextTracePoint()
        viewModelScope.launch { doPersistTick(clientUuid, state, point) }
    }

    /**
     * The one real GPS point this fare-engine tick appends to the persisted trace — see
     * `POST /v1/trips/sync`'s server-side `recompute_from_trace` (`backend/app/services/trips.py`),
     * which independently REPLAYS this exact trace through the same tick algorithm
     * [FareEngineImpl.tick] runs on-device to validate `deviceTotal` within a 1% variance
     * tolerance. This is NOT merely feeding the meter map's polyline.
     *
     * **Revision history, both real fare-integrity bugs found live, not in a lab:**
     * 1. Originally this always passed `newPoints = emptyList()` — the trace never grew at all, so
     *    the server replayed an empty trace and flagged every trip (14.08% variance, confirmed
     *    live 2026-09-06/07).
     * 2. The first fix recorded one point per tick but SKIPPED it whenever
     *    [AppContainer.speedSource.locationFix] hadn't changed since the last recorded fix (real
     *    GPS updates aren't locked to this engine's 1 Hz tick clock — a live device trace showed
     *    consecutive fix gaps of 1.40s, 2.01s, 2.04s, 0.98s, 1.05s, 0.96s, not a clean 1.0s). That
     *    got variance down to 3.12% (device $8.26 vs. server $8.01) but still over the 1%
     *    tolerance — still flagged. The root cause, verified by construction in
     *    `TripTraceReplayFidelityTest`'s "legacy" case (not merely asserted): a point was recorded
     *    using the STALE FIX'S OWN timestamp, so the trace's temporal coverage tracked the GPS
     *    receiver's jittery update cadence, not the fare engine's steady 1 Hz billing clock. Most
     *    damaging at the very end of a trip: `recompute_from_trace` iterates only over recorded
     *    trace points and never extends past the last one to the trip's real `endAt` — if GPS goes
     *    stale right as the driver stops the meter, EVERY second of real elapsed (and billed)
     *    waiting time between the last GPS fix and the actual close is invisible to the server,
     *    permanently. The identical failure mode can also happen mid-trip during any stale-GPS
     *    window, not only at the end.
     *
     * **Current design (fix 2):** record a point on EVERY tick, unconditionally (whenever there is
     * ANY known fix, fresh or stale), carrying the last known REAL fix's lat/lng/speed forward and
     * stamping it with `Instant.now()` — the tick's own real wall-clock time — rather than the
     * fix's own (possibly much older) GPS timestamp. This makes the trace's temporal resolution
     * track the fare engine's own tick clock instead of the GPS receiver's arrival cadence, so
     * server and device now integrate elapsed time from structurally the same stream, closing both
     * the mid-trip-jitter gap and the end-of-trip tail gap identically. Nothing about the POSITION
     * is fabricated — it is always the last real fix this device actually received, exactly the
     * position the fare engine's own [FareEngineImpl.tick] used for this same tick's speed/mode
     * decision; only the point's timestamp is "now" rather than "whenever the position was last
     * confirmed", which is the timestamp of the actual event being recorded — this tick firing.
     *
     * Returns `null` (append nothing this tick) only when there has never been a live fix at all
     * (no permission, cold start, no signal yet) — an honest "nothing real to record" gap, never a
     * fabricated point.
     */
    private fun nextTracePoint(): TelemetryPointDto? {
        val fix = AppContainer.speedSource.locationFix.value ?: return null
        return TelemetryPointDto(
            lat = fix.lat,
            lng = fix.lng,
            speedKmh = fix.speedKmh,
            ts = Instant.now().toString(),
        )
    }

    private suspend fun doPersistTick(clientUuid: String, state: FareState, point: TelemetryPointDto?) {
        runCatching {
            tripRepository.tick(
                clientUuid = clientUuid,
                newPoints = listOfNotNull(point),
                distanceM = state.distanceKm.movePointRight(3).setScale(0, RoundingMode.HALF_UP).toInt(),
                movingS = state.movingSeconds,
                waitingS = state.waitingSeconds,
                // Real toll-wiring fix (see TripRepository.tick's doc): the
                // live engine's cumulative toll total (from addToll() below)
                // must reach the persisted TripEntity, not just this screen's
                // own display, or S4/S5's reconstructFareState would silently
                // charge $0 in tolls no matter how many chips the driver
                // tapped. `.tolls` is the running total, not a delta — same
                // overwrite convention as distanceM/movingS/waitingS above.
                tolls = state.breakdown.tolls.toPlainString(),
            )
        }
    }

    fun toggleBreakdown() {
        _breakdownExpanded.value = !_breakdownExpanded.value
    }

    fun toggleSpeech(enabled: Boolean) {
        _speechEnabled.value = enabled
    }

    fun togglePause() {
        when (fareState.value.status) {
            TripStatus.HIRED -> fareEngine.pause()
            TripStatus.STOPPED -> fareEngine.resume()
            else -> Unit
        }
    }

    fun addToll(preset: TollPreset) {
        fareEngine.addToll(preset)
    }

    /**
     * Mid-trip passenger-count correction (miscounts happen) — see [HiredScreen]'s small
     * tap-to-edit affordance near the fare display. New method, not a change to any existing call
     * signature on this ViewModel. Updates the live engine immediately (re-deriving
     * [FareState.maxiRateApplied] for [fareState] consumers, e.g. [HiredScreen]'s MAXI RATE chip)
     * and best-effort persists the correction to the open [TripEntity][au.com.threesixty.cabdispatch.data.local.entity.TripEntity]
     * row so the eventual Close & Pay reconstruction bills off the corrected count, not the
     * original one — see [TripRepository.updatePassengerCount]'s doc.
     */
    fun updatePassengerCount(count: Int) {
        fareEngine.updatePassengerCount(count)
        val clientUuid = persistedTripClientUuid ?: return
        viewModelScope.launch {
            runCatching { tripRepository.updatePassengerCount(clientUuid, count) }
        }
    }

    /**
     * Stops the live engine and, once the final tick is durably persisted,
     * invokes [onClosed] (the caller navigates to S4 from there). Deliberately
     * does NOT call [TripRepository.closeTrip] — this is S3's "stop the
     * meter" action, not S4's "finalize with a payment method" action;
     * CloseAndPayViewModel.finalizeClose() is what flips the TripEntity to
     * CLOSED, once the driver has picked a payment method. Awaiting the final
     * persist before navigating (rather than a fire-and-forget [persistTick])
     * avoids a race where S4's [TripRepository.observeActiveTrip] Flow could
     * initialize from the second-to-last tick's counters and then never pick
     * up the final one — see [CloseAndPayViewModel]'s `loadTariffAndInit`
     * guard, which only reacts to the *first* qualifying emission.
     */
    fun endTrip(onClosed: () -> Unit) {
        val closedState = fareEngine.close()
        val clientUuid = persistedTripClientUuid
        if (clientUuid == null) {
            onClosed()
            return
        }
        val point = nextTracePoint()
        viewModelScope.launch {
            doPersistTick(clientUuid, closedState, point)
            SessionHolder.clearLiveTrip()
            onClosed()
        }
    }

    /**
     * Hidden triple-tap-corner duress trigger, per spec B5 S3 / §6 step 8. Delegates the actual
     * state machine (confirmation countdown, `POST /v1/duress/trigger` + retry, GPS relay,
     * dispatcher-resolution poll) to [AppContainer.duressController] — see that class's doc for
     * why it isn't owned here. Previously this just logged a TODO warning and flipped a local
     * flag nothing rendered (see `android/README.md`'s mock-surface table, "Duress networking"
     * row, now stale as of this pass — real backend endpoints exist per
     * `backend/app/api/v1/duress.py` and are wired end to end from here).
     *
     * Twilio SMS fallback-when-offline (spec B7) is NOT part of this pass — that's a
     * backend-triggered escalation stage (`ESCALATION_STAGE_SMS_EMERGENCY_CONTACTS` in
     * `backend/app/models/duress.py`), not something this on-device trigger call drives directly;
     * [AppContainer.duressController]'s trigger/cancel/gps calls are the full driver-device
     * surface per the backend's role policy.
     */
    fun onDuressTriggered() {
        val session = SessionHolder.session.value
        AppContainer.duressController.trigger(vehicleId = session?.vehicleId, driverId = session?.driverId)
    }

    /** Cancel affordance for [HiredScreen]'s "Duress triggered" confirmation overlay — see
     * [DuressUiState.Triggered] and [au.com.threesixty.cabdispatch.domain.DuressController.cancel]. */
    fun cancelDuress() = AppContainer.duressController.cancel()

    /**
     * Best-effort last-known-fix supplier for [AppContainer.duressController]'s GPS relay while
     * [DuressUiState.Active] — same read pattern as
     * `ui/screens/settings/SettingsViewModel.kt#pollGps` (last-known GPS/network fix, not a live
     * subscription; see that function's own TODO about a real fused location provider). Wired
     * in [init]/[onCleared] below since this VM is the only one in the batch holding a [Context]
     * while a trip (and therefore a plausible duress event) is in progress.
     */
    private fun lastKnownFix(): Triple<Double, Double, Float?>? {
        val context = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val fix = listOfNotNull(
            runCatching { locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
        ).minByOrNull { it.accuracy } ?: return null
        return Triple(fix.latitude, fix.longitude, fix.accuracy)
    }

    override fun onCleared() {
        super.onCleared()
        speechAnnouncer.shutdown()
        // Unconditional clear: this VM instance is nav-scoped (recreated per trip, per this
        // file's existing TODO on [fareEngine]) and normal navigation always tears the old
        // instance down before a new one is created, so there is no real window where a newer
        // instance's [lastKnownFix] is live when this fires. If that assumption ever breaks
        // (e.g. two HiredViewModel instances briefly coexisting across a nav transition), the
        // failure mode is GPS relay silently stopping for an in-flight duress event until the
        // next screen with a Context re-installs a supplier — acceptable degradation for a
        // best-effort relay (see [DuressController.locationProvider]'s doc), not a correctness bug.
        AppContainer.duressController.locationProvider = null
    }
}
