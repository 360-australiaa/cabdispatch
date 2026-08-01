package au.com.threesixty.cabdispatch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import au.com.threesixty.cabdispatch.ui.screens.availabletrips.AvailableTripOfferScreen
import au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen
import au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen
import au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen
import au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindScreen
import au.com.threesixty.cabdispatch.ui.screens.messages.MessageThreadScreen
import au.com.threesixty.cabdispatch.ui.screens.profile.ProfileScreen
import au.com.threesixty.cabdispatch.ui.screens.settings.SettingsScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftstart.ShiftStartScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted.ShiftSubmittedScreen
import au.com.threesixty.cabdispatch.ui.screens.splash.SplashScreen
import au.com.threesixty.cabdispatch.ui.screens.tripdetail.TripDetailScreen

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
}

@Composable
fun CabDispatchNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = CabDispatchRoutes.SPLASH,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(CabDispatchRoutes.SPLASH) {
            SplashScreen(navController = navController)
        }
        composable(CabDispatchRoutes.LOGIN_VEHICLE_BIND) {
            LoginVehicleBindScreen(navController = navController)
        }
        composable(CabDispatchRoutes.IDLE) {
            // Wheel-redesign home surface (dashboard shell agent) — replaces the old S2/Idle
            // screen; still registered under the same IDLE route key so every sibling
            // navigate(CabDispatchRoutes.IDLE) call above/elsewhere keeps working unchanged. The
            // old ui/screens/idle/IdleScreen.kt + IdleViewModel.kt are left in the tree
            // (unreferenced) rather than deleted, since their available-toggle/today's-stats
            // logic is what WheelDashboardViewModel's equivalent wiring was ported from.
            WheelDashboardScreen(navController = navController)
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
    }
}
