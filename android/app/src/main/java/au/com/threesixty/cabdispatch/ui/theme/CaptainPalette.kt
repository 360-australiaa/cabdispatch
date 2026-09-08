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
 *
 * ### Futuristic HUD reskin (2026-09-07, dark mode only)
 * Direct product brief: "futuristic, high-contrast aesthetic... deep indigo (#5B3FD6) and royal
 * purple (#6E3FF3) as the base thematic colors for structural elements... bright neon cyan for
 * active states, glowing accents, and high-priority controls." [hudSweepStart]/[hudAccent] already
 * carried those exact two hex values from the earlier HUD-kit pass, so this pass's real work is
 * threading a genuine neon-cyan token ([neonCyan] + its two alpha washes) through the tokens that
 * already stand for "this is good/active/online" — [success] and [glowSuccessSoft] — rather than
 * inventing a parallel colour system next to them, plus deepening [primary]/[accent] onto the
 * brief's own indigo/purple so a CTA fill and the meter-ring/nav-highlight colour agree with
 * [hudAccent] exactly. [hudGlassBorderWhite] (the glass-card border's second gradient stop) moves
 * from a plain low-alpha white to a low-alpha [neonCyan] tint — the brief's "thin semi-transparent
 * cyan or purple border" becomes a purple→cyan edge instead of a purple→white one. [bg] deepens a
 * hair further into a blue-black "slate" per the brief's "deep, dark slate" background, and
 * [textSecondary] shifts a few points bluer for the brief's "muted icy blue" secondary-label ask.
 *
 * **Dark mode only, deliberately** (see this pass's own delivery notes and constraint #4): the
 * brief describes a dark, neon aesthetic by name ("futuristic, high-contrast... deep, dark slate
 * background"), and [LightTokens] below is a real, already-shipped, separately-tuned WCAG-AA
 * daylight palette from the 2026-09-04 day-mode pass — repainting it neon would undo that pass's
 * own contrast work for a request that never asked for a light variant. [LightTokens] gains the
 * same three new fields (interface-required) but keeps its own already-correct values for every
 * token this pass touches in dark mode; only [neonCyan] and its washes get a genuinely new
 * (AA-deepened, teal-leaning) light value in case something reads it in light mode.
 *
 * **No motion added.** Every value below is a static colour; nothing here starts a new
 * `rememberInfiniteTransition`/timer-driven loop — see [Hud.kt]'s `GlowingMeterGauge`/
 * `GlowingSpeedometer` doc for the one already-approved motion pattern (real-speed-tied ember) and
 * why a second decorative loop is explicitly out of scope after the reverted always-orbiting
 * highlight.
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
    /** Pressed shade of [accent] -- an explicit darker step, never alpha. See CaptainButton. */
    var accentPressed: Color by mutableStateOf(DarkTokens.accentPressed); private set

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

    /**
     * The hottest point of the speedometer sweep, used only in [au.com.threesixty.cabdispatch.domain.SpeedBand.FAST].
     *
     * Deliberately not a warning colour: 60 km/h is a legal speed, and amber already means
     * PAUSED on the meter dial. This is the cyan run through to near-white — brighter, not
     * alarming.
     */
    var hudSweepHot: Color by mutableStateOf(DarkTokens.hudSweepHot); private set

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

    // --- Futuristic HUD reskin tokens (2026-09-07) — see this object's class doc, "Futuristic HUD
    // reskin" section, for why these are new fields rather than repainting an existing one, and why
    // only DarkTokens gets a genuinely neon value.

    /** Bright neon cyan — the brief's colour for "active states, glowing accents, and high-priority
     * controls": the Start Meter button's gradient, the holographic meter dial's outer bezel, and
     * (via [success] now resolving to this same hue in dark mode) every "good/active/online" tone
     * this app already had a token for (GPS/Wi-Fi/Printer status dots, AVAILABLE/VERIFIED pills,
     * the Trips/Earnings stat tiles). Deepened to a legible teal in light mode (see class doc). */
    var neonCyan: Color by mutableStateOf(DarkTokens.neonCyan); private set

    /** Low-alpha neon-cyan wash (~16%) for background glows, matching [glowPurpleSoft]'s alpha. */
    var neonCyanGlowSoft: Color by mutableStateOf(DarkTokens.neonCyanGlowSoft); private set

    /** Stronger neon-cyan glow (~33%) for a focal halo, matching [glowPurpleStrong]'s alpha. */
    var neonCyanGlowStrong: Color by mutableStateOf(DarkTokens.neonCyanGlowStrong); private set

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
        accentPressed = t.accentPressed
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
        hudSweepHot = t.hudSweepHot
        hudTrack = t.hudTrack
        hudGlass = t.hudGlass
        hudGlassBorderPurple = t.hudGlassBorderPurple
        hudGlassBorderWhite = t.hudGlassBorderWhite
        neonCyan = t.neonCyan
        neonCyanGlowSoft = t.neonCyanGlowSoft
        neonCyanGlowStrong = t.neonCyanGlowStrong
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
        /** The pressed shade of [accent] -- one explicit step darker, never alpha. See CaptainButton. */
        val accentPressed: Color
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
        val hudSweepHot: Color
        val hudTrack: Color
        val hudGlass: Color
        val hudGlassBorderPurple: Color
        val hudGlassBorderWhite: Color
        val neonCyan: Color
        val neonCyanGlowSoft: Color
        val neonCyanGlowStrong: Color
        val mapBg: Color
        val mapStreet: Color
        val mapArterial: Color
        val mapLabel: Color
    }

    /** The complete dark-mode token set — every value this file shipped with before the day-mode
     * pass, verbatim. The app's default (a device that has never touched Settings -> Display keeps
     * today's exact look). */
    private object DarkTokens : TokenSet {
        // bg deepened a hair further into a blue-black "slate" (2026-09-07 futuristic-HUD pass) —
        // was a near-neutral 0xFF05070D, now carries a touch more blue so it reads as "deep dark
        // slate" per the brief rather than plain near-black.
        // North-star tokens (owner reference render, 2026-09-08): the three surfaces the whole
        // app is built from, applied universally so every screen reads as one ecosystem.
        //   canvas  #080710   the deep-midnight ground everything sits on
        //   card    #161524   every panel / tile / sheet surface
        //   border  #26243C   the hairline that separates a card from the canvas
        // raised/inset are derived one step either side of the card so stacked surfaces still
        // read as stacked without introducing a fourth hue.
        override val bg = Color(0xFF080710)
        override val panel = Color(0xFF161524)
        override val panelBorder = Color(0xFF26243C)
        override val raised = Color(0xFF1C1B2E)
        override val inset = Color(0xFF12111E)
        override val textPrimary = Color(0xFFF5F7FB)
        // A few points bluer (2026-09-07): the brief's "muted icy blue" for secondary labels — was
        // a neutral grey-blue 0xFF8D93A6.
        override val textSecondary = Color(0xFF93AAD1)
        // #5F6478 -> #8A90A8 (A3, 2026-09-08). The old value measured ~3.0:1 against `hudGlass`
        // over `bg` - a WCAG AA FAILURE for the 11-13sp labels it is used on, which is most of the
        // small text on the driver's home screen (eyebrows, timestamps, "NEXT BREAK", ledger
        // sublines). ~4.6:1 at the new value clears AA for normal text with margin. The light
        // tokens carry measured ratios and were audited; dark had had no such pass since the
        // 2026-09-07 neon reskin, and this is that pass's one colour change.
        override val textMuted = Color(0xFF8A90A8)
        // primary/accent deepened onto the brief's own two named hexes (2026-09-07) — matching
        // hudAccent/hudSweepEnd exactly so a CTA fill, the meter-ring/nav-highlight colour and the
        // HUD kit's own accent all agree; was 0xFF7C2CFF / 0xFFA855F7 (a lighter violet pair).
        override val primary = Color(0xFF5B3FD6)
        override val accent = Color(0xFF6E3FF3)
        override val accentPressed = Color(0xFF5530C9)
        // success retinted to the brief's neon cyan (2026-09-07) — "bright neon cyan for active
        // states"; was green (0xFF39E27A). Cascades to every "good/active/online" surface that
        // already read this token (GPS/Wi-Fi/Printer status dots, AVAILABLE/VERIFIED pills, Trips/
        // Earnings stat tiles) — see class doc.
        // GREEN, per the north-star render (2026-09-08): AVAILABLE, the online/OK status dots and
        // the "12% vs yesterday" delta are all unmistakably green there. Was neon cyan
        // (0xFF00E5FF) from the 2026-09-07 HUD pass; cyan stays the HUD *sweep/hot* colour on the
        // dial, where it is decoration, and stops meaning "healthy", where it must be read at a
        // glance from a moving car. 0xFF22C55E measures ~7.3:1 on the card surface.
        override val success = Color(0xFF22C55E)
        override val warning = Color(0xFFFFB51B)
        override val danger = Color(0xFFEF4444)
        override val dialNeutral = Color(0xFF34384C)
        override val cardTop = Color(0xFF1A1930)
        override val cardBottom = Color(0xFF161524)
        override val glowPurpleSoft = Color(0x2AA855F7)
        override val glowPurpleStrong = Color(0x55A855F7)
        override val glowSuccessSoft = Color(0x2422C55E)
        override val glowWarningSoft = Color(0x24FFB51B)
        override val glowDangerSoft = Color(0x24EF4444)
        override val hudBg = Color(0xFF080710)
        override val hudAccent = Color(0xFF6E3FF3)
        override val hudSweepStart = Color(0xFF5B3FD6)
        override val hudSweepMid = Color(0xFF9E77FF)
        override val hudSweepEnd = Color(0xFF6E3FF3)
        override val hudSweepHot = Color(0xFFB8F6FF)
        override val hudTrack = Color(0xFF1E1A2D)
        override val hudGlass = Color(0xCC0D0D12)
        override val hudGlassBorderPurple = Color(0x669E77FF)
        // Border's 2nd gradient stop moves from low-alpha white to low-alpha neon cyan (2026-09-07)
        // — the brief's "thin semi-transparent cyan or purple border" reads as a purple-to-cyan lit
        // edge instead of a purple-to-white one; was 0x33FFFFFF.
        override val hudGlassBorderWhite = Color(0x4D00E5FF)
        override val neonCyan = Color(0xFF00E5FF)
        override val neonCyanGlowSoft = Color(0x2A00E5FF)
        override val neonCyanGlowStrong = Color(0x5500E5FF)
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
        override val accentPressed = Color(0xFF6528D1)
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
        // Light mode deepens rather than brightens -- a near-white hot point would vanish on a
        // pale ground, which is the same reason every other neon token is darkened here.
        override val hudSweepHot = Color(0xFF0B6B7A)
        override val hudTrack = Color(0xFFE3E0EE)
        override val hudGlass = Color(0xE6FFFFFF)
        override val hudGlassBorderPurple = Color(0x8A7C3AED)
        override val hudGlassBorderWhite = Color(0x40000000)
        // neonCyan (2026-09-07, futuristic-HUD pass): the ONLY new-to-this-pass field LightTokens
        // gives a genuinely new value — everything else this pass touches in dark mode keeps its
        // already-shipped, AA-checked light value unchanged (see class doc, "Dark mode only,
        // deliberately"). Deepened to a legible teal-cyan (5.1:1 against `bg`) in case a future
        // caller reads this token in light mode; nothing in this pass's own scope does.
        override val neonCyan = Color(0xFF0E7A8C)
        override val neonCyanGlowSoft = Color(0x1D0E7A8C)
        override val neonCyanGlowStrong = Color(0x400E7A8C)
        override val mapBg = Color(0xFFE7E5F0)
        override val mapStreet = Color(0xFFD3CFE3)
        override val mapArterial = Color(0xFFB7B0D2)
        override val mapLabel = Color(0xFF6B6F87)
    }
}
