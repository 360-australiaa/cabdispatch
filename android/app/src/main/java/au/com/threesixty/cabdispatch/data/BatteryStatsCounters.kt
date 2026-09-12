package au.com.threesixty.cabdispatch.data

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-lifetime counters for the four background costs the 2026-09-12 optimisation plan's W4
 * ("Battery, network and storage efficiency") set out to reduce — location requests, heartbeats,
 * Room writes and socket reconnects — surfaced on the debug-only Settings ▸ Diagnostics
 * `BatteryStatsPanel` (`ui/screens/settings/BatteryStatsPanel.kt`) as a rough per-minute rate.
 *
 * Deliberately dumb: four [AtomicLong] tallies since process start plus wall-clock elapsed time,
 * no windowing, no histogram, no persistence across process death. The acceptance criterion this
 * exists for ("Battery Historian or `dumpsys batterystats` before/after on the tablet... location
 * wakeups and network requests both >= 80% lower") is an OWNER-only on-device check this class
 * cannot perform or substitute for — see that section of the plan. What this DOES give a driver
 * or technician, with zero device access needed, is a live sanity check that the adaptive cadences
 * (task 1), the location-priority gating (task 2) and the collapsed reconnect policy (task 5) are
 * actually behaving as designed on THIS run, right now, without waiting for a lab pass.
 *
 * A plain top-level `object` rather than an [AppContainer][au.com.threesixty.cabdispatch.data.AppContainer]-registered
 * singleton on purpose: every call site that increments a counter here
 * ([au.com.threesixty.cabdispatch.domain.location.RealLocationProvider],
 * [au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat],
 * [au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat],
 * [au.com.threesixty.cabdispatch.data.repository.TripRepository],
 * [au.com.threesixty.cabdispatch.data.remote.RealtimeSocket]) already sits at a different layer
 * with no natural shared ancestor other than the process itself — the same reasoning
 * [au.com.threesixty.cabdispatch.domain.location.GpsSimulator.isSimulating] gives for being a
 * plain object rather than threaded through every constructor that might need it.
 */
object BatteryStatsCounters {

    private val locationRequests = AtomicLong(0)
    private val heartbeats = AtomicLong(0)
    private val roomWrites = AtomicLong(0)
    private val socketReconnects = AtomicLong(0)
    private val startedAtMs = AtomicLong(System.currentTimeMillis())

    /** A [au.com.threesixty.cabdispatch.domain.location.RealLocationProvider] (re)started or
     * renewed its `FusedLocationProviderClient.requestLocationUpdates` subscription — counted per
     * subscription (re)start, not per fix received, since the whole point of task 2's
     * priority/interval gating is how OFTEN this app asks the location radio to wake up, not how
     * many fixes come back. */
    fun recordLocationRequest() {
        locationRequests.incrementAndGet()
    }

    /** [au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat] or
     * [au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat] made one poll/publish
     * attempt — counted on the attempt, not only on success, since a failed attempt still woke
     * the radio and is the honest cost this panel exists to show. */
    fun recordHeartbeat() {
        heartbeats.incrementAndGet()
    }

    /** One [au.com.threesixty.cabdispatch.data.local.dao.TripDao]/
     * [au.com.threesixty.cabdispatch.data.local.dao.TripTracePointDao] write call — counted per
     * DAO call (an `insertAll` of ten batched trace points is one write, matching task 3's own
     * "batches every 5s or 10 points" framing, not ten). */
    fun recordRoomWrite() {
        roomWrites.incrementAndGet()
    }

    /** [au.com.threesixty.cabdispatch.data.remote.RealtimeSocket]'s shared
     * [au.com.threesixty.cabdispatch.data.remote.ReconnectPolicy]-driven loop began a fresh
     * connection attempt (the first one included) — the number a healthy, quiet link should keep
     * at exactly 1 for the life of the process, and flapping connectivity drives up. */
    fun recordSocketReconnect() {
        socketReconnects.incrementAndGet()
    }

    /** A snapshot of all four tallies as a per-minute rate since process start — see class doc
     * for why "since process start" rather than a rolling window is an honest-enough answer for
     * a debug panel. */
    data class Snapshot(
        val locationRequestsPerMin: Double,
        val heartbeatsPerMin: Double,
        val roomWritesPerMin: Double,
        val socketReconnectsPerMin: Double,
    )

    /** Floor on elapsed time before dividing, millis — see [snapshot]'s own comment. */
    private const val MIN_ELAPSED_MS = 1_000L

    /** Millis per minute — the unit [snapshot]'s rates are expressed in. */
    private const val MS_PER_MINUTE = 60_000.0

    fun snapshot(): Snapshot {
        // Floored at one second so a panel opened in the first instant of a cold start divides by
        // (near) zero and reads an absurd rate rather than crashing/NaN-ing.
        val elapsedMs = (System.currentTimeMillis() - startedAtMs.get()).coerceAtLeast(MIN_ELAPSED_MS)
        val elapsedMinutes = elapsedMs / MS_PER_MINUTE
        return Snapshot(
            locationRequestsPerMin = locationRequests.get() / elapsedMinutes,
            heartbeatsPerMin = heartbeats.get() / elapsedMinutes,
            roomWritesPerMin = roomWrites.get() / elapsedMinutes,
            socketReconnectsPerMin = socketReconnects.get() / elapsedMinutes,
        )
    }
}
