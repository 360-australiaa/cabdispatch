package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for authentication, token lifecycle and the admin-PIN gate.
 *
 * Split out of [ApiService] verbatim in Phase 0 (P0.4) — same package, same
 * visibility, same declarations, no behaviour change. `DriverEngagementDtos.kt` set
 * the precedent. [ApiService]'s file header still carries the rules these all follow:
 * money and other Pydantic `Decimal`s arrive as JSON *strings* and are kept as [String]
 * (never Float/Double), datetimes are ISO-8601 normalised to UTC, and unknown keys are
 * ignored by the shared [au.com.threesixty.cabdispatch.data.cabDispatchJson] config so
 * additive backend fields never break an older build.
 */

@Serializable
data class LoginRequestDto(val email: String, val password: String)

/** Body for [ApiService.driverLogin]. `driverCode` is the meter's "Driver ID" field — globally
 * unique, auto-generated per driver user, distinct from `email`/staff login. */
@Serializable
data class DriverLoginRequestDto(
    @SerialName("driver_code") val driverCode: String,
    val pin: String,
    // REQUIRED by the backend. Driver codes are unique per tenant, not platform-wide, so a code
    // alone does not identify a driver. Omitting this is not a soft failure: the endpoint answers
    // `422 Field required: tenant_slug` and login is impossible for every driver on every tablet.
    // The tablet learns the value at pairing time (DevicePairingStore.getTenantSlug) rather than
    // asking a driver to type their operator's name at the start of every shift.
    @SerialName("tenant_slug") val tenantSlug: String,
)

/**
 * Mirrors the backend's `TokenResponse | MfaRequiredResponse` union response_model for
 * `POST /v1/auth/driver-login` (shared/API_SUMMARY.md "POST /v1/auth/driver-login" +
 * "Admin MFA (TOTP)"). kotlinx.serialization has no first-class support for an ad hoc union
 * response like this, so both shapes' fields are folded into one DTO, all nullable —
 * `ignoreUnknownKeys = true` (data/JsonConfig.kt) makes it safe for either shape's JSON to land
 * here without the other shape's fields present.
 *
 * Callers MUST check [mfaRequired] first:
 * - `true` -> only [mfaToken] is populated; exchange it (+ a 6-digit TOTP code) via
 *   [ApiService.mfaLogin] for a real [TokenResponseDto]. [accessToken]/[user] are absent.
 * - `null`/`false` -> a normal token response; [accessToken]/[refreshToken]/[user] are populated
 *   exactly as [TokenResponseDto] would be.
 */
@Serializable
data class DriverLoginResponseDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val user: UserDto? = null,
    @SerialName("mfa_required") val mfaRequired: Boolean? = null,
    @SerialName("mfa_token") val mfaToken: String? = null,
)

/** Body for [ApiService.mfaLogin] — exchanges the short-lived `mfa_token` from a
 * [DriverLoginResponseDto] (or the staff-login equivalent) plus a 6-digit TOTP `code`. */
@Serializable
data class MfaLoginRequestDto(
    @SerialName("mfa_token") val mfaToken: String,
    val code: String,
)

@Serializable
data class RefreshRequestDto(@SerialName("refresh_token") val refreshToken: String)

/** Real bug fixed 2026-09-06: [ApiService.refresh] used to declare [TokenResponseDto] as its
 * return type, which requires a non-null `user` field — but the backend's real
 * `POST /v1/auth/refresh` response (`app/schemas/auth.py`'s `RefreshResponse`) never carries one.
 * Never noticed because nothing called [ApiService.refresh] at all until
 * [au.com.threesixty.cabdispatch.data.AppContainer]'s token authenticator started calling it — a
 * real call against the old declared type would have thrown a deserialization error on every
 * refresh. */
@Serializable
data class RefreshResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    val user: UserDto,
)

@Serializable
data class UserDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String?,
    val role: String,
    val name: String,
    val email: String,
    val status: String,
    /** Relative on-disk path, per the backend's own doc — not a directly-loadable absolute
     * URL. `null` when no photo has ever been uploaded. Not used to build the image request
     * (`ApiService.getUserPhoto` is called by user id, not by this path) — kept only so a
     * `photo_url != null` check can drive "has a photo" UI state without an extra network round
     * trip. See `ui/screens/profile/ProfileViewModel.kt`. */
    @SerialName("photo_url") val photoUrl: String? = null,
    /**
     * The real backing field for a "VERIFIED" badge (2026-08-29, backend contract Part 2.1/10:
     * `"suitability_status == \"clear\""` is the concept a driver-verification badge should map
     * to — not a field literally named "verified"). Values beyond `"clear"` (e.g. pending/
     * flagged) are real but this app has no other UI for them yet — see
     * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s header, which shows
     * VERIFIED only on an exact `"clear"` match and shows nothing (not a false claim) otherwise.
     * `null` is treated the same as "not clear" — never assumed verified by omission.
     */
    @SerialName("suitability_status") val suitabilityStatus: String? = null,
    /**
     * Added for `ui/screens/profile/ProfileScreen.kt`'s Identity card (Phase H, 2026-09-03) —
     * `backend/app/schemas/user.py`'s `UserBase`/`UserRead` already carried [phone]/[createdAt]/
     * [driverLicenseExpiry] on every `GET /v1/auth/me` response, this DTO just wasn't reading them
     * yet. [phone] is the driver's contact number (`null` if never set — an honest "—", not a
     * fetch failure). [createdAt] backs the Identity card's "Member since" row. [driverLicenseExpiry]
     * (plain `YYYY-MM-DD`, no time component — a Pydantic `date`, not `datetime`) backs the
     * Documents tab's LICENCE row's real Verified/Expiring soon/Expired status — `null` means
     * "unknown", the same fail-open convention `app.services.compliance_expiry`'s own doc
     * describes, never rendered as expired.
     */
    val phone: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("driver_license_expiry") val driverLicenseExpiry: String? = null,
)

/** Body for [ApiService.verifyAdminPin] — same PIN shape as the backend's
 * `VerifyAdminPinRequest`/`AdminPinSetRequest` (4-8 digits). */
@Serializable
data class VerifyAdminPinRequestDto(val pin: String)

/** Response for [ApiService.verifyAdminPin]. [configured] `false` means the tenant has never set
 * an admin PIN at all — kept distinct from [valid] `false` (a PIN is set but this one is wrong)
 * so a caller can tell "nothing set up yet" from "wrong PIN" rather than treating both the same
 * (i.e. rather than silently allowing a destructive action just because nothing's configured
 * yet). Callers MUST check [configured] explicitly, per shared/API_SUMMARY.md's "Admin PIN" note. */
@Serializable
data class VerifyAdminPinResponseDto(val valid: Boolean, val configured: Boolean)
