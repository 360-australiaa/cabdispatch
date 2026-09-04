package au.com.threesixty.cabdispatch.ui.screens.navigate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * 24b · Navigate — reskinned onto [CaptainPalette] (2026-08-29 purple migration pass), still a
 * VISUAL-ONLY placeholder, and now (2026-09-04 audit pass) confirmed **dead/unreachable**: no live
 * navigation path calls [CabDispatchRoutes.NAVIGATE_PLACEHOLDER] any more (its only other referrer,
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.HomeDashboardV2]'s `dockTiles`, is itself
 * unreachable — [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen], the only
 * composable that ever renders that dock, has no call site anywhere in the app; the live dashboard,
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen], has no Navigate rail item at
 * all — see that file's "omissions" comment). Left in the tree rather than deleted as this audit
 * pass's scope is reporting, not dead-code removal; a future pass can delete this file + the
 * `NAVIGATE_PLACEHOLDER` route + `WheelDashboardScreen` together.
 *
 * The claim this doc used to make — "this app has NO turn-by-turn navigation feature anywhere in
 * the codebase" — is no longer true and must not be trusted: [MeterNavViewModel] (destination
 * search, real Mapbox Directions route, live ETA, off-route reroute, spoken turns) shipped
 * 2026-09-04 and is wired into [au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen] (the
 * meter screen, reached via a real accepted job or the dashboard's METER rail item while a trip is
 * active) — a real, reachable, non-fabricated turn-by-turn feature. It is intentionally NOT plugged
 * in here: its whole UI (search dialog, route/ETA panel, map-panel mockup switch) is coupled to the
 * meter's fare/trip state and in-progress-hire layout, not a standalone destination a driver could
 * reach with no active job — turning it into one is a product-scope question (does "Navigate"
 * mean routing to an accepted job's *pickup* before the meter starts, per this screen's original
 * "ARRIVED — START METER"/"NO JOB" mockup CTAs, which don't match the current accept-job flow that
 * goes straight to [CabDispatchRoutes.HIRED]? or a general point-to-point tool with no trip tie?),
 * not a wiring gap. Every other line below (illustrative-only figures, PREVIEW labelling, decorative
 * map) is unchanged and still accurate for what remains of this now-dead screen.
 */
@Composable
fun NavigatePlaceholderScreen(navController: NavHostController) {
    Box(modifier = Modifier.fillMaxSize().background(MapBg)) {
        DecorativeMap()

        // PREVIEW chip — honest copy, unchanged prominence: same position, same explicit wording.
        Row(
            modifier = Modifier
                .offset(x = 16.dp, y = 144.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CaptainPalette.bg.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Map, contentDescription = null, tint = CaptainPalette.warning, modifier = Modifier.size(15.dp))
            Text(
                "PREVIEW — no live turn-by-turn feature on this build",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = CaptainPalette.warning,
            )
        }

        // Instruction banner — illustrative values, see file doc.
        Row(
            modifier = Modifier
                .offset(x = 32.dp, y = 24.dp)
                .width(1216.dp)
                .height(104.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(InstructionBg)
                .border(1.5.dp, CaptainPalette.success.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = CaptainPalette.textPrimary, modifier = Modifier.size(44.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "In 400 m, turn left",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    "Marion St — then pickup on the right (preview — not live guidance)",
                    fontFamily = InterFamily,
                    fontSize = 17.sp,
                    color = InstructionMint,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "2.1 km · 6 min",
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Medium,
                    fontSize = 22.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    "ETA 4:18 PM",
                    fontFamily = RobotoMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = InstructionMint,
                )
            }
        }

        // Bottom bar — honest preview copy (kept exactly as prominent) + the real EXIT NAV affordance.
        Row(
            modifier = Modifier
                .offset(x = 32.dp, y = 688.dp)
                .width(1216.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CaptainPalette.bg.copy(alpha = 0.92f))
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "NAVIGATE — PREVIEW ONLY",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    "No live navigation feature exists on this build — route and figures above are illustrative.",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            CaptainButton(
                text = "EXIT NAV",
                fontSize = 16.sp,
                modifier = Modifier.width(260.dp),
            ) {
                navController.popBackStack()
            }
        }
    }
}

private val MapBg = Color(0xFF0D1420)
private val MapRoad = Color(0xFF1C2940)
private val MapArterial = Color(0xFF243352)
private val MapLabel = Color(0xFF33445F)

/** Green-glass instruction banner fill (rgba(11,46,26,0.96)), kept as the meter-running colour
 * language distinct from [CaptainPalette]'s own success token for exact visual parity. */
private val InstructionBg = Color(0xF50B2E1A)

/** Mint secondary text on the instruction banner. */
private val InstructionMint = Color(0xFFA7E8C2)

/**
 * The decorative street grid stretched to the 1280×800 canvas, plus a route polyline and pickup
 * marker — pure canvas art, no data claims. Positions unchanged from the previous pass.
 */
@Composable
private fun DecorativeMap() {
    Box(modifier = Modifier.fillMaxSize()) {
        // Horizontal streets.
        listOf(127, 275, 444, 614, 741).forEach { y ->
            Box(
                Modifier
                    .offset(x = 0.dp, y = y.dp)
                    .fillMaxWidth()
                    .height(11.dp)
                    .background(MapRoad),
            )
        }
        // Vertical streets.
        listOf(227, 520, 845, 1072).forEach { x ->
            Box(
                Modifier
                    .offset(x = x.dp, y = 0.dp)
                    .width(19.dp)
                    .fillMaxHeight()
                    .background(MapRoad),
            )
        }
        // Arterials.
        Box(Modifier.offset(x = 0.dp, y = 360.dp).fillMaxWidth().height(19.dp).background(MapArterial))
        Box(Modifier.offset(x = 698.dp, y = 0.dp).width(29.dp).fillMaxHeight().background(MapArterial))

        // District labels.
        MapDistrictLabel("SYDNEY CITY", x = 97, y = 63)
        MapDistrictLabel("REDFERN", x = 292, y = 402)
        MapDistrictLabel("AIRPORT", x = 909, y = 635)
        MapDistrictLabel("LAKEMBA", x = 130, y = 656)

        // Route polyline: car → pickup, decorative.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = Path().apply {
                moveTo(608f.dp.toPx(), 340f.dp.toPx())
                lineTo(608f.dp.toPx(), 448f.dp.toPx())
                lineTo(712f.dp.toPx(), 448f.dp.toPx())
                lineTo(712f.dp.toPx(), 700f.dp.toPx())
                lineTo(700f.dp.toPx(), 748f.dp.toPx())
            }
            drawPath(
                path = path,
                color = CaptainPalette.accent.copy(alpha = 0.85f),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // Cab marker with soft halo (approximated with translucent circles).
        Box(
            modifier = Modifier
                .offset(x = 549.dp, y = 281.dp)
                .size(118.dp)
                .clip(CircleShape)
                .background(CaptainPalette.accent.copy(alpha = 0.14f)),
        )
        Box(
            modifier = Modifier
                .offset(x = 586.dp, y = 318.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(CaptainPalette.accent)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.LocalTaxi, contentDescription = null, tint = CaptainPalette.bg, modifier = Modifier.size(22.dp))
        }

        // Pickup marker.
        Box(
            modifier = Modifier
                .offset(x = 676.dp, y = 736.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(CaptainPalette.primary)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = CaptainPalette.textPrimary)
        }
    }
}

@Composable
private fun MapDistrictLabel(text: String, x: Int, y: Int) {
    Text(
        text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 2.sp,
        color = MapLabel,
        modifier = Modifier.offset(x = x.dp, y = y.dp),
    )
}
