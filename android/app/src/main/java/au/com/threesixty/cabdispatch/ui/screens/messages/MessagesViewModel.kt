package au.com.threesixty.cabdispatch.ui.screens.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.data.remote.MessageTemplateDto
import au.com.threesixty.cabdispatch.domain.SessionHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString

/**
 * S13/S14 — Messages (wheel-slot list content + full thread/quick-reply detail screen), per spec
 * TCT-DRIVER-APP-01.md §4 ("Messages: list of dispatch messages — sender, preview text,
 * timestamp") and §8 row 13-14 (detail screen "not yet designed" — designed inline here).
 *
 * One thread per driver (`thread_id == driver_id`, see [au.com.threesixty.cabdispatch.domain.MessagesRepository]
 * doc) — this app only ever shows/sends into the signed-in driver's own thread, so there is no
 * thread-list concept here, only a single thread's messages. [MessagesWheelContent] (the wheel
 * slot) and [MessageThreadScreen] (the detail/quick-reply screen) share this one ViewModel so a
 * reply sent from the detail screen is immediately reflected if the driver rotates back to the
 * wheel slot without navigating away.
 */
data class MessagesUiState(
    val loading: Boolean = true,
    /** Ascending by [MessageDto.sentAt] (oldest first) — natural reading order for the thread
     * view; [MessagesWheelContent] reverses this for its "most recent on top" list. */
    val messages: List<MessageDto> = emptyList(),
    val error: String? = null,
    val composerText: String = "",
    val sending: Boolean = false,
    val sendError: String? = null,
    /** Driver-side quick-tap canned templates ("No Job"/"Recall"/"Job Query"/"Other" — a real
     * competitor taxi-meter's quick-request menu, see `GET /v1/messages/templates`). Fetched once
     * per process via [MessageTemplatesCache], not per-ViewModel-instance — see that object's doc.
     * Already filtered to `sender_type == "driver"`; this app never sends a dispatch-side code. */
    val templates: List<MessageTemplateDto> = emptyList(),
    val templatesError: String? = null,
    /** Non-null while a quick-tap template send is in flight, holding that template's
     * [MessageTemplateDto.code] so only the tapped button shows a busy state — a driver mashing a
     * different button while one send is still in flight is otherwise a plausible slip at the
     * wheel. */
    val sendingTemplateCode: String? = null,
    val templateSendError: String? = null,
    /** Optional free-text suffix, entered only for the "other" template's small inline field — see
     * [MessageThreadScreen]'s `TemplateQuickTapRow`. */
    val otherNoteText: String = "",
) {
    val unreadCount: Int get() = messages.count { it.senderType == "dispatch" && it.readAt == null }
}

/**
 * Process-lifetime, in-memory cache for the canned-template menu — deliberately not Room-backed
 * like [au.com.threesixty.cabdispatch.sync.TariffCache]: this is a small, near-static, non-tenant-
 * specific list (see `app.api.v1.messages.list_templates`'s doc), so a per-process singleton is
 * plenty; no offline/persistence need the way tariffs have. [MessagesViewModel] instances are
 * per-nav-destination (see this file's top doc), so without this object each screen visit would
 * refetch — this is what actually makes "fetch once" true across the wheel-slot and thread screens.
 */
private object MessageTemplatesCache {
    private val mutex = Mutex()
    private var cached: List<MessageTemplateDto>? = null

    suspend fun getOrFetch(fetch: suspend () -> Result<List<MessageTemplateDto>>): Result<List<MessageTemplateDto>> {
        cached?.let { return Result.success(it) }
        return mutex.withLock {
            cached?.let { return@withLock Result.success(it) }
            fetch().onSuccess { cached = it }
        }
    }
}

class MessagesViewModel : ViewModel() {

    private val messagesRepository = AppContainer.messagesRepository
    private val driverId: String? = SessionHolder.session.value?.driverId

    private val _uiState = MutableStateFlow(MessagesUiState(loading = driverId != null))
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()

    init {
        if (driverId == null) {
            // No signed-in driver session yet (e.g. previewing this screen before S1 completes) —
            // degrade to an empty, non-loading thread rather than crashing.
            _uiState.update { it.copy(loading = false, error = "No active driver session.") }
        } else {
            loadThread()
            observeLive(driverId)
            loadTemplates()
        }
    }

    /** Fetches the canned-template menu (cached across instances, see [MessageTemplatesCache]) and
     * keeps only `sender_type == "driver"` entries — this is a driver-facing app, it should never
     * offer a dispatch-side quick-status code as a quick-tap button. */
    private fun loadTemplates() {
        viewModelScope.launch {
            MessageTemplatesCache.getOrFetch { messagesRepository.listTemplates() }
                .onSuccess { templates ->
                    _uiState.update {
                        it.copy(templates = templates.filter { t -> t.senderType == "driver" }, templatesError = null)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(templatesError = e.message ?: "Could not load quick messages") }
                }
        }
    }

    /** Loads the most recent 50 messages in the thread — no pagination/"load older" UI yet
     * (`skip`/`limit` default to [au.com.threesixty.cabdispatch.data.remote.ApiService.listMessages]'s
     * own 0/50 default); fine for a driver-dispatch thread's expected volume, revisit if that
     * assumption stops holding. */
    fun loadThread() {
        val driverId = this.driverId ?: return
        viewModelScope.launch {
            messagesRepository.listThread(driverId)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = null,
                            messages = response.items.sortedBy { m -> m.sentAt },
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(loading = false, error = e.message ?: "Could not load messages") }
                }
        }
    }

    /**
     * Subscribes to `WS /v1/messages/live` for real-time delivery and reconnects with a flat 3s
     * backoff on any disconnect/failure — [au.com.threesixty.cabdispatch.data.remote.RealtimeSocket]
     * itself does not retry, per its doc. Frame payloads are undocumented raw JSON (see that
     * class's doc); decoded defensively below — a frame that fails to parse as a bare [MessageDto]
     * falls back to a full [loadThread] refetch rather than dropping the update silently, since
     * the exact envelope shape (e.g. a `{event, message}` wrapper) is unknown as of this pass.
     */
    private fun observeLive(driverId: String) {
        viewModelScope.launch {
            while (isActive) {
                val token = AppContainer.accessToken
                if (token == null) {
                    delay(RECONNECT_DELAY_MS)
                    continue
                }
                runCatching {
                    messagesRepository.observeLive(driverId, token).collect { raw -> handleLiveFrame(raw) }
                }
                if (!isActive) break
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun handleLiveFrame(raw: String) {
        // Decode defensively — see method doc. Any failure (malformed/wrapped/unexpected shape)
        // falls back to a refetch rather than guessing at or crashing on an undocumented envelope.
        val decoded = runCatching { cabDispatchJson.decodeFromString<MessageDto>(raw) }.getOrNull()
        if (decoded != null) {
            _uiState.update { it.copy(messages = mergeMessage(it.messages, decoded)) }
        } else {
            loadThread()
        }
    }

    fun updateComposerText(text: String) {
        _uiState.update { it.copy(composerText = text, sendError = null) }
    }

    /** Quick-reply send — spec §8 row 14 "message detail/quick-reply". A `driver`-role caller
     * always sends as themselves server-side, so [driverId] is intentionally not sent here (see
     * [au.com.threesixty.cabdispatch.domain.MessagesRepository.sendMessage] doc). */
    fun sendReply() {
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty() || _uiState.value.sending) return
        _uiState.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            messagesRepository.sendMessage(driverId = null, body = text)
                .onSuccess { message ->
                    _uiState.update {
                        it.copy(sending = false, composerText = "", messages = mergeMessage(it.messages, message))
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(sending = false, sendError = e.message ?: "Could not send message") }
                }
        }
    }

    fun updateOtherNoteText(text: String) {
        _uiState.update { it.copy(otherNoteText = text) }
    }

    /** Quick-tap send — resolves [code] to a canned template server-side (see
     * [au.com.threesixty.cabdispatch.domain.MessagesRepository.sendTemplateMessage]'s doc) instead
     * of composing free text. [driverId] is intentionally not sent, same rationale as [sendReply].
     * Only the "other" template's optional [MessagesUiState.otherNoteText] is forwarded as [note] —
     * every other template ignores whatever's currently typed there. */
    fun sendTemplate(code: String) {
        if (_uiState.value.sendingTemplateCode != null) return
        val note = if (code == OTHER_TEMPLATE_CODE) {
            _uiState.value.otherNoteText.trim().ifBlank { null }
        } else {
            null
        }
        _uiState.update { it.copy(sendingTemplateCode = code, templateSendError = null) }
        viewModelScope.launch {
            messagesRepository.sendTemplateMessage(driverId = null, code = code, note = note)
                .onSuccess { message ->
                    _uiState.update {
                        it.copy(
                            sendingTemplateCode = null,
                            otherNoteText = if (code == OTHER_TEMPLATE_CODE) "" else it.otherNoteText,
                            messages = mergeMessage(it.messages, message),
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(sendingTemplateCode = null, templateSendError = e.message ?: "Could not send")
                    }
                }
        }
    }

    /** Marks every unread dispatch->driver message read — called when the detail screen opens. */
    fun markUnreadAsRead() {
        val unread = _uiState.value.messages.filter { it.senderType == "dispatch" && it.readAt == null }
        if (unread.isEmpty()) return
        viewModelScope.launch {
            unread.forEach { message ->
                messagesRepository.markRead(message.id).onSuccess { updated ->
                    _uiState.update { it.copy(messages = mergeMessage(it.messages, updated)) }
                }
            }
        }
    }

    private fun mergeMessage(current: List<MessageDto>, incoming: MessageDto): List<MessageDto> =
        (current.filterNot { it.id == incoming.id } + incoming).sortedBy { it.sentAt }

    companion object {
        private const val RECONNECT_DELAY_MS = 3000L

        /** Matches the backend's `app.services.messages.MESSAGE_TEMPLATES` "other" code exactly —
         * the sole template code this screen treats specially (shows the inline note field). */
        private const val OTHER_TEMPLATE_CODE = "other"
    }
}
