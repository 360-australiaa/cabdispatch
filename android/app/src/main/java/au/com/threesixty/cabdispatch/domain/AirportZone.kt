package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.domain.location.GeoMath
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One airport-fee zone, as the meter sees it: a circle around a terminal taxi rank (T1
 * International, T2/T3 Domestic, ...) plus the access fee a hiring that STARTS inside it
 * attracts. The on-device copy of a `kind="airport"` geofence
 * ([au.com.threesixty.cabdispatch.data.remote.GeofenceDto]), held in Room by
 * [au.com.threesixty.cabdispatch.sync.AirportZoneCache] so a tablet with no signal still
 * charges the right fee at the right rank.
 */
data class AirportZone(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLng: Double,
    val radiusM: Double,
    /** The access fee, already at money scale (2dp). */
    val fee: BigDecimal,
) {
    /** True when ([lat],[lng]) lies inside this zone's circle — same haversine
     * ([GeoMath.distanceKm]) [JurisdictionConfig.isInsideAirportPrecinct] uses for the legacy
     * precinct circle, so the two tests can never disagree about the shape of "inside". */
    fun contains(lat: Double, lng: Double): Boolean =
        GeoMath.distanceKm(lat, lng, centerLat, centerLng) * 1000.0 <= radiusM
}

object AirportZones {
    /**
     * Every zone in [zones] containing ([lat],[lng]), smallest radius first — so `.firstOrNull()`
     * is "the most specific zone", which is the one whose fee is charged when zones overlap (a
     * T2 rank circle drawn inside a wider T2/T3 precinct circle charges the T2 fee, once).
     */
    fun containing(zones: List<AirportZone>, lat: Double, lng: Double): List<AirportZone> =
        zones.filter { it.contains(lat, lng) }.sortedWith(compareBy({ it.radiusM }, { it.name }))
}

/**
 * The seam [FareEngineImpl] decides the airport pickup fee through — a pure function of the
 * start fix, so the engine stays a plain-JVM-testable class with no Room/network dependency
 * (same reasoning [TollRegistryProvider] gives for the toll registry). Real implementation:
 * [au.com.threesixty.cabdispatch.sync.AirportZoneCache], which answers from an in-memory copy
 * of its Room table — synchronous and instant, because the decision is made inside
 * [FareEngine.startTrip] itself, not on a later tick.
 *
 * Deliberately synchronous, unlike [TollRegistryProvider.snapshot]: a toll can be detected a
 * few fixes late with no harm, but the pickup fee is a fact about the FIRST fix and about the
 * ledger's state the instant the driver could tap the manual Airport chip — it has to be settled
 * before [FareEngine.startTrip] returns, not raced against the driver.
 */
fun interface AirportZoneLookup {
    /**
     * The cached zones containing ([lat],[lng]), smallest first — or **`null` when no airport
     * zone has ever been cached** (never synced, or the tenant has none configured). The
     * distinction is load-bearing: an empty list means "synced, and this pickup is not at an
     * airport rank" (charge nothing); `null` means "this tablet does not know where the ranks
     * are" (fall back to [JurisdictionConfig]'s compiled precinct circle so an un-synced tablet
     * still charges). See [FareEngineImpl.startTrip].
     */
    fun zonesContaining(lat: Double, lng: Double): List<AirportZone>?

    companion object {
        /** Never-synced fallback — the honest default for every call site (this project's own
         * tests included) that constructs a [FareEngineImpl] without naming a real lookup:
         * the compiled precinct circle keeps charging exactly as it did before zones existed. */
        val UNSYNCED = AirportZoneLookup { _, _ -> null }

        /** A lookup over a fixed list — what the cache and the tests both build on. An empty
         * list is reported as never-synced (`null`), per [zonesContaining]'s contract. */
        fun of(zones: List<AirportZone>): AirportZoneLookup = AirportZoneLookup { lat, lng ->
            if (zones.isEmpty()) null else AirportZones.containing(zones, lat, lng)
        }
    }
}

/**
 * What the trip record keeps about an airport access fee once one is on the ledger — enough for
 * Close & Pay, Trip Detail and the printed receipt (all of which rebuild the fare from the
 * persisted [au.com.threesixty.cabdispatch.data.local.entity.TripEntity], never from the live
 * engine) to name it under the "Tolls" total. Stored JSON-encoded in
 * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.airportAccessFeeJson].
 *
 * [zoneName] is the terminal rank the fee came from ("T1 International"), or `null` when the fee
 * was applied by the driver's manual Airport chip or by the compiled precinct-circle fallback —
 * neither of which knows a terminal, and this never invents one.
 */
@Serializable
data class AirportAccessFeeRecord(
    /** Decimal-as-string, this project's money convention. */
    val amount: String,
    val zoneName: String? = null,
) {
    /** The indented sub-line every fare surface prints under "Tolls":
     * `incl. Airport access fee $6.43 (T1 International)`. */
    fun subLine(): String {
        val money = runCatching { BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).toPlainString() }
            .getOrDefault(amount)
        val terminal = zoneName?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "incl. Airport access fee \$$money$terminal"
    }

    companion object {
        /** The ledger entry -> record mapping, from the [TollPreset] [FareEngineImpl] added (or the
         * driver tapped). `null` when the ledger carries no airport entry. */
        fun fromLedger(tollsApplied: List<TollPreset>): AirportAccessFeeRecord? =
            tollsApplied.firstOrNull { it.id == TollPresets.AIRPORT.id }?.let { preset ->
                AirportAccessFeeRecord(amount = preset.amount.toPlainString(), zoneName = preset.airportZoneName)
            }
    }
}
