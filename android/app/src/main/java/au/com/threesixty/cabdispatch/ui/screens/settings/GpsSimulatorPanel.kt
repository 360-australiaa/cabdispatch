package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.location.SimulatedRoute
import au.com.threesixty.cabdispatch.domain.location.SimulatedRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Settings → About → Advanced: drive the meter on fabricated GPS, to test the fare engine and
 * automatic toll detection without physically driving a toll road.
 *
 * Read [GpsSimulator][au.com.threesixty.cabdispatch.domain.location.GpsSimulator]'s own doc first
 * — this fabricates the primary evidence of a fare-regulated meter, and the warning below is not
 * decoration. Any trip started while this is running is permanently flagged `simulated`, on the
 * device, on the server and on the dashboard, and the meter screen carries a red banner
 * throughout.
 *
 * The toll routes are built from the device's own cached toll registry, so they drive the real
 * gantry coordinates the detector matches against — see [SimulatedRoutes.throughTollRoad]. If the
 * registry has not synced yet, those routes are absent rather than faked.
 */
@Composable
fun GpsSimulatorPanel(modifier: Modifier = Modifier) {
    val simulator = AppContainer.gpsSimulator
    val active by simulator.active.collectAsState()
    val running by simulator.route.collectAsState()
    val finished by simulator.finished.collectAsState()

    var routes by remember { mutableStateOf<List<SimulatedRoute>>(emptyList()) }
    LaunchedEffect(Unit) {
        // Toll routes need the cached registry, which is a suspending Room read — so the list is
        // built here rather than in composition. Registry not synced yet => only the plain
        // routes, never a toll route through invented coordinates.
        val registry = runCatching { AppContainer.tollRegistryCache.snapshot() }.getOrNull()
        val tollRoutes = registry
            ?.roadsById
            ?.keys
            ?.sorted()
            ?.mapNotNull { SimulatedRoutes.throughTollRoad(registry, it) }
            .orEmpty()
        routes = listOf(
            SimulatedRoutes.bandSweep(),
            SimulatedRoutes.plainDrive(),
            SimulatedRoutes.stopAndGo(),
        ) + tollRoutes
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CaptainPalette.danger.copy(alpha = 0.15f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⚠  Fabricated GPS. Any trip you run with this on is permanently marked as a " +
                    "simulation — on this tablet, on the server and in the dashboard — and is not " +
                    "real revenue. The meter shows a red banner the whole time.",
                fontFamily = InterFamily,
                fontSize = 12.sp,
                color = CaptainPalette.textMuted,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (active) {
            val label = running?.name ?: "route"
            Text(
                if (finished) "Arrived — $label finished, vehicle stopped." else "Driving $label…",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = CaptainPalette.danger,
            )
            Spacer(Modifier.height(8.dp))
            SimulatorButton(
                text = "STOP SIMULATION · BACK TO REAL GPS",
                danger = true,
                onClick = { simulator.stop() },
            )
        } else {
            // Count + scroll hint. Reported from the tablet: "I can't see other toll routes, like
            // tunnels, Westlink". They were all there -- 13 of them -- but each row was tall
            // enough that only the first three cleared the fold, and the About tab's scroll gave
            // no sign there was more below. Saying how many exist is the cheapest possible fix for
            // "I thought that was all of them"; the rows below are also tightened so more land on
            // screen at once.
            Text(
                "${routes.size} routes — scroll for the tunnels and motorways",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = CaptainPalette.textMuted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (route in routes) {
                    RouteRow(route = route, onStart = { simulator.start(route) })
                }
                if (routes.size <= 2) {
                    Text(
                        "Toll-road routes appear once the NSW toll registry has synced to this " +
                            "tablet. Nothing is invented in the meantime.",
                        fontFamily = InterFamily,
                        fontSize = 11.sp,
                        color = CaptainPalette.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteRow(route: SimulatedRoute, onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CaptainPalette.panel)
            .clickable(onClick = onStart)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            route.name,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = CaptainPalette.textPrimary,
        )
        // Description trimmed to two lines max: the pricing-model detail is useful but it was
        // costing a third of each row's height, which is what pushed the tunnels off screen.
        Text(
            route.description,
            fontFamily = InterFamily,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = CaptainPalette.textMuted,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "%.1f km · %.0f km/h · about %d min".format(
                route.lengthM / 1000.0,
                route.speedKmh,
                (route.durationSeconds / 60).toInt().coerceAtLeast(1),
            ),
            fontFamily = InterFamily,
            fontSize = 11.sp,
            color = CaptainPalette.textMuted,
        )
    }
}

@Composable
private fun SimulatorButton(text: String, danger: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) CaptainPalette.danger else CaptainPalette.panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = if (danger) CaptainPalette.bg else CaptainPalette.textPrimary,
        )
    }
}
