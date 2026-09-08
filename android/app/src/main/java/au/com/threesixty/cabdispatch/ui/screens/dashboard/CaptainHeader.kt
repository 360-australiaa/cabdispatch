package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.GpsQuality
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.ui.overlays.reportsChromeHeader
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.DriverAvatar
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PulsingDot
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.SosControl

/**
 * The home screen's top chrome: the driver avatar/name/rego block, the VERIFIED and
 * status pills, and the GPS / network / printer / battery strip, plus the status-dot
 * primitive and the network/GPS tone helpers only this header uses.
 *
 * Extracted from DeckHomeScreen.kt in Phase 0 (P0.4) — a mechanical move, same package,
 * same declarations, no behaviour change. See DeckHomeScreen.kt for the shell that hosts
 * this and for how the header publishes its own height via reportsChromeHeader().
 */

// ============================================================================================
// Header (mockup: avatar/wordmark, driver identity, VERIFIED, status pill, system strip, SOS) —
// rebuilt on the HUD kit (2026-09-04): GlassCard pills with neon halos, PulsingDot, the same real
// data sources and callbacks as before, SosControl's press-and-hold untouched.
// ============================================================================================

/**
 * The header status pill's four REAL states, derived in [headerStatus] from three signals this
 * screen already holds: [DeckHomeScreen]'s Room `observeActiveTrip()` read (an OPEN `TripEntity` —
 * the same signal that gates the rail's METER item), [WheelDashboardUiState.isAvailable], and
 * whether the session carries an open `shiftId`. ON_BREAK vs OFF_DUTY split out of a single
 * OFF_DUTY state 2026-09-06 (direct product instruction) — see [ON_BREAK]'s own doc for why a
 * driver on an open shift with `isAvailable == false` is honestly "on break", not "off duty".
 *
 * The mockup also draws ON TRIP / PAUSED / COMPLETED pills. None of those is honestly reachable
 * from this file, so none is faked: PAUSED is `FareState.status == STOPPED` on the live
 * [au.com.threesixty.cabdispatch.domain.FareEngine], which is instantiated privately per nav
 * entry inside `HiredViewModel` (see that engine's own "hoist to AppContainer" TODO) — the
 * dashboard cannot observe it; COMPLETED has no persisted signal a dashboard can watch (a closed
 * trip simply stops being the active one, and the pill falls back to AVAILABLE/ON_BREAK); ON TRIP
 * is indistinguishable from HIRED with the data here, so the one open-fare state is shown as the
 * mockup's HIRED / "Trip in progress".
 */
internal enum class HeaderStatus(val title: String, val sub: String, val tone: HudTone) {
    HIRED("HIRED", "Trip in progress", HudTone.Success),
    AVAILABLE("AVAILABLE", "Ready to receive jobs", HudTone.Success),
    // Split from the old single OFF_DUTY state, 2026-09-06 (direct product instruction): a driver
    // with an open shift and isAvailable == false is ON BREAK — their shift clock is still
    // running (ShiftStatsBar keeps ticking off the same DriverSession.shiftStartAt regardless of
    // this flag), they are simply not receiving dispatch offers right now. OFF_DUTY genuinely
    // means "no open shift at all", which this screen cannot actually reach today (starting a
    // shift is what gets a driver here in the first place — see WheelDashboardViewModel.init's
    // auto-available block, which runs the instant a shift is seen) but is kept, honestly, rather
    // than deleted, as the fallback for a session with no shiftId at all.
    ON_BREAK("ON BREAK", "Tap to resume", HudTone.Warning),
    OFF_DUTY("OFF DUTY", "Start a shift to go available", HudTone.Neutral),
}

private fun headerStatus(isAvailable: Boolean, hasActiveTrip: Boolean, hasOpenShift: Boolean): HeaderStatus = when {
    hasActiveTrip -> HeaderStatus.HIRED
    isAvailable -> HeaderStatus.AVAILABLE
    hasOpenShift -> HeaderStatus.ON_BREAK
    else -> HeaderStatus.OFF_DUTY
}

@Composable
internal fun CaptainHeader(
    state: WheelDashboardUiState,
    verified: Boolean?,
    hasActiveTrip: Boolean,
    onShowDriverId: () -> Unit,
    onOpenProfile: () -> Unit,
    onToggleAvailability: () -> Unit,
    onSos: () -> Unit,
) {
    val status = headerStatus(
        isAvailable = state.isAvailable,
        hasActiveTrip = hasActiveTrip,
        hasOpenShift = state.session?.shiftId != null,
    )
    val statusColor = status.tone.color()
    val statusNeutral = status.tone == HudTone.Neutral
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Publishes this header's real height to the app-level overlays, which are siblings of
            // the nav host and would otherwise be sitting on top of it — see CaptainChromeMetrics.
            .reportsChromeHeader()
            .background(Brush.verticalGradient(listOf(CaptainPalette.glowPurpleSoft, Color.Transparent)))
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar in a soft accent halo. Tap opens the large Driver ID card (passenger face-
        // matching); the Profile route stays reachable via that card and the name tap below.
        Box(modifier = Modifier.neonGlow(CaptainPalette.hudAccent, 44.dp, strength = 0.7f)) {
            DriverAvatar(driverId = state.session?.driverId, driverName = state.session?.driverName, onClick = onShowDriverId, sizeDp = 88)
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text("CAPTAIN", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp, color = CaptainPalette.textPrimary)
            Text("TAXIS", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 3.sp, color = CaptainPalette.hudSweepMid)
        }
        Column(
            modifier = Modifier.padding(start = 26.dp).clickable(onClick = onOpenProfile),
        ) {
            Text(
                state.session?.driverName ?: "No driver",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                color = CaptainPalette.textPrimary,
            )
            Text(
                // The mockup shows "rego · make/model" (e.g. "CAP-5517 · Toyota Camry Hybrid") —
                // no vehicle make/model field exists anywhere in this app's data model (VehicleDto
                // is id+rego only, confirmed against ApiService.kt) or the backend it talks to, so
                // this shows the real rego alone rather than fabricating a plausible-looking
                // "Toyota Camry Hybrid". Flagged as a backend-requirements candidate in
                // DASHBOARD_REDESIGN_2026.md. Driver-id is still real and shown elsewhere
                // (DriverIdCard's "DRIVER # …") rather than crowding this line.
                state.session?.vehicleId ?: "—",
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = CaptainPalette.textSecondary,
            )
        }
        // "VERIFIED" — a real backend field, per the backend's own contract (2026-08-29): maps
        // to UserDto.suitabilityStatus == "clear" (fetched via GET /v1/auth/me, see
        // rememberHomeExtras below), not merely "a session exists". `null` (still loading, or the
        // field came back something other than "clear") shows nothing — never a false claim.
        if (verified == true) {
            GlassCard(modifier = Modifier.padding(start = 18.dp).height(40.dp), cornerRadiusDp = 20, glow = CaptainPalette.success) {
                Row(modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, contentDescription = null, tint = CaptainPalette.success, modifier = Modifier.size(18.dp))
                    Text(
                        "VERIFIED",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.success,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        // Status pill — the same real toggle as before (WheelDashboardViewModel.setAvailable,
        // tap-to-flip), now a glass pill whose halo/dot/title take the HeaderStatus tone. Tapping
        // while HIRED still does exactly what it always did (flip availability); this pass changes
        // no control's behaviour.
        GlassCard(
            modifier = Modifier
                .padding(start = 18.dp)
                .height(64.dp)
                .gameClick(
                    onClick = onToggleAvailability,
                    shape = RoundedCornerShape(32.dp),
                    glowColor = if (statusNeutral) CaptainPalette.accent else statusColor,
                ),
            cornerRadiusDp = 32,
            glow = if (statusNeutral) null else statusColor,
        ) {
            Row(modifier = Modifier.fillMaxHeight().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color = if (statusNeutral) CaptainPalette.textMuted else statusColor, animated = !statusNeutral, size = 14.dp)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        status.title,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        letterSpacing = 1.sp,
                        color = if (statusNeutral) CaptainPalette.textSecondary else statusColor,
                    )
                    Text(
                        status.sub,
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Real GPS/network/printer/battery — same DashboardStatusStrip WheelDashboardViewModel
        // already polls every 4s, grouped into one glass strip. GPS shows the real fix-quality
        // tier's tone (GpsQualityClassifier: GOOD/FAIR green, POOR amber, no fix/denied red) — the
        // same mapping the SYSTEM STATUS card below uses, so the two never disagree. Network label
        // is the real transport type (DeviceTelemetry.readNetworkType — "wifi"/"4g"/"offline") and
        // deliberately carries no signal-strength adjective ("STRONG"), since no TelephonyManager/
        // SignalStrength reading exists anywhere in this app to back one.
        GlassCard(modifier = Modifier.height(56.dp), cornerRadiusDp = 28) {
            Row(
                modifier = Modifier.fillMaxHeight().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StatusDot(Icons.Rounded.LocationOn, "GPS", gpsTone(state.status.gpsQuality))
                StatusDot(networkIcon(state.status.networkType), networkStatusLabel(state.status.networkType), networkTone(state.status.networkType))
                StatusDot(Icons.Rounded.Print, "PRINTER", if (state.status.printerOk) HudTone.Success else HudTone.Danger)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val batteryColor = if (state.status.batteryOk) CaptainPalette.success else CaptainPalette.danger
                    Icon(Icons.Rounded.BatteryFull, contentDescription = null, tint = batteryColor, modifier = Modifier.size(20.dp))
                    Text(
                        state.status.batteryPercent?.let { "$it%" } ?: "—",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = if (state.status.batteryOk) CaptainPalette.textPrimary else CaptainPalette.danger,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
        SosControl(onTrigger = onSos, modifier = Modifier.padding(start = 22.dp))
    }
}

/**
 * Loads the signed-in driver's real uploaded photo (`GET /v1/users/{userId}/photo`) — same
 * endpoint and Bitmap-decode approach [au.com.threesixty.cabdispatch.ui.screens.profile.ProfileViewModel.loadPhoto]
 * already uses, reimplemented here as a screen-local loader rather than by editing that
 * ViewModel — same "screen-local loader, ViewModel is off-limits" convention
 * [au.com.threesixty.cabdispatch.ui.screens.profile.ProfileScreen] already uses for its own
 * compliance-expiry cards. Falls back to the driver's initials (this pass's previous, still-real
 * placeholder) on no-photo/offline/error — never a stock/generic image standing in for a real
 * person. [driverId] as the `remember` key: a factory-reset/re-login mid-process must not show a
 * stale photo for a different driver.
 */
/** Real transport-type label ("wifi"/"4g"/"offline" from [au.com.threesixty.cabdispatch.domain.DeviceTelemetry.readNetworkType])
 * rendered for a driver, not the raw lowercase wire value — deliberately no signal-strength
 * adjective ("STRONG"/"WEAK"), see [CaptainHeader]'s own comment for why. */
internal fun networkStatusLabel(networkType: String?): String = when (networkType) {
    "wifi" -> "WI-FI"
    "4g" -> "4G"
    "offline" -> "OFFLINE"
    else -> "NETWORK"
}

/** Tone for the same real signal: a known transport is green, a confirmed "offline" is red, and
 * `null` (DeviceTelemetry itself couldn't check) is amber — unknown, not a claimed failure. */
internal fun networkTone(networkType: String?): HudTone = when (networkType) {
    "wifi", "4g" -> HudTone.Success
    "offline" -> HudTone.Danger
    else -> HudTone.Warning
}

private fun networkIcon(networkType: String?): ImageVector =
    if (networkType == "wifi") Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt

/** Tone for the real GPS fix-quality tier (`DashboardStatusStrip.gpsQuality`). GOOD/FAIR are
 * exactly what [au.com.threesixty.cabdispatch.domain.GpsQualityClassifier.isOk] calls ok (green),
 * POOR is a real-but-degraded fix (amber), no fix / permission denied is red. */
internal fun gpsTone(quality: GpsQuality): HudTone = when (quality) {
    GpsQuality.GOOD, GpsQuality.FAIR -> HudTone.Success
    GpsQuality.POOR -> HudTone.Warning
    GpsQuality.NO_FIX, GpsQuality.PERMISSION_DENIED -> HudTone.Danger
}

/** One header system-strip entry: tone-tinted icon, label, and a [PulsingDot] that breathes only
 * for a red (failed) state — a healthy dot sits still. */
@Composable
internal fun StatusDot(icon: ImageVector, label: String, tone: HudTone) {
    val toneColor = tone.color()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = toneColor, modifier = Modifier.size(18.dp))
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.padding(start = 6.dp),
        )
        // Static glowing shadow (2026-09-07, design brief item 6: "neon cyan and red dots with a
        // glowing shadow modifier") — the existing neonGlow primitive at a small, fixed strength;
        // Success now resolves to CaptainPalette.neonCyan (see that token's own doc), so a healthy
        // GPS/Wi-Fi/Printer reading is a genuinely neon-cyan glowing dot and a failed one a red one,
        // with zero new colour logic here. Only the already-existing Danger case still breathes —
        // this glow itself never animates.
        Box(modifier = Modifier.padding(start = 7.dp).neonGlow(toneColor, 5.dp, strength = 0.9f, spread = 4.dp)) {
            PulsingDot(color = toneColor, animated = tone == HudTone.Danger, size = 9.dp)
        }
    }
}

@Preview(name = "Header — available (dark)", widthDp = 1280, heightDp = 140, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainHeaderAvailable() {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(), verified = true, hasActiveTrip = false, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}

/** Light-mode side-by-side of [PreviewCaptainHeaderAvailable] — dashboard chrome (2026-09-04
 * day-mode pass), one of this pass's three required before/after samples (dashboard/meter
 * dial/Close & Pay — see [au.com.threesixty.cabdispatch.ui.theme.Hud]'s previews for the meter
 * dial and [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen]'s for Close & Pay). */
@Preview(name = "Header — available (light)", widthDp = 1280, heightDp = 140, backgroundColor = 0xFFF4F3F8, showBackground = true)
@Composable
private fun PreviewCaptainHeaderAvailableLight() {
    CaptainPalette.applyTheme(isLight = true)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(), verified = true, hasActiveTrip = false, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}

@Preview(name = "Header — hired", widthDp = 1280, heightDp = 140, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainHeaderHired() {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(), verified = true, hasActiveTrip = true, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}

@Preview(name = "Header — off duty", widthDp = 1280, heightDp = 140, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainHeaderOffDuty() {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(available = false), verified = null, hasActiveTrip = false, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}
