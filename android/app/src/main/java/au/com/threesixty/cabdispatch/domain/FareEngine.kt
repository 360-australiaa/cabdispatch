package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * One fused-location fix, as emitted by [SpeedSource.locationFix].
 *
 * Deliberately holds more than the fare engine itself needs ([speedKmh] is the only field
 * [FareEngineImpl.tick] reads) — [lat]/[lng] exist specifically so the *other* two GPS-shaped
 * gaps named in HANDOFF.md (map centering, region auto-detection) have a real position to
 * consume from the same feed, instead of each growing their own separate location plumbing. See
 * `domain/location/RealLocationProvider.kt`'s doc for how this is populated/filtered.
 *
 * @property lat WGS84 latitude, degrees.
 * @property lng WGS84 longitude, degrees.
 * @property speedKmh Ground speed in km/h, never negative. Same value as [SpeedSource.speedKmh]
 *   at the same instant — kept as a field here too so a [locationFix] consumer never needs to
 *   also separately observe [SpeedSource.speedKmh] just to get the speed that came with this fix.
 * @property accuracyM Estimated horizontal accuracy, metres (`Float.MAX_VALUE` if the platform
 *   reported no accuracy for this fix — treat that as "unknown/poor", not "extremely precise").
 * @property timestampMillis Fix time, `System.currentTimeMillis()`-epoch millis (from
 *   `android.location.Location.getTime()`), used for jump/staleness filtering — not a monotonic
 *   clock, don't use it for elapsed-time math against [System.currentTimeMillis] directly.
 */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val speedKmh: Double,
    val accuracyM: Float,
    val timestampMillis: Long,
)

/**
 * Live GPS speed feed the fare engine ticks against — see spec B6 tick loop
 * ("speed = kalman(fused_location)").
 *
 * Real implementation: `domain/location/RealLocationProvider.kt`'s `RealLocationProvider`
 * (FusedLocationProviderClient-backed, wired as `AppContainer.speedSource`). [StubSpeedSource]
 * below remains the fixed-0.0/no-fix fallback for previews, unit tests, and (behaviourally,
 * though not by literal instance-swap) whatever `RealLocationProvider` itself degrades to when
 * ACCESS_FINE_LOCATION isn't granted.
 */
interface SpeedSource {
    val speedKmh: StateFlow<Double>

    /**
     * Latest accepted fix, or `null` before the first fix arrives / whenever location is
     * unavailable (no permission, provider disabled, no signal yet). Consumers that only care
     * about speed (this file's own [FareEngineImpl], [au.com.threesixty.cabdispatch.ui.wheel.WheelGesture]'s
     * speed-lock) can ignore this and keep reading [speedKmh] exactly as before — this property
     * is additive, for consumers that need position (map centering, region auto-detection).
     */
    val locationFix: StateFlow<LocationFix?>
}

/**
 * No-GPS fallback: fixed at 0 km/h / no fix, so the engine defaults to WAITING mode and any
 * position-consuming UI sees "no fix" rather than a fabricated location. Real GPS is
 * `domain/location/RealLocationProvider.kt`'s `RealLocationProvider` (wired as
 * `AppContainer.speedSource`) — this class is kept, deliberately, as the explicit
 * permission-denied/testing/preview fallback (see that class's and [SpeedSource]'s doc), not
 * deleted now that real GPS exists.
 */
class StubSpeedSource : SpeedSource {
    override val speedKmh: StateFlow<Double> = MutableStateFlow(0.0)
    override val locationFix: StateFlow<LocationFix?> = MutableStateFlow(null)
}

/**
 * Meter state machine + fare accrual, per spec B6 ("FOR_HIRE → HIRED →
 * (STOPPED ⇄ HIRED) → CLOSED", 1 Hz tick loop). Owns its own tick coroutine
 * while HIRED; callers drive it purely through [startTrip]/[pause]/[resume]/
 * [addToll]/[close] and observe [state].
 *
 * TODO(integration agent): this instance's lifetime is currently tied to
 * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel] (recreated
 * per nav entry), not a process-scoped/foreground-service-backed singleton —
 * navigating away or a process death mid-trip loses this *live accrual
 * display* state (not the trip record itself, once TripRepository.tickTrip/
 * closeTrip persistence lands). Hoist to AppContainer.fareEngine (or a
 * foreground service) once that matters for a real multi-hour shift.
 */
interface FareEngine {
    val state: StateFlow<FareState>
    fun startTrip(tariff: TariffDto, startLat: Double, startLng: Double)
    fun pause()
    fun resume()
    fun addToll(preset: TollPreset)
    fun close(): FareState
}

class FareEngineImpl(
    private val speedSource: SpeedSource,
    private val scope: CoroutineScope,
) : FareEngine {

    private val _state = MutableStateFlow(FareState())
    override val state: StateFlow<FareState> = _state.asStateFlow()

    private var tariff: TariffDto? = null
    private var tickJob: Job? = null

    override fun startTrip(tariff: TariffDto, startLat: Double, startLng: Double) {
        this.tariff = tariff
        val timeClass = resolveTimeClass()
        val isPeak = resolveIsPeak()
        val flagFall = tariff.flagFall.toBigDecimalOrZero()
        val peak = if (isPeak) tariff.peakCharge.toBigDecimalOrZero() else BigDecimal.ZERO
        val psl = tariff.pslAmount.toBigDecimalOrZero()

        _state.value = FareState(
            status = TripStatus.HIRED,
            mode = AccrualMode.WAITING,
            band = TariffBand.BAND_1,
            timeClass = timeClass,
            breakdown = FareBreakdown(flagFall = flagFall, peakAmount = peak, psl = psl),
        )
        startTicking()
    }

    override fun pause() {
        if (_state.value.status != TripStatus.HIRED) return
        tickJob?.cancel()
        _state.value = _state.value.copy(status = TripStatus.STOPPED)
    }

    override fun resume() {
        if (_state.value.status != TripStatus.STOPPED) return
        _state.value = _state.value.copy(status = TripStatus.HIRED)
        startTicking()
    }

    override fun addToll(preset: TollPreset) {
        val current = _state.value
        _state.value = current.copy(
            breakdown = current.breakdown.copy(tolls = current.breakdown.tolls.add(preset.amount)),
            tollsApplied = current.tollsApplied + preset,
        )
    }

    override fun close(): FareState {
        tickJob?.cancel()
        _state.value = _state.value.copy(status = TripStatus.CLOSED)
        return _state.value
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && _state.value.status == TripStatus.HIRED) {
                delay(1000)
                if (_state.value.status == TripStatus.HIRED) tick()
            }
        }
    }

    /** One second of the spec B6 tick loop. */
    private fun tick() {
        val t = tariff ?: return
        val speed = speedSource.speedKmh.value
        val threshold = t.speedThresholdKmh.toBigDecimalOrZero().toDouble().takeIf { it > 0 } ?: 26.0
        val current = _state.value

        if (speed >= threshold) {
            val dKm = BigDecimal.valueOf(speed / 3600.0) // one tick = one second
            val newDistanceKm = current.distanceKm.add(dKm)
            val distThreshold = t.distKmThreshold.toBigDecimalOrZero().takeIf { it.signum() > 0 } ?: BigDecimal("12")
            val band = if (newDistanceKm <= distThreshold) TariffBand.BAND_1 else TariffBand.BAND_2
            val rate = selectDistanceRate(t, band, current.timeClass)
            val addAmount = dKm.multiply(rate)

            _state.value = current.copy(
                mode = AccrualMode.DISTANCE,
                band = band,
                distanceKm = newDistanceKm,
                currentSpeedKmh = speed,
                movingSeconds = current.movingSeconds + 1,
                breakdown = current.breakdown.copy(
                    distanceAmount = current.breakdown.distanceAmount.add(addAmount),
                ),
            )
        } else {
            val waitingRate = t.waitingRatePerMin.toBigDecimalOrZero()
            val addAmount = waitingRate.divide(BigDecimal(60), 6, RoundingMode.HALF_UP)

            _state.value = current.copy(
                mode = AccrualMode.WAITING,
                currentSpeedKmh = speed,
                waitingSeconds = current.waitingSeconds + 1,
                breakdown = current.breakdown.copy(
                    waitingAmount = current.breakdown.waitingAmount.add(addAmount),
                ),
            )
        }
    }

    private fun selectDistanceRate(t: TariffDto, band: TariffBand, timeClass: TimeClass): BigDecimal {
        val field = when {
            timeClass == TimeClass.NIGHT && band == TariffBand.BAND_1 -> t.nightRate1
            timeClass == TimeClass.NIGHT && band == TariffBand.BAND_2 -> t.nightRate2
            band == TariffBand.BAND_1 -> t.distRate1
            else -> t.distRate2
        }
        return field.toBigDecimalOrZero()
    }

    /**
     * time_class fixed at journey commencement per the Fares Order wording —
     * see spec B6. Holiday detection needs a public-holiday calendar, out of
     * scope here — TODO(fare-engine sibling agent) to fold that in.
     * Simplified day/night boundary: 6am-8pm day, else night.
     */
    private fun resolveTimeClass(): TimeClass {
        val hour = LocalTime.now().hour
        return if (hour in 6 until 20) TimeClass.DAY else TimeClass.NIGHT
    }

    /**
     * Peak Time Hiring Charge: urban, hiring commences 10pm-6am Fri/Sat/
     * pre-holiday (spec B6). Pre-holiday detection out of scope — TODO as
     * above.
     */
    private fun resolveIsPeak(): Boolean {
        val now = ZonedDateTime.now()
        val isLateNight = now.hour >= 22 || now.hour < 6
        val isFriOrSat = now.dayOfWeek == DayOfWeek.FRIDAY || now.dayOfWeek == DayOfWeek.SATURDAY
        return isLateNight && isFriOrSat
    }
}

private fun String.toBigDecimalOrZero(): BigDecimal = runCatching { BigDecimal(this) }.getOrDefault(BigDecimal.ZERO)
