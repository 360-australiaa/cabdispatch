package au.com.threesixty.cabdispatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost
import au.com.threesixty.cabdispatch.ui.theme.CabDispatchTheme

/**
 * Single Activity hosting the whole app (spec B5: six screens, S1–S6, are all
 * Compose destinations inside one NavHost — no per-screen Activities).
 *
 * Kiosk/lock-task mode (B1: "Kiosk/lock-task mode") is out of scope for this
 * skeleton pass; a later agent should call
 * `startLockTask()`/`DevicePolicyManager` setup from here once device-owner
 * provisioning is wired up.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CabDispatchScreenRoot()
        }
    }
}

@Composable
private fun CabDispatchScreenRoot() {
    CabDispatchTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CabDispatchNavHost()
        }
    }
}
