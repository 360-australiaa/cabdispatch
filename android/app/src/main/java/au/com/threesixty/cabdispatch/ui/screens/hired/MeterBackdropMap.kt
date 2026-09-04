package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.createGlowLine
import au.com.threesixty.cabdispatch.ui.theme.toMapboxHex
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
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
 *   emissions collected while this pane is composed. The second source exists because of an honest,
 *   pre-existing gap this pass does NOT fix (HiredViewModel is read-only for it): the live meter's
 *   persister passes `newPoints = emptyList()` on every tick, so `gpsTraceJson` stays `"[]"` for
 *   the whole live trip today. The polyline is therefore drawn from the same real fixes the fare
 *   engine ticks against — just held in memory for this screen rather than read back from Room.
 *   Once something starts feeding real points into `TripRepository.tick`, the persisted half
 *   simply grows and the live half keeps appending after it.
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
 * once on the first real position then eases to follow the vehicle at a fixed zoom, exactly as
 * before. Once a real [plannedRoute] exists (mockup-#4's "TRIP IN PROGRESS" pane) the camera
 * instead fits the whole picture — route + vehicle + destination — via `MapboxMap
 * .cameraForCoordinates(points, EdgeInsets, bearing = null, pitch = null)`, re-fit whenever the
 * route reference changes (a fresh route or a reroute), so the driver sees the full trip context
 * rather than a tight follow-cam that would hide the destination off-screen.
 *
 * Gestures are fully disabled (`gestures.updateSettings`) — this is a backdrop the dial floats
 * over, not a map to pan. Scale bar and compass are hidden (they'd sit under the dim overlay
 * looking broken); Mapbox's logo/attribution are left enabled per its terms, dimmed like the rest
 * of the map. A dark overlay ([dimAlpha]) + radial vignette on top keeps the dial legible over
 * street detail — the mockup-#4 "TRIP IN PROGRESS" pane, where the map is the content rather than
 * a backdrop, passes a lighter wash.
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
                    mapView.gestures.updateSettings {
                        scrollEnabled = false
                        pinchToZoomEnabled = false
                        rotateEnabled = false
                        pitchEnabled = false
                        doubleTapToZoomInEnabled = false
                        doubleTouchToZoomOutEnabled = false
                        quickZoomEnabled = false
                    }
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
    // moves >~10 m (see followKey). Gestures are disabled, so there is no driver pan to fight with.
    LaunchedEffect(mapReady, cameraFramed, followKey, hasPlannedRoute) {
        if (!mapReady || !cameraFramed || hasPlannedRoute) return@LaunchedEffect
        val holder = mapHolder.value ?: return@LaunchedEffect
        val target = vehicle ?: return@LaunchedEffect
        holder.mapView.camera.easeTo(
            CameraOptions.Builder().center(Point.fromLngLat(target.lng, target.lat)).zoom(BACKDROP_ZOOM).build(),
            MapAnimationOptions.mapAnimationOptions { duration(900) },
        )
    }

    // Bounds-fit camera (navigator mode only) — re-fit whenever the route reference changes (a
    // fresh route or a reroute) or the destination moves, so the whole trip stays in frame rather
    // than a tight follow-cam hiding the destination off-screen. `cameraForCoordinates` is the
    // synchronous overload (the map's already loaded by the time a route can exist), padded so the
    // route never touches the pane's edge (where the dial/cards sit in the caller's layout).
    LaunchedEffect(mapReady, plannedRoute, destination) {
        if (!mapReady || !hasPlannedRoute) return@LaunchedEffect
        val holder = mapHolder.value ?: return@LaunchedEffect
        val points = buildList {
            plannedRoute.forEach { add(Point.fromLngLat(it.lng, it.lat)) }
            vehicle?.let { add(Point.fromLngLat(it.lng, it.lat)) }
            destination?.let { add(Point.fromLngLat(it.lng, it.lat)) }
        }
        if (points.size < 2) return@LaunchedEffect
        runCatching {
            val fitted = holder.mapView.mapboxMap.cameraForCoordinates(
                points,
                CameraOptions.Builder().build(),
                EdgeInsets(BOUNDS_FIT_PADDING_PX, BOUNDS_FIT_PADDING_PX, BOUNDS_FIT_PADDING_PX, BOUNDS_FIT_PADDING_PX),
                null,
                null,
            )
            holder.mapView.camera.easeTo(fitted, MapAnimationOptions.mapAnimationOptions { duration(900) })
        }
        cameraFramed = true
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

private const val BACKDROP_ZOOM = 14.5
private const val BOUNDS_FIT_PADDING_PX = 56.0

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
