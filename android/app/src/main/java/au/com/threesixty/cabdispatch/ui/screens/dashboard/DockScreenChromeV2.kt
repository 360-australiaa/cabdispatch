package au.com.threesixty.cabdispatch.ui.screens.dashboard

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.R
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TodayStats
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.screens.profile.ProfilePhotoUiState
import au.com.threesixty.cabdispatch.ui.screens.profile.ProfileViewModel
import au.com.threesixty.cabdispatch.ui.screens.messages.MessagesViewModel
import au.com.threesixty.cabdispatch.ui.theme.WheelColorsV2
import au.com.threesixty.cabdispatch.ui.wheel.WheelSlot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase B v2 — shared full-screen chrome (map/gradient backdrop + top status chips + bottom dock)
 * for the dock-menu destinations that are their OWN nav-graph route rather than a
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot] hosted inside
 * [WheelDashboardScreen]'s `WheelSlotContentSheet` — i.e. [au.com.threesixty.cabdispatch.ui.screens.zones.PlotZoneScreen],
 * [au.com.threesixty.cabdispatch.ui.screens.zones.ZoneStatisticsScreen], and the new Navigate
 * placeholder. Ports [HomeDashboardV2ChromeOverlay]'s `GlassPanel`/`BrandStatusChip`/`DockTile`
 * patterns (same tokens, same "solid scrim instead of real blur" approximation — see
 * [WheelColorsV2]'s doc) rather than inventing a second visual language, since these screens sit
 * one tap away from the same dashboard and must look like the same app.
 *
 * These 3 screens are reached via a plain `composable(route) { Screen(navController) }` entry in
 * [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost] — no [WheelDashboardUiState] is
 * threaded down to them the way it is inside [WheelDashboardScreen] itself. Rather than plumbing
 * that shared state through the nav graph (a materially larger change than a visual reskin, and
 * one that would touch shared nav infra other passes may also be editing), [DockChromeViewModel]
 * below re-derives the same handful of fields from the same real sources
 * ([SessionHolder.session], [AppContainer.tripStatsRepository], GPS/network system services) —
 * mirroring the existing precedent of [AvailableTripsWheelContent]/[MessagesWheelContent] etc. each
 * spinning up their own small `viewModel()` instance rather than sharing one across screens.
 */
class DockChromeViewModel(application: Application) : AndroidViewModel(application) {

    private val _status = MutableStateFlow(DockChromeStatus())
    val status: StateFlow<DockChromeStatus> = _status.asStateFlow()

    val session: StateFlow<DriverSession?> = SessionHolder.session

    private val _todayStats = MutableStateFlow(TodayStats())
    val todayStats: StateFlow<TodayStats> = _todayStats.asStateFlow()

    init {
        // Re-subscribes to observeTodayStats(driverId) whenever the signed-in driver changes —
        // same "cancel + restart the inner collection on a new key" behavior flatMapLatest would
        // give, spelled out as a plain loop so no experimental-coroutines opt-in is needed for
        // what these 3 low-frequency screens need (a single always-current stats stream).
        viewModelScope.launch {
            var innerJob: kotlinx.coroutines.Job? = null
            session.collect { s ->
                innerJob?.cancel()
                val driverId = s?.driverId
                if (driverId == null) {
                    _todayStats.value = TodayStats()
                } else {
                    innerJob = launch {
                        AppContainer.tripStatsRepository.observeTodayStats(driverId).collect { _todayStats.value = it }
                    }
                }
            }
        }

        viewModelScope.launch {
            val context = getApplication<Application>()
            while (isActive) {
                _status.value = DockChromeStatus(
                    gpsOk = hasFineLocationPermission(context) && isLocationEnabled(context),
                    networkOk = hasActiveInternet(context),
                )
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun hasFineLocationPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return runCatching {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }.getOrDefault(false)
    }

    private fun hasActiveInternet(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = connectivityManager?.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    companion object {
        private const val STATUS_POLL_INTERVAL_MS = 5_000L
    }
}

data class DockChromeStatus(val gpsOk: Boolean = false, val networkOk: Boolean = false)

/** Which of the 6 dock tiles is "active" (highlighted gold) on the current screen. The 7th
 * ("Navigate") was deleted 2026-09-05 — see [dockTilesV2]'s doc. */
enum class DockDestination { MY_TRIPS, PLOT, AVAILABLE_TRIPS, STATISTICS, MESSAGES, HISTORY }

/**
 * Full-bleed v2 chrome: page-gradient background ([WheelColorsV2.pageBackgroundBrush] — these 3
 * screens have no live map the way the dashboard/Navigate mock does, see each call site's own
 * doc), top status/earnings/clock chips, the "Panel / Content" glass card ([content]), and the
 * bottom dock with [active] highlighted. Every dock tap navigates via the exact same routes
 * [HomeDashboardV2ChromeOverlay]'s `dockTiles` already uses — this is the one other place those
 * 6 destinations are wired from, so both call sites are kept in sync by inspection.
 */
@Composable
fun DockScreenScaffoldV2(
    navController: NavHostController,
    active: DockDestination,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    panelWidth: Dp = 720.dp,
    content: @Composable () -> Unit,
) {
    val chromeViewModel: DockChromeViewModel = viewModel()
    val session by chromeViewModel.session.collectAsState()
    val stats by chromeViewModel.todayStats.collectAsState()
    val status by chromeViewModel.status.collectAsState()
    val messagesViewModel: MessagesViewModel = viewModel()
    val messagesState by messagesViewModel.uiState.collectAsState()
    var dockExpanded by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WheelColorsV2.pageBackgroundBrush),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            DockBrandStatusChip(gpsOk = status.gpsOk, networkOk = status.networkOk)
            DockEarningsChip(stats = stats)
            DockClockSessionChip(
                onLogOffClick = { navController.navigate(CabDispatchRoutes.SHIFT_REPORT) },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 72.dp, top = 94.dp)
                .width(panelWidth),
        ) {
            DockPanelContent(title = title, subtitle = subtitle, content = content)
        }

        DockDriverIdentityChip(
            session = session,
            onClick = { navController.navigate(CabDispatchRoutes.PROFILE) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = if (dockExpanded) 132.dp else 96.dp),
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            if (dockExpanded) {
                DockBarV2(
                    navController = navController,
                    active = active,
                    unreadCount = messagesState.unreadCount,
                    onHideMenu = { dockExpanded = false },
                )
            } else {
                DockCollapsedMenuPillV2(
                    unreadCount = messagesState.unreadCount,
                    onClick = { dockExpanded = true },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Glass panel (shared visual primitive — same approximation as HomeDashboardV2ChromeOverlay's).
// ---------------------------------------------------------------------------------------------

@Composable
fun DockGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    fill: Color = WheelColorsV2.glassPanel,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(fill)
            .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(cornerRadius))
            .border(1.dp, WheelColorsV2.glassInnerHighlight, RoundedCornerShape(cornerRadius)),
    ) {
        content()
    }
}

@Composable
private fun DockPanelContent(title: String, subtitle: String, content: @Composable () -> Unit) {
    DockGlassPanel(cornerRadius = 24.dp, fill = WheelColorsV2.panelGlass) {
        Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 26.dp)) {
            Text(title, color = Color.White.copy(alpha = 0.96f), fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Top chips — same visual spec as HomeDashboardV2ChromeOverlay's, factored out so both the
// dashboard's own chrome and these standalone screens render pixel-identical chips.
// ---------------------------------------------------------------------------------------------

@Composable
private fun DockBrandStatusChip(gpsOk: Boolean, networkOk: Boolean) {
    DockGlassPanel(cornerRadius = 18.dp) {
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
            Box(modifier = Modifier.width(1.dp).height(16.dp).background(WheelColorsV2.glassBorder))
            DockStatusDot(label = "GPS", ok = gpsOk)
            DockStatusDot(label = "4G", ok = networkOk)
        }
    }
}

@Composable
private fun DockStatusDot(label: String, ok: Boolean) {
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

@Composable
private fun DockEarningsChip(stats: TodayStats) {
    DockGlassPanel(cornerRadius = 999.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DockClockSessionChip(onLogOffClick: () -> Unit) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH) }

    DockGlassPanel(cornerRadius = 18.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                now.format(formatter),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(WheelColorsV2.dangerBg)
                    .border(1.dp, WheelColorsV2.dangerBorder, RoundedCornerShape(14.dp))
                    .clickable(onClick = onLogOffClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("LOG OFF", color = WheelColorsV2.dangerText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

/**
 * **2026-08-27 fidelity pass — driver photo + tap-through**, same feature/reasoning as
 * [HomeDashboardV2.DriverIdentityChip]'s own doc: the design brief specs this card as
 * "tappable, opens Profile" with a real avatar/photo, neither of which existed here before. A
 * real uploaded photo ([au.com.threesixty.cabdispatch.ui.screens.profile.ProfilePhotoUiState.Loaded])
 * takes priority; the `dummy_driver_photo` placeholder is the fallback. [onClick] is wired to
 * `CabDispatchRoutes.PROFILE` by [DockScreenScaffoldV2] above.
 */
@Composable
private fun DockDriverIdentityChip(session: DriverSession?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val remaining = remember(session?.shiftStartAt) { ShiftDurationLimit.remaining(session?.shiftStartAt) }
    val profileViewModel: ProfileViewModel = viewModel()
    val photoState by profileViewModel.photoState.collectAsState()
    DockGlassPanel(modifier = modifier.clickable(onClick = onClick), cornerRadius = 20.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(WheelColorsV2.steelTileBrush)
                    .border(1.5.dp, WheelColorsV2.goldCtaTop.copy(alpha = 0.75f), CircleShape),
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
                        contentDescription = "Driver photo placeholder",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
            }
            Column {
                Text(session?.driverName ?: "Not signed in", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    session?.driverId?.let {
                        Text("Driver $it", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    session?.vehicleId?.let {
                        Text("Vehicle $it", color = WheelColorsV2.amberFigure, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (remaining != null) {
                    val overdue = remaining.isNegative
                    val magnitude = if (overdue) remaining.negated() else remaining
                    Text(
                        if (overdue) {
                            "${magnitude.toHours()}h ${magnitude.toMinutes() % 60}m over shift limit"
                        } else {
                            "${magnitude.toHours()}h ${magnitude.toMinutes() % 60}m left on shift"
                        },
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------------------------
// Dock bar — same 7 tiles/targets as HomeDashboardV2ChromeOverlay's dockTiles, with real 24dp
// vector icons (Icons.Filled/AutoMirrored core set, already transitively on the classpath via
// material3 — confirmed no new Gradle dependency needed) instead of Wave 1's emoji placeholders,
// per this pass's "no emoji, 24-grid 2px stroke" icon spec. Figma's `icon/*` glyphs have no
// Code Connect mapping onto an existing project icon component (this app ships no icon set of its
// own — grepped res/drawable and the whole ui/ tree, only launcher art exists), so these are the
// closest-matching stand-ins from Compose's built-in icon set rather than fetched Figma SVGs, per
// the task brief's own fallback allowance.
// ---------------------------------------------------------------------------------------------

@Composable
private fun DockBarV2(
    navController: NavHostController,
    active: DockDestination,
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

        DockGlassPanel(cornerRadius = 24.dp) {
            LazyRow(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(dockTilesV2(navController, active, unreadCount)) { tile -> DockTileV2(tile) }
            }
        }
    }
}

private data class DockTileSpecV2(
    val label: String,
    val icon: ImageVector,
    val active: Boolean,
    val badge: Int? = null,
    val onClick: () -> Unit,
)

/**
 * Same 6 destinations/targets as [HomeDashboardV2ChromeOverlay]'s `dockTiles` — see that
 * function's doc for the full "why these route mappings" rationale (this app's wheel only has 6
 * fixed slots; My Trips and History both point at the one real Trips screen). Kept in sync with
 * that function by inspection since both are small and cater to the same 6-destination contract;
 * a future pass could factor this list out to one shared source if a third call site appears.
 *
 * 2026-09-05 audit: the "Navigate" tile (which used to route to [CabDispatchRoutes.IDLE], not the
 * now-deleted `NavigatePlaceholderScreen` destination) was removed along with that screen — see
 * [HomeDashboardV2ChromeOverlay]'s `dockTiles` doc for the confirmed-dead-code finding.
 */
private fun dockTilesV2(
    navController: NavHostController,
    active: DockDestination,
    unreadCount: Int,
): List<DockTileSpecV2> = listOf(
    DockTileSpecV2(
        "My Trips",
        Icons.AutoMirrored.Filled.List,
        active = active == DockDestination.MY_TRIPS,
        onClick = {
            navController.navigate(CabDispatchRoutes.IDLE) { launchSingleTop = true }
        },
    ),
    DockTileSpecV2(
        "Plot",
        Icons.Filled.LocationOn,
        active = active == DockDestination.PLOT,
        onClick = {
            if (active != DockDestination.PLOT) navController.navigate(CabDispatchRoutes.PLOT_ZONE)
        },
    ),
    DockTileSpecV2(
        "Avail. Trips",
        Icons.Filled.Search,
        active = active == DockDestination.AVAILABLE_TRIPS,
        onClick = {
            navController.navigate(CabDispatchRoutes.IDLE) { launchSingleTop = true }
        },
    ),
    DockTileSpecV2(
        "Statistics",
        Icons.Filled.DateRange,
        active = active == DockDestination.STATISTICS,
        onClick = {
            if (active != DockDestination.STATISTICS) navController.navigate(CabDispatchRoutes.ZONE_STATISTICS)
        },
    ),
    DockTileSpecV2(
        "Messages",
        Icons.Filled.MailOutline,
        active = active == DockDestination.MESSAGES,
        badge = unreadCount.takeIf { it > 0 },
        onClick = {
            navController.navigate(CabDispatchRoutes.IDLE) { launchSingleTop = true }
        },
    ),
    DockTileSpecV2(
        "History",
        Icons.Filled.Refresh,
        active = active == DockDestination.HISTORY,
        onClick = {
            navController.navigate(CabDispatchRoutes.IDLE) { launchSingleTop = true }
        },
    ),
)

@Composable
private fun DockTileV2(spec: DockTileSpecV2) {
    val brush = if (spec.active) WheelColorsV2.goldCtaBrush else WheelColorsV2.steelTileBrush
    val tint = if (spec.active) WheelColorsV2.onGoldCta else WheelColorsV2.steelTileText
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(spec.icon, contentDescription = spec.label, tint = tint, modifier = Modifier.size(24.dp))
            Text(
                spec.label,
                color = tint,
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
private fun DockCollapsedMenuPillV2(unreadCount: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(WheelColorsV2.glassPanel)
            .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
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
