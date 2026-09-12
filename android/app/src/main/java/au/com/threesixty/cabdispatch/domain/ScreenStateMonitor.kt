package au.com.threesixty.cabdispatch.domain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * Shared "is the screen currently on" signal — the 2026-09-12 optimisation plan's W4 needs this
 * for two independent reasons: [LivePositionHeartbeat]'s "screen off and stationary" cadence tier
 * (task 1) and [DeviceCommandHeartbeat]'s "skip the POST when nothing changed and the screen is
 * off" backoff (task 4). Extracted as one process-lifetime singleton rather than each of those two
 * classes registering its own [BroadcastReceiver], because there is no
 * [kotlinx.coroutines.flow.StateFlow]/callback API for screen state on any supported API level —
 * a `BroadcastReceiver` is genuinely the only mechanism, and two independent registrations for the
 * identical two actions would be pure duplication for no benefit (both would always agree, by
 * construction).
 *
 * A plain object with a snapshot read ([isScreenOn]) rather than an observable
 * [kotlinx.coroutines.flow.StateFlow]: both consumers already poll their own state on a fixed
 * interval (see [LivePositionHeartbeat.publishLoop]/[DeviceCommandHeartbeat.pollLoop]'s own docs
 * for why a poll, not a reactive combine, is the right shape for them), so a `StateFlow` here would
 * add an unused subscription API on top of a value that is, in practice, only ever read
 * synchronously.
 */
object ScreenStateMonitor {

    @Volatile
    private var screenOn: Boolean = true

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    /**
     * Registers the receiver once, for the process lifetime — call from
     * [au.com.threesixty.cabdispatch.data.AppContainer.init]. Idempotent (a second call is a
     * no-op) so it is safe to call unconditionally alongside every other process-lifetime
     * singleton `start()`/registration in that method. Seeds [screenOn] from
     * [PowerManager.isInteractive] so a consumer that reads [isScreenOn] before the very first
     * `SCREEN_ON`/`SCREEN_OFF` broadcast still gets a real answer, not the bare default.
     */
    fun start(appContext: Context) {
        if (registered) return
        registered = true
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = powerManager?.isInteractive ?: true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Registration can theoretically fail (a `Context` that refuses dynamic receivers) — never
        // let that take the rest of process init down; every consumer degrades to the seeded
        // snapshot above, never a crash, matching this app's "best-effort, silent-on-failure"
        // convention for background plumbing.
        runCatching { appContext.registerReceiver(receiver, filter) }
    }

    /** Current screen state — `true` until proven otherwise, so a consumer that (in a test, or in
     * the brief window before [start] is called) never sees a real broadcast defaults to the
     * SAFER assumption for both of this object's consumers: [LivePositionHeartbeat] would rather
     * over-publish than under-publish, and [DeviceCommandHeartbeat] would rather poll than
     * silently go quiet on a device it has no real signal for. */
    fun isScreenOn(): Boolean = screenOn
}
