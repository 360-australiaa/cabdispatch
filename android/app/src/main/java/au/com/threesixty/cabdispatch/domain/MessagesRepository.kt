package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.MessageCreateDto
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.data.remote.MessageListResponseDto
import au.com.threesixty.cabdispatch.data.remote.MessageTemplateDto
import au.com.threesixty.cabdispatch.data.remote.RealtimeSocket
import au.com.threesixty.cabdispatch.data.remote.TemplateMessageCreateDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Messages (S13/S14) — dispatch<->driver threads, per spec TCT-DRIVER-APP-01.md §9. One thread
 * per driver (`thread_id == driver_id`). Thin network-only wrapper, same rationale as
 * [JobsRepository]: no offline queue, follows [ShiftRepository]'s `Result<T>` pattern.
 */
interface MessagesRepository {
    /** [driverId] is ignored server-side for a `driver`-role caller (always sends as themselves);
     * required for a dispatch-side (owner/admin/dispatcher) caller. */
    suspend fun sendMessage(driverId: String?, body: String): Result<MessageDto>
    suspend fun listThread(driverId: String, skip: Int = 0, limit: Int = 50): Result<MessageListResponseDto>
    suspend fun markRead(messageId: String): Result<MessageDto>

    /**
     * Raw JSON text frames from `WS /v1/messages/live?driver_id=` — see [RealtimeSocket]'s doc for
     * why this is untyped `String`. A `driver`-role caller may only subscribe to their own thread
     * server-side.
     *
     * Auto-reconnecting (W4 task 5, 2026-09-12 optimisation plan) via
     * [RealtimeSocket.connectWithReconnect] — see [JobsRepository.observeLiveOffers]'s identical
     * doc for the shared reconnect-policy shape and why [tokenProvider] is a function, not one
     * fixed token.
     */
    fun observeLive(driverId: String, tokenProvider: () -> String?): Flow<String>

    /** Canned quick-tap template menu — see [ApiService.listMessageTemplates]'s doc. Callers
     * should fetch once and cache (e.g. in a ViewModel's [kotlinx.coroutines.flow.StateFlow]),
     * not refetch per composition — the menu is not tenant/driver-specific and effectively
     * static for the lifetime of the app process. */
    suspend fun listTemplates(): Result<List<MessageTemplateDto>>

    /** Quick-tap send — see [ApiService.sendTemplateMessage]'s doc. Same [driverId] rule as
     * [sendMessage]. [note] is the optional free-text suffix (primarily for the "other"
     * template). */
    suspend fun sendTemplateMessage(driverId: String?, code: String, note: String? = null): Result<MessageDto>
}

class RemoteBackedMessagesRepository(
    private val apiService: ApiService,
    private val realtimeSocket: RealtimeSocket,
    private val baseHttpUrl: String,
    /** See [RealtimeSocket.connectWithReconnect]'s `isOnline` doc. */
    private val isOnline: StateFlow<Boolean>,
) : MessagesRepository {

    override suspend fun sendMessage(driverId: String?, body: String): Result<MessageDto> =
        runCatching { apiService.sendMessage(MessageCreateDto(driverId = driverId, body = body)) }

    override suspend fun listThread(driverId: String, skip: Int, limit: Int): Result<MessageListResponseDto> =
        runCatching { apiService.listMessages(driverId = driverId, skip = skip, limit = limit) }

    override suspend fun markRead(messageId: String): Result<MessageDto> =
        runCatching { apiService.markMessageRead(messageId) }

    override fun observeLive(driverId: String, tokenProvider: () -> String?): Flow<String> =
        realtimeSocket.connectWithReconnect(
            urlProvider = { tokenProvider()?.let { RealtimeSocket.messagesLiveUrl(baseHttpUrl, driverId, it) } },
            isOnline = isOnline,
        )

    override suspend fun listTemplates(): Result<List<MessageTemplateDto>> =
        runCatching { apiService.listMessageTemplates() }

    override suspend fun sendTemplateMessage(driverId: String?, code: String, note: String?): Result<MessageDto> =
        runCatching {
            apiService.sendTemplateMessage(code, TemplateMessageCreateDto(driverId = driverId, note = note))
        }
}
