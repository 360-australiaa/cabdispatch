package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.GeofenceListResponseDto
import au.com.threesixty.cabdispatch.data.remote.GeofencePresetDto

/**
 * Geofences domain (`backend/app/api/v1/geofences.py`) — today only the `kind == "airport"`
 * subset matters on this device: the terminal-rank zones whose pickup attracts the airport
 * access fee.
 *
 * Same thin network-only `Result<T>` shape as [ZonesRepository]. This is NOT what the fare engine
 * reads — that goes through [au.com.threesixty.cabdispatch.sync.AirportZoneCache]'s persisted,
 * offline-safe copy (which fetches through [ApiService] directly, the way
 * [au.com.threesixty.cabdispatch.sync.TollRegistryCache] does). This wrapper exists for the
 * call sites that genuinely want the live server answer — the presets endpoint in particular,
 * which has no offline meaning at all (it is a seeding template, never a zone in force).
 */
interface GeofencesRepository {
    /** `GET /v1/geofences?kind=airport` — the tenant's airport zones as currently configured. */
    suspend fun listAirportZones(limit: Int = 200): Result<GeofenceListResponseDto>

    /** `GET /v1/geofences/presets/airport` — the backend's canonical T1/T2/T3 rank templates. */
    suspend fun airportPresets(): Result<List<GeofencePresetDto>>
}

class RemoteBackedGeofencesRepository(private val apiService: ApiService) : GeofencesRepository {
    override suspend fun listAirportZones(limit: Int): Result<GeofenceListResponseDto> =
        runCatching { apiService.listGeofences(kind = AIRPORT_GEOFENCE_KIND, limit = limit) }

    override suspend fun airportPresets(): Result<List<GeofencePresetDto>> =
        runCatching { apiService.airportGeofencePresets() }
}

/** The backend's `kind` discriminator for an airport-fee zone. */
const val AIRPORT_GEOFENCE_KIND = "airport"
