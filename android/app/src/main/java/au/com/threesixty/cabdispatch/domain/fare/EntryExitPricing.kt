package au.com.threesixty.cabdispatch.domain.fare

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.ZonedDateTime

/** One Linkt time band of a [TollPricePairRef]: [day] "all" / "weekdays" / "weekend", [interval]
 * "HHMM-HHMM" (may wrap midnight, "1900-0630"), [price] the Tag base toll. */
data class TollPriceBandRef(val day: String, val interval: String, val price: BigDecimal)

/**
 * Linkt's own price for one entry point -> exit point trip (backend `TollPricePair`) -- the
 * `entry_exit` pricing model every NSW toll road uses since 2026-09-15 (owner: "copy all pricing
 * from Linkt, Linkt pricing is accurate"). [entryGantryId] / [exitGantryId] are [TollGantryRef.id]s
 * whose [TollGantryRef.ramp] is "entry" / "exit". Anzac Bridge -> Homebush Bay Drive is ONE pair
 * at $8.80 (WestConnex bills the Rozelle Interchange and the M4 East as one trip), which no
 * per-road formula reproduced.
 */
data class TollPricePairRef(
    val entryGantryId: String,
    val exitGantryId: String,
    val billingName: String,
    val classABands: List<TollPriceBandRef>,
) {
    val id: String get() = pairId(entryGantryId, exitGantryId)

    companion object {
        fun pairId(entryGantryId: String, exitGantryId: String): String = "$entryGantryId->$exitGantryId"
    }
}

/** One driven section of an `entry_exit` road: opened at [entryGantryId], priced by the last exit
 * point passed ([exitGantryId] / [amount] revised in place while the section stays open). */
data class EntryExitSection(
    val entryGantryId: String,
    /** The road of the entry point the trip is priced from -- re-pointed by the exit when a
     * co-located candidate entry of another road turns out to be the one Linkt prices. */
    var roadId: String,
    var exitGantryId: String? = null,
    var amount: BigDecimal? = null,
) {
    /** [entryGantryId] plus every co-located entry point confirmed while the section had no exit
     * yet: Linkt lists several entry points at one spot where the same on-ramp feeds different
     * products (Lane Cove Tunnel: "just the tunnel" vs "tunnel and Military Road e-ramp"); the
     * exit decides which of them the trip is priced from. */
    val candidateEntryIds: MutableList<String> = mutableListOf(entryGantryId)
}

/**
 * Linkt places an interchange's entry point and exit point at the same spot (Homebush Bay Drive:
 * 15 m apart), so a vehicle driving THROUGH an interchange confirms both. An entry point this
 * close to the exit just recorded is that same interchange, not a re-entry -- it never opens a
 * new section. (A driver who genuinely leaves and rejoins at the same interchange is billed one
 * trip instead of two: the cheaper reading, never the dearer guess.)
 */
const val ENTRY_EXIT_CO_LOCATED_M = 200.0

/** Every Linkt entry/exit point gantry id starts with this (backend `fetch_linkt_prices.py`). */
const val LINKT_POINT_ID_PREFIX = "LINKT:"

private const val MINUTES_PER_HOUR = 60
private const val HHMM_LENGTH = 4
private const val MONEY_SCALE = 2

private fun parseMinutes(hhmm: String): Int? {
    val hours = hhmm.take(2).toIntOrNull()
    val minutes = hhmm.drop(2).toIntOrNull()
    val valid = hhmm.length == HHMM_LENGTH && hours != null && minutes != null
    return if (valid) hours * MINUTES_PER_HOUR + minutes else null
}

private fun dayMatches(day: String, local: ZonedDateTime): Boolean {
    val weekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY
    return when (day) {
        "weekdays" -> !weekend
        "weekend" -> weekend
        else -> true
    }
}

internal fun bandMatches(band: TollPriceBandRef, local: ZonedDateTime): Boolean {
    val parts = band.interval.split("-")
    val start = parts.getOrNull(0)?.let(::parseMinutes)
    val end = parts.getOrNull(1)?.let(::parseMinutes)
    if (!dayMatches(band.day, local) || start == null || end == null) return false
    val minute = local.hour * MINUTES_PER_HOUR + local.minute
    return if (end <= start) minute >= start || minute < end else minute in start until end
}

/** The Linkt band in force at [ts] (evaluated in NSW local time), or null when none covers it.
 * Kotlin mirror of `app.services.tolls.select_pair_band_price`. */
fun selectPairBandPrice(bands: List<TollPriceBandRef>, ts: ZonedDateTime): BigDecimal? {
    val local = ts.withZoneSameInstant(NSW_FARE_ZONE)
    return bands.firstOrNull { bandMatches(it, local) }?.price
}

/** What one [applyEntryExitHits] pass reports back into [onFix]'s result. */
internal class EntryExitOutcome(
    val changed: MutableMap<String, BigDecimal>,
    val newlyUnpriced: MutableSet<String>,
) {
    val touchedRoads: MutableSet<String> = mutableSetOf()
}

/**
 * The `entry_exit` model -- Kotlin mirror of the backend's `_apply_entry_exit_hits`. An ENTRY point
 * opens a section unless one is still open with no exit yet, or the entry is the same interchange
 * as the exit just recorded (see [ENTRY_EXIT_CO_LOCATED_M]). An EXIT point Linkt prices from the
 * open section's entry sets -- or, for a later exit on the same section, REVISES -- that section's
 * charge to the pair price for the band in force now; the section stays revisable until the next
 * entry opens a new one. An exit Linkt does not price from this entry (the other carriageway's
 * point, or one behind us) is simply ignored. An exit with no section at all (the vehicle was
 * already on the motorway when the fare started) is flagged for the driver to add manually, never
 * guessed. Entries are processed before exits within one fix so a fare that starts at an
 * interchange opens its section before seeing that interchange's own exit point.
 *
 * **Abandoned sections (2026-09-16 field report)** -- real Sydney tablet trips, not the bench
 * simulator, drove a section Linkt has genuinely no through-price for at all (Anzac Bridge <->
 * Iron Cove Bridge within the Rozelle Interchange without continuing onto a WestConnex tunnel is
 * FREE; not every entry a vehicle merely passes near turns into a paid trip). The exit that closes
 * such a section is itself unremarkable -- ignored, same as any other exit "not ours" -- but until
 * this fix that left the section [EntryExitSection.exitGantryId] `null` FOREVER, and because
 * [openEntryExitSection] refuses to open a second section while one is still open, EVERY toll road
 * entered for the REST OF THE TRIP was silently swallowed into that same abandoned section and
 * never priced -- a real WestConnex/Harbour crossing billed $0.00 tolls with no evidence at all,
 * not even an "unpriced, add manually" flag. [openEntryExitSection] now closes an abandoned section
 * (flagging its road unpriced -- never silently dropped) the moment a genuinely new, non-co-located
 * entry shows the vehicle has moved on, so later roads are detected normally.
 *
 * [outcome] receives each touched road's new TOTAL across its sections, keyed by the entry
 * point's road id.
 */
internal fun applyEntryExitHits(
    state: TollDetectionState,
    registry: TollRegistrySnapshot,
    confirmedHits: List<TollGantryRef>,
    ts: ZonedDateTime,
    outcome: EntryExitOutcome,
) {
    // Only Linkt's own points price an entry_exit road; the road's TfNSW gantries are kept for
    // corridor geometry and never open or close a section.
    val linktHits = confirmedHits.filter { it.id.startsWith(LINKT_POINT_ID_PREFIX) }
    val exitsThisFix = linktHits.filter { it.ramp == "exit" }
    for (gantry in linktHits.sortedBy { if (it.ramp == "entry") 0 else 1 }) {
        val road = registry.roadsById[gantry.tollRoadId]
        if (road == null || road.pricingModel != "entry_exit" || road.id in state.dismissedRoadIds) continue
        when (gantry.ramp) {
            "entry" -> state.openEntryExitSection(registry, gantry, road.id, exitsThisFix, outcome)
            "exit" -> state.priceEntryExitSection(registry, gantry, road.id, ts, outcome)
        }
    }
    for (roadId in outcome.touchedRoads) {
        val sections = state.entryExitSections.filter { it.roadId == roadId }
        val total = sections
            .mapNotNull { it.amount }
            .fold(BigDecimal.ZERO, BigDecimal::add)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        if (sections.isEmpty()) {
            state.chargedRoads.remove(roadId) // the section moved to a sibling road: no $0.00 ghost line
        } else {
            state.chargedRoads[roadId] = total
        }
        state.unpricedRoadIds.remove(roadId)
        outcome.changed[roadId] = total
    }
}

private fun TollRegistrySnapshot.gantryDistanceM(gantryId: String, other: TollGantryRef): Double? =
    gantries.firstOrNull { it.id == gantryId }
        ?.let { tollHaversineM(it.latitude, it.longitude, other.latitude, other.longitude) }

// ReturnCount: guard-clause style, same accepted pattern as elsewhere in this file's package
// (e.g. InertialSpeedSource.maybeExtendLockAtFork) -- each clause is a distinct, named exit.
@Suppress("ReturnCount")
/** Whether [gantry] (an entry point) is at the same physical interchange as [last]'s own last
 * exit, or as an exit point also confirmed THIS fix -- "driving through", never a new section.
 * `false` with no [last] section at all. Split out of [openEntryExitSection] to keep that
 * function's own branching within detekt's complexity budget. */
private fun TollRegistrySnapshot.isSameInterchange(
    last: EntryExitSection?,
    gantry: TollGantryRef,
    exitsThisFix: List<TollGantryRef>,
): Boolean {
    if (last == null) return false
    val besideAnExitNow = exitsThisFix.any {
        tollHaversineM(it.latitude, it.longitude, gantry.latitude, gantry.longitude) <= ENTRY_EXIT_CO_LOCATED_M
    }
    if (besideAnExitNow) return true
    val lastExit = last.exitGantryId ?: return false
    return (gantryDistanceM(lastExit, gantry) ?: Double.MAX_VALUE) <= ENTRY_EXIT_CO_LOCATED_M
}

// LongParameterList: six positional inputs, same accepted pattern as onFix in TollDetector.kt --
// folding these into a data class would touch every caller for no clarity gain.
// ReturnCount: guard-clause style, same accepted pattern as isSameInterchange above.
@Suppress("LongParameterList", "ReturnCount")
/**
 * A still-open section (no exit yet) either folds [gantry] in as another candidate entry of the
 * SAME interchange, or -- 2026-09-16 field report -- was abandoned by an earlier exit Linkt had no
 * through-price for at all (see [applyEntryExitHits]' own doc). Before this fix that left the
 * section open FOREVER, silently swallowing every later road's own entry for the rest of the trip.
 * Now a genuinely new, non-co-located entry closes it out (flagged unpriced, never silently
 * dropped) and opens its own section fresh instead.
 */
private fun TollDetectionState.continueOpenSection(
    registry: TollRegistrySnapshot,
    gantry: TollGantryRef,
    roadId: String,
    exitsThisFix: List<TollGantryRef>,
    last: EntryExitSection,
    outcome: EntryExitOutcome,
) {
    if (gantry.id in last.candidateEntryIds) return
    val distanceM = registry.gantryDistanceM(last.entryGantryId, gantry) ?: Double.MAX_VALUE
    if (distanceM <= ENTRY_EXIT_CO_LOCATED_M) {
        last.candidateEntryIds += gantry.id
        return
    }
    if (registry.isSameInterchange(last, gantry, exitsThisFix)) return
    if (unpricedRoadIds.add(last.roadId)) outcome.newlyUnpriced.add(last.roadId)
    entryExitSections += EntryExitSection(entryGantryId = gantry.id, roadId = roadId)
}

private fun TollDetectionState.openEntryExitSection(
    registry: TollRegistrySnapshot,
    gantry: TollGantryRef,
    roadId: String,
    exitsThisFix: List<TollGantryRef>,
    outcome: EntryExitOutcome,
) {
    val last = entryExitSections.lastOrNull()
    if (last != null && last.exitGantryId == null) {
        continueOpenSection(registry, gantry, roadId, exitsThisFix, last, outcome)
        return
    }
    if (!registry.isSameInterchange(last, gantry, exitsThisFix)) {
        entryExitSections += EntryExitSection(entryGantryId = gantry.id, roadId = roadId)
    }
}

private fun TollDetectionState.priceEntryExitSection(
    registry: TollRegistrySnapshot,
    gantry: TollGantryRef,
    roadId: String,
    ts: ZonedDateTime,
    outcome: EntryExitOutcome,
) {
    val current = entryExitSections.lastOrNull()
    val pair = current?.candidateEntryIds
        ?.firstNotNullOfOrNull { entryId -> registry.pricePairs[TollPricePairRef.pairId(entryId, gantry.id)] }
    val price = pair?.let { selectPairBandPrice(it.classABands, ts) }
    when {
        current != null && price != null -> {
            val pricedEntryRoad = registry.gantries.firstOrNull { it.id == pair.entryGantryId }?.tollRoadId
            if (pricedEntryRoad != null && pricedEntryRoad != current.roadId) {
                outcome.touchedRoads += current.roadId
                current.roadId = pricedEntryRoad
            }
            current.exitGantryId = gantry.id
            current.amount = price
            outcome.touchedRoads += current.roadId
        }
        current == null || pair != null -> {
            // No section at all, or a pair with no band for this moment: the driver adds it manually.
            if (unpricedRoadIds.add(roadId)) outcome.newlyUnpriced.add(roadId)
        }
        else -> Unit // an exit Linkt does not price from this entry: not ours, ignore
    }
}
