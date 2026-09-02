package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.toDomainTariff
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
import au.com.threesixty.cabdispatch.domain.fare.AreaClass
import java.time.DayOfWeek
import java.time.ZonedDateTime
import au.com.threesixty.cabdispatch.domain.fare.FareEngine as CalcFareEngine
import au.com.threesixty.cabdispatch.domain.fare.FareState as CalcFareState
import au.com.threesixty.cabdispatch.domain.fare.TimeClass as CalcTimeClass

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

    /**
     * @param isMaxiVehicle Driver's local self-declaration that this vehicle has 5+ seats
     *   excluding the driver — see [au.com.threesixty.cabdispatch.domain.MaxiVehicleStore]'s doc
     *   for why this is a local declaration, not fleet-registry data. Defaulted `false` so every
     *   call site that never touches the Point to Point Transport (Fares) Order 2026 maxi
     *   controls keeps behaving exactly as before (never maxi-eligible).
     * @param passengerCount Declared passenger count, 1-11. Defaulted `1`, the ordinary case.
     * @param wheelchairHiring True when the hiring is for a wheelchair passenger — per the Fares
     *   Order this carve-out means the maxi rate is never charged regardless of [isMaxiVehicle]/
     *   [passengerCount]/[airportRankRequestedMaxi]. Enforced entirely by the pure engine's own
     *   [au.com.threesixty.cabdispatch.domain.fare.FareState.maxiRateApplied] derivation, not
     *   re-checked here.
     * @param airportRankRequestedMaxi True only when the hirer specifically requested a maxi taxi
     *   at a Sydney Airport rank — the one scenario the maxi rate applies independent of
     *   [passengerCount].
     */
    fun startTrip(
        tariff: TariffDto,
        startLat: Double,
        startLng: Double,
        isMaxiVehicle: Boolean = false,
        passengerCount: Int = 1,
        wheelchairHiring: Boolean = false,
        airportRankRequestedMaxi: Boolean = false,
    )
    fun pause()
    fun resume()
    fun addToll(preset: TollPreset)

    /**
     * Corrects the declared passenger count mid-trip (miscounts happen). Mutates only the shadow
     * [au.com.threesixty.cabdispatch.domain.fare.FareState.passengerCount] input and re-reads that
     * engine's own derived `maxiRateApplied` immediately, so [FareState.maxiRateApplied] (and
     * [FareState.passengerCount]) update on this call — never touching any already-accrued
     * [FareState.breakdown] figure (flagFall/distanceAmount/waitingAmount/peakAmount are raw
     * cumulative sums this class has never multiplied; see this file's class-level doc, point 4,
     * and [au.com.threesixty.cabdispatch.domain.fare.FareEngine.close]'s own doc) — those are
     * exactly the same numbers immediately before and after this call. The eventual maxi
     * multiplier (if [FareState.maxiRateApplied] ends up true) is, per the pure engine's own
     * design, applied ONCE to the whole metered fare at real close/reconstruction time — this
     * class does not invent per-segment multiplier tracking the pure engine itself doesn't have
     * (isMaxiVehicle/wheelchairHiring already work this same "fixed input, applied wholesale at
     * close" way; this just makes passengerCount correctable instead of frozen at [startTrip]).
     * No-op if no trip is open yet (no [startTrip] call preceded this one).
     */
    fun updatePassengerCount(count: Int)
    fun close(): FareState
}

/**
 * Consolidation pass (2026-08-29, user-directed): this class used to reimplement the exact same
 * distance/waiting accrual math a second time, by hand, instead of calling
 * [au.com.threesixty.cabdispatch.domain.fare.FareEngine] — the *actual* NSW-Fares-Order engine,
 * ported line-for-line from the backend's Python implementation and golden-vector-tested against
 * it (see that class's own doc). The two had already drifted apart in four real, found ways
 * before this pass:
 *
 * 1. **Night boundary** — this class used 6am-8pm/8pm-6am; the tested engine (and the backend)
 *    use 10pm-6am. Fixed as its own smaller pass just before this one — see [resolveTimeClass]'s
 *    doc, kept here for the historical record.
 * 2. **Distance-band splitting** — the tested engine's `tick()` splits ONE delta across the 12km
 *    band boundary (charging the pre-threshold portion at rate 1 and the rest at rate 2 within
 *    the SAME tick); this class picked a single rate for the whole tick's delta based on where
 *    cumulative distance landed AFTER adding it. Only matters for the one tick that straddles
 *    12km exactly — a few cents at most — but a real, provable discrepancy.
 * 3. **Waiting-mode distance tracking** — the tested engine folds *any* distance covered while
 *    below the speed threshold into cumulative distance (crawling in traffic still counts toward
 *    the 12km band); this class only advanced `distanceKm` in the distance branch. On a trip with
 *    a lot of slow traffic, this class's on-screen band-switch to the cheaper/dearer long-distance
 *    rate would fire later than the real engine's — a materially bigger drift than #2 on a long
 *    trip.
 * 4. **PSL always-on** — [startTrip] used to unconditionally add `tariff.pslAmount` to the live
 *    breakdown from the moment a trip started. The Point-to-Point levy is actually a driver
 *    decision made at Close & Pay time
 *    ([au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel.setIncludePsl],
 *    persisted as [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.includePsl],
 *    default `false`) — matching the tested engine's own `close(includePsl: Boolean = false)`
 *    default. So the live meter overstated the running total by the PSL amount (~$1.32) for
 *    every single trip, for the entire trip, in the common case (driver leaves it unchecked).
 *    **This never affected the actual bill** — verified: [endTrip]'s [doPersistTick] only ever
 *    persisted `distanceKm`/`movingSeconds`/`waitingSeconds`/tolls off this class's [FareState],
 *    never its money fields; the real, final total is computed entirely separately, offline, by
 *    [au.com.threesixty.cabdispatch.domain.fare.TripFareReconstruction] — which already calls the
 *    tested engine directly. So this was a driver-facing live-display accuracy bug, not a billing
 *    one, but a real one: a driver watching the meter mid-trip saw a total the passenger was very
 *    unlikely to actually be charged.
 *
 * Rather than patch each symptom by hand, this class now delegates every accrual computation to a
 * private [CalcFareEngine] instance driving a shadow [CalcFareState] — the exact same tested code
 * path [TripFareReconstruction] already trusts — and maps its output onto this class's own
 * UI-facing [FareState]/[FareBreakdown] (`domain.FareState`, a different, display-oriented shape
 * from the calc engine's `domain.fare.FareState` — kept as-is here deliberately: [HiredScreen]/
 * [HiredViewModel]'s existing rendering and persistence code reads specific fields
 * (`movingSeconds`, `waitingSeconds`, `currentSpeedKmh`, `band`, `mode`) that have no equivalent
 * on the calc engine's types, so this is a delegation of MATH, not a type-level replacement — zero
 * changes needed to [HiredScreen]/[HiredViewModel]/[TripRepository] call sites).
 */
class FareEngineImpl(
    private val speedSource: SpeedSource,
    private val scope: CoroutineScope,
) : FareEngine {

    private val _state = MutableStateFlow(FareState())
    override val state: StateFlow<FareState> = _state.asStateFlow()

    private var tariff: TariffDto? = null
    private var tickJob: Job? = null

    /** Stateless (per its own doc) — safe to share one instance across the whole trip. */
    private val calcEngine = CalcFareEngine()

    /** The real per-trip accumulator [calcEngine] mutates — `null` before [startTrip]. Every
     * money/distance figure this class shows the driver is read back off this after each tick,
     * never computed independently. */
    private var calcState: CalcFareState? = null

    override fun startTrip(
        tariff: TariffDto,
        startLat: Double,
        startLng: Double,
        isMaxiVehicle: Boolean,
        passengerCount: Int,
        wheelchairHiring: Boolean,
        airportRankRequestedMaxi: Boolean,
    ) {
        this.tariff = tariff
        val domainTariff = tariff.toDomainTariff()
        val area = if (tariff.region.equals("urban", ignoreCase = true)) AreaClass.URBAN else AreaClass.COUNTRY
        val timeClass = resolveTimeClass(area)
        val isPeak = resolveIsPeak()
        val newCalcState = CalcFareState(
            tariff = domainTariff,
            timeClass = timeClass.toCalcTimeClass(),
            isPeak = isPeak,
            isMaxiVehicle = isMaxiVehicle,
            passengerCount = passengerCount,
            wheelchairHiring = wheelchairHiring,
            airportRankRequestedMaxi = airportRankRequestedMaxi,
        )
        calcState = newCalcState

        val peak = if (isPeak) domainTariff.peakCharge else BigDecimal.ZERO

        _state.value = FareState(
            status = TripStatus.HIRED,
            mode = AccrualMode.WAITING,
            band = TariffBand.BAND_1,
            timeClass = timeClass,
            // PSL deliberately NOT included here — see this class's own doc, point 4. It is a
            // driver decision made later, at Close & Pay, never baked into the live display.
            breakdown = FareBreakdown(flagFall = domainTariff.flagFall, peakAmount = peak),
            // Point to Point Transport (Fares) Order 2026 UI-wiring pass: passengerCount/
            // maxiRateApplied copied verbatim off the pure engine's own shadow state — see
            // FareState (domain/TripModels.kt)'s doc on why the UI must never re-derive this
            // itself. wheelchairHiring is fixed at commencement (no updatePassengerCount-style
            // mid-trip correction exists for it — informational display only, see HiredScreen).
            passengerCount = newCalcState.passengerCount,
            wheelchairHiring = newCalcState.wheelchairHiring,
            maxiRateApplied = newCalcState.maxiRateApplied,
        )
        startTicking()
    }

    override fun updatePassengerCount(count: Int) {
        val cs = calcState ?: return
        cs.passengerCount = count
        _state.value = _state.value.copy(
            passengerCount = cs.passengerCount,
            maxiRateApplied = cs.maxiRateApplied,
        )
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
        // Mirrored into the shadow calc state too — harmless today (close() below still returns
        // the UI snapshot directly, matching pre-existing behaviour, not calcEngine.close()'s
        // result), but keeps the two totals from silently disagreeing if a future change ever
        // does read calcState back out.
        calcState?.let { it.tolls = it.tolls.add(preset.amount) }
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

    /**
     * One second of the spec B6 tick loop — now a thin adapter: read the real-time speed, hand it
     * to [calcEngine] (which owns mode/band/rate selection and distance-band splitting), then copy
     * its updated totals onto the UI-facing [FareState]. `distanceDeltaKm` is still speed×1s (this
     * class has never had an independent GPS-distance integration — a pre-existing simplification,
     * not something this consolidation pass changes), but it is now fed into the SAME generic
     * `(speed, distanceDelta, elapsedSeconds)` tick signature the tested engine's own golden
     * vectors exercise, rather than duplicated inline.
     *
     * Accrued amounts are copied RAW (no per-tick rounding) — the tested engine only rounds at
     * [CalcFareEngine.close] time; this class's own [FareBreakdown.total] used to round every
     * single tick to 6 decimal places, a small compounding drift over a long trip. Display
     * formatting ([toMoneyString]) already rounds to cents at render time, so nothing is lost.
     */
    private fun tick() {
        val cs = calcState ?: return
        val speed = speedSource.speedKmh.value
        val threshold = cs.tariff.speedThresholdKmh.toDouble().takeIf { it > 0 } ?: 26.0
        val current = _state.value

        val dKm = BigDecimal.valueOf(speed / 3600.0) // one tick = one second, per startTicking's delay(1000)
        calcEngine.tick(cs, speedKmh = speed, distanceDeltaKm = dKm, elapsedSeconds = 1)

        val mode = if (speed >= threshold) AccrualMode.DISTANCE else AccrualMode.WAITING
        // Computed off TRUE cumulative distance every tick (fix #3 above), not only while in the
        // distance branch — a trip that crawls past 12km in traffic now switches band correctly.
        val band = if (cs.cumulativeDistanceKm <= cs.tariff.distKmThreshold) TariffBand.BAND_1 else TariffBand.BAND_2

        _state.value = current.copy(
            mode = mode,
            band = band,
            distanceKm = cs.cumulativeDistanceKm,
            currentSpeedKmh = speed,
            movingSeconds = current.movingSeconds + if (mode == AccrualMode.DISTANCE) 1 else 0,
            waitingSeconds = current.waitingSeconds + if (mode == AccrualMode.WAITING) 1 else 0,
            breakdown = current.breakdown.copy(
                distanceAmount = cs.accruedDistanceCharge,
                waitingAmount = cs.accruedWaitingCharge,
            ),
        )
    }

    /** [TimeClass] (this file's display-oriented enum, `domain.TimeClass`) -> [CalcTimeClass]
     * (`domain.fare.TimeClass`, the tested engine's own) — same three cases, different enum types
     * because [TimeClass] carries a display [TimeClass.label] the calc engine has no use for. */
    private fun TimeClass.toCalcTimeClass(): CalcTimeClass = when (this) {
        TimeClass.DAY -> CalcTimeClass.DAY
        TimeClass.NIGHT -> CalcTimeClass.NIGHT
        TimeClass.HOLIDAY -> CalcTimeClass.HOLIDAY
    }

    /**
     * time_class fixed at journey commencement per the Fares Order wording — see spec B6.
     *
     * Night boundary is 10pm-6am — real bug fixed 2026-08-29 (found and reported while building
     * the Captain Taxis dashboard's Night Fare tile, confirmed against the backend/architecture
     * agent's own contract doc: "the 10pm-6am boundary is presently hardcoded server-side in the
     * fare engine ... TimeClass.NIGHT"). This function previously used `hour in 6 until 20`
     * (6am-8pm day / 8pm-6am night) — two hours off the server's real boundary, and inconsistent
     * with [resolveIsPeak] a few lines below, which already correctly used `hour >= 22 || hour < 6`
     * for the same Fares Order night window. A trip started between 8pm and 10pm was silently
     * billed at the day rate on this client's live display while the backend's authoritative tick
     * ([ApiService.tickTrip]) billed it at night — this client-side estimate never actually
     * overrode the server's real total (the server ticks win on any discrepancy, per that
     * endpoint's own contract), so no driver was ever charged the wrong amount, but the live
     * on-screen fare during that 2-hour window would have under-read what the final invoice
     * actually charged.
     *
     * **Point to Point Transport (Fares) Order 2026 compliance pass:** the public-holiday
     * calendar this doc used to flag as out of scope is now folded in — see
     * [resolveTimeClassFor]'s doc for the actual (now holiday-calendar-aware) classification
     * rule this delegates to.
     */
    private fun resolveTimeClass(area: AreaClass): TimeClass = resolveTimeClassFor(ZonedDateTime.now(), area)

    /** Peak Time Hiring Charge: urban, hiring commences 10pm-6am Fri/Sat/pre-holiday (spec B6) —
     * see [resolveIsPeakFor]'s doc for the actual rule. */
    private fun resolveIsPeak(): Boolean = resolveIsPeakFor(ZonedDateTime.now())
}

/**
 * time_class classification per the Point to Point Transport (Fares) Order 2026: 10pm-6am any
 * night is [TimeClass.NIGHT] (both areas); for [AreaClass.COUNTRY] only, 6am-10pm on a Sunday or a
 * gazetted NSW public holiday ([NswPublicHolidays]) is [TimeClass.HOLIDAY] (urban has no holiday
 * distance rate at all, so urban never returns [TimeClass.HOLIDAY]); everything else is
 * [TimeClass.DAY]. A top-level function (not a private method) taking an explicit [now] so it is
 * unit-testable without needing to fake the system clock — [FareEngineImpl.resolveTimeClass] is a
 * thin `ZonedDateTime.now()`-supplying wrapper around this.
 */
fun resolveTimeClassFor(now: ZonedDateTime, area: AreaClass): TimeClass {
    val hour = now.hour
    if (hour >= 22 || hour < 6) return TimeClass.NIGHT
    if (area == AreaClass.COUNTRY &&
        (now.dayOfWeek == DayOfWeek.SUNDAY || NswPublicHolidays.isPublicHoliday(now.toLocalDate()))
    ) {
        return TimeClass.HOLIDAY
    }
    return TimeClass.DAY
}

/**
 * Peak Time Hiring Charge eligibility per the Point to Point Transport (Fares) Order 2026: hiring
 * commences 10pm-6am on a Friday, a Saturday, OR the night before a gazetted NSW public holiday
 * ([NswPublicHolidays.isDayBeforePublicHoliday]). Urban only in practice (country's
 * [au.com.threesixty.cabdispatch.domain.fare.COUNTRY_TARIFF] carries no peak charge), but this
 * function itself is area-agnostic — the caller applying a zero peak charge for country is what
 * makes it a no-op there. Top-level per [resolveTimeClassFor]'s same testability rationale.
 */
fun resolveIsPeakFor(now: ZonedDateTime): Boolean {
    val isLateNight = now.hour >= 22 || now.hour < 6
    val isFriSatOrPreHoliday = now.dayOfWeek == DayOfWeek.FRIDAY || now.dayOfWeek == DayOfWeek.SATURDAY ||
        NswPublicHolidays.isDayBeforePublicHoliday(now.toLocalDate())
    return isLateNight && isFriSatOrPreHoliday
}
