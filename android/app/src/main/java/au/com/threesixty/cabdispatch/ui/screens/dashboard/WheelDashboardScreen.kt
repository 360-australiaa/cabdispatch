package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff
import au.com.threesixty.cabdispatch.domain.TodayStats
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone
import au.com.threesixty.cabdispatch.ui.screens.earnings.EarningsWheelContent
import au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftWheelContent
import au.com.threesixty.cabdispatch.ui.screens.trips.TripsWheelContent
import au.com.threesixty.cabdispatch.ui.theme.CabDispatchColors
import au.com.threesixty.cabdispatch.ui.theme.WheelColors
import au.com.threesixty.cabdispatch.ui.wheel.WheelController
import au.com.threesixty.cabdispatch.ui.wheel.WheelGeometry
import au.com.threesixty.cabdispatch.ui.wheel.WheelSlot
import au.com.threesixty.cabdispatch.ui.wheel.WheelSnapEasing
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent
import au.com.threesixty.cabdispatch.ui.wheel.rememberWheelController
import au.com.threesixty.cabdispatch.ui.wheel.wheelGesture
import au.com.threesixty.cabdispatch.data.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The new home surface (replaces old S2/Idle, per task brief) — full-bleed map background,
 * identity/quick-stats/status chrome, the six-slot rotating wheel, and the Start Meter button
 * with its point-of-origin scale+fade transition into the meter screen. Ground truth for exact
 * visuals/behavior: `docs/driver-dashboard-full-prototype.html`'s `#dashboard` view + design spec
 * TCT-DRIVER-APP-01.md §§3-6.
 *
 * Registered under the existing [CabDispatchRoutes.IDLE] route key (not a new route) so every
 * sibling screen that already navigates to `CabDispatchRoutes.IDLE` (S4's "Done", S5's submit,
 * etc.) keeps working unchanged — see `ui/navigation/CabDispatchNavHost.kt`.
 */
@Composable
fun WheelDashboardScreen(
    navController: NavHostController,
    viewModel: WheelDashboardViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    // Rows 28-30 contextual overlays' shared state (spec §2: hidden duress gesture "active
    // throughout", not just during an active trip — see ui/overlays/DuressOverlays.kt /
    // domain/DuressController.kt). Collected directly off the process-lifetime
    // AppContainer.duressController singleton, same pattern
    // ui/screens/hired/HiredViewModel.kt#duressState uses (a plain passthrough there since that
    // ViewModel already exists for other reasons; no ViewModel-layer indirection needed here).
    val duressState by AppContainer.duressController.state.collectAsState()
    val wheelController = rememberWheelController(speedSource = AppContainer.speedSource)
    var displayedSlotIndex by remember { mutableStateOf(WheelSlot.OFF_DUTY_AVAILABLE.index) }
    val displayedSlot = remember(displayedSlotIndex) { WheelSlot.fromIndex(displayedSlotIndex) }

    // --- Start Meter -> meter-screen transition state (spec §6.2) ---
    // This agent owns only the dashboard shell, not the meter screen (sibling agent's S3), so
    // there's no shared-element/AnimatedContent API available across that route boundary without
    // touching CabDispatchNavHost.kt (shared infra 7 other agents are also landing routes into
    // right now). Instead: play the point-of-origin scale+fade locally as a same-composable
    // overlay that fully covers the screen by the time the animation finishes, THEN navigate —
    // the nav-graph swap underneath is invisible to the driver. Reproduces the prototype's
    // `startBtn` click handler (transform-origin = button's own screen position at tap time,
    // scale .08->1 over ~550ms with `cubic-bezier(.22,1,.36,1)`, fade over 300ms) without
    // depending on the destination screen's internals.
    var isTransitioning by remember { mutableStateOf(false) }
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var buttonOriginFraction by remember { mutableStateOf(Offset(0.5f, 0.94f)) } // sane default: bottom-center
    val overlayScale = remember { Animatable(0.08f) }
    val overlayAlpha = remember { Animatable(0f) }
    val dashboardAlpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    val startMeterEnabled = uiState.tariff != null && uiState.session != null && !isTransitioning

    fun onStartMeterTapped() {
        if (!startMeterEnabled) return
        isTransitioning = true
        scope.launch {
            launch {
                delay(100) // matches prototype's `.hidden-behind{ transition: opacity .25s ease .1s }`
                dashboardAlpha.animateTo(0f, tween(250))
            }
            launch { overlayAlpha.animateTo(1f, tween(300)) }
            overlayScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 550,
                    easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                ),
            )
            val started = viewModel.startMeter()
            if (started) {
                // Verified (reconciliation pass): no sibling agent introduced a distinct
                // wheel-redesign meter route — HIRED/S3 (ui/screens/hired/HiredScreen.kt) is the
                // one real meter screen, and it's registered under CabDispatchRoutes.HIRED in
                // CabDispatchNavHost.kt exactly as this call expects.
                navController.navigate(CabDispatchRoutes.HIRED)
            } else {
                isTransitioning = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg)
            .onGloballyPositioned { rootCoordinates = it },
    ) {
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dashboardAlpha.value }) {
            MapBackground(modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                TopStatusStrip(status = uiState.status)
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WheelColors.border))
            }

            IdentityCard(
                session = uiState.session,
                onClick = {
                    // Profile (Compliance + Settings, spec §5/§8 rows 20-21) has landed —
                    // see ui/screens/profile/ProfileScreen.kt. Was routed to the standalone
                    // Settings (S6) screen as a stand-in until then.
                    navController.navigate(CabDispatchRoutes.PROFILE)
                },
                modifier = Modifier.align(Alignment.TopStart).padding(top = 52.dp, start = 24.dp),
            )

            QuickStatsCard(
                stats = uiState.todayStats,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 24.dp),
            )

            StatusCard(
                isAvailable = uiState.isAvailable,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 140.dp, start = 24.dp),
            )

            Crossfade(
                targetState = displayedSlot,
                animationSpec = tween(WheelGeometry.CONTENT_FADE_MS),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 210.dp, start = 24.dp)
                    .widthIn(max = 460.dp),
                label = "wheelContentPane",
            ) { slot ->
                val provider = wheelSlotContentProviderFor(
                    slot = slot,
                    uiState = uiState,
                    navController = navController,
                    onAvailableChange = viewModel::setAvailable,
                )
                ContentPane(provider = provider)
            }

            WheelArea(
                controller = wheelController,
                displayedSlotIndex = displayedSlotIndex,
                onSettled = { index -> displayedSlotIndex = index },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 80.dp),
            )

            StartMeterButton(
                enabled = startMeterEnabled,
                onClick = ::onStartMeterTapped,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .onGloballyPositioned { buttonCoords ->
                        val root = rootCoordinates ?: return@onGloballyPositioned
                        if (root.size.width == 0 || root.size.height == 0) return@onGloballyPositioned
                        val centerInRoot = root.localPositionOf(
                            buttonCoords,
                            Offset(buttonCoords.size.width / 2f, buttonCoords.size.height / 2f),
                        )
                        buttonOriginFraction = Offset(
                            centerInRoot.x / root.size.width,
                            centerInRoot.y / root.size.height,
                        )
                    },
            )
        }

        if (isTransitioning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = overlayScale.value
                        scaleY = overlayScale.value
                        alpha = overlayAlpha.value
                        transformOrigin = TransformOrigin(buttonOriginFraction.x, buttonOriginFraction.y)
                    }
                    .background(WheelColors.bg),
                contentAlignment = Alignment.Center,
            ) {
                // Local placeholder only — the real meter screen (S3/HIRED, sibling-owned) takes
                // over the instant navigate() fires above; this is just what's visible for the
                // ~550ms the scale-in is still running.
                Text(
                    "Starting meter…",
                    color = WheelColors.gold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }

        // Rows 28-30 — hidden duress gesture + its "Duress triggered"/"Duress active" contextual
        // overlays (spec §2: "active throughout", i.e. here on the dashboard too, not just S3/
        // Hired — see ui/screens/hired/HiredScreen.kt for the original wiring this mirrors).
        // Placed outside the `dashboardAlpha`-faded inner Box above so the hit-target/overlays
        // stay live and fully opaque even mid-transition into the meter screen.
        HiddenDuressGestureZone(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 24.dp),
            onTriggered = {
                AppContainer.duressController.trigger(
                    vehicleId = uiState.session?.vehicleId,
                    driverId = uiState.session?.driverId,
                )
            },
        )
        when (val d = duressState) {
            is DuressUiState.Active -> DuressActiveBanner(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            )
            is DuressUiState.Triggered -> DuressTriggeredOverlay(
                secondsRemaining = d.secondsRemaining,
                onCancel = { AppContainer.duressController.cancel() },
            )
            DuressUiState.Idle -> Unit
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Map background — plain drawn grid + position pin, no paid maps SDK (matches the fleet
// dashboard's own approach: dashboard/src/pages/live-map/FleetMapCanvas.tsx's doc comment "no
// paid maps SDK available offline... plain lat/lng plot, not real map tiles").
// ---------------------------------------------------------------------------------------------

@Composable
private fun MapBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(WheelColors.surface)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawDiagonalGrid(angleDeg = -8f, spacingPx = 84f)
            drawDiagonalGrid(angleDeg = 96f, spacingPx = 168f)
        }
        // Driver position pin. TODO(location sibling agent): this is a fixed illustrative
        // position, not a real fix — HANDOFF.md's "GPS is stubbed, not real" gap applies here
        // too. Once a real FusedLocationProviderClient location lands, project it the same way
        // FleetMapCanvas.tsx projects lat/lng into its bounding box, rather than a static offset.
        DriverPositionPin(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-160).dp, y = (-30).dp),
        )
    }
}

private fun DrawScope.drawDiagonalGrid(angleDeg: Float, spacingPx: Float) {
    rotate(degrees = angleDeg, pivot = center) {
        val diag = size.width + size.height
        var x = -diag
        while (x < diag) {
            drawLine(
                color = Color.White.copy(alpha = 0.04f),
                start = Offset(x, -diag),
                end = Offset(x, diag),
                strokeWidth = 2f,
            )
            x += spacingPx
        }
    }
}

@Composable
private fun DriverPositionPin(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(WheelColors.available.copy(alpha = 0.18f)),
        )
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(WheelColors.available)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Top status strip
// ---------------------------------------------------------------------------------------------

@Composable
private fun TopStatusStrip(status: DashboardStatusStrip, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(30_000)
        }
    }
    val formatter = remember { DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.ENGLISH) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(WheelColors.surface)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(now.format(formatter), color = WheelColors.textSecondary, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            StatusChip("GPS", status.gpsOk)
            StatusChip("4G", status.networkOk)
            StatusChip("Printer", status.printerOk)
            StatusChip(
                if (status.batteryPercent != null) "Batt ${status.batteryPercent}%" else "Batt —",
                status.batteryOk,
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ok) WheelColors.available else WheelColors.waiting),
        )
        Text(label, color = WheelColors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------------------------
// Identity card / quick stats / status card
// ---------------------------------------------------------------------------------------------

@Composable
private fun IdentityCard(session: DriverSession?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(WheelColors.surfaceRaised.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(WheelColors.gold),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialsFor(session?.driverName ?: "?"),
                color = CabDispatchColors.Indigo,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Column {
            Text(
                session?.driverName ?: "Not signed in",
                color = WheelColors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            // "Driver ID · vehicle rego" per spec §5 — DriverSession doesn't yet carry a separate
            // display driver-code/rego field (see domain/Session.kt), so this shows the raw
            // driverId/vehicleId the driver logged in / bound with (session.vehicleId IS a
            // free-text/QR-scanned identifier entered at S1 vehicle-bind, so it's already
            // effectively a rego in practice — see LoginVehicleBindViewModel.bindVehicle).
            Text(
                listOfNotNull(session?.driverId, session?.vehicleId).joinToString(" · "),
                color = WheelColors.textSecondary,
                fontSize = 11.sp,
            )
        }
        Text("›", color = WheelColors.textSecondary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

private fun initialsFor(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.first().uppercaseChar() }
        .take(2).joinToString("").ifEmpty { "?" }

@Composable
private fun QuickStatsCard(stats: TodayStats, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(WheelColors.surfaceRaised.copy(alpha = 0.85f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Prototype shows "Hours" here; TodayStats (preserved from the old IdleViewModel's
        // stats source, see WheelDashboardViewModel) doesn't currently expose shift-elapsed
        // hours, only trip count/km/earnings — showing Km instead until whichever sibling agent
        // owns the Shift wheel-slot wants to also surface an hours-on-shift value up here.
        QuickStat(stats.tripsCount.toString(), "Trips today")
        QuickStat(stats.kmTotal.setScale(1, RoundingMode.HALF_UP).toPlainString(), "Km today")
        QuickStat(stats.earningsTotal.toMoneyString(), "Earnings")
    }
}

@Composable
private fun QuickStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, color = WheelColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(label, color = WheelColors.textSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun StatusCard(isAvailable: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(WheelColors.surfaceRaised.copy(alpha = 0.92f))
            .border(1.dp, WheelColors.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Text(
            if (isAvailable) "AVAILABLE" else "OFF DUTY",
            color = WheelColors.textSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Text(
            if (isAvailable) "Ready for dispatch offers" else "Rotate the wheel to go Available",
            color = WheelColors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Content pane + per-slot content contract (spec §4)
// ---------------------------------------------------------------------------------------------

/**
 * Content-pane payload for one wheel slot: eyebrow label + hero title + real detail body (spec
 * §4 — "must show real content, not summaries"). One implementation per [WheelSlot] — this
 * dashboard-shell screen implements [WheelSlot.OFF_DUTY_AVAILABLE] itself
 * ([OffDutyAvailableContent]) and, as of the reconciliation pass, wires each sibling agent's own
 * slot 1-5 composable (Available Trips, Messages, Trips, Earnings, Shift) into the matching
 * branch of [wheelSlotContentProviderFor] below via a small `WheelSlotContentProvider` wrapper per
 * slot ([AvailableTripsSlotContent] etc.) — see that function's doc for the reconciliation note.
 */
interface WheelSlotContentProvider {
    val eyebrow: String
    val hero: String

    @Composable
    fun Body()
}

@Composable
private fun wheelSlotContentProviderFor(
    slot: WheelSlot,
    uiState: WheelDashboardUiState,
    navController: NavHostController,
    onAvailableChange: (Boolean) -> Unit,
): WheelSlotContentProvider = when (slot) {
    WheelSlot.OFF_DUTY_AVAILABLE -> OffDutyAvailableContent(uiState, onAvailableChange)
    // Reconciliation pass: every wheel-slot sibling screen below shipped its own composable
    // (ui/wheel/content/AvailableTripsWheelContent.kt, ui/screens/messages/MessagesWheelContent.kt,
    // ui/screens/trips/TripsWheelContent.kt, ui/screens/earnings/EarningsWheelContent.kt,
    // ui/screens/shiftreport/ShiftWheelContent.kt) but none of them were actually reachable from
    // this screen — every branch here still fell through to PlaceholderSlotContent. Wired for real
    // below; each sibling composable's own "TODO: verify against the sibling dashboard screen"
    // doc comment is resolved by this wiring.
    WheelSlot.AVAILABLE_TRIPS -> AvailableTripsSlotContent(navController)
    WheelSlot.MESSAGES -> MessagesSlotContent(navController)
    WheelSlot.TRIPS -> TripsSlotContent(navController)
    WheelSlot.EARNINGS -> EarningsSlotContent()
    WheelSlot.SHIFT -> ShiftSlotContent(navController)
}

/**
 * Wheel slot 1 — delegates to [AvailableTripsWheelContent], which already takes the shared
 * [NavHostController] directly (accept navigates straight to S3/HIRED, tap-through navigates to
 * [CabDispatchRoutes.AVAILABLE_TRIP_OFFER] — both internal to that composable).
 */
private class AvailableTripsSlotContent(
    private val navController: NavHostController,
) : WheelSlotContentProvider {
    override val eyebrow: String = "CURRENT SELECTION"
    override val hero: String = WheelSlot.AVAILABLE_TRIPS.label

    @Composable
    override fun Body() {
        AvailableTripsWheelContent(navController = navController)
    }
}

/** Wheel slot 2 — [MessagesWheelContent]'s `onOpenThread` targets [CabDispatchRoutes.MESSAGES_THREAD]. */
private class MessagesSlotContent(
    private val navController: NavHostController,
) : WheelSlotContentProvider {
    override val eyebrow: String = "CURRENT SELECTION"
    override val hero: String = WheelSlot.MESSAGES.label

    @Composable
    override fun Body() {
        MessagesWheelContent(
            onOpenThread = { navController.navigate(CabDispatchRoutes.MESSAGES_THREAD) },
        )
    }
}

/**
 * Wheel slot 3 — [TripsWheelContent]'s `onTripClick` hands the tapped trip's clientUuid off via
 * [TripDetailHandoff] (no nav-graph args on any route here — see that object's doc) then navigates
 * to [CabDispatchRoutes.TRIP_DETAIL].
 */
private class TripsSlotContent(
    private val navController: NavHostController,
) : WheelSlotContentProvider {
    override val eyebrow: String = "CURRENT SELECTION"
    override val hero: String = WheelSlot.TRIPS.label

    @Composable
    override fun Body() {
        TripsWheelContent(
            onTripClick = { clientUuid ->
                TripDetailHandoff.set(clientUuid)
                navController.navigate(CabDispatchRoutes.TRIP_DETAIL)
            },
        )
    }
}

/** Wheel slot 4 — [EarningsWheelContent] needs no navigation callback. */
private class EarningsSlotContent : WheelSlotContentProvider {
    override val eyebrow: String = "CURRENT SELECTION"
    override val hero: String = WheelSlot.EARNINGS.label

    @Composable
    override fun Body() {
        EarningsWheelContent()
    }
}

/**
 * Wheel slot 5 — [ShiftWheelContent]'s `onSubmitted` captures the just-submitted totals via
 * [ShiftSubmissionHandoff] then navigates to [CabDispatchRoutes.SHIFT_SUBMITTED], same hand-off
 * pattern as [TripsSlotContent].
 */
private class ShiftSlotContent(
    private val navController: NavHostController,
) : WheelSlotContentProvider {
    override val eyebrow: String = "CURRENT SELECTION"
    override val hero: String = WheelSlot.SHIFT.label

    @Composable
    override fun Body() {
        ShiftWheelContent(
            onSubmitted = { summary ->
                ShiftSubmissionHandoff.set(summary)
                navController.navigate(CabDispatchRoutes.SHIFT_SUBMITTED)
            },
        )
    }
}

private class OffDutyAvailableContent(
    private val uiState: WheelDashboardUiState,
    private val onAvailableChange: (Boolean) -> Unit,
) : WheelSlotContentProvider {
    override val eyebrow: String = "CURRENT STATUS"
    override val hero: String get() = if (uiState.isAvailable) "Available" else "Off Duty"

    @Composable
    override fun Body() {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = if (uiState.isAvailable) {
                    "You're visible to dispatch and can receive job offers on the Available Trips slot."
                } else {
                    "Rotate the wheel to browse, or tap Start Meter once you have a fare."
                },
                color = WheelColors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = uiState.isAvailable,
                    onCheckedChange = onAvailableChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = WheelColors.gold,
                        checkedTrackColor = WheelColors.surfaceSunken,
                    ),
                )
                Text(
                    if (uiState.isAvailable) "Available for dispatch" else "Off duty",
                    color = WheelColors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            uiState.availabilityError?.let { error ->
                Text(error, color = WheelColors.duress, fontSize = 12.sp)
            }
            if (uiState.tariff == null) {
                Text("Loading cached tariff…", color = WheelColors.textMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ContentPane(provider: WheelSlotContentProvider, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            provider.eyebrow,
            color = WheelColors.textMuted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            provider.hero,
            color = WheelColors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
        )
        Spacer(Modifier.height(14.dp))
        Box(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            provider.Body()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The wheel itself
// ---------------------------------------------------------------------------------------------

@Composable
private fun WheelArea(
    controller: WheelController,
    displayedSlotIndex: Int,
    onSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(WheelColors.surfaceRaised.copy(alpha = 0.85f))
                .border(1.dp, WheelColors.border, RoundedCornerShape(11.dp))
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (controller.enabled) WheelColors.available else WheelColors.waiting),
            )
            Text(
                if (controller.enabled) {
                    "Wheel unlocked — locks above 26 km/h"
                } else {
                    "Wheel locked — slow down to unlock"
                },
                color = WheelColors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .size(WheelGeometry.WHEEL_DIAMETER_DP.dp)
                .graphicsLayer { alpha = if (controller.enabled) 1f else 0.45f }
                .wheelGesture(controller, onSettled),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, WheelColors.borderStrong, CircleShape),
            )
            WheelSlot.entries.forEach { slot ->
                val angleDeg = WheelGeometry.slotScreenAngleDeg(slot.index, controller.rotationDegrees)
                WheelSlotDot(
                    slot = slot,
                    selected = slot.index == displayedSlotIndex,
                    angleDeg = angleDeg,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.WheelSlotDot(
    slot: WheelSlot,
    selected: Boolean,
    angleDeg: Float,
) {
    val dotSize by animateDpAsState(
        targetValue = if (selected) WheelGeometry.SELECTED_DOT_SIZE_DP.dp else WheelGeometry.UNSELECTED_DOT_SIZE_DP.dp,
        animationSpec = tween(WheelGeometry.SNAP_DURATION_MS, easing = WheelSnapEasing),
        label = "wheelDotSize",
    )

    // Lock-in pulse: the instant this slot BECOMES the selected one (not on every recomposition —
    // gated on the false->true edge of [selected]), bounce it past full size and back, matching
    // the HTML reference's `.pulse` keyframe (scale 1 -> 1.18 -> 1, back-out ease, ~400ms). The
    // size/color/border crossfade alone reads as passive; this makes landing on a section feel
    // like a felt "click", the way a real detent does.
    val pulseScale = remember(slot) { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pulseScale.snapTo(1f)
            pulseScale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 400
                    1f at 0
                    1.18f at 180 using WheelSnapEasing
                    1f at 400 using WheelSnapEasing
                },
            )
        }
    }

    val (dotX, dotY) = WheelGeometry.offsetForAngle(angleDeg, WheelGeometry.ICON_RING_RADIUS_DP)
    val (labelX, labelY) = WheelGeometry.offsetForAngle(angleDeg, WheelGeometry.LABEL_RING_RADIUS_DP)

    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = dotX.dp, y = dotY.dp)
            .size(dotSize)
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            }
            .then(
                // Gold glow behind the locked-in slot, matching the reference's
                // `box-shadow:0 0 22px rgba(244,195,0,.45)` — Compose's shadow modifier is the
                // nearest equivalent for a colored glow (vs. a plain elevation shadow).
                if (selected) {
                    Modifier.shadow(
                        elevation = 14.dp,
                        shape = CircleShape,
                        ambientColor = WheelColors.gold,
                        spotColor = WheelColors.gold,
                    )
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(if (selected) WheelColors.surfaceSunken else WheelColors.surfaceRaised)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) WheelColors.gold else WheelColors.border,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            slotAbbreviation(slot),
            color = if (selected) WheelColors.textPrimary else WheelColors.textSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        )
    }

    Text(
        text = slot.label,
        color = if (selected) WheelColors.textPrimary else WheelColors.textSecondary,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 12.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = labelX.dp, y = labelY.dp)
            .width(120.dp),
    )
}

private fun slotAbbreviation(slot: WheelSlot): String =
    slot.label.split(Regex("[\\s/]+")).filter { it.isNotEmpty() }
        .joinToString("") { it.first().uppercase() }.take(2)

// ---------------------------------------------------------------------------------------------
// Start Meter button
// ---------------------------------------------------------------------------------------------

@Composable
private fun StartMeterButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(340.dp)
            .height(88.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) WheelColors.gold else WheelColors.gold.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "START METER",
            color = CabDispatchColors.Indigo,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 21.sp,
        )
    }
}
