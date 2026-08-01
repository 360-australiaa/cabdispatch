package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DuressCancelRequestDto
import au.com.threesixty.cabdispatch.data.remote.DuressEventDto
import au.com.threesixty.cabdispatch.data.remote.DuressGpsPointDto
import au.com.threesixty.cabdispatch.data.remote.DuressTriggerRequestDto

/**
 * Thin `Result<T>`-returning network wrapper over the duress endpoints (spec §8 rows 28-30 —
 * "Duress triggered"/"Duress active" contextual overlays), same shape/convention as
 * [JobsRepository]/[MessagesRepository] from the shared wheel-redesign foundation. No Room/offline
 * queue here either, and for the same reason those two skip it: a duress event is inherently a
 * live, server-authoritative safety record, not something meaningfully replayable from a local
 * queue hours later. [DuressController] is what actually decides retry/backoff policy on top of
 * this — this class is a dumb transport wrapper only.
 */
interface DuressRepository {
    suspend fun trigger(vehicleId: String, driverId: String): Result<DuressEventDto>
    suspend fun cancel(eventId: String): Result<DuressEventDto>
    suspend fun getEvent(eventId: String): Result<DuressEventDto>
    suspend fun postGps(eventId: String, lat: Double, lng: Double, speedKmh: Double?, accuracyM: Double?): Result<Unit>
}

class RemoteBackedDuressRepository(private val apiService: ApiService) : DuressRepository {

    override suspend fun trigger(vehicleId: String, driverId: String): Result<DuressEventDto> = runCatching {
        // "trigger" field is always "gesture" — the only trigger source this app implements is
        // the hidden triple-tap corner (spec B5 S3 / §6 step 8); "button"/"voice"/"auto" are
        // other trigger sources the backend schema anticipates but this device never sends.
        apiService.triggerDuress(
            DuressTriggerRequestDto(vehicleId = vehicleId, driverId = driverId, trigger = "gesture"),
        )
    }

    override suspend fun cancel(eventId: String): Result<DuressEventDto> = runCatching {
        apiService.cancelDuress(eventId, DuressCancelRequestDto())
    }

    override suspend fun getEvent(eventId: String): Result<DuressEventDto> = runCatching {
        apiService.getDuressEvent(eventId)
    }

    override suspend fun postGps(
        eventId: String,
        lat: Double,
        lng: Double,
        speedKmh: Double?,
        accuracyM: Double?,
    ): Result<Unit> = runCatching {
        apiService.postDuressGps(
            eventId,
            DuressGpsPointDto(lat = lat, lng = lng, speedKmh = speedKmh, accuracyM = accuracyM),
        )
    }
}
