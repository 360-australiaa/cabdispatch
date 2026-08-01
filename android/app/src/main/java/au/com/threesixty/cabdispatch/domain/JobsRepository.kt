package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.DriverAvailabilityDto
import au.com.threesixty.cabdispatch.data.remote.DriverAvailabilityUpdateDto
import au.com.threesixty.cabdispatch.data.remote.JobDto
import au.com.threesixty.cabdispatch.data.remote.JobListResponseDto
import au.com.threesixty.cabdispatch.data.remote.JobOfferDto
import au.com.threesixty.cabdispatch.data.remote.RealtimeSocket
import kotlinx.coroutines.flow.Flow

/**
 * Available Trips (S11/S12) — job/offer broadcast+accept, per spec TCT-DRIVER-APP-01.md §9.
 * Deliberately a thin network-only wrapper, NOT Room/offline-queue-backed like
 * [au.com.threesixty.cabdispatch.data.repository.TripRepository]: offers carry a ~20s
 * server-side expiry (shared/API_SUMMARY.md), so there is no meaningful "queue this write for
 * later sync" story the way there is for trips — a driver who's offline simply cannot receive or
 * accept an offer, full stop. Follows [ShiftRepository]'s simpler `Result<T>`-returning pattern
 * instead. If a sibling agent later wants an offline *read* cache (e.g. a "missed offers"
 * history screen), that's a reasonable Room-backed addition layered on top of this, not a
 * replacement for it.
 */
interface JobsRepository {
    suspend fun listJobs(status: String? = null, skip: Int = 0, limit: Int = 20): Result<JobListResponseDto>
    suspend fun getJob(jobId: String): Result<JobDto>

    /** Admin/dispatcher only server-side — will fail with a 403 `Result.failure` for a driver caller. */
    suspend fun cancelJob(jobId: String): Result<JobDto>
    suspend fun listOffers(jobId: String): Result<List<JobOfferDto>>
    suspend fun acceptOffer(jobId: String, offerId: String): Result<JobOfferDto>
    suspend fun declineOffer(jobId: String, offerId: String): Result<JobOfferDto>

    /** Drives the Off Duty/Available wheel slot's toggle — the write half of the offer-matching
     * rule (available AND on shift AND not mid-trip). */
    suspend fun setAvailability(isAvailable: Boolean): Result<DriverAvailabilityDto>

    /**
     * Raw JSON text frames from `WS /v1/jobs/live` (pushes `job_offer` events to this driver
     * only) — see [RealtimeSocket]'s doc for why this is untyped `String`, not a parsed event
     * class. [accessToken] is passed explicitly (rather than read from
     * [au.com.threesixty.cabdispatch.data.AppContainer.accessToken] internally) so the caller
     * controls exactly which session's token is used and can restart the flow on token refresh.
     */
    fun observeLiveOffers(accessToken: String): Flow<String>
}

class RemoteBackedJobsRepository(
    private val apiService: ApiService,
    private val realtimeSocket: RealtimeSocket,
    private val baseHttpUrl: String,
) : JobsRepository {

    override suspend fun listJobs(status: String?, skip: Int, limit: Int): Result<JobListResponseDto> =
        runCatching { apiService.listJobs(status = status, skip = skip, limit = limit) }

    override suspend fun getJob(jobId: String): Result<JobDto> =
        runCatching { apiService.getJob(jobId) }

    override suspend fun cancelJob(jobId: String): Result<JobDto> =
        runCatching { apiService.cancelJob(jobId) }

    override suspend fun listOffers(jobId: String): Result<List<JobOfferDto>> =
        runCatching { apiService.listJobOffers(jobId) }

    override suspend fun acceptOffer(jobId: String, offerId: String): Result<JobOfferDto> =
        runCatching { apiService.acceptJobOffer(jobId, offerId) }

    override suspend fun declineOffer(jobId: String, offerId: String): Result<JobOfferDto> =
        runCatching { apiService.declineJobOffer(jobId, offerId) }

    override suspend fun setAvailability(isAvailable: Boolean): Result<DriverAvailabilityDto> =
        runCatching {
            apiService.setDriverAvailability(DriverAvailabilityUpdateDto(isAvailable))
        }

    override fun observeLiveOffers(accessToken: String): Flow<String> =
        realtimeSocket.connect(RealtimeSocket.jobsLiveUrl(baseHttpUrl, accessToken))
}
