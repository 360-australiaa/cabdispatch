package au.com.threesixty.cabdispatch

import android.app.Application
import au.com.threesixty.cabdispatch.data.AppContainer
import com.mapbox.common.MapboxOptions

/**
 * Application entry point. Sole job: stand up [AppContainer] (the manual
 * ServiceLocator — see its class doc for why no Hilt/KSP) before any
 * Activity/ViewModel/Composable touches it.
 *
 * Also sets the global Mapbox access token (2026-08-28, restored now that the Maps SDK dependency
 * is re-enabled — see app/build.gradle.kts and MapboxOfflineRegion.kt). This was the missing half
 * of that re-enable: [au.com.threesixty.cabdispatch.data.remote.MapboxOfflineRegion.tileStore]'s
 * own doc already assumed `MapboxOptions.accessToken` was "already set once at startup by
 * CabDispatchApp" — it wasn't, this class still had the old no-SDK stub, so a real
 * [com.mapbox.common.TileStore]/[com.mapbox.maps.OfflineManager] call would have had no token to
 * authenticate with at runtime despite the SDK now compiling fine. A blank
 * [BuildConfig.MAPBOX_ACCESS_TOKEN] here is a harmless no-op assignment — every real Mapbox
 * request (SDK or the [au.com.threesixty.cabdispatch.data.remote.MapboxStaticImage] REST
 * fallback) already null/blank-checks the token itself before firing.
 */
class CabDispatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
        AppContainer.init(this)
    }
}
