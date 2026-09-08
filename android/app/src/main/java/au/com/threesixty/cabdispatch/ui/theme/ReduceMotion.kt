package au.com.threesixty.cabdispatch.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * A8 accessibility pass (2026-09-08). Whether the OS-level "Remove animations" setting
 * (Settings > Accessibility > Remove animations, backed by `Settings.Global.ANIMATOR_DURATION_SCALE`)
 * is on. This is Android's standard system-wide reduce-motion signal — the same toggle a screen
 * reader user or anyone motion-sensitive already relies on across the rest of the OS.
 *
 * This does NOT touch this app's own calm-motion doctrine (nothing loops/breathes/orbits while
 * the vehicle is parked — see [Hud.kt]'s `GlowingSpeedometer` doc and [SosControl]'s doc for the
 * incident that established it). That doctrine already means most "ambient" motion here is gone;
 * what remains ([PulsingDot] signalling a genuinely live state, the engagement-refresh spinner,
 * the duress overlay's pulse) is real information, not decoration, so the calm-motion rule alone
 * doesn't touch it. Respecting this OS setting on top of that is a pure ADDITION of a further
 * accessibility floor for the subset of drivers who have it on, never a relaxation of calm-motion
 * for anyone who doesn't — it never introduces a new loop, only stills existing ones on request.
 *
 * Re-read on every resume (not just once at process start) since a driver can flip the setting
 * from the notification-shade quick toggle without leaving the app.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduceMotion by remember {
        mutableStateOf(readAnimatorDurationScale(context) == 0f)
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduceMotion = readAnimatorDurationScale(context) == 0f
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reduceMotion
}

private fun readAnimatorDurationScale(context: android.content.Context): Float =
    try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Settings.SettingNotFoundException) {
        1f
    }
