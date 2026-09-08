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
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import au.com.threesixty.cabdispatch.domain.AlertTone
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.ToneGeneratorAlertTone
import au.com.threesixty.cabdispatch.domain.SpeechPriority
import au.com.threesixty.cabdispatch.domain.TextToSpeechAnnouncer
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.domain.toMoneyString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class HiredViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * The running meter — **process-scoped**, owned by [AppContainer.meterController], not by this
     * ViewModel.
     *
     * F4 (architecture audit 2026-09-08, §2.1, rated blocker) resolved the standing TODO that used
     * to sit here. This class constructed its own [FareEngineImpl] on `viewModelScope`, which tied
     * the fare's lifetime to a navigation entry: press Back and the tick loop was cancelled, let
     * Doze suspend a backgrounded app and it stopped, lose the process and it was gone — each of
     * them a trip that quietly stops being charged for with the passenger still in the car.
     *
     * This ViewModel is now what the audit asked for: a thin observer. It reads [fareState] and
     * forwards driver actions. It owns no fare state, starts no tick loop, and persists nothing —
     * [MeterController] does all three, for as long as the *hiring* lasts rather than as long as
     * this screen does. Creating and destroying this class as often as navigation likes is now
     * free.
     */
    private val meter = AppContainer.meterController

    val fareState: StateFlow<FareState> = meter.state

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

    /** The literal "beep" of the automatic toll-detection requirement — see [AlertTone]'s own doc
     * for why it deliberately does NOT honour [speechEnabled] the way the spoken alert below does. */
    private val alertTone: AlertTone = ToneGeneratorAlertTone()
    private var lastAnnouncedDollar = -1

    // --- Room persistence (integration pass) ---
    //
    // Was entirely missing: this ViewModel drove [fareEngine]'s in-memory
    // state and nothing else, so no TripEntity row ever existed for S4/S5 to
    // read (see AppContainer.kt's note by [pureFareEngine] and the
    // now-resolved TODO that used to be in CloseAndPayViewModel.kt). Opens
    // the trip in Room the moment the live engine starts, then keeps it
    // updated on every live-engine emission (by MeterController, which owns
    // that subscription now) so
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
            // Opens the Room row and starts the process-scoped meter against it. The
            // `fareState.onEach { ... }` Room-persistence subscription that used to be launched here on
            // viewModelScope has moved into MeterController along with the engine — see that
            // class's doc for why hoisting the accrual without its persistence would have been
            // worse than hoisting neither.
            openTripInRoom(tripContext)
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

        // Automatic NSW toll-road detection audible confirmation (product requirement, 2026-09):
        // "when vehicle move from that location diameter, automatically it will make beep sound
        // and show toll has been added". Reuses this same speechAnnouncer (respecting the exact
        // same speechEnabled mute the "Fare now N dollars" announcement above already honours — a
        // driver who has muted the app must not suddenly hear anything) rather than a new sound
        // asset/audio path. Keyed on FareState.lastAutoTollAlert.id (see that field's own doc) so
        // this fires exactly once per real detected/revised charge, never on an unrelated
        // recomposition-driving emission. SpeechPriority.TOLL_ALERT (see that enum's own doc) is
        // deliberately non-coalescing and ranked above FARE — this alert must never be silently
        // dropped just because a fare-dollar announcement happens to enqueue moments later; a
        // driver who can't hear WHICH toll fired can't judge whether it was wrong.
        fareState
            .map { it.lastAutoTollAlert }
            .distinctUntilChanged { old, new -> old?.id == new?.id }
            .onEach { alert ->
                if (alert == null) return@onEach
                // The beep always plays; the spoken detail is the opt-in extra on top of it. See
                // AlertTone's doc: spoken announcements are off by default, so gating the beep on
                // them would mean a freshly-installed tablet silently adds money to the fare.
                alertTone.tollDetected()
                if (_speechEnabled.value) {
                    speechAnnouncer.announce("${alert.roadName} toll added — ${alert.amount.toMoneyString()}", SpeechPriority.TOLL_ALERT)
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
        // Started BEFORE the suspending Room write below, not after: startTrip() sets the
        // initial FareState synchronously (status/timeClass/peak breakdown), and the `initial` read
        // a few lines down depends on that having already happened — the same ordering the old
        // code relied on when it called fareEngine.startTrip() from init.
        meter.startTrip(tripContext, clientUuid)
        viewModelScope.launch {
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
                // breakdown is exactly and only true when the fare engine
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

    fun toggleBreakdown() {
        _breakdownExpanded.value = !_breakdownExpanded.value
    }

    fun toggleSpeech(enabled: Boolean) {
        _speechEnabled.value = enabled
    }

    fun togglePause() {
        when (fareState.value.status) {
            TripStatus.HIRED -> meter.pause()
            TripStatus.STOPPED -> meter.resume()
            else -> Unit
        }
    }

    fun addToll(preset: TollPreset) {
        meter.addToll(preset)
    }

    /** Driver-initiated correction of an auto-detected toll — see [au.com.threesixty.cabdispatch.domain.FareEngine.removeAutoToll]'s
     * doc. The next state emission -- which MeterController's own Room subscription is watching
     * -- durably reflects the correction, same "no separate persistence call needed" pattern
     * [addToll] already relies on. */
    fun removeAutoToll(roadId: String) {
        meter.removeAutoToll(roadId)
    }

    /** Driver dismissal of a "needs manual toll" notice — see [au.com.threesixty.cabdispatch.domain.FareEngine.dismissUnpricedToll]'s doc. */
    fun dismissUnpricedToll(roadId: String) {
        meter.dismissUnpricedToll(roadId)
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
        meter.updatePassengerCount(count)
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
     * persist before navigating (rather than a fire-and-forget one)
     * avoids a race where S4's [TripRepository.observeActiveTrip] Flow could
     * initialize from the second-to-last tick's counters and then never pick
     * up the final one — see [CloseAndPayViewModel]'s `loadTariffAndInit`
     * guard, which only reacts to the *first* qualifying emission.
     */
    fun endTrip(onClosed: () -> Unit) {
        // Stops the engine, ends the persistence subscription, and takes the foreground service
        // down — see MeterController.stopTrip's doc for why the service ends with the fare rather
        // than with Close & Pay.
        val closedState = meter.stopTrip()
        val clientUuid = persistedTripClientUuid
        if (clientUuid == null) {
            onClosed()
            return
        }
        // One last synchronous persist of the final state before navigating. MeterController's own
        // subscription has just been cancelled by stopTrip(), and awaiting this write (rather than
        // firing and forgetting) is what stops S4's `observeActiveTrip` Flow from initialising off
        // the second-to-last tick's counters and never seeing the final one — see
        // CloseAndPayViewModel's `loadTariffAndInit` guard, which only reacts to the FIRST
        // qualifying emission.
        viewModelScope.launch {
            runCatching {
                tripRepository.tick(
                    clientUuid = clientUuid,
                    newPoints = emptyList(),
                    distanceM = closedState.distanceKm.movePointRight(3).setScale(0, RoundingMode.HALF_UP).toInt(),
                    movingS = closedState.movingSeconds,
                    waitingS = closedState.waitingSeconds,
                    tolls = closedState.breakdown.tolls.toPlainString(),
                    autoTolledRoads = closedState.autoTollsApplied.associate { it.roadId to it.amount.toPlainString() },
                    unpricedTollRoadIds = closedState.unpricedTollRoads.map { it.roadId },
                    accruedDistanceCharge = closedState.breakdown.distanceAmount.toPlainString(),
                    accruedWaitingCharge = closedState.breakdown.waitingAmount.toPlainString(),
                )
            }
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
        alertTone.shutdown()
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
