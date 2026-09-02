package au.com.threesixty.cabdispatch.ui.screens.earnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.EARNINGS] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Earnings: stat grid — today's total, card split, cash split, trip
 * count") — a direct Compose port of the reference prototype's Earnings `.stat-grid`
 * (docs/driver-dashboard-full-prototype.html lines ~359-365).
 *
 * Renders only the content-pane *body* (the stat grid itself), not the pane's eyebrow/hero title
 * chrome — same convention as [au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent],
 * which the wheel-dashboard host screen owns uniformly for every slot (spec §4 intro).
 *
 * See [EarningsWheelViewModel] for the data flow (same offline Room source the "Trips" wheel
 * content pane reads, aggregated to today's calendar day).
 *
 * **Captain Taxis purple reskin (2026-08-29):** this pane is hosted inside
 * [au.com.threesixty.cabdispatch.ui.theme.PaneShell]'s purple panel/back-arrow chrome (see
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `CaptainPane.EARNINGS`
 * branch), so this file's own tiles are now painted on
 * [au.com.threesixty.cabdispatch.ui.theme.CaptainPalette] instead of the legacy glass/gold
 * palette other wheel-content panes still use — a visual-only change, same
 * [EarningsWheelViewModel]/[EarningsWheelUiState] fields read below. The stat tiles are a
 * file-local [EarningsStatTile], no longer the shared, legacy-palette-painted `StatGrid` also
 * used by [au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftWheelContent] (that shared
 * composable and its host file are untouched here, since `ShiftWheelContent` still relies on it
 * and re-theming a shared file is outside this pass's one-file scope).
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * renders this composable for [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.EARNINGS] — see
 * that screen's `EarningsSlotContent`.
 */
@Composable
fun EarningsWheelContent(
    modifier: Modifier = Modifier,
    viewModel: EarningsWheelViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CaptainPalette.accent)
        }
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EarningsStatTile(
            icon = Icons.Rounded.AttachMoney,
            label = "TODAY",
            value = state.todayTotal.asMoney(),
            modifier = Modifier.weight(1f),
        )
        EarningsStatTile(
            icon = Icons.Rounded.CreditCard,
            label = "CARD",
            value = state.cardTotal.asMoney(),
            modifier = Modifier.weight(1f),
        )
        EarningsStatTile(
            icon = Icons.Rounded.Payments,
            label = "CASH",
            value = state.cashTotal.asMoney(),
            modifier = Modifier.weight(1f),
        )
        EarningsStatTile(
            icon = Icons.Rounded.DirectionsCar,
            label = "TRIPS",
            value = state.tripsCount.toString(),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One "value + label" stat card — mirrors [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s
 * established `NightFareTile`/`QuickActionTile` tile language (panel fill, 1dp panelBorder stroke,
 * 20dp rounded corners, accent-tinted icon over a semibold secondary-text label, then a large bold
 * primary-text value) rather than inventing a new card shape for this one pane.
 */
@Composable
private fun EarningsStatTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CaptainPalette.raised, RoundedCornerShape(20.dp))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
            .padding(vertical = 20.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(24.dp))
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            letterSpacing = 0.5.sp,
            color = CaptainPalette.textSecondary,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            value,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
