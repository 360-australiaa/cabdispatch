package au.com.threesixty.cabdispatch.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.R

/**
 * "Command Deck" design system — Meter v2 redesign (2026-08-27), ported 1:1 from the new Figma
 * file `h0PSsXQ971dOJvt25tN7BA` ("Cab Dispatch — Meter v2 Redesign"), page `00 · Design System`
 * node `3:2`. This REPLACES both the ad-hoc v1 `WheelColors` and the Phase-B `WheelColorsV2`
 * token sets for every screen ported to the v2 layout; the old objects are left in the tree only
 * until the last consumer is migrated.
 *
 * Core rules carried over from the Figma design-system page verbatim:
 * - 1280×800 landscape, always dark, ZERO vertical scroll — columns, not stacks.
 * - Touch targets ≥64dp; primary actions 72–88dp; keypad keys 140×78.
 * - One unambiguous meter state at all times — see [DeckState]'s color system.
 * - Compliance: TSP on splash · signed-tariff chip · GST + PSL in every total · discreet duress.
 *
 * ### Light/Dark theme (2026-09-04 day-mode pass)
 * The surface/text tokens below ("always dark" per the note above, until now) are
 * `mutableStateOf`-backed `var`s, swapped in bulk by [applyTheme] — see [CaptainPalette]'s own,
 * more detailed doc for why plain `State<Color>` fields (not a `CompositionLocal`) is the right
 * shape here: [ui.deck.DeckWidgets.DeckKey] (the live meter keypad's key) reads these from ordinary
 * Compose code, not a draw scope, but keeping both design systems' tokens the same *shape* means
 * one mental model for the whole app. The five meter-state/brand colours
 * ([yellow]/[info]/[ledGreen]/[ledAmber]/[forHire]/[hired]/[stopped]/[offDuty]/[duress]) are
 * deliberately left theme-**invariant**: these are unambiguous status colours (a green
 * "for hire"/red "hired" reads the same regardless of cabin lighting, the same way a traffic light
 * doesn't get a day palette), and their `on*` ink pairs are already fixed inks independent of
 * [canvas] with the one exception fixed in this pass — see [onYellow]'s doc.
 */
object Deck {

    // --- Surfaces (Figma "SURFACES" section, node 3:5) ---
    var canvas: Color by mutableStateOf(DeckDarkTokens.canvas); private set
    var panel: Color by mutableStateOf(DeckDarkTokens.panel); private set
    var card: Color by mutableStateOf(DeckDarkTokens.card); private set
    var raised: Color by mutableStateOf(DeckDarkTokens.raised); private set

    /** LED well — the near-black inset behind the giant fare numerals; a light recessed well in
     * light mode. */
    var inset: Color by mutableStateOf(DeckDarkTokens.inset); private set
    var strokeSubtle: Color by mutableStateOf(DeckDarkTokens.strokeSubtle); private set
    var strokeStrong: Color by mutableStateOf(DeckDarkTokens.strokeStrong); private set

    // --- Text & accent (node 3:36) ---
    var textPrimary: Color by mutableStateOf(DeckDarkTokens.textPrimary); private set
    var textSecondary: Color by mutableStateOf(DeckDarkTokens.textSecondary); private set
    var textMuted: Color by mutableStateOf(DeckDarkTokens.textMuted); private set
    val yellow = Color(0xFFFFC627) // brand/captain-yellow — primary CTAs, black text on top
    val info = Color(0xFF4DA3FF)
    val ledGreen = Color(0xFF49FFA3) // fare numerals while hired
    val ledAmber = Color(0xFFFFC94D) // fare numerals while waiting/stopped

    /** Ink used on top of [yellow] CTAs. Fixed to yellow's own dark-mode canvas value
     * (`#0B0F16`), NOT aliased to the live [canvas] property any more — [canvas] itself now flips
     * to a light colour in light mode, and a dark yellow CTA needs dark ink on top in *either*
     * theme (this was an actual bug caught by this pass: aliasing would have put light-on-light
     * "invisible" text on the yellow button as soon as light mode shipped). */
    val onYellow = Color(0xFF0B0F16)

    // --- Meter state system (node 3:67) — "always one, always unambiguous" ---
    val forHire = Color(0xFF2BD96B)
    val hired = Color(0xFFFF4438)
    val stopped = Color(0xFFFFAA2B)
    val offDuty = Color(0xFF64748B)
    val duress = Color(0xFF8B5CF6)
    val onForHire = Color(0xFF07220F) // dark green ink on the green pill/buttons
    val onStopped = Color(0xFF2A1D04)
    val onHired = Color(0xFFFFFFFF)

    /** Applies [isLight] to every surface/text token above — see [CaptainPalette.applyTheme], the
     * sibling call [CabDispatchTheme] makes alongside this one. */
    fun applyTheme(isLight: Boolean) {
        val t: DeckTokenSet = if (isLight) DeckLightTokens else DeckDarkTokens
        canvas = t.canvas
        panel = t.panel
        card = t.card
        raised = t.raised
        inset = t.inset
        strokeSubtle = t.strokeSubtle
        strokeStrong = t.strokeStrong
        textPrimary = t.textPrimary
        textSecondary = t.textSecondary
        textMuted = t.textMuted
    }

    // --- Radii / dimensions (LAYOUT & COMPLIANCE RULES, node 3:116) ---
    const val STATUS_STRIP_H = 44
    const val NAV_RAIL_W = 92
    const val DRIVE_PANEL_W = 400
    const val TOUCH_MIN = 64
    const val CTA_H = 88
    const val KEY_W = 140
    const val KEY_H = 78
    const val R_SM = 8
    const val R_MD = 14
    const val R_LG = 16
    const val R_XL = 24
}

/** Shape shared by [DeckDarkTokens]/[DeckLightTokens] — see [CaptainPalette.TokenSet]'s own doc for
 * why [Deck.applyTheme] needs this rather than an `if/else` over the two `object`s directly. */
private interface DeckTokenSet {
    val canvas: Color
    val panel: Color
    val card: Color
    val raised: Color
    val inset: Color
    val strokeSubtle: Color
    val strokeStrong: Color
    val textPrimary: Color
    val textSecondary: Color
    val textMuted: Color
}

/** [Deck]'s original, unchanged dark-mode surface/text values — the app's default. */
private object DeckDarkTokens : DeckTokenSet {
    override val canvas = Color(0xFF0B0F16)
    override val panel = Color(0xFF121927)
    override val card = Color(0xFF1A2436)
    override val raised = Color(0xFF223047)
    override val inset = Color(0xFF05070C)
    override val strokeSubtle = Color(0xFF26334A)
    override val strokeStrong = Color(0xFF3A4A66)
    override val textPrimary = Color(0xFFF4F7FD)
    override val textSecondary = Color(0xFF9FB0C9)
    override val textMuted = Color(0xFF5F7089)
}

/** [Deck]'s light-mode surface/text values (2026-09-04 day-mode pass) — same design rules as
 * [CaptainPalette]'s light token set (soft off-white canvas, near-white elevated cards, near-black
 * primary text); see that object's doc for the full rationale. The meter keypad
 * ([au.com.threesixty.cabdispatch.ui.deck.DeckKey]) is the one live consumer of these — its
 * `stopped` accent glyph colour is left theme-invariant per [Deck]'s own class doc. */
private object DeckLightTokens : DeckTokenSet {
    override val canvas = Color(0xFFEDEBF4)
    override val panel = Color(0xFFF7F5FB)
    override val card = Color(0xFFFFFFFF)
    override val raised = Color(0xFFFFFFFF)
    override val inset = Color(0xFFE7E4F0)
    override val strokeSubtle = Color(0xFFDEDAEA)
    override val strokeStrong = Color(0xFFC7C2D9)
    override val textPrimary = Color(0xFF14141C)
    override val textSecondary = Color(0xFF4B4F63)
    override val textMuted = Color(0xFF82869B)
}

// --- Font families (res/font — Chakra Petch static, Inter + Roboto Mono variable) ---

val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

/** Inter ships as a single variable font — each declared weight pins the `wght` axis so Compose
 * doesn't fake-bold the variable file. */
@OptIn(ExperimentalTextApi::class)
val InterFamily = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@OptIn(ExperimentalTextApi::class)
val RobotoMonoFamily = FontFamily(
    Font(R.font.roboto_mono_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.roboto_mono_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

/** Type scale — Figma "TYPE SCALE" section (node 3:90), sizes verbatim. */
object DeckType {
    /** LED / Fare display — Chakra Petch SemiBold 150 (the giant $ readout). */
    val ledFare = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 150.sp)

    /** LED / Meter data — Chakra Petch Medium 40 (km, time, speed under the fare). */
    val ledData = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 40.sp)

    val h1 = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, color = Deck.textPrimary)
    val h2 = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Deck.textPrimary)
    val h3 = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Deck.textPrimary)
    val body = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, color = Deck.textSecondary)

    /** Label — Inter Medium 13, caps, wide tracking ("TARIFF 1 — URBAN DAY · SIGNED ✓"). */
    val label = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp,
        color = Deck.textSecondary,
    )

    /** Data — Roboto Mono Medium 15 (receipt refs, GST figures, ids). */
    val data = TextStyle(fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Deck.textSecondary)

    /** Tiny bold caps used for nav-rail / KPI-tile labels (Inter Bold 11). */
    val tinyLabel = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = Deck.textMuted,
    )
}

/**
 * The five-state meter color system (Figma node 3:67). Exactly one is active at any moment and
 * it repeats in three places at once — status-strip pill, screen top border, drive-panel state
 * card — so the state is readable from the far side of the cab.
 */
enum class DeckState(val label: String, val color: Color, val ink: Color, val caption: String) {
    FOR_HIRE("FOR HIRE", Deck.forHire, Deck.onForHire, "Visible to dispatch. Live position heartbeat publishing every 30 s."),
    HIRED("HIRED", Deck.hired, Deck.onHired, "Meter running — fare accruing."),
    STOPPED("STOPPED · WAITING", Deck.stopped, Deck.onStopped, "Waiting time accrues below 26 km/h."),
    OFF_DUTY("OFF DUTY", Deck.offDuty, Deck.textPrimary, "Not accepting work. Dispatch cannot see this cab."),
    DURESS("DURESS", Deck.duress, Deck.textPrimary, "Silent alarm active."),
}
