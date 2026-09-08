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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.rounded.ExpandMore
import au.com.threesixty.cabdispatch.domain.TenantBranding
import au.com.threesixty.cabdispatch.ui.deck.rememberDeckClock
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.Radius
import au.com.threesixty.cabdispatch.ui.theme.Space
import au.com.threesixty.cabdispatch.ui.theme.Type
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
    // Rebuilt against the owner's north-star render (2026-09-08). Three groups on ONE full-width
    // dark card, left / centre / right, each on the 8pt grid:
    //   left    avatar · operator wordmark · driver name + VERIFIED · rego
    //   centre  the availability pill (a real toggle, so it reads as one: dot, state, chevron)
    //   right   GPS · network · printer · battery  |  SOS  |  date + clock
    // The card is a real `panel` surface with a `panelBorder` hairline underneath, not a gradient
    // wash: the reference treats the header as a card in the same ecosystem as every other one.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .reportsChromeHeader()
                .background(CaptainPalette.panel)
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            // ---- LEFT: identity -------------------------------------------------------------
            Box(modifier = Modifier.neonGlow(CaptainPalette.hudAccent, 26.dp, strength = 0.6f)) {
                DriverAvatar(driverId = state.session?.driverId, driverName = state.session?.driverName, onClick = onShowDriverId, sizeDp = 56)
            }
            // Operator wordmark, restored per the reference. Sourced from TenantBranding, the one
            // place the operator name lives; that object documents whether the value is a
            // compiled default or a fetched tenant record, so nothing here overstates it.
            Text(
                TenantBranding.operatorName.uppercase(),
                style = Type.tiny,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = CaptainPalette.textPrimary,
                maxLines = 1,
            )
            Column(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable(onClick = onOpenProfile)
                    .padding(horizontal = Space.sm),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        state.session?.driverName ?: "No driver",
                        style = Type.h2,
                        color = CaptainPalette.textPrimary,
                        maxLines = 1,
                    )
                    // VERIFIED as a solid purple pill beside the name, as the reference draws it.
                    // Same real backend field and honesty rule as before: null/false (loading, or
                    // suitability not "clear") draws NOTHING, never a false claim.
                    if (verified == true) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(CaptainPalette.accent)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Verified,
                                contentDescription = "Verified driver",
                                tint = CaptainPalette.onAccent,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                "VERIFIED",
                                style = Type.tiny,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = CaptainPalette.onAccent,
                            )
                        }
                    }
                }
                // Rego only. The reference shows "rego · make/model" but no make/model field
                // exists in this app's data model or its backend, so the real rego is shown and
                // nothing is invented to fill the gap.
                Text(
                    state.session?.vehicleId ?: "—",
                    style = Type.mono,
                    color = CaptainPalette.textSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))

            // ---- CENTRE: availability pill --------------------------------------------------
            // A real toggle (WheelDashboardViewModel.setAvailable), so it carries a chevron: the
            // reference's cue that this is something you press. The pill is a dark card with the
            // state colour as halo and border rather than a filled block; green has to read from
            // a moving car, and a green glow on dark does that better than green ink.
            GlassCard(
                modifier = Modifier
                    .height(56.dp)
                    .gameClick(
                        onClick = onToggleAvailability,
                        shape = RoundedCornerShape(28.dp),
                        glowColor = if (statusNeutral) CaptainPalette.accent else statusColor,
                    ),
                cornerRadiusDp = 28,
                glow = if (statusNeutral) null else statusColor,
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(start = Space.md, end = Space.smd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // SANCTIONED LOOP 1 OF 3 (A3 motion pass): breathes only while the status is
                    // non-neutral. See HomeMotion.kt.
                    PulsingDot(color = if (statusNeutral) CaptainPalette.textMuted else statusColor, animated = !statusNeutral, size = 12.dp)
                    Column(modifier = Modifier.padding(start = Space.smd)) {
                        Text(
                            status.title,
                            style = Type.h2,
                            letterSpacing = 1.sp,
                            color = if (statusNeutral) CaptainPalette.textSecondary else statusColor,
                            maxLines = 1,
                        )
                        Text(status.sub, style = Type.tiny, color = CaptainPalette.textSecondary, maxLines = 1)
                    }
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = CaptainPalette.textSecondary,
                        modifier = Modifier.padding(start = Space.smd).size(20.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))

            // ---- RIGHT: system matrix, SOS, clock -------------------------------------------
            // Real GPS/network/printer/battery, the same DashboardStatusStrip the ViewModel polls
            // every 4s. Read-only readouts, so 44dp is legitimate: nothing here is tappable. No
            // signal-strength adjective on the network label; nothing in this app measures one.
            GlassCard(modifier = Modifier.height(44.dp), cornerRadiusDp = 22) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    StatusDot(Icons.Rounded.LocationOn, "GPS", gpsTone(state.status.gpsQuality))
                    StatusDot(networkIcon(state.status.networkType), networkStatusLabel(state.status.networkType), networkTone(state.status.networkType))
                    StatusDot(Icons.Rounded.Print, "PRINTER", if (state.status.printerOk) HudTone.Success else HudTone.Danger)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val batteryColor = if (state.status.batteryOk) CaptainPalette.success else CaptainPalette.danger
                        Icon(
                            Icons.Rounded.BatteryFull,
                            contentDescription = state.status.batteryPercent?.let { "Battery $it percent" } ?: "Battery level unknown",
                            tint = batteryColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            state.status.batteryPercent?.let { "$it%" } ?: "—",
                            fontFamily = ChakraPetch,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = if (state.status.batteryOk) CaptainPalette.textPrimary else CaptainPalette.danger,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
            // Press-and-HOLD, never a tap. See SosControl's own doc. Unchanged.
            SosControl(onTrigger = onSos, sizeDp = 56, inlineHoldLabel = false)
            // Date over time, right-aligned, per the reference. rememberDeckClock already formats
            // "EEE d MMM · h:mm a" and ticks once a second; it is split on the separator so the two
            // lines can be styled independently rather than adding a second clock.
            val clock = rememberDeckClock()
            val sep = clock.indexOf(" · ")
            val dateText = if (sep >= 0) clock.substring(0, sep) else clock
            val timeText = if (sep >= 0) clock.substring(sep + 3) else ""
            Column(horizontalAlignment = Alignment.End) {
                Text(dateText, style = Type.tiny, color = CaptainPalette.textSecondary, maxLines = 1)
                Text(
                    timeText,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = CaptainPalette.textPrimary,
                    maxLines = 1,
                )
            }
        }
        // The hairline that separates the header card from the canvas. The reference's cards all
        // carry it, and this is a card.
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))
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

/** Spoken form of a [HudTone] for the status strip's contentDescriptions - see [StatusDot]. */
private fun toneDescription(tone: HudTone): String = when (tone) {
    HudTone.Success -> "ok"
    HudTone.Warning -> "unknown"
    HudTone.Danger -> "failed"
    else -> "unknown"
}

/** One header system-strip entry: tone-tinted icon, label, and a [PulsingDot] that breathes only
 * for a red (failed) state — a healthy dot sits still. */
@Composable
internal fun StatusDot(icon: ImageVector, label: String, tone: HudTone) {
    val toneColor = tone.color()
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A3 a11y pass: was contentDescription = null. The tone IS the information here (a red
        // GPS dot and a green one are the same glyph), so the description carries the state, not
        // just the name - a TalkBack user gets "GPS, ok" / "GPS, failed", never a bare "GPS".
        Icon(
            icon,
            contentDescription = "$label, ${toneDescription(tone)}",
            tint = toneColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            // 13sp -> 12sp (A3, Type.tiny - the floor).
            style = Type.tiny,
            letterSpacing = 0.5.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.padding(start = Space.xs),
        )
        // Static glowing shadow (2026-09-07, design brief item 6: "neon cyan and red dots with a
        // glowing shadow modifier") — the existing neonGlow primitive at a small, fixed strength;
        // Success now resolves to CaptainPalette.neonCyan (see that token's own doc), so a healthy
        // GPS/Wi-Fi/Printer reading is a genuinely neon-cyan glowing dot and a failed one a red one,
        // with zero new colour logic here. Only the already-existing Danger case still breathes —
        // this glow itself never animates.
        Box(modifier = Modifier.padding(start = Space.xs).neonGlow(toneColor, 5.dp, strength = 0.9f, spread = 4.dp)) {
            PulsingDot(color = toneColor, animated = tone == HudTone.Danger, size = 8.dp)
        }
    }
}

@Preview(name = "Header — available (dark)", widthDp = 1280, heightDp = 96, backgroundColor = 0xFF0B0B10, showBackground = true)
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
@Preview(name = "Header — available (light)", widthDp = 1280, heightDp = 96, backgroundColor = 0xFFF4F3F8, showBackground = true)
@Composable
private fun PreviewCaptainHeaderAvailableLight() {
    CaptainPalette.applyTheme(isLight = true)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(), verified = true, hasActiveTrip = false, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}

@Preview(name = "Header — hired", widthDp = 1280, heightDp = 96, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainHeaderHired() {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(), verified = true, hasActiveTrip = true, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}

@Preview(name = "Header — off duty", widthDp = 1280, heightDp = 96, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainHeaderOffDuty() {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg)) {
        CaptainHeader(state = previewState(available = false), verified = null, hasActiveTrip = false, onShowDriverId = {}, onOpenProfile = {}, onToggleAvailability = {}, onSos = {})
    }
}
