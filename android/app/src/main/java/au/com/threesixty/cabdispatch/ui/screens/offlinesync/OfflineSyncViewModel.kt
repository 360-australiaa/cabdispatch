package au.com.threesixty.cabdispatch.ui.screens.offlinesync

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import au.com.threesixty.cabdispatch.data.AppContainer
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
        loadCachedTariff()
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
