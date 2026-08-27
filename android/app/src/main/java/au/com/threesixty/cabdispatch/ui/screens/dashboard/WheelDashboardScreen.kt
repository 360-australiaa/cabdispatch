package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import au.com.threesixty.cabdispatch.data.remote.MapboxStaticImage
import au.com.threesixty.cabdispatch.data.remote.SydneyCbdFallback
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.overlays.DuressActiveBanner
import au.com.threesixty.cabdispatch.ui.overlays.DuressTriggeredOverlay
import au.com.threesixty.cabdispatch.ui.overlays.HiddenDuressGestureZone
import au.com.threesixty.cabdispatch.ui.screens.earnings.EarningsWheelContent
import au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftWheelContent
import au.com.threesixty.cabdispatch.ui.screens.trips.TripsPaneVariant
import au.com.threesixty.cabdispatch.ui.screens.trips.TripsWheelContent
import au.com.threesixty.cabdispatch.ui.theme.CabDispatchColors
import au.com.threesixty.cabdispatch.ui.theme.WheelColorsV2
import au.com.threesixty.cabdispatch.ui.wheel.WheelSlot
import au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent
import au.com.threesixty.cabdispatch.data.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

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
    // Real GPS fix (2026-08-03, map-centering/region-detection pass) — see MapBackground's doc
    // for how this replaces the fixed SydneyCbdFallback center it used to be pinned to
    // unconditionally.
    val locationFix by AppContainer.speedSource.locationFix.collectAsState()
    // Phase B v2: the rotating-wheel gesture UI (WheelController/WheelArea) is no longer rendered
    // on this screen (see the v2 chrome swap below) — displayedSlotIndex is now driven by dock
    // tile taps instead of wheel drag/snap, but it's the exact same state shape
    // [wheelSlotContentProviderFor] already expected, so every per-slot content composable below
    // is unchanged. showWheelSlotSheet gates the sheet that hosts that per-slot content (there is
    // no permanent side panel in the v2 design to show it in inline any more).
    var displayedSlotIndex by remember { mutableStateOf(WheelSlot.OFF_DUTY_AVAILABLE.index) }
    val displayedSlot = remember(displayedSlotIndex) { WheelSlot.fromIndex(displayedSlotIndex) }
    var showWheelSlotSheet by remember { mutableStateOf(false) }
    // True when the sheet was opened from the dock's "History" tile rather than "My Trips" — both
    // resolve to WheelSlot.TRIPS (see HomeDashboardV2's dockTiles doc), but the v2 reskin renders
    // two different layouts for that one real trips dataset. See TripsSlotContent below.
    var tripsHistoryVariant by remember { mutableStateOf(false) }

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

    // --- Start-meter confirmation dialog + "Set Price" negotiated-fare entry point
    // (2026-08-10 meter-polish pass, matching real competitor taxi-meter UX patterns). ---
    var showStartConfirm by remember { mutableStateOf(false) }
    var showSetPriceEntry by remember { mutableStateOf(false) }

    /**
     * [negotiatedTotal] is decimal-as-string ("45.00"), already validated against
     * [NEGOTIATED_TOTAL_MIN]/[NEGOTIATED_TOTAL_MAX] by [SetPriceEntryScreen] before this is ever
     * called — null for a normal metered Start Meter tap (the pre-existing, unchanged path).
     */
    fun onStartMeterTapped(negotiatedTotal: String? = null) {
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
            val started = viewModel.startMeter(negotiatedTotal)
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
            .background(WheelColorsV2.pageBackgroundBrush)
            .onGloballyPositioned { rootCoordinates = it },
    ) {
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dashboardAlpha.value }) {
            MapBackground(fix = locationFix, modifier = Modifier.fillMaxSize())

            // Phase B v2 reskin (Figma fileKey JhEhok3n9bntRNS5Y1u3Yc, node 25:3/28:9): the old
            // permanent split-panel body (LeftContentPanel + RightWheelPanel, a fixed-in-place
            // wheel instrument console) is replaced by full-bleed map chrome — glass chips/CTAs/
            // dock, per the approved design, which has no side-panel layout at all. The wheel's
            // rotation/gesture system itself (WheelController, wheelGesture, WheelState) and the
            // per-slot content composables (AvailableTripsWheelContent, MessagesWheelContent,
            // etc.) are NOT deleted — see [WheelSlotContentSheet] below, opened when a dock tile
            // picks a slot, so tapping "Messages"/"Available Trips"/etc. still reaches the exact
            // same real content this screen already had, just presented as a sheet instead of a
            // permanent side panel (there is no such panel in the v2 design to host it in).
            HomeDashboardV2ChromeOverlay(
                uiState = uiState,
                navController = navController,
                onStartMeterClick = { showStartConfirm = true },
                onZonesClick = { navController.navigate(CabDispatchRoutes.PLOT_ZONE) },
                onSelectWheelSlot = { index, isHistoryVariant ->
                    displayedSlotIndex = index
                    tripsHistoryVariant = isHistoryVariant
                    showWheelSlotSheet = true
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (showWheelSlotSheet) {
                WheelSlotContentSheet(
                    slot = displayedSlot,
                    uiState = uiState,
                    navController = navController,
                    onAvailableChange = viewModel::setAvailable,
                    tripsHistoryVariant = tripsHistoryVariant,
                    onDismiss = { showWheelSlotSheet = false },
                )
            }

            StartMeterButton(
                enabled = startMeterEnabled,
                // Gated behind a confirmation dialog now (2026-08-10 meter-polish pass, matching
                // a real competitor taxi-meter UX pattern) instead of firing on tap directly —
                // see [StartMeterConfirmDialog] below (Phase B v2 reskin, Figma node 36:2). That
                // dialog itself also offers a "SET PRICE" button (per the approved design) that
                // routes into the same [SetPriceEntryScreen] as the standalone bottom TextButton
                // below — Set Price's own numeric-entry screen already ends in an explicit
                // "Confirm & start" action, so no second confirmation stacks on top of it.
                onClick = { showStartConfirm = true },
                modifier = Modifier
                    // Moved from BottomCenter to BottomEnd (Phase B tablet-verification pass):
                    // BottomCenter collided with BOTH the v2 dock bar (HomeDashboardV2ChromeOverlay's
                    // DockBar, bottom-center) and the driver-identity chip (bottom-start) — there is
                    // no bottom-center-with-enough-clearance option left once those two coexist.
                    // BottomEnd is genuinely empty in the idle (no ambient booked trip) state this
                    // button is only ever visible in — see HomeDashboardV2ChromeOverlay's own doc on
                    // why its Trip Focus card (which would occupy this same corner) doesn't render
                    // without a real booked trip.
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 210.dp)
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

            // "Set Price" — negotiated/fixed-fare entry point (2026-08-10 meter-polish pass),
            // placed just above Start Meter rather than replacing it: a driver picking up a
            // street hail still wants the normal metered flow most of the time, this is the
            // secondary/occasional path (pre-arranged/negotiated fare, NSW Fares Order allows
            // this for negotiated fares). Small `TextButton`, not another `StartMeterButton`-sized
            // control, so it reads as clearly secondary at a glance.
            TextButton(
                onClick = { if (startMeterEnabled) showSetPriceEntry = true },
                enabled = startMeterEnabled,
                modifier = Modifier
                    // Moved to BottomEnd alongside StartMeterButton — see that modifier's comment.
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 306.dp),
            ) {
                Text(
                    "Set Price",
                    color = if (startMeterEnabled) WheelColorsV2.amberFigure else WheelColorsV2.mutedFigure,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
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
                    .background(Color(0xFF0D0920)),
                contentAlignment = Alignment.Center,
            ) {
                // Local placeholder only — the real meter screen (S3/HIRED, sibling-owned) takes
                // over the instant navigate() fires above; this is just what's visible for the
                // ~550ms the scale-in is still running.
                Text(
                    "Starting meter…",
                    color = WheelColorsV2.goldCtaTop,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }

        if (showStartConfirm) {
            StartMeterConfirmDialog(
                tariff = uiState.tariff,
                onDismiss = { showStartConfirm = false },
                onSetPrice = {
                    showStartConfirm = false
                    showSetPriceEntry = true
                },
                onConfirm = {
                    showStartConfirm = false
                    onStartMeterTapped()
                },
            )
        }

        if (showSetPriceEntry) {
            SetPriceEntryScreen(
                onCancel = { showSetPriceEntry = false },
                onConfirm = { negotiatedTotal ->
                    showSetPriceEntry = false
                    onStartMeterTapped(negotiatedTotal = negotiatedTotal)
                },
            )
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
// Map background — real, interactive Mapbox map (added 2026-08-02 once a secret
// MAPBOX_DOWNLOADS_TOKEN became available, see settings.gradle.kts + data/remote/
// MapboxOfflineRegion.kt) via a classic `MapView` wrapped in Compose's `AndroidView` interop
// (more robust/predictable than betting on the exact API shape of Mapbox's separate Compose
// extension artifact, which this project does not depend on). Falls back to the previous Static
// Images API approach ([MapboxStaticImage], a plain HTTPS GET needing only the public token) if
// [BuildConfig.MAPBOX_ACCESS_TOKEN] is blank — i.e. this degrades gracefully through THREE tiers:
// real interactive+offline map -> static image -> [IllustrativeGridFallback], never a blank
// surface. Once a region has been downloaded via [au.com.threesixty.cabdispatch.data.remote.MapboxOfflineRegion],
// this same MapView serves it from the local tile cache automatically with zero network — no
// separate offline-mode code path needed here.
//
// Centered on the driver's real GPS fix as of 2026-08-03 (location/map-centering pass) — was
// unconditionally pinned to the fixed Sydney CBD coordinate ([SydneyCbdFallback]) before this,
// since the only location-adjacent data source wired into this app
// (`AppContainer.speedSource`) used to expose `speedKmh` only (see `domain/FareEngine.kt`'s
// `LocationFix`/`SpeedSource.locationFix` doc for how that changed). [SydneyCbdFallback] is KEPT,
// deliberately not deleted — it's still the real fallback center for first launch/permission-
// denied/indoors, i.e. whenever [MapBackground]'s `fix` parameter is `null`.
// ---------------------------------------------------------------------------------------------

@Composable
private fun MapBackground(fix: LocationFix?, modifier: Modifier = Modifier) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val centerLat = fix?.lat ?: SydneyCbdFallback.LAT
    val centerLng = fix?.lng ?: SydneyCbdFallback.LNG

    Box(
        modifier = modifier
            .background(Color(0xFF1C1730))
            .onGloballyPositioned { sizePx = it.size },
    ) {
        if (sizePx.width > 0 && sizePx.height > 0) {
            // Mapbox Maps SDK not bundled in this build (see MapboxOfflineRegion.kt), so the map
            // always renders via the Static Images API fallback (needs a real pixel size, hence the
            // sizePx gate). If MAPBOX_ACCESS_TOKEN is also blank the image request fails and this
            // degrades one more tier to IllustrativeGridFallback below — never a blank surface.
            // `remember(sizePx, centerLat, centerLng)` (not just `sizePx`) so a real fix landing —
            // or the driver moving meaningfully — requests a freshly-centered image; the Static
            // Images API is a plain fetched PNG (per MapboxStaticImage's own doc, "a fresh image
            // must be requested for a new center"), there's no live camera to just re-point.
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
                    else -> IllustrativeGridFallback()
                }
            }
        } else {
            // First composition, before onGloballyPositioned has reported a real size yet, AND
            // no token configured (if a token exists, the MapView branch above doesn't need a
            // pre-measured size and renders immediately).
            IllustrativeGridFallback()
        }

        // Driver position pin. Once a real fix exists the map itself is centered on it (see
        // above), so the driver's own marker belongs at dead-center, not the old fixed
        // illustrative offset — that offset only made sense back when the map was always
        // centered on the fixed Sydney CBD point regardless of the driver's actual location, i.e.
        // "you're somewhere near this map, not literally at the crosshair". Falls back to that
        // same illustrative offset whenever `fix` is null (no map-following center to be at the
        // middle of yet) so the no-GPS case still reads as "approximate", not "definitely here".
        DriverPositionPin(
            modifier = Modifier
                .align(Alignment.Center)
                .let { if (fix != null) it else it.offset(x = (-160).dp, y = (-30).dp) },
        )
    }
}

@Composable
private fun IllustrativeGridFallback(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawDiagonalGrid(angleDeg = -8f, spacingPx = 84f)
        drawDiagonalGrid(angleDeg = 96f, spacingPx = 168f)
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
                .background(WheelColorsV2.successFigure.copy(alpha = 0.18f)),
        )
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(WheelColorsV2.successFigure)
                .border(2.dp, Color.White, CircleShape),
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
    tripsHistoryVariant: Boolean = false,
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
    WheelSlot.TRIPS -> TripsSlotContent(navController, tripsHistoryVariant)
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
 * to [CabDispatchRoutes.TRIP_DETAIL]. [isHistoryVariant] picks which of the two v2 layouts to
 * render for this one real trips dataset — see [TripsWheelContent]'s `variant` doc and
 * [HomeDashboardV2ChromeOverlay]'s `dockTiles` doc for why "My Trips" and "History" share data.
 */
private class TripsSlotContent(
    private val navController: NavHostController,
    private val isHistoryVariant: Boolean,
) : WheelSlotContentProvider {
    override val eyebrow: String = "CURRENT SELECTION"
    override val hero: String = if (isHistoryVariant) "Trip History" else WheelSlot.TRIPS.label

    @Composable
    override fun Body() {
        TripsWheelContent(
            variant = if (isHistoryVariant) TripsPaneVariant.HISTORY else TripsPaneVariant.MY_TRIPS,
            onTripClick = { clientUuid ->
                TripDetailHandoff.set(clientUuid)
                navController.navigate(CabDispatchRoutes.TRIP_DETAIL)
            },
            onOpenActiveTrip = { navController.navigate(CabDispatchRoutes.HIRED) },
            onShiftReportClick = { navController.navigate(CabDispatchRoutes.SHIFT_REPORT) },
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
                color = WheelColorsV2.mutedFigure,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = uiState.isAvailable,
                    onCheckedChange = onAvailableChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = WheelColorsV2.goldCtaTop,
                        checkedTrackColor = Color(0xFF3A3160),
                    ),
                )
                Text(
                    if (uiState.isAvailable) "Available for dispatch" else "Off duty",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            uiState.availabilityError?.let { error ->
                Text(error, color = WheelColorsV2.dangerText, fontSize = 12.sp)
            }
            if (uiState.tariff == null) {
                Text("Loading cached tariff…", color = WheelColorsV2.mutedFigure, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Phase B v2: hosts whichever wheel-slot's real content composable is currently selected
 * ([wheelSlotContentProviderFor] — unchanged from v1), presented as a bottom sheet opened by a
 * dock-tile tap ([HomeDashboardV2ChromeOverlay]'s `onSelectWheelSlot`) instead of a permanent
 * side panel (the v2 Figma design has no such panel to host it in). Every sibling screen's own
 * wheel-slot content composable (AvailableTripsWheelContent, MessagesWheelContent,
 * TripsWheelContent, EarningsWheelContent, ShiftWheelContent) is reused completely unchanged —
 * only the container presenting them changed shape. Sheet chrome restyled to the v2 "Panel /
 * Content" glass look ([WheelColorsV2.panelGlass]/[glassBorder]) to match this pass's other 6 dock
 * destinations (Figma fileKey `JhEhok3n9bntRNS5Y1u3Yc`, nodes under major 34/35), instead of v1's
 * [WheelColors.surfaceRaised] bottom-sheet look.
 */
@Composable
private fun WheelSlotContentSheet(
    slot: WheelSlot,
    uiState: WheelDashboardUiState,
    navController: NavHostController,
    onAvailableChange: (Boolean) -> Unit,
    tripsHistoryVariant: Boolean,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(WheelColorsV2.panelGlass)
                .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                // Absorb clicks so tapping inside the sheet doesn't fall through to the scrim's
                // onDismiss above.
                .clickable(onClick = {}, indication = null, interactionSource = remember { MutableInteractionSource() })
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (slot == WheelSlot.TRIPS && tripsHistoryVariant) "Trip History" else slot.label,
                    color = Color.White.copy(alpha = 0.96f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                Text(
                    "✕",
                    color = Color.White.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            Spacer(Modifier.height(14.dp))
            val provider = wheelSlotContentProviderFor(
                slot = slot,
                uiState = uiState,
                navController = navController,
                onAvailableChange = onAvailableChange,
                tripsHistoryVariant = tripsHistoryVariant,
            )
            ContentPane(
                provider = provider,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            )
        }
    }
}

@Composable
private fun ContentPane(provider: WheelSlotContentProvider, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            provider.eyebrow,
            color = Color.White.copy(alpha = 0.55f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            provider.hero,
            color = Color.White.copy(alpha = 0.96f),
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
        )
        Spacer(Modifier.height(14.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            provider.Body()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Start Meter button
// ---------------------------------------------------------------------------------------------

/** Figma's own literal for the always-visible dashboard "START METER" CTA -- see the call site's
 * comment for why this stays a flat literal rather than [WheelColorsV2]'s gold-CTA gradient. */
private val FigmaStartMeterGold = Color(0xFFF4C300)

@Composable
private fun StartMeterButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(340.dp)
            .height(88.dp)
            .clip(RoundedCornerShape(18.dp))
            // Flat #F4C300, not WheelColorsV2's gold-CTA gradient -- matches every dashboard frame's
            // own "START METER" CTA (Figma nodes 6:81/7:81/7:171/8:181/8:289/9:81 etc: bg-[#f4c300]
            // flat) exactly, so kept as the literal value rather than switched to the v2 gradient.
            .background(if (enabled) FigmaStartMeterGold else FigmaStartMeterGold.copy(alpha = 0.4f))
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

// ---------------------------------------------------------------------------------------------
// Start Meter confirmation dialog (Phase B v2 reskin, Figma fileKey JhEhok3n9bntRNS5Y1u3Yc,
// node 36:2 "16 · Start Meter — Confirm")
// ---------------------------------------------------------------------------------------------

/**
 * Reskin of the plain Material [AlertDialog] the 2026-08-10 meter-polish pass added — same
 * confirm/cancel/set-price logic (all three callbacks are just forwarded to the exact call sites
 * the old dialog used), now drawn as the approved glass dialog-over-dimmed-map design instead of
 * a default AlertDialog. [MapBackground]/the dashboard chrome behind it are untouched; this is
 * purely an overlay drawn on top, matching how [SetPriceEntryScreen] and the transition overlay
 * above already layer over the same Box.
 *
 * Row data is real, not fabricated: [tariff]'s [TariffDto.name] and [TariffDto.flagFall]/
 * [TariffDto.distRate1] back the TARIFF/FLAGFALL rows, and "signed" reflects
 * [TariffDto.signature] actually being present (only ever populated once verified — see that
 * field's own doc on why a non-null signature means a verified signed tariff, not just "some
 * value came back"). There is deliberately no PASSENGER/job row here (unlike the Figma
 * reference's mock "Job 1374 · pickup 12 Railway Pde, Lakemba" copy) because this screen has no
 * real ambient "currently booked job" state to read from — confirmed by
 * [HomeDashboardV2ChromeOverlay]'s own doc comment on why its Trip Focus card doesn't render
 * either; inventing a fake passenger/pickup line here would be the same mistake in a different
 * spot. TODO(product decision needed if a live "booked" trip concept is desired here): add a
 * PASSENGER row once a real job-accepted/booked-trip state exists to read.
 */
@Composable
private fun StartMeterConfirmDialog(
    tariff: au.com.threesixty.cabdispatch.data.remote.TariffDto?,
    onDismiss: () -> Unit,
    onSetPrice: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xD9050410)) // rgba(5,4,12,0.85) glass scrim — see WheelColorsV2 doc
                .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(28.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}, // swallow taps so they don't fall through to the dismiss scrim
                )
                .padding(horizontal = 36.dp, vertical = 28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Are you sure you want to start the meter?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                )

                ConfirmDialogRow(
                    label = "Tariff",
                    value = if (tariff != null) {
                        tariff.name + if (tariff.signature != null) " · signed ✓" else ""
                    } else {
                        "No tariff loaded"
                    },
                )
                ConfirmDialogRow(
                    label = "Flagfall",
                    value = if (tariff != null) {
                        CURRENCY_SIGN + tariff.flagFall + " + " + CURRENCY_SIGN + tariff.distRate1 + "/km"
                    } else {
                        "—"
                    },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DialogSteelButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
                    DialogSteelButton(
                        text = "Set Price",
                        textColor = WheelColorsV2.amberFigure,
                        onClick = onSetPrice,
                        modifier = Modifier.weight(1f),
                    )
                    // Figma's confirm dialog (node 9:2) uses green for this specific "Start Meter"
                    // confirm action, not the dashboard's usual gold CTA -- gold stays reserved for
                    // the idle dashboard's own Start Meter button (see that button's own frame).
                    DialogGreenButton(text = "Start Meter", onClick = onConfirm, modifier = Modifier.weight(1.3f))
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialogRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.45f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            color = Color.White.copy(alpha = 0.94f),
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun DialogSteelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFFB7B0CF),
) {
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(WheelColorsV2.steelTileBrush)
            .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(WheelColorsV2.bevelHighlightBrush),
        )
        Text(text, color = textColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun DialogGoldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(WheelColorsV2.goldCtaBrush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(WheelColorsV2.bevelHighlightBrush),
        )
        Text(text, color = WheelColorsV2.onGoldCta, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

/** Matches Figma node 9:2's confirm-dialog "Start Meter" CTA — green, not the dashboard's usual
 * gold — see [StartMeterConfirmDialog]'s call site for why. */
@Composable
private fun DialogGreenButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(WheelColorsV2.greenCtaBrush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(WheelColorsV2.bevelHighlightBrush),
        )
        Text(text, color = WheelColorsV2.onGreenCta, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// "Set Price" -- negotiated/fixed-fare numeric entry (2026-08-10 meter-polish pass)
// ---------------------------------------------------------------------------------------------

/** Mirrors the backend's app.services.fare_engine.NEGOTIATED_TOTAL_MIN/_MAX exactly (per the
 * fare-set-price backend agent's own contract notes this same pass) -- client-side validation
 * here is purely a nicer UX (fail fast before ever reaching the network/outbox); the real
 * enforcement is still server-side (validate_negotiated_total, 422 on violation), since this
 * app's actual live network path for a trip (POST /v1/trips/sync) only fires well after this
 * screen is gone. */
private val NEGOTIATED_TOTAL_MIN = BigDecimal("1.00")
private val NEGOTIATED_TOTAL_MAX = BigDecimal("500.00")
private const val CURRENCY_SIGN = "$"

/**
 * Full-screen numeric-entry overlay for a negotiated/pre-arranged fare, opened from the
 * dashboard's Set Price secondary button. Deliberately a same-composable overlay (matching
 * this screen's existing Starting meter overlay pattern above), not a new
 * [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost] route -- this agent owns
 * only the dashboard shell, and adding a nav route touches shared infra (CabDispatchNavHost.kt)
 * several other concurrent passes may also be editing; a local overlay needs no such
 * coordination.
 *
 * onConfirm receives the amount as a decimal-as-string (45.00, never a raw/unvalidated
 * string) -- the confirm button below is only enabled once the input parses as a BigDecimal
 * within NEGOTIATED_TOTAL_MIN/NEGOTIATED_TOTAL_MAX.
 *
 * Phase B v2 reskin (Figma fileKey JhEhok3n9bntRNS5Y1u3Yc, node 36:154 "17 · Set Price
 * (negotiated fare)"): swaps the plain OutlinedTextField entry for the approved on-brand
 * numeric keypad + amount display + quick-amount presets, but keeps every real behavior from the
 * 2026-08-10 pass unchanged -- same [NEGOTIATED_TOTAL_MIN]/[NEGOTIATED_TOTAL_MAX] validation
 * window, same decimal-as-string [onConfirm] contract, same "does not include levies/tolls" +
 * NSW negotiated-fare compliance copy (now the amber compliance banner rather than a plain
 * caption). Typed digits are tracked as an integer cents buffer (keypad taps append/backspace a
 * digit, same mental model as a real taxi meter/POS keypad) and only ever converted to the
 * [BigDecimal] this screen already validated against once two decimal places exist -- no new
 * business rule, just a different input widget feeding the same validation.
 */
@Composable
private fun SetPriceEntryScreen(onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    // Cents-buffer keypad state: "6500" displays as "65.00". Backspace drops the last digit,
    // CLR resets to empty/zero. A preset tap sets the buffer directly to that whole-dollar amount.
    var centsInput by remember { mutableStateOf("") }
    val amount = remember(centsInput) {
        if (centsInput.isEmpty()) {
            null
        } else {
            centsInput.toBigIntegerOrNull()?.toBigDecimal()?.movePointLeft(2)
        }
    }
    val isValid = amount != null && amount >= NEGOTIATED_TOTAL_MIN && amount <= NEGOTIATED_TOTAL_MAX
    val displayAmount = (amount ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString()

    fun appendDigit(digit: Char) {
        // Cap at NEGOTIATED_TOTAL_MAX's digit count ($500.00 -> "50000", 5 digits) so the buffer
        // can never represent an amount the confirm button would reject anyway.
        if (centsInput.length >= 5) return
        val next = (centsInput + digit).trimStart('0').ifEmpty { "0" }
        centsInput = next
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColorsV2.pageBackgroundBrush)
            .padding(horizontal = 36.dp, vertical = 28.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Set price — negotiated fare",
                color = Color.White.copy(alpha = 0.96f),
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Fixed total agreed with the passenger before departure. Overrides the meter; " +
                    "recorded on the trip record as negotiated_total.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                // Left column: amount display, presets, compliance notice, cancel.
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0A0716))
                            .border(2.dp, Color(0x99F4C300), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$CURRENCY_SIGN $displayAmount",
                            color = Color(0xFFF4FAFF),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 44.sp,
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(listOf("45", "55", "65", "80", "100")) { preset ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(WheelColorsV2.steelTileBrush)
                                    .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(999.dp))
                                    .clickable { centsInput = preset + "00" }
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    "$CURRENCY_SIGN$preset",
                                    color = Color(0xFFF4FAFF),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    if (centsInput.isNotEmpty() && !isValid) {
                        Text(
                            "Enter an amount between " + CURRENCY_SIGN + NEGOTIATED_TOTAL_MIN +
                                " and " + CURRENCY_SIGN + NEGOTIATED_TOTAL_MAX,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    Row(
                        modifier = Modifier
                            .background(Color(0xE62A1E05), RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x66FFC94A), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("⚠", color = WheelColorsV2.amberFigure, fontSize = 15.sp)
                        Text(
                            "NSW: negotiated fares must be agreed before the trip starts and " +
                                "offered — never demanded. This price does not include levies " +
                                "and/or tolls.",
                            color = WheelColorsV2.amberFigure,
                            fontSize = 13.sp,
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .height(76.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(WheelColorsV2.steelTileBrush)
                            .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(14.dp))
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Cancel", color = Color(0xFFB7B0CF), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }

                // Right column: keypad + confirm CTA.
                Column(modifier = Modifier.weight(1f)) {
                    SetPriceKeypad(
                        onDigit = { appendDigit(it) },
                        onBackspace = { centsInput = centsInput.dropLast(1) },
                        onClear = { centsInput = "" },
                    )
                    Spacer(Modifier.height(16.dp))
                    val confirmBrush = if (isValid) {
                        WheelColorsV2.greenCtaBrush
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                WheelColorsV2.greenCtaTop.copy(alpha = 0.4f),
                                WheelColorsV2.greenCtaBottom.copy(alpha = 0.4f),
                            ),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(76.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(confirmBrush)
                            .clickable(enabled = isValid) { onConfirm(displayAmount) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(WheelColorsV2.bevelHighlightBrush),
                        )
                        Text(
                            "Confirm $CURRENCY_SIGN$displayAmount & Start Meter",
                            color = WheelColorsV2.onGreenCta,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetPriceKeypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "CLR"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (key in row) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(WheelColorsV2.steelTileBrush)
                            .border(1.dp, WheelColorsV2.glassBorder, RoundedCornerShape(14.dp))
                            .clickable {
                                when (key) {
                                    "⌫" -> onBackspace()
                                    "CLR" -> onClear()
                                    else -> onDigit(key.first())
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(WheelColorsV2.bevelHighlightBrush),
                        )
                        Text(
                            key,
                            color = Color.White.copy(alpha = 0.96f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (key == "CLR") 16.sp else 20.sp,
                        )
                    }
                }
            }
        }
    }
}

