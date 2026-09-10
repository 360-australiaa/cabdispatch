package au.com.threesixty.cabdispatch.ui.screens.hired

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.domain.TrafficCamera
import au.com.threesixty.cabdispatch.domain.TrafficHazard
import au.com.threesixty.cabdispatch.domain.TrafficHazardCategories
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.UpcomingHazard
import au.com.threesixty.cabdispatch.domain.fare.UpcomingToll
import au.com.threesixty.cabdispatch.domain.fare.upcomingHazard
import au.com.threesixty.cabdispatch.domain.fare.upcomingToll
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.Type
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.createGlowLine
import au.com.threesixty.cabdispatch.ui.theme.toMapboxHex
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.scalebar.scalebar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A lat/lng pair the backdrop can centre on / draw — one tiny shape for both the persisted
 * telemetry points ([TelemetryPointDto]) and the live fixes ([LocationFix]) so the polyline code
 * doesn't care which source a vertex came from.
 */
internal data class MapPoint(val lat: Double, val lng: Double)

/**
 * Non-interactive Mapbox backdrop behind the Meter screen's dial (Meter "game-level" visual pass,
 * 2026-09-03; navigator wiring, 2026-09-04) — this app's SECOND real `MapView`, a direct reuse of
 * the lifecycle/`rememberUpdatedState`/"frame once, then only touch layers on data change" pattern
 * proven in [au.com.threesixty.cabdispatch.ui.screens.zones.HeatMapTabContent] (the first).
 * Everything drawn is real data, or absent:
 *
 * - **Route polyline** — the HUD kit's two-layer glow line
 *   ([au.com.threesixty.cabdispatch.ui.theme.createGlowLine]: wide low-alpha halo under a thin
 *   bright line, explicit sort keys) in [CaptainPalette.hudAccent]. Every vertex is a real GPS
 *   fix. Two sources, concatenated in order:
 *   [persistedTrace] (the active `TripEntity.gpsTraceJson`, via
 *   [au.com.threesixty.cabdispatch.data.repository.TripRepository.observeActiveTripGpsTrace]) and
 *   then [liveTrace], a screen-local accumulation of `AppContainer.speedSource.locationFix`
 *   emissions collected while this pane is composed.
 *
 *   **Fixed (2026-09-07):** [liveTrace] used to be the ONLY real source here — `HiredViewModel`'s
 *   persister passed `newPoints = emptyList()` on every tick, so `gpsTraceJson` stayed `"[]"` for
 *   the whole live trip (a real fare-integrity bug, not just a cosmetic map gap — see
 *   `HiredViewModel.nextTracePoint`'s doc). [persistedTrace] now genuinely grows in near-real-time
 *   as the trip runs. [liveTrace] is kept rather than removed: it fills the small window between a
 *   fix landing and [au.com.threesixty.cabdispatch.data.repository.TripRepository.tick]'s Room
 *   write actually completing/propagating through [au.com.threesixty.cabdispatch.data.repository.TripRepository.observeActiveTripGpsTrace]'s
 *   `Flow`, so the drawn line never visibly lags the vehicle marker by a tick. The two sources
 *   overlap almost entirely in steady state (both are fed by the same real fix stream), which is
 *   harmless here — re-drawing the same already-drawn stretch of line changes no pixels.
 * - **Pickup pin** (green) — `TripContext.startLat/startLng`, the real trip start.
 * - **Vehicle marker** (purple) — the latest real fix, or the trip start before the first fix.
 * - **Destination pin** (red) — drawn ONLY when real coordinates arrive via [destLat]/[destLng] —
 *   today, the driver's own real Mapbox-geocoded pick from [MeterNavViewModel]'s destination search
 *   (`HiredScreen`'s nav layout wires this in); before a destination is chosen, the caller passes
 *   null and no pin is drawn — a pin at a guessed/geocoded spot would be a fabricated destination.
 * - **Planned route** ([plannedRoute]) — a second, dimmer glow line in [CaptainPalette.hudSweepMid]
 *   for [MeterNavViewModel]'s real Directions-API polyline, drawn under the driven trace. Empty
 *   (nothing drawn) until a route has actually been fetched.
 *
 * **Camera.** With no [plannedRoute] (the ordinary metered mockup-#3 backdrop) the camera frames
 * once on the first real position then eases to follow the vehicle at a fixed zoom ([BACKDROP_ZOOM]),
 * exactly as before. Once a real [plannedRoute] exists (mockup-#4's "TRIP IN PROGRESS" pane, real
 * turn-by-turn) the camera instead live-follows the vehicle at a tighter [NAVIGATION_ZOOM],
 * rotating to match the real direction of travel ([LocationFix.heading]) on every fix — direct
 * driver feedback (2026-09-06): a prior version of this only re-framed the WHOLE route on a
 * reroute and otherwise sat still, so an upcoming turn was never actually visible forming on the
 * map ahead of time. A live, rotating, direction-of-travel view is what every real turn-by-turn
 * app does while actively navigating, for exactly this reason.
 *
 * **Gestures (fixed 2026-09-07 — direct driver feedback: "ON THE RIGHT SIDE MAP, THERE SHOULD BE
 * ZOOM IN AND ZOOM OUT OR MOVE THE MAP FROM FINGER").** Pan (`scrollEnabled`) and every zoom
 * gesture (pinch, double-tap-in, two-finger-tap-out, press-and-drag "quick zoom") are now enabled —
 * this used to be a fully non-interactive backdrop the dial merely floated over; it no longer is.
 * Rotate/pitch stay OFF, deliberately: this is a tablet fixed in a car dash, not a hand-held map
 * app, and an accidental two-finger twist mid-drive would leave the map sitting in a confusing
 * off-north orientation with no obvious way back (the compass widget that would normally offer a
 * tap-to-reset is hidden, on purpose, a few lines below). Pan/zoom get an explicit, visible way
 * back instead — [followSuspended]/[RecentreButton], see below — so they don't need one.
 *
 * The auto-follow camera (both the ordinary [BACKDROP_ZOOM] follow and the navigator's
 * [NAVIGATION_ZOOM] live-follow-with-bearing) would otherwise fight a driver's pan/pinch the
 * moment the vehicle next moves ~10 m — exactly the failure mode this file's own follow-effect
 * comments already warn a re-centering camera creates. [followSuspended] fixes that: an
 * [OnMoveListener]/[OnScaleListener] pair flips it `true` the instant a drag or pinch actually
 * begins, and both follow effects below no-op entirely while it's set — the driver's pan/zoom then
 * simply stands, untouched, for as long as they want. It deliberately does NOT auto-resume when
 * the gesture ends (that would snap the camera straight back the moment a finger lifts, undoing
 * the very thing the driver just did); [RecentreButton] — shown only while [followSuspended] is
 * true — is the one explicit way back, in this app's existing small-pill HUD-affordance style
 * (`HiredScreen.kt`'s `ControlsHandle`). This applies uniformly to both camera modes: a driver who
 * pans away mid-navigation stays panned away, turn-by-turn included, until they tap RECENTRE —
 * coherent with, not a special case of, the ordinary-mode behaviour.
 *
 * Scale bar and compass are hidden (they'd sit under the dim overlay looking broken); Mapbox's
 * logo/attribution are left enabled per its terms, dimmed like the rest of the map. A flat dark
 * overlay ([dimAlpha]) is the only wash now — the radial vignette this used to also draw (for a
 * dial that used to float ON TOP of this map as a backdrop) was removed outright once the current
 * two-column layout gave the dial its own separate `GlassCard` (see [HiredScreen]'s class doc):
 * `HiredScreen.kt`'s "TRIP IN PROGRESS" pane is this composable's one and only caller, the map is
 * always the content here, never a backdrop, and a vignette darkening the corners of a driver-
 * facing map panel was pure lost legibility with nothing left depending on the look it made.
 * [dimAlpha] itself is a low, uniform brand-dark tint (0.08 at this call site) — real bug, found
 * live: the first turn-down from the historical 0.62 default was still "not visible properly" by
 * direct, repeated owner correction.
 *
 * **Custom style + live traffic overlay (live-map redesign, 2026-09-09 — owner: "I want to show
 * them the updated map so they can see the cameras icon, they can see the toll price, toll gate
 * is coming on the road upcoming... Not like you are just copying or pasting the Mapbox. It
 * should be proper custom design map and properly visible to the driver.").**
 *
 * Interpretation used here, stated explicitly because "not just Mapbox" has no literal reading —
 * this app has no other map engine, and building one from scratch was never in scope: Mapbox stays
 * the RENDERER; what had to stop being generic is the STYLE and the ON-MAP INFORMATION.
 * - **Style**: [BACKDROP_MAP_STYLE_URI] swaps in this app's own custom dark Mapbox Studio style —
 *   the SAME one the dashboard's Live Map / Trip Route Map / Toll Zone picker already ship
 *   (`dashboard/src/pages/live-map/mapInit.ts`'s `MAP_STYLE_URL`) — in place of the bare built-in
 *   `Style.DARK` this file used before. A suitable custom style already existed from that
 *   dashboard work; this reuses it rather than commissioning a second one, so the driver's meter
 *   map and the dispatcher's map read as one product.
 * - **Information**: three new custom-drawn layers, all sourced from data this app's own backend
 *   (never livetraffic.com/any third party directly) already routes through it —
 *   [au.com.threesixty.cabdispatch.sync.TrafficCache] (cameras + hazards, `GET /v1/traffic`) and
 *   the SAME on-device toll registry [au.com.threesixty.cabdispatch.domain.fare.onFix] auto-detects
 *   tolls against ([au.com.threesixty.cabdispatch.domain.fare.upcomingToll]):
 *   1. **Camera markers** — [rememberTrafficMarkerBitmaps]'s hand-drawn "lens" glyph (three nested
 *      circles: neon-cyan ring, dark body, light iris — no emoji/font glyph, so it renders
 *      identically on every device including the older tablets this fleet runs), filtered to
 *      roughly the current visible map bounds (see the marker-drawing [LaunchedEffect] below) so a
 *      small screen never tries to plot a whole region's cameras at once.
 *   2. **Hazard markers** — the same bitmap technique, a filled warning triangle, tinted by
 *      severity ([CaptainPalette.danger] for `incident`/`fire`, [CaptainPalette.warning] for
 *      `roadwork`/`flood`/`alpine`/`majorevent`).
 *   3. **Toll-ahead chip** ([TollAheadChip]) — "Toll ahead: <road> — <price>" when
 *      [au.com.threesixty.cabdispatch.domain.fare.upcomingToll] finds a real gantry within 2km
 *      roughly ahead of the vehicle's heading (see that function's own doc for why 2km). A
 *      one-shot fade in/out, never a loop — this screen's own precedent (`GlowingSpeedometer`'s
 *      "calm animations" doc in `Hud.kt`) after decorative looping motion was explicitly reverted
 *      here before for being distracting.
 *
 * **Never a tap target.** Cameras and hazards are drawn on [BackdropHolder.markers], a
 * [PointAnnotationManager] that NEVER gets a click listener added anywhere in this file — a tap
 * on a marker falls straight through to the map's own default handling, exactly as if nothing
 * were drawn there. [TollAheadChip] is likewise plain, non-interactive `Text`/`GlassCard` with no
 * `clickable` of its own. Both live inside THIS composable's own `MapView`/`Box`; the meter's real
 * Start/Stop/Close Fare controls are separate Compose composables elsewhere in `HiredScreen.kt`,
 * never children of this one — so nothing added here can ever sit in front of, or steal a gesture
 * from, those controls.
 */
@Composable
internal fun MeterBackdropMap(
    startLat: Double?,
    startLng: Double?,
    persistedTrace: List<TelemetryPointDto>,
    liveTrace: List<MapPoint>,
    liveFix: LocationFix?,
    modifier: Modifier = Modifier,
    destLat: Double? = null,
    destLng: Double? = null,
    plannedRoute: List<MapPoint> = emptyList(),
    dimAlpha: Float = 0.62f,
) {
    val routePoints = remember(persistedTrace, liveTrace) {
        persistedTrace.map { MapPoint(it.lat, it.lng) } + liveTrace
    }
    val vehicle: MapPoint? = liveFix?.let { MapPoint(it.lat, it.lng) }
        ?: routePoints.lastOrNull()
        ?: if (startLat != null && startLng != null) MapPoint(startLat, startLng) else null
    val pickup: MapPoint? = if (startLat != null && startLng != null) MapPoint(startLat, startLng) else null
    val destination: MapPoint? = if (destLat != null && destLng != null) MapPoint(destLat, destLng) else null
    val hasPlannedRoute = plannedRoute.size >= 2

    val mapHolder = remember { mutableStateOf<BackdropHolder?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var cameraFramed by remember { mutableStateOf(false) }
    // Auto-follow suspension — see this file's class doc, "Gestures" section, for the full story.
    // Set true the instant a drag/pinch begins (the OnMoveListener/OnScaleListener wired in the
    // AndroidView factory below), cleared only by an explicit RecentreButton tap.
    var followSuspended by remember { mutableStateOf(false) }
    // ~10 m quantisation of the follow target (1e-4 deg ≈ 11 m) — the camera-follow effect below
    // keys on this, not on the raw fix, so a stationary cab jittering by a metre or two doesn't
    // restart a camera ease every second.
    val followKey = vehicle?.let { "${(it.lat * 1e4).roundToInt()}:${(it.lng * 1e4).roundToInt()}" }

    // --- Live map redesign (2026-09-09): cameras, hazards, upcoming-toll advisory. See this
    // file's class doc, "Custom style + live traffic overlay" section, for the full design.
    val trafficOverlay = rememberTrafficOverlay(vehicle)
    val tollRegistry = rememberTollRegistrySnapshot()
    val upcoming = remember(tollRegistry, vehicle, liveFix?.heading) {
        vehicle?.let { v -> upcomingToll(tollRegistry, v.lat, v.lng, liveFix?.heading) }
    }
    // Held across the fade-out (see [TollAheadChip]'s own call site below) so the chip's content
    // doesn't blank instantly the moment [upcoming] itself goes null — it keeps showing the last
    // real advisory while the one-shot fade animates out.
    var displayedUpcoming by remember { mutableStateOf<UpcomingToll?>(null) }
    // Same pattern, one level over -- see [UpcomingHazardAdvisor.kt]'s own class doc for why this
    // exists (a marker icon alone names nothing) and [HazardAheadChip]'s call site for why it
    // stacks with, not replaces, the toll-ahead chip above it.
    val upcomingHazardAhead = remember(trafficOverlay.hazards, vehicle, liveFix?.heading) {
        vehicle?.let { v -> upcomingHazard(trafficOverlay.hazards, v.lat, v.lng, liveFix?.heading) }
    }
    var displayedUpcomingHazard by remember { mutableStateOf<UpcomingHazard?>(null) }
    LaunchedEffect(upcomingHazardAhead) { if (upcomingHazardAhead != null) displayedUpcomingHazard = upcomingHazardAhead }
    LaunchedEffect(upcoming) { if (upcoming != null) displayedUpcoming = upcoming }
    val markerBitmaps = rememberTrafficMarkerBitmaps()

    Box(modifier = modifier) {
        if (vehicle != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val mapView = MapView(ctx)
                    // Pan + every zoom gesture ON; rotate/pitch stay OFF — see this file's class
                    // doc, "Gestures" section, for the full reasoning (tablet-in-a-car, no easy
                    // undo for a stray rotation vs. an explicit RECENTRE undo for pan/zoom).
                    mapView.gestures.updateSettings {
                        scrollEnabled = true
                        pinchToZoomEnabled = true
                        doubleTapToZoomInEnabled = true
                        doubleTouchToZoomOutEnabled = true
                        quickZoomEnabled = true
                        rotateEnabled = false
                        pitchEnabled = false
                    }
                    // Suspends the auto-follow camera the moment the driver actually touches the
                    // map — see [followSuspended]'s own doc (this file's class doc, "Gestures"
                    // section) for why this deliberately does NOT auto-resume on gesture end.
                    // `onMove`/`onScale` both return without consuming the gesture (`false`/no-op)
                    // so the map's own default pan/pinch handling still runs exactly as it would
                    // with no listener at all — this only observes, never intercepts.
                    mapView.gestures.addOnMoveListener(object : OnMoveListener {
                        override fun onMoveBegin(detector: MoveGestureDetector) {
                            followSuspended = true
                        }
                        override fun onMove(detector: MoveGestureDetector): Boolean = false
                        override fun onMoveEnd(detector: MoveGestureDetector) {}
                    })
                    mapView.gestures.addOnScaleListener(object : OnScaleListener {
                        override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                            followSuspended = true
                        }
                        override fun onScale(detector: StandardScaleGestureDetector) {}
                        override fun onScaleEnd(detector: StandardScaleGestureDetector) {}
                    })
                    mapView.scalebar.enabled = false
                    mapView.compass.enabled = false
                    mapView.mapboxMap.loadStyle(BACKDROP_MAP_STYLE_URI) {
                        val lines = mapView.annotations.createPolylineAnnotationManager()
                        val circles = mapView.annotations.createCircleAnnotationManager()
                        // Cameras + hazards only — decorative, no click listener ever added (see
                        // this file's class doc, "Custom style + live traffic overlay" section):
                        // a tap here is never consumed, so it can never intercept a tap meant for
                        // the meter's own Start/Stop/Close Fare controls (those are separate
                        // Compose composables layered elsewhere in HiredScreen, not on this
                        // manager at all).
                        val markers = mapView.annotations.createPointAnnotationManager()
                        mapHolder.value = BackdropHolder(mapView, lines, circles, markers)
                        mapReady = true
                    }
                    mapView
                },
            )
        }
        // Real bug, found live (2026-09-10), repeated user correction ("not visible properly")
        // even after [dimAlpha] itself had already been turned down once for this screen (see
        // HiredScreen.kt's own call site doc): this used to ALSO draw an unconditional radial
        // vignette here — near-transparent centre, ~55% opaque at the corners — regardless of
        // [dimAlpha]. That vignette dates from the 2026-09-04b-and-earlier "dial floats over the
        // map" mockup (`MeterBackdropMap` as literal backdrop BEHIND the dial), which this file's
        // own class doc already documents as superseded: [HiredScreen]'s current two-column
        // layout gives the dial its own separate `GlassCard` and uses this composable only as a
        // real, clearly bounded map PANEL, never scenery behind anything. `HiredScreen.kt` is the
        // one and only caller of this composable in the app, so nothing still depends on the old
        // look — the vignette was pure dead weight darkening a quarter of a panel whose entire
        // job is being read by the driver. Removed outright rather than just tuned down; only the
        // flat [dimAlpha] wash remains, for a faint, uniform brand-dark tint.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CaptainPalette.hudBg.copy(alpha = dimAlpha.coerceIn(0f, 1f))),
        )
        // Top-end corner column: the trip/nav status pills and destination search bar already
        // dock top-start (see `HiredScreen.kt`'s map-panel layout) and the nav bottom bar docks
        // bottom-center, so this is the one consistently-empty corner regardless of nav mode —
        // now shared by three independent affordances, chips above button:
        // - The hazard-ahead advisory ([HazardAheadChip], 2026-09-10) — same calm one-shot
        //   fade-in/out as the toll chip below it, stacked ABOVE it: a real hazard on the road
        //   ahead is the more time-sensitive of the two to notice first.
        // - The toll-ahead advisory (see this file's class doc) — a calm, one-shot fade-in/out,
        //   never a looping/pulsing animation (this screen's own "calm animations" precedent —
        //   see [GlowingSpeedometer]'s doc in Hud.kt for the same rule applied to the dial).
        // - [RecentreButton] — the one explicit way back to auto-follow, see [followSuspended]'s
        //   doc (class doc, "Gestures" section) for why there is no automatic resume.
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = upcomingHazardAhead != null,
                enter = fadeIn(tween(TOLL_CHIP_FADE_MS)),
                exit = fadeOut(tween(TOLL_CHIP_FADE_MS / 2)),
            ) {
                displayedUpcomingHazard?.let { HazardAheadChip(it) }
            }
            AnimatedVisibility(
                visible = upcoming != null,
                enter = fadeIn(tween(TOLL_CHIP_FADE_MS)),
                exit = fadeOut(tween(TOLL_CHIP_FADE_MS / 2)),
            ) {
                displayedUpcoming?.let { TollAheadChip(it) }
            }
            if (followSuspended) {
                RecentreButton(onClick = { followSuspended = false })
            }
        }
    }

    // Camera/hazard markers — see this file's class doc, "Custom style + live traffic overlay"
    // section. Recomputed whenever the cached data changes or the camera settles on a new
    // position (mirroring the follow effects' own `followKey`/`hasPlannedRoute` keys), never on a
    // timer — decorative markers redraw when there is new data or the view has moved, nothing
    // else. deleteAll + recreate, same cost/shape as the lines+circles effect above: a handful of
    // markers per screen (filtered to the visible bounds below), nowhere near expensive.
    LaunchedEffect(mapReady, cameraFramed, trafficOverlay, followKey, hasPlannedRoute) {
        val holder = mapHolder.value ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        holder.markers.deleteAll()
        if (trafficOverlay.cameras.isEmpty() && trafficOverlay.hazards.isEmpty()) return@LaunchedEffect

        // The map's own real current viewport, not a guessed radius — see this file's class doc
        // for why "roughly the visible bounds" means asking Mapbox rather than assuming a fixed
        // window. Safe to call once the style/camera has actually settled (guarded by `mapReady`
        // above and `cameraFramed` in this effect's keys).
        if (!cameraFramed) return@LaunchedEffect
        val cam = holder.mapView.mapboxMap.cameraState
        val bounds = holder.mapView.mapboxMap.coordinateBoundsForCamera(
            CameraOptions.Builder()
                .center(cam.center)
                .zoom(cam.zoom)
                .bearing(cam.bearing)
                .pitch(cam.pitch)
                .build(),
        )
        val south = bounds.southwest.latitude()
        val west = bounds.southwest.longitude()
        val north = bounds.northeast.latitude()
        val east = bounds.northeast.longitude()
        fun withinView(lat: Double, lng: Double) = lat in south..north && lng in west..east

        val options = buildList {
            trafficOverlay.cameras.filter { withinView(it.latitude, it.longitude) }.forEach { camera ->
                add(
                    PointAnnotationOptions()
                        .withPoint(Point.fromLngLat(camera.longitude, camera.latitude))
                        .withIconImage(markerBitmaps.camera),
                )
            }
            trafficOverlay.hazards.filter { withinView(it.latitude, it.longitude) }.forEach { hazard ->
                // Falls back to the INCIDENT bitmap (the plain triangle) for a category this app
                // does not recognise yet -- see glyphPathFor's own doc for why that is the right
                // default rather than skipping the marker outright.
                val bitmap = markerBitmaps.hazardByCategory[hazard.category]
                    ?: markerBitmaps.hazardByCategory.getValue(TrafficHazardCategories.INCIDENT)
                add(
                    PointAnnotationOptions()
                        .withPoint(Point.fromLngLat(hazard.longitude, hazard.latitude))
                        .withIconImage(bitmap),
                )
            }
        }
        // No click listener is EVER added to `holder.markers` (see this file's class doc) — these
        // are pure decoration; a tap here falls through to the map's own default handling exactly
        // as if nothing were drawn, so it can never intercept a tap meant for the meter itself.
        if (options.isNotEmpty()) holder.markers.create(options)
    }

    // One-time initial framing (follow-cam mode only — see class doc), exactly like
    // HeatMapTabContent: first time the style is loaded AND we have a real position. Subsequent
    // moves go through the follow effect below. Skipped once a real planned route exists — that
    // case gets the bounds-fit camera instead (see the effect further down).
    LaunchedEffect(mapReady, vehicle != null, hasPlannedRoute) {
        if (cameraFramed || !mapReady || hasPlannedRoute) return@LaunchedEffect
        val holder = mapHolder.value ?: return@LaunchedEffect
        val target = vehicle ?: return@LaunchedEffect
        holder.mapView.mapboxMap.setCamera(
            CameraOptions.Builder().center(Point.fromLngLat(target.lng, target.lat)).zoom(BACKDROP_ZOOM).build(),
        )
        cameraFramed = true
    }

    // Camera follow (follow-cam mode only) — eases to the vehicle's real position whenever it
    // moves >~10 m (see followKey). Suspended while the driver has an active pan/pinch underway
    // (or left one standing) — see [followSuspended]'s own doc; [followSuspended] is in this
    // effect's keys so a RECENTRE tap (which flips it back to false) re-runs this immediately
    // against the current followKey rather than waiting for the next vehicle move.
    LaunchedEffect(mapReady, cameraFramed, followKey, hasPlannedRoute, followSuspended) {
        if (!mapReady || !cameraFramed || hasPlannedRoute || followSuspended) return@LaunchedEffect
        val holder = mapHolder.value ?: return@LaunchedEffect
        val target = vehicle ?: return@LaunchedEffect
        holder.mapView.camera.easeTo(
            CameraOptions.Builder().center(Point.fromLngLat(target.lng, target.lat)).zoom(BACKDROP_ZOOM).build(),
            MapAnimationOptions.mapAnimationOptions { duration(900) },
        )
    }

    // Live-follow-with-bearing camera (navigator mode only) — replaces a prior static bounds-fit
    // that framed the WHOLE route+vehicle+destination once and then only re-fit on a reroute (real
    // driver feedback, 2026-09-06: "before he take a right or left, he should see on the map that
    // he have to take a right or left" — a camera that only moves on reroute can't show that; the
    // driver needs a tight, live, direction-of-travel-oriented view, the same thing every real
    // turn-by-turn app (Google Maps, Waze) does while actively navigating). `bearing` comes from the
    // real fix ([LocationFix.heading], wired through from `Location.getBearing()` — see that
    // property's own doc) — genuinely null while stationary/no bearing fix, in which case the
    // camera keeps whatever bearing it last had rather than snapping to a fabricated north-up
    // orientation. `followKey` (~10m quantised, see its own doc above) plus a coarse (~5°) bearing
    // quantisation are both in the effect key so a stationary cab's GPS jitter doesn't restart an
    // ease every second. First arrival (not yet [cameraFramed]) jumps straight there with
    // `setCamera` — an ease from the ambient follow-cam's old wide zoom would otherwise be a slow,
    // disorienting zoom-and-spin right as the driver most needs their bearings.
    //
    // Suspended by [followSuspended] exactly like the ordinary follow-cam effect above — a driver
    // who pans/zooms away mid-navigation stays panned away (turn-by-turn included) until they tap
    // RECENTRE, same "no fighting the driver's own gesture" rule, applied uniformly rather than as
    // a nav-mode special case. followSuspended is in this effect's keys for the same "a RECENTRE
    // tap re-runs immediately" reason the ordinary follow-cam effect above has it.
    val bearingKey = liveFix?.heading?.let { (it / 5.0).roundToInt() * 5 }
    LaunchedEffect(mapReady, hasPlannedRoute, followKey, bearingKey, followSuspended) {
        if (!mapReady || !hasPlannedRoute || followSuspended) return@LaunchedEffect
        val holder = mapHolder.value ?: return@LaunchedEffect
        val target = vehicle ?: return@LaunchedEffect
        val camera = CameraOptions.Builder()
            .center(Point.fromLngLat(target.lng, target.lat))
            .zoom(NAVIGATION_ZOOM)
            .apply { liveFix?.heading?.let { bearing(it) } }
            .build()
        if (!cameraFramed) {
            holder.mapView.mapboxMap.setCamera(camera)
            cameraFramed = true
        } else {
            holder.mapView.camera.easeTo(camera, MapAnimationOptions.mapAnimationOptions { duration(700) })
        }
    }

    // Layer refresh on data change only (never moves the camera): the planned route (if any) and
    // the driven trace are each the kit's two-layer glow line (`createGlowLine` — wide low-alpha
    // halo under a narrow bright line, sort-keyed so the bright line stays on top), then
    // pickup + destination + vehicle circle annotations. deleteAll + recreate, same as the heat
    // map: a trip's trace is at most a few thousand points, well within what this costs.
    LaunchedEffect(mapReady, routePoints, plannedRoute, pickup, destination, vehicle) {
        val holder = mapHolder.value ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        holder.lines.deleteAll()
        holder.circles.deleteAll()

        // Planned (navigator) route first so the driven trace draws over it. Dimmer, lighter
        // purple: "where we're going" reads as secondary to "where we've actually been".
        if (plannedRoute.size >= 2) {
            holder.lines.createGlowLine(
                points = plannedRoute.map { Point.fromLngLat(it.lng, it.lat) },
                color = CaptainPalette.hudSweepMid,
                glowOpacity = 0.18,
                lineWidth = 3.0,
                lineOpacity = 0.55,
            )
        }
        if (routePoints.size >= 2) {
            holder.lines.createGlowLine(points = routePoints.map { Point.fromLngLat(it.lng, it.lat) })
        }
        val pins = buildList {
            if (pickup != null) {
                add(
                    CircleAnnotationOptions()
                        .withPoint(Point.fromLngLat(pickup.lng, pickup.lat))
                        .withCircleRadius(7.0)
                        .withCircleColor(CaptainPalette.success.toMapboxHex())
                        .withCircleStrokeWidth(3.0)
                        .withCircleStrokeColor(CaptainPalette.textPrimary.toMapboxHex()),
                )
            }
            if (destination != null) {
                add(
                    CircleAnnotationOptions()
                        .withPoint(Point.fromLngLat(destination.lng, destination.lat))
                        .withCircleRadius(7.0)
                        .withCircleColor(CaptainPalette.danger.toMapboxHex())
                        .withCircleStrokeWidth(3.0)
                        .withCircleStrokeColor(CaptainPalette.textPrimary.toMapboxHex()),
                )
            }
            if (vehicle != null) {
                // Soft halo under the vehicle dot — the "glowing marker" read, without a bitmap.
                add(
                    CircleAnnotationOptions()
                        .withPoint(Point.fromLngLat(vehicle.lng, vehicle.lat))
                        .withCircleRadius(18.0)
                        .withCircleColor(CaptainPalette.hudAccent.toMapboxHex())
                        .withCircleOpacity(0.25),
                )
                add(
                    CircleAnnotationOptions()
                        .withPoint(Point.fromLngLat(vehicle.lng, vehicle.lat))
                        .withCircleRadius(8.0)
                        .withCircleColor(CaptainPalette.hudAccent.toMapboxHex())
                        .withCircleStrokeWidth(3.0)
                        .withCircleStrokeColor(CaptainPalette.textPrimary.toMapboxHex()),
                )
            }
        }
        if (pins.isNotEmpty()) holder.circles.create(pins)
    }

    // Lifecycle wiring — verbatim from HeatMapTabContent.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mapView = mapHolder.value?.mapView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapHolder.value?.mapView?.onDestroy()
        }
    }
}

/**
 * Explicit "resume auto-follow" affordance — shown only while [MeterBackdropMap]'s own
 * [followSuspended] is true, i.e. only once the driver has actually panned/pinched the map away
 * from the live vehicle position (see that composable's class doc, "Gestures" section, for why
 * there is no automatic resume). Same pill shape/tokens as `HiredScreen.kt`'s private
 * `ControlsHandle` — this app's one existing "small tap affordance docked in an otherwise-empty
 * map/dial corner" convention — rather than a new visual language for this one button.
 */
@Composable
private fun RecentreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(99.dp))
            .background(CaptainPalette.raised.copy(alpha = 0.92f))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.GpsFixed,
            contentDescription = "Recentre map on vehicle",
            tint = CaptainPalette.hudAccent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "RECENTRE",
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

private const val BACKDROP_ZOOM = 14.5

/** Closer than [BACKDROP_ZOOM] — the ambient/ordinary-metered zoom is tuned for "where roughly am
 * I", this one for "which lane/turn is coming up", the same tighter-zoom convention every real
 * turn-by-turn app uses while actively navigating (see the live-follow camera effect's own doc). A
 * chosen default, not a value derived from any spec — tune if it reads too tight/too wide on a
 * real device. */
private const val NAVIGATION_ZOOM = 17.0

private data class BackdropHolder(
    val mapView: MapView,
    val lines: PolylineAnnotationManager,
    val circles: CircleAnnotationManager,
    /** Camera/hazard markers only — see this file's class doc, "Custom style + live traffic
     * overlay" section. No click listener is ever added to this manager, anywhere in this file. */
    val markers: PointAnnotationManager,
)

/**
 * Screen-local live-trace accumulator (see [MeterBackdropMap]'s class doc for why this exists
 * alongside the persisted trace). Collects `AppContainer.speedSource.locationFix` while composed,
 * appending a vertex only when the fix has genuinely moved (>~3 m) from the last kept vertex, so a
 * parked cab doesn't grow a thousand-point blob. Capped at [LIVE_TRACE_MAX] vertices (oldest
 * dropped) — a display buffer, never the trip's system of record.
 */
@Composable
internal fun rememberLiveTrace(): List<MapPoint> {
    val fix by AppContainer.speedSource.locationFix.collectAsState()
    var trace by remember { mutableStateOf<List<MapPoint>>(emptyList()) }
    LaunchedEffect(fix) {
        val f = fix ?: return@LaunchedEffect
        val last = trace.lastOrNull()
        if (last == null || abs(last.lat - f.lat) > 3e-5 || abs(last.lng - f.lng) > 3e-5) {
            trace = (trace + MapPoint(f.lat, f.lng)).takeLast(LIVE_TRACE_MAX)
        }
    }
    return trace
}

private const val LIVE_TRACE_MAX = 4000

// ============================================================================================
// Live map redesign (2026-09-09) — custom style, camera/hazard markers, toll-ahead advisory.
// See [MeterBackdropMap]'s class doc, "Custom style + live traffic overlay" section, for the
// full design and the explicit "not just Mapbox" interpretation this implements.
// ============================================================================================

/**
 * This app's own custom dark Mapbox Studio style — the SAME style the dashboard's Live Map / Trip
 * Route Map / Toll Zone picker already use (`dashboard/src/pages/live-map/mapInit.ts`'s
 * `MAP_STYLE_URL`), reused here rather than referenced fresh so the driver's meter map and the
 * dispatcher's dashboard map are visibly one product. Replaces the bare built-in `Style.DARK`
 * this file used before this pass.
 */
private const val BACKDROP_MAP_STYLE_URI = "mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za"

/** [TollAheadChip]'s fade-in duration; fade-out is half this — a quick, calm exit once the
 * gantry is behind the vehicle, never a lingering banner. */
private const val TOLL_CHIP_FADE_MS = 400

/** Cameras + hazards this composition currently has cached — see [rememberTrafficOverlay]. Both
 * lists are the FULL cached set (every bbox-fetched row), not yet filtered to the visible map
 * bounds; the marker-drawing effect in [MeterBackdropMap] does that filtering right before
 * drawing, against the map's real current viewport. */
private data class TrafficOverlayState(
    val cameras: List<TrafficCamera> = emptyList(),
    val hazards: List<TrafficHazard> = emptyList(),
)

/**
 * Loads [AppContainer.trafficCache]'s cached cameras/hazards into this composition, and performs
 * the "map panel open" refresh trigger — see `AppContainer.refreshTrafficData`'s own doc for why
 * every OTHER trigger point (login, reconnect, the 15-minute sync worker) goes through that
 * shared fire-and-forget helper while this one calls [au.com.threesixty.cabdispatch.sync.TrafficCache.refresh]
 * directly: this composable already has a real position (the trip's vehicle) to build a
 * locally-relevant bbox from, which none of those three non-Compose trigger points do.
 *
 * `LaunchedEffect(Unit)` — runs once per time this composable enters composition (i.e. once per
 * hired trip's map panel appearing), never on a timer and never re-triggered by [vehicle] moving
 * afterwards; a mid-trip refresh would need a genuinely new trigger point this task's own
 * instructions rule out inventing.
 */
@Composable
private fun rememberTrafficOverlay(vehicle: MapPoint?): TrafficOverlayState {
    var state by remember { mutableStateOf(TrafficOverlayState()) }
    LaunchedEffect(Unit) {
        runCatching { AppContainer.trafficCache.warmUp() }
        state = TrafficOverlayState(
            cameras = AppContainer.trafficCache.cachedCameras().orEmpty(),
            hazards = AppContainer.trafficCache.cachedHazards().orEmpty(),
        )
        runCatching { AppContainer.trafficCache.refresh(vehicle?.lat, vehicle?.lng) }
        state = TrafficOverlayState(
            cameras = AppContainer.trafficCache.cachedCameras().orEmpty(),
            hazards = AppContainer.trafficCache.cachedHazards().orEmpty(),
        )
    }
    return state
}

/**
 * The on-device toll registry snapshot [au.com.threesixty.cabdispatch.domain.fare.upcomingToll]
 * reads — the SAME cache [au.com.threesixty.cabdispatch.domain.fare.onFix] already uses for
 * auto-toll detection, loaded once per trip (matching that call site's own "loaded once, never
 * per GPS fix" contract — see [au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot]'s
 * doc). This file never refreshes the registry itself: it already refreshes on login, reconnect,
 * the sync worker and the pricing/GPS-simulator panels opening (see `AppContainer.kt`) — reading
 * whatever is already cached here is all the toll-ahead advisory needs.
 */
@Composable
private fun rememberTollRegistrySnapshot(): TollRegistrySnapshot {
    var snapshot by remember { mutableStateOf(TollRegistrySnapshot.EMPTY) }
    LaunchedEffect(Unit) {
        snapshot = runCatching { AppContainer.tollRegistryCache.snapshot() }.getOrDefault(TollRegistrySnapshot.EMPTY)
    }
    return snapshot
}

/** Calm, non-interactive "toll ahead" advisory — see [MeterBackdropMap]'s class doc for the
 * one-shot fade-in/out this is always shown through, never on its own clock. Plain [GlassCard]
 * (this kit's one floating-over-map surface), warm amber glow to read as an advisory without
 * competing with the danger-red destination pin already on the map. No `clickable` — informational
 * only, per this file's "never a tap target" rule. */
@Composable
private fun TollAheadChip(upcoming: UpcomingToll) {
    GlassCard(cornerRadiusDp = 14, glow = CaptainPalette.warning) {
        Row(
            // Real bug, found live (2026-09-09, GPS-simulated "Airport T1 pickup -> CBD" run): an
            // unbounded-width single-line Text here grows exactly as wide as its content needs —
            // fine for a short road name, but "Toll ahead: Sydney Harbour Bridge & Sydney Harbour
            // Tunnel — $7.41" is long enough to reach clear across this Column's TopEnd anchor and
            // overlap HiredScreen.kt's Trip/Zone pills docked TopStart, exactly the "one
            // consistently-empty corner" this file's class doc assumed never collides with
            // anything. Capped so a long road name wraps onto a second line instead of intruding
            // on the opposite corner — the chip stays anchored top-end either way.
            modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("Toll ahead: ")
                    append(upcoming.roadName)
                    upcoming.price?.let { append(" — ").append(it.toMoneyString()) }
                },
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                style = Type.tiny,
                color = CaptainPalette.textPrimary,
            )
        }
    }
}

/**
 * Calm, non-interactive "hazard ahead" advisory — see [UpcomingHazardAdvisor.kt]'s own class doc
 * for why this exists and [MeterBackdropMap]'s class doc for the one-shot fade this is always
 * shown through. Same shape as [TollAheadChip] (a plain [GlassCard], width-capped so a long
 * headline wraps rather than reaching across the panel), glow tinted by severity — danger-red for
 * [TrafficHazardCategories.INCIDENT]/[TrafficHazardCategories.FIRE], the same split the marker
 * icons themselves use, warning-amber otherwise — so a driver reads urgency from the chip's own
 * colour before reading a single word of it.
 *
 * [UpcomingHazard.speedLimitKmh], when present, is rendered as "reduced to N km/h here" —
 * deliberately not bare "N km/h" or "speed limit N", which would read as this road's ordinary
 * posted limit (a real, separate, larger data gap this app does not fill yet — see that field's
 * own doc). This is a temporary zone tied to the hazard itself, and must never be confused with
 * one.
 */
@Composable
private fun HazardAheadChip(upcoming: UpcomingHazard) {
    val severe = upcoming.category == TrafficHazardCategories.INCIDENT || upcoming.category == TrafficHazardCategories.FIRE
    GlassCard(cornerRadiusDp = 14, glow = if (severe) CaptainPalette.danger else CaptainPalette.warning) {
        Column(
            modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                buildString {
                    append(hazardCategoryLabel(upcoming.category))
                    append(" ahead")
                    upcoming.speedLimitKmh?.let { append(" — reduced to ").append(it).append(" km/h here") }
                },
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                style = Type.tiny,
                color = CaptainPalette.textPrimary,
            )
            // Real feed text only, never a fabricated summary -- see UpcomingHazard.headline's
            // own doc. Omitted entirely (not shown as an empty line) when the source published
            // none.
            upcoming.headline?.takeIf { it.isNotBlank() }?.let { headline ->
                Text(
                    headline,
                    fontFamily = InterFamily,
                    style = Type.tiny,
                    color = CaptainPalette.textSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Human-readable label for a [TrafficHazardCategories] constant — the one place this file turns
 * the feed's own lowercase machine category into words a driver reads at a glance. Falls back to
 * the raw [category] string (still real feed data, never fabricated) for one this app does not
 * yet recognise, same "degrade to honest, not blank" posture [glyphPathFor] already uses for the
 * matching icon. */
private fun hazardCategoryLabel(category: String): String = when (category) {
    TrafficHazardCategories.INCIDENT -> "Incident"
    TrafficHazardCategories.ROADWORK -> "Roadwork"
    TrafficHazardCategories.FLOOD -> "Flooding"
    TrafficHazardCategories.FIRE -> "Fire"
    TrafficHazardCategories.ALPINE -> "Alpine closure"
    TrafficHazardCategories.MAJOR_EVENT -> "Major event"
    else -> category
}

/** The custom-drawn marker bitmaps this file's camera/hazard layer uses — built once per
 * composition (colours resolved from [CaptainPalette] at the point of construction, so a
 * light/dark app-theme switch still redraws them, exactly like every other themed value here).
 *
 * [hazardByCategory] is keyed by [TrafficHazardCategories]'s own constants — one visually distinct
 * glyph per real category (see [buildHazardMarkerBitmap]'s own doc for why category, not just
 * severity, now decides the icon: real bug/gap found live 2026-09-10, owner asked for "road
 * closure, or accident... all needs to be load in our system" as visually distinguishable
 * information, and a flood closure drawing the identical plain triangle as a crash told a driver
 * nothing about which kind of hazard was ahead). */
private class TrafficMarkerBitmaps(
    val camera: Bitmap,
    val hazardByCategory: Map<String, Bitmap>,
)

/**
 * See [TrafficMarkerBitmaps]. Deliberately drawn from plain [android.graphics] primitives (nested
 * circles for the camera "lens", a filled triangle for a hazard) rather than an emoji glyph or a
 * Material icon glyph rendered to a bitmap: primitives render pixel-identically on every device
 * (this fleet's own memory notes an older SM-T575 tablet in service — emoji-font coverage is not
 * something to gamble a live meter screen's legibility on), and drawing the shape itself, not
 * borrowing a system glyph, is the literal "this app draws its own icons" reading of the owner's
 * "proper custom design" request.
 */
@Composable
private fun rememberTrafficMarkerBitmaps(): TrafficMarkerBitmaps {
    val density = LocalDensity.current
    val cameraRing = CaptainPalette.neonCyan.toArgb()
    val cameraBody = CaptainPalette.raised.toArgb()
    val cameraIris = CaptainPalette.textPrimary.toArgb()
    val cameraPupil = CaptainPalette.bg.toArgb()
    val hazardCautionFill = CaptainPalette.warning.toArgb()
    val hazardSevereFill = CaptainPalette.danger.toArgb()
    val hazardGlyph = CaptainPalette.textPrimary.toArgb()
    val hazardGlyphOutline = CaptainPalette.bg.toArgb()
    return remember(cameraRing, cameraBody, cameraIris, cameraPupil, hazardCautionFill, hazardSevereFill, hazardGlyph, hazardGlyphOutline, density) {
        // Severity (fill colour) and category (glyph) are independent axes — see
        // TrafficMarkerBitmaps' own doc. Same severity split MeterBackdropMap's class doc already
        // documents (incident/fire = danger, everything else = warning); only the glyph inside
        // changes per category now.
        fun fillFor(category: String) = if (category == TrafficHazardCategories.INCIDENT || category == TrafficHazardCategories.FIRE) {
            hazardSevereFill
        } else {
            hazardCautionFill
        }
        val hazardByCategory = TrafficHazardCategories.ALL.associateWith { category ->
            buildHazardMarkerBitmap(density, fillFor(category), hazardGlyph, hazardGlyphOutline, category)
        }
        TrafficMarkerBitmaps(
            camera = buildCameraMarkerBitmap(density, cameraRing, cameraBody, cameraIris, cameraPupil),
            hazardByCategory = hazardByCategory,
        )
    }
}

/** Marker diameter, dp — small and deliberately unobtrusive on a tablet screen (see this file's
 * class doc, "never a tap target" rule: these are decoration, not buttons, and are sized like it). */
private val MARKER_DIAMETER_DP = 24.dp

/** Three concentric circles — outer neon-cyan ring, dark body, light iris, dark pupil — reading as
 * a stylised camera lens without any text/emoji glyph. See [rememberTrafficMarkerBitmaps]'s doc. */
private fun buildCameraMarkerBitmap(density: Density, ringArgb: Int, bodyArgb: Int, irisArgb: Int, pupilArgb: Int): Bitmap {
    val d = with(density) { MARKER_DIAMETER_DP.toPx() }.roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val r = d / 2f
    val strokeW = d * 0.12f
    val bodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = bodyArgb
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(r, r, r - strokeW / 2f, bodyPaint)
    val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = ringArgb
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = strokeW
    }
    canvas.drawCircle(r, r, r - strokeW / 2f, ringPaint)
    val irisPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = irisArgb
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(r, r, r * 0.46f, irisPaint)
    val pupilPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = pupilArgb
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(r, r, r * 0.20f, pupilPaint)
    return bitmap
}

/**
 * A filled circle badge (severity-tinted) with a category-specific glyph on top, plus a thin dark
 * outline on the glyph for contrast against either fill colour in either app theme. See
 * [rememberTrafficMarkerBitmaps]'s doc for the fill-colour (severity) side of this.
 *
 * Real gap found live (2026-09-10): every hazard used to draw the identical plain triangle
 * regardless of [category] — a flood closure, a bushfire and an ordinary incident were visually
 * indistinguishable on the map, telling a driver only "something, somewhere" rather than which
 * kind of hazard is ahead (owner: "road closure, or accident... all needs to be load in our
 * system", asked as visually distinct information, not just one generic warning triangle). Each
 * glyph below is still a plain-primitives shape — no emoji/system glyph — same "this app draws
 * its own icons" reasoning [rememberTrafficMarkerBitmaps]'s own doc already gives for the camera
 * icon: a triangle (incident, the ordinary case), a flame (fire), a diamond (roadwork — the same
 * silhouette a real roadside roadwork sign uses), stacked wave lines (flood), a six-point asterisk
 * (alpine closure — reads as "snow/ice" without needing a snowflake's fine detail at 24dp), and a
 * five-point star (major event — the one category that is a scheduled closure, not a hazard, and
 * reads distinctly from all the ad hoc ones above).
 */
private fun buildHazardMarkerBitmap(density: Density, fillArgb: Int, glyphArgb: Int, outlineArgb: Int, category: String): Bitmap {
    val d = with(density) { MARKER_DIAMETER_DP.toPx() }.roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val r = d / 2f
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = fillArgb
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(r, r, r, fillPaint)

    val path = glyphPathFor(category, d, r)
    val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = outlineArgb
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = d * 0.05f
    }
    canvas.drawPath(path, outlinePaint)
    val glyphPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = glyphArgb
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawPath(path, glyphPaint)
    return bitmap
}

/** The one glyph [buildHazardMarkerBitmap] draws for [category] — see that function's own doc for
 * why each is a distinct plain-primitives shape. Falls back to the plain triangle (the existing,
 * pre-this-pass shape) for [TrafficHazardCategories.INCIDENT] and for any category this app does
 * not yet recognise, rather than drawing nothing — an unrecognised-but-real category from a future
 * feed change should still read as "a hazard, kind unspecified", never a blank/missing marker. */
private fun glyphPathFor(category: String, d: Int, r: Float): android.graphics.Path = when (category) {
    TrafficHazardCategories.FIRE -> android.graphics.Path().apply {
        // A flame: a rounded teardrop, point up, via two symmetric quadratic curves from the tip
        // down to a wide, flat-ish base — reads as "fire" at 24dp without needing an inner
        // highlight the way a two-tone flame icon normally would.
        val tipY = r * 0.30f
        val baseY = d - r * 0.42f
        val baseHalfW = r * 0.44f
        moveTo(r, tipY)
        quadTo(r + baseHalfW * 1.15f, r * 1.05f, r + baseHalfW, baseY)
        quadTo(r + baseHalfW * 0.4f, d - r * 0.18f, r, d - r * 0.22f)
        quadTo(r - baseHalfW * 0.4f, d - r * 0.18f, r - baseHalfW, baseY)
        quadTo(r - baseHalfW * 1.15f, r * 1.05f, r, tipY)
        close()
    }
    TrafficHazardCategories.ROADWORK -> android.graphics.Path().apply {
        // A diamond (rotated square) — the same silhouette a real roadside roadwork/warning sign
        // uses, visually distinct from the incident triangle at a glance.
        val half = r * 0.46f
        moveTo(r, r - half)
        lineTo(r + half, r)
        lineTo(r, r + half)
        lineTo(r - half, r)
        close()
    }
    TrafficHazardCategories.FLOOD -> android.graphics.Path().apply {
        // Two stacked wave lines, each a short S-curve given real width by tracing it there and
        // partway back at a slight vertical offset — a filled Path needs a closed, positive-area
        // outline; a single stroked line is not an option here since every other glyph in this
        // function is filled, not stroked, for a consistent weight in both app themes.
        val waveHalfW = r * 0.5f
        val waveThickness = d * 0.09f
        for (waveY in floatArrayOf(r * 0.72f, r * 1.28f)) {
            moveTo(r - waveHalfW, waveY)
            quadTo(r - waveHalfW * 0.4f, waveY - waveThickness * 1.6f, r, waveY)
            quadTo(r + waveHalfW * 0.4f, waveY + waveThickness * 1.6f, r + waveHalfW, waveY)
            lineTo(r + waveHalfW, waveY + waveThickness)
            quadTo(r + waveHalfW * 0.4f, waveY + waveThickness * 1.6f + waveThickness, r, waveY + waveThickness)
            quadTo(r - waveHalfW * 0.4f, waveY - waveThickness * 1.6f + waveThickness, r - waveHalfW, waveY + waveThickness)
            close()
        }
    }
    TrafficHazardCategories.ALPINE -> android.graphics.Path().apply {
        // A six-point asterisk (three crossing bars), each bar a thin filled rectangle rotated
        // 60deg from the last — reads as "snow/ice" at 24dp without a snowflake's fine detail,
        // which a 24dp bitmap cannot resolve cleanly anyway.
        val armLen = r * 0.62f
        val armHalfW = d * 0.045f
        for (angleDeg in intArrayOf(90, 30, 150)) {
            val rad = Math.toRadians(angleDeg.toDouble())
            val dx = (kotlin.math.cos(rad) * armLen).toFloat()
            val dy = (kotlin.math.sin(rad) * armLen).toFloat()
            val px = (-kotlin.math.sin(rad) * armHalfW).toFloat()
            val py = (kotlin.math.cos(rad) * armHalfW).toFloat()
            moveTo(r - dx + px, r - dy + py)
            lineTo(r + dx + px, r + dy + py)
            lineTo(r + dx - px, r + dy - py)
            lineTo(r - dx - px, r - dy - py)
            close()
        }
    }
    TrafficHazardCategories.MAJOR_EVENT -> android.graphics.Path().apply {
        // A five-point star — the one category that is a scheduled closure (a stadium event, a
        // parade), not an ad hoc hazard, and deliberately reads as "different in kind" from every
        // other glyph here.
        val outerR = r * 0.62f
        val innerR = outerR * 0.42f
        for (i in 0 until 10) {
            val rad = Math.toRadians((-90 + i * 36).toDouble())
            val radius = if (i % 2 == 0) outerR else innerR
            val x = r + (kotlin.math.cos(rad) * radius).toFloat()
            val y = r + (kotlin.math.sin(rad) * radius).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    // INCIDENT, and any category this app does not yet recognise -- see this function's own doc.
    else -> android.graphics.Path().apply {
        val triHalfW = r * 0.42f
        val triTop = r * 0.48f
        val triBottom = d - r * 0.55f
        moveTo(r, triTop)
        lineTo(r - triHalfW, triBottom)
        lineTo(r + triHalfW, triBottom)
        close()
    }
}
