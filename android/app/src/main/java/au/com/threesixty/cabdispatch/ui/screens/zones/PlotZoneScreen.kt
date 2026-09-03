package au.com.threesixty.cabdispatch.ui.screens.zones

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.ZoneDto
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * 25 · Plot — Zones — reskinned onto [CaptainPalette] (2026-08-29 purple migration pass).
 * [PlotZoneViewModel]/[PlotZoneUiState] and every plot/unplot/refresh action are unchanged —
 * layout/tokens only.
 *
 * Chrome note: this standalone route has no live source for a persistent status strip (it belongs
 * to the home shell's WheelDashboardViewModel), so per the detail-screen convention the screen owns
 * the whole canvas without chrome, with an outline "Dashboard" back affordance kept on both states.
 *
 * Honesty notes:
 * - The per-zone "Queue 4 · 12 bookings/hr" captions and a "You are #3 of 3" position have no
 *   backing fields here ([ZoneDto] carries name/number/geometry only, and the plot response
 *   carries no queue position) — cards show the real zone number badge + name, and the plotted
 *   card says "Currently plotted" plainly. Live demand lives on the Statistics screen.
 * - More than 6 zones scroll INSIDE the fixed-height grid container (the page itself never
 *   scrolls vertically).
 */
@Composable
fun PlotZoneScreen(
    navController: NavHostController,
    viewModel: PlotZoneViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .padding(start = 72.dp, end = 72.dp, top = 44.dp, bottom = 36.dp),
    ) {
        when (val s = state) {
            is PlotZoneUiState.Loading -> {
                PlotHeader(plottedZone = null, showPill = false)
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CaptainPalette.accent)
                }
                BackRow(navController)
            }
            is PlotZoneUiState.Error -> {
                PlotHeader(plottedZone = null, showPill = false)
                Spacer(Modifier.height(20.dp))
                EmptyStateCard(
                    modifier = Modifier.weight(1f),
                    title = "Couldn't load zones",
                    body = s.message,
                    buttonText = "RETRY",
                    onButtonClick = viewModel::refresh,
                )
                Spacer(Modifier.height(16.dp))
                BackRow(navController)
            }
            is PlotZoneUiState.Loaded -> {
                val plottedZone = s.zones.firstOrNull { it.id == s.plottedZoneId }
                PlotHeader(plottedZone = plottedZone, showPill = plottedZone != null)
                Spacer(Modifier.height(20.dp))

                if (s.error != null) {
                    Text(s.error, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.danger)
                    Spacer(Modifier.height(10.dp))
                }

                if (s.zones.isEmpty()) {
                    // 25b — empty state.
                    EmptyStateCard(
                        modifier = Modifier.weight(1f),
                        title = "No zones published for this region yet",
                        body = "Your operator has not defined dispatch zones for the area you are in, or the " +
                            "zone list is still syncing. You can still receive direct job offers and street " +
                            "hails while unplotted.",
                        buttonText = "REFRESH ZONES",
                        onButtonClick = viewModel::refresh,
                    )
                    Spacer(Modifier.height(16.dp))
                    BackRow(navController)
                } else {
                    // Populated — 3-column grid of 220dp zone cards. Scrolls internally only when
                    // the list outgrows the fixed region (>6 zones).
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(s.zones, key = { it.id }) { zone ->
                            ZoneCard(
                                zone = zone,
                                plotted = zone.id == s.plottedZoneId,
                                busy = s.busy,
                                onPlot = { viewModel.plotInto(zone) },
                                onUnplot = viewModel::unplot,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Bottom row + the kept outline back affordance.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CaptainButton(text = "Dashboard", outline = true, modifier = Modifier.width(200.dp)) {
                            navController.popBackStack()
                        }
                        Box(
                            modifier = Modifier
                                .width(300.dp)
                                .height(64.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(CaptainPalette.raised)
                                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                                .clickable { navController.navigate(CabDispatchRoutes.ZONE_STATISTICS) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "VIEW ZONE STATISTICS",
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = CaptainPalette.accent,
                            )
                        }
                        Text(
                            "Plotting joins the zone queue — jobs offer to queue position #1 first.",
                            fontFamily = InterFamily,
                            fontSize = 14.sp,
                            color = CaptainPalette.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlotHeader(plottedZone: ZoneDto?, showPill: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Plot into a zone",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            color = CaptainPalette.textPrimary,
        )
        Spacer(Modifier.weight(1f))
        if (showPill && plottedZone != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CaptainPalette.success.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CaptainPalette.success, modifier = Modifier.size(16.dp))
                Text(
                    "Plotted: ${plottedZone.number} — ${plottedZone.name}",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = CaptainPalette.success,
                )
            }
        }
    }
}

@Composable
private fun BackRow(navController: NavHostController) {
    CaptainButton(text = "Dashboard", outline = true, modifier = Modifier.width(220.dp)) {
        navController.popBackStack()
    }
}

/**
 * Zone List tab content (`squishy-herding-iverson.md` Phase F) — embeds this screen's real
 * zone-grid + plot/unplot flow inside the new tabbed Zones pane
 * ([au.com.threesixty.cabdispatch.ui.screens.zones.ZonesPaneContent]) rather than re-implementing
 * it: reuses [PlotZoneViewModel] and the same [ZoneCard]/[EmptyStateCard] composables
 * [PlotZoneScreen] itself uses, minus that standalone route's own title/back-row chrome (the tab
 * shell supplies its own). [PlotZoneScreen] and this route it lives on are otherwise untouched and
 * still reachable exactly as before.
 */
@Composable
fun PlotZoneTabContent(viewModel: PlotZoneViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    when (val s = state) {
        is PlotZoneUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CaptainPalette.accent)
        }
        is PlotZoneUiState.Error -> EmptyStateCard(
            modifier = Modifier.fillMaxSize(),
            title = "Couldn't load zones",
            body = s.message,
            buttonText = "RETRY",
            onButtonClick = viewModel::refresh,
        )
        is PlotZoneUiState.Loaded -> if (s.zones.isEmpty()) {
            EmptyStateCard(
                modifier = Modifier.fillMaxSize(),
                title = "No zones published for this region yet",
                body = "Your operator has not defined dispatch zones for the area you are in, or the " +
                    "zone list is still syncing. You can still receive direct job offers and street " +
                    "hails while unplotted.",
                buttonText = "REFRESH ZONES",
                onButtonClick = viewModel::refresh,
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                val plottedZone = s.zones.firstOrNull { it.id == s.plottedZoneId }
                if (plottedZone != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(CaptainPalette.success.copy(alpha = 0.12f))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CaptainPalette.success, modifier = Modifier.size(16.dp))
                            Text(
                                "Plotted: ${plottedZone.number} — ${plottedZone.name}",
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = CaptainPalette.success,
                            )
                        }
                    }
                }
                if (s.error != null) {
                    Text(s.error, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.danger)
                    Spacer(Modifier.height(10.dp))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(s.zones, key = { it.id }) { zone ->
                        ZoneCard(
                            zone = zone,
                            plotted = zone.id == s.plottedZoneId,
                            busy = s.busy,
                            onPlot = { viewModel.plotInto(zone) },
                            onUnplot = viewModel::unplot,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 220dp zone card: 52dp Chakra number badge + name, pinned bottom CTA. Plotted card flips to the
 * success-bordered variant with the danger-outline "PLOTTED · UNPLOT".
 */
@Composable
private fun ZoneCard(
    zone: ZoneDto,
    plotted: Boolean,
    busy: Boolean,
    onPlot: () -> Unit,
    onUnplot: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .height(220.dp)
            .clip(shape)
            .background(if (plotted) CaptainPalette.success.copy(alpha = 0.10f) else CaptainPalette.panel)
            .border(
                width = if (plotted) 2.dp else 1.dp,
                color = if (plotted) CaptainPalette.success else CaptainPalette.panelBorder,
                shape = shape,
            )
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (plotted) CaptainPalette.success else CaptainPalette.raised),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    zone.number,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Medium,
                    fontSize = 24.sp,
                    color = if (plotted) CaptainPalette.bg else CaptainPalette.accent,
                )
            }
            Text(
                zone.name,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = CaptainPalette.textPrimary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!plotted) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(15.dp))
            }
            Text(
                // No per-zone queue/demand fields exist on ZoneDto — see file doc.
                text = if (plotted) "Currently plotted" else "Tap to join this zone's queue",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = if (plotted) CaptainPalette.success else CaptainPalette.textSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (plotted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, CaptainPalette.danger.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .alpha(if (busy) 0.4f else 1f)
                    .clickable(enabled = !busy, onClick = onUnplot),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CaptainPalette.danger)
                } else {
                    Text(
                        "PLOTTED · UNPLOT",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = CaptainPalette.danger,
                    )
                }
            }
        } else {
            CaptainButton(
                text = "PLOT HERE",
                heightDp = 58,
                fontSize = 16.sp,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onPlot,
            )
        }
    }
}

/**
 * The dashed-border empty/error card (also reused for the load-error state). Compose has no
 * dashed border modifier, so the dash is drawn with a [PathEffect.dashPathEffect] rounded-rect
 * stroke.
 */
@Composable
private fun EmptyStateCard(
    modifier: Modifier = Modifier,
    title: String,
    body: String,
    buttonText: String,
    onButtonClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .drawBehind {
                drawRoundRect(
                    color = CaptainPalette.panelBorder,
                    cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(56.dp))
        Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
        Text(
            body,
            fontFamily = InterFamily,
            fontSize = 16.sp,
            color = CaptainPalette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(560.dp),
        )
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CaptainPalette.raised)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                .clickable(onClick = onButtonClick),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(18.dp))
                Text(buttonText, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.accent)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
