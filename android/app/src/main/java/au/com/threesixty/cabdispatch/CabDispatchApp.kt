package au.com.threesixty.cabdispatch

import android.app.Application
import android.os.StrictMode
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
        // StrictMode, DEBUG builds only (W5 optimisation plan, 2026-09-12) -- `penaltyLog()`
        // ONLY, never `penaltyDeath()`. This is a real, safety-critical taxi-meter app a working
        // driver depends on mid-shift; a StrictMode violation is a signal to fix in the next debug
        // build, never a reason to crash a live fare in front of a passenger. `BuildConfig.DEBUG`
        // means a release build (what actually ships to the fleet) never pays this cost and can
        // never be affected by it — this exists purely to surface main-thread disk/network calls
        // and leaked closables to whoever is looking at `adb logcat` on a debug build, the same way
        // Android's own "Detect all" preset is documented to be used during development.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
        AppContainer.init(this)
    }
}
