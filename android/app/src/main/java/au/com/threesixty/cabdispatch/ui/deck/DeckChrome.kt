package au.com.threesixty.cabdispatch.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.DeckState
import au.com.threesixty.cabdispatch.ui.theme.DeckType
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Command Deck chrome — the three fixed layout regions of the v2 redesign (Figma
 * `h0PSsSXQ971dOJvt25tN7BA`.. components `c/status-strip` 4:14, `c/nav-rail` 4:39): the 44dp
 * persistent status strip, the 92dp left nav rail, and [DeckScaffold] composing them around a
 * content slot. The 400dp drive panel (`c/drive-panel` 11:27) is home-screen-specific and lives
 * with [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen].
 */

/** Everything the status strip renders, decoupled from any single ViewModel. */
data class StripStatus(
    val state: DeckState,
    val shiftLeftLabel: String?, // e.g. "5h 54m left" — null hides the chip (not in shift)
    val tariffSigned: Boolean,
    val gpsOk: Boolean,
    val netOk: Boolean,
    val printerOk: Boolean,
    val batteryPercent: Int?,
)

private fun okColor(ok: Boolean) = if (ok) Deck.forHire else Deck.hired

/** Live "Mon 10 Aug · 4:05 PM" clock, ticking on the minute. */
@Composable
fun rememberDeckClock(): String {
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.ENGLISH) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }
    return fmt.format(now)
}

/**
 * `c/status-strip` — 44dp, persists on every in-shift screen: clock · shift countdown · center
 * STATE pill · TARIFF SIGNED ✓ · GPS/4G/PRN/BATT chips. A 2dp underline in the state color makes
 * the state readable even when the pill is out of the driver's fovea.
 */
@Composable
fun DeckStatusStrip(status: StripStatus, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Deck.STATUS_STRIP_H.dp)
                .background(Deck.panel)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = rememberDeckClock(),
                    fontFamily = RobotoMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Deck.textSecondary,
                )
                status.shiftLeftLabel?.let { DeckChip(text = "SHIFT $it", dotColor = Deck.yellow) }
            }
            StatePill(state = status.state, modifier = Modifier.align(Alignment.Center))
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeckChip(
                    text = if (status.tariffSigned) "TARIFF SIGNED ✓" else "TARIFF UNSIGNED",
                    dotColor = okColor(status.tariffSigned),
                )
                DeckChip(text = "GPS", dotColor = okColor(status.gpsOk))
                DeckChip(text = "4G", dotColor = okColor(status.netOk))
                DeckChip(text = "PRN", dotColor = okColor(status.printerOk))
                DeckChip(
                    text = status.batteryPercent?.let { "$it%" } ?: "—",
                    dotColor = okColor((status.batteryPercent ?: 100) > 15),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(status.state.color),
        )
    }
}

/** One rail destination. [emoji] matches the Figma glyphs exactly. */
enum class DeckTab(val emoji: String, val label: String) {
    STATUS("🚕", "STATUS"),
    JOBS("📋", "JOBS"),
    ZONES("📍", "ZONES"),
    MSGS("✉️", "MSGS"),
    TRIPS("🧾", "TRIPS"),
    EARN("💰", "EARN"),
    SHIFT("⏱", "SHIFT"),
}

/**
 * `c/nav-rail` — 92dp wide, 76×84 items. The bottom item is the DELIBERATELY unlabeled duress
 * control (Figma `item-duress`): a plain dark circle with a faint purple dot, discreet by design.
 * Long-press (not tap) triggers it so a knee-bump can't fire a silent alarm.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckNavRail(
    selected: DeckTab,
    onSelect: (DeckTab) -> Unit,
    onDuressLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(Deck.NAV_RAIL_W.dp)
            .fillMaxHeight()
            .background(Deck.panel)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DeckTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(Deck.R_MD.dp))
                    .background(if (isSelected) Deck.raised else Color.Transparent)
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = tab.emoji, fontSize = 24.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.label,
                    style = DeckType.tinyLabel,
                    color = if (isSelected) Deck.yellow else Deck.textMuted,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(76.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(Deck.R_MD.dp))
                .combinedClickable(onClick = {}, onLongClick = onDuressLongPress),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Deck.duress.copy(alpha = 0.55f)),
            )
        }
    }
}

/**
 * Standard in-shift screen shell: status strip on top, nav rail on the left, [content] filling
 * the rest on the canvas surface. Boot/login/full-takeover screens (splash, meter) intentionally
 * do NOT use this — they own their whole 1280×800.
 */
@Composable
fun DeckScaffold(
    status: StripStatus,
    selectedTab: DeckTab,
    onSelectTab: (DeckTab) -> Unit,
    onDuressLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(Deck.canvas)) {
        DeckStatusStrip(status = status)
        Row(modifier = Modifier.weight(1f)) {
            DeckNavRail(selected = selectedTab, onSelect = onSelectTab, onDuressLongPress = onDuressLongPress)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                content()
            }
        }
    }
}
