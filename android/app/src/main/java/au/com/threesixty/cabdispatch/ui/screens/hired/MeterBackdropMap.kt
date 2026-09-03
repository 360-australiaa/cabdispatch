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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.domain.LocationFix
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
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
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
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
 * 2026-09-03) — this app's SECOND real `MapView`, a direct reuse of the lifecycle/`rememberUpdatedState`/
 * "frame once, then only touch layers on data change" pattern proven in
 * [au.com.threesixty.cabdispatch.ui.screens.zones.HeatMapTabContent] (the first). Everything drawn
 * is real data, or absent:
 *
 * - **Route polyline** — every vertex is a real GPS fix. Two sources, concatenated in order:
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
 * - **Destination pin** — deliberately NOT drawn. `TripContext` carries a `destAddress` string but
 *   no destination coordinates (the dispatch offer's `destLat/destLng` are never threaded into the
 *   hand-off), and a pin at a guessed/geocoded spot would be a fabricated destination.
 *
 * Gestures are fully disabled (`gestures.updateSettings`) — this is a backdrop the dial floats
 * over, not a map to pan; the camera follows the vehicle's real position with a short ease, keyed
 * on a ~10m-quantised position so 1 Hz fixes don't restart the ease every tick. Scale bar and
 * compass are hidden (they'd sit under the dim overlay looking broken); Mapbox's logo/attribution
 * are left enabled per its terms, dimmed like the rest of the map. A dark overlay + radial vignette
 * on top keeps the dial legible over street detail.
 */
@Composable
internal fun MeterBackdropMap(
    startLat: Double?,
    startLng: Double?,
    persistedTrace: List<TelemetryPointDto>,
    liveTrace: List<MapPoint>,
    liveFix: LocationFix?,
    modifier: Modifier = Modifier,
) {
    val routePoints = remember(persistedTrace, liveTrace) {
        persistedTrace.map { MapPoint(it.lat, it.lng) } + liveTrace
    }
    val vehicle: MapPoint? = liveFix?.let { MapPoint(it.lat, it.lng) }
        ?: routePoints.lastOrNull()
        ?: if (startLat != null && startLng != null) MapPoint(startLat, startLng) else null
    val pickup: MapPoint? = if (startLat != null && startLng != null) MapPoint(startLat, startLng) else null

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
        // Dim + vignette so the dial reads on top of street detail. Two layers: a flat ~62% bg
        // wash, then a radial fade that's near-transparent behind the dial's centre and darker at
        // the corners — the "map recedes, dial floats" look from the mockup.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CaptainPalette.bg.copy(alpha = 0.62f))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, CaptainPalette.bg.copy(alpha = 0.55f)),
                    ),
                ),
        )
    }

    // One-time initial framing, exactly like HeatMapTabContent: first time the style is loaded AND
    // we have a real position. Subsequent moves go through the follow effect below.
    LaunchedEffect(mapReady, vehicle != null) {
        if (cameraFramed || !mapReady) return@LaunchedEffect
        val holder = mapHolder.value ?: return@LaunchedEffect
        val target = vehicle ?: return@LaunchedEffect
        holder.mapView.mapboxMap.setCamera(
            CameraOptions.Builder().center(Point.fromLngLat(target.lng, target.lat)).zoom(BACKDROP_ZOOM).build(),
        )
        cameraFramed = true
    }

    // Camera follow — eases to the vehicle's real position whenever it moves >~10 m (see
    // followKey). Gestures are disabled, so there is no driver pan to fight with.
    LaunchedEffect(mapReady, cameraFramed, followKey) {
        if (!mapReady || !cameraFramed) return@LaunchedEffect
        val holder = mapHolder.value ?: return@LaunchedEffect
        val target = vehicle ?: return@LaunchedEffect
        holder.mapView.camera.easeTo(
            CameraOptions.Builder().center(Point.fromLngLat(target.lng, target.lat)).zoom(BACKDROP_ZOOM).build(),
            MapAnimationOptions.mapAnimationOptions { duration(900) },
        )
    }

    // Layer refresh on data change only (never moves the camera): the route polyline is drawn as
    // two line annotations — a wide, low-alpha one underneath for the glow and a narrow bright one
    // on top — then pickup + vehicle circle annotations. deleteAll + recreate, same as the heat
    // map: a trip's trace is at most a few thousand points, well within what this costs.
    LaunchedEffect(mapReady, routePoints, pickup, vehicle) {
        val holder = mapHolder.value ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        holder.lines.deleteAll()
        holder.circles.deleteAll()

        if (routePoints.size >= 2) {
            val geo = routePoints.map { Point.fromLngLat(it.lng, it.lat) }
            holder.lines.create(
                listOf(
                    PolylineAnnotationOptions()
                        .withPoints(geo)
                        .withLineColor(CaptainPalette.accent.toHex())
                        .withLineWidth(14.0)
                        .withLineOpacity(0.28),
                    PolylineAnnotationOptions()
                        .withPoints(geo)
                        .withLineColor(CaptainPalette.accent.toHex())
                        .withLineWidth(4.0)
                        .withLineOpacity(0.95),
                ),
            )
        }
        val pins = buildList {
            if (pickup != null) {
                add(
                    CircleAnnotationOptions()
                        .withPoint(Point.fromLngLat(pickup.lng, pickup.lat))
                        .withCircleRadius(7.0)
                        .withCircleColor(CaptainPalette.success.toHex())
                        .withCircleStrokeWidth(3.0)
                        .withCircleStrokeColor(CaptainPalette.textPrimary.toHex()),
                )
            }
            if (vehicle != null) {
                // Soft halo under the vehicle dot — the "glowing marker" read, without a bitmap.
                add(
                    CircleAnnotationOptions()
                        .withPoint(Point.fromLngLat(vehicle.lng, vehicle.lat))
                        .withCircleRadius(18.0)
                        .withCircleColor(CaptainPalette.accent.toHex())
                        .withCircleOpacity(0.25),
                )
                add(
                    CircleAnnotationOptions()
                        .withPoint(Point.fromLngLat(vehicle.lng, vehicle.lat))
                        .withCircleRadius(8.0)
                        .withCircleColor(CaptainPalette.accent.toHex())
                        .withCircleStrokeWidth(3.0)
                        .withCircleStrokeColor(CaptainPalette.textPrimary.toHex()),
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

private data class BackdropHolder(
    val mapView: MapView,
    val lines: PolylineAnnotationManager,
    val circles: CircleAnnotationManager,
)

/** Mapbox annotation colour strings are CSS hex; this is the same palette token, not a new colour. */
private fun Color.toHex(): String = "#%06X".format(0xFFFFFF and toArgb())

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
