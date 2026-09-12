package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.BatteryStatsCounters
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Settings ▸ Diagnostics, debug-only: live per-minute counters for the four background costs the
 * 2026-09-12 optimisation plan's W4 exists to reduce — location requests, heartbeats, Room
 * writes and socket reconnects (task 7). See [BatteryStatsCounters]'s own doc for exactly what
 * each counts and why it is a rough, process-lifetime rate rather than anything precise.
 *
 * Deliberately simple, per the plan's own wording ("simple counters, no need for anything
 * fancy") — four labelled numbers, refreshed once a second, no charts/history/export. This is a
 * live sanity check that the adaptive cadences (task 1), the location-priority gating (task 2)
 * and the collapsed reconnect policy (task 5) are behaving as designed on THIS run; it is NOT a
 * substitute for the plan's own OWNER-only Battery Historian / `dumpsys batterystats` acceptance
 * gate, which needs a real device this pass has no access to.
 *
 * Gated on [BuildConfig.DEBUG] rather than the admin-PIN gate
 * [GpsSimulatorPanel]/[au.com.threesixty.cabdispatch.domain.location.GpsSimulator] use: unlike the
 * simulator, reading these counters cannot fabricate a fare or otherwise affect anything a driver
 * or the server relies on, so there is nothing here that needs gating beyond "not in a release
 * build a driver might see".
 */
// Composable function names follow this codebase's own PascalCase convention (every other
// composable in the module does the same, e.g. GpsSimulatorPanel/SectionLabel/ActionTile) rather
// than detekt's default lowerCamelCase function-naming rule.
@Suppress("FunctionNaming")
@Composable
fun BatteryStatsPanel(modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return

    var snapshot by remember { mutableStateOf(BatteryStatsCounters.snapshot()) }
    // Refreshed on a plain 1s poll, not reactively -- BatteryStatsCounters is a bag of AtomicLongs
    // with no Flow of its own (see that object's doc: it is written to from four completely
    // independent, unrelated call sites, so there is no single natural event to key a Flow off).
    // `LaunchedEffect(Unit)`, keyed on nothing, is correct here (not a mis-keyed leftover — see
    // the W5 "audit the 15 LaunchedEffect(Unit) sites" item this comment pre-empts): this effect's
    // job is exactly "start one polling loop when this panel first enters composition and keep it
    // running for as long as the panel is on screen", with no dependency whose change should
    // restart it.
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = BatteryStatsCounters.snapshot()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CaptainPalette.panel)
            .padding(16.dp),
    ) {
        Text(
            "BATTERY / NETWORK DIAGNOSTICS",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            color = CaptainPalette.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        StatRow("Location requests / min", snapshot.locationRequestsPerMin)
        StatRow("Heartbeats / min", snapshot.heartbeatsPerMin)
        StatRow("Room writes / min", snapshot.roomWritesPerMin)
        StatRow("Socket reconnects / min", snapshot.socketReconnectsPerMin)
    }
}

@Suppress("FunctionNaming")
@Composable
private fun StatRow(label: String, perMinute: Double) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.textPrimary)
        // One decimal place -- these are already a rough rate (see BatteryStatsCounters.snapshot's
        // doc), so more precision would be false confidence, not more information.
        val rounded = (perMinute * ROUNDING_FACTOR).roundToInt() / ROUNDING_FACTOR
        Text(
            rounded.toString(),
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = CaptainPalette.textPrimary,
        )
    }
}

/** How often [BatteryStatsPanel]'s counters refresh -- see its own doc for why a plain poll. */
private const val REFRESH_INTERVAL_MS = 1_000L

/** [StatRow] rounds to one decimal place by multiplying, rounding to the nearest integer, then
 * dividing back by this same factor. */
private const val ROUNDING_FACTOR = 10.0
