package au.com.threesixty.cabdispatch.data.remote

import com.mapbox.bindgen.Expected
import com.mapbox.common.TileRegionError
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileRegionLoadProgress
import com.mapbox.common.TileStore
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.TilesetDescriptorOptions
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.location.GeoMath
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

    /** Stable id for the pilot fleet's home offline region — Sydney metro. */
    const val SYDNEY_METRO_REGION_ID = "sydney-metro"

    /** Stable id for the Karachi field-test offline region (2026-08-28 — see [downloadRegionNear],
     * added because [downloadSydneyMetroRegion] alone is useless while field-testing this build
     * outside Sydney: downloading Sydney tiles from Karachi costs bandwidth for a region the
     * device will never actually need offline). Two named regions is enough for right now (pilot
     * fleet + the active field test); generalize to an arbitrary-city lookup later if a third
     * ever shows up. */
    const val KARACHI_METRO_REGION_ID = "karachi-metro"

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

    /** Karachi metro bounding box — Hub River/Manghopir in the west to the coast/Korangi in the
     * east, roughly the airport/Malir in the north to the harbour in the south. Same "generous,
     * not tight" rationale as [SYDNEY_METRO_BOUNDS]. */
    private val KARACHI_METRO_BOUNDS: Polygon = Polygon.fromLngLats(
        listOf(
            listOf(
                Point.fromLngLat(66.80, 24.70), // SW
                Point.fromLngLat(67.35, 24.70), // SE
                Point.fromLngLat(67.35, 25.10), // NE
                Point.fromLngLat(66.80, 25.10), // NW
                Point.fromLngLat(66.80, 24.70), // close ring
            ),
        ),
    )

    /** [id] + generous-radius centre point for [downloadRegionNear] to pick the nearest region to
     * a given GPS fix — same "which metro area is this fix in" shape [RegionResolver] already
     * uses for tariff regions, kept separate since map-region selection and tariff-region
     * selection are unrelated concerns that happen to both need "distance to a reference city". */
    private data class NamedRegion(val id: String, val bounds: Polygon, val centerLat: Double, val centerLng: Double)

    private val SYDNEY = NamedRegion(SYDNEY_METRO_REGION_ID, SYDNEY_METRO_BOUNDS, -33.87, 151.21)
    private val KARACHI = NamedRegion(KARACHI_METRO_REGION_ID, KARACHI_METRO_BOUNDS, 24.86, 67.01)
    private val ALL_REGIONS = listOf(SYDNEY, KARACHI)

    /** Reasonable zoom range for a dashboard background (not turn-by-turn navigation) — higher
     * max zoom = larger download for detail nobody's looking at on a persistent background map. */
    private const val MIN_ZOOM = 0.0
    private const val MAX_ZOOM = 15.0

    sealed interface DownloadState {
        data object Started : DownloadState
        /** [progressPercent] is 0-100, derived from the SDK's completed/required-resource-count
         * progress callback. */
        data class InProgress(val progressPercent: Int) : DownloadState
        /** [regionId] is one of [SYDNEY_METRO_REGION_ID]/[KARACHI_METRO_REGION_ID] — added
         * 2026-08-28 alongside [downloadRegionNear] so callers can show which region actually
         * downloaded rather than assuming it was always Sydney (see SettingsScreen's
         * `OfflineMapsTile`, which used to hardcode "Sydney metro" regardless). */
        data class Completed(val regionId: String) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    // v11 SIGNATURE FIX (2026-08-28, first real compile against the actual v11.8.1 SDK jar --
    // see this file's own top-of-file "API-surface risk flag"): `TileStoreOptions.MAPBOX_ACCESS_TOKEN`
    // does not exist in v11 -- real compiler error, "Unresolved reference". Per Mapbox's own v11
    // migration guide, the per-TileStore access-token option was removed entirely in v11; a
    // `TileStore` now picks up the token centrally from `MapboxOptions.accessToken` (already set
    // once at startup by `CabDispatchApp` -- see this fun's old doc, which already assumed that
    // and was half-right: the global option really is authoritative, this v10-era `setOption`
    // call was simply dead/wrong code layered on top of it for v11). [accessToken] is kept as a
    // parameter only so [downloadSydneyMetroRegion]'s public signature stays unchanged for its
    // caller (SettingsViewModel) -- it does nothing here now.
    private fun tileStore(accessToken: String): TileStore = TileStore.create()

    /**
     * Downloads [region] for fully-offline use afterward. Safe to call again later (e.g. to
     * refresh stale tiles) — `loadTileRegion` with the same region id updates the existing region
     * rather than duplicating it.
     *
     * @param accessToken the RUNTIME public pk.* token (same one [au.com.threesixty.cabdispatch.CabDispatchApp]
     *   sets on [com.mapbox.common.MapboxOptions]) — the offline download itself is billed/rate-limited
     *   against this token like any other map request, it does NOT need the secret downloads token
     *   (that one only gates the Gradle/Maven dependency resolution at build time, per
     *   settings.gradle.kts's comment).
     */
    private fun downloadRegion(accessToken: String, region: NamedRegion): Flow<DownloadState> = callbackFlow {
        trySend(DownloadState.Started)

        val store = tileStore(accessToken)
        val offlineManager = OfflineManager()

        val tilesetDescriptor = offlineManager.createTilesetDescriptor(
            TilesetDescriptorOptions.Builder()
                .styleURI("mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za") // custom global style (2026-08-27), see MapboxStaticImage.STYLE
                // .toByte(), NOT plain Int -- corrected 2026-08-28, first real compile against
                // the actual v11.8.1 SDK jar: real compiler error was "Type mismatch: inferred
                // type is Int but Byte was expected". The previous comment here claimed the
                // opposite (Int, no Byte conversion) citing v11.4.0 docs -- that citation was
                // wrong for the resolved 11.8.1 jar (or was simply never actually checked
                // against real docs; this file's own top-of-file flag warned exactly this kind
                // of claim needed verification once someone could compile it). Kotlin does not
                // implicitly widen/narrow Byte<->Int, hence the explicit conversion.
                .minZoom(MIN_ZOOM.toInt().toByte())
                .maxZoom(MAX_ZOOM.toInt().toByte())
                .build(),
        )

        val loadOptions = TileRegionLoadOptions.Builder()
            .geometry(region.bounds)
            .descriptors(listOf(tilesetDescriptor))
            .acceptExpired(true)
            .networkRestriction(com.mapbox.common.NetworkRestriction.NONE)
            .build()

        val cancelable = store.loadTileRegion(
            region.id,
            loadOptions,
            { progress: TileRegionLoadProgress ->
                val required = progress.requiredResourceCount
                val completed = progress.completedResourceCount
                val pct = if (required > 0) ((completed * 100) / required).toInt().coerceIn(0, 100) else 0
                trySend(DownloadState.InProgress(pct))
            },
            { expected: Expected<TileRegionError, com.mapbox.common.TileRegion> ->
                if (expected.isValue) {
                    trySend(DownloadState.Completed(region.id))
                } else {
                    trySend(DownloadState.Failed(expected.error?.message ?: "Unknown offline-download error"))
                }
                close()
            },
        )

        awaitClose { cancelable.cancel() }
    }

    /** Convenience wrapper kept for any existing call site that specifically wants Sydney
     * regardless of the driver's current position — prefer [downloadRegionNear] for the normal
     * "download wherever I am" Settings-screen flow (see that function's doc). */
    fun downloadSydneyMetroRegion(accessToken: String): Flow<DownloadState> = downloadRegion(accessToken, SYDNEY)

    /**
     * Downloads whichever named metro region ([SYDNEY] or [KARACHI]) is nearest [fix] — added
     * 2026-08-28 for the active Karachi field test: the Settings screen's "download offline maps"
     * action previously always fetched Sydney tiles regardless of where the device actually was,
     * which is useless bandwidth spent on a region a Karachi-based test device will never need
     * offline. Falls back to Sydney (the pilot fleet's home region) when [fix] is `null` — same
     * "no fix yet" default every other GPS-dependent call site in this app already uses.
     */
    fun downloadRegionNear(accessToken: String, fix: LocationFix?): Flow<DownloadState> {
        val nearest = if (fix == null) {
            SYDNEY
        } else {
            ALL_REGIONS.minBy { GeoMath.distanceKm(fix.lat, fix.lng, it.centerLat, it.centerLng) }
        }
        return downloadRegion(accessToken, nearest)
    }

    /** Removes the downloaded region, freeing its local storage — exposed for a "clear offline
     * maps" settings action; not currently wired to any UI, add one if device storage becomes a
     * real concern for pilot fleets. */
    fun removeSydneyMetroRegion(accessToken: String) {
        tileStore(accessToken).removeTileRegion(SYDNEY_METRO_REGION_ID)
    }
}
