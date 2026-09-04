package au.com.threesixty.cabdispatch.domain

/**
 * The driver's chosen display theme (Settings -> Display, 2026-09-04 day-mode pass) — real values
 * a real Settings toggle writes through [SettingsPreferencesStore.themeMode], read once at the
 * composition root ([au.com.threesixty.cabdispatch.ui.theme.CabDispatchTheme]) to drive
 * [au.com.threesixty.cabdispatch.ui.theme.CaptainPalette.applyTheme]/
 * [au.com.threesixty.cabdispatch.ui.theme.Deck.applyTheme] for the whole app in one place.
 *
 * [DARK] is the default (see [SettingsPreferencesStore]'s own doc) — a driver who never opens
 * Settings -> Display keeps today's exact HUD look, unchanged by this pass.
 */
enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),

    /** Follows [androidx.compose.foundation.isSystemInDarkTheme] — the tablet's own Android
     * display setting. A nice-to-have alongside the required manual Light/Dark toggle, not the
     * only option: most of this app's tablets are dedicated, kiosk-mounted devices where a driver
     * is far more likely to want to force Light for midday glare than to rely on whatever the
     * device's own system setting happens to be. */
    SYSTEM("System"),
}
