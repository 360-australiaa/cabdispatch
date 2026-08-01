package au.com.threesixty.cabdispatch.ui.screens.hired

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.repository.TripRepository
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

class HiredViewModel(application: Application) : AndroidViewModel(application) {

    // TODO(integration agent): see FareEngine's doc comment — this instance
    // is recreated per nav entry, not process-scoped.
    private val fareEngine: FareEngine = FareEngineImpl(AppContainer.speedSource, viewModelScope)

    val fareState: StateFlow<FareState> = fareEngine.state

    private val _speechEnabled = MutableStateFlow(false)
    val speechEnabled: StateFlow<Boolean> = _speechEnabled.asStateFlow()

    private val _breakdownExpanded = MutableStateFlow(false)
    val breakdownExpanded: StateFlow<Boolean> = _breakdownExpanded.asStateFlow()

    private val _duressTriggered = MutableStateFlow(false)
    val duressTriggered: StateFlow<Boolean> = _duressTriggered.asStateFlow()

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
        val tripContext = SessionHolder.pendingTrip.value
        if (tripContext != null) {
            fareEngine.startTrip(tripContext.tariff, tripContext.startLat, tripContext.startLng)
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
        viewModelScope.launch {
            // fareEngine.startTrip() above set the initial state synchronously
            // (status/timeClass/peak breakdown), so this read is safe here.
            val initial = fareState.value
            val trip = tripRepository.openTrip(
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
     */
    private fun persistTick(state: FareState) {
        val clientUuid = persistedTripClientUuid ?: return
        viewModelScope.launch { doPersistTick(clientUuid, state) }
    }

    private suspend fun doPersistTick(clientUuid: String, state: FareState) {
        runCatching {
            tripRepository.tick(
                clientUuid = clientUuid,
                newPoints = emptyList(),
                distanceM = state.distanceKm.movePointRight(3).setScale(0, RoundingMode.HALF_UP).toInt(),
                movingS = state.movingSeconds,
                waitingS = state.waitingSeconds,
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
        viewModelScope.launch {
            doPersistTick(clientUuid, closedState)
            onClosed()
        }
    }

    /**
     * Hidden triple-tap-corner duress trigger, per spec B5 S3. Actual duress
     * networking (Twilio SMS fallback when offline, per B7) is a backend
     * concern wired later — TODO(backend/duress sibling agent): call the
     * real duress endpoint / SMS fallback here.
     */
    fun onDuressTriggered() {
        Log.w("CabDispatch", "Duress gesture triggered — TODO: wire to real duress dispatch")
        _duressTriggered.value = true
    }

    override fun onCleared() {
        super.onCleared()
        speechAnnouncer.shutdown()
    }
}
