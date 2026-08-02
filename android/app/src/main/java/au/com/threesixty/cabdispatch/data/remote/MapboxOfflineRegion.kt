package au.com.threesixty.cabdispatch.data.remote

import com.mapbox.bindgen.Expected
import com.mapbox.common.TileDataDomain
import com.mapbox.common.TileRegionError
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileRegionLoadProgress
import com.mapbox.common.TileStore
import com.mapbox.common.TileStoreOptions
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.Style
import com.mapbox.maps.TilesetDescriptorOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real offline map region download via Mapbox's Maps SDK v11 [TileStore]/[OfflineManager] —
 * added 2026-08-02 once a secret `MAPBOX_DOWNLOADS_TOKEN` became available (see
 * settings.gradle.kts's Maven-credentials block; without that secret token this whole SDK
 * dependency fails to resolve at all, which is why the app previously used the Static Images API
 * fallback instead — see [MapboxStaticImage]'s doc for that history, kept as the loading/error/
 * no-token fallback in [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]).
 *
 * **API-surface risk flag, read before touching this file:** this was written against a solid
 * general understanding of the Mapbox v11 offline API shape (`TileStore.create()` +
 * `OfflineManager.createTilesetDescriptor()` + `tileStore.loadTileRegion(...)`), but has never
 * been compiled — no Android SDK in the environment that wrote it. Of every file in this Android
 * module, THIS is the one most likely to need small signature fixes (class/method names, callback
 * shapes) once someone can actually build against the real SDK jar — Mapbox's offline API has
 * genuinely changed shape across v11 minor versions historically. Start here first if the build
 * fails inside `com.mapbox.*` types. Mapbox's own current docs
 * ("Android Offline Maps" / "TileStore" guide on docs.mapbox.com) are the authoritative reference
 * for reconciling any mismatch, not this comment.
 *
 * Once a region has been downloaded via [downloadSydneyMetroRegion], the SDK's [TileStore] serves
 * matching map/style requests from the local cache automatically — [WheelDashboardScreen]'s
 * `MapView` needs no separate "offline mode" code path, this is transparent to it.
 */
object MapboxOfflineRegion {

    /** Stable id for the one pre-defined offline region this app ships — a single "Sydney metro"
     * region is enough for the pilot fleet's operating area; multiple named regions can be added
     * later the same way if operators outside Sydney metro need their own. */
    const val SYDNEY_METRO_REGION_ID = "sydney-metro"

    /** Sydney metro bounding box, roughly Windsor/Penrith in the west to the coast in the east,
     * Hornsby in the north to Sutherland in the south — deliberately generous rather than tight,
     * a few km of slop at the edges costs little extra download size and avoids a driver crossing
     * just outside a tightly-drawn boundary and losing the map. */
    private val SYDNEY_METRO_BOUNDS: Polygon = Polygon.fromLngLats(
        listOf(
            listOf(
                Point.fromLngLat(150.55, -34.15), // SW
                Point.fromLngLat(151.45, -34.15), // SE
                Point.fromLngLat(151.45, -33.55), // NE
                Point.fromLngLat(150.55, -33.55), // NW
                Point.fromLngLat(150.55, -34.15), // close ring
            ),
        ),
    )

    /** Reasonable zoom range for a dashboard background (not turn-by-turn navigation) — higher
     * max zoom = larger download for detail nobody's looking at on a persistent background map. */
    private const val MIN_ZOOM = 0.0
    private const val MAX_ZOOM = 15.0

    sealed interface DownloadState {
        data object Started : DownloadState
        /** [progressPercent] is 0-100, derived from the SDK's completed/required-resource-count
         * progress callback. */
        data class InProgress(val progressPercent: Int) : DownloadState
        data object Completed : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private fun tileStore(accessToken: String): TileStore {
        val store = TileStore.create()
        store.setOption(
            TileStoreOptions.MAPBOX_ACCESS_TOKEN,
            TileDataDomain.MAPS,
            com.mapbox.bindgen.Value(accessToken),
        )
        return store
    }

    /**
     * Downloads [SYDNEY_METRO_BOUNDS] for fully-offline use afterward. Safe to call again later
     * (e.g. to refresh stale tiles) — `loadTileRegion` with the same region id updates the
     * existing region rather than duplicating it.
     *
     * @param accessToken the RUNTIME public pk.* token (same one [au.com.threesixty.cabdispatch.CabDispatchApp]
     *   sets on [com.mapbox.common.MapboxOptions]) — the offline download itself is billed/rate-limited
     *   against this token like any other map request, it does NOT need the secret downloads token
     *   (that one only gates the Gradle/Maven dependency resolution at build time, per
     *   settings.gradle.kts's comment).
     */
    fun downloadSydneyMetroRegion(accessToken: String): Flow<DownloadState> = callbackFlow {
        trySend(DownloadState.Started)

        val store = tileStore(accessToken)
        val offlineManager = OfflineManager()

        val tilesetDescriptor = offlineManager.createTilesetDescriptor(
            TilesetDescriptorOptions.Builder()
                .styleURI(Style.DARK)
                .minZoom(MIN_ZOOM.toInt().toByte())
                .maxZoom(MAX_ZOOM.toInt().toByte())
                .build(),
        )

        val loadOptions = TileRegionLoadOptions.Builder()
            .geometry(SYDNEY_METRO_BOUNDS)
            .descriptors(listOf(tilesetDescriptor))
            .acceptExpired(true)
            .networkRestriction(com.mapbox.common.NetworkRestriction.NONE)
            .build()

        val cancelable = store.loadTileRegion(
            SYDNEY_METRO_REGION_ID,
            loadOptions,
            { progress: TileRegionLoadProgress ->
                val required = progress.requiredResourceCount
                val completed = progress.completedResourceCount
                val pct = if (required > 0) ((completed * 100) / required).toInt().coerceIn(0, 100) else 0
                trySend(DownloadState.InProgress(pct))
            },
            { expected: Expected<TileRegionError, com.mapbox.common.TileRegion> ->
                if (expected.isValue) {
                    trySend(DownloadState.Completed)
                } else {
                    trySend(DownloadState.Failed(expected.error?.message ?: "Unknown offline-download error"))
                }
                close()
            },
        )

        awaitClose { cancelable.cancel() }
    }

    /** Removes the downloaded region, freeing its local storage — exposed for a "clear offline
     * maps" settings action; not currently wired to any UI, add one if device storage becomes a
     * real concern for pilot fleets. */
    fun removeSydneyMetroRegion(accessToken: String) {
        tileStore(accessToken).removeTileRegion(SYDNEY_METRO_REGION_ID)
    }
}
