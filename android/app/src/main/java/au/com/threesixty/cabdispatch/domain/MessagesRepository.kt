package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.MessageCreateDto
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.data.remote.MessageListResponseDto
import au.com.threesixty.cabdispatch.data.remote.RealtimeSocket
import kotlinx.coroutines.flow.Flow

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

    /** Raw JSON text frames from `WS /v1/messages/live?driver_id=` — see [RealtimeSocket]'s doc
     * for why this is untyped `String`. A `driver`-role caller may only subscribe to their own
     * thread server-side. */
    fun observeLive(driverId: String, accessToken: String): Flow<String>
}

class RemoteBackedMessagesRepository(
    private val apiService: ApiService,
    private val realtimeSocket: RealtimeSocket,
    private val baseHttpUrl: String,
) : MessagesRepository {

    override suspend fun sendMessage(driverId: String?, body: String): Result<MessageDto> =
        runCatching { apiService.sendMessage(MessageCreateDto(driverId = driverId, body = body)) }

    override suspend fun listThread(driverId: String, skip: Int, limit: Int): Result<MessageListResponseDto> =
        runCatching { apiService.listMessages(driverId = driverId, skip = skip, limit = limit) }

    override suspend fun markRead(messageId: String): Result<MessageDto> =
        runCatching { apiService.markMessageRead(messageId) }

    override fun observeLive(driverId: String, accessToken: String): Flow<String> =
        realtimeSocket.connect(RealtimeSocket.messagesLiveUrl(baseHttpUrl, driverId, accessToken))
}
