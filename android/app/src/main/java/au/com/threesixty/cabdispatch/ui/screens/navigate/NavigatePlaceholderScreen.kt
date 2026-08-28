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
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * 24b · Navigate — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `23:1111`), still a
 * VISUAL-ONLY placeholder. Nothing about this screen's honest nature changed from the previous
 * version's doc: this app has NO turn-by-turn navigation feature anywhere in the codebase (the
 * only real "navigate" affordance is the one-shot `openInMaps` geo: deep link, which lives on the
 * job-offer screen, not here), so every route/instruction/ETA figure on this screen is a fixed
 * illustrative value from the Figma frame and is labelled as such on-screen — see the PREVIEW
 * chip and the bottom bar's explicit preview copy. The single live affordance is EXIT NAV → pop.
 *
 * The frame's "ARRIVED — START METER" / "NO JOB" CTAs are NOT reproduced: they would fabricate a
 * meter-start and a quick-message action this screen has no wiring for (same "do not fabricate
 * turn-by-turn logic" brief as before); the green CTA slot carries the real EXIT NAV instead.
 *
 * Map tones `#0D1420`/`#1C2940`/`#243352`/`#33445F` are the established v2 map-surface colors
 * (`c/map`, node 11:55). The frame's route SVG + halo are decorative vectors, redrawn here as a
 * Canvas polyline/translucent circles rather than shipped as image assets.
 */
@Composable
fun NavigatePlaceholderScreen(navController: NavHostController) {
    Box(modifier = Modifier.fillMaxSize().background(MapBg)) {
        DecorativeMap()

        // PREVIEW chip (replaces the frame's "offline maps up to date" chip with honest copy).
        Box(
            modifier = Modifier
                .offset(x = 16.dp, y = 144.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Deck.canvas.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                "🗺 PREVIEW — no live turn-by-turn feature on this build",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = Deck.stopped,
            )
        }

        // Instruction banner (Figma 23:1137) — illustrative values, see file doc.
        Row(
            modifier = Modifier
                .offset(x = 32.dp, y = 24.dp)
                .width(1216.dp)
                .height(104.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(InstructionBg)
                .border(1.5.dp, Deck.forHire.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("⬅", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 54.sp, color = Deck.textPrimary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "In 400 m, turn left",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    color = Deck.textPrimary,
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
                    color = Deck.textPrimary,
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

        // Bottom bar (Figma 23:1145) — honest preview copy + the real EXIT NAV affordance.
        Row(
            modifier = Modifier
                .offset(x = 32.dp, y = 688.dp)
                .width(1216.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Deck.canvas.copy(alpha = 0.92f))
                .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(20.dp))
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
                    color = Deck.textPrimary,
                )
                Text(
                    "No live navigation feature exists on this build — route and figures above are illustrative.",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = Deck.textMuted,
                )
            }
            DeckButton(
                text = "EXIT NAV",
                kind = DeckButtonKind.Success,
                fontSize = 16,
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

/** Frame `23:1137`'s green-glass instruction banner fill (rgba(11,46,26,0.96)). */
private val InstructionBg = Color(0xF50B2E1A)

/** Frame `23:1141`'s mint secondary text on the instruction banner. */
private val InstructionMint = Color(0xFFA7E8C2)

/**
 * The `c/map` decorative street grid (node 11:55) stretched to the 1280×800 canvas like the frame
 * does, plus the frame's route polyline and pickup marker — pure canvas art, no data claims.
 * Positions are the frame's, scaled from the 788×756 component to 1280×800.
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

        // Route polyline: car → pickup, redrawn from the frame's route vector (decorative).
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
                color = Deck.forHire.copy(alpha = 0.85f),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // Cab marker with soft halo (frame's halo vector approximated with translucent circles).
        Box(
            modifier = Modifier
                .offset(x = 549.dp, y = 281.dp)
                .size(118.dp)
                .clip(CircleShape)
                .background(Deck.forHire.copy(alpha = 0.14f)),
        )
        Box(
            modifier = Modifier
                .offset(x = 586.dp, y = 318.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Deck.forHire)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("🚕", fontSize = 20.sp)
        }

        // Pickup marker (Figma 23:1135).
        Box(
            modifier = Modifier
                .offset(x = 676.dp, y = 736.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Deck.forHire)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Deck.onForHire)
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
