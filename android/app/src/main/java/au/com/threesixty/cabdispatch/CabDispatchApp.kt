package au.com.threesixty.cabdispatch

import android.app.Application
import au.com.threesixty.cabdispatch.data.AppContainer

/**
 * Application entry point. Sole job: stand up [AppContainer] (the manual
 * ServiceLocator — see its class doc for why no Hilt/KSP) before any
 * Activity/ViewModel/Composable touches it.
 *
 * NOTE: the Mapbox Maps SDK is not bundled in this build (no sk.* downloads token available —
 * see app/build.gradle.kts and MapboxOfflineRegion.kt), so there is no MapboxOptions token to set
 * here. The dashboard map renders via the non-SDK Static Images fallback, which reads the pk.*
 * token straight from BuildConfig where it's needed. Restore the MapboxOptions.accessToken call
 * here if/when the SDK dependency is re-enabled.
 */
class CabDispatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}
