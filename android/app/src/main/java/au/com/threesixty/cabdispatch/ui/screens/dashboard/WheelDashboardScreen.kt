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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.remote.MapboxStaticImage
import au.com.threesixty.cabdispatch.data.remote.SydneyCbdFallback
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.domain.DuressUiState
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
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
import java.math.BigDecimal
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
    // Real GPS fix (2026-08-03, map-centering/region-detection pass) — see MapBackground's doc
    // for how this replaces the fixed SydneyCbdFallback center it used to be pinned to
    // unconditionally.
    val locationFix by AppContainer.speedSource.locationFix.collectAsState()
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
            .background(WheelColors.bg)
            .onGloballyPositioned { rootCoordinates = it },
    ) {
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dashboardAlpha.value }) {
            MapBackground(fix = locationFix, modifier = Modifier.fillMaxSize())

            // Permanent split-panel body (per direct request): a LEFT content panel that hosts
            // identity/stats/status chrome plus whichever wheel slot's real screen is currently
            // selected, and a RIGHT instrument-console panel that always shows the wheel itself —
            // fixed in place regardless of which slot is displayed, rather than one of several
            // independently-floating overlays on the map the way this screen used to be laid
            // out. The live map stays full-bleed behind both panels (spec §5: "nothing is ever a
            // blank screen") — [LeftContentPanel]/[RightWheelPanel] use a semi-opaque surface so
            // map imagery underneath doesn't fight with card/wheel readability.
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TopStatusStrip(
                        status = uiState.status,
                        onZonesClick = { navController.navigate(CabDispatchRoutes.PLOT_ZONE) },
                        session = uiState.session,
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WheelColors.border))
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    LeftContentPanel(
                        uiState = uiState,
                        displayedSlot = displayedSlot,
                        navController = navController,
                        onAvailableChange = viewModel::setAvailable,
                        onIdentityClick = {
                            // Profile (Compliance + Settings, spec §5/§8 rows 20-21) has landed —
                            // see ui/screens/profile/ProfileScreen.kt. Was routed to the
                            // standalone Settings (S6) screen as a stand-in until then.
                            navController.navigate(CabDispatchRoutes.PROFILE)
                        },
                        modifier = Modifier.weight(0.42f).fillMaxHeight(),
                    )

                    RightWheelPanel(
                        controller = wheelController,
                        displayedSlotIndex = displayedSlotIndex,
                        onSettled = { index -> displayedSlotIndex = index },
                        modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    )
                }
            }

            StartMeterButton(
                enabled = startMeterEnabled,
                // Gated behind a confirmation dialog now (2026-08-10 meter-polish pass, matching
                // a real competitor taxi-meter UX pattern) instead of firing on tap directly —
                // see the AlertDialog below. Set Price (below) deliberately does NOT go through
                // this same dialog: its own numeric-entry screen already ends in an explicit
                // "Start meter" confirm action, so a second confirmation on top of that would be
                // pure friction, not an extra safety check.
                onClick = { showStartConfirm = true },
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
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp),
            ) {
                Text(
                    "Set Price",
                    color = if (startMeterEnabled) WheelColors.goldSoft else WheelColors.textMuted,
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

        if (showStartConfirm) {
            AlertDialog(
                onDismissRequest = { showStartConfirm = false },
                title = { Text("Start meter?") },
                text = { Text("Are you sure you want to start the meter?") },
                confirmButton = {
                    TextButton(onClick = {
                        showStartConfirm = false
                        onStartMeterTapped()
                    }) { Text("Start meter") }
                },
                dismissButton = {
                    TextButton(onClick = { showStartConfirm = false }) { Text("Cancel") }
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
            .background(WheelColors.surface)
            .onGloballyPositioned { sizePx = it.size },
    ) {
        if (BuildConfig.MAPBOX_ACCESS_TOKEN.isNotBlank()) {
            RealMapboxMapView(fix = fix, modifier = Modifier.fillMaxSize())
        } else if (sizePx.width > 0 && sizePx.height > 0) {
            // No token at all — Static Images API fallback (needs a real pixel size, hence the
            // sizePx gate; MapView above doesn't need this, it lays itself out normally).
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

/** Shared by [RealMapboxMapView]'s create-time and recenter-time camera setup — real [fix] if
 * present, [SydneyCbdFallback] otherwise. */
private fun cameraFor(fix: LocationFix?): CameraOptions =
    CameraOptions.Builder()
        .center(Point.fromLngLat(fix?.lng ?: SydneyCbdFallback.LNG, fix?.lat ?: SydneyCbdFallback.LAT))
        .zoom(SydneyCbdFallback.ZOOM)
        .build()

@Composable
private fun RealMapboxMapView(fix: LocationFix?, modifier: Modifier = Modifier) {
    // Root-cause fix (2026-08-02, found via a real device/emulator report — "map area renders
    // solid black"): a View-based Mapbox `MapView` embedded via `AndroidView` does NOT get
    // Activity/Fragment lifecycle callbacks automatically the way it would if inflated via XML in
    // an Activity that itself forwards onStart/onResume/etc. Without those forwarded calls, the
    // MapView allocates its rendering surface but never actually starts its GL render loop — the
    // symptom is exactly a solid black view, not a crash, not an error, nothing in Logcat pointing
    // at "you forgot this". This is a well-known Mapbox-in-Compose gotcha, not a token/network
    // issue (a bad/missing token would show as a grey/placeholder tile pattern or a style-load
    // error, not solid black). Fixed by observing the local lifecycle and forwarding every stage.
    val mapView = remember { mutableStateOf<MapView?>(null) }
    // androidx.compose.ui.platform.LocalLifecycleOwner (not the newer androidx.lifecycle.compose
    // one, which lives in a separate lifecycle-runtime-compose artifact this project doesn't
    // declare) — this one ships with core androidx.compose.ui:ui, already a dependency via the
    // compose-bom, so no new artifact needed.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).also { view ->
                mapView.value = view
                view.mapboxMap.loadStyle(Style.DARK)
                view.mapboxMap.setCamera(cameraFor(fix))
                // Background map, not a navigation tool — the wheel/dashboard chrome sits on top
                // of this, so gestures reaching the map underneath a control would be confusing.
                // Leave pan/zoom enabled for the driver to glance around, per spec §5 ("live map
                // is the permanent background... nothing is ever a blank screen, there's always
                // spatial context") — this is deliberate, not an oversight to lock it down later.
                //
                // No manual onStart()/onResume() call needed here — androidx.lifecycle.Lifecycle
                // brings a freshly-added LifecycleEventObserver (below) up to the CURRENT state
                // automatically (documented behavior), so the DisposableEffect's observer alone
                // covers both "already resumed when this view is created" and every future
                // pause/resume/destroy.
            }
        },
    )

    // 2026-08-03: recenters the live camera the FIRST time a real fix becomes available (most
    // importantly a few seconds after this view is first created with the SydneyCbdFallback
    // center — permission grant takes a moment, the first fix takes longer still) — and again on
    // any later "no fix -> fix" transition (e.g. GPS regained after a signal loss). Keyed on
    // `fix != null` rather than on `fix` itself, so this deliberately does NOT re-fire on every
    // ~1Hz location tick while the vehicle is moving: the whole point of leaving pan/zoom enabled
    // on this background map (see the factory block's comment above, "let the driver glance
    // around") would be defeated if the camera snapped back to the driver every single second —
    // a genuine continuous follow-me camera is a real, separate UX decision for whoever owns this
    // screen next, not an accidental side effect of wiring GPS in.
    LaunchedEffect(fix != null) {
        if (fix != null) mapView.value?.mapboxMap?.setCamera(cameraFor(fix))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            val view = mapView.value ?: return@LifecycleEventObserver
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> view.onStart()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> view.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> view.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> view.onStop()
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.value?.onDestroy()
        }
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
private fun TopStatusStrip(
    status: DashboardStatusStrip,
    onZonesClick: () -> Unit,
    /** Feeds the shift-duration countdown chip below (2026-08-10 meter-polish pass) — null
     * (chip hidden) whenever there's no signed-in session, same as every other session-gated
     * piece of UI on this screen. */
    session: DriverSession?,
    modifier: Modifier = Modifier,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // Plot / Statistics entry point (zone-based demand screens, matching a real
            // competitor taxi meter -- backend/app/api/v1/zones.py) -- see
            // CabDispatchRoutes.PLOT_ZONE's own doc for why this lives here rather than as a 7th
            // wheel slot. A plain clickable label, same visual weight as the status chips beside
            // it rather than a full button, so it doesn't visually compete with StartMeterButton.
            Row(
                modifier = Modifier.clickable(onClick = onZonesClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("📍", fontSize = 11.sp)
                Text("Zones", color = WheelColors.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            ShiftCountdownChip(session = session, now = now)
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

/**
 * Live "time remaining before shift-duration limit" chip (2026-08-10 meter-polish pass,
 * matching real competitor taxi-meter UX). Recomputed off [now] — [TopStatusStrip]'s own 30s
 * ticker above — rather than running an independent timer, so this stays a "live" countdown
 * (updates on the same cadence the clock in this strip already does) without adding a second
 * `LaunchedEffect`/coroutine to this composable. See [ShiftDurationLimit]'s own doc for the
 * honest simplification behind the limit value itself (a hardcoded mirror of the backend's
 * default `FATIGUE_SHIFT_DURATION_LIMIT_HOURS`, not a live-read tenant config value — no
 * endpoint exposes that to this client today).
 */
@Composable
private fun ShiftCountdownChip(session: DriverSession?, now: LocalDateTime) {
    val remaining = remember(session?.shiftStartAt, now) {
        ShiftDurationLimit.remaining(session?.shiftStartAt)
    } ?: return

    val overdue = remaining.isNegative
    val magnitude = if (overdue) remaining.negated() else remaining
    val hours = magnitude.toHours()
    val minutes = magnitude.toMinutes() % 60
    val label = if (overdue) {
        "Shift limit exceeded (" + hours + "h " + minutes + "m over)"
    } else {
        "Shift: " + hours + "h " + minutes + "m left"
    }
    // Reuses StatusChip's dot+label visual language rather than a bespoke chip shape — this is
    // still "one glance-zone status dot", just fed by a client-side computation instead of a
    // device/network signal. `ok = !overdue` so it reads red exactly when the limit has actually
    // been passed, amber/warn styling is StatusChip's own call (it only has an ok/not-ok binary
    // today — a three-state "approaching the limit" warn tier is a real, deliberately-deferred
    // follow-up, not silently assumed handled).
    StatusChip(label, ok = !overdue)
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

/**
 * Permanent LEFT panel (per direct request replacing the old map-overlay-cards layout): identity
 * + quick stats header row, the off-duty/available status card, then whichever wheel slot's real
 * screen is currently selected ([ContentPane], via the same [wheelSlotContentProviderFor] this
 * screen already used) filling the rest of the panel's height. `bottom = 130.dp` on the outer
 * padding keeps content clear of [StartMeterButton], which floats over BOTH panels at
 * [Alignment.BottomCenter] of the whole screen, not just this one.
 */
@Composable
private fun LeftContentPanel(
    uiState: WheelDashboardUiState,
    displayedSlot: WheelSlot,
    navController: NavHostController,
    onAvailableChange: (Boolean) -> Unit,
    onIdentityClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(WheelColors.surfaceRaised.copy(alpha = 0.92f))
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 130.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IdentityCard(session = uiState.session, onClick = onIdentityClick)
            QuickStatsCard(stats = uiState.todayStats)
        }
        Spacer(Modifier.height(14.dp))
        StatusCard(isAvailable = uiState.isAvailable)
        Spacer(Modifier.height(20.dp))

        Crossfade(
            targetState = displayedSlot,
            animationSpec = tween(WheelGeometry.CONTENT_FADE_MS),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "wheelContentPane",
        ) { slot ->
            val provider = wheelSlotContentProviderFor(
                slot = slot,
                uiState = uiState,
                navController = navController,
                onAvailableChange = onAvailableChange,
            )
            ContentPane(provider = provider, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Permanent RIGHT panel: just [WheelArea], centered in its own dedicated region regardless of
 * which slot [LeftContentPanel] is currently showing — this is what makes the wheel "always
 * visible on the right" a real structural guarantee rather than an incidental effect of where it
 * happened to be drawn. Centered (not bottom-anchored) per the two options in the request
 * ("bottom or centre right") — trivial to change [Alignment.Center] to
 * `Alignment.BottomCenter` here if bottom-anchored is preferred instead.
 */
@Composable
private fun RightWheelPanel(
    controller: WheelController,
    displayedSlotIndex: Int,
    onSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        WheelArea(
            controller = controller,
            displayedSlotIndex = displayedSlotIndex,
            onSettled = onSettled,
        )
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
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
            // Dimensional rim + spokes — a 2026-08-02 design pass moving away from the plain
            // flat ring-of-dots toward a literal steering-wheel read (rim/spokes/hub), per a
            // reference mockup. Purely a rendering layer — geometry/interaction below is
            // unchanged, spokes are drawn at the exact same angles WheelSlotDot places its icons
            // at, so they always point precisely at each icon regardless of wheel rotation.
            WheelRimAndSpokes(rotationDegrees = controller.rotationDegrees)

            WheelSlot.entries.forEach { slot ->
                val angleDeg = WheelGeometry.slotScreenAngleDeg(slot.index, controller.rotationDegrees)
                WheelSlotDot(
                    slot = slot,
                    selected = slot.index == displayedSlotIndex,
                    angleDeg = angleDeg,
                )
            }

            WheelHub(modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * Rim (outer ring with a soft radial fill + a highlighted edge, suggesting a moulded/
 * leather-wrapped wheel rather than a flat line) + spokes (thin gradient lines from center to
 * each icon position). Drawn once as a single [Canvas] covering the whole wheel, rather than
 * per-slot, since the rim needs the full circle geometry in one pass anyway.
 */
@Composable
private fun WheelRimAndSpokes(rotationDegrees: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Rim: a soft radial fill (subtle depth, not flat) plus a brighter ring right at the
        // edge so it reads as a moulded rim, not a flat disc with a stroke.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(WheelColors.surfaceRaised.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        drawCircle(
            color = WheelColors.borderStrong,
            radius = radius,
            center = center,
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = WheelColors.gold.copy(alpha = 0.14f),
            radius = radius - 3.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )

        // Spokes: one line per slot, center -> icon position, at the slot's CURRENT (rotated)
        // screen angle — reuses the exact same angle math WheelSlotDot positions icons with, so
        // a spoke always points straight at its icon.
        WheelSlot.entries.forEach { slot ->
            val angleDeg = WheelGeometry.slotScreenAngleDeg(slot.index, rotationDegrees)
            val (dx, dy) = WheelGeometry.offsetForAngle(angleDeg, WheelGeometry.ICON_RING_RADIUS_DP)
            val end = Offset(center.x + dx.dp.toPx(), center.y + dy.dp.toPx())
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(WheelColors.border, WheelColors.borderStrong),
                    start = center,
                    end = end,
                ),
                start = center,
                end = end,
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Small decorative hub at the wheel's center — purely visual (no interaction), completes the
 * steering-wheel read the rim/spokes above establish. */
@Composable
private fun WheelHub(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .shadow(elevation = 8.dp, shape = CircleShape, ambientColor = WheelColors.gold, spotColor = WheelColors.gold)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(WheelColors.surfaceSunken, WheelColors.bg),
                ),
            )
            .border(1.dp, WheelColors.gold.copy(alpha = 0.5f), CircleShape),
    )
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
            // Every icon glows, not just the selected one (per the reference mockup's "all spoke
            // icons lit" look) — selected gets a stronger gold glow, unselected a faint white
            // one so the wheel doesn't look "dead" between selections.
            .shadow(
                elevation = if (selected) 14.dp else 4.dp,
                shape = CircleShape,
                ambientColor = if (selected) WheelColors.gold else WheelColors.meterLedWhite,
                spotColor = if (selected) WheelColors.gold else WheelColors.meterLedWhite,
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
            slotIcon(slot),
            fontSize = if (selected) 26.sp else 20.sp,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = (if (selected) WheelColors.gold else WheelColors.meterLedWhite).copy(alpha = 0.8f),
                    blurRadius = if (selected) 18f else 8f,
                ),
            ),
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

/**
 * One glyph per wheel slot — emoji rather than a Material icon set on purpose: this codebase
 * already uses emoji for iconography elsewhere (see `LoginVehicleBindScreen.kt`'s "🔑"/"🚗"/"📋"
 * step icons, `LoginTopBar`'s "⚙") and this project has no `material-icons-extended` dependency
 * (only the small core set ships with material3 by default), so reaching for named icons like
 * `Icons.Filled.Email` here would risk an unresolved-reference compile error this environment
 * can't check. Zero new dependency, consistent with the rest of the app, matches the reference
 * mockup's "one icon per spoke" idea closely enough without betting on exact icon availability.
 */
private fun slotIcon(slot: WheelSlot): String = when (slot) {
    WheelSlot.OFF_DUTY_AVAILABLE -> "🚕"
    WheelSlot.AVAILABLE_TRIPS -> "📋"
    WheelSlot.MESSAGES -> "✉️"
    WheelSlot.TRIPS -> "🧾"
    WheelSlot.EARNINGS -> "💰"
    WheelSlot.SHIFT -> "⏱️"
}

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
 */
@Composable
private fun SetPriceEntryScreen(onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val amount = remember(input) { input.toBigDecimalOrNull() }
    val isValid = amount != null && amount >= NEGOTIATED_TOTAL_MIN && amount <= NEGOTIATED_TOTAL_MAX

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg)
            .padding(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TextButton(onClick = onCancel) { Text("< Cancel", color = WheelColors.textSecondary) }
            Spacer(Modifier.height(12.dp))
            Text(
                "Set Price",
                color = WheelColors.textPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Enter the agreed fare for this trip.",
                color = WheelColors.textSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    val dotCount = value.count { it == '.' }
                    val decimalsOk = value.substringAfter('.', "").length <= 2
                    if (value.all { it.isDigit() || it == '.' } && dotCount <= 1 && decimalsOk) {
                        input = value
                    }
                },
                label = { Text("Fare amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (input.isNotEmpty() && !isValid) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Enter an amount between " + CURRENCY_SIGN + NEGOTIATED_TOTAL_MIN +
                        " and " + CURRENCY_SIGN + NEGOTIATED_TOTAL_MAX,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
            // Reference-screenshot wording, matched verbatim per the task brief.
            Text(
                "This price does not include levies and/or tolls",
                color = WheelColors.textMuted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.weight(1f))

            val displayAmount = amount?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isValid) WheelColors.gold else WheelColors.gold.copy(alpha = 0.4f))
                    .clickable(enabled = isValid) {
                        if (displayAmount != null) onConfirm(displayAmount)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (displayAmount != null) {
                        "START METER AT " + CURRENCY_SIGN + displayAmount
                    } else {
                        "START METER"
                    },
                    color = CabDispatchColors.Indigo,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}
