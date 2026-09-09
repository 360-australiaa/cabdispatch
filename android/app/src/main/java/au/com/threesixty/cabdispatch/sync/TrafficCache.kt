package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.local.dao.TrafficCameraDao
import au.com.threesixty.cabdispatch.data.local.dao.TrafficHazardDao
import au.com.threesixty.cabdispatch.data.local.entity.TrafficCameraEntity
import au.com.threesixty.cabdispatch.data.local.entity.TrafficHazardEntity
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.TrafficCameraDto
import au.com.threesixty.cabdispatch.data.remote.TrafficHazardDto
import au.com.threesixty.cabdispatch.domain.TrafficCamera
import au.com.threesixty.cabdispatch.domain.TrafficHazard

/**
 * Local cache of the live NSW traffic reference data (`GET /v1/traffic/cameras`,
 * `GET /v1/traffic/hazards&active_only=true`) — same role/shape [AirportZoneCache] plays for the
 * airport-fee zones: a Room-backed mirror plus an in-memory copy so
 * [au.com.threesixty.cabdispatch.ui.screens.hired.MeterBackdropMap] can draw camera/hazard
 * markers with zero connectivity.
 *
 * **Deliberately NOT a fare-engine input.** Every other cache in this package
 * ([TollRegistryCache], [AirportZoneCache]) feeds a dollar figure the meter charges — a failed or
 * partial refresh there must never silently corrupt the previously-cached, fully-consistent set
 * (see each of those classes' own doc). Cameras and hazards are purely informational map
 * decoration, so this class trades that strictness for resilience instead: [refresh] fetches and
 * replaces cameras and hazards as two INDEPENDENT steps, each swallowing its own failure — a
 * hazards-feed outage must never stop a driver's camera markers from updating, and vice versa.
 * Bugs here can degrade what a driver sees on the map; they can never change what a fare charges.
 *
 * **Always fetched with a bbox, never the whole table.** The backend's `/v1/traffic` routes
 * cover the whole of NSW; sending every tablet in the fleet the entire state on every refresh
 * would be needless load on both the network and this device for markers a small map panel could
 * never usefully show at once (see [MeterBackdropMap]'s own doc on filtering to the visible map
 * bounds for the same reason, one step further down the pipe). [refresh] always resolves a bbox
 * from the caller's best-known position via [bboxAround] — a generous ~80km-square window around
 * it (see that function's own doc for the exact figure), or [NSW_WIDE_BBOX_FALLBACK] on the rare
 * call with no position at all yet (e.g. process start, before the first GPS fix).
 *
 * **Never calls livetraffic.com, or any third party, directly.** Every fetch goes through
 * [ApiService.trafficCameras]/[ApiService.trafficHazards] — OUR OWN backend, which is the only
 * thing that ever calls the public NSW feed (`backend/app/services/live_traffic.py`). This mirrors
 * the toll registry's/airport zones' own "routes through the backend first" rule.
 */
class TrafficCache(
    private val cameraDao: TrafficCameraDao,
    private val hazardDao: TrafficHazardDao,
    /** The two network calls, as functions — so this class's own unit test can hand them canned
     * pages instead of a Retrofit [ApiService]. Production goes through the secondary constructor
     * below. */
    private val fetchCameras: suspend (bbox: String) -> List<TrafficCameraDto>,
    private val fetchHazards: suspend (bbox: String) -> List<TrafficHazardDto>,
) {
    constructor(cameraDao: TrafficCameraDao, hazardDao: TrafficHazardDao, apiService: ApiService) : this(
        cameraDao,
        hazardDao,
        { bbox -> apiService.trafficCameras(bbox = bbox).items },
        { bbox -> apiService.trafficHazards(bbox = bbox, activeOnly = true).items },
    )

    /** In-memory mirrors of the Room tables — `null` until [warmUp]/[refresh] has run once, same
     * "honest null before anything is known" contract [AirportZoneCache.cachedZones] documents. */
    @Volatile
    private var cameras: List<TrafficCamera>? = null

    @Volatile
    private var hazards: List<TrafficHazard>? = null

    /** Pure local read (no network) that loads whatever Room already holds into memory — call
     * once at process start, fire-and-forget. Idempotent; harmless to call again. */
    suspend fun warmUp() {
        cameras = cameraDao.getAll().map { it.toDomain() }
        hazards = hazardDao.getAll().map { it.toDomain() }
    }

    /** What is in memory right now, without touching Room — `null` before [warmUp]/[refresh] has
     * ever completed. [au.com.threesixty.cabdispatch.ui.screens.hired.MeterBackdropMap] draws
     * nothing while either is `null`, exactly as it draws nothing for an empty list — there is no
     * meaningful difference on-screen between "not synced yet" and "synced, nothing near here". */
    fun cachedCameras(): List<TrafficCamera>? = cameras
    fun cachedHazards(): List<TrafficHazard>? = hazards

    /**
     * Fetches cameras and hazards inside a bbox resolved from [lat]/[lng] (see this class's own
     * doc) and replaces each local cache atomically. The two steps are independent — see this
     * class's own doc for why, unlike [TollRegistryCache.refresh]/[AirportZoneCache.refresh],
     * neither failure here propagates to the caller; both are simply swallowed, leaving whichever
     * cache did succeed updated and the other one untouched.
     */
    suspend fun refresh(lat: Double?, lng: Double?) {
        val bbox = bboxAround(lat, lng)
        val now = System.currentTimeMillis()
        runCatching {
            val entities = fetchCameras(bbox).map { it.toEntity(now) }
            cameraDao.replaceAll(entities)
            cameras = entities.map { it.toDomain() }
        }
        runCatching {
            val entities = fetchHazards(bbox).map { it.toEntity(now) }
            hazardDao.replaceAll(entities)
            hazards = entities.map { it.toDomain() }
        }
    }

    private fun TrafficCameraDto.toEntity(fetchedAt: Long): TrafficCameraEntity = TrafficCameraEntity(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        direction = direction,
        imageUrl = imageUrl,
        region = region,
        fetchedAt = fetchedAt,
    )

    private fun TrafficCameraEntity.toDomain(): TrafficCamera = TrafficCamera(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        direction = direction,
        imageUrl = imageUrl,
        region = region,
    )

    private fun TrafficHazardDto.toEntity(fetchedAt: Long): TrafficHazardEntity = TrafficHazardEntity(
        id = id,
        category = category,
        latitude = latitude,
        longitude = longitude,
        headline = headline,
        closureType = closureType,
        direction = direction,
        speedLimit = speedLimit,
        expectedDelayMinutes = expectedDelayMinutes,
        fetchedAt = fetchedAt,
    )

    private fun TrafficHazardEntity.toDomain(): TrafficHazard = TrafficHazard(
        id = id,
        category = category,
        latitude = latitude,
        longitude = longitude,
        headline = headline,
        closureType = closureType,
        direction = direction,
        speedLimit = speedLimit,
        expectedDelayMinutes = expectedDelayMinutes,
    )

    companion object {
        /**
         * Half-width, in degrees, of the bbox requested around a known position — roughly 80km
         * square at Sydney's latitude (1° latitude ≈ 111km everywhere; 1° longitude ≈ 111km *
         * cos(lat), ≈ 93km at -33.9°). Generous enough that an hour or two of ordinary driving
         * around a metro area (or between nearby regional towns) rarely needs a second refresh
         * mid-shift, while nowhere close to "the whole state" this class's own doc says to avoid —
         * NSW alone spans roughly 10° of latitude. A fixed degree box, not a fixed metre box: this
         * is a coarse fetch-scope decision (how much to download), not the precise on-screen
         * visible-bounds filter [au.com.threesixty.cabdispatch.ui.screens.hired.MeterBackdropMap]
         * applies separately before drawing a single marker.
         */
        private const val BBOX_HALF_DEG = 0.35

        /** Fallback for a refresh with no known position at all (process start, before the first
         * GPS fix) — the whole of NSW, roughly. Wide, but still a real bbox rather than `null`
         * (which the backend reads as "no filter", i.e. literally the whole table) — see
         * `backend/app/api/v1/traffic.py`'s `_parse_bbox`. */
        private const val NSW_WIDE_BBOX_FALLBACK = "140.8,-37.6,153.7,-28.1"

        /** `"minLng,minLat,maxLng,maxLat"` — the backend's own bbox format (see
         * `backend/app/api/v1/traffic.py::_parse_bbox`). */
        internal fun bboxAround(lat: Double?, lng: Double?, halfDeg: Double = BBOX_HALF_DEG): String {
            if (lat == null || lng == null) return NSW_WIDE_BBOX_FALLBACK
            val minLng = lng - halfDeg
            val minLat = lat - halfDeg
            val maxLng = lng + halfDeg
            val maxLat = lat + halfDeg
            return "$minLng,$minLat,$maxLng,$maxLat"
        }
    }
}
