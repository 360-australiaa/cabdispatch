package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.threesixty.cabdispatch.domain.ActiveBlackout
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.TripStatus
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import java.math.BigDecimal
import java.time.Instant

/**
 * Design previews for [MeterDial] at the tablet's real canvas size (A4, 2026-09-08).
 *
 * WHY 1280×800 AND NOT THE DEFAULT. This app renders on a fixed landscape canvas (the SM-T575 the
 * fleet actually runs) via `FixedDesignCanvas`, and the meter pane is the half of it that carries
 * the largest type in the app — a 76sp fare figure, a 56dp END FARE button and a circular dial
 * sized to the *smaller* of its two bounds. A preview at any other aspect ratio silently changes
 * the dial's diameter and tells you nothing about whether the real thing fits. The audit (§6) flags
 * exactly this: `FixedDesignCanvas` scales from width only, so height is where things clip.
 *
 * The states below are the ones this workstream changed behaviour in, and are meant to be compared
 * side by side in the preview pane:
 * - RUNNING — the speed-driven glow at a real road speed, distance readout at full brightness.
 * - PAUSED — amber, static glow, no speed term at all. A paused meter must not look alive.
 * - GPS LOST (waiting) — a blackout that began stationary: "GPS LOST m:ss · WAITING ONLY", the
 *   whole, final story for this case (see [gpsLostPillText]'s own doc).
 * - GPS LOST (moving) — a blackout that began in motion: "GPS LOST m:ss · NO CHARGE YET" — nothing
 *   is being billed while the outcome (corridor vs. nothing) is still unknown.
 * - MAXI — `maxiRateApplied`, the state the dial's total is most often checked against.
 *
 * These are `@Preview`s, not tests: they render, they are not asserted on. The behaviour they
 * illustrate (glow driven by speed rather than by a clock) is covered by the calm-motion rule and
 * by there being no `rememberInfiniteTransition` left in this package to find.
 */

/** A running fare, mid-trip, at a real road speed — the ordinary case. */
private fun previewFareState(
    speedKmh: Double = 48.0,
    gpsLost: Boolean = false,
    blackout: ActiveBlackout? = null,
    maxi: Boolean = false,
    paused: Boolean = false,
): FareState = FareState(
    status = if (paused) TripStatus.STOPPED else TripStatus.HIRED,
    distanceKm = BigDecimal("7.4"),
    currentSpeedKmh = speedKmh,
    movingSeconds = 622,
    waitingSeconds = 143,
    maxiRateApplied = maxi,
    gpsLost = gpsLost,
    blackout = blackout,
)

/** How long ago [previewBlackout]'s fixture blackout "started" — 90s renders as a real, non-zero
 * "1:30" on the pill in the preview pane, rather than "0:00" for a blackout that just began. */
private const val PREVIEW_BLACKOUT_ELAPSED_SECONDS = 90L

/** A real [ActiveBlackout] fixture for the two GPS-LOST previews below — see
 * [PREVIEW_BLACKOUT_ELAPSED_SECONDS]'s own doc for why [startedAtIso] isn't just "now". */
private fun previewBlackout(entryWasMoving: Boolean): ActiveBlackout = ActiveBlackout(
    segmentId = "preview-blackout",
    startedAtIso = Instant.now().minusSeconds(PREVIEW_BLACKOUT_ELAPSED_SECONDS).toString(),
    entryLat = -33.8688,
    entryLng = 151.2093,
    entryWasMoving = entryWasMoving,
)

@Composable
private fun DialPreviewFrame(fareState: FareState, isPaused: Boolean) {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        MeterDial(
            fareState = fareState,
            isPaused = isPaused,
            onEndFare = {},
            onTogglePause = {},
            modifier = Modifier.fillMaxSize().padding(24.dp),
        )
    }
}

@Preview(name = "Meter dial — RUNNING", widthDp = 1280, heightDp = 800, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewMeterDialRunning() {
    DialPreviewFrame(previewFareState(speedKmh = 48.0), isPaused = false)
}

@Preview(name = "Meter dial — PAUSED", widthDp = 1280, heightDp = 800, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewMeterDialPaused() {
    // Speed is deliberately 0 as well as `paused`: a paused meter is normally a stopped cab, and
    // this is the state where the old looping glow was most obviously wrong — it kept breathing.
    DialPreviewFrame(previewFareState(speedKmh = 0.0, paused = true), isPaused = true)
}

@Preview(
    name = "Meter dial — GPS LOST (waiting)",
    widthDp = 1280,
    heightDp = 800,
    backgroundColor = 0xFF0B0B10,
    showBackground = true,
)
@Composable
private fun PreviewMeterDialGpsLostWaiting() {
    // Speed reads 0 because the engine publishes 0 when the fix is stale (FareEngine's
    // `billedSpeedKmh`), which is also why the glow sits still here — the honest rendering of "we
    // do not know how fast this cab is going". entryWasMoving = false: the blackout began
    // stationary, so "waiting only" is the whole, final story for this segment.
    DialPreviewFrame(
        previewFareState(speedKmh = 0.0, gpsLost = true, blackout = previewBlackout(entryWasMoving = false)),
        isPaused = false,
    )
}

@Preview(
    name = "Meter dial — GPS LOST (moving)",
    widthDp = 1280,
    heightDp = 800,
    backgroundColor = 0xFF0B0B10,
    showBackground = true,
)
@Composable
private fun PreviewMeterDialGpsLostMoving() {
    // entryWasMoving = true: the blackout began mid-drive (the tunnel-entry case) — the outcome
    // (a real corridor distance, or nothing) isn't known until reacquisition, so the pill says
    // "no charge yet" rather than committing to either answer early.
    DialPreviewFrame(
        previewFareState(speedKmh = 0.0, gpsLost = true, blackout = previewBlackout(entryWasMoving = true)),
        isPaused = false,
    )
}

@Preview(name = "Meter dial — MAXI", widthDp = 1280, heightDp = 800, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewMeterDialMaxi() {
    DialPreviewFrame(previewFareState(speedKmh = 62.0, maxi = true), isPaused = false)
}
