package au.com.threesixty.cabdispatch.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The driver surfaces' spacing, radius and type scale (workstream A3, 2026-09-08).
 *
 * WHY THIS FILE EXISTS. The 2026-09-08 driver-UI audit (§3) counted, on the home screen alone,
 * **twenty distinct font sizes** (9, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 24, 26, 28, 32,
 * 34, 40, 44, 76sp), **39 distinct dp paddings** and **12 distinct corner radii** — roughly ninety
 * `Text` call sites each re-declaring `fontFamily` + `fontWeight` + `fontSize` + `letterSpacing`
 * inline. A scale already existed ([DeckType] in DeckTheme.kt) and was referenced *zero* times by
 * the live home screen. This file is the replacement that actually gets adopted, and it is
 * deliberately small enough that adopting it is easier than not.
 *
 * THE RULE, for anything under `ui/screens/dashboard/`:
 * - only [Space] values in `padding` / `spacedBy` / `height` gaps,
 * - only [Radius] values in `RoundedCornerShape`,
 * - only [Type] styles in `Text` (pass `style = Type.body`, override colour only).
 *
 * THE 12sp FLOOR is a hard accessibility rule, not a preference. The home screen's class doc has
 * committed since day one to *"majority users are old age… deliberately larger type"*, and the
 * audit (§6) found that commitment kept for headline numbers and broken for labels — a **9sp**
 * floor on the nav rail's mid-fare lock caption, 10sp and 11sp elsewhere. There is no [Type] style
 * below 12sp here, so the floor is enforced by there being nothing smaller to reach for.
 *
 * WHAT IS DELIBERATELY *NOT* HERE: colour. Colour stays entirely in [CaptainPalette], which is
 * theme-swapped at runtime by `applyTheme()`. A `TextStyle` carrying a colour would capture
 * whichever theme happened to be live when this object initialised, and would silently keep the
 * dark colour after a switch to light. Every style below is colour-free by construction; call
 * sites pass `color = CaptainPalette.…` alongside `style = Type.…`.
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp

    /** The half-step between [sm] and [md]. Earns its place: the 12dp gutter between the content
     * row and the nav rail, and the gap between stat-bar cells, are both real measured values on
     * the fixed 1280×800 canvas that neither 8 nor 16 replaces without moving the grid. */
    val smd = 12.dp

    /** The half-step between [md] and [lg] — card inner padding. 18dp was the single most common
     * padding on the pre-A3 screen (every `GlassCard` body used it); keeping it as a named step
     * migrates those call sites without re-tuning every card's interior. */
    val mlg = 20.dp
}

object Radius {
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp

    /** Fully-rounded. Not `999.dp` as a literal at each call site — `RoundedCornerShape` clamps to
     * half the smaller side, so any sufficiently large value is a pill; this names the intent. */
    val pill = 999.dp
}

/**
 * The type scale. Nine styles replace twenty ad-hoc sizes.
 *
 * Family assignment follows the convention already established in DeckTheme.kt and used
 * consistently across the app: [ChakraPetch] for numerals and data readouts, [InterFamily] for
 * prose and labels, [RobotoMonoFamily] for regos and IDs (where a fixed advance width matters
 * because the value is character-by-character compared against a physical plate or a printed
 * docket).
 */
object Type {
    /** The one enormous readout — the meter dial's OFF / STARTING. Sized so a driver glancing
     * across the cab knows the meter state without focusing. Nothing else may use this. */
    val display = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 64.sp)

    /** Card and pane titles ("LIVE DISPATCH"). */
    val h1 = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)

    /** Sub-card headings, the header's driver name. */
    val h2 = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)

    /** Tile titles ("SET PRICE", an announcement's title). */
    val h3 = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

    /** Running prose — an announcement body, an empty-state explanation, a dialog paragraph. */
    val body = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp)

    /** Uppercase eyebrow / caption labels ("MY ACCOUNT", "NIGHT FARE", rail item labels). The
     * generous [letterSpacing] is what makes 13sp uppercase read as a label rather than as small
     * body text. */
    val label = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
    )

    /** The floor — 12sp, and there is nothing smaller in this file on purpose (see the class doc).
     * Timestamps, "vs yesterday", the mid-fare lock caption. */
    val tiny = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

    /** Regos, driver numbers, docket references — fixed advance width, see [Type]'s own doc. */
    val mono = TextStyle(fontFamily = RobotoMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

    /** Stat-bar and tile numerals — the shift clock, the trip count, the dollar figure. */
    val numeral = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 28.sp)
}
