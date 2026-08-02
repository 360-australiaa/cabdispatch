package au.com.threesixty.cabdispatch

import android.app.Application
import au.com.threesixty.cabdispatch.data.AppContainer
import com.mapbox.common.MapboxOptions

/**
 * Application entry point. Sole job: stand up [AppContainer] (the manual
 * ServiceLocator — see its class doc for why no Hilt/KSP) before any
 * Activity/ViewModel/Composable touches it, plus set the Mapbox runtime token.
 */
class CabDispatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)

        // Mapbox Maps SDK v11's programmatic-token pattern (not a manifest meta-data entry,
        // that was v9/v10). Empty-string-safe: if MAPBOX_ACCESS_TOKEN wasn't set in
        // local.properties, this sets an empty token and MapView will fail to load a style —
        // WheelDashboardScreen.kt's MapBackground already falls back to the illustrative grid in
        // that case, so this is a silent-degrade, not a crash.
        if (BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()) {
            MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
        }
    }
}
