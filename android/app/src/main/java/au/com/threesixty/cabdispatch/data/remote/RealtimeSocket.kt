package au.com.threesixty.cabdispatch.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Raw text-frame WebSocket connector for the two realtime endpoints this pass adds support for —
 * `WS /v1/jobs/live` and `WS /v1/messages/live?driver_id=` (shared/API_SUMMARY.md "Notes for
 * downstream agents"). Deliberately NOT Retrofit: Retrofit has no first-class WebSocket support,
 * so [ApiService] only carries the HTTP jobs/messages endpoints — this class is the WS
 * counterpart, reusing the same [OkHttpClient] (connection pool, logging interceptor) via
 * [au.com.threesixty.cabdispatch.data.AppContainer.realtimeSocket].
 *
 * Deliberately NOT parsed into a typed event here: the backend does not publish a WS payload
 * schema in `shared/openapi.json` (FastAPI doesn't emit one for websocket routes, only HTTP), so
 * the exact frame envelope (e.g. the `job_offer` event's field names) is undocumented as of this
 * pass. Callers get raw JSON text via [connect] and should decode it themselves — e.g. peek a
 * discriminator field before assuming a shape — rather than this shared-foundation class guessing
 * a schema that might not match what the backend actually sends. If/when the backend team
 * documents the frame shape, add a typed `@Serializable` event class + a decode step here.
 */
class RealtimeSocket(private val okHttpClient: OkHttpClient) {

    /**
     * Opens a WS connection to [url] and emits every text frame received as a raw JSON string.
     * Cancelling collection of the returned [Flow] closes the socket (normal closure, code 1000).
     * A connection failure closes the flow with that [Throwable] — callers wanting
     * reconnect-with-backoff should catch/retry around their own `collect`, this class does not
     * retry internally.
     */
    fun connect(url: String): Flow<String> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }
        val socket = okHttpClient.newWebSocket(request, listener)
        awaitClose { socket.close(NORMAL_CLOSURE_CODE, "collector cancelled") }
    }

    companion object {
        private const val NORMAL_CLOSURE_CODE = 1000

        /**
         * `WS /v1/jobs/live?token=...` — pushes `job_offer` events to the offer's own driver
         * only (see module doc for why the payload isn't typed here).
         */
        fun jobsLiveUrl(baseHttpUrl: String, accessToken: String): String =
            wsUrl(baseHttpUrl, "/v1/jobs/live") + "?token=$accessToken"

        /**
         * `WS /v1/messages/live?driver_id=...&token=...` — a `driver`-role caller may only
         * subscribe to their own thread server-side.
         */
        fun messagesLiveUrl(baseHttpUrl: String, driverId: String, accessToken: String): String =
            wsUrl(baseHttpUrl, "/v1/messages/live") + "?driver_id=$driverId&token=$accessToken"

        /**
         * Browsers/OkHttp can't set custom headers on a WS handshake, so auth travels as a
         * `?token=` query param instead of the usual `Authorization` header — same pattern as
         * `WS /v1/fleet/live` and `WS /v1/duress/{id}/live` already use elsewhere in this API.
         */
        private fun wsUrl(baseHttpUrl: String, path: String): String {
            val httpBase = baseHttpUrl.trimEnd('/')
            val wsBase = when {
                httpBase.startsWith("https://") -> "wss://" + httpBase.removePrefix("https://")
                httpBase.startsWith("http://") -> "ws://" + httpBase.removePrefix("http://")
                else -> httpBase
            }
            return "$wsBase$path"
        }
    }
}
