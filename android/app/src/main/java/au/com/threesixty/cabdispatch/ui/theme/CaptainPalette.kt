package au.com.threesixty.cabdispatch.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 *
 * ### Light/Dark theme (2026-09-04 day-mode pass)
 * Every token below used to be a plain `val` — one fixed dark colour, forever. It is now a
 * `mutableStateOf`-backed `var` (private setter) seeded from [DarkTokens] and reassigned in bulk by
 * [applyTheme] whenever the active [au.com.threesixty.cabdispatch.domain.ThemeMode] changes (see
 * [CabDispatchTheme] — the one place [applyTheme] is called, driven by the real Settings -> Display
 * toggle). Every call site in the app keeps reading `CaptainPalette.textPrimary` etc. completely
 * unchanged; the property itself now resolves to whichever theme is active.
 *
 * This is a **plain Compose snapshot `State`**, not a `CompositionLocal` + `@Composable get()` —
 * deliberately, because several of these tokens (`hudTrack`, `hudSweepStart/Mid/End`, `hudAccent`
 * inside [drawHudArc], the tick colours in [GlowingSpeedometer]) are read from *inside* a `Canvas`'s
 * `DrawScope` draw lambda, which runs during the draw phase, not composition — a `@Composable`
 * getter is illegal to call there. A `State<Color>` has no such restriction: Compose's snapshot
 * system observes reads made during draw exactly the same as reads made during composition, and
 * invalidates only what needs to redraw. So a plain field read (`CaptainPalette.hudTrack`) keeps
 * working, unchanged, in every context it was already used in, and now also repaints automatically
 * the instant [applyTheme] flips it.
 *
 * [isLight] is the one non-colour flag alongside the tokens — [Hud.kt][drawHudArc] and
 * [CaptainWidgets.kt][neonGlow] read it to pick between the dark-mode neon-blur glow and the
 * light-mode crisp-ring-plus-soft-shadow treatment (see those files' own docs for why a blurred
 * neon glow that reads as "a lit sign" on a near-black background reads as a muddy smear on a
 * light one, and what replaces it).
 */
object CaptainPalette {
    /** True once [applyTheme] has been called with `isLight = true`. Read (never written) from
     * draw-scope code in [Hud.kt] and [CaptainWidgets.kt] to switch glow techniques — see class doc. */
    var isLight: Boolean by mutableStateOf(false)
        private set

    var bg: Color by mutableStateOf(DarkTokens.bg); private set
    var panel: Color by mutableStateOf(DarkTokens.panel); private set
    var panelBorder: Color by mutableStateOf(DarkTokens.panelBorder); private set
    var raised: Color by mutableStateOf(DarkTokens.raised); private set
    var inset: Color by mutableStateOf(DarkTokens.inset); private set

    var textPrimary: Color by mutableStateOf(DarkTokens.textPrimary); private set
    var textSecondary: Color by mutableStateOf(DarkTokens.textSecondary); private set
    var textMuted: Color by mutableStateOf(DarkTokens.textMuted); private set

    /** Primary CTA fill — Figma's `#7c2cff` in dark mode; deepened for light mode (see
     * [LightTokens]'s own doc). Pair with [onAccent] for the label/icon colour on top, never
     * [textPrimary] — [textPrimary] flips to near-black in light mode and would go invisible on a
     * purple fill (this was an actual bug fixed in this pass; see [CaptainButton]/message-bubble
     * call sites that used to read `textPrimary` on a `primary` background). */
    var primary: Color by mutableStateOf(DarkTokens.primary); private set

    /** Accent used for the meter-dial ring/ticks and active nav-rail highlight — Figma's `#a855f7`
     * in dark mode; deepened for light mode. See [primary]'s doc — same on-colour-text rule applies. */
    var accent: Color by mutableStateOf(DarkTokens.accent); private set

    var success: Color by mutableStateOf(DarkTokens.success); private set
    var warning: Color by mutableStateOf(DarkTokens.warning); private set
    var danger: Color by mutableStateOf(DarkTokens.danger); private set

    /** Meter-dial idle ring/tick colour — Figma's neutral tick `#34384c` in dark mode. */
    var dialNeutral: Color by mutableStateOf(DarkTokens.dialNeutral); private set

    /** Text/icon colour for content painted on top of a solid [primary] or [accent] fill (a CTA
     * button's label, a chat bubble's own text, a "VERIFIED DRIVER" badge). Deliberately **not**
     * theme-reactive — [primary]/[accent] stay dark-enough, saturated fills in both themes (see
     * [LightTokens]'s doc), so plain white reads correctly on top of either, the same way
     * [Deck.onHired]/[Deck.onYellow] are fixed inks rather than swapped per theme. */
    val onAccent: Color = Color(0xFFFFFFFF)

    // --- Prominence-pass tokens (2026-09-02, Home-dashboard redesign) ------------------------
    // Added for the "match the mockup — lots of shades/colours, prominent" visual pass on
    // au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen: layered gradient washes
    // and glow accents instead of flat fills, using the SAME hues already above (primary/accent/
    // success/warning/danger) rather than inventing new ones — these are alpha/shade variants of
    // existing tokens, precomputed as literal ARGB hex (matching this file's existing convention)
    // rather than `.copy(alpha=...)` at every call site.

    /** Card background top-of-gradient — a hair lighter than [panel] so cards read as gently lit
     * from above rather than flat, without introducing a whole new neutral. */
    var cardTop: Color by mutableStateOf(DarkTokens.cardTop); private set

    /** Card background bottom-of-gradient — a hair darker than [panel]. Paired with [cardTop] via
     * `Brush.verticalGradient` for subtle depth on every major Home panel. */
    var cardBottom: Color by mutableStateOf(DarkTokens.cardBottom); private set

    /** Low-alpha purple wash for background glows (page backdrop, header, meter-dial backdrop) —
     * [accent] at ~16% alpha, baked into the literal so call sites don't repeat `.copy(alpha=...)`. */
    var glowPurpleSoft: Color by mutableStateOf(DarkTokens.glowPurpleSoft); private set

    /** Stronger purple glow for a focal element's halo (meter dial ring backdrop, SOS armed state)
     * — [accent] at ~33% alpha. */
    var glowPurpleStrong: Color by mutableStateOf(DarkTokens.glowPurpleStrong); private set

    /** Low-alpha green wash for "healthy/available" card tints (e.g. a subtle tint behind the
     * AVAILABLE pill) — [success] at ~14% alpha. */
    var glowSuccessSoft: Color by mutableStateOf(DarkTokens.glowSuccessSoft); private set

    /** Low-alpha amber wash for "rank job/warning" tints (dispatch-card accents) — [warning] at
     * ~14% alpha. */
    var glowWarningSoft: Color by mutableStateOf(DarkTokens.glowWarningSoft); private set

    /** Low-alpha red wash for "danger/SOS" backgrounds — [danger] at ~14% alpha. */
    var glowDangerSoft: Color by mutableStateOf(DarkTokens.glowDangerSoft); private set

    // --- HUD kit tokens (2026-09-03, `ui/theme/Hud.kt`) --------------------------------------
    // The automotive-cockpit / game-HUD visual standard's palette, supplied as an exact technical
    // blueprint. Named here (rather than as literals in Hud.kt) so a screen rebuilt on the HUD kit
    // and the kit itself agree on one source of truth. Additive only — nothing above changes.

    /** HUD page background — `#0B0B10` in dark mode. A hair bluer/lighter than [bg]; the HUD kit's
     * previews and any screen built on it paint this, not [bg]. */
    var hudBg: Color by mutableStateOf(DarkTokens.hudBg); private set

    /** HUD neon accent — `#6E3FF3` in dark mode. Glow arcs, lit speedometer ticks, the Mapbox glow
     * line, and (deepened) the light-mode crisp ring stroke — see [Hud.kt]'s draw-arc doc. */
    var hudAccent: Color by mutableStateOf(DarkTokens.hudAccent); private set

    /** Gauge foreground sweep gradient, start → mid → end. */
    var hudSweepStart: Color by mutableStateOf(DarkTokens.hudSweepStart); private set
    var hudSweepMid: Color by mutableStateOf(DarkTokens.hudSweepMid); private set
    var hudSweepEnd: Color by mutableStateOf(DarkTokens.hudSweepEnd); private set

    /** The three sweep stops as one list, for `Brush.sweepGradient` call sites. Recomputed as a
     * plain `val` accessor (not cached) since it's cheap and must reflect the live tokens above. */
    val hudSweep: List<Color> get() = listOf(hudSweepStart, hudSweepMid, hudSweepEnd)

    /** Gauge dark track arc — `#1E1A2D` in dark mode; a light neutral groove in light mode. */
    var hudTrack: Color by mutableStateOf(DarkTokens.hudTrack); private set

    /** Glass surface fill — 80% alpha over the map in dark mode; a near-opaque white in light mode
     * (see [GlassCard]'s doc — daylight glare wants MORE opacity, not less, to keep card text
     * legible over a bright map/photo behind it). */
    var hudGlass: Color by mutableStateOf(DarkTokens.hudGlass); private set

    /** Glass card 1dp border gradient, stop 1 (top-left) — purple in both themes. */
    var hudGlassBorderPurple: Color by mutableStateOf(DarkTokens.hudGlassBorderPurple); private set

    /** Glass card 1dp border gradient, stop 2 (bottom-right) — low-alpha white in dark mode (reads
     * as a lit edge); a soft low-alpha **dark** tint in light mode so the border stays visible
     * against a near-white card instead of vanishing white-on-white. Same field name kept across
     * themes (it names the gradient's second stop, not literally "always white"). */
    var hudGlassBorderWhite: Color by mutableStateOf(DarkTokens.hudGlassBorderWhite); private set

    // --- Fallback "illustrative map" tokens (2026-09-04 day-mode pass) -----------------------
    // [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `IllustrativeStreetGrid`
    // (and, until it was deleted as confirmed-dead code 2026-09-05, a duplicate of it in the
    // Navigate placeholder screen) drew a fixed dark-navy fake map (`Color(0xFF0D1420)` etc.)
    // regardless of theme — a real bug this pass fixes (see this pass's report): a hardcoded
    // night-map illustration behind a light-themed dashboard would look broken. Named here so any
    // future screen needing the same illustrative map doesn't duplicate these literals.
    var mapBg: Color by mutableStateOf(DarkTokens.mapBg); private set
    var mapStreet: Color by mutableStateOf(DarkTokens.mapStreet); private set
    var mapArterial: Color by mutableStateOf(DarkTokens.mapArterial); private set
    var mapLabel: Color by mutableStateOf(DarkTokens.mapLabel); private set

    /** Fixed (non-theme-reactive) dark, low-alpha tint for the light-mode "soft drop shadow" pass
     * in [Hud.kt]'s `drawHudArc`/[neonGlow] — see those files' docs for why light mode trades the
     * dark-mode neon blur for a crisp ring + a real (dark, small-blur) elevation shadow instead of
     * a colour-tinted one. Only ever read inside an `if (isLight)` branch. */
    val hudDayShadow: Color = Color(0x3315101F)

    /**
     * Applies [isLight] across every token above in one shot — the only place any of these vars is
     * ever assigned outside their own declaration. Called from [CabDispatchTheme] (via
     * `remember(isLight) { ... }`, not a `LaunchedEffect`, so the very first frame after a theme
     * switch already has the right colours — no one-frame flash of the old theme).
     */
    fun applyTheme(isLight: Boolean) {
        this.isLight = isLight
        val t: TokenSet = if (isLight) LightTokens else DarkTokens
        bg = t.bg
        panel = t.panel
        panelBorder = t.panelBorder
        raised = t.raised
        inset = t.inset
        textPrimary = t.textPrimary
        textSecondary = t.textSecondary
        textMuted = t.textMuted
        primary = t.primary
        accent = t.accent
        success = t.success
        warning = t.warning
        danger = t.danger
        dialNeutral = t.dialNeutral
        cardTop = t.cardTop
        cardBottom = t.cardBottom
        glowPurpleSoft = t.glowPurpleSoft
        glowPurpleStrong = t.glowPurpleStrong
        glowSuccessSoft = t.glowSuccessSoft
        glowWarningSoft = t.glowWarningSoft
        glowDangerSoft = t.glowDangerSoft
        hudBg = t.hudBg
        hudAccent = t.hudAccent
        hudSweepStart = t.hudSweepStart
        hudSweepMid = t.hudSweepMid
        hudSweepEnd = t.hudSweepEnd
        hudTrack = t.hudTrack
        hudGlass = t.hudGlass
        hudGlassBorderPurple = t.hudGlassBorderPurple
        hudGlassBorderWhite = t.hudGlassBorderWhite
        mapBg = t.mapBg
        mapStreet = t.mapStreet
        mapArterial = t.mapArterial
        mapLabel = t.mapLabel
    }

    /** Shape shared by [DarkTokens]/[LightTokens] so [applyTheme] can hold `t` as one statically-
     * typed reference (`if (isLight) LightTokens else DarkTokens` has no common type without this —
     * two unrelated `object`s' properties aren't otherwise visible through their `Any` supertype). */
    private interface TokenSet {
        val bg: Color
        val panel: Color
        val panelBorder: Color
        val raised: Color
        val inset: Color
        val textPrimary: Color
        val textSecondary: Color
        val textMuted: Color
        val primary: Color
        val accent: Color
        val success: Color
        val warning: Color
        val danger: Color
        val dialNeutral: Color
        val cardTop: Color
        val cardBottom: Color
        val glowPurpleSoft: Color
        val glowPurpleStrong: Color
        val glowSuccessSoft: Color
        val glowWarningSoft: Color
        val glowDangerSoft: Color
        val hudBg: Color
        val hudAccent: Color
        val hudSweepStart: Color
        val hudSweepMid: Color
        val hudSweepEnd: Color
        val hudTrack: Color
        val hudGlass: Color
        val hudGlassBorderPurple: Color
        val hudGlassBorderWhite: Color
        val mapBg: Color
        val mapStreet: Color
        val mapArterial: Color
        val mapLabel: Color
    }

    /** The complete dark-mode token set — every value this file shipped with before the day-mode
     * pass, verbatim. The app's default (a device that has never touched Settings -> Display keeps
     * today's exact look). */
    private object DarkTokens : TokenSet {
        override val bg = Color(0xFF05070D)
        override val panel = Color(0xFF12131C)
        override val panelBorder = Color(0xFF222433)
        override val raised = Color(0xFF171B2A)
        override val inset = Color(0xFF181C2B)
        override val textPrimary = Color(0xFFF5F7FB)
        override val textSecondary = Color(0xFF8D93A6)
        override val textMuted = Color(0xFF5F6478)
        override val primary = Color(0xFF7C2CFF)
        override val accent = Color(0xFFA855F7)
        override val success = Color(0xFF39E27A)
        override val warning = Color(0xFFFFB51B)
        override val danger = Color(0xFFEF4444)
        override val dialNeutral = Color(0xFF34384C)
        override val cardTop = Color(0xFF15182A)
        override val cardBottom = Color(0xFF0D0E18)
        override val glowPurpleSoft = Color(0x2AA855F7)
        override val glowPurpleStrong = Color(0x55A855F7)
        override val glowSuccessSoft = Color(0x2439E27A)
        override val glowWarningSoft = Color(0x24FFB51B)
        override val glowDangerSoft = Color(0x24EF4444)
        override val hudBg = Color(0xFF0B0B10)
        override val hudAccent = Color(0xFF6E3FF3)
        override val hudSweepStart = Color(0xFF5B3FD6)
        override val hudSweepMid = Color(0xFF9E77FF)
        override val hudSweepEnd = Color(0xFF6E3FF3)
        override val hudTrack = Color(0xFF1E1A2D)
        override val hudGlass = Color(0xCC0D0D12)
        override val hudGlassBorderPurple = Color(0x669E77FF)
        override val hudGlassBorderWhite = Color(0x33FFFFFF)
        override val mapBg = Color(0xFF0D1420)
        override val mapStreet = Color(0xFF1C2940)
        override val mapArterial = Color(0xFF243352)
        override val mapLabel = Color(0xFF33445F)
    }

    /**
     * The complete light-mode token set (2026-09-04 day-mode pass) — a real daylight palette, not
     * an inverted dark one. Design rules followed throughout (see this pass's report for the full
     * rationale + measured contrast ratios):
     * - Background is a soft, cool off-white (`#EDEBF4`/`#F4F3F8`) — never stark `#FFFFFF` — the
     *   same "not stark" judgement [DarkTokens.bg] made for dark, mirrored for light glare.
     * - The purple brand hue is kept throughout, but every accent/status colour that ever doubles
     *   as *text* ([accent], [success], [warning], [danger], [hudAccent]) is deepened from its
     *   dark-mode value until it clears WCAG AA (4.5:1) against both `panel` and `bg` — verified
     *   with the real relative-luminance formula, not eyeballed:
     *   `success` `#0E7A3E` 5.4:1, `warning` `#92600A` 5.4:1, `danger` `#C81E1E` 5.7:1, `accent`
     *   `#7C3AED` 5.7:1, `hudAccent` `#5B21B6` 9.0:1, all measured against white; `textPrimary`
     *   `#14141C` is 18.3:1 against `bg` — the fare figure and RUNNING/PAUSED/duress pills (this
     *   pass's explicit "get these right first") land on the darkest, highest-contrast end of that
     *   set ([success]/[danger] for the pill text, [textPrimary] for the fare).
     * - Fills that are only ever backgrounds, never text ([primary], [dialNeutral], [hudTrack],
     *   the glow washes) don't need the same treatment — they're judged on visual weight/definition
     *   against the new light surfaces instead.
     */
    private object LightTokens : TokenSet {
        override val bg = Color(0xFFEDEBF4)
        override val panel = Color(0xFFFCFBFE)
        override val panelBorder = Color(0xFFDEDAEA)
        override val raised = Color(0xFFFFFFFF)
        override val inset = Color(0xFFE7E4F0)
        override val textPrimary = Color(0xFF14141C)
        override val textSecondary = Color(0xFF4B4F63)
        override val textMuted = Color(0xFF82869B)
        override val primary = Color(0xFF6A1FE0)
        override val accent = Color(0xFF7C3AED)
        override val success = Color(0xFF0E7A3E)
        override val warning = Color(0xFF92600A)
        override val danger = Color(0xFFC81E1E)
        override val dialNeutral = Color(0xFFC9C6D9)
        override val cardTop = Color(0xFFFFFFFF)
        override val cardBottom = Color(0xFFF6F4FB)
        override val glowPurpleSoft = Color(0x1F7C3AED)
        override val glowPurpleStrong = Color(0x407C3AED)
        override val glowSuccessSoft = Color(0x1D0E7A3E)
        override val glowWarningSoft = Color(0x1D92600A)
        override val glowDangerSoft = Color(0x1DC81E1E)
        override val hudBg = Color(0xFFF4F3F8)
        override val hudAccent = Color(0xFF5B21B6)
        override val hudSweepStart = Color(0xFF4C1D95)
        override val hudSweepMid = Color(0xFF7C3AED)
        override val hudSweepEnd = Color(0xFF5B21B6)
        override val hudTrack = Color(0xFFE3E0EE)
        override val hudGlass = Color(0xE6FFFFFF)
        override val hudGlassBorderPurple = Color(0x8A7C3AED)
        override val hudGlassBorderWhite = Color(0x40000000)
        override val mapBg = Color(0xFFE7E5F0)
        override val mapStreet = Color(0xFFD3CFE3)
        override val mapArterial = Color(0xFFB7B0D2)
        override val mapLabel = Color(0xFF6B6F87)
    }
}
