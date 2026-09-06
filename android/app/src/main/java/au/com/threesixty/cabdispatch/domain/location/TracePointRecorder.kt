package au.com.threesixty.cabdispatch.domain.location

/**
 * Pure decision logic behind [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s
 * "which real GPS fix, if any, gets appended to this trip's persisted GPS trace this fare-engine
 * tick" — see that class's own `nextTracePoint` doc for the full fare-integrity story this exists
 * for. Short version: `TripEntity.gpsTraceJson` used to never accumulate anything at all
 * (`HiredViewModel.doPersistTick` always called `TripRepository.tick(newPoints = emptyList())`),
 * and that trace isn't just decoration for the meter map's polyline — `POST /v1/trips/sync`'s
 * server-side `recompute_from_trace` (`backend/app/services/trips.py`) independently REPLAYS it
 * through the identical tick algorithm [au.com.threesixty.cabdispatch.domain.fare.FareEngine] runs
 * on-device (deriving `distance_m`/`moving_s`/`waiting_s` from it) to validate the device's own
 * `deviceTotal` within a 1% variance tolerance. An empty trace made that replay compute a
 * flagfall-only fare and auto-flag every single trip for review — confirmed live (2026-09-06/07):
 * device charged $9.11, server recomputed $6.32 (flagfall + PSL only) from a `[]` trace and flagged
 * every trip in the ledger for "Fare check: Failed".
 *
 * A tiny, Android-free JVM object (matching [GeoMath]'s own "money-adjacent logic gets a
 * plain-Kotlin, unit-testable port" convention) purely so this decision is testable without
 * Robolectric — [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel] itself extends
 * `AndroidViewModel` and can't run under a plain JVM test.
 *
 * ### Why "record one point per fare-engine tick", not a distance/time-thresholded sparse sample
 * The obvious instinct for "don't let an hour-long fare's trace grow unbounded" is a
 * distance/time threshold — e.g. [MeterBackdropMap]'s own `rememberLiveTrace` only keeps a vertex
 * every ~3 m, purely for on-screen line smoothness. That convention is WRONG for this trace,
 * because it isn't just a line to draw: `recompute_from_trace` derives `moving_s`/`waiting_s` from
 * the real elapsed-time gap between consecutive points and picks distance- vs waiting-mode **per
 * point** from that point's own `speed_kmh`. Coalescing several fare-engine ticks into one sparse
 * point means the single `speed_kmh` reading at the end of a gap decides the mode for the WHOLE
 * gap — a stray high reading right as a long wait ends (GPS jitter, or the vehicle just pulling
 * away) would silently reclassify real waiting time as a near-zero-distance "distance" tick,
 * undercharging a real, already-earned waiting charge. Recording one real point per tick (the
 * device fare engine's own 1 Hz cadence, [au.com.threesixty.cabdispatch.domain.FareEngineImpl.startTicking])
 * keeps every mode-decision boundary on the server's replay lined up with the same boundary the
 * device's own engine used for that exact same second — see `TripTraceReplayFidelityTest` for a
 * synthetic device-vs-replay comparison proving this cadence keeps the two totals within
 * `compute_variance_pct`'s own 1% band, and a sparse-sampling counter-example proving a coarser
 * threshold does NOT.
 *
 * A trip's trace therefore grows roughly 1 point/second for as long as the meter is HIRED — for a
 * typical taxi fare (minutes, not hours) that's a few hundred points; even a full hour lands at
 * ~3,600, squarely inside the "a few thousand points, well within what this costs" ceiling this
 * codebase already treats as cheap for annotation rendering (see [MeterBackdropMap]'s own doc on
 * its `deleteAll`+recreate layer refresh). The one real de-duplication this class applies —
 * [isNewFix] — trims the handful of ticks per trip where the fare engine's `delay(1000)` coroutine
 * fires before a fresh GPS fix has actually arrived, which would otherwise re-record the exact same
 * point (contributing a zero distance/zero elapsed-time no-op to the server's replay either way —
 * `recompute_from_trace`'s own `distance_delta_km <= 0` short-circuit for distance mode, or a
 * zero-`elapsed_seconds` waiting tick — so skipping it is pure payload trimming, never a
 * correctness trade-off).
 *
 * ### The one open gap: no point cap for a pathologically long trip
 * [au.com.threesixty.cabdispatch.data.remote.ApiService]'s `gps_trace_ref` field (mirrored onto
 * [au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto.gpsTraceRef] /
 * `backend/app/models/trips.py::Trip.gps_trace_ref`) reads, from its name and the architecture
 * spec's own annotation (`docs/TCT-METER-01-spec.md`: "gps_trace_ref(S3)"), like it was meant to
 * let a large trace be uploaded once to blob storage and referenced by id instead of inlined on
 * every sync payload — but grepping this checkout's backend turns up no S3/blob client anywhere,
 * and `gps_trace_ref` is never actually SET to anything server-side (`sync_trips` only ever copies
 * whatever the client already sent, which today is always `null` — nothing generates a reference).
 * So it is a real column with no working reference-upload mechanism behind it; inlining the whole
 * trace in `gps_trace` (what [au.com.threesixty.cabdispatch.data.repository.TripRepository.toSyncItemDto]
 * already does, unchanged by this pass) is the only path that actually reaches the server today.
 * Building a real blob-upload mechanism to use `gps_trace_ref` for real is a materially larger
 * change (a new upload endpoint, a new offline-retry path for it) than this pass's brief covers —
 * flagged here explicitly rather than silently assumed solved. For an ordinary taxi fare (minutes)
 * this is a non-issue (a few hundred points, tens of KB); only an unusually long single hiring
 * (multiple hours) would produce a trace large enough to matter, and this pass deliberately does
 * NOT cap/drop points to bound that case, since a silently-truncated trace would reproduce the
 * exact "server can't see what actually happened" fare-integrity bug this pass exists to fix.
 */
object TracePointRecorder {

    /**
     * `true` when [candidateTimestampMillis] is a genuinely new fix — strictly later than the fix
     * the caller already recorded on a previous tick (or there is no previous recorded fix at
     * all). See this object's own class doc for why an exact repeat is safe (and correct) to skip
     * rather than re-record; an equal-or-earlier timestamp is treated the same way (never
     * genuinely new) rather than special-cased, since a real GPS fix never moves backwards in time
     * relative to what this class already recorded.
     */
    fun isNewFix(candidateTimestampMillis: Long, lastRecordedTimestampMillis: Long?): Boolean =
        lastRecordedTimestampMillis == null || candidateTimestampMillis > lastRecordedTimestampMillis
}
