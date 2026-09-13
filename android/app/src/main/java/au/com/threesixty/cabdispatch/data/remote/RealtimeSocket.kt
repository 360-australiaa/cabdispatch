package au.com.threesixty.cabdispatch.data.remote

import android.util.Log
import au.com.threesixty.cabdispatch.data.BatteryStatsCounters
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
     * A connection failure closes the flow with that [Throwable] — this class does not retry
     * internally. Most callers want [connectWithReconnect] instead; this raw form stays for
     * anyone (a future consumer, or a test) that genuinely wants a single one-shot connection with
     * no retry policy attached.
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

    /**
     * [connect], wrapped with the shared [ReconnectPolicy] (W4 task 5, 2026-09-12 optimisation
     * plan) — exponential backoff [ReconnectPolicy.MIN_DELAY_MS] to [ReconnectPolicy.MAX_DELAY_MS]
     * with jitter, reset once a connection has stayed open for [ReconnectPolicy.CONNECTED_GRACE_MS]
     * without failing, paused entirely while [isOnline] reports no network, resumed the instant it
     * flips true. Collapses what used to be independently hand-rolled flat-3s retry loops in
     * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel] and
     * [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel] onto this one
     * implementation.
     *
     * @param urlProvider Re-evaluated on every (re)connect attempt, not read once — so a token
     * refreshed mid-collection, or a session that only just paired, is picked up automatically
     * without the caller having to restart the flow itself. Returning `null` (no session/token
     * yet) is treated exactly like a failed attempt: the same backoff/jitter applies before trying
     * again, rather than a separate fixed poll.
     * @param isOnline The single "is there real connectivity right now" signal — this app already
     * has one process-lifetime instance,
     * [au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger.isOnline], reused here rather
     * than this class standing up its own second `ConnectivityManager.NetworkCallback`
     * registration for the identical question.
     * @param policy Injectable so a test can observe/drive the backoff explicitly; defaults to a
     * fresh [ReconnectPolicy] per call (i.e. per logical subscription), matching the "one policy
     * instance per socket, not shared across independent sockets" shape this app's two real call
     * sites (jobs, messages — see [au.com.threesixty.cabdispatch.domain.JobsRepository] and
     * [au.com.threesixty.cabdispatch.domain.MessagesRepository]) want: a flapping jobs socket must
     * not penalise the completely independent messages socket's own backoff.
     */
    fun connectWithReconnect(
        urlProvider: () -> String?,
        isOnline: StateFlow<Boolean>,
        policy: ReconnectPolicy = ReconnectPolicy(),
    ): Flow<String> = channelFlow {
        while (isActive) {
            isOnline.first { it } // suspends here for as long as ConnectivityManager reports none.
            val url = urlProvider()
            if (url == null) {
                delay(policy.nextDelayMillis())
                continue
            }
            BatteryStatsCounters.recordSocketReconnect()
            // A genuine `CancellationException` (the collector was cancelled -- e.g. its
            // ViewModel's scope ended) must NOT be swallowed here and treated as "connection
            // failed, retry" -- that would keep this loop spinning after whoever was collecting it
            // has already gone away. Every OTHER failure (the WS closing with an error, a DNS
            // failure, `urlProvider` returning null next time) is exactly what this loop exists to
            // retry, so only that narrower case is caught.
            try {
                coroutineScope {
                    // Reset the backoff once the connection has proven itself for
                    // CONNECTED_GRACE_MS, independent of whether any frame ever arrives on it —
                    // jobs/messages sockets can sit open, healthy, and silent for long stretches
                    // between real events, and a healthy-but-quiet connection must not be
                    // penalised as if it never succeeded.
                    val resetJob = launch {
                        delay(ReconnectPolicy.CONNECTED_GRACE_MS)
                        policy.reset()
                    }
                    try {
                        connect(url).collect { send(it) }
                    } finally {
                        resetJob.cancel()
                    }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                // Deliberately broad (W7 audit, 2026-09-13): this loop's entire purpose is to
                // survive and retry every way a socket connection can fail -- `okhttp3`'s
                // `WebSocketListener.onFailure` hands back a bare `Throwable`/`IOException` for
                // network faults, `connect`'s own `awaitClose`/`callbackFlow` machinery can
                // surface `IllegalStateException` on a channel already closed, and a malformed
                // frame can throw a serialization exception from a downstream collector chained
                // onto this flow. Narrowing this catch would mean adding a new exception type here
                // every time OkHttp or a caller changes what it throws, which is exactly the kind
                // of coupling a "keep retrying, no matter what broke" loop should not have.
                // CancellationException is re-thrown above so this never masks real cancellation.
                Log.w(TAG, "reconnect loop: connection attempt failed, backing off and retrying", e)
            }
            if (isActive) delay(policy.nextDelayMillis())
        }
    }

    companion object {
        private const val TAG = "RealtimeSocket"
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
