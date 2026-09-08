package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SsidChart
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.spring
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.neonGlow
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.rememberInfiniteFloat

/**
 * The home screen's left nav rail: the rail item model, the item list, the rail itself
 * and the individual tile.
 *
 * Extracted from DeckHomeScreen.kt in Phase 0 (P0.4) — a mechanical move, same package,
 * same declarations, no behaviour change.
 */

// ============================================================================================
// Nav rail — the ONE menu (mockup #3/#4): a single vertical icon rail, icon + short uppercase label
// per item, the active item lit by an accent glow pill. Rebuilt on the HUD kit (2026-09-04): the
// numbered "1 2 3…" circles are gone (not in the mockup), DISPATCH carries a live red offer-count
// badge, METER glows green while a fare is open. No hamburger, flyout or chevron — those were
// three duplicate menus and were deleted on 2026-09-03; see the class doc for the item mapping,
// additions (Messages, Live Map) and omissions (Help & Support, Navigate, More).
// ============================================================================================

private data class RailItem(
    val icon: ImageVector,
    val label: String,
    val action: RailAction,
)

private sealed interface RailAction {
    data class ToPane(val pane: CaptainPane) : RailAction
    data object OpenVouchers : RailAction
    data object OpenProfile : RailAction
    data object OpenSettings : RailAction
    data object LogOff : RailAction
}

/**
 * [hasActiveTrip] decides what the METER row actually points at (Phase A shell-integration,
 * 2026-09-03): the old hardcoded alias to [CaptainPane.DASHBOARD] ("meter lives on Dashboard") is
 * now only the fallback for "no fare is open right now" — tapping METER while [DeckHomeScreen]'s
 * own active-trip read is true instead jumps straight to the real, live [CaptainPane.METER] pane,
 * matching this file's class doc ("decide whether that alias should now point at the real
 * active-fare pane when a trip is active"). A plain function (not a `val`) since this genuinely
 * varies per composition rather than being a fixed table.
 */
private fun railItems(hasActiveTrip: Boolean) = listOf(
    RailItem(Icons.Rounded.Home, "DASHBOARD", RailAction.ToPane(CaptainPane.DASHBOARD)),
    RailItem(Icons.Rounded.Receipt, "TRIPS", RailAction.ToPane(CaptainPane.TRIPS)),
    RailItem(Icons.Rounded.SwapHoriz, "DISPATCH", RailAction.ToPane(CaptainPane.DISPATCH)),
    RailItem(Icons.Rounded.Speed, "METER", RailAction.ToPane(if (hasActiveTrip) CaptainPane.METER else CaptainPane.DASHBOARD)),
    RailItem(Icons.Rounded.SsidChart, "EARNINGS", RailAction.ToPane(CaptainPane.EARNINGS)),
    RailItem(Icons.Rounded.History, "HISTORY", RailAction.ToPane(CaptainPane.SHIFT)),
    RailItem(Icons.Rounded.LocationOn, "ZONES", RailAction.ToPane(CaptainPane.ZONES)),
    RailItem(Icons.Rounded.Sell, "PRICING", RailAction.ToPane(CaptainPane.PRICING)),
    RailItem(Icons.Rounded.ConfirmationNumber, "VOUCHERS", RailAction.ToPane(CaptainPane.VOUCHERS)),
    RailItem(Icons.Rounded.Person, "DRIVER", RailAction.OpenProfile),
    RailItem(Icons.Rounded.SettingsSuggest, "SETTINGS", RailAction.OpenSettings),
    // Messages, Live map and Log off used to live only in the flyout that was deleted. The one
    // rail now carries every real destination (it scrolls), so nothing working is stranded.
    RailItem(Icons.Rounded.Mail, "MESSAGES", RailAction.ToPane(CaptainPane.MESSAGES)),
    RailItem(Icons.Rounded.Map, "MAP", RailAction.ToPane(CaptainPane.MAP)),
    RailItem(Icons.AutoMirrored.Rounded.Logout, "LOG OUT", RailAction.LogOff),
)

/**
 * [dispatchOfferCount] is the size of the live pending-offer list
 * ([au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsUiState.cards]) the caller already
 * collects for the Dashboard's LiveDispatchCard — the DISPATCH badge shows it while non-zero and
 * nothing otherwise. Never a static number.
 */
@Composable
internal fun CaptainNavRail(
    pane: CaptainPane,
    hasActiveTrip: Boolean,
    dispatchOfferCount: Int,
    onSelectPane: (CaptainPane) -> Unit,
    onOpenVouchers: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Real bug fix (2026-09-06, live driver testing): a driver could tap any other rail item
    // mid-fare and land on it -- the Dashboard pane would then show its OWN "METER STATUS: OFF /
    // Tap to start a new fare" dial, directly contradicting the still-green METER tile and the
    // "1 Active" trip count, while the actual fare kept accruing unattended underneath. `hasActiveTrip`
    // was already computed and passed in here (see this composable's own doc) but nothing ever
    // consulted it before dispatching -- every action fired unconditionally. Fixed at this single
    // choke point every rail tap and header/rail navigation action already funnels through: while a
    // trip is open, the ONLY action that's allowed through is switching TO the METER pane itself
    // (already-selected METER taps, or re-tapping METER from METER, are harmless no-ops here).
    // Everything else (DASHBOARD/TRIPS/DISPATCH/EARNINGS/HISTORY/ZONES/PRICING/VOUCHERS/MESSAGES/MAP,
    // DRIVER, SETTINGS, LOG OUT, and the Vouchers info dialog) is a no-op until the trip is
    // genuinely closed-and-paid (CloseAndPayViewModel.finalizeClose flips hasActiveTrip back to
    // false, per DeckHomeScreen's own observeActiveTrip() read above). `RailTile` below dims these
    // locked tiles so the no-op doesn't look like a stalled tap.
    // SETTINGS is exempt from the mid-fare lock (2026-09-07). The lock exists because navigating
    // to another PANE showed a second, contradictory meter dial while the real fare kept accruing
    // -- Settings has no fare dial, so it cannot cause that, and locking it meant a driver could
    // not reach the printer, GPS diagnostics or offline maps without abandoning a trip first.
    // Reported from the tablet as simply "the menu is locked, I can't go to settings".
    val alwaysAllowed = setOf<RailAction>(RailAction.ToPane(CaptainPane.METER), RailAction.OpenSettings)

    fun dispatch(action: RailAction) {
        if (hasActiveTrip && action !in alwaysAllowed) return
        when (action) {
            is RailAction.ToPane -> onSelectPane(action.pane)
            RailAction.OpenVouchers -> onOpenVouchers()
            RailAction.OpenProfile -> onOpenProfile()
            RailAction.OpenSettings -> onOpenSettings()
            RailAction.LogOff -> onLogOff()
        }
    }

    // DASHBOARD and METER both alias CaptainPane.DASHBOARD while no fare is open (see railItems'
    // own comment) — matching on `pane` alone would light up BOTH simultaneously, which is not
    // what the mockup shows (exactly one item highlighted at a time). Picking only the FIRST
    // item whose target matches resolves the alias in DASHBOARD's favour without separate
    // click-tracked selection state. Once a fare IS open, METER's own target becomes
    // CaptainPane.METER (distinct from DASHBOARD's), so both light up correctly on their own pane.
    val items = railItems(hasActiveTrip)
    val activeIndex = items.indexOfFirst { (it.action as? RailAction.ToPane)?.pane == pane }
    GlassCard(modifier = modifier.width(RAIL_WIDTH), cornerRadiusDp = 22) {
        // Scrollable — with 14 real destinations at a legible touch-target size the list runs
        // taller than the rail's real available height (measured live on the SM-T575: an
        // un-scrollable Column here silently clipped everything from HISTORY down).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Say WHY the menu is unresponsive, not just that it is.
            //
            // Reported from the tablet, 2026-09-07: "why can't I see settings, all menu is
            // locked?". It was locked, correctly and deliberately -- `dispatch` above refuses
            // every destination except METER while a fare is open, so a driver cannot wander off
            // mid-trip while it keeps accruing. But the only signal was RailTile's 35% dim, and a
            // dimmed menu on a dark screen is indistinguishable from a broken one. The driver's
            // conclusion (a fleet admin has locked this tablet) was entirely reasonable, and the
            // FLEET LOCKED badge sitting in the corner made it more so.
            //
            // One line converts a mystery into an instruction. Shown only while the lock is
            // actually in force.
            if (hasActiveTrip) {
                Text(
                    "FARE RUNNING\nMeter + Settings only",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 0.4.sp,
                    textAlign = TextAlign.Center,
                    color = CaptainPalette.warning,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            items.forEachIndexed { index, item ->
                val target = (item.action as? RailAction.ToPane)?.pane
                RailTile(
                    item = item,
                    selected = index == activeIndex,
                    badge = if (target == CaptainPane.DISPATCH && dispatchOfferCount > 0) dispatchOfferCount else null,
                    // METER's target is CaptainPane.METER only while a fare is actually open.
                    live = hasActiveTrip && target == CaptainPane.METER,
                    // Same condition dispatch() itself gates on above -- kept in sync deliberately
                    // (both read hasActiveTrip + compare against the same METER action) rather than
                    // exposed as a shared val, since this one also needs `item.action` per-tile.
                    // Same exemption set dispatch() uses, so a tile is dimmed exactly when it is
                    // actually inert -- a dimmed-but-working SETTINGS would be its own small lie.
                    locked = hasActiveTrip && item.action !in alwaysAllowed,
                    onClick = { dispatch(item.action) },
                )
            }
        }
    }
}

/**
 * One rail tile — icon over a short uppercase label, a full 96x72dp touch target. [selected] lights
 * an accent fill + a breathing [neonGlow] halo (the mockup's active pill); [live] (METER while a
 * fare is open) breathes a green halo instead so the driver can see at a glance that a meter is
 * running from any pane; [badge] is the red offer-count dot on DISPATCH. Press feedback is the
 * same bouncy spring squash every tappable surface in this app uses.
 *
 * [locked] (2026-09-06, real driver-testing fix): true for every tile except METER while a trip
 * is open -- `onClick` still fires (dispatch() itself is the actual guard, see CaptainNavRail's
 * own doc), so this is purely the honest visual half of that fix: a locked tile that looked fully
 * normal would read as a broken/unresponsive tap, not an intentional "finish this trip first"
 * state. Plain reduced opacity, no motion -- this app's standing rule against decorative
 * animation on driver-facing chrome applies here same as anywhere else.
 */
@Composable
private fun RailTile(item: RailItem, selected: Boolean, badge: Int?, live: Boolean, locked: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f),
        label = "rail-tile-press",
    )
    val fg by animateColorAsState(
        when {
            selected -> CaptainPalette.hudSweepMid
            live -> CaptainPalette.success
            else -> CaptainPalette.textSecondary
        },
        label = "rail-fg",
    )
    val fill by animateColorAsState(if (selected) CaptainPalette.hudAccent.copy(alpha = 0.24f) else Color.Transparent, label = "rail-fill")
    val breathe by rememberInfiniteFloat(enabled = selected || live, from = 0.45f, to = 1f, durationMs = 1300)
    val halo = when {
        selected -> CaptainPalette.hudAccent
        live -> CaptainPalette.success
        else -> null
    }
    val shape = RoundedCornerShape(16.dp)
    val lockedAlpha by animateFloatAsState(if (locked) 0.35f else 1f, label = "rail-locked-alpha")
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(72.dp)
            .scale(scale)
            .alpha(lockedAlpha)
            .then(if (halo != null) Modifier.neonGlow(halo, 16.dp, strength = breathe) else Modifier)
            .clip(shape)
            .background(fill)
            .then(if (halo != null) Modifier.border(1.5.dp, halo.copy(alpha = 0.35f + 0.55f * breathe), shape) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(item.icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 11.dp, y = (-9).dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(CaptainPalette.danger)
                            .border(1.5.dp, CaptainPalette.hudBg, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (badge > 9) "9+" else badge.toString(),
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = CaptainPalette.onAccent,
                        )
                    }
                }
            }
            Text(
                item.label,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = fg,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Preview(name = "Rail — dispatch selected, 3 offers, meter live", widthDp = 160, heightDp = 760, backgroundColor = 0xFF0B0B10, showBackground = true)
@Composable
private fun PreviewCaptainNavRail() {
    CaptainPalette.applyTheme(isLight = false)
    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg).padding(12.dp)) {
        CaptainNavRail(
            pane = CaptainPane.DISPATCH,
            hasActiveTrip = true,
            dispatchOfferCount = 3,
            onSelectPane = {},
            onOpenVouchers = {},
            onOpenProfile = {},
            onOpenSettings = {},
            onLogOff = {},
            modifier = Modifier.fillMaxHeight(),
        )
    }
}
