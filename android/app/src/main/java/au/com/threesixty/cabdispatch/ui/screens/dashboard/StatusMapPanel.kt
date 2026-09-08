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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * The home screen's map panel and its no-token placeholder: the illustrative street grid
 * and suburb labels shown when no real map surface is available.
 *
 * Extracted from DeckHomeScreen.kt in Phase 0 (P0.4) — a mechanical move, same package,
 * same declarations, no behaviour change.
 */

// ============================================================================================
// Live map pane (unchanged from the previous Command Deck layout — kept reachable via the
// flyout's "LIVE MAP" entry rather than deleted; see this file's class doc)
// ============================================================================================

@Composable
internal fun StatusMapPanel(onPlotZone: () -> Unit) {
    val fix by AppContainer.speedSource.locationFix.collectAsState()
    // Show Map in Background (Settings -> Display, 2026-09-03 Settings two-pane pass) — the real
    // toggle behind this pane's Mapbox Static Images fetch (see
    // au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore's own doc). Defaults true, so
    // a driver who never touches the setting sees exactly the same map this pane always rendered.
    val showMapInBackground by AppContainer.settingsPreferencesStore.showMapInBackground.collectAsState()
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val centerLat = fix?.lat ?: SydneyCbdFallback.LAT
    val centerLng = fix?.lng ?: SydneyCbdFallback.LNG

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.mapBg)
            .onGloballyPositioned { sizePx = it.size },
    ) {
        if (!showMapInBackground) {
            MapHiddenPlaceholder()
        } else if (sizePx.width > 0 && sizePx.height > 0) {
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

        val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "halo").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
            label = "halo-a",
        )
        Box(
            modifier = Modifier.offset(x = 360.dp, y = 300.dp).size(72.dp).clip(CircleShape)
                .background(CaptainPalette.accent.copy(alpha = pulse)),
        )
        Box(
            modifier = Modifier.offset(x = 374.dp, y = 314.dp).size(44.dp).clip(CircleShape)
                .background(CaptainPalette.accent).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("🚕", fontSize = 20.sp)
        }
        Box(
            modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(10.dp))
                .background(CaptainPalette.bg.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                if (fix != null) "🗺 Live position" else "🗺 Waiting for GPS fix…",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = CaptainPalette.textSecondary,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CaptainPalette.bg.copy(alpha = 0.88f))
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(CaptainPalette.accent.copy(alpha = 0.14f))
                    .clickable(onClick = onPlotZone).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "📍 Plot a zone — see live demand →",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = CaptainPalette.accent,
                )
            }
            Text("Heartbeat 30 s · GPS live", fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textMuted)
        }
    }
}

/** Renders instead of the real Mapbox imagery (or its street-grid fallback) when Settings ->
 * Display -> "Show Map in Background" is off — honest and static, never a stale/last-cached map
 * frame. Position pin/heartbeat chip/plot-zone bar above this Box are unaffected: Plot a Zone
 * stays reachable either way, this setting only governs the map imagery itself. */
@Composable
private fun MapHiddenPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Map, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(40.dp))
            Text(
                "Background map hidden",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "Turn on \"Show Map in Background\" in Settings -> Display to bring it back.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun IllustrativeStreetGrid() {
    // CaptainPalette.mapStreet/mapArterial (not a local hardcoded literal) — this fallback
    // "fake map" used to stay a fixed dark navy regardless of theme, a real day-mode bug fixed in
    // this pass: a hardcoded night-map illustration pasted onto a light-themed dashboard looked
    // broken. See CaptainPalette's own doc for the token.
    val street = CaptainPalette.mapStreet
    val arterial = CaptainPalette.mapArterial
    listOf(120, 260, 420, 580, 700).forEach { y -> Box(Modifier.offset(y = y.dp).fillMaxWidth().height(10.dp).background(street)) }
    listOf(140, 320, 520, 660).forEach { x -> Box(Modifier.offset(x = x.dp).fillMaxHeight().width(12.dp).background(street)) }
    Box(Modifier.offset(y = 340.dp).fillMaxWidth().height(18.dp).background(arterial))
    Box(Modifier.offset(x = 430.dp).fillMaxHeight().width(18.dp).background(arterial))
    SuburbLabel("SYDNEY CITY", 60.dp, 60.dp)
    SuburbLabel("REDFERN", 180.dp, 380.dp)
    SuburbLabel("AIRPORT", 560.dp, 600.dp)
    SuburbLabel("LAKEMBA", 80.dp, 620.dp)
}

@Composable
private fun SuburbLabel(text: String, x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp) {
    Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = CaptainPalette.mapLabel, modifier = Modifier.offset(x = x, y = y))
}
