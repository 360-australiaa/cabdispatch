package au.com.threesixty.cabdispatch.domain.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.SpeedSource
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real [SpeedSource] backed by [FusedLocationProviderClient] — the GPS/location core this
 * module was missing (see HANDOFF.md "GPS is stubbed, not real" and README.md's `SpeedSource`
 * row). Wired as `AppContainer.speedSource`, replacing the former default `StubSpeedSource()`
 * there (see `domain/FareEngine.kt` — `StubSpeedSource` itself is unchanged/kept, as the
 * explicit no-GPS fallback for tests/previews and for what this class's own permission-denied
 * behaviour deliberately matches).
 *
 * ### Update rate
 * [TICK_INTERVAL_MS] (1s) matches the fare engine's own 1 Hz tick loop (spec B6: "Tick loop
 * (1 Hz, driven by fused GPS)") — no point sampling location faster than the engine reads it.
 *
 * ### Filtering (deliberately simple — spec B6 calls for `kalman(fused_location)`, this is not
 * a Kalman filter)
 * The task brief for this pass was explicit that a full Kalman filter is *not* required for this
 * pass — "simple sanity filtering is enough" — so [passesFilter] only does two cheap checks per
 * incoming fix, dropping it (keeping the previous accepted fix/speed) rather than ever throwing:
 * 1. **Speed-jump rejection** — reject a fix that implies a ground speed greater than
 *    [MAX_PLAUSIBLE_JUMP_KMH] between it and the previously *accepted* fix. The task brief that
 *    generated this file cited "spec section 5.2.2" and a 150 km/h threshold; neither the section
 *    number nor that figure actually appears in `../docs/TCT-METER-01-spec.md` as it exists in
 *    this checkout — what that spec's real fare-engine section (B6) says verbatim is
 *    `"reject jumps >180 km/h, HDOP filter"`. Used the verified 180 km/h figure from the actual
 *    spec text rather than the brief's unverifiable paraphrase; flagged here loudly in case the
 *    150 km/h figure came from a newer/different revision of the spec this checkout doesn't have
 *    — trivial to change [MAX_PLAUSIBLE_JUMP_KMH] if so.
 * 2. **Accuracy preference** — if a fix arrives very soon after the last accepted one
 *    ([ACCURACY_GRACE_PERIOD_SECONDS]) and is meaningfully less accurate
 *    ([ACCURACY_DEGRADATION_FACTOR]), drop it rather than let a single noisy fix override a good
 *    one — "prefer higher-accuracy fixes" per the task brief.
 *
 * No map-matching/snap-to-road (spec B6's `haversine(prev, curr) map-matched` for the *fare*
 * calculation is [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick]'s own concern, using
 * [speedKmh] as an input — this class only supplies the speed/position feed, it doesn't do
 * distance/fare math itself).
 *
 * ### Speed source
 * Prefers the platform's own [Location.hasSpeed]/[Location.getSpeed] (fused-provider-computed,
 * generally Doppler-derived and more accurate than a naive position-delta) and falls back to
 * `distance(prev, curr) / dt` only when the platform reports no speed for a fix (some devices/
 * providers, especially early fixes) — see [resolveSpeedKmh].
 *
 * ### Permission handling
 * Requesting the runtime permission itself (showing the system dialog) needs an `Activity`,
 * which this plain-domain class deliberately doesn't hold — that's a UI-layer concern for
 * whichever screen owns a `rememberLauncherForActivityResult`
 * /`ActivityResultContracts.RequestPermission` (none of which exist yet in this module as of this
 * pass — grep for `ContextCompat.checkSelfPermission` and note every hit today only *checks*,
 * never *requests*; wiring an actual request prompt is squarely a "UI-consumer call site" and out
 * of this pass's scope per its own brief). What this class *does* own: [supervisePermission]
 * polls [hasPermission] every [PERMISSION_POLL_INTERVAL_MS] and starts/stops the underlying
 * [FusedLocationProviderClient] subscription accordingly — so the moment some future screen's
 * permission-request flow results in a grant, this provider picks up location within one poll
 * interval with zero extra wiring on that screen's part (no explicit "tell the provider" call
 * needed). While ungranted, [locationFix]/[speedKmh] read `null`/`0.0` — the exact same
 * observable shape as [au.com.threesixty.cabdispatch.domain.StubSpeedSource] — so
 * [au.com.threesixty.cabdispatch.domain.FareEngineImpl] (and every other [SpeedSource] consumer)
 * degrades gracefully instead of crashing.
 */
class RealLocationProvider(
    context: Context,
    private val scope: CoroutineScope,
) : SpeedSource {

    private val appContext: Context = context.applicationContext

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(appContext)
    }

    private val _speedKmh = MutableStateFlow(0.0)
    override val speedKmh: StateFlow<Double> = _speedKmh.asStateFlow()

    private val _locationFix = MutableStateFlow<LocationFix?>(null)
    override val locationFix: StateFlow<LocationFix?> = _locationFix.asStateFlow()

    /** Last fix that passed [passesFilter] — the baseline the next fix is checked against. */
    private var lastAccepted: LocationFix? = null

    /** The currently-running `requestLocationUpdates` collector, or `null` while ungranted/between
     * retries. Only touched from [supervisorJob]'s own coroutine, so no extra synchronisation. */
    private var updatesJob: Job? = null

    private val supervisorJob: Job = scope.launch { supervisePermission() }

    /**
     * Owns the whole permission-check → start/stop lifecycle. A plain poll loop rather than a
     * `BroadcastReceiver`/callback on the permission grant, because there is no such callback for
     * a plain domain class without an `Activity`/`Fragment` — see class doc. [PERMISSION_POLL_INTERVAL_MS]
     * is intentionally short enough to feel immediate to a driver who just granted the permission,
     * long enough not to matter as busy-work in the meantime.
     */
    private suspend fun supervisePermission() {
        // scope.isActive, not a bare isActive — kotlinx.coroutines.isActive is an extension
        // property on CoroutineScope, and this plain suspend member function has no implicit
        // CoroutineScope receiver of its own (this class implements SpeedSource, not
        // CoroutineScope) — a bare `isActive` here would be an unresolved reference. Caught during
        // a 2026-08-03 reconciliation pass (see android/HANDOFF.md) while verifying a sibling
        // file's (LivePositionHeartbeat.kt) deliberate avoidance of this exact pitfall.
        while (scope.isActive) {
            if (hasPermission()) {
                if (updatesJob?.isActive != true) {
                    updatesJob = scope.launch { rawLocationUpdates().collect(::onNewFix) }
                }
            } else {
                updatesJob?.cancel()
                updatesJob = null
                lastAccepted = null
                _locationFix.value = null
                _speedKmh.value = 0.0
            }
            delay(PERMISSION_POLL_INTERVAL_MS)
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Raw [FusedLocationProviderClient] updates as a cold [Flow]. Every call site
     * ([supervisePermission]) only launches this after [hasPermission] just returned `true`, so
     * the `@RequiresPermission` this Play Services API is annotated with is satisfied at runtime
     * even though the compiler can't see that across the `if` — hence the suppression, not a
     * blanket "trust me".
     */
    @SuppressLint("MissingPermission")
    private fun rawLocationUpdates(): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TICK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(TICK_INTERVAL_MS)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        val requested = runCatching {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }.isSuccess
        // Registration can fail synchronously (permission revoked in the tiny window between the
        // hasPermission() check and this call, Play Services unavailable, etc.) — close cleanly
        // rather than let an exception escape into supervisePermission()'s loop; the next poll
        // tick will simply retry.
        if (!requested) close()
        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    private fun onNewFix(location: Location) {
        val candidate = LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            speedKmh = resolveSpeedKmh(location, lastAccepted),
            accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
            timestampMillis = location.time,
            // Same honest-null shape as accuracyM just above: hasBearing() is false whenever the
            // platform has no bearing to report (stationary device, no recent movement vector) —
            // read as null there rather than fabricating a heading, per LocationFix.heading's doc.
            heading = if (location.hasBearing()) location.bearing.toDouble() else null,
        )
        if (!passesFilter(candidate, lastAccepted)) return
        lastAccepted = candidate
        _locationFix.value = candidate
        _speedKmh.value = candidate.speedKmh
    }

    /** Prefer the platform's own speed reading; fall back to a position-delta estimate only when
     * the platform reports none for this fix (see class doc). Never negative. */
    private fun resolveSpeedKmh(location: Location, previous: LocationFix?): Double {
        if (location.hasSpeed()) return (location.speed * MS_TO_KMH).toDouble().coerceAtLeast(0.0)
        if (previous == null) return 0.0
        val dtSeconds = (location.time - previous.timestampMillis) / 1000.0
        if (dtSeconds <= 0.0) return previous.speedKmh
        val distanceKm = distanceKm(previous.lat, previous.lng, location.latitude, location.longitude)
        return (distanceKm / (dtSeconds / 3600.0)).coerceAtLeast(0.0)
    }

    /** Simple sanity filtering only — see class doc for why this is deliberately not a Kalman
     * filter. Returns `false` to drop [candidate] and keep [previous] as the accepted state. */
    private fun passesFilter(candidate: LocationFix, previous: LocationFix?): Boolean {
        if (previous == null) return true
        val dtSeconds = (candidate.timestampMillis - previous.timestampMillis) / 1000.0
        if (dtSeconds <= 0.0) return false // stale/out-of-order fix relative to the last accepted one

        // 1. Speed-jump rejection (spec B6: "reject jumps >180 km/h" — see class doc for the
        // 150km/h-vs-180km/h note).
        val impliedKmh = distanceKm(previous.lat, previous.lng, candidate.lat, candidate.lng) / (dtSeconds / 3600.0)
        if (impliedKmh > MAX_PLAUSIBLE_JUMP_KMH) return false

        // 2. Prefer higher-accuracy fixes: a much-worse-accuracy fix arriving hot on the heels of
        // a good one is more likely noise than a genuine accuracy regression.
        if (candidate.accuracyM > previous.accuracyM * ACCURACY_DEGRADATION_FACTOR &&
            dtSeconds < ACCURACY_GRACE_PERIOD_SECONDS
        ) {
            return false
        }
        return true
    }

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0] / 1000.0
    }

    /**
     * Stops [supervisePermission] and any in-flight update subscription. Not called anywhere yet
     * — [scope] is currently always a process-lifetime scope (see `AppContainer.speedSource`),
     * so there is nothing that tears this down today. Provided for whoever eventually hoists this
     * behind a lifecycle that *can* end (a foreground service being stopped, a test harness, etc.)
     * rather than leaking a running location subscription in that scenario.
     */
    fun shutdown() {
        supervisorJob.cancel()
        updatesJob?.cancel()
    }

    private companion object {
        const val TICK_INTERVAL_MS = 1000L
        const val PERMISSION_POLL_INTERVAL_MS = 3000L
        const val MAX_PLAUSIBLE_JUMP_KMH = 180.0
        const val ACCURACY_DEGRADATION_FACTOR = 3.0
        const val ACCURACY_GRACE_PERIOD_SECONDS = 5.0
        const val MS_TO_KMH = 3.6f
    }
}
