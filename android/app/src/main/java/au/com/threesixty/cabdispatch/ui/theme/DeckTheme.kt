package au.com.threesixty.cabdispatch.ui.theme

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
 */
object Deck {

    // --- Surfaces (Figma "SURFACES" section, node 3:5) ---
    val canvas = Color(0xFF0B0F16)
    val panel = Color(0xFF121927)
    val card = Color(0xFF1A2436)
    val raised = Color(0xFF223047)

    /** LED well — the near-black inset behind the giant fare numerals. */
    val inset = Color(0xFF05070C)
    val strokeSubtle = Color(0xFF26334A)
    val strokeStrong = Color(0xFF3A4A66)

    // --- Text & accent (node 3:36) ---
    val textPrimary = Color(0xFFF4F7FD)
    val textSecondary = Color(0xFF9FB0C9)
    val textMuted = Color(0xFF5F7089)
    val yellow = Color(0xFFFFC627) // brand/captain-yellow — primary CTAs, black text on top
    val info = Color(0xFF4DA3FF)
    val ledGreen = Color(0xFF49FFA3) // fare numerals while hired
    val ledAmber = Color(0xFFFFC94D) // fare numerals while waiting/stopped

    /** Ink used on top of [yellow] CTAs (Figma uses #0B0F16 — same as canvas). */
    val onYellow = canvas

    // --- Meter state system (node 3:67) — "always one, always unambiguous" ---
    val forHire = Color(0xFF2BD96B)
    val hired = Color(0xFFFF4438)
    val stopped = Color(0xFFFFAA2B)
    val offDuty = Color(0xFF64748B)
    val duress = Color(0xFF8B5CF6)
    val onForHire = Color(0xFF07220F) // dark green ink on the green pill/buttons
    val onStopped = Color(0xFF2A1D04)
    val onHired = Color(0xFFFFFFFF)

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
