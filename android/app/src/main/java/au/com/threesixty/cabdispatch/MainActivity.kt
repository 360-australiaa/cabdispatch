package au.com.threesixty.cabdispatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost
import au.com.threesixty.cabdispatch.ui.theme.CabDispatchTheme

/**
 * Single-activity Compose host. TODO(kiosk agent): wire the kiosk-mode
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
    CabDispatchTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            FixedDesignCanvas {
                CabDispatchNavHost()
            }
        }
    }
}
