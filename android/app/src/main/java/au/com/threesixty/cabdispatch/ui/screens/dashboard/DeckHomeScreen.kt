package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import au.com.threesixty.cabdispatch.data.remote.MapboxStaticImage
import au.com.threesixty.cabdispatch.data.remote.SydneyCbdFallback
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.deck.DeckScaffold
import au.com.threesixty.cabdispatch.ui.deck.DeckTab
import au.com.threesixty.cabdispatch.ui.deck.KpiTile
import au.com.threesixty.cabdispatch.ui.deck.StripStatus
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.ui.deck.DeckKeypad
import au.com.threesixty.cabdispatch.ui.screens.earnings.EarningsWheelContent
import au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftWheelContent
import au.com.threesixty.cabdispatch.ui.screens.trips.TripsWheelContent
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.DeckState
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent
import java.math.RoundingMode

/**
 * Command Deck home — the v2 replacement for the rotating-wheel dashboard (Figma
 * `h0PSsXQ971dOJvt25tN7BA` frames `13:30` 09·Off Duty / `13:133` 10·For Hire, plus the tab
 * frames 11–15 rendered in the content slot). Registered under the same `IDLE` route key, and
 * driven by the same [WheelDashboardViewModel] (session/availability/stats/tariff/status wiring
 * unchanged) — this file is layout only.
 *
 * Fixed regions: 44dp status strip (state pill + live chips) · 92dp nav rail (7 tabs + discreet
 * duress) · content slot per tab · 400dp drive panel (state card, driver row, KPI tiles,
 * availability toggle, permanent START METER / SET PRICE).
 *
 * Tab content note (staged port): STATUS renders the v2 map surface below; the other tabs mount
 * the existing self-contained content composables (each owns its ViewModel + real data wiring:
 * [AvailableTripsWheelContent], [MessagesWheelContent], [TripsWheelContent],
 * [EarningsWheelContent], [ShiftWheelContent]) inside a v2 panel shell — functionality first,
 * with their internal visual polish tracked against frames 11–15.
 */
@Composable
fun DeckHomeScreen(
    navController: NavHostController,
    viewModel: WheelDashboardViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(DeckTab.STATUS) }
    var showStartConfirm by rememberSaveable { mutableStateOf(false) }
    var showSetPrice by rememberSaveable { mutableStateOf(false) }
    // Real gap fix (2026-08-28): the dashboard's hidden duress dot called trigger() but never
    // rendered the resulting state — a duress event could fire with zero on-screen indication.
    // Same DuressController.state observation + overlay pair HiredScreen already uses.
    val duressState by AppContainer.duressController.state.collectAsState()

    val deckState = if (state.isAvailable) DeckState.FOR_HIRE else DeckState.OFF_DUTY
    val shiftLeft = ShiftDurationLimit.remaining(state.session?.shiftStartAt)?.let { d ->
        val h = d.toHours()
        val m = d.minusHours(h).toMinutes()
        "${h}h ${m.toString().padStart(2, '0')}m left"
    }

    DeckScaffold(
        status = StripStatus(
            state = deckState,
            shiftLeftLabel = shiftLeft,
            tariffSigned = state.tariff != null,
            gpsOk = state.status.gpsOk,
            netOk = state.status.networkOk,
            printerOk = state.status.printerOk,
            batteryPercent = state.status.batteryPercent,
        ),
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        onDuressLongPress = {
            AppContainer.duressController.trigger(state.session?.vehicleId, state.session?.driverId)
        },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (selectedTab) {
                    DeckTab.STATUS -> StatusMapPanel(onPlotZone = { selectedTab = DeckTab.ZONES })
                    DeckTab.JOBS -> TabShell("Job board") { AvailableTripsWheelContent(navController = navController) }
                    DeckTab.ZONES -> ZonesTab(navController)
                    DeckTab.MSGS -> TabShell("Messages") {
                        MessagesWheelContent(onOpenThread = { navController.navigate(CabDispatchRoutes.MESSAGES_THREAD) })
                    }
                    DeckTab.TRIPS -> TabShell("Trip history") {
                        TripsWheelContent(
                            onTripClick = { clientUuid ->
                                TripDetailHandoff.set(clientUuid)
                                navController.navigate(CabDispatchRoutes.TRIP_DETAIL)
                            },
                            // Real bug found + fixed 2026-08-28 (live-reproduced: an app kill/crash
                            // mid-fare leaves a TripEntity with status=OPEN in Room forever — no
                            // code path ever resumes it). This CTA used to navigate straight to
                            // HIRED, but HiredViewModel only starts the live ticking fare engine
                            // from SessionHolder.pendingTrip (in-memory, gone after any process
                            // death) — so the driver landed on a HIRED screen showing a fresh
                            // $0.00 meter totally disconnected from the real trip, with no way to
                            // actually finalize/pay it out, and it never synced to the backend.
                            // CLOSE_PAY is the correct destination: CloseAndPayViewModel already
                            // reactively reads TripRepository.observeActiveTrip() straight from
                            // Room (see its own init) and reconstructs the real accrued fare from
                            // the trip's last-persisted distance/waiting counters — no
                            // SessionHolder dependency, so it works correctly however the driver
                            // got here. Deliberately NOT resuming the *live* ticking meter here:
                            // that needs bridging two non-interchangeable FareState types (the
                            // live domain.FareState vs. the offline-reconstruction domain.fare.FareState
                            // used here), which is real, higher-risk financial-calculation work —
                            // flagged to the user as a follow-up product decision, not invented
                            // silently. Closing out promptly on the accrued total is the safe
                            // default: it recovers the trip and gets it paid/synced rather than
                            // leaving it lost forever.
                            onOpenActiveTrip = { navController.navigate(CabDispatchRoutes.CLOSE_PAY) },
                            onShiftReportClick = { selectedTab = DeckTab.SHIFT },
                        )
                    }
                    DeckTab.EARN -> TabShell("Earnings — this shift") { EarningsWheelContent() }
                    DeckTab.SHIFT -> TabShell("Shift") {
                        ShiftWheelContent(
                            onSubmitted = { summary ->
                                ShiftSubmissionHandoff.set(summary)
                                navController.navigate(CabDispatchRoutes.SHIFT_SUBMITTED)
                            },
                        )
                    }
                }
            }
            DrivePanel(
                state = state,
                deckState = deckState,
                onToggleAvailability = { viewModel.setAvailable(!state.isAvailable) },
                onStartMeter = { showStartConfirm = true },
                onSetPrice = { showSetPrice = true },
                onOpenProfile = { navController.navigate(CabDispatchRoutes.PROFILE) },
                onLogOff = { navController.navigate(CabDispatchRoutes.LOG_OFF) },
                onSettings = { navController.navigate(CabDispatchRoutes.SETTINGS) },
            )
        }
    }

    if (showSetPrice) {
        SetPriceDialogV2(
            onDismiss = { showSetPrice = false },
            onConfirm = { total ->
                showSetPrice = false
                if (viewModel.startMeter(negotiatedTotal = total)) {
                    navController.navigate(CabDispatchRoutes.HIRED)
                }
            },
        )
    }
    if (showStartConfirm) {
        StartMeterConfirmV2(
            state = state,
            onDismiss = { showStartConfirm = false },
            onConfirm = {
                showStartConfirm = false
                if (viewModel.startMeter()) navController.navigate(CabDispatchRoutes.HIRED)
            },
        )
    }
    when (val d = duressState) {
        is DuressUiState.Triggered -> DuressTriggeredOverlay(
            secondsRemaining = d.secondsRemaining,
            onCancel = AppContainer.duressController::cancel,
        )
        is DuressUiState.Active -> DuressActiveBanner()
        DuressUiState.Idle -> Unit
    }
}

// --- STATUS tab: the map surface (Figma c/map 11:55 + overlayBar 13:238) ---------------------

@Composable
private fun StatusMapPanel(onPlotZone: () -> Unit) {
    // Real Mapbox Static Images background (Ben's custom style, 2026-08-28 — see
    // MapboxStaticImage's own doc for why the Static Images API + a public pk.* token, not the
    // full interactive SDK), centered on the driver's real GPS fix
    // ([AppContainer.speedSource.locationFix]) — same "real fix, Sydney-CBD-until-then" fallback
    // [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]'s own MapBackground
    // already uses. The illustrative street-grid/suburb-label drawing below this now serves only
    // as the loading/error placeholder — real content while the image request is in flight or
    // (offline/no token) fails — not the map surface itself.
    val fix by AppContainer.speedSource.locationFix.collectAsState()
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val centerLat = fix?.lat ?: SydneyCbdFallback.LAT
    val centerLng = fix?.lng ?: SydneyCbdFallback.LNG

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1420))
            .onGloballyPositioned { sizePx = it.size },
    ) {
        if (sizePx.width > 0 && sizePx.height > 0) {
            val mapUrl = remember(sizePx, centerLat, centerLng) {
                MapboxStaticImage.url(
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = SydneyCbdFallback.ZOOM,
                    widthPx = sizePx.width,
                    heightPx = sizePx.height,
                )
            }
            SubcomposeAsyncImage(
                model = mapUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                    else -> IllustrativeStreetGrid()
                }
            }
        } else {
            IllustrativeStreetGrid()
        }

        // Car marker + pulsing halo at the frame's position.
        val pulse by rememberInfiniteTransition(label = "halo").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
            label = "halo-a",
        )
        Box(
            modifier = Modifier
                .offset(x = 360.dp, y = 300.dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(Deck.forHire.copy(alpha = pulse)),
        )
        Box(
            modifier = Modifier
                .offset(x = 374.dp, y = 314.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Deck.forHire)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("🚕", fontSize = 20.sp)
        }

        // Map chip (top-left)
        Box(
            modifier = Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Deck.canvas.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                // Was a hardcoded "Sydney metro" claim regardless of the driver's real location —
                // dishonest now that the panel behind this chip is a real, GPS-centered map (see
                // this fun's own doc). Says only what's actually known: whether a real fix exists.
                if (fix != null) "🗺 Live position" else "🗺 Waiting for GPS fix…",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = Deck.textSecondary,
            )
        }

        // Overlay bar (bottom) — plot chip + heartbeat line.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(Deck.R_LG.dp))
                .background(Deck.canvas.copy(alpha = 0.88f))
                .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_LG.dp))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Deck.forHire.copy(alpha = 0.14f))
                    .clickable(onClick = onPlotZone)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "📍 Plot a zone — see live demand →",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Deck.forHire,
                )
            }
            Text(
                "Heartbeat 30 s · GPS live",
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = Deck.textMuted,
            )
        }
    }
}

/** [StatusMapPanel]'s loading/error placeholder — the design's own illustrative street-grid look
 * (Figma `c/map 11:55`), now shown only while the real Mapbox image is in flight or unavailable
 * (offline, no token), not as the map surface itself. The suburb labels are the design's fixed
 * sample content, not a claim about the driver's real location — same reasoning
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]'s own
 * `IllustrativeGridFallback` already documents for its identical placeholder role. */
@Composable
private fun IllustrativeStreetGrid() {
    val street = Color(0xFF1C2940)
    val arterial = Color(0xFF243352)
    listOf(120, 260, 420, 580, 700).forEach { y ->
        Box(Modifier.offset(y = y.dp).fillMaxWidth().height(10.dp).background(street))
    }
    listOf(140, 320, 520, 660).forEach { x ->
        Box(Modifier.offset(x = x.dp).fillMaxHeight().width(12.dp).background(street))
    }
    Box(Modifier.offset(y = 340.dp).fillMaxWidth().height(18.dp).background(arterial))
    Box(Modifier.offset(x = 430.dp).fillMaxHeight().width(18.dp).background(arterial))

    SuburbLabel("SYDNEY CITY", 60.dp, 60.dp)
    SuburbLabel("REDFERN", 180.dp, 380.dp)
    SuburbLabel("AIRPORT", 560.dp, 600.dp)
    SuburbLabel("LAKEMBA", 80.dp, 620.dp)
}

@Composable
private fun SuburbLabel(text: String, x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
        color = Color(0xFF33445F),
        modifier = Modifier.offset(x = x, y = y),
    )
}

// --- Zones tab ------------------------------------------------------------------------------

@Composable
private fun ZonesTab(navController: NavHostController) {
    // Zones are full screens (Group D port) — the tab is a launcher panel for both.
    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Zones", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Deck.textPrimary)
        DeckButton(text = "📍  Plot into a zone", kind = DeckButtonKind.Primary, modifier = Modifier.width(420.dp)) {
            navController.navigate(CabDispatchRoutes.PLOT_ZONE)
        }
        DeckButton(text = "📊  Zone statistics — live supply & demand", kind = DeckButtonKind.Outline, modifier = Modifier.width(420.dp)) {
            navController.navigate(CabDispatchRoutes.ZONE_STATISTICS)
        }
    }
}

// --- generic v2 shell for the reused tab-content composables --------------------------------

@Composable
private fun TabShell(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Deck.textPrimary)
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(Deck.R_LG.dp))
                .background(Deck.panel)
                .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_LG.dp))
                .padding(18.dp),
        ) {
            content()
        }
    }
}

// --- Drive panel (Figma c/drive-panel 11:27 / 13:184) ----------------------------------------

@Composable
private fun DrivePanel(
    state: WheelDashboardUiState,
    deckState: DeckState,
    onToggleAvailability: () -> Unit,
    onStartMeter: () -> Unit,
    onSetPrice: () -> Unit,
    onOpenProfile: () -> Unit,
    onLogOff: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(Deck.DRIVE_PANEL_W.dp)
            .fillMaxHeight()
            .background(Deck.panel)
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // State card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Deck.R_LG.dp))
                .background(deckState.color.copy(alpha = 0.10f))
                .border(1.5.dp, deckState.color.copy(alpha = 0.5f), RoundedCornerShape(Deck.R_LG.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                deckState.label,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = 1.5.sp,
                color = deckState.color,
            )
            Text(deckState.caption, fontFamily = InterFamily, fontSize = 14.sp, color = Deck.textSecondary)
        }
        // Driver row — tap opens Profile (kept from the old dashboard's identity-chip behavior).
        Row(
            modifier = Modifier.clickable(onClick = onOpenProfile),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Deck.raised),
                contentAlignment = Alignment.Center,
            ) {
                val initials = state.session?.driverName
                    ?.split(" ")
                    ?.mapNotNull { it.firstOrNull()?.uppercase() }
                    ?.take(2)
                    ?.joinToString("") ?: "—"
                Text(initials, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Deck.yellow)
            }
            Column {
                Text(
                    state.session?.driverName ?: "No driver",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = Deck.textPrimary,
                )
                Text(
                    state.session?.let { "${it.driverId.take(8)} · ${it.vehicleId}" } ?: "—",
                    fontFamily = RobotoMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = Deck.textMuted,
                )
            }
        }
        // KPI tiles — real Room aggregates via TodayStats.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiTile(value = state.todayStats.tripsCount.toString(), label = "TRIPS", modifier = Modifier.weight(1f))
            KpiTile(
                value = state.todayStats.kmTotal.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                label = "KM",
                modifier = Modifier.weight(1f),
            )
            KpiTile(
                value = "$" + state.todayStats.earningsTotal.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                label = "EARNED",
                modifier = Modifier.weight(1f),
            )
        }
        // Availability toggle: green GO AVAILABLE when off duty; outline GO OFF DUTY when visible.
        if (state.isAvailable) {
            DeckButton(text = "GO OFF DUTY", kind = DeckButtonKind.Outline, modifier = Modifier.fillMaxWidth(), onClick = onToggleAvailability)
        } else {
            DeckButton(text = "GO AVAILABLE", kind = DeckButtonKind.Success, modifier = Modifier.fillMaxWidth(), onClick = onToggleAvailability)
        }
        state.availabilityError?.let {
            Text(it, fontFamily = InterFamily, fontSize = 13.sp, color = Deck.hired)
        }
        Spacer(Modifier.weight(1f))
        // Utility row — settings / log off (small ghost affordances, kept from the old chrome).
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "⚙ SETTINGS",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Deck.textMuted,
                modifier = Modifier.clickable(onClick = onSettings),
            )
            Text(
                "⏻ LOG OFF",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Deck.textMuted,
                modifier = Modifier.clickable(onClick = onLogOff),
            )
        }
        DeckButton(
            text = "START METER",
            kind = DeckButtonKind.Primary,
            heightDp = Deck.CTA_H,
            fontSize = 24,
            enabled = state.tariff != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = onStartMeter,
        )
        DeckButton(text = "SET PRICE — FIXED FARE", kind = DeckButtonKind.Outline, modifier = Modifier.fillMaxWidth(), onClick = onSetPrice)
        Text(
            if (state.tariff != null) "Menu locks over 26 km/h — meter stays visible" else "Waiting for a signed tariff…",
            fontFamily = InterFamily,
            fontSize = 12.sp,
            color = Deck.textMuted,
        )
    }
}

// --- Start Meter confirm (v2-styled; full Figma 16 frame port lands with Group C) -----------

@Composable
private fun StartMeterConfirmV2(
    state: WheelDashboardUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(Deck.R_XL.dp))
                .background(Deck.panel)
                .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_XL.dp))
                .clickable(enabled = false) {}
                .padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Start the meter?", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Deck.textPrimary)
            val t = state.tariff
            Text(
                if (t != null) "${t.name} · signed ✓" else "No tariff",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = Deck.forHire,
            )
            t?.let {
                Text(
                    "Flagfall $${it.flagFall} + $${it.distRate1}/km · waiting $${it.waitingRatePerMin}/min",
                    fontFamily = RobotoMonoFamily,
                    fontSize = 15.sp,
                    color = Deck.textSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DeckButton(text = "Cancel", kind = DeckButtonKind.Outline, modifier = Modifier.weight(1f), onClick = onDismiss)
                DeckButton(text = "Start Meter", kind = DeckButtonKind.Success, heightDp = 72, modifier = Modifier.weight(1.6f), onClick = onConfirm)
            }
        }
    }
}


// --- Set Price (fixed fare) entry — v2-styled; full Figma 17 frame port lands with Group C ---

@Composable
private fun SetPriceDialogV2(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(Deck.R_XL.dp))
                .background(Deck.panel)
                .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_XL.dp))
                .clickable(enabled = false) {}
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Set price — fixed fare",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = Deck.textPrimary,
            )
            Text(
                "Agreed with the passenger before starting. Levies and GST still apply on top.",
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = Deck.textSecondary,
            )
            Box(
                modifier = Modifier
                    .width(448.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(Deck.R_MD.dp))
                    .background(Deck.inset),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (amount.isEmpty()) "$0" else "$" + amount,
                    fontFamily = au.com.threesixty.cabdispatch.ui.theme.ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 44.sp,
                    color = Deck.ledGreen,
                )
            }
            DeckKeypad(
                onDigit = { d -> if (amount.length < 3) amount += d }, // Figma cap: $1–$500
                onBackspace = { amount = amount.dropLast(1) },
                onClear = { amount = "" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DeckButton(text = "Cancel", kind = DeckButtonKind.Outline, modifier = Modifier.weight(1f), onClick = onDismiss)
                DeckButton(
                    text = "Start at fixed price",
                    kind = DeckButtonKind.Primary,
                    heightDp = 72,
                    enabled = (amount.toIntOrNull() ?: 0) in 1..500,
                    modifier = Modifier.weight(1.6f),
                ) { onConfirm(amount) }
            }
        }
    }
}
