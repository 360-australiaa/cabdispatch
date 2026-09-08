package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
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
            fontSize = 10.sp,
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
 */
@Composable
internal fun ControlsDrawer(
    fareState: FareState,
    tripContext: TripContext?,
    startAtIso: String?,
    hasDestination: Boolean,
    breakdownExpanded: Boolean,
    onToggleBreakdown: () -> Unit,
    actions: MeterActions,
    onDismiss: () -> Unit,
) {
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
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close controls",
                tint = CaptainPalette.textMuted,
                modifier = Modifier.size(22.dp).clickable(onClick = onDismiss),
            )
        }
        Spacer(Modifier.height(14.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            NightFareTile(timeClass = fareState.timeClass, tariff = tripContext?.tariff)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SetPriceTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
                AddTollTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PauseFareTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
                MoreTile(actions, Modifier.weight(1f).height(NAV_TILE_H))
            }
            Spacer(Modifier.height(10.dp))
            FareBreakdownCard(
                title = if (hasDestination) "FARE DETAILS" else "FARE BREAKDOWN",
                breakdown = fareState.breakdown,
                timeClass = fareState.timeClass,
                nightMultiplierLabel = nightMultiplierLabel(tripContext?.tariff),
                expanded = breakdownExpanded,
                onToggle = onToggleBreakdown,
                negotiatedTotal = fareState.negotiatedTotal,
            )
            // Dropped once the map panel already carries the same PICK UP/DESTINATION pair — the
            // two must never show the same address/time twice.
            if (!hasDestination) {
                Spacer(Modifier.height(10.dp))
                TripDetailsCard(tripContext = tripContext, fareState = fareState, startAtIso = startAtIso)
            }
            Spacer(Modifier.height(8.dp))
            AccrualNote()
        }
    }
}
