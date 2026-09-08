package au.com.threesixty.cabdispatch.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import au.com.threesixty.cabdispatch.ui.theme.Deck
import kotlin.math.abs
import kotlin.math.min
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

private val RAIL_CAROUSEL_W = 108.dp
private val RAIL_CAROUSEL_ITEM_H = 88.dp

/**
 * `c/nav-rail` v3 (2026-08-28, right-side movable carousel per Ben's request) — a draggable
 * vertical wheel where only the item nearest the vertical centre is enlarged + gold-highlighted;
 * neighbours shrink and fade toward the edges. Dragging up/down rotates the wheel and the settled
 * centre item becomes the selected tab (soft-snaps to centre on release); tapping any item scrolls
 * it to the centre and selects it. Replaces the old fixed [DeckNavRail] (kept below, unused, for
 * reference) and is mounted on the RIGHT of [DeckScaffold] rather than the left.
 *
 * Centring maths: the [LazyColumn] gets symmetric vertical [PaddingValues] of half the viewport so
 * every item — first and last included — can sit dead centre; an item is centred by
 * `scrollToItem(index, -padPx)` (its top placed `padPx` below the viewport top == its centre on the
 * viewport centre). Per-item scale/alpha/colour are driven by each visible item's distance from the
 * viewport centre read straight off [androidx.compose.foundation.lazy.LazyListState.layoutInfo].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckNavRailCarousel(
    selected: DeckTab,
    onSelect: (DeckTab) -> Unit,
    onDuressLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = DeckTab.entries
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val itemHpx = with(density) { RAIL_CAROUSEL_ITEM_H.toPx() }

    Column(
        modifier = modifier
            .width(RAIL_CAROUSEL_W)
            .fillMaxHeight()
            .background(Deck.panel),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val vpHpx = with(density) { maxHeight.toPx() }
            val padPx = ((vpHpx - itemHpx) / 2f).coerceAtLeast(0f)
            val padDp = with(density) { padPx.toDp() }
            val vpCenter = vpHpx / 2f

            // Selection is the single source of truth; the wheel follows it. Whenever the selected
            // tab changes (initial load, a tap on the wheel, or an external jump like the map's
            // "plot a zone" → ZONES), bring that item to the exact vertical centre. Centring is done
            // from measured layout positions (scroll it to the top, then nudge by its real distance
            // to centre) rather than a computed scrollOffset, which behaved inconsistently near the
            // list ends. Dragging the wheel freely re-highlights the centre item live (below); a tap
            // commits the selection.
            LaunchedEffect(selected, padPx) {
                if (padPx > 0f) {
                    val i = tabs.indexOf(selected).coerceAtLeast(0)
                    listState.animateScrollToItem(i)
                    val item = listState.layoutInfo.visibleItemsInfo.find { it.index == i }
                    if (item != null) {
                        val delta = (item.offset + item.size / 2f) - vpCenter
                        if (abs(delta) > 1f) listState.animateScrollBy(delta)
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = padDp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(tabs) { index, tab ->
                    val info = listState.layoutInfo.visibleItemsInfo.find { it.index == index }
                    val posFrac = if (info == null) {
                        0f
                    } else {
                        val itemCenter = info.offset + info.size / 2f
                        1f - min(1f, abs(itemCenter - vpCenter) / (itemHpx * 1.25f))
                    }
                    // At rest the selected tab is the highlighted centre item (robust regardless of
                    // exact scroll position); while the wheel is being dragged, the highlight follows
                    // whichever item is physically nearest the centre.
                    val frac = if (!listState.isScrollInProgress && tab == selected) 1f else posFrac
                    val scale = lerp(0.68f, 1f, frac)
                    val alpha = lerp(0.32f, 1f, frac)
                    val isCentre = frac > 0.72f
                    Column(
                        modifier = Modifier
                            .height(RAIL_CAROUSEL_ITEM_H)
                            .width(88.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                            .clip(RoundedCornerShape(Deck.R_MD.dp))
                            .background(if (isCentre) Deck.raised else Color.Transparent)
                            // Tapping selects; the centring effect above then snaps it to centre.
                            .clickable { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(text = tab.emoji, fontSize = if (isCentre) 30.sp else 24.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            style = DeckType.tinyLabel,
                            color = if (isCentre) Deck.yellow else Deck.textMuted,
                        )
                    }
                }
            }
        }
        // Discreet duress control pinned below the wheel (long-press only, same as the old rail).
        Box(
            modifier = Modifier
                .width(76.dp)
                .height(56.dp)
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
 * Standard in-shift screen shell: status strip on top, the movable nav-rail carousel on the RIGHT
 * (2026-08-28), [content] filling the rest on the canvas surface. Boot/login/full-takeover screens
 * (splash, meter) intentionally do NOT use this — they own their whole 1280×800.
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                content()
            }
            DeckNavRailCarousel(
                selected = selectedTab,
                onSelect = onSelectTab,
                onDuressLongPress = onDuressLongPress,
            )
        }
    }
}
