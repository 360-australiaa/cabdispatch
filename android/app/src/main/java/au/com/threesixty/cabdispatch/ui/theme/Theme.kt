package au.com.threesixty.cabdispatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.ThemeMode

/**
 * Cab Dispatch brand palette — exact hex values from spec B1/CLAUDE instructions.
 * Keep these four as the single source of truth for the brand; the dashboard
 * (React/Tailwind) and this app must stay in visual lock-step with them.
 */
object CabDispatchColors {
    val Indigo = Color(0xFF2A1C58)
    val Gold = Color(0xFFF4C300)
    val Lavender = Color(0xFFEFEAF8)
    val Purple = Color(0xFF3A2774)
}

/**
 * Wheel-redesign dark-mode design tokens — verbatim port of the reference prototype's CSS custom
 * properties (docs/driver-dashboard-full-prototype.html `:root{...}`), matching design spec
 * TCT-DRIVER-APP-01.md §10 and the CLAUDE-instructions color list 1:1. Property names match the
 * CSS var names (kebab-case -> camelCase) so there's no translation ambiguity for the 8
 * screen-owning sibling agents building the wheel/dashboard/meter/payment screens on top of this.
 *
 * Note on what was actually already present vs. added this pass: checked [CabDispatchColors]
 * first as instructed — three of these fifteen values are numerically identical to existing brand
 * constants (`gold`==`Gold`, `surfaceRaised`==`Indigo`, `surfaceSunken`==`Purple`) and are reused
 * below rather than re-declared as duplicate `Color(...)` literals. The other twelve — including
 * `meterLedRed`/`goldSoft` — did not exist under any name before this pass; they're added in full
 * (not just the two the task named) because the wheel/meter/payment screens reference the whole
 * set by these exact semantic names (see the HTML prototype's CSS), and leaving 10 of them for
 * each sibling agent to separately re-derive/re-guess from the spec doc would just invite drift.
 *
 * Deliberately a flat object, not merged into Material3's `ColorScheme` — per [CabDispatchTheme]'s
 * existing doc comment, screens needing the exact high-contrast look should read color constants
 * directly, and the wheel redesign (all-dark per spec §10, "almost all screens ... built and
 * reviewed in Dark mode") is exactly that case.
 */
object WheelColors {
    val bg = Color(0xFF0D0A18)
    val surface = Color(0xFF1C1730)
    val surfaceRaised = CabDispatchColors.Indigo // #2A1C58 — same value, existing name
    val surfaceSunken = CabDispatchColors.Purple // #3A2774 — same value, existing name
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFF9F94C9)
    val textMuted = Color(0xFF7C7594)
    val border = Color(0x14FFFFFF) // rgba(255,255,255,0.08)
    val borderStrong = Color(0x2EFFFFFF) // rgba(255,255,255,0.18)
    val gold = CabDispatchColors.Gold // #F4C300 — same value, existing name
    val goldSoft = Color(0xFFF8DA66)
    val available = Color(0xFF2E9E4F)
    val waiting = Color(0xFFE0932B)
    val duress = Color(0xFFD8352E)

    /**
     * Fare-meter LED digit color — #FF2A28. No longer used for the digits themselves (see
     * [meterLedWhite]), kept defined since it's still referenced by name in doc comments
     * elsewhere (e.g. [au.com.threesixty.cabdispatch.ui.overlays.DuressOverlays]) explaining why
     * duress uses [duress] and not this. Distinct from [duress] (#D8352E) either way — a
     * physical-taxi-meter red is not a panic-alarm red, never conflate the two.
     */
    val meterLedRed = Color(0xFFFF2A28)

    /**
     * Fare-meter LED digit color, current — bright white with a soft glow (not [textPrimary]'s
     * flat white; this one is meant to read as illuminated, matching a real LED display), swapped
     * in from [meterLedRed] for back-seat-passenger legibility: white reads as more prominent and
     * higher-contrast against the dark dashboard than red at a glance, especially at the larger
     * size the digits now render at (see `HiredScreen.LedFareDigits`).
     */
    val meterLedWhite = Color(0xFFF4FAFF)
}

private val LightColors = lightColorScheme(
    primary = CabDispatchColors.Indigo,
    onPrimary = Color.White,
    secondary = CabDispatchColors.Gold,
    onSecondary = CabDispatchColors.Indigo,
    tertiary = CabDispatchColors.Purple,
    onTertiary = Color.White,
    background = CabDispatchColors.Lavender,
    onBackground = CabDispatchColors.Indigo,
    surface = Color.White,
    onSurface = CabDispatchColors.Indigo,
)

private val DarkColors = darkColorScheme(
    primary = CabDispatchColors.Gold,
    onPrimary = CabDispatchColors.Indigo,
    secondary = CabDispatchColors.Purple,
    onSecondary = Color.White,
    tertiary = CabDispatchColors.Lavender,
    onTertiary = CabDispatchColors.Indigo,
    background = CabDispatchColors.Indigo,
    onBackground = CabDispatchColors.Lavender,
    surface = CabDispatchColors.Purple,
    onSurface = Color.White,
)

/**
 * App-wide Material3 theme wrapper. S3 (Hired, passenger-visible fare) in
 * particular needs high contrast in direct sunlight — screens that need to
 * force the high-contrast look regardless of system theme should read
 * [CabDispatchColors] directly rather than [MaterialTheme.colorScheme].
 *
 * ### Light/Dark theme (2026-09-04 day-mode pass)
 * This is the ONE place [au.com.threesixty.cabdispatch.domain.ThemeMode] gets turned into an
 * actual `isLight` boolean and pushed out to [CaptainPalette]/[Deck] — every screen in the app
 * reads those two objects' tokens, never this composable directly, so applying the theme here once
 * (rather than per-screen) is what makes the toggle genuinely global. Composed exactly once, at the
 * very top of [au.com.threesixty.cabdispatch.MainActivity]'s `CabDispatchScreenRoot`.
 *
 * The `remember(isLight) { ... }` below (not a `LaunchedEffect`) is deliberate: [CaptainPalette]'s
 * tokens are plain mutable snapshot state, so writing them is a synchronous, side-effect-free-enough
 * operation that's safe to do inline during composition, and doing it in `remember` means the very
 * first frame after a theme switch already has the new colours — a `LaunchedEffect` would apply the
 * change one frame late (after this composable's children have already composed with the stale
 * palette), a visible flash on every toggle.
 */
@Composable
fun CabDispatchTheme(
    content: @Composable () -> Unit,
) {
    val themeMode by AppContainer.settingsPreferencesStore.themeMode.collectAsState()
    val systemInDarkTheme = isSystemInDarkTheme()
    val isLight = when (themeMode) {
        ThemeMode.LIGHT -> true
        ThemeMode.DARK -> false
        ThemeMode.SYSTEM -> !systemInDarkTheme
    }
    remember(isLight) {
        CaptainPalette.applyTheme(isLight)
        Deck.applyTheme(isLight)
    }
    val colorScheme = if (isLight) LightColors else DarkColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
