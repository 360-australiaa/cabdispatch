package au.com.threesixty.cabdispatch.domain

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backing store for the three genuinely-real Settings rows added in the Settings two-pane pass
 * (2026-09-03) — Auto Accept Jobs, Show Map in Background, Allow Cash. Mirrors
 * [DevicePairingStore]/[MaxiVehicleStore]'s own `getSharedPreferences(..., MODE_PRIVATE)`
 * pattern — this app's existing precedent for small durable per-device state — rather than
 * introducing a new persistence mechanism (Jetpack DataStore) for three booleans; there is no
 * DataStore dependency anywhere in this project's Gradle files today, and these three flags have
 * no need for DataStore's reactive-Flow-over-disk machinery beyond what a small in-memory
 * [MutableStateFlow] seeded from [android.content.SharedPreferences] already gives for free below.
 *
 * Unlike [MaxiVehicleStore] (a single flag read once per screen visit into a ViewModel's own
 * `StateFlow`), each flag here is exposed as its own [StateFlow] directly off this singleton —
 * several *different* screens/ViewModels need to react to the same flag ([SettingsScreen] as the
 * writer; [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s background map,
 * [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen]'s payment grid, and
 * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel]'s offer-accept
 * flow as readers), none of which share a ViewModel instance with Settings — a plain "read once at
 * init" mirror the way [SettingsUiState.isMaxiVehicle] does it would leave those other screens
 * showing a stale value until their own process happened to re-read it.
 */
class SettingsPreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("settings_preferences", Context.MODE_PRIVATE)

    /**
     * When on, the very next job offer this device sees is accepted automatically — see
     * [au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelViewModel]'s own doc for
     * the actual accept hook. Defaults `false`: a driver who never touches this setting keeps
     * today's manual accept/decline behaviour exactly as before this row existed.
     */
    private val _autoAcceptJobs = MutableStateFlow(prefs.getBoolean(KEY_AUTO_ACCEPT_JOBS, false))
    val autoAcceptJobs: StateFlow<Boolean> = _autoAcceptJobs.asStateFlow()
    fun setAutoAcceptJobs(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ACCEPT_JOBS, value).apply()
        _autoAcceptJobs.value = value
    }

    /**
     * Gates the Live Map pane's background Mapbox Static Images fetch
     * ([au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen]'s `StatusMapPanel`) —
     * defaults `true` so a driver who never touches this setting keeps today's always-on map
     * exactly as before this row existed.
     */
    private val _showMapInBackground = MutableStateFlow(prefs.getBoolean(KEY_SHOW_MAP_IN_BACKGROUND, true))
    val showMapInBackground: StateFlow<Boolean> = _showMapInBackground.asStateFlow()
    fun setShowMapInBackground(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_MAP_IN_BACKGROUND, value).apply()
        _showMapInBackground.value = value
    }

    /**
     * When off, Close & Pay's CASH payment option is disabled (never removed outright — see
     * [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen]'s `PayCard` `enabled`
     * parameter). Defaults `true`: a driver/operator who never touches this setting keeps today's
     * cash-accepted behaviour exactly as before this row existed.
     */
    private val _allowCash = MutableStateFlow(prefs.getBoolean(KEY_ALLOW_CASH, true))
    val allowCash: StateFlow<Boolean> = _allowCash.asStateFlow()
    fun setAllowCash(value: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_CASH, value).apply()
        _allowCash.value = value
    }

    /**
     * The real Light/Dark(/System) display theme (2026-09-04 day-mode pass) — see [ThemeMode]'s
     * own doc. Read by [au.com.threesixty.cabdispatch.ui.theme.CabDispatchTheme] (the composition
     * root, not a screen) to drive [au.com.threesixty.cabdispatch.ui.theme.CaptainPalette.applyTheme]
     * for the whole app — a `StateFlow` here rather than a plain field for the same reason
     * [showMapInBackground] is one: the writer (Settings' Display tab) and the reader
     * ([CabDispatchTheme], composed once at the very top of [au.com.threesixty.cabdispatch.MainActivity])
     * are two different composables with no shared ViewModel. Defaults [ThemeMode.DARK]: a driver
     * who never opens Settings -> Display keeps today's exact HUD look, unchanged by this pass.
     */
    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, null)?.let { saved ->
            runCatching { ThemeMode.valueOf(saved) }.getOrNull()
        } ?: ThemeMode.DARK,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    fun setThemeMode(value: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        _themeMode.value = value
    }

    private companion object {
        const val KEY_AUTO_ACCEPT_JOBS = "auto_accept_jobs"
        const val KEY_SHOW_MAP_IN_BACKGROUND = "show_map_in_background"
        const val KEY_ALLOW_CASH = "allow_cash"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
