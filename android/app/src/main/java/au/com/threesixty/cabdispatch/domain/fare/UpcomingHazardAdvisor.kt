package au.com.threesixty.cabdispatch.domain.fare

import au.com.threesixty.cabdispatch.domain.TrafficHazard

/**
 * "Hazard ahead" map advisory — the live-traffic sibling of [upcomingToll]/[UpcomingToll], same
 * file's worth of reasoning reused wholesale: a calm, informational answer to "is there a real
 * incident/closure/roadwork actually on the road I'm driving, and what does it mean for me?",
 * computed purely from the SAME cached [TrafficHazard] list
 * [au.com.threesixty.cabdispatch.sync.TrafficCache] already keeps warm for the map's own marker
 * layer — no new network call, no dependency the marker layer does not already have.
 *
 * Real gap this closes (2026-09-10, owner: "road closure, or accident... all needs to be load in
 * our system, so driver can get as much information as possible"): the map already draws a marker
 * icon for a nearby hazard (see [au.com.threesixty.cabdispatch.ui.screens.hired.MeterBackdropMap]'s
 * per-category glyphs), but a marker alone names nothing — a driver glancing at a diamond icon on
 * the map cannot tell "roadwork" from "which road, how far, what speed limit applies here". This
 * gives the SAME nearest-ahead-of-travel hazard a real, honest one-line description, the identical
 * "ahead" test [upcomingToll] already uses (distance + trusted heading, never a guess).
 *
 * [TrafficHazard.speedLimit] is real, already-ingested Live Traffic NSW data (a temporary reduced-
 * speed zone tied to this specific hazard, e.g. roadwork) that was captured on every hazard row
 * from day one but never surfaced anywhere on the driver-facing map — this advisory is the first
 * place it is actually shown. It is NOT a general "the posted speed limit of whatever road you are
 * on right now" figure (this codebase has no such per-road static speed-limit data source yet,
 * a real, separate, larger gap this pass did not close) — never claim it as one; [UpcomingHazard]'s
 * own doc restates this at the point a caller would render it.
 */
data class UpcomingHazard(
    val category: String,
    /** Real feed text when the source published one; `null` otherwise -- never a fabricated
     * summary of what the hazard is. */
    val headline: String?,
    /** A temporary reduced-speed zone tied to THIS hazard (e.g. through roadwork) -- not the
     * road's ordinary posted limit. See this file's class doc for the distinction; a caller must
     * render this as "reduced to N km/h here", never bare "speed limit N". */
    val speedLimitKmh: Int?,
    val distanceAheadM: Double,
)

/** See [au.com.threesixty.cabdispatch.domain.fare]'s [upcomingToll]'s own "Why 2km" doc — identical
 * reasoning, same figure, so the two advisories feel like one system rather than two independently
 * tuned distances. */
const val HAZARD_LOOKAHEAD_M = 2000.0

/** See [TOLL_LOOKAHEAD_BEARING_TOLERANCE_DEG]'s own doc — identical reasoning, reused as-is. */
const val HAZARD_LOOKAHEAD_BEARING_TOLERANCE_DEG = 60.0

/**
 * The nearest real hazard ahead of ([lat],[lng]) on heading [headingDeg], within [lookaheadM] — or
 * `null` when there is none (nothing that close, nothing roughly ahead, or no trusted heading to
 * judge "ahead" by at all). See [upcomingToll]'s own doc for the full reasoning behind both gates;
 * this is the identical decision over [hazards] instead of a toll registry's gantries.
 */
fun upcomingHazard(
    hazards: List<TrafficHazard>,
    lat: Double,
    lng: Double,
    headingDeg: Double?,
    lookaheadM: Double = HAZARD_LOOKAHEAD_M,
    bearingToleranceDeg: Double = HAZARD_LOOKAHEAD_BEARING_TOLERANCE_DEG,
): UpcomingHazard? {
    if (headingDeg == null) return null // no trusted direction of travel -- never guess "ahead"

    var best: TrafficHazard? = null
    var bestDistanceM = Double.MAX_VALUE
    for (hazard in hazards) {
        val distanceM = tollHaversineM(lat, lng, hazard.latitude, hazard.longitude)
        if (distanceM > lookaheadM || distanceM >= bestDistanceM) continue

        val bearingToHazard = tollBearingDegrees(lat, lng, hazard.latitude, hazard.longitude)
        if (angularDifferenceDeg(headingDeg, bearingToHazard) > bearingToleranceDeg) continue

        best = hazard
        bestDistanceM = distanceM
    }

    val hazard = best ?: return null
    return UpcomingHazard(
        category = hazard.category,
        headline = hazard.headline,
        speedLimitKmh = hazard.speedLimit,
        distanceAheadM = bestDistanceM,
    )
}
