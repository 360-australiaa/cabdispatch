package au.com.threesixty.cabdispatch.ui.screens.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

private data class ChecklistPermission(
    val manifestPermission: String,
    val label: String,
    val reason: String,
    val minSdk: Int = Build.VERSION_CODES.BASE,
)

/**
 * Permissions checklist (2026-08-10 meter-polish pass), reachable from Settings (S6) -- a
 * read-only status display of every runtime permission this app actually requests somewhere in
 * its real flow, each with a checkmark/cross for "granted right now". Deliberately NOT a request
 * flow: per the task brief, this app has no existing runtime-permission-request wiring to hook
 * into (grepping the whole module for ActivityCompat.requestPermissions or
 * rememberLauncherForActivityResult turns up nothing -- every existing
 * ContextCompat.checkSelfPermission call site in this codebase, e.g.
 * WheelDashboardViewModel.pollStatus, domain/location/RealLocationProvider.kt,
 * domain/duress/DuressAudioRecorder.kt, only ever checks, never requests), so adding one here
 * would be new, unreviewed surface area well beyond "add a checklist screen". A driver who sees a
 * permission listed as ungranted here still has to grant it via the OS Settings app -- flagged
 * loudly rather than silently implying this screen can fix it.
 *
 * INTERNET and ACCESS_NETWORK_STATE are deliberately excluded -- they are normal-protection-level
 * permissions (granted automatically at install, never a runtime prompt), so they would not read
 * as "not granted" alongside real dangerous-permission entries and would only add confusing noise
 * to what is meant to be a "did the OS actually grant this" checklist.
 */
@Composable
fun PermissionsChecklistScreen(navController: NavHostController) {
    val context = LocalContext.current
    val permissions = remember {
        listOf(
            ChecklistPermission(
                Manifest.permission.ACCESS_FINE_LOCATION,
                "Precise location",
                "Fare-engine GPS/speed fusion while a trip is in progress (spec B6).",
            ),
            ChecklistPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                "Background location",
                "Keeps a trip fare ticking if the app is briefly backgrounded mid-fare.",
            ),
            ChecklistPermission(
                Manifest.permission.CAMERA,
                "Camera",
                "QR vehicle pairing at Login/Vehicle bind (S1).",
            ),
            ChecklistPermission(
                Manifest.permission.RECORD_AUDIO,
                "Microphone",
                "Optional duress audio recording (Blueprint section 4.3/8.3) -- best-effort, " +
                    "never blocks the duress flow if ungranted.",
            ),
            ChecklistPermission(
                "android.permission.POST_NOTIFICATIONS",
                "Notifications",
                "Sync/duress/shift-report notifications.",
                minSdk = Build.VERSION_CODES.TIRAMISU,
            ),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(WheelColors.bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { navController.popBackStack() }) {
                    Text("< Back", color = WheelColors.textSecondary)
                }
            }
        }
        item {
            Text(
                "App permissions",
                style = MaterialTheme.typography.headlineSmall,
                color = WheelColors.textPrimary,
            )
        }
        item {
            Text(
                "Every runtime permission this app uses, and whether it is currently granted on " +
                    "this device. Grant or revoke permissions from Android Settings -- this " +
                    "screen is status-only.",
                color = WheelColors.textSecondary,
            )
        }

        items(permissions) { permission ->
            val applicable = Build.VERSION.SDK_INT >= permission.minSdk
            val granted = applicable &&
                ContextCompat.checkSelfPermission(context, permission.manifestPermission) ==
                PackageManager.PERMISSION_GRANTED

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(permission.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(permission.reason, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(0.dp))
                    Text(
                        when {
                            !applicable -> "N/A on this OS version"
                            granted -> "Granted"
                            else -> "Not granted"
                        },
                        color = when {
                            !applicable -> WheelColors.textMuted
                            granted -> WheelColors.available
                            else -> WheelColors.waiting
                        },
                    )
                }
            }
        }
    }
}
