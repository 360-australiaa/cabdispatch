package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.ZoneListResponseDto
import au.com.threesixty.cabdispatch.data.remote.ZonePlotReadDto
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto

/**
 * Zones domain (`backend/app/api/v1/zones.py`) — named dispatch zones, "plot into a zone" (a
 * driver marking themselves as actively waiting in a specific zone, distinct from just being on
 * shift), and the live per-zone demand-stats table — matches a real competitor taxi meter's
 * (MTI) zone-based demand screens, per
 * [au.com.threesixty.cabdispatch.ui.screens.zones.PlotZoneScreen]/
 * [au.com.threesixty.cabdispatch.ui.screens.zones.ZoneStatisticsScreen].
 *
 * Follows [JobsRepository]'s thin network-only `Result<T>` pattern, not Room/offline-queue-backed
 * like [au.com.threesixty.cabdispatch.data.repository.TripRepository]: like a job offer, "plot me
 * into zone X" and the live stats table are only ever meaningful *right now* — there's no
 * offline-queue story for "plot me in from 20 minutes ago" the way there is for a trip's fare
 * math, and a driver who's offline simply can't see live stats or change their plot until they're
 * back online, same as [JobsRepository]'s own doc for offers.
 */
interface ZonesRepository {
    suspend fun listZones(skip: Int = 0, limit: Int = 200): Result<ZoneListResponseDto>
    suspend fun getZoneStats(): Result<List<ZoneStatsDto>>

    /** Plots the calling driver's own current shift into [zoneId] — see
     * [ApiService.plotIntoZone]'s doc for the 404/409 cases a caller should expect in the
     * returned [Result.failure]. */
    suspend fun plotIntoZone(zoneId: String): Result<ZonePlotReadDto>

    /** Clears the calling driver's own current shift's plot, if any. */
    suspend fun unplot(): Result<ZonePlotReadDto>
}

class RemoteBackedZonesRepository(private val apiService: ApiService) : ZonesRepository {
    override suspend fun listZones(skip: Int, limit: Int): Result<ZoneListResponseDto> =
        runCatching { apiService.listZones(skip = skip, limit = limit) }

    override suspend fun getZoneStats(): Result<List<ZoneStatsDto>> =
        runCatching { apiService.zoneStats() }

    override suspend fun plotIntoZone(zoneId: String): Result<ZonePlotReadDto> =
        runCatching { apiService.plotIntoZone(zoneId) }

    override suspend fun unplot(): Result<ZonePlotReadDto> =
        runCatching { apiService.unplotZone() }
}
