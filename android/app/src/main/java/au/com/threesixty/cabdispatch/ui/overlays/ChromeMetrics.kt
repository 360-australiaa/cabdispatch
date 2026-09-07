package au.com.threesixty.cabdispatch.ui.overlays

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How tall the app's header actually is, measured, for the overlays that have to clear it.
 *
 * [au.com.threesixty.cabdispatch.MainActivity] hosts the fleet-command banners as siblings of the
 * nav host, not as children of any screen — that is deliberate (see FleetCommandOverlays.kt's own
 * doc: they follow the driver everywhere instead of being wired into each screen). The cost is that
 * they cannot see the chrome they sit on top of, so their clearance was a constant: `STATUS_STRIP_H
 * + 10`, i.e. 54dp, sized for `DeckChrome.kt`'s 44dp in-shift status strip.
 *
 * That strip is not what renders any more. The live chrome is `CaptainHeader` (driver card, status
 * pill, GPS/WI-FI/PRINTER/battery, the 72dp SOS control), which is around three times taller — so
 * on a real tablet the UPDATE PENDING banner sat squarely ON the header, hiding the driver's name
 * and the HIRED/AVAILABLE status pill for as long as the flag stayed set. Confirmed on the tablet,
 * 2026-09-08.
 *
 * A second constant would just be the same bug with a bigger number in it, one redesign later.
 * `CaptainHeader` reports its own measured height here instead ([reportsChromeHeader]), and the
 * overlays clear whatever it actually is. Deliberately a plain process-wide holder rather than a
 * CompositionLocal: the banners are the header's SIBLINGS, so no local provided around the header
 * could ever reach them.
 *
 * The default is only what gets used before a header has ever composed (login, shift start, and
 * settings carry no header at all) — a small non-zero clearance so a banner on those screens still
 * sits below the top edge rather than flush against it.
 */
object CaptainChromeMetrics {

    /** Clearance used until a real header measures itself; also the floor below it never goes. */
    private val FALLBACK: Dp = 54.dp

    /**
     * Height of the most recently composed [au.com.threesixty.cabdispatch.ui.screens.dashboard]
     * header, in dp. Read by the top-aligned overlays; written only by [reportsChromeHeader].
     */
    var headerHeight: Dp by mutableStateOf(FALLBACK)
        private set

    /** Where a top-aligned overlay should start so it clears the header entirely. */
    val topOverlayInset: Dp get() = headerHeight + 10.dp

    /**
     * True while an exclusive full-screen surface owns the display and the app-level banners must
     * stand down.
     *
     * Set only by the device-readiness gate
     * ([au.com.threesixty.cabdispatch.ui.screens.readiness.DeviceReadinessScreen]). That screen has
     * no header, so the banners' measured clearance -- the last real header they saw -- lands them
     * on top of its own headline and its first checklist row. Worse, they are redundant there:
     * FLEET LOCKED, TABLET NOT REGISTERED and UPDATE PENDING all announce conditions the gate is
     * already dedicated to, in fewer words and with no fix attached, over the version of the same
     * message that does have one.
     *
     * Deliberately narrow. This is not a general "hide the chrome" switch -- the banners exist
     * because a driver must see these states everywhere else, and the one place they may be
     * suppressed is a screen whose entire job is to say the same thing better.
     */
    var fullScreenGateVisible: Boolean by mutableStateOf(false)
        private set

    /** Called by the gate as it enters and leaves composition. */
    internal fun setFullScreenGateVisible(visible: Boolean) {
        fullScreenGateVisible = visible
    }

    /**
     * Publishes this element's measured height as the app header's.
     *
     * Screens with no header never call this, so the last real measurement persists — which is the
     * right behaviour: a screen without a header has nothing at the top to collide with, and
     * keeping the previous value avoids a visible jump as the banner re-lays out on every
     * navigation. It never shrinks below [FALLBACK] so a mid-composition zero measurement cannot
     * slam an overlay against the top edge.
     */
    internal fun report(height: Dp) {
        headerHeight = maxOf(height, FALLBACK)
    }
}

/** Applied to the app header so [CaptainChromeMetrics] knows how much room the overlays must clear. */
fun Modifier.reportsChromeHeader(): Modifier = composed {
    val density = LocalDensity.current
    onSizeChanged { size ->
        CaptainChromeMetrics.report(with(density) { size.height.toDp() })
    }
}
