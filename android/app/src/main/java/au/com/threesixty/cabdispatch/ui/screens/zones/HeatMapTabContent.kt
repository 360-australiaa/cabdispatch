package au.com.threesixty.cabdispatch.ui.screens.zones

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.remote.ZoneDto
import au.com.threesixty.cabdispatch.data.remote.ZoneStatsDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import com.mapbox.geojson.Point
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.location.GeoMath
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.OnCircleAnnotationClickListener
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import kotlin.math.max
import kotlin.math.min

/**
 * Heat Map tab (`squishy-herding-iverson.md` Phase F) — this app's FIRST real interactive Mapbox
 * `MapView` anywhere. Every other "map" in this app (the dashboard's Live Map pane,
 * `MapboxStaticImage`) is the non-interactive Static Images API — a stale comment elsewhere
 * (`WheelDashboardScreen.kt`) claims a real `MapView` already exists on the dashboard; it doesn't.
 * This one genuinely does: real pan/zoom/rotate gestures, backed by `com.mapbox.maps:android`
 * (already a Gradle dependency, `app/build.gradle.kts`).
 *
 * One [com.mapbox.maps.plugin.annotation.generated.CircleAnnotation] per real [ZoneDto], placed at
 * its real [ZoneDto.centerLat]/[centerLng], colored by [SurgeModel.color] off the matching real
 * [ZoneStatsDto] — never a fabricated overlay. Tapping a circle shows that zone's real numbers in
 * [SelectedZoneCard]. [radiusPxFor] scales the on-screen circle size off [ZoneDto.radiusM] purely
 * as a relative "bigger real zone -> bigger marker" visual cue — Mapbox's `circle-radius` is a
 * fixed screen-pixel size, not a geo-referenced polygon, so this is deliberately NOT a claim of
 * pixel-perfect metres-on-map geofence accuracy (an exact circle would need a hand-rolled
 * `FillLayer` polygon built from a bearing/distance offset helper that doesn't exist anywhere in
 * this app's `GeoMath` today — left as real future work, not faked here).
 *
 * **HUD kit rebuild (2026-09-04).** Chrome only: [SurgeLegendCard] and [SelectedZoneCard] are now
 * [GlassCard]s carrying a [HudStatusPill] for the tapped zone's surge multiplier. The real
 * interactive Mapbox `MapView`, its circle annotations, and every [SurgeModel] color/multiplier
 * computation above are untouched.
 */
@Composable
fun HeatMapTabContent(
    plotViewModel: PlotZoneViewModel = viewModel(),
    statsViewModel: ZoneStatisticsViewModel = viewModel(),
) {
    val zoneState by plotViewModel.uiState.collectAsState()
    val statsState by statsViewModel.uiState.collectAsState()

    val zones = (zoneState as? PlotZoneUiState.Loaded)?.zones.orEmpty()
    val statsByZoneId = remember(statsState.stats) { statsState.stats.associateBy { it.zoneId } }

    var selected by remember { mutableStateOf<Pair<ZoneDto, ZoneStatsDto?>?>(null) }
    val mapHolder = remember { mutableStateOf<MapHolder?>(null) }
    // Real values as of the most recent recomposition, readable from the click-listener lambda
    // below (created once, inside AndroidView's `factory`, and never recreated — a plain captured
    // `val` there would go stale the moment zones/stats first update; `rememberUpdatedState` is
    // Compose's documented fix for exactly this "long-lived callback needs the latest value"
    // shape).
    val latestZones = rememberUpdatedState(zones)
    val latestStatsByZoneId = rememberUpdatedState(statsByZoneId)
    val zoneIdByAnnotationId = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // True only once, right after the map's style + circle-annotation manager finish loading —
    // used to run the one-time initial camera framing exactly once (see the LaunchedEffect below)
    // rather than re-centering on every 20s stats poll, which would fight a driver's own pan/zoom.
    var mapReady by remember { mutableStateOf(false) }
    var cameraFramed by remember { mutableStateOf(false) }
    var noZonesNearby by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            zoneState is PlotZoneUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CaptainPalette.accent)
            }
            zones.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No zones published for this region yet — nothing to plot on the heat map.",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
            else -> {
                AndroidView(
                    // NO clip on the map view itself (tablet, 2026-09-08). Mapbox renders into a
                    // SurfaceView, which punches a hole in the window that Compose's clip cannot
                    // shape -- on this tablet the clipped surface simply never appeared: scale bar
                    // and wordmark (ordinary views) drew, the map did not. The meter's backdrop map,
                    // which works, carries no clip. The card around this already rounds the corners.
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // Style given AT CONSTRUCTION. A bare MapView(ctx) starts loading the SDK's
                        // default style on its own; a loadStyle(DARK) issued right after it raced
                        // that load and was dropped with "[Style]: Updated style is ignored due to
                        // runtime changes" (logcat, tablet, 2026-09-08) -- so the callback that
                        // creates the annotation manager, sets mapReady and frames the camera never
                        // ran, and the pane stayed a black rectangle. MapInitOptions makes DARK the
                        // one and only style load.
                        // textureView = true. The style loads (logcat: "style loaded:
                        // mapbox://styles/mapbox/dark-v11"), the camera frames, the annotation
                        // manager exists -- and the canvas is still black. This pane sits inside
                        // PaneShell, whose content Box is clip(RoundedCornerShape(18.dp)). Mapbox's
                        // default SurfaceView punches a hole in the window that an ancestor's
                        // Compose clip then masks entirely; the meter's backdrop map has no clipped
                        // ancestor and works. A TextureView composites like an ordinary view and
                        // honours the clip.
                        val mapView = MapView(ctx, MapInitOptions(ctx, styleUri = Style.DARK, textureView = true))
                        // START IT NOW (tablet, 2026-09-08: a black canvas, scale bar and logo but
                        // no tiles and no zones). The lifecycle observer below only forwards FUTURE
                        // ON_START events -- and when the Zones pane opens the activity is already
                        // started, so that event never comes and a never-started MapView draws
                        // nothing. The observer still handles stop/restart from here on.
                        mapView.onStart()
                        // Runtime evidence, kept: every load failure and the first full render are
                        // logged so a black canvas can be read off logcat instead of guessed at.
                        mapView.mapboxMap.subscribeMapLoadingError { android.util.Log.e("HeatMap", "map load error: ${it.type} ${it.message} ${it.sourceId ?: ""} ${it.tileId ?: ""}") }
                        mapView.mapboxMap.subscribeMapLoaded { android.util.Log.i("HeatMap", "map fully loaded") }
                        mapView.mapboxMap.getStyle {
                            android.util.Log.i("HeatMap", "style loaded: ${it.styleURI}")
                            val circleManager = mapView.annotations.createCircleAnnotationManager()
                            circleManager.addClickListener(
                                OnCircleAnnotationClickListener { clicked ->
                                    val zoneId = zoneIdByAnnotationId.value[clicked.id]
                                    val zone = latestZones.value.firstOrNull { it.id == zoneId }
                                    if (zone != null) selected = zone to latestStatsByZoneId.value[zone.id]
                                    true
                                },
                            )
                            mapHolder.value = MapHolder(mapView, circleManager)
                            mapReady = true
                        }
                        mapView
                    },
                )

                SurgeLegendCard(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) // BottomEnd: BottomStart sat on the Mapbox wordmark
                LastUpdatedChip(statsState.lastUpdatedAt, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
                if (noZonesNearby) {
                    Text(
                        "No zones within 200 km of this vehicle — showing your position. Check the zone coordinates in the dashboard.",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = CaptainPalette.warning,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp).padding(horizontal = 24.dp),
                    )
                }
                selected?.let { (zone, stats) ->
                    SelectedZoneCard(zone, stats, modifier = Modifier.align(Alignment.TopStart).padding(16.dp), onDismiss = { selected = null })
                }
            }
        }
    }

    // One-time initial camera framing — runs once, the first time the map is ready AND zones have
    // actually loaded, never again after that. Deliberately NOT re-run on every stats poll: an
    // effect keyed on the live data would silently snap a driver's own pan/zoom back to center
    // every 20s, defeating the whole point of a real interactive map.
    androidx.compose.runtime.LaunchedEffect(mapReady, zones.isNotEmpty()) {
        if (cameraFramed || !mapReady || zones.isEmpty()) return@LaunchedEffect
        val holder = mapHolder.value ?: return@LaunchedEffect
        // Frame the zones' BOUNDING BOX, not their average centre at a fixed zoom 11 (tablet,
        // 2026-09-08: five zones, a black canvas). Zones spread across a region average to a point
        // none of them is near, and at z11 every circle sat off-screen over empty map. Fitting the
        // box keeps every zone in view whatever their spread; the padding keeps the outermost
        // circles clear of the legend and the scale bar.
        // Frame the zones NEAR THE VEHICLE, not all of them. This tenant's zones are Sydney CBD,
        // Bondi, Parramatta, Sydney Airport -- and Karachi, where the field-test tablet is. The
        // average of those sat in the Indian Ocean and a box around them was half the planet; both
        // rendered as a black canvas. Zones within 200 km of the current fix are the ones a driver
        // can act on; only when none are that close does the frame fall back to all of them.
        val fix = AppContainer.speedSource.locationFix.value
        val nearby = if (fix == null) zones else zones.filter {
            GeoMath.distanceKm(fix.lat, fix.lng, it.centerLat, it.centerLng) <= 200.0
        }
        if (nearby.isEmpty() && fix != null) {
            // No zone within 200 km of the vehicle: frame the VEHICLE, never "all zones". The
            // fallback that boxed every zone was how a single zone saved with its latitude and
            // longitude swapped (zone 10, lat 67.17 lng 24.88 -- Scandinavia) dragged the camera
            // from Sydney to the Bay of Bengal and left the pane a black sea. The card above the
            // map says why nothing is drawn; the map itself shows where the driver actually is.
            noZonesNearby = true
            holder.mapView.mapboxMap.setCamera(
                CameraOptions.Builder().center(Point.fromLngLat(fix.lng, fix.lat)).zoom(11.0).build(),
            )
        } else {
            noZonesNearby = false
            val framed = if (nearby.isEmpty()) zones else nearby
            val points = framed.map { Point.fromLngLat(it.centerLng, it.centerLat) }
            val camera = holder.mapView.mapboxMap.cameraForCoordinates(
                points,
                CameraOptions.Builder().build(),
                com.mapbox.maps.EdgeInsets(120.0, 160.0, 220.0, 160.0),
                null,
                null,
            )
            holder.mapView.mapboxMap.setCamera(
                CameraOptions.Builder()
                    .center(camera.center)
                    .zoom((camera.zoom ?: 11.0).coerceIn(9.0, 14.0))
                    .build(),
            )
        }
        cameraFramed = true
    }

    // Redraws the circle layer whenever the zone list or live stats change (initial load, the 20s
    // stats poll ticking over, a plot/unplot changing plottedVehicles) — deleteAll+recreate rather
    // than diffing, this list is at most a handful of zones per tenant. Camera position is
    // untouched here — only the framing effect above ever moves it.
    androidx.compose.runtime.LaunchedEffect(mapReady, zones, statsByZoneId) {
        val holder = mapHolder.value ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        holder.circleManager.deleteAll()
        if (zones.isEmpty()) {
            zoneIdByAnnotationId.value = emptyMap()
            return@LaunchedEffect
        }

        val created = zones.map { zone ->
            val multiplier = statsByZoneId[zone.id]?.let(SurgeModel::multiplier) ?: 1.0
            val hex = SurgeModel.colorHex(multiplier)
            CircleAnnotationOptions()
                .withPoint(Point.fromLngLat(zone.centerLng, zone.centerLat))
                .withCircleRadius(radiusPxFor(zone.radiusM))
                .withCircleColor(hex)
                .withCircleOpacity(0.45)
                // White stroke, not the fill colour: a 1.0x zone's grey at 0.55 alpha on Style.DARK
                // was indistinguishable from the map. The ring is what makes the zone findable.
                .withCircleStrokeWidth(3.0)
                .withCircleStrokeColor("#FFFFFF")
        }
        val annotations = holder.circleManager.create(created)
        android.util.Log.i("HeatMap", "zones=" + zones.joinToString { "${it.number}@${it.centerLat},${it.centerLng} r=${it.radiusM}" } + " created=${annotations.size} cam=${holder.mapView.mapboxMap.cameraState.center.latitude()},${holder.mapView.mapboxMap.cameraState.center.longitude()} z=${holder.mapView.mapboxMap.cameraState.zoom}")
        zoneIdByAnnotationId.value = annotations.mapIndexed { index, annotation -> annotation.id to zones[index].id }.toMap()
    }

    // Pauses/resumes the map's render thread with the host lifecycle — standard Mapbox
    // Compose-interop wiring; no prior precedent in this app since this is its first real MapView.
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

private data class MapHolder(
    val mapView: MapView,
    val circleManager: CircleAnnotationManager,
)

/** Relative on-screen circle size (Mapbox `circle-radius`, screen px) from a zone's real
 * `radius_m` — clamped so a tiny zone stays tappable and a huge one doesn't swallow the map. See
 * this file's class doc for why this is a relative cue, not a metres-accurate geofence. */
private fun radiusPxFor(radiusM: Double): Double = max(24.0, min(90.0, radiusM / 15.0))

/** Surge-multiplier legend, now a [GlassCard] (was a hand-rolled `bg`-tinted `Column`) — the four
 * real bands [SurgeModel] can ever produce, each shown with its real [SurgeModel.color]. */
@Composable
private fun SurgeLegendCard(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadiusDp = 14) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SURGE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
            listOf(1.0, 1.2, 1.6, 2.0).forEach { multiplier ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(SurgeModel.color(multiplier)))
                    Text(
                        "${"%.1f".format(multiplier)}x",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = CaptainPalette.textSecondary,
                    )
                }
            }
        }
    }
}

/** Tapped-zone info card, now a [GlassCard] carrying the multiplier as a [HudStatusPill] (was a
 * hand-rolled `bg`-tinted `Column` with the multiplier as plain text) — real numbers off the same
 * [ZoneStatsDto] the table/Surge Areas tab show, or an honest "no live statistics yet" line when
 * this zone hasn't reported any. */
@Composable
private fun SelectedZoneCard(zone: ZoneDto, stats: ZoneStatsDto?, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val multiplier = stats?.let(SurgeModel::multiplier) ?: 1.0
    GlassCard(modifier = modifier.width(280.dp), cornerRadiusDp = 14, glow = if (stats != null) SurgeModel.color(multiplier) else null) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${zone.number} · ${zone.name}", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text(
                    "✕",
                    color = CaptainPalette.textMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp).clickable(onClick = onDismiss),
                )
            }
            if (stats != null) {
                HudStatusPill(label = "Surge", value = SurgeModel.label(stats), tone = surgeTone(multiplier), pulsing = false)
                Text(
                    "${stats.bookingsLastHour} bookings + ${stats.streetHailsLastHour} hails/hr · " +
                        "${stats.vacantVehicles} vacant · ${stats.busyVehicles} busy · ${stats.plottedVehicles} plotted",
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    color = CaptainPalette.textSecondary,
                )
            } else {
                Text("No live statistics reported for this zone yet.", fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted)
            }
        }
    }
}
