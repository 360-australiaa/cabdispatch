package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the driver/dispatch message threads and canned templates.
 *
 * Split out of [ApiService] verbatim in Phase 0 (P0.4) — same package, same
 * visibility, same declarations, no behaviour change. `DriverEngagementDtos.kt` set
 * the precedent. [ApiService]'s file header still carries the rules these all follow:
 * money and other Pydantic `Decimal`s arrive as JSON *strings* and are kept as [String]
 * (never Float/Double), datetimes are ISO-8601 normalised to UTC, and unknown keys are
 * ignored by the shared [au.com.threesixty.cabdispatch.data.cabDispatchJson] config so
 * additive backend fields never break an older build.
 */

// ---- Messages DTOs — mirror shared/openapi.json MessageCreate/MessageRead 1:1. ----

/** Body for `POST /v1/messages`. [driverId] identifies whose thread the message belongs to —
 * required for dispatch-side senders (owner/admin/dispatcher); ignored (and replaced with the
 * caller's own id) for a `driver`-role sender. */
@Serializable
data class MessageCreateDto(
    @SerialName("driver_id") val driverId: String? = null,
    val body: String,
)

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("thread_id") val threadId: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("sender_type") val senderType: String, // dispatch | driver
    @SerialName("sender_user_id") val senderUserId: String?,
    val body: String,
    @SerialName("sent_at") val sentAt: String,
    @SerialName("read_at") val readAt: String?,
)

@Serializable
data class MessageListResponseDto(
    val items: List<MessageDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)

/** One entry of `GET /v1/messages/templates` — mirrors `app.schemas.messages.MessageTemplateRead`
 * 1:1. [code] is the stable identifier passed to `POST /v1/messages/templates/{code}`; [label] is
 * the human-readable button text; [senderType] is `driver` or `dispatch` — this app should only
 * ever render the `driver`-typed entries as quick-tap buttons (a driver-role caller can't use a
 * dispatch-side code, see [ApiService.sendTemplateMessage]'s doc / the 400 it maps to). */
@Serializable
data class MessageTemplateDto(
    val code: String,
    val label: String,
    @SerialName("sender_type") val senderType: String, // dispatch | driver
)

/** Body for `POST /v1/messages/templates/{code}`. Same [MessageCreateDto.driverId] rule as a
 * free-text send. [note] is an optional free-text suffix — the UI should only surface an input
 * for it on the "other" template (see [ApiService.sendTemplateMessage]'s doc), though the backend
 * accepts it on any code. */
@Serializable
data class TemplateMessageCreateDto(
    @SerialName("driver_id") val driverId: String? = null,
    val note: String? = null,
)
