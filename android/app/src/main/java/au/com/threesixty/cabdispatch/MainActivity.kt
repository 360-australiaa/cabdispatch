package au.com.threesixty.cabdispatch

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.DevicePairingStatus
import au.com.threesixty.cabdispatch.domain.KioskLockController
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost
import au.com.threesixty.cabdispatch.ui.overlays.CaptainChromeMetrics
import au.com.threesixty.cabdispatch.ui.overlays.DeviceUnpairedBanner
import au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner
import au.com.threesixty.cabdispatch.ui.overlays.KioskLockedBanner
import au.com.threesixty.cabdispatch.ui.overlays.OfflineBanner
import au.com.threesixty.cabdispatch.ui.theme.CabDispatchTheme
import kotlinx.coroutines.delay

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
 *    here — only a pin this app itself put in `PINNED` mode ever is. Whether the OS actually
 *    confirmed the result — `kioskPinConfirmed` — is read from [KioskLockController.liveLockTaskMode],
 *    fed by this Activity's own [onLockTaskModeChanged] override below, NOT from [applyKioskLock]'s
 *    return value: see [KioskLockController]'s "Why liveLockTaskMode exists" doc for why a real
 *    Samsung tablet can leave that confirmation pending for however long a human takes to dismiss
 *    One UI's own pinning dialog — an unbounded wait no poll-with-timeout in [CabDispatchScreenRoot]
 *    could ever cover. Threaded into [KioskLockedBanner] so the driver can tell "the depot asked
 *    for this" apart from "and the OS actually granted it".
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
        // Required for WindowInsets.ime to be REPORTED to Compose rather than silently swallowed
        // by the framework resizing the window for us. It is what makes the manifest's
        // `adjustNothing` usable: the window keeps its full height (so FixedDesignCanvas's design
        // scale never moves) and the keyboard arrives as an inset the content pads for itself.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            CabDispatchScreenRoot()
        }
    }

    /** The app (re)entering the foreground — see [AppContainer.idleLogoutSupervisor]'s own doc
     * for why this, not just [dispatchTouchEvent] below, feeds its clock: time spent with the
     * screen off or the app backgrounded ("we close the tablet") must count against the same
     * idle budget as time spent untouched in the foreground. Fires on the very first launch too,
     * which is exactly right — a fresh sign-in should not be judged idle from process-start time. */
    override fun onStart() {
        super.onStart()
        AppContainer.idleLogoutSupervisor.recordForegroundResume()
    }

    /** Every touch anywhere in the app, including a dialog or the on-screen keypad hosted outside
     * this Activity's own Compose tree — see [AppContainer.idleLogoutSupervisor]'s own doc for why
     * this lives here rather than as a `pointerInput` on one screen's root. Never consumes the
     * event: this is purely an observer, [dispatchTouchEvent]'s real job is unaffected. */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        AppContainer.idleLogoutSupervisor.recordInteraction()
        return super.dispatchTouchEvent(ev)
    }

    /** Fires the instant the OS actually changes lock-task mode — see [KioskLockController]'s
     * "Why liveLockTaskMode exists" doc for why [CabDispatchScreenRoot]'s kiosk-lock confirmation
     * is driven off this rather than a poll with a timeout: on a real Samsung tablet, the wait
     * between [KioskLockController.applyKioskLock] calling startLockTask() and this actually
     * firing is however long a human takes to dismiss One UI's own pinning dialog, not a fixed
     * delay. There is no plain `Activity.onLockTaskModeChanged` override in the public SDK for a
     * non-device-owner app (a first attempt at this fix tried exactly that and did not compile) --
     * `ACTION_LOCK_TASK_ENTERING`/`ACTION_LOCK_TASK_EXITING` are the real, documented signal. */
    private val lockTaskModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            KioskLockController.refreshLiveLockTaskMode(this@MainActivity)
        }
    }

    /** Registered/unregistered around the foreground window, same lifecycle shape as any other
     * receiver this Activity would only care about while visible — there is nothing for a
     * backgrounded tablet to confirm on-screen anyway. */
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            // Intent.ACTION_LOCK_TASK_ENTERING/EXITING are real, protected system broadcasts the
            // OS actually sends (confirmed live), but the constants themselves are @SystemApi --
            // hidden from the public android.jar this app compiles against, so the compiler
            // cannot resolve the symbol even though the broadcast fires at runtime. Their string
            // values are stable AOSP platform contract, not this app's own invention.
            addAction("android.app.action.LOCK_TASK_ENTERING")
            addAction("android.app.action.LOCK_TASK_EXITING")
        }
        ContextCompat.registerReceiver(this, lockTaskModeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // Cold start / returning to the foreground can both miss the window where the broadcast
        // fired (e.g. it fired while backgrounded, before this receiver was registered) -- one
        // direct read here catches that, exactly as applyKioskLock's own seed read does.
        KioskLockController.refreshLiveLockTaskMode(this)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(lockTaskModeReceiver) }
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
            // EXCLUDE the IME from the measurement the design scale is computed from.
            //
            // The window is `adjustResize` (see AndroidManifest) so the soft keyboard shrinks it
            // rather than covering the Pair button on a gate a driver cannot leave. But the scale
            // below is derived from the height this box is given, so with the keyboard up that
            // height collapsed and the scale went with it: on the pilot tablet
            // `minOf(1920/1280, ~500/800)` = 0.62, and the ENTIRE interface -- text, dial, rails,
            // every screen, not just the field being typed into -- redrew at 62%. Reported as
            // "when open keyboard, whole screen alignment not good" (2026-09-08), and it was:
            // the design canvas itself was being rescaled by a keyboard.
            //
            // The canvas now always measures the window as if no keyboard were present, so the
            // scale is a property of the panel and nothing else. The keyboard is handled where it
            // belongs -- as padding on the content below -- which moves things without resizing
            // them.
            //
            // `exclude(ime)` alone did NOT achieve this and was the first attempt: under
            // adjustResize the framework shrinks the window before Compose measures anything, so
            // the ime inset reads zero and there is nothing left to exclude. The manifest is
            // adjustNothing now, which keeps the window at full height and reports the keyboard as
            // a real inset. The exclusion stays because it is what makes the intent explicit and
            // holds if the soft-input mode is ever changed back.
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime)),
    ) {
        val systemDensity = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // LETTERBOX, don't clip (A3, 2026-09-08).
        //
        // This was `widthPx / DESIGN_W_DP` - width only. On the pilot SM-T575 (1920x1200, 16:10,
        // kiosk-pinned so insets are zero) that is exactly right: both axes are 1.6x and 1280x800
        // maps perfectly. On anything that is NOT 16:10 it silently lies. A 16:9 panel (1920x1080)
        // scaled from width alone yields a canvas 1280 x **720**dp: every screen authored against
        // 800dp of height loses 80dp off the bottom with no warning and no visual cue. The
        // dashboard's content pane dropped to ~381dp and the meter dial - a hardcoded 414dp at the
        // time - was simply cut off. A visible nav bar eating the safe area does the same thing.
        //
        // Taking the MINIMUM of the two scales means the design canvas always fits inside the real
        // panel: on a shorter screen the layout gets slightly smaller and leaves unused margin at
        // the sides (a letterbox), which is visible, harmless and honest, rather than confidently
        // drawing content into pixels that do not exist.
        //
        // On the 16:10 pilot tablet the two scales are identical, so this changes nothing there -
        // it only ever engages on a panel the old formula was already getting wrong.
        val scale = minOf(widthPx / DESIGN_W_DP, heightPx / DESIGN_H_DP)
        CompositionLocalProvider(
            LocalDensity provides Density(density = scale, fontScale = systemDensity.fontScale),
        ) {
            // The keyboard, applied AFTER the scale is fixed. Inside the canvas, so this padding is
            // in design dp like everything else: a screen with a text field gets shorter and can
            // scroll to its field, while type size, stroke widths and every other screen's layout
            // stay exactly as authored.
            Box(modifier = Modifier.fillMaxSize().imePadding()) {
                content()
            }
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

    // Issues the actual startLockTask()/stopLockTask() call (via decideAction's own gating, a
    // no-op when the OS is already in the desired mode) and re-seeds liveLockTaskMode below with a
    // fresh direct read. Loops forever rather than running once per commandState change: a
    // DeviceCommandState data class poll that comes back byte-for-byte identical to the last one
    // (the common case: kioskLocked sitting at true across many consecutive polls) is conflated
    // by MutableStateFlow and never reaches collectAsState() as a new value at all, so keying this
    // on commandState (tried, and reverted) reruns no more often than keying on just .kioskLocked
    // did -- neither actually re-checks the OS periodically. A real timer, independent of whether
    // the server's answer ever changes, is the only thing that can. Backstops
    // MainActivity's ACTION_LOCK_TASK_ENTERING/EXITING receiver for whatever reason it does not
    // fire on a given OEM/OS combo (unconfirmed why, live-tested one that did not): polls quickly
    // while still unconfirmed (a human dismissing One UI's own pinning dialog is the expected
    // unbounded wait), then coarsely once confirmed, matching DeviceCommandHeartbeat's own poll
    // cadence -- there is nothing left to catch quickly once the OS agrees with the depot.
    LaunchedEffect(activity) {
        while (true) {
            val confirmed = activity?.let { KioskLockController.applyKioskLock(it, commandState.kioskLocked) } ?: false
            delay(if (confirmed) 60_000L else 2_000L)
        }
    }

    // Whether the OS's live lock-task mode actually matches commandState.kioskLocked — fed by
    // MainActivity's lock-task broadcast receiver AND the poll-interval backstop above, never a
    // fixed-delay guess. See KioskLockController's "Why liveLockTaskMode exists" doc: on a real
    // Samsung tablet the OS does not actually enter PINNED until a human dismisses One UI's own
    // confirmation dialog, an unbounded wait no fixed retry-with-delay loop can ever cover (this
    // file's two earlier, wrong attempts at exactly that). This being a plain derived value rather
    // than remember/mutableStateOf means KioskLockedBanner below is always exactly as fresh as the
    // last real update, cold start included (applyKioskLock seeds it with one direct read before
    // either update path has had the chance to fire).
    val liveLockTaskMode by KioskLockController.liveLockTaskMode.collectAsState()
    val kioskPinConfirmed = KioskLockController.isPinConfirmed(liveLockTaskMode, commandState.kioskLocked)

    CabDispatchTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            FixedDesignCanvas {
                Box(modifier = Modifier.fillMaxSize()) {
                    CabDispatchNavHost()
                    // All non-hit-testable full-size overlays (see FleetCommandOverlays.kt's own
                    // doc) — stacked above the nav host so they follow the driver across every
                    // screen rather than being wired into each screen individually.
                    // Every app-level banner stands down while a full-screen gate owns the
                    // display -- see CaptainChromeMetrics.fullScreenGateVisible for why.
                    val gateVisible = CaptainChromeMetrics.fullScreenGateVisible
                    if (!gateVisible && commandState.forceUpdatePending) {
                        ForceUpdatePendingBanner()
                    }
                    // The two latching status chips, stacked in ONE column rather than each
                    // positioning itself.
                    //
                    // Both used to align themselves BottomStart independently, which meant they
                    // drew on top of each other whenever both were true (an unregistered tablet
                    // that a fleet admin has also locked is a perfectly ordinary state), and — more
                    // seriously — that corner is where the meter dial's live km/h readout sits. A
                    // chip about the tablet's pairing status covering the speed the passenger is
                    // being charged by is the wrong trade every time, so they moved up under the
                    // header, where they overlay the empty edge of the dial instead. Confirmed on
                    // the tablet, 2026-09-08. TopStart, not TopCenter, keeps them clear of
                    // ForceUpdatePendingBanner's centred pill above.
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = CaptainChromeMetrics.topOverlayInset),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (!gateVisible && commandState.kioskLocked) {
                            KioskLockedBanner(pinConfirmed = kioskPinConfirmed)
                        }
                        if (!gateVisible &&
                            DevicePairingStatus.isUnpaired(commandState.deviceId, commandState.deviceRejected)
                        ) {
                            DeviceUnpairedBanner()
                        }
                    }
                    // Otherwise unconditional: OfflineBanner reads live connectivity + outbox
                    // state itself and renders nothing when there's nothing honest to say — see
                    // its own doc.
                    if (!gateVisible) {
                        OfflineBanner()
                    }
                }
            }
        }
    }
}
