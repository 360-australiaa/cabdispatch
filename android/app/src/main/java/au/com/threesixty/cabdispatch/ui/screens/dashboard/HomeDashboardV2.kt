package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.R
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
import au.com.threesixty.cabdispatch.domain.TodayStats
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel
import au.com.threesixty.cabdispatch.ui.screens.profile.ProfilePhotoUiState
import au.com.threesixty.cabdispatch.ui.screens.profile.ProfileViewModel
import au.com.threesixty.cabdispatch.ui.theme.WheelColorsV2
import au.com.threesixty.cabdispatch.ui.wheel.WheelSlot
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase B v2 Home dashboard chrome — reskins [WheelDashboardScreen]'s top/bottom overlays into
 * the approved Figma design (fileKey `JhEhok3n9bntRNS5Y1u3Yc`, node `25:3` expanded / `28:9`
 * collapsed) while reusing every existing state source (session, today's stats, tariff,
 * available/on-duty toggle, status strip, unread messages) and every existing navigation target
 * (the 6 [WheelSlot]s + the Plot/Zones entry point). This file owns ONLY the visual chrome drawn
 * on top of [MapBackground] — it does not replace [WheelDashboardViewModel] or any wheel-gesture
 * plumbing; [WheelDashboardScreen] decides whether to render this v2 chrome or the v1 wheel body.
 *
 * Two states (dock expanded / collapsed) are toggled by a local `remember` boolean per the task
 * brief — TODO(future screen owner): promote this to persisted state (e.g. DataStore) once a
 * product decision on "should this remember across app restarts" is made; out of scope for this
 * visual pass.
 */
@Composable
fun HomeDashboardV2ChromeOverlay(
    uiState: WheelDashboardUiState,
    navController: NavHostController,
    onStartMeterClick: () -> Unit,
    onZonesClick: () -> Unit,
    // `isHistoryVariant`: true when opened from the "History" dock tile rather than "My Trips" —
    // both resolve to the same WheelSlot.TRIPS real data source (see dockTiles' own doc for why
    // this app has no second trips screen), but the v2 reskin (2026-08-26 dock-menu pass) presents
    // two different layouts for that one real dataset (My Trips' focused active/recent cards vs
    // History's filterable flat list, matching Figma nodes 34:2 / 35:170) — this flag is how
    // WheelDashboardScreen's WheelSlotContentSheet picks which one to render.
    onSelectWheelSlot: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dockExpanded by remember { mutableStateOf(true) }
    // Real unread-message count (MessagesViewModel.uiState.unreadCount, the same computed
    // property MessagesWheelContent already reads) — a second, independent MessagesViewModel
    // instance scoped to this composable, same "plain ViewModel() via viewModel()" pattern
    // AvailableTripsWheelContent/MessagesWheelContent already use elsewhere on this screen.
    val messagesViewModel: MessagesViewModel = viewModel()
    val messagesState by messagesViewModel.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        // Top row: brand/status chip, earnings chip, clock/session chip.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            BrandStatusChip(gpsOk = uiState.status.gpsOk, networkOk = uiState.status.networkOk)
            EarningsChip(stats = uiState.todayStats)
            ClockSessionChip(
                onLogOffClick = {
                    // Phase B v2 pass (Figma "36 · Log Off", node 20:137): now routes to a real
                    // confirmation screen first, rather than jumping straight to Shift Report —
                    // see au.com.threesixty.cabdispatch.ui.screens.logoff.LogOffScreen's doc.
                    // That screen's own LOG OFF action still lands on the exact same
                    // SHIFT_REPORT destination this used to navigate to directly.
                    navController.navigate(CabDispatchRoutes.LOG_OFF)
                },
            )
        }

        // Bottom-left stack: driver identity chip above the ON DUTY CTA.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = if (dockExpanded) 132.dp else 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DutyCta(isAvailable = uiState.isAvailable, onClick = { onStartMeterClick() })
            DriverIdentityChip(
                session = uiState.session,
                onClick = { navController.navigate(CabDispatchRoutes.PROFILE) },
            )
        }

        // Right side: Trip Focus card — only when a real active/booked trip exists. v1 has no
        // ambient "currently booked" trip concept on this screen (JobOfferHandoff is a transient
        // accept/decline hand-off, not a live trip-focus state; TripContext/SessionHolder.pendingTrip
        // only exists mid-transition into the meter screen). Per the task brief, since there is
        // genuinely no such state to read here, this card does not render — matching v1's
        // equivalent "no trip" behavior (StatusCard shows Available/Off Duty only, no fake trip
        // card). TODO(product decision needed if a live "booked" trip concept is desired here):
        // wire this to a real dispatch-push job-accepted state once one exists.

        // Bottom-center: dock (expanded) or single Menu pill (collapsed).
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            if (dockExpanded) {
                DockBar(
                    navController = navController,
                    onZonesClick = onZonesClick,
                    onSelectWheelSlot = onSelectWheelSlot,
                    unreadCount = messagesState.unreadCount,
                    onHideMenu = { dockExpanded = false },
                )
            } else {
                CollapsedMenuPill(
                    unreadCount = messagesState.unreadCount,
                    onClick = { dockExpanded = true },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared "glass" surface — solid scrim approximation, see WheelColorsV2's doc for why.
// ---------------------------------------------------------------------------------------------

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(WheelColorsV2.glassPanel)
            .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(cornerRadius))
            // Approximates the spec's "inset 0 1px 1px rgba(255,255,255,0.12)" inner top glow —
            // a thin brighter top border reads close enough to a top inner-highlight without a
            // real inset-shadow API in Compose.
            .border(
                width = 1.dp,
                color = WheelColorsV2.glassInnerHighlight,
                shape = RoundedCornerShape(cornerRadius),
            ),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Top-left — Chip / Brand + Status
// ---------------------------------------------------------------------------------------------

@Composable
private fun BrandStatusChip(gpsOk: Boolean, networkOk: Boolean) {
    GlassPanel {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(WheelColorsV2.goldCtaBrush),
                contentAlignment = Alignment.Center,
            ) {
                Text("CD", color = WheelColorsV2.onGoldCta, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
            Text("CAB DISPATCH", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(WheelColorsV2.glassBorder),
            )
            // Real GPS-fix-available state: DashboardStatusStrip.gpsOk (permission granted AND
            // location provider enabled — WheelDashboardViewModel.pollStatus/isLocationEnabled),
            // the same signal the v1 top strip's "GPS" StatusChip already reads.
            StatusDot(label = "GPS", ok = gpsOk)
            // Real connectivity state: DashboardStatusStrip.networkOk, backed by
            // ConnectivityManager.getNetworkCapabilities(NET_CAPABILITY_INTERNET) in
            // WheelDashboardViewModel.hasActiveInternet — not a stub.
            StatusDot(label = "4G", ok = networkOk)
        }
    }
}

@Composable
private fun StatusDot(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (ok) WheelColorsV2.greenCtaTop else WheelColorsV2.dangerText),
        )
        Text(label, color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------------------------
// Top-center — NAV_EARNINGS pill
// ---------------------------------------------------------------------------------------------

@Composable
private fun EarningsChip(stats: TodayStats) {
    // Real shift-earnings/trip-count state: WheelDashboardUiState.todayStats, sourced from
    // AppContainer.tripStatsRepository.observeTodayStats (see WheelDashboardViewModel) — the same
    // source the v1 QuickStatsCard already reads. "THIS SHIFT" label matches the design; the
    // underlying stat is today's totals (this app has no separate shift-vs-day boundary today —
    // see TodayStats' own doc), not a fabricated distinct shift figure.
    GlassPanel {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stats.earningsTotal.toMoneyString(),
                color = WheelColorsV2.amberFigure,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
            )
            Text(
                "THIS SHIFT · ${stats.tripsCount} TRIPS",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Top-right — Chip / Clock + Session
// ---------------------------------------------------------------------------------------------

@Composable
private fun ClockSessionChip(onLogOffClick: () -> Unit) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH) }

    GlassPanel {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                now.format(formatter),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(WheelColorsV2.dangerBg)
                    .border(1.dp, WheelColorsV2.dangerBorder, RoundedCornerShape(999.dp))
                    .clickable(onClick = onLogOffClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("LOG OFF", color = WheelColorsV2.dangerText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom-left — Chip / Driver Identity
// ---------------------------------------------------------------------------------------------

/**
 * **2026-08-27 fidelity pass — driver photo + tap-through:** the original design brief
 * (`docs/TCT-DRIVER-APP-01.md` §5) specs this exact card as "avatar/photo, name, driver ID,
 * vehicle rego — tappable, opens Profile" — neither half of that was true before this pass (no
 * photo, not clickable), even though [au.com.threesixty.cabdispatch.ui.screens.profile.ProfileViewModel]'s
 * real photo-capture/upload flow has existed since 2026-08-10 (see [ProfileScreen]'s
 * `IdentityHeader`) with nowhere on the dashboard to show its result. Wired here: a real uploaded
 * photo (once [ProfilePhotoUiState.Loaded]) takes priority; `dummy_driver_photo` (a placeholder
 * illustrated headshot — see that drawable's own doc for why a real photo asset didn't already
 * exist) is the fallback shown otherwise, replacing the old initials-only circle. Tapping the
 * chip now calls [onClick] (wired to `CabDispatchRoutes.PROFILE`), matching the brief's "opens
 * Profile" spec — Profile already *is* "more details": full name, driver ID, licence, authority,
 * vehicle, and the compliance-document list, all real backend data, not fabricated for this pass.
 */
@Composable
private fun DriverIdentityChip(session: DriverSession?, onClick: () -> Unit) {
    val remaining = remember(session?.shiftStartAt) { ShiftDurationLimit.remaining(session?.shiftStartAt) }
    val profileViewModel: ProfileViewModel = viewModel()
    val photoState by profileViewModel.photoState.collectAsState()
    GlassPanel(cornerRadius = 20.dp, modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).widthIn(max = 280.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DriverAvatar(photoState = photoState, driverName = session?.driverName, size = 52.dp)
            Column {
                Text(
                    session?.driverName ?: "Not signed in",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Text(
                    buildString {
                        session?.driverId?.let { append("Driver $it") }
                    },
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp,
                )
                session?.vehicleId?.let { vehicleId ->
                    Text(
                        "Vehicle $vehicleId",
                        color = WheelColorsV2.amberFigure,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Real shift-remaining state: ShiftDurationLimit.remaining(session.shiftStartAt),
                // the same computation the v1 top strip's ShiftCountdownChip already uses.
                if (remaining != null) {
                    val overdue = remaining.isNegative
                    val magnitude = if (overdue) remaining.negated() else remaining
                    Text(
                        if (overdue) {
                            "${magnitude.toHours()}h ${magnitude.toMinutes() % 60}m over shift limit"
                        } else {
                            "${magnitude.toHours()}h ${magnitude.toMinutes() % 60}m left on shift"
                        },
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/**
 * Shared driver-avatar renderer (dashboard identity chip + [au.com.threesixty.cabdispatch.ui.screens.dashboard.DockDriverIdentityChip]
 * use this same logic, see that file's own copy) — a real uploaded photo takes priority; falls
 * back to `dummy_driver_photo` (a placeholder illustrated headshot, not a real photo — see that
 * drawable's own doc) rather than the old text-initials circle.
 */
@Composable
private fun DriverAvatar(photoState: ProfilePhotoUiState, driverName: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(WheelColorsV2.goldCtaBrush)
            .border(2.dp, WheelColorsV2.goldCtaTop, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val loadedPhoto = photoState as? ProfilePhotoUiState.Loaded
        if (loadedPhoto != null) {
            Image(
                bitmap = loadedPhoto.bitmap.asImageBitmap(),
                contentDescription = "Driver photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.dummy_driver_photo),
                contentDescription = if (driverName != null) "Driver photo placeholder for $driverName" else "Driver photo placeholder",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}


// ---------------------------------------------------------------------------------------------
// Bottom-left, above identity — CTA_DUTY
// ---------------------------------------------------------------------------------------------

/**
 * ON DUTY / OFF DUTY toggle CTA — wired to the exact same [WheelDashboardUiState.isAvailable] +
 * [WheelDashboardViewModel.setAvailable] the v1 dashboard's status card/Switch already drove (see
 * [WheelDashboardScreen]'s `OffDutyAvailableContent`), not a new business action.
 */
@Composable
private fun DutyCta(isAvailable: Boolean, onClick: () -> Unit) {
    val brush = if (isAvailable) WheelColorsV2.greenCtaBrush else WheelColorsV2.steelTileBrush
    val textColor = if (isAvailable) WheelColorsV2.onGreenCta else WheelColorsV2.steelTileText
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(brush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Bevel highlight overlay (see WheelColorsV2.bevelHighlightBrush doc).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(999.dp))
                .background(WheelColorsV2.bevelHighlightBrush),
        )
        Text(
            if (isAvailable) "● ON DUTY" else "● GO ON DUTY",
            color = textColor,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom-center — Dock / Menu (expanded) + collapsed Menu pill
// ---------------------------------------------------------------------------------------------

@Composable
private fun DockBar(
    navController: NavHostController,
    onZonesClick: () -> Unit,
    onSelectWheelSlot: (Int, Boolean) -> Unit,
    unreadCount: Int,
    onHideMenu: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.End) {
        Box(
            modifier = Modifier
                .align(Alignment.End)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(WheelColorsV2.glassPanel)
                .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(999.dp))
                .clickable(onClick = onHideMenu)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("HIDE MENU", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        GlassPanel(cornerRadius = 24.dp) {
            LazyRow(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(dockTiles(navController, onZonesClick, onSelectWheelSlot, unreadCount)) { tile ->
                    DockTile(tile)
                }
            }
        }
    }
}

/**
 * The 7 dock tiles, mapped to real navigation targets. This app's wheel has only 6 fixed
 * [WheelSlot]s (a hardcoded `SLOT_COUNT = 6`, see [au.com.threesixty.cabdispatch.ui.wheel.WheelState])
 * plus one further "demoted off the wheel" entry point (Plot/Zones, `CabDispatchRoutes.PLOT_ZONE`
 * — see that route constant's own doc). There is no distinct "History" or "Navigate" screen
 * anywhere in this codebase (confirmed: no such route in CabDispatchRoutes, no such composable
 * under ui/screens). Matched as closely as the real app structure allows:
 * - My Trips -> [WheelSlot.TRIPS] (trip history/detail — the closest existing match to "History")
 * - Plot -> the existing Zones/Plot entry point ([CabDispatchRoutes.PLOT_ZONE])
 * - Avail. Trips -> [WheelSlot.AVAILABLE_TRIPS]
 * - Statistics -> [CabDispatchRoutes.ZONE_STATISTICS] (the only "Statistics" screen this app has)
 * - Messages -> [WheelSlot.MESSAGES], badge wired to the real unread count
 * - History -> [WheelSlot.TRIPS] again (same real screen/data as "My Trips" — this app does not
 *   have two distinct trips data sources; presented with a different layout via the
 *   `isHistoryVariant` flag on [onSelectWheelSlot] — see [TripsWheelContent]'s `variant` doc — so
 *   the two dock tiles at least render Figma's two different screens, not literally the same pane)
 * - Navigate -> [CabDispatchRoutes.NAVIGATE_PLACEHOLDER] (2026-08-26 dock-menu v2 pass: this is a
 *   genuinely new destination, not a WheelSlot — Figma node `35:356` has no dock/chrome at all, a
 *   full-screen in-trip turn-by-turn overlay mockup, confirming it isn't meant to live inside
 *   [WheelSlotContentSheet] alongside the other 6.
 *   2026-09-04 audit note: the "no real turn-by-turn navigation feature anywhere in this codebase"
 *   claim this comment used to make is now FALSE — [au.com.threesixty.cabdispatch.ui.screens.hired.MeterNavViewModel]
 *   (real Mapbox search/route/ETA/voice/reroute) shipped and is wired into the meter screen
 *   ([au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen]) the same day. It is deliberately
 *   not plugged into this tile — see [au.com.threesixty.cabdispatch.ui.screens.navigate.NavigatePlaceholderScreen]'s
 *   own doc for why that's a product-scope question, not a wiring gap. Separately, this whole tile
 *   is unreachable in the live app: this composable ([HomeDashboardV2ChromeOverlay]) only renders
 *   from [WheelDashboardScreen], which itself has no call site anywhere — the live dashboard is
 *   [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen], which has no Navigate rail
 *   item at all.
 *
 * [WheelDashboardScreen] still tracks which slot is "displayed" (`displayedSlotIndex`, unchanged
 * from v1 — the wheel-gesture code used to drive it, dock taps drive it now) and opens
 * [WheelSlotContentSheet] to show that slot's real content composable when one is picked here —
 * [onSelectWheelSlot] is that screen's own `displayedSlotIndex = index` setter (plus the
 * `isHistoryVariant` flag above), passed down through [HomeDashboardV2ChromeOverlay].
 */
private fun dockTiles(
    navController: NavHostController,
    onZonesClick: () -> Unit,
    onSelectWheelSlot: (Int, Boolean) -> Unit,
    unreadCount: Int,
): List<DockTileSpec> = listOf(
    DockTileSpec("My Trips", "🧾", active = true, onClick = { onSelectWheelSlot(WheelSlot.TRIPS.index, false) }),
    DockTileSpec("Plot", "📍", onClick = onZonesClick),
    DockTileSpec("Avail. Trips", "📋", onClick = { onSelectWheelSlot(WheelSlot.AVAILABLE_TRIPS.index, false) }),
    DockTileSpec("Statistics", "📊", onClick = { navController.navigate(CabDispatchRoutes.ZONE_STATISTICS) }),
    DockTileSpec(
        "Messages",
        "✉️",
        badge = unreadCount.takeIf { it > 0 },
        onClick = { onSelectWheelSlot(WheelSlot.MESSAGES.index, false) },
    ),
    DockTileSpec("History", "🕘", onClick = { onSelectWheelSlot(WheelSlot.TRIPS.index, true) }),
    DockTileSpec("Navigate", "🧭", onClick = { navController.navigate(CabDispatchRoutes.NAVIGATE_PLACEHOLDER) }),
)

private data class DockTileSpec(
    val label: String,
    val icon: String,
    val active: Boolean = false,
    val badge: Int? = null,
    val onClick: () -> Unit,
)

@Composable
private fun DockTile(spec: DockTileSpec) {
    val brush = if (spec.active) WheelColorsV2.goldCtaBrush else WheelColorsV2.steelTileBrush
    val textColor = if (spec.active) WheelColorsV2.onGoldCta else WheelColorsV2.steelTileText
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(brush)
            .then(
                if (spec.active) {
                    Modifier.border(1.5.dp, WheelColorsV2.activeTileBorder, RoundedCornerShape(20.dp))
                } else {
                    Modifier.border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(20.dp))
                },
            )
            .clickable(onClick = spec.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(spec.icon, fontSize = 22.sp)
            Text(
                spec.label,
                color = textColor,
                fontSize = 10.sp,
                fontWeight = if (spec.active) FontWeight.Bold else FontWeight.Medium,
            )
        }
        if (spec.badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(WheelColorsV2.dangerText),
                contentAlignment = Alignment.Center,
            ) {
                Text(spec.badge.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CollapsedMenuPill(unreadCount: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(WheelColorsV2.glassPanel)
            .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("☰", color = Color.White, fontSize = 16.sp)
            Text("MENU", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(WheelColorsV2.dangerText),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(unreadCount.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
