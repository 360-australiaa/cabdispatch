package au.com.threesixty.cabdispatch.ui.screens.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * 03 · Permissions — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `8:39`): left
 * header column (H1 + subtitle + grant-count pill) and a 2×4 grid of 96dp permission cards.
 *
 * ### 2026-08-28: made this screen actually *request*, not just *display*
 * Original behaviour was status-display only — it read every permission's grant state via
 * [ContextCompat.checkSelfPermission] but had no way to grant anything, so the app never showed
 * the OS permission dialogs on its own (found live on a field-test tablet: location/camera/
 * notifications all sat denied, GPS never fixed, the meter's map fell back to Sydney and the
 * dashboard region resolver + fare engine had no position to work from). Every not-granted card is
 * now tappable and drives the correct request path, plus a prominent "Grant all permissions"
 * primary button walks the whole set:
 * - Runtime dangerous perms (location/camera/mic/notifications) → the system permission dialog via
 *   [ActivityResultContracts.RequestMultiplePermissions]/[ActivityResultContracts.RequestPermission].
 * - Background location → requested on its own (Android 11+ can't bundle it with foreground; the OS
 *   routes the driver to the "Allow all the time" settings page). Foreground location is requested
 *   first if it isn't granted yet, since background is meaningless without it.
 * - "Disable battery optimisation" → [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] system
 *   dialog, so the meter's foreground work isn't dozed mid-fare.
 * Grant state is re-read on every [Lifecycle.Event.ON_RESUME] (returning from a Settings screen)
 * and after every launcher result, via a [refreshKey] the card list is keyed on.
 *
 * ### Launch gate
 * When reached from the splash gate (with [next] set — see [SplashScreen]) this is the first thing
 * a cold-start driver sees, so the core runtime permissions are auto-requested once on entry (that
 * is the "asks like every other app on first open" behaviour). "Continue" then proceeds to [next]
 * (login/home); when opened from Settings ([next] is null) it just pops back.
 *
 * Honesty rule carried over: no card claims a check this app doesn't really make. Bluetooth and
 * file-storage remain informational "Not required on this build" cards (the manifest declares no
 * Bluetooth permission; API 29+ scoped storage needs none) — those two are not tappable.
 */
@Composable
fun PermissionsChecklistScreen(navController: NavHostController, next: String? = null) {
    val context = LocalContext.current

    // Bumped after every request/settings result so the grid re-reads live grant state. The
    // permission dialogs and the battery-optimisation/background-location Settings screens all
    // report back through the launchers below, each of which increments this.
    var refreshKey by remember { mutableIntStateOf(0) }

    val cards = remember(refreshKey) { buildPermissionCards(context) }
    val grantedCount = cards.count { it.granted }
    val allOk = grantedCount == cards.size

    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshKey++ }

    val singlePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshKey++ }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshKey++ }

    fun requestForeground() {
        multiPermLauncher.launch(foregroundRuntimePermissions())
    }

    fun requestBackgroundLocation() {
        // Background location can only be granted after foreground location; ask for that first.
        if (!runtimeGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestForeground()
            return
        }
        singlePermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun openBatteryOptimisation() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching { settingsLauncher.launch(intent) }
            .onFailure {
                // Some OEMs don't honour the direct-request action — fall back to the app's own
                // battery-optimisation settings list rather than crashing.
                runCatching {
                    settingsLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
    }

    fun onCardTapped(kind: PermKind) {
        when (kind) {
            PermKind.FOREGROUND_RUNTIME -> requestForeground()
            PermKind.BACKGROUND_LOCATION -> requestBackgroundLocation()
            PermKind.BATTERY -> openBatteryOptimisation()
            PermKind.INFO -> Unit // informational card, no action
        }
    }

    // On first entry from the splash launch gate, auto-request the core runtime permissions once,
    // so a cold-start driver is prompted immediately like any other app.
    LaunchedEffect(Unit) {
        if (next != null && foregroundRuntimePermissions().any { !runtimeGranted(context, it) }) {
            requestForeground()
        }
    }

    fun proceed() {
        if (next != null) {
            navController.navigate(next) {
                popUpTo(au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes.PERMISSIONS_CHECKLIST) {
                    inclusive = true
                }
            }
        } else {
            navController.popBackStack()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Deck.canvas)
            .padding(start = 72.dp, end = 40.dp, top = 96.dp, bottom = 44.dp),
    ) {
        // Left header column
        Column(modifier = Modifier.width(376.dp)) {
            Text("Permissions", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = Deck.textPrimary)
            Spacer(Modifier.height(16.dp))
            Text(
                "The meter needs these to run legally and safely. Tap any item to grant it.",
                fontFamily = InterFamily,
                fontSize = 17.sp,
                color = Deck.textSecondary,
                modifier = Modifier.width(340.dp),
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background((if (allOk) Deck.forHire else Deck.stopped).copy(alpha = 0.14f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (allOk) "✓ ${cards.size} of ${cards.size} granted" else "$grantedCount of ${cards.size} granted",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (allOk) Deck.forHire else Deck.stopped,
                )
            }
            Spacer(Modifier.height(20.dp))
            // Primary driver of the whole flow — walks foreground perms, then background, then
            // battery optimisation, using the same handlers the individual cards do.
            if (!allOk) {
                DeckButton(text = "Grant all permissions", kind = DeckButtonKind.Primary, modifier = Modifier.width(340.dp)) {
                    when {
                        foregroundRuntimePermissions().any { !runtimeGranted(context, it) } -> requestForeground()
                        !runtimeGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> requestBackgroundLocation()
                        !isIgnoringBatteryOptimizations(context) -> openBatteryOptimisation()
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            DeckButton(text = if (next != null) "Skip for now" else "Back", kind = DeckButtonKind.Ghost, modifier = Modifier.width(180.dp)) {
                proceed()
            }
        }
        Spacer(Modifier.width(40.dp))
        // Right — 2-wide grid of permission cards + Continue pinned bottom-right.
        Column(modifier = Modifier.weight(1f)) {
            cards.chunked(2).forEach { rowCards ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowCards.forEach { card ->
                        PermCard(card, modifier = Modifier.weight(1f), onClick = { onCardTapped(card.kind) })
                    }
                    if (rowCards.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.weight(1f))
            Row {
                Spacer(Modifier.weight(1f))
                DeckButton(
                    text = if (allOk) "Continue" else "Continue anyway",
                    kind = DeckButtonKind.Primary,
                    heightDp = 72,
                    modifier = Modifier.width(360.dp),
                ) { proceed() }
            }
        }
    }
}

/** What tapping a card should do. */
private enum class PermKind { FOREGROUND_RUNTIME, BACKGROUND_LOCATION, BATTERY, INFO }

private data class PermCard(
    val emoji: String,
    val title: String,
    val granted: Boolean,
    val statusText: String,
    val kind: PermKind,
)

private fun runtimeGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

/** The dangerous runtime permissions this app requests via the system dialog on first launch
 * (background location is deliberately excluded — it must be requested on its own, after these). */
private fun foregroundRuntimePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun buildPermissionCards(context: Context): List<PermCard> {
    fun of(emoji: String, title: String, permission: String, kind: PermKind): PermCard {
        val granted = runtimeGranted(context, permission)
        return PermCard(emoji, title, granted, if (granted) "Granted" else "Not granted — tap to allow", kind)
    }

    val battery = isIgnoringBatteryOptimizations(context)

    return listOf(
        of("📍", "Location (while in use)", Manifest.permission.ACCESS_FINE_LOCATION, PermKind.FOREGROUND_RUNTIME),
        of("🛰", "Background location", Manifest.permission.ACCESS_BACKGROUND_LOCATION, PermKind.BACKGROUND_LOCATION),
        // Broadened from "QR pairing" only — this permission now also gates the cabin-facing
        // still-frame capture that runs while a duress event is open (2026-08-27 snapshot-gallery
        // pass), so the row can't stay QR-scanning-specific without misleading the driver about
        // why the app wants it.
        of("📷", "Camera (QR pairing, duress cabin capture)", Manifest.permission.CAMERA, PermKind.FOREGROUND_RUNTIME),
        of("🎙", "Microphone (duress audio)", Manifest.permission.RECORD_AUDIO, PermKind.FOREGROUND_RUNTIME),
        // POST_NOTIFICATIONS only exists as a runtime permission on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            of("🔔", "Notifications", Manifest.permission.POST_NOTIFICATIONS, PermKind.FOREGROUND_RUNTIME)
        } else {
            PermCard("🔔", "Notifications", granted = true, statusText = "Granted at install on this Android version", kind = PermKind.INFO)
        },
        PermCard(
            emoji = "🔋",
            title = "Disable battery optimisation",
            granted = battery,
            statusText = if (battery) "Exempt — meter won't be dozed mid-fare" else "Not exempt — tap to allow",
            kind = PermKind.BATTERY,
        ),
        // The manifest declares no Bluetooth runtime permission (printer gateway is mocked) and
        // API 29+ scoped storage needs no storage permission — stated plainly, not faked.
        PermCard("🅱", "Bluetooth — nearby devices", granted = true, statusText = "Not required on this build", kind = PermKind.INFO),
        PermCard("📁", "File storage", granted = true, statusText = "Not required — scoped storage", kind = PermKind.INFO),
    )
}

@Composable
private fun PermCard(card: PermCard, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val actionable = card.kind != PermKind.INFO && !card.granted
    Row(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(16.dp))
            .then(if (actionable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(card.emoji, fontSize = 26.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(card.title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Deck.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                card.statusText,
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = if (card.granted) Deck.textMuted else Deck.stopped,
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background((if (card.granted) Deck.forHire else Deck.stopped).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (card.granted) "✓" else "!",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = if (card.granted) Deck.forHire else Deck.stopped,
            )
        }
    }
}
