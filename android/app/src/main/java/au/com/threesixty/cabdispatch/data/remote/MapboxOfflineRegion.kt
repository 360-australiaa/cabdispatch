package au.com.threesixty.cabdispatch.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * STUBBED build (no Mapbox Maps SDK bundled).
 *
 * The real implementation used Mapbox's Maps SDK v11 [TileStore]/[OfflineManager] to pre-download
 * a Sydney-metro region for fully-offline use. That SDK resolves only from Mapbox's private Maven
 * repo, which requires a secret `MAPBOX_DOWNLOADS_TOKEN` (sk.*) — not available on this machine —
 * so the dependency is currently commented out in app/build.gradle.kts and this file is reduced to
 * a no-op that preserves the exact public API its caller
 * ([au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel]) expects.
 *
 * With the SDK gone, [downloadSydneyMetroRegion] immediately reports [DownloadState.Failed]
 * ("offline map download unavailable"); the dashboard map itself still renders live via the
 * non-SDK Static Images fallback ([MapboxStaticImage]), which needs only the pk.* runtime token.
 *
 * To restore real offline downloads: add a real sk.* token to local.properties, re-enable the
 * `com.mapbox.maps:android` dependency, and restore this file from git history (the original SDK
 * implementation is preserved there).
 */
object MapboxOfflineRegion {

    /** Stable id for the one pre-defined offline region this app ships. */
    const val SYDNEY_METRO_REGION_ID = "sydney-metro"

    sealed interface DownloadState {
        data object Started : DownloadState
        /** [progressPercent] is 0-100. */
        data class InProgress(val progressPercent: Int) : DownloadState
        data object Completed : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    /**
     * No-op in this build. Emits [DownloadState.Failed] because no Mapbox Maps SDK is bundled to
     * perform a real tile-region download. Signature unchanged from the real implementation so the
     * caller compiles and behaves gracefully (the Settings screen shows the failure message).
     */
    fun downloadSydneyMetroRegion(accessToken: String): Flow<DownloadState> = flow {
        emit(
            DownloadState.Failed(
                "Offline map download unavailable in this build (Mapbox Maps SDK not bundled)",
            ),
        )
    }

    /** No-op in this build — there is no downloaded region to remove. */
    fun removeSydneyMetroRegion(accessToken: String) {
        // Intentionally empty: no TileStore in this build.
    }
}
