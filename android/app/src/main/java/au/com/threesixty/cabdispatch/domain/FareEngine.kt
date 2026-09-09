package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.TollDetectionState
import au.com.threesixty.cabdispatch.domain.fare.chargeDisplayName
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.dismissCharge
import au.com.threesixty.cabdispatch.domain.fare.knownCorridorDistanceKm
import au.com.threesixty.cabdispatch.domain.fare.onFix
import au.com.threesixty.cabdispatch.domain.fare.toDomainTariff
import au.com.threesixty.cabdispatch.domain.location.GeoMath
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
import au.com.threesixty.cabdispatch.domain.fare.NSW_FARE_ZONE
import java.time.DayOfWeek
import kotlin.math.roundToInt
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
 * @property heading Compass bearing in degrees (0=north), from
 *   `android.location.Location.getBearing()` when `Location.hasBearing()` is true; `null` when
 *   the platform reports no bearing (e.g. device stationary) — never fabricated, same honest-null
 *   convention as [accuracyM]'s own handling here. Defaulted to `null` so this field is additive:
 *   every call site that already constructs a [LocationFix] without naming it (there was exactly
 *   one, `RealLocationProvider.onNewFix`, checked when this field was added) keeps compiling.
 *   Threaded through to `PositionPublishRequestDto.heading` by
 *   [au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat.publishOnce] so the dispatcher
 *   Live Map can orient the vehicle marker, not just place it.
 */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val speedKmh: Double,
    val accuracyM: Float,
    val timestampMillis: Long,
    val heading: Double? = null,
    /**
     * The value of [System.nanoTime] at the instant this fix object was constructed -- i.e. when
     * the device *received* it, on the monotonic clock, as opposed to [timestampMillis], which is
     * when the GPS constellation says the fix was *taken*, on the wall clock.
     *
     * F3 (architecture audit 2026-09-08, §2.1): [FareEngineImpl.tick] needs to answer "is what I
     * know about this vehicle's motion still true?", and that is a question about elapsed time on
     * a clock that cannot jump. [timestampMillis] is unusable for it on both counts -- it is
     * wall-clock (an NTP correction or a driver changing the tablet's date mid-shift moves it
     * arbitrarily, in either direction) and it is the *receiver's* clock, not this device's, so
     * differencing it against a local `System.currentTimeMillis()` measures clock skew as much as
     * staleness. This field is differenced against [FareEngineImpl]'s own monotonic tick clock, so
     * the age it yields is real elapsed time on one clock and nothing else.
     *
     * Defaulted to `System.nanoTime()` rather than to a sentinel: the default is evaluated at each
     * construction, and every real construction site
     * ([au.com.threesixty.cabdispatch.domain.location.RealLocationProvider.onNewFix]) builds the
     * fix at the moment it arrives, so the default *is* the correct value there. That keeps the
     * field additive -- every pre-existing call site (test fixtures included) that never names it
     * keeps compiling and gets an honest answer -- while letting a test pin an explicit age.
     */
    val receivedAtNanos: Long = System.nanoTime(),
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
 * Supplies the cached NSW toll-road registry [FareEngineImpl] auto-detects crossings against — a
 * thin seam so [FareEngineImpl] itself stays a plain-JVM-testable class with no Room/network
 * dependency, same reasoning [SpeedSource] already gives for not depending on
 * `FusedLocationProviderClient` directly. Real implementation:
 * [au.com.threesixty.cabdispatch.data.AppContainer.tollRegistryCache] wrapped as a
 * `TollRegistryProvider { AppContainer.tollRegistryCache.snapshot() }` — a pure, fast local Room
 * read (see that class's doc), never a network call on this path.
 */
fun interface TollRegistryProvider {
    suspend fun snapshot(): TollRegistrySnapshot

    companion object {
        /**
         * No-cache fallback: an always-empty registry, so [FareEngineImpl] detects nothing and the
         * meter falls back to exactly today's manual-only toll behaviour — the correct, honest
         * default for every existing call site (this file's own tests included) that constructs a
         * [FareEngineImpl] without naming a real provider, and for a genuinely empty on-device
         * cache (see [au.com.threesixty.cabdispatch.sync.TollRegistryCache]'s "offline-empty-cache"
         * doc) — never a crash, never a guessed toll.
         */
        val EMPTY = TollRegistryProvider { TollRegistrySnapshot.EMPTY }
    }
}

/**
 * The seam [FareEngineImpl.tick] decides a GPS-blackout's known-corridor catch-up distance
 * through — same "engine stays a plain-JVM-testable class with no Room/network dependency" reasoning
 * [AirportZoneLookup] already gives, and the same shape: a pure function of two fixes, defaulted to
 * an honest no-op.
 *
 * See [au.com.threesixty.cabdispatch.domain.fare.knownCorridorDistanceKm]'s own doc for what this
 * actually decides (the owner's 2026-09-09 ruling on billing a GPS blackout at the distance rate when
 * it can be explained by a known, mapped toll-road corridor) and why a straight-line entry/exit
 * distance is never good enough for a curving tunnel.
 */
fun interface KnownCorridorDistanceLookup {
    /**
     * Known real distance (km) travelled between ([entryLat],[entryLng]) — the last GPS fix before a
     * blackout — and ([exitLat],[exitLng]) — the first fix after it — if, and only if, both fixes
     * plausibly lie on the SAME mapped toll-road corridor. `null` means "cannot be explained by a
     * known corridor" — [FareEngineImpl.tick] treats that exactly like today's conservative default:
     * the blackout accrues nothing, never a dead-reckoning or waiting-time guess.
     */
    fun knownDistanceKm(entryLat: Double, entryLng: Double, exitLat: Double, exitLng: Double): BigDecimal?

    companion object {
        /**
         * No-known-corridor default — every pre-existing call site (this file's own tests included)
         * keeps its exact current behaviour: an unexplained blackout accrues nothing.
         *
         * Production needs no separate wiring for this to actually work end to end: this default is
         * what a caller that never names the parameter gets, but [FareEngineImpl.tick] ALSO always
         * tries the same already-loaded [TollRegistrySnapshot] it uses for toll detection (see that
         * method's "known-corridor" section) whenever this constructor-injected lookup itself answers
         * `null` — so a real device, which always has a real (possibly empty) cached registry, gets
         * real corridor matching automatically. This constructor seam exists purely so a TEST can
         * wire a fixture registry straight through [of], bypassing the async
         * `TollRegistryProvider.snapshot()` load entirely.
         */
        val NONE = KnownCorridorDistanceLookup { _, _, _, _ -> null }

        /** A lookup backed by a fixed [TollRegistrySnapshot] — what a test wires when it wants
         * known-corridor matching without touching [TollRegistryProvider] at all. */
        fun of(registry: TollRegistrySnapshot): KnownCorridorDistanceLookup =
            KnownCorridorDistanceLookup { entryLat, entryLng, exitLat, exitLng ->
                knownCorridorDistanceKm(registry, entryLat, entryLng, exitLat, exitLng)
            }
    }
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
     * @param negotiatedTotal "Set Price" amount agreed with the passenger before the meter started
     *   ([au.com.threesixty.cabdispatch.domain.TripContext.negotiatedTotal]), or `null` for an
     *   ordinary metered trip (the default — every pre-existing call site that never named this
     *   keeps behaving exactly as before). See [FareState.negotiatedTotal]/`.total` for how this
     *   changes what the live dial shows without changing what actually gets billed at close.
     */
    fun startTrip(
        tariff: TariffDto,
        startLat: Double,
        startLng: Double,
        isMaxiVehicle: Boolean = false,
        passengerCount: Int = 1,
        wheelchairHiring: Boolean = false,
        airportRankRequestedMaxi: Boolean = false,
        negotiatedTotal: BigDecimal? = null,
    )
    /**
     * Re-enters a trip that is already under way, from a state rebuilt off the persisted trip row --
     * the process-restart path. See [FareEngineImpl.resumeTrip]'s doc for the full reasoning; on
     * this interface it exists so [au.com.threesixty.cabdispatch.domain.MeterController] can drive
     * restoration without depending on the implementation type.
     */
    fun resumeTrip(
        tariff: TariffDto,
        restored: au.com.threesixty.cabdispatch.domain.fare.FareState,
        movingSeconds: Int,
        waitingSeconds: Int,
        tollsApplied: List<TollPreset> = emptyList(),
        autoTollsApplied: List<AutoTollEntry> = emptyList(),
    )

    fun pause()
    fun resume()
    fun addToll(preset: TollPreset)

    /**
     * Driver-initiated correction: removes an auto-detected toll charge for [roadId] (see
     * [FareState.autoTollsApplied]) — the "the driver must be able to see and correct what was
     * auto-added" requirement this whole feature is built around. Subtracts the charged amount
     * back out of [FareState.breakdown]'s tolls total and permanently stops that road from being
     * auto-charged again for the REST of this trip (a false positive the driver has already
     * corrected must stay corrected, even if the vehicle re-crosses the same gantry in queued
     * traffic) — see [au.com.threesixty.cabdispatch.domain.fare.dismissCharge]'s own doc. No-op if
     * [roadId] was never actually auto-charged (nothing to remove).
     */
    fun removeAutoToll(roadId: String)

    /**
     * Driver-initiated dismissal of a "this road needs a manual toll" notice (see
     * [FareState.unpricedTollRoads]) — UI-only housekeeping, never re-enables auto-charging for
     * [roadId] (a `zone_flat`/unpriced road is never auto-charged regardless of this call; see
     * [au.com.threesixty.cabdispatch.domain.fare.onFix]'s own doc for why).
     */
    fun dismissUnpricedToll(roadId: String)

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
 * **2026-09 update — PSL is back, deliberately, and for a different reason than #4 above got it
 * removed:** the driver-optional `includePsl` toggle #4 describes is now gone entirely — the PSL
 * is a mandatory Fares Order pass-through, unconditionally added at Close & Pay
 * ([au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel.loadTariffAndInit]'s
 * `includePsl = true`, no remaining toggle to turn it off) — so #4's actual finding ("the live
 * total overstated what would be billed") can no longer happen: PSL is now ALWAYS billed, so a live
 * total that always shows it is always honest, never an overstatement. Product report (2026-09):
 * the meter should start at flagfall + PSL (e.g. $5.17 + $1.32), not flagfall alone, because that
 * is what the passenger will actually pay from the first second — [startTrip] now seeds
 * [FareBreakdown.psl] from `domainTariff.pslAmount` (never hardcoded) instead of leaving it at
 * zero for the trip's whole duration. See [FareState.total]'s doc for the same "show what will
 * actually be charged, from the start" fix applied to tolls/waiting/negotiated-price display too.
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
    /** See [TollRegistryProvider]'s own doc. Defaulted to [TollRegistryProvider.EMPTY] so every
     * pre-existing call site (this file's own tests included) keeps compiling/behaving exactly as
     * before — no toll auto-detection, manual [addToll] chips only, same as today. */
    private val tollRegistryProvider: TollRegistryProvider = TollRegistryProvider.EMPTY,
    /**
     * The monotonic clock [tick] measures its own elapsed time against, and the clock
     * [LocationFix.receivedAtNanos] ages are computed on. Defaulted to the real
     * [System.nanoTime], so every pre-existing call site keeps behaving exactly as in production.
     *
     * A seam rather than a direct `System.nanoTime()` call for one reason: this class's tests run
     * on `kotlinx.coroutines.test`'s **virtual** time, where `advanceTimeBy(1000)` moves the
     * scheduler a second forward without a nanosecond of real time passing. A [tick] that read the
     * real clock would measure `dt ~= 0` under every such test and quietly assert nothing about
     * the very accrual F1 is about. Tests pass `{ testScheduler.currentTime * 1_000_000L }` and
     * get a clock that advances exactly as much as the coroutine machinery believes it did.
     */
    private val nanoTimeSource: () -> Long = { System.nanoTime() },
    /**
     * Where the airport pickup fee is decided from — see [AirportZoneLookup]'s own doc. Defaulted
     * to [AirportZoneLookup.UNSYNCED] ("no zone has ever been cached") so every pre-existing call
     * site keeps charging exactly as before this seam existed: the compiled
     * [JurisdictionConfig] precinct circle. Production passes
     * [au.com.threesixty.cabdispatch.sync.AirportZoneCache].
     */
    private val airportZoneLookup: AirportZoneLookup = AirportZoneLookup.UNSYNCED,
    /**
     * See [KnownCorridorDistanceLookup]'s own doc. Defaulted to [KnownCorridorDistanceLookup.NONE]
     * so every pre-existing call site (this file's own tests included) keeps compiling and behaving
     * exactly as before — an unexplained GPS blackout still accrues nothing. A test that wants
     * known-corridor matching passes [KnownCorridorDistanceLookup.of] with a fixture registry; real
     * production traffic gets it for free from [tollRegistry] (see [tick]'s "known-corridor"
     * section) with no separate wiring needed here.
     */
    private val knownCorridorDistanceLookup: KnownCorridorDistanceLookup = KnownCorridorDistanceLookup.NONE,
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

    /** This trip's toll-detection dedup bookkeeping — see [TollDetectionState]'s own doc for why
     * this lives in-memory here rather than Room-backed (same lifetime as every other live-accrual
     * field on this class). Fresh per [FareEngineImpl] instance, matching [calcState]. */
    private val tollDetectionState = TollDetectionState()

    /** Loaded once, asynchronously, at [startTrip] time — `null` until that load completes (a fast
     * local Room read via [tollRegistryProvider], not a network wait; see [TollRegistryProvider]'s
     * own doc). [tick] simply skips toll detection for any fix that arrives before this is ready,
     * which in practice is a handful of fixes at most at trip start, never a missed real crossing
     * later in a normal-length trip. */
    private var tollRegistry: TollRegistrySnapshot? = null

    /** Monotonic source for [FareState.lastAutoTollAlert]'s [AutoTollAlert.id] — a plain
     * incrementing counter (not a timestamp/random value) is enough since this engine is only ever
     * driven from one coroutine at a time (its own tick loop); every real alert just needs an id
     * distinct from the one before it. */
    private var autoTollAlertSeq = 0L

    /**
     * [nanoTimeSource] reading taken at the end of the previous [tick] (or at [startTicking], for
     * the first one) -- the baseline the next tick's real elapsed time is measured from. `null`
     * only before the meter has ever started ticking.
     *
     * F1 (architecture audit 2026-09-08, §2.1, rated **blocker**). See [tick]'s own doc.
     */
    private var lastTickNanos: Long? = null

    /**
     * The [SpeedSource.locationFix] value this class saw on the *previous* tick -- the other end of
     * the haversine segment F2 charges for, and the identity check ("is this the same object I
     * already billed for?") that decides whether a new fix arrived at all this tick.
     *
     * Deliberately compared by reference (`!==`), not by value: two genuinely distinct fixes taken
     * a second apart at a red light can be field-for-field equal, and treating those as "no new
     * fix" would silently drop back to the speed-integration fallback for the one case where the
     * haversine is most obviously right (zero metres travelled). [SpeedSource.locationFix] hands
     * out a new instance per accepted fix, so reference identity is exactly the question being
     * asked.
     */
    private var lastTickFix: LocationFix? = null

    /**
     * Last speed this engine actually *knew*, from a fix that was fresh at the time -- retained
     * across a GPS blackout so [tick] can answer F3's question: was this vehicle stationary when
     * the sky closed over, or was it doing 80 into a tunnel? Those two need opposite treatment (see
     * [tick]'s doc), and once the fixes stop arriving there is no other way to tell them apart.
     */
    private var lastKnownSpeedKmh: Double = 0.0

    /**
     * The last known-good fix immediately before the CURRENT GPS blackout began, or `null` when no
     * blackout is in progress right now.
     *
     * Captured once, on the very first tick a blackout is declared, from that tick's own
     * `previousFix` — the prior tick's genuinely live fix (see [tick]'s "known-corridor" section for
     * exactly why that value, at that moment, is the right one). Deliberately a SEPARATE field from
     * [lastTickFix], which is nulled the instant GPS is lost (see that field's own doc) specifically
     * so the ordinary F2 haversine path can never draw one segment across the whole blackout — this
     * field's entire purpose is to survive past that null-out, to the recovery tick.
     */
    private var blackoutEntryFix: LocationFix? = null

    /**
     * Whether the vehicle was moving (i.e. NOT [wasStationaryWhenLost][tick]) at the exact instant
     * [blackoutEntryFix] was captured — set alongside it, once, per blackout.
     *
     * Gates the known-corridor catch-up in [tick]: a blackout where the vehicle was already
     * stationary when signal dropped bills WAITING time throughout it already (F3's existing "still
     * genuinely waiting" branch) — layering a corridor's known distance on top of that would double-
     * bill the same minutes. The catch-up only ever applies to the "distance frozen, nothing accrued"
     * case a known corridor is meant to fix.
     */
    private var blackoutEntryWasMoving: Boolean = false

    /**
     * Fractional-second accumulators behind [FareState.movingSeconds]/[FareState.waitingSeconds].
     *
     * Those two are `Int` because [au.com.threesixty.cabdispatch.data.local.entity.TripEntity]
     * stores whole seconds, and they used to be incremented by a flat `+1` per tick. Under F1's
     * real `dt` that would throw the measured elapsed time away again at the very last step --
     * and these counters are not cosmetic: the server replays the trip against them
     * (`recompute_from_trace`) and flags a fare whose device total drifts past 1%. Accumulating
     * in `Double` and rounding only at publish time keeps the whole-second contract for storage
     * while making the underlying tally track real time rather than tick count.
     */
    private var movingSecondsAccum: Double = 0.0
    private var waitingSecondsAccum: Double = 0.0

    override fun startTrip(
        tariff: TariffDto,
        startLat: Double,
        startLng: Double,
        isMaxiVehicle: Boolean,
        passengerCount: Int,
        wheelchairHiring: Boolean,
        airportRankRequestedMaxi: Boolean,
        negotiatedTotal: BigDecimal?,
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
            negotiatedTotal = negotiatedTotal,
        )
        calcState = newCalcState
        // Every per-trip tick accumulator starts clean. This class is process-scoped now (F4 --
        // see AppContainer.fareEngine), so an instance genuinely does outlive a trip and a second
        // startTrip() on the same object is the ordinary case, not a test-only one; leaving these
        // carrying the previous trip's totals would bill the new passenger for the old one's time.
        movingSecondsAccum = 0.0
        waitingSecondsAccum = 0.0
        lastTickFix = null
        lastKnownSpeedKmh = 0.0
        blackoutEntryFix = null
        blackoutEntryWasMoving = false
        autoTollAlertSeq = 0L
        tollDetectionState.reset()

        val peak = if (isPeak) domainTariff.peakCharge else BigDecimal.ZERO

        _state.value = FareState(
            status = TripStatus.HIRED,
            mode = AccrualMode.WAITING,
            band = TariffBand.BAND_1,
            timeClass = timeClass,
            // Set here as well as in tick(): the dial renders the instant the fare starts, a whole
            // second before the first tick, and would otherwise mark the default threshold on a
            // country tariff for that second.
            speedThresholdKmh = domainTariff.speedThresholdKmh.toDouble().takeIf { it > 0 }
                ?: DEFAULT_SPEED_THRESHOLD_KMH,
            // Point-to-Point Levy fix (product-reported, 2026-09): the live meter must start at
            // flagfall + PSL, not flagfall alone (the PSL is a mandatory Fares Order pass-through —
            // see CloseAndPayViewModel's own `includePsl = true` doc — never a driver-optional
            // extra the way the old, now-removed includePsl toggle treated it). Sourced from
            // domainTariff.pslAmount, never hardcoded, so a tariff change changes this too. This
            // does NOT change what gets billed: Close & Pay always added PSL at close time
            // already (unconditionally, since the 2026-09-05 fix removed the driver toggle) — this
            // only stops the live dial from hiding it until the very end.
            breakdown = FareBreakdown(flagFall = domainTariff.flagFall, peakAmount = peak, psl = domainTariff.pslAmount),
            // Point to Point Transport (Fares) Order 2026 UI-wiring pass: passengerCount/
            // maxiRateApplied copied verbatim off the pure engine's own shadow state — see
            // FareState (domain/TripModels.kt)'s doc on why the UI must never re-derive this
            // itself. wheelchairHiring is fixed at commencement (no updatePassengerCount-style
            // mid-trip correction exists for it — informational display only, see HiredScreen).
            passengerCount = newCalcState.passengerCount,
            wheelchairHiring = newCalcState.wheelchairHiring,
            maxiRateApplied = newCalcState.maxiRateApplied,
            // "Set Price" fix (product-reported, 2026-09): copied verbatim off the pure engine's
            // own shadow state, same pattern as maxiRateApplied above — see FareState.negotiatedTotal/
            // `.total`'s doc for how this changes what the dial shows without changing the close()
            // math (which already handled this correctly; only the live display didn't know).
            negotiatedTotal = newCalcState.negotiatedTotal,
            // F8: the dial is authoritative from its very first frame, not only from the first
            // tick a second later. Same close()-derived figure every subsequent tick republishes.
            runningTotal = runningTotal(newCalcState),
        )
        // Load the cached toll registry in the background (see [tollRegistry]'s own doc) — never
        // awaited here, so a slow/empty cache can never delay the meter actually starting.
        scope.launch { tollRegistry = runCatching { tollRegistryProvider.snapshot() }.getOrNull() }
        startTicking()
        // AIRPORT PICKUP FEE (2026-09-08; server-defined zones 2026-09-09). Charged once per
        // hiring that STARTS inside an airport zone -- the passenger picked up at the rank pays it
        // -- never for a drop-off that merely drives in, because only the start fix is ever
        // tested (nothing in [tick] looks at these zones). Same ledger id as the manual Airport
        // preset, so the breakdown, the receipt and the server's toll total all see one thing;
        // [addToll]'s guard then makes a manual tap on the same trip a no-op, never a second fee.
        //
        // Three honest answers from the lookup (see AirportZoneLookup.zonesContaining):
        //  - zones found  -> the SMALLEST containing zone's own fee, labelled with its terminal;
        //  - empty list   -> synced, and this pickup is not at an airport rank: no fee;
        //  - null         -> this tablet has never cached a zone: fall back to the compiled
        //                    JurisdictionConfig precinct circle + constant, so an un-synced tablet
        //                    still charges the regulated fee rather than silently nothing.
        airportFeeAutoApplied = false
        val containingZones = airportZoneLookup.zonesContaining(startLat, startLng)
        if (containingZones != null) {
            containingZones.firstOrNull()?.let { zone ->
                addToll(TollPresets.airportAccessFee(zone))
                airportFeeAutoApplied = true
            }
        } else {
            val jurisdiction = JurisdictionConfig.NSW
            if (jurisdiction.airportAccessFee != null && jurisdiction.isInsideAirportPrecinct(startLat, startLng)) {
                addToll(TollPresets.AIRPORT)
                airportFeeAutoApplied = true
            }
        }
    }

    /**
     * Picks an already-running trip back up, mid-hiring, against a fare state rebuilt from the
     * persisted [au.com.threesixty.cabdispatch.data.local.entity.TripEntity] row rather than
     * started from flagfall.
     *
     * F4 (architecture audit 2026-09-08, §2.1, rated **blocker**). Before the process-lifetime
     * hoist, the *only* way this engine ever entered a trip was [startTrip], because the engine
     * itself could not outlive the screen: kill the app mid-fare and there was no engine left to
     * restore into. Now that [au.com.threesixty.cabdispatch.domain.MeterForegroundService] keeps
     * the meter alive for the whole hiring, the mirror case matters -- the OS killed the process
     * anyway (low memory, a crash, a reboot) while a passenger was still in the car -- and the
     * answer must not be a dial that resets to zero. The trip record survived in Room; this is how
     * the live meter catches back up to it.
     *
     * [restored] is the pure engine's own state as rebuilt by
     * [au.com.threesixty.cabdispatch.domain.fare.reconstructFareState], so every accrued figure
     * here is the tested engine's, not a second guess at it. This method deliberately takes plain
     * values rather than the `TripEntity` itself: the mapping from a Room row to a fare state
     * already exists in exactly one place, and duplicating even part of it here would be a second
     * place for the reconstruction rules to drift.
     *
     * The tick clock is re-baselined by [startTicking], so the wall time the process spent dead is
     * never billed as motion -- and even if it were somehow not, [tick]'s own [MAX_TICK_SECONDS]
     * clamp is the backstop. Time genuinely elapsed while the app was not running is simply not
     * charged: the meter cannot attest to travel it did not observe, and a passenger must never be
     * billed for a gap in our own record-keeping.
     */
    override fun resumeTrip(
        tariff: TariffDto,
        restored: CalcFareState,
        movingSeconds: Int,
        waitingSeconds: Int,
        tollsApplied: List<TollPreset>,
        autoTollsApplied: List<AutoTollEntry>,
    ) {
        this.tariff = tariff
        calcState = restored

        // Same clean-slate treatment [startTrip] gives these, for the same reason: this instance is
        // process-scoped and may have been through other trips. The one difference is the two
        // second-counters, which are restored rather than zeroed -- they are cumulative trip
        // totals, and the passenger already owes the waiting time accrued before the crash.
        movingSecondsAccum = movingSeconds.toDouble()
        waitingSecondsAccum = waitingSeconds.toDouble()
        lastTickFix = null
        lastKnownSpeedKmh = 0.0
        blackoutEntryFix = null
        blackoutEntryWasMoving = false
        autoTollAlertSeq = 0L
        // Deliberately NOT repopulated from the persisted per-road audit trail: [TollDetectionState]
        // is in-memory dedup bookkeeping, and seeding it would require reconstructing gantry
        // confirmation sets that were never persisted. The consequence is bounded and documented --
        // a road already auto-charged before the crash could be charged a second time if the
        // vehicle re-crosses one of its gantries after the restart -- and [addToll]/[removeAutoToll]
        // leave the driver able to see and correct exactly that. A wrong suppression would be worse:
        // it would silently drop a real toll with nothing on screen to notice.
        tollDetectionState.reset()

        val domainTariff = restored.tariff
        val peak = if (restored.isPeak) domainTariff.peakCharge else BigDecimal.ZERO
        val threshold = domainTariff.speedThresholdKmh.toDouble().takeIf { it > 0 }
            ?: DEFAULT_SPEED_THRESHOLD_KMH

        _state.value = FareState(
            status = TripStatus.HIRED,
            mode = AccrualMode.WAITING,
            band = if (restored.cumulativeDistanceKm <= domainTariff.distKmThreshold) {
                TariffBand.BAND_1
            } else {
                TariffBand.BAND_2
            },
            timeClass = restored.timeClass.toDisplayTimeClass(),
            speedThresholdKmh = threshold,
            distanceKm = restored.cumulativeDistanceKm,
            movingSeconds = movingSeconds,
            waitingSeconds = waitingSeconds,
            breakdown = FareBreakdown(
                flagFall = domainTariff.flagFall,
                distanceAmount = restored.accruedDistanceCharge,
                waitingAmount = restored.accruedWaitingCharge,
                peakAmount = peak,
                tolls = restored.tolls,
                psl = domainTariff.pslAmount,
                extras = restored.extras,
            ),
            tollsApplied = tollsApplied,
            autoTollsApplied = autoTollsApplied,
            passengerCount = restored.passengerCount,
            wheelchairHiring = restored.wheelchairHiring,
            maxiRateApplied = restored.maxiRateApplied,
            negotiatedTotal = restored.negotiatedTotal,
            // The whole point of the exercise: the dial shows the real running total the instant
            // the meter comes back, not zero and not flagfall.
            runningTotal = runningTotal(restored),
        )
        scope.launch { tollRegistry = runCatching { tollRegistryProvider.snapshot() }.getOrNull() }
        startTicking()
    }

    override fun updatePassengerCount(count: Int) {
        val cs = calcState ?: return
        cs.passengerCount = count
        _state.value = _state.value.copy(
            passengerCount = cs.passengerCount,
            maxiRateApplied = cs.maxiRateApplied,
            // A corrected count can flip maxi eligibility, and the maxi multiplier is exactly what
            // F8 found missing from the old dial sum -- so the displayed total has to be recomputed
            // here or the correction would be visible in the MAXI chip while the money stayed wrong.
            runningTotal = runningTotal(cs),
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

    /**
     * Adds a driver-tapped toll, and — **T1** — makes sure the same road is not also charged
     * automatically.
     *
     * When this preset names a registry road ([TollPreset.registryRoadId]), any auto-detected
     * charge already standing for that road is withdrawn here, and the road is marked dismissed so
     * the detector will not charge it again for the rest of this trip
     * ([au.com.threesixty.cabdispatch.domain.fare.dismissCharge] does both). Tapping M5 while
     * driving the M5 used to bill the crossing twice, through two lists that never consulted each
     * other.
     *
     * The driver's tap wins, deliberately. It is an explicit, deliberate act about a road they are
     * physically on; the detector's is an inference from GPS proximity. Where the two disagree the
     * human is the better authority, and — unlike suppressing the tap — this direction leaves a
     * visible result: the amount the driver entered is the amount on the breakdown, which is what
     * they will expect to see.
     */
    /** Set by [startTrip] when the airport pickup fee was applied automatically, so a manual
     * Airport preset tap on the same trip is a no-op rather than a second $6.43. */
    private var airportFeeAutoApplied: Boolean = false

    override fun addToll(preset: TollPreset) {
        if (preset.id == TollPresets.AIRPORT.id && airportFeeAutoApplied && calcState != null) return
        preset.registryRoadId?.let { roadId -> supersedeAutoToll(roadId) }
        val current = _state.value
        // Mirrored into the shadow calc state too — harmless today (close() below still returns
        // the UI snapshot directly, matching pre-existing behaviour, not calcEngine.close()'s
        // result), but keeps the two totals from silently disagreeing if a future change ever
        // does read calcState back out.
        calcState?.let { it.tolls = it.tolls.add(preset.amount) }
        _state.value = current.copy(
            breakdown = current.breakdown.copy(tolls = current.breakdown.tolls.add(preset.amount)),
            tollsApplied = current.tollsApplied + preset,
            // Republished here, not left until the next tick: the driver taps the chip and the dial
            // has to answer in that frame, not up to a second later. Same close()-derived figure
            // [tick] publishes -- see [runningTotal].
            runningTotal = calcState?.let(::runningTotal) ?: current.runningTotal,
        )
    }

    /**
     * Withdraws any standing auto-toll charge for [roadId] and blocks further auto-charging of it
     * this trip, because the driver has just charged the same road by hand — T1's other half. A
     * no-op when the detector never charged that road, which is the common case.
     *
     * Distinct from [removeAutoToll] only in intent (and in not being a driver-facing correction):
     * both funnel through the same
     * [au.com.threesixty.cabdispatch.domain.fare.dismissCharge] bookkeeping.
     */
    private fun supersedeAutoToll(roadId: String) {
        val amount = dismissCharge(tollDetectionState, roadId) ?: return
        calcState?.let { it.tolls = (it.tolls - amount).coerceAtLeast(BigDecimal.ZERO) }
        val current = _state.value
        _state.value = current.copy(
            breakdown = current.breakdown.copy(
                tolls = (current.breakdown.tolls - amount).coerceAtLeast(BigDecimal.ZERO),
            ),
            autoTollsApplied = current.autoTollsApplied.filterNot { it.roadId == roadId },
        )
    }

    override fun removeAutoToll(roadId: String) {
        val amount = dismissCharge(tollDetectionState, roadId) ?: return
        calcState?.let { it.tolls = (it.tolls - amount).coerceAtLeast(BigDecimal.ZERO) }
        val current = _state.value
        _state.value = current.copy(
            breakdown = current.breakdown.copy(tolls = (current.breakdown.tolls - amount).coerceAtLeast(BigDecimal.ZERO)),
            autoTollsApplied = current.autoTollsApplied.filterNot { it.roadId == roadId },
            runningTotal = calcState?.let(::runningTotal) ?: current.runningTotal,
        )
    }

    override fun dismissUnpricedToll(roadId: String) {
        // UI-only: [tollDetectionState].unpricedRoadIds is left untouched, so a re-crossing of the
        // same road never re-raises this notice (onFix's `state.unpricedRoadIds.add(roadId)` is
        // already false for a road flagged once) — this call only clears it from the driver-facing
        // list. Never re-enables auto-charging for roadId; see [FareEngine.dismissUnpricedToll]'s doc.
        val current = _state.value
        _state.value = current.copy(unpricedTollRoads = current.unpricedTollRoads.filterNot { it.roadId == roadId })
    }

    override fun close(): FareState {
        tickJob?.cancel()
        _state.value = _state.value.copy(status = TripStatus.CLOSED)
        return _state.value
    }

    /**
     * Starts (or restarts, after [resume]) the accrual loop.
     *
     * [lastTickNanos] is re-baselined to *now* here, not left carrying whatever it held before.
     * That is what makes a [pause]/[resume] cycle free: the wall time a driver spent stopped with
     * the meter deliberately paused is never billed as motion when it restarts. The same
     * re-baselining is what makes a foreground-service restart safe -- see
     * [au.com.threesixty.cabdispatch.domain.MeterForegroundService].
     */
    private fun startTicking() {
        tickJob?.cancel()
        lastTickNanos = nanoTimeSource()
        tickJob = scope.launch {
            while (isActive && _state.value.status == TripStatus.HIRED) {
                delay(TICK_PERIOD_MS)
                if (_state.value.status == TripStatus.HIRED) tick()
            }
        }
    }

    /**
     * One iteration of the spec B6 meter loop. Four of this codebase's rated-blocker fare bugs
     * lived in the eight lines this method used to be, so the reasoning is worth spelling out.
     *
     * **F1 -- how much time just passed.** This used to bill a hardcoded `elapsedSeconds = 1` and
     * `speed / 3600` km, on the reasoning that [startTicking] delays for exactly a second.
     * `delay(1000)` guarantees *at least* a second: under GC pressure, a busy dispatcher, or a
     * Doze-throttled wakeup, a tick can take 1.1-2.0s of wall clock and still bill one second of
     * it. That is a systematic **under-charge**, it grows with device load, and nothing bounds it.
     * Elapsed time is now measured against [nanoTimeSource], a monotonic clock, and the real delta
     * is what gets billed.
     *
     * The delta is clamped to [MAX_TICK_SECONDS]. The clamp is not defensive tidiness -- it is the
     * correctness half of F4's process-lifetime hoist. A resumed process, or a service the OS
     * froze and thawed, can hand this method a `dt` of ten minutes; billing that as motion at the
     * last known speed would invent kilometres out of a gap in which the app was not running. When
     * the clamp bites, the unbilled remainder is deliberately *dropped*, never carried forward:
     * the meter's duty is to charge for travel it observed, and it observed none of it.
     *
     * **F3 -- whether what we know about the vehicle is still true.** [SpeedSource] only ever
     * revised its speed when a fix was *accepted*. In a tunnel no fix is ever accepted, so the last
     * speed froze and this loop kept integrating against it: enter at 80 km/h, lose the sky for
     * four minutes, get billed 5.3 km nobody drove. So a fix older than [MAX_FIX_AGE_MS] (and the
     * no-fix-at-all case) is now treated as what it is -- the meter does not know whether this
     * vehicle is moving -- and the rule is:
     *
     *  - **No distance accrues.** Ever, under any GPS-lost condition. Distance we cannot observe is
     *    distance we cannot charge for.
     *  - **Waiting time accrues only if the vehicle was already stationary** when the fixes stopped
     *    ([lastKnownSpeedKmh] below the threshold). A cab stopped at a kerb under a bridge is still
     *    genuinely waiting, and the passenger owes that. A cab doing 80 into a tunnel is not
     *    waiting, and billing it waiting time is the same bug wearing the other hat.
     *  - [FareState.gpsLost] is published so the driver is *told*, rather than left watching a dial
     *    that has quietly stopped moving for reasons nobody explained.
     *
     * **F2 -- how far it actually went.** Distance was time-integrated instantaneous speed and
     * never once a real position delta, which is what made F3 able to invent kilometres at all.
     * When a genuinely new fix arrived this tick, distance is now the great-circle distance
     * ([GeoMath.distanceKm]) between that fix and the previous tick's. Only when no new fix arrived
     * (GPS updates are not locked to this loop's cadence -- a live trace showed gaps of 1.40s,
     * 2.04s, 0.96s) does it fall back to `speed x dt`. Both paths are capped at
     * `speed x dt x` [MAX_DISTANCE_OVERSHOOT_FACTOR] so a single fix that jumps -- multipath off a
     * building, a provider switch -- cannot bill a kilometre in one second. The cap is also what
     * bounds the error when a haversine leg happens to span a tick whose fallback was already
     * billed.
     *
     * **F8 -- what the driver is shown.** [FareState.total] used to be a naive seven-way sum of
     * [FareBreakdown], with no round-down and no maxi multiplier, so a maxi trip's dial under-read
     * its own bill by 50% of the metered base. The running total is now
     * [CalcFareEngine.close]'s `grandTotal` -- the same golden-vector-tested computation Close &
     * Pay bills off -- recomputed here each tick. `close()` does not mutate the state it reads (see
     * its own doc: it is explicitly checkpoint-safe), so calling it per tick is sound.
     *
     * Accrued amounts are still copied RAW, unrounded, between ticks; the tested engine rounds only
     * at close, and rounding every tick compounds a real drift over a long trip. Only
     * [FareState.runningTotal] -- which *is* a close() result -- carries cent rounding.
     */
    private fun tick() {
        val cs = calcState ?: return

        // --- F1: real elapsed time, on a clock that cannot jump ------------------------------
        val nowNanos = nanoTimeSource()
        val previousTickNanos = lastTickNanos
        lastTickNanos = nowNanos
        val dtSeconds = if (previousTickNanos == null) {
            0.0
        } else {
            ((nowNanos - previousTickNanos) / NANOS_PER_SECOND).coerceIn(0.0, MAX_TICK_SECONDS)
        }

        val threshold = cs.tariff.speedThresholdKmh.toDouble().takeIf { it > 0 } ?: DEFAULT_SPEED_THRESHOLD_KMH

        // --- F3: is the newest fix recent enough to believe? ---------------------------------
        val fix = speedSource.locationFix.value
        val fixAgeNanos = fix?.let { nowNanos - it.receivedAtNanos }
        val gpsLost = fixAgeNanos == null || fixAgeNanos > MAX_FIX_AGE_MS * NANOS_PER_MILLI
        val previousFix = lastTickFix
        // Only advance the haversine baseline while GPS is live. Holding the pre-blackout fix as
        // the baseline through a tunnel would mean the first fix out the far end draws a segment
        // across the entire tunnel and charges it in one tick -- exactly the phantom distance F3
        // exists to stop, re-entering by the F2 door. The cap would blunt it; not relying on the
        // cap for this is better.
        lastTickFix = if (gpsLost) null else fix

        if (!gpsLost) lastKnownSpeedKmh = speedSource.speedKmh.value

        // The speed the accrual decision is actually made on. While GPS is lost we never claim
        // motion: a vehicle we cannot see is either stationary (bill waiting) or unknown (bill
        // nothing), and both of those are decided here, not by pretending to a speed.
        val wasStationaryWhenLost = lastKnownSpeedKmh < threshold
        val accrueThisTick = !gpsLost || wasStationaryWhenLost
        val billedSpeedKmh = if (gpsLost) 0.0 else lastKnownSpeedKmh

        // --- Known-corridor blackout catch-up (owner's decision, 2026-09-09) -----------------
        // See KnownCorridorDistanceLookup's / knownCorridorDistanceKm's own doc for the full
        // reasoning. Two things happen here, on opposite ends of one blackout:
        //
        //  - The FIRST tick a blackout is declared, [blackoutEntryFix] is captured from THIS tick's
        //    own `previousFix` -- the prior tick's genuinely live fix, still sitting in
        //    [lastTickFix] at this exact point (it is nulled a few lines above, but `previousFix`
        //    was already read out before that happened) -- i.e. the last position the meter
        //    actually believed before the sky closed over. [blackoutEntryWasMoving] is captured in
        //    the same instant from [wasStationaryWhenLost] just above, which -- on this specific
        //    tick -- still reflects the speed at the moment signal was lost (lastKnownSpeedKmh has
        //    not been touched this tick, since gpsLost is true).
        //  - The tick GPS is REACQUIRED (gpsLost just went false, and a blackout was in progress),
        //    before any of the ordinary F1-F3 accrual below runs: ask whether the whole blackout can
        //    be explained by a known, mapped toll-road corridor between [blackoutEntryFix] and this
        //    tick's own fresh `fix`. A match bills the real corridor distance ONCE, through the exact
        //    same [calcEngine].tick() distance-accrual path every ordinary GPS tick already uses
        //    (never a second billing code path) -- at a speed comfortably over the tariff's own
        //    threshold, so it always lands in the distance branch, never waiting. No match (the
        //    common case: no corridor here, or the fixes don't plausibly continue one road) falls
        //    straight through to today's existing behaviour -- the blackout accrues nothing.
        //
        // Gated on [blackoutEntryWasMoving]: a blackout where the vehicle was already stationary when
        // signal dropped has been billing WAITING time throughout it already (the branch immediately
        // below); adding a corridor's known distance on top of that would double-bill the same
        // minutes, once as waiting and once as distance.
        if (gpsLost) {
            if (blackoutEntryFix == null) {
                blackoutEntryFix = previousFix
                blackoutEntryWasMoving = !wasStationaryWhenLost
            }
        } else if (blackoutEntryFix != null) {
            val entryFix = blackoutEntryFix
            if (blackoutEntryWasMoving && entryFix != null && fix != null) {
                val knownKm = knownCorridorDistanceLookup.knownDistanceKm(entryFix.lat, entryFix.lng, fix.lat, fix.lng)
                    ?: tollRegistry?.let { registry ->
                        knownCorridorDistanceKm(registry, entryFix.lat, entryFix.lng, fix.lat, fix.lng)
                    }
                if (knownKm != null && knownKm.signum() > 0) {
                    calcEngine.tick(
                        cs,
                        speedKmh = threshold + 1.0,
                        distanceDeltaKm = knownKm,
                        elapsedSeconds = BigDecimal.ZERO,
                    )
                }
            }
            blackoutEntryFix = null
            blackoutEntryWasMoving = false
        }

        // --- F2: distance from real positions, not integrated speed --------------------------
        val speedCapKm = billedSpeedKmh * dtSeconds / SECONDS_PER_HOUR * MAX_DISTANCE_OVERSHOOT_FACTOR
        val distanceDeltaKm = when {
            gpsLost -> 0.0
            // A genuinely new fix arrived since the last tick: charge the ground between them.
            previousFix != null && fix != null && fix !== previousFix ->
                GeoMath.distanceKm(previousFix.lat, previousFix.lng, fix.lat, fix.lng).coerceAtMost(speedCapKm)
            // No new fix this tick (GPS cadence is not this loop's cadence), or the very first tick
            // of the trip with no previous position to measure from: integrate speed, as before.
            else -> (billedSpeedKmh * dtSeconds / SECONDS_PER_HOUR).coerceAtMost(speedCapKm)
        }

        if (accrueThisTick && dtSeconds > 0.0) {
            calcEngine.tick(
                cs,
                speedKmh = billedSpeedKmh,
                distanceDeltaKm = BigDecimal.valueOf(distanceDeltaKm),
                elapsedSeconds = BigDecimal.valueOf(dtSeconds),
            )
        }

        // Auto-toll detection (see [detectTolls]'s own doc) -- runs on the SAME real GPS fix
        // [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel] records into the trip's
        // persisted trace, using [cs]'s just-updated cumulative distance (needed for the `distance`
        // pricing model). Skipped entirely while GPS is lost: detecting a gantry crossing needs a
        // position we currently believe, and a stale one would charge a toll for a road the vehicle
        // may have left minutes ago. Folds straight into `_state.value` before the final `current`
        // read below.
        if (!gpsLost) detectTolls(cs)

        val current = _state.value
        val mode = if (billedSpeedKmh >= threshold) AccrualMode.DISTANCE else AccrualMode.WAITING
        // Computed off TRUE cumulative distance every tick (fix #3 in this class's doc), not only
        // while in the distance branch -- a trip that crawls past 12km in traffic now switches band
        // correctly.
        val band = if (cs.cumulativeDistanceKm <= cs.tariff.distKmThreshold) TariffBand.BAND_1 else TariffBand.BAND_2

        // Whole-second counters (TripEntity.movingS/.waitingS are Ints) fed from a fractional
        // accumulator rather than a flat +1 per tick, so F1's real `dt` is not thrown away again at
        // the last moment by the very counters the server replays the trip against.
        if (accrueThisTick) {
            if (mode == AccrualMode.DISTANCE) movingSecondsAccum += dtSeconds else waitingSecondsAccum += dtSeconds
        }

        _state.value = current.copy(
            mode = mode,
            band = band,
            distanceKm = cs.cumulativeDistanceKm,
            currentSpeedKmh = billedSpeedKmh,
            // The same threshold this tick just used to pick the accrual mode -- so the dial bands
            // on exactly the line the meter is charging on, not a constant that could drift.
            speedThresholdKmh = threshold,
            movingSeconds = movingSecondsAccum.roundToInt(),
            waitingSeconds = waitingSecondsAccum.roundToInt(),
            gpsLost = gpsLost,
            breakdown = current.breakdown.copy(
                distanceAmount = cs.accruedDistanceCharge,
                waitingAmount = cs.accruedWaitingCharge,
            ),
            runningTotal = runningTotal(cs),
        )
    }

    /**
     * The one authoritative "what does the passenger owe right now" figure -- F8. Delegates
     * wholesale to the pure, golden-vector-tested engine's own [CalcFareEngine.close], which
     * already applies the negotiated-fare branch, the maxi multiplier, the levy and the
     * Act s76(5)/(6) round-down that this class's own [FareBreakdown.total] sum applies none of.
     *
     * `paymentMethod` is left at its `"cash"` default deliberately: the non-cash surcharge is a
     * function of a payment method the driver has not chosen yet at meter time, so quoting a
     * card-inclusive figure on the dial would overstate what a cash passenger will actually pay.
     * Close & Pay adds it once a method is genuinely picked. `includePsl = true` matches
     * [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayViewModel]'s own unconditional
     * levy -- the PSL is a mandatory Fares Order pass-through with no remaining toggle, so a live
     * total that always shows it is always honest.
     */
    private fun runningTotal(cs: CalcFareState): BigDecimal =
        calcEngine.close(cs, includePsl = true).grandTotal

    /**
     * Runs [au.com.threesixty.cabdispatch.domain.fare.onFix] against the latest known GPS fix and
     * folds any result straight into [_state]. A no-op whenever there's nothing to detect against
     * yet — no fix at all (no permission/no signal, same honest-null [SpeedSource.locationFix]
     * convention as [LocationFix]'s own doc), or [tollRegistry] hasn't finished loading, or it
     * loaded but is genuinely empty (never cached — see [TollRegistryProvider]'s "offline-empty-
     * cache" fallback) — every one of these is the SAME correct behaviour: detect nothing, change
     * nothing, let the driver keep using manual [addToll] presets exactly as before this feature
     * existed. [au.com.threesixty.cabdispatch.domain.fare.TollDetectionResult.isEmpty] is checked
     * before touching [_state] at all so an ordinary tick with no gantry nearby (the overwhelming
     * majority of ticks on any real trip) never triggers a state emission for this alone.
     */
    private fun detectTolls(cs: CalcFareState) {
        val registry = tollRegistry ?: return
        if (registry.gantries.isEmpty()) return
        val fix = speedSource.locationFix.value ?: return

        // T1: a road the driver has already added by hand is never also auto-charged. addToll
        // dismisses the road as it goes, so onFix below skips it outright; this is the belt to that
        // braces, covering a preset whose registryRoadId is added while a charge for it is already
        // in flight this same tick.
        val manuallyChargedRoadIds = _state.value.tollsApplied.mapNotNull { it.registryRoadId }.toSet()

        val result = onFix(
            tollDetectionState,
            registry,
            lat = fix.lat,
            lng = fix.lng,
            // NSW local: SHB/SHT's time-of-day bands are Sydney wall clock (see shbShtBand).
            ts = ZonedDateTime.now(NSW_FARE_ZONE),
            cumulativeDistanceKm = cs.cumulativeDistanceKm,
        )
        if (result.isEmpty) return

        val current = _state.value
        var tolls = current.breakdown.tolls
        var autoTolls = current.autoTollsApplied
        var alert: AutoTollAlert? = null
        for ((roadId, newAmount) in result.chargedRoadsChanged) {
            if (roadId in manuallyChargedRoadIds) continue
            // `distance`-model roads REVISE the same entry (see onFix's doc) — subtract the amount
            // this trip previously showed for roadId before adding the new one, so a growing M7
            // charge updates in place rather than double-counting on every tick it changes.
            val previousAmount = autoTolls.firstOrNull { it.roadId == roadId }?.amount ?: BigDecimal.ZERO
            val delta = newAmount - previousAmount
            tolls += delta
            // Mirrored into the shadow calc state too, same "harmless today, keeps the two totals
            // from silently disagreeing" reasoning [addToll] above already documents.
            cs.tolls += delta
            // Not a plain roadsById lookup: on a cumulative-per-point road the key is a TOLL
            // POINT id, which that map does not contain. See chargeDisplayName.
            val roadName = chargeDisplayName(registry, roadId)
            autoTolls = autoTolls.filterNot { it.roadId == roadId } + AutoTollEntry(roadId, roadName, newAmount)
            // Audible + on-screen confirmation (product requirement, 2026-09) — see
            // AutoTollAlert's own doc. If more than one road changes on the same tick (rare: two
            // gantries of different roads within detection radius of the same fix), the last one
            // wins the single alert slot; not worth a multi-alert queue for an edge case this thin.
            //
            // Announce a road ONCE, on the tick it first becomes chargeable — never again as its
            // amount is revised. Found on the tablet, 2026-09-07, driving the real M7 route: a
            // distance-metered road re-prices on every fix while the vehicle is inside a gantry's
            // 150m radius, which is ~13 consecutive fixes at 80 km/h. Alerting on each of those
            // meant a burst of ~13 beeps per gantry -- times M7's 45 gantries -- and a passenger
            // being told "TOLL ADDED $0.19", then "$0.92" for the SAME road, as though a second
            // toll had been charged. The revision is still applied to the fare (the dial and the
            // breakdown both show it live); it simply stops being announced.
            if (previousAmount.signum() == 0) {
                alert = AutoTollAlert(roadName = roadName, amount = newAmount, id = ++autoTollAlertSeq)
            }
        }

        val alreadyFlagged = current.unpricedTollRoads.mapTo(mutableSetOf()) { it.roadId }
        val newUnpricedEntries = result.newlyUnpricedRoadIds
            .filterNot { it in alreadyFlagged }
            .map { roadId -> UnpricedTollRoad(roadId, chargeDisplayName(registry, roadId)) }

        _state.value = current.copy(
            breakdown = current.breakdown.copy(tolls = tolls),
            autoTollsApplied = autoTolls,
            unpricedTollRoads = current.unpricedTollRoads + newUnpricedEntries,
            lastAutoTollAlert = alert ?: current.lastAutoTollAlert,
            runningTotal = runningTotal(cs),
        )
    }

    /** [TimeClass] (this file's display-oriented enum, `domain.TimeClass`) -> [CalcTimeClass]
     * (`domain.fare.TimeClass`, the tested engine's own) — same three cases, different enum types
     * because [TimeClass] carries a display [TimeClass.label] the calc engine has no use for. */
    /** [CalcTimeClass] -> [TimeClass], the inverse of [toCalcTimeClass] below. Needed by
     * [resumeTrip], which receives the calc engine's own enum from the reconstruction and has to
     * put this class's display-oriented one on [FareState]. */
    private fun CalcTimeClass.toDisplayTimeClass(): TimeClass = when (this) {
        CalcTimeClass.DAY -> TimeClass.DAY
        CalcTimeClass.NIGHT -> TimeClass.NIGHT
        CalcTimeClass.HOLIDAY -> TimeClass.HOLIDAY
    }

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
     *
     * **Clock:** [NSW_FARE_ZONE], not the tablet's own zone. Plain `ZonedDateTime.now()` reads the
     * device's configured timezone, which silently makes the regulated night window follow the
     * tablet rather than the Fares Order -- a unit set to the wrong zone, or a field-test tablet in
     * another country (exactly how this surfaced, 2026-09-07), charges the night rate at the wrong
     * hours with nothing on screen to suggest anything is wrong. The server's classifier is pinned
     * to the same zone and the two MUST agree, or every night fare fails the fare-variance check on
     * sync.
     */
    private fun resolveTimeClass(area: AreaClass): TimeClass =
        resolveTimeClassFor(ZonedDateTime.now(NSW_FARE_ZONE), area)

    /** Peak Time Hiring Charge: urban, hiring commences 10pm-6am Fri/Sat/pre-holiday (spec B6) —
     * see [resolveIsPeakFor]'s doc for the actual rule. */
    private fun resolveIsPeak(): Boolean = resolveIsPeakFor(ZonedDateTime.now(NSW_FARE_ZONE))

    companion object {
        /** Nominal loop cadence. The meter no longer *bills* this figure -- see [tick]'s F1 note --
         * it only decides how often the loop wakes up to measure what really elapsed. */
        private const val TICK_PERIOD_MS = 1000L

        /**
         * How old the newest GPS fix may be before [tick] stops believing it knows whether this
         * vehicle is moving (F3, architecture audit §2.1). Five seconds is comfortably longer than
         * the 1 Hz cadence [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider]
         * requests (so ordinary jitter -- live traces show 0.96s-2.04s gaps -- never trips it) and
         * short enough that a real blackout is caught within a few metres of travel rather than a
         * few hundred.
         */
        const val MAX_FIX_AGE_MS = 5_000L

        /**
         * Upper bound on the elapsed time any single tick may bill, seconds (F1).
         *
         * A tick that genuinely took longer than this did not observe the intervening travel -- the
         * process was frozen, Doze-throttled, or restarted -- so billing it as motion would invent
         * distance. Five seconds is the same figure as [MAX_FIX_AGE_MS] and for the same reason: at
         * that point the meter's information about the vehicle is stale regardless of which of the
         * two clocks noticed first.
         */
        private const val MAX_TICK_SECONDS = 5.0

        /**
         * How far past `speed x dt` a single tick's distance may go before it is treated as a GPS
         * jump rather than travel (F2). A haversine leg is real position data and normally the more
         * trustworthy of the two paths, but multipath off a city building or a provider handover
         * can move a "position" hundreds of metres in one sample. 1.5x leaves ordinary
         * acceleration within a tick unclipped while making a fabricated kilometre impossible.
         */
        private const val MAX_DISTANCE_OVERSHOOT_FACTOR = 1.5

        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val SECONDS_PER_HOUR = 3600.0
    }
}

/**
 * time_class classification per the Point to Point Transport (Fares) Order 2026: 10pm-6am any
 * night is [TimeClass.NIGHT] (both areas); for [AreaClass.COUNTRY] only, 6am-10pm on a Sunday or a
 * gazetted NSW public holiday ([NswPublicHolidays]) is [TimeClass.HOLIDAY] (urban has no holiday
 * distance rate at all, so urban never returns [TimeClass.HOLIDAY]); everything else is
 * [TimeClass.DAY]. A top-level function (not a private method) taking an explicit [now] so it is
 * unit-testable without needing to fake the system clock — [FareEngineImpl.resolveTimeClass] is a
 * thin `ZonedDateTime.now(NSW_FARE_ZONE)`-supplying wrapper around this.
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
