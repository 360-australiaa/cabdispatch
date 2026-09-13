package au.com.threesixty.cabdispatch.ui.theme

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * W8 (release readiness, 2026-09-12/13 optimisation plan). Applies `FLAG_SECURE` to this window
 * for exactly as long as the calling composable stays in composition, then clears it again — a
 * screen-recording/screenshot block that is on ONLY while a genuinely sensitive screen is showing,
 * never a whole-`Activity`-permanent flag that would blank the rest of the app out of the OS's own
 * Recents thumbnail too.
 *
 * Call once, near the top of a composable's body, on each of the specific screens that show
 * something a screen recording could leak: a card payment flow (Close & Pay), driver PII
 * (Profile), or the duress-arming confirmation ([au.com.threesixty.cabdispatch.ui.overlays
 * .DuressTriggeredOverlay]). The live meter dial itself is deliberately NOT one of these — a
 * screen recording of a running fare is not the secrecy concern a card number or a panic-button
 * confirmation is, and this app is single-`Activity` (`MainActivity`, Compose Navigation for every
 * screen), so a permanent flag would blanket every destination, not just these three.
 *
 * [au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner] (the small, deliberately discreet
 * ongoing stealth indicator that keeps showing over the meter for the rest of a duress event) does
 * NOT call this: it overlays the ordinary Hired/dashboard screens for a potentially long remainder
 * of the trip, and those screens' own content is exactly the "meter dial, not a secrecy concern"
 * case above — only the brief arming/confirmation panel itself is treated as sensitive here.
 *
 * Resolves the window via `LocalView.current`'s hosting [Activity] rather than a `Context` cast at
 * each call site, matching this codebase's existing `(context as? Activity)` convention
 * ([au.com.threesixty.cabdispatch.ui.screens.readiness.DeviceReadinessScreen]) while staying a
 * zero-argument drop-in. Silently does nothing if the view is not attached to an `Activity` window
 * (e.g. a Compose `@Preview`) — a preview never needs screen-recording protection, and this must
 * never crash one.
 *
 * In this app's actual navigation, [CloseAndPayScreen][au.com.threesixty.cabdispatch.ui.screens
 * .closepay.CloseAndPayScreen], [ProfileScreen][au.com.threesixty.cabdispatch.ui.screens.profile
 * .ProfileScreen] and the duress overlay's host screens are mutually exclusive destinations/layers
 * in the one `NavHost`, so only one caller of this function is ever composed at a time in practice
 * — each independently sets the flag on entry and clears it on exit with no risk of one caller's
 * exit turning the flag off while another is still relying on it being on.
 */
@Composable
fun SecureScreen() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
