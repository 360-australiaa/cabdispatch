package au.com.threesixty.cabdispatch

import android.app.Application
import au.com.threesixty.cabdispatch.data.AppContainer

/**
 * Application entry point. Sole job: stand up [AppContainer] (the manual
 * ServiceLocator — see its class doc for why no Hilt/KSP) before any
 * Activity/ViewModel/Composable touches it.
 */
class CabDispatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}
