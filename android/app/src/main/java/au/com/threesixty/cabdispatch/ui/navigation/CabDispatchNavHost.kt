package au.com.threesixty.cabdispatch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen
import au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen
import au.com.threesixty.cabdispatch.ui.screens.idle.IdleScreen
import au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindScreen
import au.com.threesixty.cabdispatch.ui.screens.settings.SettingsScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportScreen

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
    const val LOGIN_VEHICLE_BIND = "login_vehicle_bind" // S1
    const val IDLE = "idle" // S2
    const val HIRED = "hired" // S3
    const val CLOSE_PAY = "close_pay" // S4
    const val SHIFT_REPORT = "shift_report" // S5
    const val SETTINGS = "settings" // S6
}

@Composable
fun CabDispatchNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = CabDispatchRoutes.LOGIN_VEHICLE_BIND,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(CabDispatchRoutes.LOGIN_VEHICLE_BIND) {
            LoginVehicleBindScreen(navController = navController)
        }
        composable(CabDispatchRoutes.IDLE) {
            IdleScreen(navController = navController)
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
    }
}
