package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.Type
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.createGlowLine
import au.com.threesixty.cabdispatch.ui.theme.toMapboxHex
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
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
 * logo/attribution are left enabled per its terms, dimmed like the rest of the map. A dark overlay
 * ([dimAlpha]) + radial vignette on top keeps the dial legible over street detail — the mockup-#4
 * "TRIP IN PROGRESS" pane, where the map is the content rather than a backdrop, passes a lighter
 * wash.
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
                    mapView.mapboxMap.loadStyle(Style.DARK) {
                        val lines = mapView.annotations.createPolylineAnnotationManager()
                        val circles = mapView.annotations.createCircleAnnotationManager()
                        mapHolder.value = BackdropHolder(mapView, lines, circles)
                        mapReady = true
                    }
                    mapView
                },
            )
        }
        // Dim + vignette so the dial reads on top of street detail. Two layers: a flat bg wash
        // ([dimAlpha], ~62% by default), then a radial fade that's near-transparent behind the
        // dial's centre and darker at the corners — the "map recedes, dial floats" look from the
        // mockup.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CaptainPalette.hudBg.copy(alpha = dimAlpha.coerceIn(0f, 1f)))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, CaptainPalette.bg.copy(alpha = 0.55f)),
                    ),
                ),
        )
        // The one explicit way back to auto-follow — see [followSuspended]'s doc (class doc,
        // "Gestures" section) for why there is no automatic resume. Top-end corner: the
        // trip/nav status pills and destination search bar already dock top-start (see
        // `HiredScreen.kt`'s map-panel layout), so this is the one consistently-empty corner
        // regardless of nav mode.
        if (followSuspended) {
            RecentreButton(
                onClick = { followSuspended = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }
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
