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

    // --- Prominence-pass tokens (2026-09-02, Home-dashboard redesign) ------------------------
    // Added for the "match the mockup — lots of shades/colours, prominent" visual pass on
    // au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen: layered gradient washes
    // and glow accents instead of flat fills, using the SAME hues already above (primary/accent/
    // success/warning/danger) rather than inventing new ones — these are alpha/shade variants of
    // existing tokens, precomputed as literal ARGB hex (matching this file's existing convention)
    // rather than `.copy(alpha=...)` at every call site.

    /** Card background top-of-gradient — a hair lighter than [panel] so cards read as gently lit
     * from above rather than flat, without introducing a whole new neutral. */
    val cardTop = Color(0xFF15182A)

    /** Card background bottom-of-gradient — a hair darker than [panel]. Paired with [cardTop] via
     * `Brush.verticalGradient` for subtle depth on every major Home panel. */
    val cardBottom = Color(0xFF0D0E18)

    /** Low-alpha purple wash for background glows (page backdrop, header, meter-dial backdrop) —
     * [accent] at ~16% alpha, baked into the literal so call sites don't repeat `.copy(alpha=...)`. */
    val glowPurpleSoft = Color(0x2AA855F7)

    /** Stronger purple glow for a focal element's halo (meter dial ring backdrop, SOS armed state)
     * — [accent] at ~33% alpha. */
    val glowPurpleStrong = Color(0x55A855F7)

    /** Low-alpha green wash for "healthy/available" card tints (e.g. a subtle tint behind the
     * AVAILABLE pill) — [success] at ~14% alpha. */
    val glowSuccessSoft = Color(0x2439E27A)

    /** Low-alpha amber wash for "rank job/warning" tints (dispatch-card accents) — [warning] at
     * ~14% alpha. */
    val glowWarningSoft = Color(0x24FFB51B)

    /** Low-alpha red wash for "danger/SOS" backgrounds — [danger] at ~14% alpha. */
    val glowDangerSoft = Color(0x24EF4444)
}
