package au.com.threesixty.cabdispatch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens lifted directly from the Captain Taxis Figma file
 * (`NP1afUMe5UKIl3CQUBRnyV`, "Driver Tablet — First 3 Screens", 2026-08-29 design pass) —
 * exact hex values off the design's own React/Tailwind export, not eyeballed off the screenshot.
 *
 * Deliberately its OWN object, not an edit to [Deck] above: [Deck]'s yellow/black palette is the
 * app's existing brand and is used by every other screen in this app (Hired, Settings, Profile,
 * Close & Pay, …) — repainting [Deck] itself to Captain Taxis' purple would silently reskin all of
 * them, which is a full rebrand decision far outside "update the first 3 screens" and would need
 * its own explicit sign-off (see this pass's delivery notes). This object exists so the new
 * Captain Taxis dashboard ([au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen])
 * can be visually faithful to its own design file without moving that line for anyone else.
 */
object CaptainPalette {
    val bg = Color(0xFF05070D)
    val panel = Color(0xFF12131C)
    val panelBorder = Color(0xFF222433)
    val raised = Color(0xFF171B2A)
    val inset = Color(0xFF181C2B)

    val textPrimary = Color(0xFFF5F7FB)
    val textSecondary = Color(0xFF8D93A6)
    val textMuted = Color(0xFF5F6478)

    /** Primary CTA fill — Figma's `#7c2cff`. */
    val primary = Color(0xFF7C2CFF)

    /** Accent used for the meter-dial ring/ticks and active nav-rail highlight — Figma's `#a855f7`. */
    val accent = Color(0xFFA855F7)

    val success = Color(0xFF39E27A)
    val warning = Color(0xFFFFB51B)
    val danger = Color(0xFFEF4444)

    /** Meter-dial idle ring/tick colour — Figma's neutral tick `#34384c`. */
    val dialNeutral = Color(0xFF34384C)
}
