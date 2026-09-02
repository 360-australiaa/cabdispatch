package au.com.threesixty.cabdispatch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import au.com.threesixty.cabdispatch.ui.screens.availabletrips.AvailableTripOfferScreen
import au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen
import au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen
import au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen
import au.com.threesixty.cabdispatch.ui.screens.logoff.LogOffScreen
import au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindScreen
import au.com.threesixty.cabdispatch.ui.screens.messages.MessageThreadScreen
import au.com.threesixty.cabdispatch.ui.screens.navigate.NavigatePlaceholderScreen
import au.com.threesixty.cabdispatch.ui.screens.offlinesync.OfflineSyncScreen
import au.com.threesixty.cabdispatch.ui.screens.permissions.PermissionsChecklistScreen
import au.com.threesixty.cabdispatch.ui.screens.profile.ProfileScreen
import au.com.threesixty.cabdispatch.ui.screens.settings.SettingsScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftstart.ShiftStartScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted.ShiftSubmittedScreen
import au.com.threesixty.cabdispatch.ui.screens.splash.SplashScreen
import au.com.threesixty.cabdispatch.ui.screens.terms.TermsDisclaimerScreen
import au.com.threesixty.cabdispatch.ui.screens.tripdetail.TripDetailScreen
import au.com.threesixty.cabdispatch.ui.screens.zones.PlotZoneScreen
import au.com.threesixty.cabdispatch.ui.screens.zones.ZoneStatisticsScreen

/**
 * Route name constants for the six meter screens (spec B5, S1–S6). Screens
 * import [CabDispatchRoutes] rather than hardcoding route strings.
 *
 * Flow (integration pass): S1 -> S2 -> S3 -> S4 -> back to S2 (S3/S4 popped
 * off the back stack on S4's "Done", see CLOSE_PAY's `popUpTo` below); S5 is
 * reachable from S2 (shift-report icon) and returns to S1 on submit; S6 is
 * reachable from every screen via a small settings icon/glyph and pops back
 * to wherever it was opened from.
 */
object CabDispatchRoutes {
    /** Row 1 — Splash (spec §8): brand mark + brief loading state, routes to [LOGIN_VEHICLE_BIND]
     * or [IDLE] depending on cached session (see [au.com.threesixty.cabdispatch.ui.screens.splash.SplashScreen]'s
     * doc for the current honest limits of "cached" given [au.com.threesixty.cabdispatch.domain.SessionHolder]
     * is in-memory only). This is now the app's actual start destination, ahead of S1. */
    const val SPLASH = "splash"
    const val LOGIN_VEHICLE_BIND = "login_vehicle_bind" // S1
    const val IDLE = "idle" // S2
    const val HIRED = "hired" // S3
    const val CLOSE_PAY = "close_pay" // S4
    const val SHIFT_REPORT = "shift_report" // S5
    const val SETTINGS = "settings" // S6

    /** S14 — Messages thread detail/quick-reply (wheel redesign, spec §8 row 13-14). Verified
     * (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]'s
     * Messages wheel-slot content ([au.com.threesixty.cabdispatch.ui.screens.messages.MessagesWheelContent])
     * `onOpenThread` callback now navigates here — see that screen's `MessagesSlotContent`. */
    const val MESSAGES_THREAD = "messages_thread"

    /** Row 16 — Trip detail (wheel redesign, spec §8): tap a "Trips" wheel-content history row
     * to see its full fare breakdown. See [au.com.threesixty.cabdispatch.ui.screens.tripdetail.TripDetailScreen]. */
    const val TRIP_DETAIL = "trip_detail"

    /** Row 19 — Submit shift confirmation (wheel redesign, spec §8): shown after the "Shift"
     * wheel content pane's Submit Shift action succeeds. See
     * [au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted.ShiftSubmittedScreen]. */
    const val SHIFT_SUBMITTED = "shift_submitted"

    /** Row 12 — Available Trips job-offer accept/decline detail (wheel redesign, spec §8).
     * Registered here ahead of the wheel-dashboard screen itself, same precedent
     * [MESSAGES_THREAD] set (no such dashboard screen exists in this tree yet). See
     * [au.com.threesixty.cabdispatch.ui.screens.availabletrips.AvailableTripOfferScreen]. */
    const val AVAILABLE_TRIP_OFFER = "available_trip_offer"

    /** Row 5 — Shift start confirmation (spec §8): shown once after S1's pre-shift-inspection
     * step successfully starts a shift, mirroring [SHIFT_SUBMITTED]'s visual pattern for symmetry.
     * See [au.com.threesixty.cabdispatch.ui.screens.shiftstart.ShiftStartScreen]. */
    const val SHIFT_START = "shift_start"

    /** Rows 20-21 — Profile: Compliance + Settings (spec §5: opened by tapping the dashboard's
     * top-left identity card, "demoted off the wheel since they're low-frequency" — NOT one of
     * the 6 wheel slots). [SETTINGS] above is deliberately left registered as its own standalone
     * route too — every other screen's small gear-icon affordance still targets it directly (see
     * `android/README.md`'s S6 convention), this is purely a second entry point that additionally
     * surfaces the Compliance Vault dossier. See
     * [au.com.threesixty.cabdispatch.ui.screens.profile.ProfileScreen]. */
    const val PROFILE = "profile"

    /** Plot / Statistics — zone-based demand screens (matches a real competitor taxi meter's
     * screens, backend/app/api/v1/zones.py). All 6 wheel slots are already spoken for
     * ([au.com.threesixty.cabdispatch.ui.wheel.WheelState.SLOT_COUNT] is a fixed 6, with the
     * angle/geometry math in [au.com.threesixty.cabdispatch.ui.wheel.WheelGeometry] hardcoded
     * against that count) so these are separate destinations, not a 7th/8th wheel slot — reached
     * from a small "Zones" entry point on the dashboard's [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
     * top status strip, same "demoted off the wheel" precedent [PROFILE] above already
     * documents for a low-frequency screen. [PLOT_ZONE] is the entry point (zone list + current
     * plot state); [ZONE_STATISTICS] is reached from a button on that screen and pops back to it. */
    const val PLOT_ZONE = "plot_zone"
    const val ZONE_STATISTICS = "zone_statistics"

    /** Dock-menu v2 pass (2026-08-26): "Navigate" placeholder. Figma node `35:356` ("15 · Navigate",
     * fileKey `JhEhok3n9bntRNS5Y1u3Yc`) mocks up a full-screen in-trip turn-by-turn banner/ETA
     * overlay, but this codebase has no turn-by-turn navigation feature anywhere to back it with —
     * the only real "navigate" affordance that exists is
     * [au.com.threesixty.cabdispatch.ui.overlays.NavigateOverlay]/`openInMaps`, a one-shot deep
     * link out to the device's own Maps app (no live route/ETA/speed state this app owns). This
     * route is a visual-only placeholder screen matching the Figma layout with clearly-labelled
     * static/mock figures — see [au.com.threesixty.cabdispatch.ui.screens.navigate.NavigatePlaceholderScreen]'s
     * doc. TODO(product decision): a real Navigate feature needs a turn-by-turn/ETA data source
     * (e.g. Mapbox Navigation SDK) this app does not currently have. */
    const val NAVIGATE_PLACEHOLDER = "navigate_placeholder"

    /** Boot-time Terms and Conditions / Privacy Policy disclaimer (2026-08-10 meter-polish
     * pass), registered ahead of S1 -- see [au.com.threesixty.cabdispatch.ui.screens.splash.SplashScreen]
     * for the gate deciding whether this is ever actually shown (once per app-version, per
     * [au.com.threesixty.cabdispatch.domain.TermsAcceptance]'s own doc), and
     * [au.com.threesixty.cabdispatch.ui.screens.terms.TermsDisclaimerScreen] for the screen
     * itself. */
    const val TERMS_DISCLAIMER = "terms_disclaimer"

    /** Permissions checklist (2026-08-10 meter-polish pass) -- reachable from Settings (S6), a
     * read-only status display of every runtime permission this app uses. See
     * [au.com.threesixty.cabdispatch.ui.screens.permissions.PermissionsChecklistScreen]. */
    const val PERMISSIONS_CHECKLIST = "permissions_checklist"

    /** Row 35 — Offline & Sync status (Phase B v2 pass, fileKey `JhEhok3n9bntRNS5Y1u3Yc` node
     * `20:114`): a new, dedicated read-only view over the outbox-drain/tariff-cache machinery that
     * already existed with no UI of its own before this pass (see
     * [au.com.threesixty.cabdispatch.ui.screens.offlinesync.OfflineSyncViewModel]'s doc).
     * Reachable from Settings & Diagnostics (S6). */
    const val OFFLINE_SYNC = "offline_sync"

    /** Row 36 — Log Off confirmation (Phase B v2 pass, fileKey `JhEhok3n9bntRNS5Y1u3Yc` node
     * `20:137`): a confirmation step in front of the dashboard's "LOG OFF" chip, which previously
     * jumped straight to [SHIFT_REPORT] with no confirmation at all. See
     * [au.com.threesixty.cabdispatch.ui.screens.logoff.LogOffScreen]'s doc. */
    const val LOG_OFF = "log_off"
}

@Composable
fun CabDispatchNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = CabDispatchRoutes.SPLASH,
) {
    // Premium-motion pass (2026-08-29): screens previously hard-cut between routes with zero
    // transition. One set of defaults here gives every route a consistent 300ms fade+drift —
    // forward navigation slides content gently up-and-in, back navigation reverses it. Kept
    // deliberately subtle (24dp travel) so an older driver never perceives it as content
    // "flying"; purely presentational, no navigation behavior/back-stack change.
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(300)) { it / 24 }
        },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)) },
        popExitTransition = {
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(220)) { it / 24 }
        },
    ) {
        composable(CabDispatchRoutes.SPLASH) {
            SplashScreen(navController = navController)
        }
        composable(CabDispatchRoutes.LOGIN_VEHICLE_BIND) {
            LoginVehicleBindScreen(navController = navController)
        }
        composable(CabDispatchRoutes.IDLE) {
            // Command Deck v2 home (2026-08-27 redesign port) — replaces the rotating-wheel
            // dashboard; still registered under the same IDLE route key so every sibling
            // navigate(CabDispatchRoutes.IDLE) call keeps working unchanged. The old
            // WheelDashboardScreen (and the older IdleScreen before it) are left in the tree
            // unreferenced; DeckHomeScreen reuses WheelDashboardViewModel as-is.
            DeckHomeScreen(navController = navController)
        }
        composable(CabDispatchRoutes.HIRED) {
            HiredScreen(navController = navController)
        }
        composable(CabDispatchRoutes.CLOSE_PAY) {
            CloseAndPayScreen(
                navController = navController,
                onDone = {
                    navController.navigate(CabDispatchRoutes.IDLE) {
                        popUpTo(CabDispatchRoutes.IDLE) { inclusive = true }
                    }
                },
            )
        }
        composable(CabDispatchRoutes.SHIFT_REPORT) {
            ShiftReportScreen(
                navController = navController,
                onDone = {
                    navController.navigate(CabDispatchRoutes.LOGIN_VEHICLE_BIND) {
                        popUpTo(0)
                    }
                },
            )
        }
        composable(CabDispatchRoutes.SETTINGS) {
            SettingsScreen(
                navController = navController,
                onFactoryReset = {
                    navController.navigate(CabDispatchRoutes.LOGIN_VEHICLE_BIND) {
                        popUpTo(0)
                    }
                },
            )
        }
        composable(CabDispatchRoutes.MESSAGES_THREAD) {
            MessageThreadScreen(navController = navController)
        }
        composable(CabDispatchRoutes.TRIP_DETAIL) {
            TripDetailScreen(navController = navController)
        }
        composable(CabDispatchRoutes.SHIFT_SUBMITTED) {
            ShiftSubmittedScreen(navController = navController)
        }
        composable(CabDispatchRoutes.AVAILABLE_TRIP_OFFER) {
            AvailableTripOfferScreen(navController = navController)
        }
        composable(CabDispatchRoutes.SHIFT_START) {
            ShiftStartScreen(navController = navController)
        }
        composable(CabDispatchRoutes.PROFILE) {
            ProfileScreen(
                navController = navController,
                onFactoryReset = {
                    navController.navigate(CabDispatchRoutes.LOGIN_VEHICLE_BIND) {
                        popUpTo(0)
                    }
                },
            )
        }
        composable(CabDispatchRoutes.PLOT_ZONE) {
            PlotZoneScreen(navController = navController)
        }
        composable(CabDispatchRoutes.ZONE_STATISTICS) {
            ZoneStatisticsScreen(navController = navController)
        }
        composable(CabDispatchRoutes.TERMS_DISCLAIMER) {
            TermsDisclaimerScreen(
                onAccept = {
                    navController.navigate(postAuthDestination()) {
                        popUpTo(CabDispatchRoutes.TERMS_DISCLAIMER) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = "${CabDispatchRoutes.PERMISSIONS_CHECKLIST}?next={next}",
            arguments = listOf(
                navArgument("next") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            // `next` is set only when reached from the splash launch gate (proceed to login/home
            // after granting); null when opened from Settings (just pop back).
            PermissionsChecklistScreen(
                navController = navController,
                next = backStackEntry.arguments?.getString("next"),
            )
        }
        composable(CabDispatchRoutes.OFFLINE_SYNC) {
            OfflineSyncScreen(navController = navController)
        }
        composable(CabDispatchRoutes.LOG_OFF) {
            LogOffScreen(navController = navController)
        }
        composable(CabDispatchRoutes.NAVIGATE_PLACEHOLDER) {
            NavigatePlaceholderScreen(navController = navController)
        }
    }
}

/**
 * Shared branch decision between [au.com.threesixty.cabdispatch.ui.screens.splash.SplashScreen]
 * (the normal path) and [CabDispatchRoutes.TERMS_DISCLAIMER]'s onAccept above (the boot-time
 * disclaimer path, 2026-08-10 meter-polish pass, only reached the first time a given app version
 * is ever opened) -- both need to answer the exact same question, "is there already a session, or
 * does this driver need to sign in", so this is factored out once here rather than duplicated in
 * both screens (which would risk them silently drifting apart over time).
 */
fun postAuthDestination(): String =
    if (au.com.threesixty.cabdispatch.domain.SessionHolder.session.value != null) {
        CabDispatchRoutes.IDLE
    } else {
        CabDispatchRoutes.LOGIN_VEHICLE_BIND
    }
