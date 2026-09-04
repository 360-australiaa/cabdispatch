package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.AnnouncementDto
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.IncentiveProgressDto
import au.com.threesixty.cabdispatch.data.remote.RatingDto
import au.com.threesixty.cabdispatch.data.remote.WalletDto

/**
 * Driver-engagement domain (`backend/app/api/v1/me.py`, commit 58ccfcf): the calling driver's own
 * wallet ledger, trip-rating summary, live announcements and incentive progress — the four
 * dashboard tiles in [au.com.threesixty.cabdispatch.ui.screens.dashboard.EngagementTiles].
 *
 * Thin network-only `Result<T>` shape, same as [ZonesRepository]/[JobsRepository], not Room-backed:
 * every one of these is a server-derived read (the balance is a live SUM of the ledger, the
 * rating an aggregate, announcements/incentives are "live right now" windows), so there's nothing
 * meaningful to persist or queue offline — a driver who's offline sees the honest error/retry
 * state in the tile, never a stale number presented as current. Read-only by design: a driver
 * cannot post to their own wallet (owner/admin-gated `POST /v1/wallet/transactions`), so this
 * interface deliberately has no write.
 */
interface DriverEngagementRepository {
    suspend fun wallet(recentLimit: Int = 20): Result<WalletDto>
    suspend fun rating(recentLimit: Int = 10): Result<RatingDto>
    suspend fun announcements(): Result<List<AnnouncementDto>>
    suspend fun incentives(): Result<List<IncentiveProgressDto>>
}

class RemoteBackedDriverEngagementRepository(private val apiService: ApiService) : DriverEngagementRepository {
    override suspend fun wallet(recentLimit: Int): Result<WalletDto> =
        runCatching { apiService.myWallet(limit = recentLimit) }

    override suspend fun rating(recentLimit: Int): Result<RatingDto> =
        runCatching { apiService.myRating(limit = recentLimit) }

    override suspend fun announcements(): Result<List<AnnouncementDto>> =
        runCatching { apiService.myAnnouncements().items }

    override suspend fun incentives(): Result<List<IncentiveProgressDto>> =
        runCatching { apiService.myIncentives().items }
}
