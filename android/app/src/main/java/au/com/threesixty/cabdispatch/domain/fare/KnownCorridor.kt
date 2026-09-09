package au.com.threesixty.cabdispatch.domain.fare

import java.math.BigDecimal

/**
 * Known-corridor distance during a GPS blackout — owner's decision, 2026-09-09 (superseding an
 * earlier, same-session proposal to fall back to a waiting-time charge instead; that proposal is
 * explicitly NOT implemented anywhere in this codebase).
 *
 * **The problem:** [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick]'s F3 rule bills
 * NOTHING for the duration of a real GPS blackout (a tunnel blocking satellite signal — nothing to
 * do with cellular/network connectivity, and no network call exists anywhere in this path). That is
 * the correct conservative default for an ARBITRARY blackout, where the vehicle could genuinely be
 * anywhere. But for the specific, common Sydney case of a vehicle driving at speed through a real
 * road tunnel — Cross City Tunnel, Lane Cove Tunnel, NorthConnex, M5 East, the WestConnex tunnels —
 * this app already has real, mapped point geometry for that exact corridor, via the very same
 * on-device toll-road/gantry registry [onFix] already auto-detects tolls against
 * ([au.com.threesixty.cabdispatch.sync.TollRegistryCache.snapshot], cached from
 * `GET /v1/toll-roads` + per-road gantry detail, no network call from this hot path). A tunnel long
 * enough to black out GPS completely is, in practice, virtually always a toll tunnel this registry
 * knows the shape of.
 *
 * **What this file decides, and does not decide:** [knownCorridorDistanceKm] answers exactly one
 * question — given the last fix before a blackout and the first fix after it, is there a real
 * mapped road whose own gantry points both fixes plausibly sit on, and if so, what is the real
 * distance along THAT road's own points between them? It is pure geometry over already-cached data.
 * It knows nothing about tolls, pricing, or money — [au.com.threesixty.cabdispatch.domain.FareEngineImpl.tick]
 * is what turns a non-null answer into a distance-rate charge (see that method's own "known-corridor"
 * section), through the exact same [FareEngine.tick] distance-accrual call every ordinary GPS-tick
 * already uses. Toll CHARGING for the same road, if any, is [onFix]'s separate, unaffected concern —
 * a corridor match here never itself adds or changes a toll line.
 *
 * **Deliberately rejected alternatives** (both considered this session, both explicitly out): generic
 * dead-reckoning from the last known speed/heading (reopens exactly the F3 overbilling bug this
 * engine was fixed to close — a vehicle that slowed or stopped mid-tunnel would be billed as if it
 * kept going), and any device-sensor-based (accelerometer/gyroscope) distance estimate (same
 * fabricated-distance risk [MeterAccuracyTest]'s "no fabricated distance" tests exist to catch, just
 * moved from GPS-speed-integration to a different unreliable sensor). The only distance this file
 * will ever produce is one that sums real, already-known, already-published road geometry — never a
 * guess at what the vehicle itself did in between.
 *
 * **The geometry, precisely.** The registry carries no stored ordering for a road's own gantries
 * (see [TollGantryEntity][au.com.threesixty.cabdispatch.data.local.entity.TollGantryEntity] — no
 * sequence column at all, by design: [onFix] only ever needed proximity, never path order). So a
 * road's own "point sequence" is reconstructed here, per match attempt, by a greedy nearest-neighbour
 * walk from the gantry nearest the entry fix to the gantry nearest the exit fix — the best ordering
 * available from data that was never captured with one. For the real, densely-and-fairly-evenly
 * spaced gantry chains in this app's actual dataset (M7's 45, the WestConnex tunnels' several each)
 * this reconstructs the true physical order in practice; it is not guaranteed to for a road whose
 * real gantry spacing is irregular enough that "nearest unvisited" skips ahead of the true next point
 * — see [greedyChainDistanceM]'s own doc for the honest limitation. Distance billed is the SUM of the
 * consecutive real segments — entry fix -> nearest gantry -> ... walked chain ... -> nearest gantry
 * -> exit fix — never the straight-line chord between the two fixes, so a curving tunnel is not
 * undercounted the way a plain haversine(entry, exit) would undercount it.
 *
 * **Rejection, not fabrication, is the default.** Returns `null` — no known distance, no charge, the
 * engine's existing "accrue nothing" fallback — whenever: no road's gantries lie within
 * [CORRIDOR_PORTAL_MATCH_RADIUS_M] of BOTH fixes (the ordinary "no known corridor here" case, or a
 * genuinely wrong/wildly-off reacquisition fix); the two fixes match to the very same single gantry
 * (no real path exists between one point and itself); or a road has fewer than two gantries at all
 * (nothing to interpolate along). Where more than one road's gantries plausibly bracket both fixes,
 * the SHORTEST candidate distance wins — the more parsimonious, less coincidental match.
 */
fun knownCorridorDistanceKm(
    registry: TollRegistrySnapshot,
    entryLat: Double,
    entryLng: Double,
    exitLat: Double,
    exitLng: Double,
): BigDecimal? {
    var bestKm: BigDecimal? = null
    for ((_, gantries) in registry.gantries.groupBy { it.tollRoadId }) {
        if (gantries.size < 2) continue // nothing to interpolate a path along

        val entryNearest = gantries.minByOrNull { tollHaversineM(entryLat, entryLng, it.latitude, it.longitude) }
            ?: continue
        val entryGapM = tollHaversineM(entryLat, entryLng, entryNearest.latitude, entryNearest.longitude)
        if (entryGapM > CORRIDOR_PORTAL_MATCH_RADIUS_M) continue // entry fix is not near this road at all

        val exitNearest = gantries.minByOrNull { tollHaversineM(exitLat, exitLng, it.latitude, it.longitude) }
            ?: continue
        val exitGapM = tollHaversineM(exitLat, exitLng, exitNearest.latitude, exitNearest.longitude)
        if (exitGapM > CORRIDOR_PORTAL_MATCH_RADIUS_M) continue // exit fix does not plausibly continue this road

        if (entryNearest.id == exitNearest.id) continue // same single point both ends -- no real path

        val pathM = greedyChainDistanceM(gantries, entryNearest, exitNearest) ?: continue
        val totalKm = BigDecimal.valueOf((entryGapM + pathM + exitGapM) / 1000.0)
        val currentBest = bestKm
        if (currentBest == null || totalKm < currentBest) bestKm = totalKm
    }
    return bestKm?.takeIf { it.signum() > 0 }
}

/**
 * Closest approach, in metres, either fix of a blackout must land to a road's own gantry before
 * that road is even considered a candidate corridor for this blackout.
 *
 * Sized like [TOLL_GANTRY_DETECTION_RADIUS_M] — real GPS accuracy (5-30m) plus the ground covered
 * between fixes at highway speed — but larger, deliberately: [TOLL_GANTRY_DETECTION_RADIUS_M] only
 * has to survive ordinary 1Hz fix jitter, while the EXIT side of a blackout can genuinely take a
 * fix cycle or two longer than that to re-lock after a real signal loss (a cold-ish reacquisition,
 * not a missed sample), so the vehicle can be materially further past the tunnel mouth before the
 * first usable fix lands. Still well short of the real cross-road gantry separations documented on
 * [TOLL_GANTRY_DETECTION_RADIUS_M] (~29-150m at the closest real interchanges): requiring BOTH the
 * entry AND the exit fix to land within this radius of the SAME road's own gantries — not just one
 * end — is the corroboration this feature relies on instead of [TOLL_MIN_CONFIRMATIONS]'s multi-
 * gantry scheme (a blackout, by definition, gives this feature only two fixes to work with, never a
 * sequence of confirmations along the way).
 */
const val CORRIDOR_PORTAL_MATCH_RADIUS_M = 250.0

/**
 * Sums the real, consecutive haversine legs of a greedy nearest-neighbour walk from [start] to [end]
 * over [gantries] (one road's own points) — the best available reconstruction of "this road's point
 * sequence" for a dataset that stores no path order at all (see [knownCorridorDistanceKm]'s own doc).
 *
 * **Honest limitation:** nearest-neighbour is not guaranteed to recover the true physical order of
 * an irregularly-spaced chain — a point could be geometrically closer to the current one than the
 * true "next" point along the road actually is, in which case this walk visits it out of turn. For
 * the real dataset's gantry spacing (fairly even along each corridor) this does not happen in
 * practice, but it is a real, acknowledged gap for a hypothetical road whose registry points are
 * sparse or unevenly spaced — see this feature's own report for which real tunnels that risk applies
 * to. `null` if the walk cannot reach [end] within [gantries]'s own size (should not happen for a
 * finite, correctly-tagged road; a defensive bound, not an expected outcome).
 */
internal fun greedyChainDistanceM(
    gantries: List<TollGantryRef>,
    start: TollGantryRef,
    end: TollGantryRef,
): Double? {
    if (start.id == end.id) return 0.0
    val remaining = gantries.filterNot { it.id == start.id }.toMutableList()
    var current = start
    var total = 0.0
    var stepsLeft = gantries.size
    while (current.id != end.id) {
        if (stepsLeft-- <= 0) return null
        val next = remaining.minByOrNull { tollHaversineM(current.latitude, current.longitude, it.latitude, it.longitude) }
            ?: return null
        total += tollHaversineM(current.latitude, current.longitude, next.latitude, next.longitude)
        remaining.removeAll { it.id == next.id }
        current = next
    }
    return total
}
