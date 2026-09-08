package au.com.threesixty.cabdispatch.ui.screens.offlinesync

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import au.com.threesixty.cabdispatch.sync.SyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OfflineSyncUiState(
    val isOffline: Boolean = false,
    val pendingOutboxCount: Int = 0,
    val cachedTariff: TariffDto? = null,
    val syncTriggeredJustNow: Boolean = false,
    /** Rows that have given up — see [OfflineSyncViewModel.retryDeadLettered] and finding S1/S2. */
    val failedRows: List<FailedSyncRow> = emptyList(),
)

/**
 * One dead-lettered outbox row, flattened for display (findings S1/S2).
 *
 * Before this pass these rows had no UI at all — a permanently-rejected trip was retried silently
 * forever, and a malformed one was skipped silently forever while still counted as "pending". Both
 * ended with the driver being told work was outstanding and never being told it had failed. This is
 * the honest version: the row is named, the server's reason is shown verbatim, and there is a
 * button to try again.
 */
data class FailedSyncRow(
    val id: Long,
    /** "Trip" / "Shift" — what the driver lost, in their words, not the enum's. */
    val label: String,
    val clientUuid: String,
    val attempts: Int,
    /** The last error verbatim. Not prettified: when a depot tech rings up about a trip that
     * won't sync, the raw server message is the only thing that identifies the cause. */
    val lastError: String?,
)

/**
 * Figma "35 · Offline & Sync" (fileKey `JhEhok3n9bntRNS5Y1u3Yc`, node `20:114`). This is a new
 * screen surfacing real, already-wired offline-first plumbing that had no dedicated UI before this
 * pass — it does not add any new offline/sync *behaviour*, only a place to see it:
 *
 * - **Trips pending sync**: [au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao.observeOutboxSize],
 *   whose own doc already says it exists to "drive a 'N trips pending sync' indicator in the UI
 *   (e.g. S2/S6)" — wired here for the first time.
 * - **Cached tariff**: [AppContainer.tariffCache] — a pure local Room read
 *   ([au.com.threesixty.cabdispatch.sync.TariffCache.getActiveTariff], never touches
 *   `domain/fare/`), the same signed/verified tariff cache the fare engine itself reads from.
 * - **Force sync now**: [SyncWorker.enqueueOneTime] — the exact same one-time work request
 *   [au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger] already fires automatically the
 *   instant connectivity returns; this button just lets a driver ask for it manually too.
 * - **Network status**: the same `ConnectivityManager` capability check
 *   [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.pollNetwork] already uses.
 *
 * Deliberately does NOT show a "driver login cache" or "duress SMS fallback" row the Figma mock
 * includes — neither concept exists anywhere in this codebase (no login-cache-expiry tracking, no
 * SMS-based duress fallback channel), and inventing either would be fabricating a feature, not
 * restyling one. See [au.com.threesixty.cabdispatch.ui.overlays.DuressOverlays] for the one real
 * duress UI this app has (data-relay based, not SMS) — out of scope for this screen to alter.
 */
class OfflineSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(OfflineSyncUiState())
    val uiState: StateFlow<OfflineSyncUiState> = _uiState.asStateFlow()

    init {
        pollNetwork()
        viewModelScope.launch {
            AppContainer.syncOutboxDao.observeOutboxSize().collect { count ->
                _uiState.update { it.copy(pendingOutboxCount = count) }
            }
        }
        viewModelScope.launch {
            AppContainer.syncOutboxDao.observeDeadLettered().collect { rows ->
                _uiState.update {
                    it.copy(
                        failedRows = rows.map { row ->
                            FailedSyncRow(
                                id = row.id,
                                label = if (row.entityType == OutboxEntityType.SHIFT) "Shift" else "Trip",
                                clientUuid = row.clientUuid,
                                attempts = row.attempts,
                                lastError = row.lastError,
                            )
                        },
                    )
                }
            }
        }
        loadCachedTariff()
    }

    /**
     * Puts one dead-lettered row back in the queue and asks for a drain immediately.
     *
     * The realistic case is that whatever the server was rejecting has since been fixed at the
     * depot end, and someone needs a way to say "try that one again" without reinstalling the app
     * and losing the trip entirely. Resetting `attempts` gives the row a fresh five, and clearing
     * the backoff makes it eligible on the very next drain rather than in eight minutes.
     */
    fun retryDeadLettered(id: Long) {
        viewModelScope.launch {
            AppContainer.syncOutboxDao.resetForRetry(id)
            forceSyncNow()
        }
    }

    private fun pollNetwork() {
        val context = getApplication<Application>()
        val connectivityManager = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
        val caps = connectivityManager?.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _uiState.update { it.copy(isOffline = !online) }
    }

    private fun loadCachedTariff() {
        viewModelScope.launch {
            val region = RegionResolver.resolve(AppContainer.speedSource.locationFix.value)
            val dto = AppContainer.tariffCache.getActiveTariff(region = region)
            _uiState.update { it.copy(cachedTariff = dto) }
        }
    }

    /**
     * Manual "force sync now" — enqueues the exact same [SyncWorker] one-time request the
     * reconnect callback already fires automatically (see class doc). [syncTriggeredJustNow] is a
     * transient UI acknowledgement only (WorkManager itself has no synchronous "done" signal this
     * screen awaits) — it clears itself after [SYNC_ACK_MS] so re-tapping shows the same feedback
     * again rather than staying stuck "on".
     */
    fun forceSyncNow() {
        val context = getApplication<Application>()
        SyncWorker.enqueueOneTime(WorkManager.getInstance(context))
        _uiState.update { it.copy(syncTriggeredJustNow = true) }
        viewModelScope.launch {
            delay(SYNC_ACK_MS)
            _uiState.update { it.copy(syncTriggeredJustNow = false) }
        }
        pollNetwork()
    }

    companion object {
        private const val SYNC_ACK_MS = 2500L
    }
}
