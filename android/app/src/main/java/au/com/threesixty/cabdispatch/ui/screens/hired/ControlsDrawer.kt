package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.FareBreakdown
import au.com.threesixty.cabdispatch.domain.TimeClass
import au.com.threesixty.cabdispatch.domain.TripContext
import java.math.BigDecimal
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.Type
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * The meter screen's pull-up controls drawer and the handle that opens it.
 *
 * Extracted from HiredScreen.kt in Phase 0 (P0.4) — a mechanical move, same package, same
 * declarations, no behaviour change.
 */

private val NAV_TILE_H = 92.dp

/**
 * Exactly the [au.com.threesixty.cabdispatch.domain.FareState] fields [ControlsDrawer]'s subtree
 * reads, bundled into one class for the same reason [MeterActions] bundles its own four callbacks
 * — see [ControlsDrawer]'s own doc for the recomposition-hygiene reasoning this exists for.
 */
internal data class ControlsDrawerFareInfo(
    val breakdown: FareBreakdown,
    val timeClass: TimeClass,
    val negotiatedTotal: BigDecimal?,
    val movingSeconds: Int,
    val distanceKm: BigDecimal,
)

/**
 * The small, low-profile affordance that opens [ControlsDrawer] — docked in the dial panel's
 * otherwise-empty corner (see [MeterPaneLayout]'s doc for why a corner is always safe there). One
 * tap opens the drawer; the drawer's own Close (or tapping the scrim) collapses it again — this is
 * the "small secondary-actions affordance the driver deliberately opens" half of the production
 * taxi-meter/kiosk convention this redesign follows, paired with the big always-on dial+map readout
 * a back-seat passenger can actually read.
 */
@Composable
internal fun ControlsHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(99.dp))
            .background(CaptainPalette.raised.copy(alpha = 0.92f))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.MoreHoriz, contentDescription = "Open controls", tint = CaptainPalette.textSecondary, modifier = Modifier.size(18.dp))
        Text(
            "CONTROLS",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            // 10sp -> 12sp (A4: Type.tiny, the accessibility floor).
            style = Type.tiny,
            letterSpacing = 1.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * The collapsed control surface itself (2026-09-04b redesign) — everything that used to sit in the
 * permanent third column (NIGHT/DAY FARE, the four action tiles, FARE BREAKDOWN/DETAILS, TRIP
 * DETAILS), unchanged in substance, moved here behind [ControlsHandle]. Same dialog shell as every
 * other action on this screen ([TollPresetDialog], [SetPriceInfoDialog], [MoreActionsSheet] etc.) —
 * a fixed-width [GlassCard]-style panel via [CaptainDialogScrim], scrollable so it never clips on
 * the pane's real height. [actions] is the identical [MeterActions] bundle [HiredScreen] already
 * builds — SET PRICE/ADD TOLL/MORE close this drawer first (see those callbacks' own doc at the
 * `actions` call site) so the driver lands back on the plain dial+map view under whichever dialog it
 * opened; PAUSE FARE has no dialog of its own and leaves the drawer open.
 *
 * Takes [fareInfo] (a small bundle of exactly [ControlsDrawerFareInfo.breakdown]/`.timeClass`/
 * `.negotiatedTotal`/`.movingSeconds`/`.distanceKm`) rather than the whole `FareState` (W5
 * recomposition-hygiene pass, 2026-09-12) — this drawer is open on demand (see [ControlsHandle]'s
 * doc) but, while it IS open, a whole-`FareState` parameter recomposed it on every one-second tick
 * regardless of whether anything it actually renders changed (parked with the drawer open,
 * currentSpeedKmh/waitingSeconds still tick every second with nothing here depending on either).
 * Narrowing to exactly the fields [NightFareTile]/[FareBreakdownCard]/[TripDetailsCard] read below
 * lets Compose skip this whole subtree on a tick that doesn't move any of them — bundled into one
 * class, matching [MeterActions]' own precedent just below, rather than five separate parameters
 * (detekt's `LongParameterList` rule, same reasoning that class's doc gives).
 */
@Composable
internal fun ControlsDrawer(
    fareInfo: ControlsDrawerFareInfo,
    tripContext: TripContext?,
    /** The charging tariff for a resumed fare, when [tripContext] is gone -- see HiredScreen. */
    tariffFallback: TariffDto? = null,
    startAtIso: String?,
    hasDestination: Boolean,
    breakdownExpanded: Boolean,
    onToggleBreakdown: () -> Unit,
    actions: MeterActions,
    onDismiss: () -> Unit,
) {
    // Not a destructuring `val (a, b, c, d, e) = fareInfo` (detekt's
    // DestructuringDeclarationWithTooManyEntries caps that at 3) — plain property reads, same
    // values, one line each.
    val breakdown = fareInfo.breakdown
    val timeClass = fareInfo.timeClass
    val negotiatedTotal = fareInfo.negotiatedTotal
    val movingSeconds = fareInfo.movingSeconds
    val distanceKm = fareInfo.distanceKm
    Column(
        modifier = Modifier
            .width(440.dp)
            .heightIn(max = 600.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Controls", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.weight(1f))
            // 22dp -> a 48dp target (A4, 2026-09-08). The icon stays 22dp; what changed is that
            // the CLICKABLE is now the 48dp box around it rather than the glyph itself. The audit
            // (§6) listed this as "22dp icon, clickable directly" — the smallest hit area on the
            // screen, on the only control that closes this panel.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Close controls",
                    tint = CaptainPalette.textMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            NightFareTile(timeClass = timeClass, tariff = (tripContext?.tariff ?: tariffFallback))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SetPriceTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
                AddTollTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PauseFareTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
                MoreTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
            }
            Spacer(Modifier.height(8.dp))
            FareBreakdownCard(
                title = if (hasDestination) "FARE DETAILS" else "FARE BREAKDOWN",
                breakdown = breakdown,
                timeClass = timeClass,
                nightMultiplierLabel = nightMultiplierLabel((tripContext?.tariff ?: tariffFallback)),
                expanded = breakdownExpanded,
                onToggle = onToggleBreakdown,
                negotiatedTotal = negotiatedTotal,
            )
            // Dropped once the map panel already carries the same PICK UP/DESTINATION pair — the
            // two must never show the same address/time twice.
            if (!hasDestination) {
                Spacer(Modifier.height(8.dp))
                TripDetailsCard(
                    tripContext = tripContext,
                    movingSeconds = movingSeconds,
                    distanceKm = distanceKm,
                    startAtIso = startAtIso,
                )
            }
            Spacer(Modifier.height(8.dp))
            AccrualNote()
        }
    }
}
