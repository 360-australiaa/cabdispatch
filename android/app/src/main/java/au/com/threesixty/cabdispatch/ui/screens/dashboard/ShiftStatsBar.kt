package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudRing
import au.com.threesixty.cabdispatch.ui.theme.HudStatTile
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.hudSpring
import au.com.threesixty.cabdispatch.ui.theme.gameClick
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PulsingDot
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The home screen's bottom stats bar — shift time, trips, earnings and next break — with
 * the progress bar, pills and the duration/clock formatting helpers it uses.
 *
 * Extracted from DeckHomeScreen.kt in Phase 0 (P0.4) — a mechanical move, same package,
 * same declarations, no behaviour change.
 */

// ============================================================================================
// Bottom stats bar (mockup: SHIFT TIME / TRIPS / EARNINGS / NEXT BREAK, plus SYSTEM STATUS) —
// rebuilt on the HUD kit (2026-09-04). Every cell is a HudStatTile or a GlassCard; every figure is
// the same real source as before (session.shiftStartAt, todayStats, HomeExtras, ShiftDurationLimit,
// DashboardStatusStrip). Nothing renders a placeholder number.
// ============================================================================================

/** Bar-cell value size: 32sp (the kit's 24sp default is a card size; this bar is read at arm's
 * length by an older driver population — see this file's class doc). */
private val STATS_VALUE_SIZE = 32.sp

@Composable
internal fun ShiftStatsBar(
    state: WheelDashboardUiState,
    extras: HomeExtras,
    onTakeBreak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = ShiftDurationLimit.remaining(state.session?.shiftStartAt)
    val elapsedFraction = remaining?.let { r ->
        val limit = ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS * 3600.0
        val remainingSec = r.seconds.toDouble()
        ((limit - remainingSec) / limit).toFloat().coerceIn(0f, 1f)
    } ?: 0f
    val elapsedLabel = shiftElapsedLabel(state.session?.shiftStartAt)
    // Real shift-scoped open-trip count (2026-08-29 contract Part 2.4/4.2: GET /v1/trips?...
    // &status=open). Shown only once loaded and non-zero — a `0` here is genuinely "no active
    // trip", worth just staying quiet about rather than printing "0 Active" under a stat row.
    val activeTrips = extras.tripsActiveThisShift?.takeIf { it > 0 }
    // Real day-over-day trend (2026-08-29 contract Part 4.3: GET /v1/trips/earnings/today,
    // Sydney-local calendar day). `null` means either not loaded yet or the backend had no
    // yesterday baseline — both render nothing, never a fabricated "0%" or "+12%".
    val pctChange = extras.earningsPctChange

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HudStatTile(
            icon = Icons.Rounded.Schedule,
            label = "Shift time",
            value = elapsedLabel ?: "—",
            sub = state.session?.shiftStartAt?.let { "Started ${formatClockTime(it)}" } ?: "No active shift",
            valueFontSize = STATS_VALUE_SIZE,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            footer = { ShiftProgressBar(fraction = elapsedFraction) },
        )
        HudStatTile(
            icon = Icons.Rounded.DirectionsCar,
            label = "Trips",
            value = state.todayStats.tripsCount.toString(),
            sub = "Completed",
            tone = HudTone.Success,
            valueFontSize = STATS_VALUE_SIZE,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            footer = if (activeTrips != null) { { ActiveTripsPill(active = activeTrips) } } else null,
        )
        HudStatTile(
            icon = Icons.Rounded.AttachMoney,
            label = "Earnings",
            value = "$" + state.todayStats.earningsTotal.setScale(0, RoundingMode.HALF_UP).toPlainString(),
            sub = "Today",
            tone = HudTone.Success,
            valueFontSize = STATS_VALUE_SIZE,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            footer = if (pctChange != null) { { EarningsDelta(pct = pctChange) } } else null,
        )
        NextBreakTile(
            remaining = remaining,
            session = state.session,
            isAvailable = state.isAvailable,
            fatigueAlertCount = extras.fatigueAlertCount,
            latestFatigueKind = extras.latestFatigueKind,
            onTakeBreak = onTakeBreak,
            modifier = Modifier.weight(1.55f).fillMaxHeight(),
        )
    }
}

/** SHIFT TIME's thin elapsed-vs-limit bar: [CaptainPalette.hudTrack] track, the HUD sweep gradient
 * as fill, settling on [hudSpring] like every other gauge in the kit. */
@Composable
private fun ShiftProgressBar(fraction: Float) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = hudSpring(), label = "shift-progress")
    Box(
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(CaptainPalette.hudTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(CaptainPalette.hudSweep)),
        )
    }
}

/** TRIPS' green "N Active" pill — only ever composed for a real, loaded, non-zero count. */
@Composable
private fun ActiveTripsPill(active: Int) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CaptainPalette.glowSuccessSoft)
            .border(1.dp, CaptainPalette.success.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color = CaptainPalette.success, animated = true, size = 8.dp)
        Text(
            "$active Active",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = CaptainPalette.success,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** EARNINGS' day-over-day delta — green when up, red when down; only composed when the backend
 * returned a real yesterday baseline. */
@Composable
private fun EarningsDelta(pct: Double) {
    val up = pct >= 0
    Text(
        "${if (up) "+" else "−"}${"%.0f".format(Locale.ENGLISH, kotlin.math.abs(pct))}% vs yesterday",
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        color = if (up) CaptainPalette.success else CaptainPalette.danger,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * The mockup's NEXT BREAK cell, on [GlassCard] + [HudRing]. Every figure is the REAL 12h shift-
 * duration clock ([ShiftDurationLimit.remaining], the documented client-side mirror of the
 * backend's fatigue limit) — there is still no break-schedule / break-taken API anywhere in this
 * app (see this file's class doc, "NEXT BREAK ring"), so "Break in h:mm" here means "time until
 * the fatigue limit says you must stop", and "Working until" is that limit's wall-clock time.
 * Neither is an invented schedule. The ring's progress is the remaining fraction of the limit and
 * the card gains a red halo once genuinely close to it (<15% left).
 *
 * [fatigueAlertCount]/[latestFatigueKind] stay as the real `GET /v1/fatigue-alerts` signal
 * (see [rememberHomeExtras]), shown only once loaded and non-zero. TAKE BREAK/RESUME
 * ([onTakeBreak]) is a real two-way `setAvailable` toggle (2026-09-06 — was a one-way
 * `setAvailable(false)` with no way back from this tile, mirrored from the header pill's own
 * toggle once [isAvailable] was threaded through here too): it honestly does the one thing this
 * app can do (stop/resume receiving dispatch offers) and claims no return time it doesn't know —
 * the shift clock above keeps running regardless, this only ever touches dispatch eligibility.
 */
@Composable
internal fun NextBreakTile(
    remaining: Duration?,
    session: DriverSession?,
    isAvailable: Boolean,
    fatigueAlertCount: Int?,
    latestFatigueKind: String?,
    onTakeBreak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = remaining?.let { r ->
        (r.seconds.toDouble() / (ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS * 3600.0)).toFloat().coerceIn(0f, 1f)
    } ?: 0f
    val urgent = remaining != null && fraction < 0.15f
    val labelTint = if (urgent) CaptainPalette.danger else CaptainPalette.hudAccent
    GlassCard(modifier = modifier, cornerRadiusDp = 18, glow = if (urgent) CaptainPalette.danger else null) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                HudRing(progress = fraction, modifier = Modifier.fillMaxSize(), strokeWidthDp = 6)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Coffee, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(16.dp))
                    Text(
                        remaining?.let { formatDurationHm(it) } ?: "—",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = CaptainPalette.textPrimary,
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Coffee, contentDescription = null, tint = labelTint, modifier = Modifier.size(16.dp))
                    Text(
                        "NEXT BREAK",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.textMuted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    remaining?.let { "Break in ${formatDurationHmm(it)}" } ?: "No active shift",
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = if (urgent) CaptainPalette.danger else CaptainPalette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    workingUntilLabel(session?.shiftStartAt) ?: "Start a shift to see your limit",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = CaptainPalette.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Real signal, finally consumed (2026-09-02): GET /v1/fatigue-alerts. Only shown once
                // loaded and non-zero — silence here is a genuine "no alerts", not "not checked".
                if (fatigueAlertCount != null && fatigueAlertCount > 0) {
                    Text(
                        "⚠ $fatigueAlertCount fatigue alert${if (fatigueAlertCount == 1) "" else "s"}" +
                            (latestFatigueKind?.let { " · ${it.replace('_', ' ')}" } ?: ""),
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = CaptainPalette.warning,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // Honest local action, not a fabricated break schedule — see this composable's own
                // doc. Real two-way availability toggle; claims no return time this app doesn't know.
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CaptainPalette.hudAccent.copy(alpha = 0.22f))
                        .border(1.dp, CaptainPalette.hudSweepMid.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .gameClick(onClick = onTakeBreak, shape = RoundedCornerShape(12.dp), glowColor = CaptainPalette.hudSweepMid)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        if (isAvailable) "☕ TAKE BREAK" else "▶ RESUME",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.hudSweepMid,
                    )
                }
            }
        }
    }
}

// SystemStatusCard/SystemStatusCell (the bottom-right SYSTEM STATUS tile: GPS/network/printer/
// meter) removed 2026-09-06 on direct driver feedback -- it duplicated the header strip's own
// StatusDot row (CaptainHeader, ~line 903) with no removal ever having landed before now (checked
// git history and HANDOFF.md -- no prior removal attempt is recorded). gpsValueLabel/
// networkCellValue below were this composable's own value-formatting helpers, unused by anything
// else, and are removed with it; networkStatusLabel/gpsTone/networkTone are kept -- the header
// strip still uses those three.

// --- Shift-time formatting helpers (real session.shiftStartAt, no fabricated numbers) ---------

private fun shiftElapsedLabel(shiftStartAtIso: String?): String? {
    val start = shiftStartAtIso?.let { parseInstantOrOffset(it) } ?: return null
    val elapsed = Duration.between(start, Instant.now()).let { if (it.isNegative) Duration.ZERO else it }
    return formatDurationHm(elapsed)
}

/** "Working until 6:12 PM" — the wall-clock time the real 12h shift-duration limit is reached. */
private fun workingUntilLabel(shiftStartAtIso: String?): String? {
    val start = shiftStartAtIso?.let { parseInstantOrOffset(it) } ?: return null
    val end = start.plusSeconds((ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS * 3600.0).toLong())
    val zoned = end.atZone(java.time.ZoneId.systemDefault())
    val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    return "Working until ${fmt.format(zoned)}"
}

private fun formatClockTime(iso: String): String {
    val instant = parseInstantOrOffset(iso) ?: return iso
    val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    return fmt.format(instant.atZone(java.time.ZoneId.systemDefault()))
}

private fun formatDurationHm(d: Duration): String {
    val abs = d.abs()
    val h = abs.toHours()
    val m = abs.minusHours(h).toMinutes()
    val sign = if (d.isNegative) "-" else ""
    return "$sign${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

/** "7:48" — [formatDurationHm] without the zero-padded hour, for the "Break in h:mm" line. */
private fun formatDurationHmm(d: Duration): String {
    val abs = d.abs()
    val h = abs.toHours()
    val m = abs.minusHours(h).toMinutes()
    val sign = if (d.isNegative) "-" else ""
    return "$sign$h:${m.toString().padStart(2, '0')}"
}

private fun parseInstantOrOffset(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.recoverCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

@Preview(name = "Bottom bar (dark)", widthDp = 1280, heightDp = 176, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewShiftStatsBar() {
    CaptainPalette.applyTheme(isLight = false)
    val state = previewState()
    Row(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg).padding(12.dp).height(152.dp)) {
        ShiftStatsBar(
            state = state,
            extras = HomeExtras(verified = true, earningsPctChange = 12.0, tripsActiveThisShift = 1, fatigueAlertCount = 1, latestFatigueKind = "shift_duration"),
            onTakeBreak = {},
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Preview(name = "Bottom bar (light)", widthDp = 1280, heightDp = 176, backgroundColor = 0xFFF4F3F8, showBackground = true)
@Composable
private fun PreviewShiftStatsBarLight() {
    CaptainPalette.applyTheme(isLight = true)
    val state = previewState()
    Row(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg).padding(12.dp).height(152.dp)) {
        ShiftStatsBar(
            state = state,
            extras = HomeExtras(verified = true, earningsPctChange = 12.0, tripsActiveThisShift = 1, fatigueAlertCount = 1, latestFatigueKind = "shift_duration"),
            onTakeBreak = {},
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
