package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.local.dao.AirportZoneDao
import au.com.threesixty.cabdispatch.data.local.entity.AirportZoneEntity
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.GeofenceDto
import au.com.threesixty.cabdispatch.domain.AIRPORT_GEOFENCE_KIND
import au.com.threesixty.cabdispatch.domain.AirportZone
import au.com.threesixty.cabdispatch.domain.AirportZoneLookup
import au.com.threesixty.cabdispatch.domain.AirportZones
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Local cache of the tenant's airport-fee zones (`GET /v1/geofences?kind=airport&limit=200`) —
 * the terminal taxi-rank circles whose PICKUP attracts the airport access fee. Same role/shape
 * [TollRegistryCache] plays for toll gantries: this is what lets
 * [au.com.threesixty.cabdispatch.domain.FareEngineImpl.startTrip] charge the right rank's fee
 * for a trip started with zero connectivity. The fare engine reaches it ONLY through the
 * [AirportZoneLookup] seam ([zonesContaining]) — never [ApiService.listGeofences] directly.
 *
 * **Why the lookup is synchronous and in-memory.** [TollRegistryCache.snapshot] is a suspending
 * Room read the engine loads in the background, because a toll detected a few fixes late costs
 * nothing. The pickup fee is different: it is decided inside `startTrip` itself, before the
 * driver can tap the manual Airport chip, so it must answer instantly. This class therefore
 * mirrors its Room table into [zones] — populated by [warmUp] at process start (a pure local
 * read) and replaced by every successful [refresh] — and [zonesContaining] reads only that.
 *
 * **Offline-empty-cache contract (explicit, load-bearing):** [zonesContaining] returns `null`
 * — never throws, never blocks — while nothing has ever been cached (a brand-new install before
 * its first successful sync, or a tenant with no airport zones configured), and ALSO in the
 * brief window before [warmUp] has finished reading Room after a cold start. `null` tells
 * [au.com.threesixty.cabdispatch.domain.FareEngineImpl.startTrip] to fall back to
 * [au.com.threesixty.cabdispatch.domain.JurisdictionConfig]'s compiled precinct circle, so an
 * un-synced tablet still charges the regulated fee at the airport rather than silently charging
 * nothing. Once at least one zone is cached, the zones are authoritative: a start fix inside
 * none of them charges no fee, even inside the old 1.8 km circle.
 *
 * [refresh] is the only method that touches the network, and it is opportunistic/best-effort:
 * it rides the same triggers [TollRegistryCache.refresh] does (process start, login,
 * reconnect, the 15-minute sync worker, the simulator and pricing panes opening). Errors
 * propagate to the caller (who wraps in `runCatching`, per the same contract) and leave the
 * previously cached set untouched — [AirportZoneDao.replaceAll] runs only after a fully
 * successful fetch.
 */
class AirportZoneCache internal constructor(
    private val dao: AirportZoneDao,
    /** The one network call, as a function — so the cache's own unit test can hand it a
     * canned page instead of a Retrofit [ApiService]. Production goes through the secondary
     * constructor below. */
    private val fetchAirportGeofences: suspend () -> List<GeofenceDto>,
) : AirportZoneLookup {

    constructor(dao: AirportZoneDao, apiService: ApiService) : this(
        dao,
        { apiService.listGeofences(kind = AIRPORT_GEOFENCE_KIND, limit = 200).items },
    )

    /** In-memory mirror of the Room table — `null` until [warmUp]/[refresh] has run once. */
    @Volatile
    private var zones: List<AirportZone>? = null

    /**
     * Pure local read (no network) that loads the persisted zones into memory — call once at
     * process start, fire-and-forget. Idempotent; harmless to call again.
     */
    suspend fun warmUp() {
        zones = dao.getAll().map { it.toDomain() }
    }

    /** Pure local read — every cached zone, from Room. Never throws on an empty cache (returns
     * an empty list). Also refreshes the in-memory mirror as a side effect. */
    suspend fun snapshot(): List<AirportZone> = dao.getAll().map { it.toDomain() }.also { zones = it }

    /** What is in memory right now, without touching Room — `null` before [warmUp]. For display
     * surfaces (the pricing pane) that want "the cached fee" without a suspending read. */
    fun cachedZones(): List<AirportZone>? = zones

    /**
     * Fetches the tenant's airport zones and replaces the ENTIRE local cache atomically. Rows
     * whose `kind` is not `"airport"` (defensive: the query already filters) or whose fee does
     * not parse are dropped rather than cached with an invented fee — see
     * [GeofenceDto.tollAmountOrNull]. Throws/propagates network errors to the caller.
     */
    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val entities = fetchAirportGeofences()
            .filter { it.kind == AIRPORT_GEOFENCE_KIND }
            .mapNotNull { it.toEntity(now) }
        dao.replaceAll(entities)
        zones = entities.map { it.toDomain() }
    }

    /** See [AirportZoneLookup.zonesContaining] and this class's offline-empty-cache doc. */
    override fun zonesContaining(lat: Double, lng: Double): List<AirportZone>? {
        val cached = zones ?: return null
        if (cached.isEmpty()) return null
        return AirportZones.containing(cached, lat, lng)
    }

    private fun GeofenceDto.toEntity(fetchedAt: Long): AirportZoneEntity? {
        val fee = tollAmountOrNull() ?: return null
        if (radiusM <= 0.0) return null
        return AirportZoneEntity(
            id = id,
            name = name,
            centerLat = centerLat,
            centerLng = centerLng,
            radiusM = radiusM,
            feeAmount = fee.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            fetchedAt = fetchedAt,
        )
    }

    private fun AirportZoneEntity.toDomain(): AirportZone = AirportZone(
        id = id,
        name = name,
        centerLat = centerLat,
        centerLng = centerLng,
        radiusM = radiusM,
        fee = runCatching { BigDecimal(feeAmount).setScale(2, RoundingMode.HALF_UP) }.getOrDefault(BigDecimal.ZERO),
    )
}
