package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the driver-engagement domain (`backend/app/schemas/driver_engagement.py`, backend
 * commit 58ccfcf) — the four driver-facing `GET /v1/me/{wallet,rating,announcements,incentives}` reads behind the dashboard's WALLET
 * BALANCE / RATING / ANNOUNCEMENTS / INCENTIVE PROGRESS tiles
 * ([au.com.threesixty.cabdispatch.ui.screens.dashboard.EngagementTiles]).
 *
 * Money (`balance_aud`, `amount_aud`, `reward_aud`) and `average_stars` are Pydantic `Decimal`s,
 * which arrive as JSON *strings* — kept as [String] here per [ApiService]'s header rule (never
 * Float/Double); parse with `BigDecimal` at the display layer. Datetimes are ISO-8601 strings
 * normalised to UTC server-side. Unknown keys are ignored by the shared
 * [au.com.threesixty.cabdispatch.data.cabDispatchJson] config, so additive backend fields never
 * break an older build.
 */

// --- wallet ---------------------------------------------------------------------------------

/** Mirrors `WalletTransactionRead`. [kind] is one of `trip_earning | top_up | adjustment | payout`;
 * [amountAud] is the SIGNED delta applied to the balance (payouts are negative). */
@Serializable
data class WalletTransactionDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("amount_aud") val amountAud: String,
    val kind: String,
    val reference: String? = null,
    val note: String? = null,
    @SerialName("created_by_user_id") val createdByUserId: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/** Mirrors `WalletRead` (`GET /v1/me/wallet`). [balanceAud] is derived server-side as the SUM of
 * the ledger on every read — there is no stored balance to drift from it. */
@Serializable
data class WalletDto(
    @SerialName("driver_id") val driverId: String,
    @SerialName("balance_aud") val balanceAud: String,
    val recent: List<WalletTransactionDto> = emptyList(),
)

// --- ratings --------------------------------------------------------------------------------

/** Mirrors `TripRatingRead`. */
@Serializable
data class TripRatingDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("driver_id") val driverId: String,
    val stars: Int,
    val comment: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/** Mirrors `RatingRead` (`GET /v1/me/rating`). [averageStars] is `null` until the driver's first
 * rating — the UI must show "No ratings yet" in that case, never a stand-in score. */
@Serializable
data class RatingDto(
    @SerialName("driver_id") val driverId: String,
    @SerialName("average_stars") val averageStars: String? = null,
    @SerialName("rating_count") val ratingCount: Int = 0,
    val recent: List<TripRatingDto> = emptyList(),
)

// --- announcements --------------------------------------------------------------------------

/** Mirrors `AnnouncementRead`. [kind] is one of `info | maintenance | surge | feature`. */
@Serializable
data class AnnouncementDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    val title: String,
    val body: String,
    val kind: String = "info",
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String? = null,
    val active: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Mirrors `AnnouncementListRead` (`GET /v1/me/announcements`) — only currently-live ones,
 * already ordered newest (`starts_at`) first by the backend. */
@Serializable
data class AnnouncementListDto(val items: List<AnnouncementDto> = emptyList())

// --- incentives -----------------------------------------------------------------------------

/** Mirrors `IncentiveProgressRead` — one live incentive plus the calling driver's derived
 * progress ([completedTrips] / [targetTrips], [achieved]). */
@Serializable
data class IncentiveProgressDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    val title: String,
    val description: String? = null,
    @SerialName("target_trips") val targetTrips: Int,
    @SerialName("reward_aud") val rewardAud: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    val active: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("completed_trips") val completedTrips: Int = 0,
    @SerialName("remaining_trips") val remainingTrips: Int = 0,
    @SerialName("progress_pct") val progressPct: Int = 0,
    val achieved: Boolean = false,
)

/** Mirrors `IncentiveProgressListRead` (`GET /v1/me/incentives`), soonest-ending first. */
@Serializable
data class IncentiveProgressListDto(val items: List<IncentiveProgressDto> = emptyList())
