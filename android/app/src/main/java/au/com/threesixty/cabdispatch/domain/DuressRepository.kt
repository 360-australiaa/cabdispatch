package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DuressCancelRequestDto
import au.com.threesixty.cabdispatch.data.remote.DuressEventDto
import au.com.threesixty.cabdispatch.data.remote.DuressGpsPointDto
import au.com.threesixty.cabdispatch.data.remote.DuressSnapshotDto
import au.com.threesixty.cabdispatch.data.remote.DuressTriggerRequestDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

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

    /** Uploads a captured duress audio recording (`POST /v1/duress/{eventId}/audio`, multipart
     * `file` field — see `backend/app/api/v1/duress.py`). Driver-device-callable per that
     * router's role policy, same as [trigger]/[cancel]/[postGps]. Called from
     * [DuressController.stopAndUploadAudio] — best-effort, a failure here is swallowed by that
     * caller exactly like every other call in this interface. */
    suspend fun uploadAudio(eventId: String, file: File): Result<DuressEventDto>

    /** Uploads one captured cabin-camera JPEG frame (`POST /v1/duress/{eventId}/snapshot`,
     * multipart `file` field — see `backend/app/api/v1/duress.py`, same driver-callable role
     * policy as [uploadAudio]). Called from
     * [DuressController]'s active-phase loop for every frame [au.com.threesixty.cabdispatch.domain.duress.DuressCameraCapture]
     * emits — best-effort/fire-and-forget, a single dropped frame is never worth surfacing an
     * error for (there will be another one in ~3s). */
    suspend fun uploadSnapshot(eventId: String, jpegBytes: ByteArray): Result<Unit>
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

    override suspend fun uploadAudio(eventId: String, file: File): Result<DuressEventDto> = runCatching {
        // "audio/mp4" — a reasonable MIME for an MPEG-4-container/AAC (.m4a) file
        // (au.com.threesixty.cabdispatch.domain.duress.DuressAudioRecorder's actual output
        // format), not verified against the backend rejecting/accepting anything specific here —
        // `app/api/v1/duress.py#upload_audio` reads the multipart part generically
        // (`UploadFile`) and never inspects `content_type`, so this is not load-bearing either
        // way, just a best-effort correct label.
        val body = file.asRequestBody("audio/mp4".toMediaType())
        val part = MultipartBody.Part.createFormData("file", file.name, body)
        apiService.uploadDuressAudio(eventId, part)
    }

    override suspend fun uploadSnapshot(eventId: String, jpegBytes: ByteArray): Result<Unit> = runCatching {
        val body = jpegBytes.toRequestBody("image/jpeg".toMediaType())
        val part = MultipartBody.Part.createFormData("file", "frame.jpg", body)
        apiService.uploadDuressSnapshot(eventId, part)
        Unit
    }
}
