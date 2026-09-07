package au.com.threesixty.cabdispatch

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.DevicePairingStatus
import au.com.threesixty.cabdispatch.domain.KioskLockController
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost
import au.com.threesixty.cabdispatch.ui.overlays.DeviceUnpairedBanner
import au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner
import au.com.threesixty.cabdispatch.ui.overlays.KioskLockedBanner
import au.com.threesixty.cabdispatch.ui.overlays.OfflineBanner
import au.com.threesixty.cabdispatch.ui.theme.CabDispatchTheme

/**
 * Single-activity Compose host, and the composition root for the two app-wide fleet-command
 * effects driven off [AppContainer.deviceCommandHeartbeat]'s [au.com.threesixty.cabdispatch.domain.DeviceCommandState]:
 *
 * 1. **Kiosk lock enforcement** — [CabDispatchScreenRoot] collects the heartbeat's `state` and, on
 *    every change to `kioskLocked`, hands this Activity to [KioskLockController.applyKioskLock].
 *    That is plain Android **screen pinning** (`Activity.startLockTask()`/`stopLockTask()`) — this
 *    app holds no device-owner provisioning and the manifest sets no `android:lockTaskMode`, so
 *    there is no DPC/Knox allowlist backing it, no `DevicePolicyManager` call, and nothing here
 *    can land the OS in `LOCK_TASK_MODE_LOCKED`. See [KioskLockController]'s class doc for the
 *    full pinning-vs-device-owner write-up and its decision table, in particular the rule that a
 *    `LOCK_TASK_MODE_LOCKED` state (a DPC/Knox lock this app did not start) is never released from
 *    here — only a pin this app itself put in `PINNED` mode ever is.
 * 2. **The fleet-command/connectivity banners** — [au.com.threesixty.cabdispatch.ui.overlays.KioskLockedBanner],
 *    [au.com.threesixty.cabdispatch.ui.overlays.DeviceUnpairedBanner],
 *    [au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner], and
 *    [au.com.threesixty.cabdispatch.ui.overlays.OfflineBanner] are composed once here, `Box`-stacked
 *    over [CabDispatchNavHost], so they follow the driver across every screen instead of living
 *    inside whichever screen happens to be open — see that file's own doc. Unlike the others,
 *    [OfflineBanner] is unconditional (not gated behind an `if` here) — it reads live connectivity
 *    state itself and renders nothing when online with nothing queued, see its own doc.
 *    [DeviceUnpairedBanner] (2026-09-06) is gated on [DevicePairingStatus.isUnpaired] reading
 *    `commandState.deviceId` — the exact same field [DeviceCommandHeartbeat] itself gates its whole
 *    poll loop on, so this banner is on precisely when that loop is off. See
 *    [au.com.threesixty.cabdispatch.domain.DevicePairingStatus]'s class doc for the onboarding
 *    defect this closes.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CabDispatchScreenRoot()
        }
    }
}

/** The Command Deck design's fixed logical canvas (Figma frames are all 1280×800). */
private const val DESIGN_W_DP = 1280f
private const val DESIGN_H_DP = 800f

/**
 * Density override so the whole app renders on a fixed 1280×800dp logical canvas regardless of
 * the physical panel (Command Deck v2 port, 2026-08-27). The pilot tablet (SM-T575) is
 * 1920×1200 @ 320dpi = 960×600dp — every Figma-exact dimension in the v2 screens (92dp rail,
 * 400dp drive panel, 140×78 keys…) is authored against 1280×800, so instead of re-deriving every
 * measurement adaptively, the root [Density] is scaled so 1280dp of layout exactly spans the
 * panel's width (uniform scale, aspect preserved: both axes are 1.6× here, and any 16:10 panel
 * maps cleanly). Fonts scale identically since sp resolves through the same density. This is the
 * standard fixed-canvas approach for single-purpose kiosk hardware; dialogs/popups hosted in
 * separate windows keep system density, which is acceptable for their content.
 */
@Composable
private fun FixedDesignCanvas(content: @Composable () -> Unit) {
    // Inset to the SAFE AREA before measuring, not after.
    //
    // Reported from a second tablet, 2026-09-07: "bottom bar having apps pinned, so when I open
    // this apk the buttons crop or hide". The canvas below is sized from the constraints it is
    // given, so handing it the full window means it lays 800dp of design out over a window whose
    // bottom strip is physically covered by the system navigation bar -- and every screen's bottom
    // row (CLOSE TRIP, Accept, the keypad) lands underneath it. The pilot SM-T575 never showed
    // this because kiosk screen pinning hides its bars, so the insets there are zero and this
    // padding is a no-op; a tablet with visible three-button navigation is a different shape and
    // the app was simply ignoring it.
    //
    // `safeDrawing` rather than `systemBars`: it also covers a display cutout, which some tablets
    // have and which would clip the status strip in the same silent way. Applied to the OUTER box
    // so `constraints` (and therefore the design scale computed from them) describe the usable
    // area, which is the whole point -- padding the content instead would scale to the full width
    // and then push the bottom off-screen.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val systemDensity = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        // Width-driven scale: horizontal Figma dimensions stay exact (1280dp spans the panel);
        // the status bar's ~32dp bite out of the 800dp height is absorbed by each screen's
        // weighted spacers (every v2 screen pins its bottom CTAs with Spacer(weight)).
        val scale = widthPx / DESIGN_W_DP
        CompositionLocalProvider(
            LocalDensity provides Density(density = scale, fontScale = systemDensity.fontScale),
        ) {
            content()
        }
    }
}

@Composable
private fun CabDispatchScreenRoot() {
    // Same `LocalContext.current as? <activity type>` idiom TermsDisclaimerScreen.kt already uses
    // to reach the hosting Activity from inside a Composable — MainActivity is the only Activity
    // this single-activity app ever hosts, so this is never null in practice; the `?.` below is a
    // no-op guard for previews/tests that render this Composable outside an Activity context.
    val activity = LocalContext.current as? Activity
    val commandState by AppContainer.deviceCommandHeartbeat.state.collectAsState()

    // Re-evaluate the pin every time the server's last-known kioskLocked flag changes (poll
    // landing, seed on cold start, or a factory-reset reset back to the DeviceCommandState()
    // default) — see KioskLockController's class doc for the decision this makes and why it can
    // never release a LOCK_TASK_MODE_LOCKED state.
    LaunchedEffect(activity, commandState.kioskLocked) {
        activity?.let { KioskLockController.applyKioskLock(it, commandState.kioskLocked) }
    }

    CabDispatchTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            FixedDesignCanvas {
                Box(modifier = Modifier.fillMaxSize()) {
                    CabDispatchNavHost()
                    // All non-hit-testable full-size overlays (see FleetCommandOverlays.kt's own
                    // doc) — stacked above the nav host so they follow the driver across every
                    // screen rather than being wired into each screen individually.
                    if (commandState.kioskLocked) {
                        KioskLockedBanner()
                    }
                    if (commandState.forceUpdatePending) {
                        ForceUpdatePendingBanner()
                    }
                    if (DevicePairingStatus.isUnpaired(commandState.deviceId)) {
                        DeviceUnpairedBanner()
                    }
                    // Unconditional: OfflineBanner reads live connectivity + outbox state itself
                    // and renders nothing when there's nothing honest to say — see its own doc.
                    OfflineBanner()
                }
            }
        }
    }
}
