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
import au.com.threesixty.cabdispatch.ui.screens.logoff.LogOffScreen
import au.com.threesixty.cabdispatch.ui.screens.login.LoginVehicleBindScreen
import au.com.threesixty.cabdispatch.ui.screens.messages.MessageThreadScreen
import au.com.threesixty.cabdispatch.ui.screens.offlinesync.OfflineSyncScreen
import au.com.threesixty.cabdispatch.ui.screens.permissions.PermissionsChecklistScreen
import au.com.threesixty.cabdispatch.ui.screens.profile.ProfileScreen
import au.com.threesixty.cabdispatch.ui.screens.rating.RatePassengerScreen
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.domain.DeviceReadiness
import au.com.threesixty.cabdispatch.ui.screens.readiness.DeviceReadinessScreen
import au.com.threesixty.cabdispatch.ui.screens.settings.SettingsScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftstart.ShiftStartScreen
import au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted.ShiftSubmittedScreen
import au.com.threesixty.cabdispatch.ui.screens.splash.SplashScreen
import au.com.threesixty.cabdispatch.ui.screens.terms.TermsDisclaimerScreen
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
     * doc — [au.com.threesixty.cabdispatch.domain.SessionHolder]'s driver/vehicle/shift identity is
     * durable across a process restart as of the 2026-09-04 session-persistence pass, restored by
     * [au.com.threesixty.cabdispatch.data.AppContainer.init] before this screen's gate ever runs;
     * see [au.com.threesixty.cabdispatch.domain.SessionStore]'s doc for exactly what that does and
     * does not cover). This is now the app's actual start destination, ahead of S1. */
    const val SPLASH = "splash"
    const val LOGIN_VEHICLE_BIND = "login_vehicle_bind" // S1
    const val IDLE = "idle" // S2
    const val HIRED = "hired" // S3
    const val CLOSE_PAY = "close_pay" // S4
    const val SHIFT_REPORT = "shift_report" // S5
    const val SETTINGS = "settings" // S6

    /** New post-trip "Rate Passenger" screen (2026-09-04, ratings backend pass) — reached from
     * [CLOSE_PAY]'s receipt step ("Done — back to For Hire") instead of going straight to [IDLE],
     * per [au.com.threesixty.cabdispatch.ui.screens.rating.RatePassengerScreen]'s own doc for the
     * exact hand-off. Same no-nav-graph-argument convention as [TRIP_DETAIL]/[MESSAGES_THREAD]
     * below — the trip to rate travels via
     * [au.com.threesixty.cabdispatch.domain.RatePassengerHandoff], not a route argument. */
    const val RATE_PASSENGER = "rate_passenger"

    /** S14 — Messages thread detail/quick-reply (wheel redesign, spec §8 row 13-14). Verified
     * (reconciliation pass): `WheelDashboardScreen` (deleted, P0.3)'s
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

    /** The device-readiness gate — see [au.com.threesixty.cabdispatch.domain.DeviceReadiness].
     * Reached only from [postAuthDestination], never navigated to directly, and it navigates
     * onward itself the moment the tablet becomes fit to use. */
    const val DEVICE_READINESS = "device_readiness"

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
            // WheelDashboardScreen (and the older IdleScreen before it) sat unreferenced in the
            // tree until Phase 0 (P0.3) deleted them; DeckHomeScreen is now the only post-login
            // home, and it reuses WheelDashboardViewModel as-is.
            DeckHomeScreen(navController = navController)
        }
        composable(CabDispatchRoutes.HIRED) {
            // Phase A shell-integration (2026-09-03): HIRED now renders the exact same shared-shell
            // composable as IDLE, just starting on the Meter pane, rather than a standalone
            // full-screen HiredScreen route with no header/footer/nav-rail — see DeckHomeScreen's
            // own class doc ("Meter joins the shared shell") and its `startOnMeter` param doc. This
            // keeps every existing `navController.navigate(CabDispatchRoutes.HIRED)` call site
            // (the dispatch wheel-content pane's accept action, the job-offer detail screen's
            // accept action, and DeckHomeScreen's own Start Meter/Set Price transition) working
            // unchanged — the route string didn't move, only what it renders.
            DeckHomeScreen(navController = navController, startOnMeter = true)
        }
        composable(CabDispatchRoutes.CLOSE_PAY) {
            CloseAndPayScreen(
                navController = navController,
                // Rate Passenger pass (2026-09-04): the receipt step's "Done — back to For Hire"
                // now routes through RATE_PASSENGER first (CloseAndPayScreen itself sets
                // au.com.threesixty.cabdispatch.domain.RatePassengerHandoff with the just-closed
                // trip's clientUuid immediately before calling this), then on to IDLE from there —
                // see RATE_PASSENGER's own composable below. popUpTo(CLOSE_PAY) clears this whole
                // S4 flow (method picker + receipt) off the back stack, same as the old direct
                // popUpTo(IDLE) did, so back from the rating screen can't return to a closed trip.
                onDone = {
                    navController.navigate(CabDispatchRoutes.RATE_PASSENGER) {
                        popUpTo(CabDispatchRoutes.CLOSE_PAY) { inclusive = true }
                    }
                },
            )
        }
        composable(CabDispatchRoutes.RATE_PASSENGER) {
            RatePassengerScreen(
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
                onRerunSetup = {
                    AppContainer.commissioningStore.clear()
                    navController.navigate(CabDispatchRoutes.DEVICE_READINESS) { popUpTo(0) }
                },
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
        composable(CabDispatchRoutes.TERMS_DISCLAIMER) {
            TermsDisclaimerScreen(
                onAccept = {
                    navController.navigate(postAuthDestination()) {
                        popUpTo(CabDispatchRoutes.TERMS_DISCLAIMER) { inclusive = true }
                    }
                },
            )
        }
        composable(CabDispatchRoutes.DEVICE_READINESS) {
            DeviceReadinessScreen(
                onReady = {
                    navController.navigate(postAuthDestination()) {
                        // popUpTo(0) rather than popping this route alone: the gate must not be
                        // reachable by Back from the login screen behind it, and there is nothing
                        // above it worth keeping either.
                        popUpTo(0)
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
    }
}

/**
 * Shared branch decision between [au.com.threesixty.cabdispatch.ui.screens.splash.SplashScreen]
 * (the normal path) and [CabDispatchRoutes.TERMS_DISCLAIMER]'s onAccept above (the boot-time
 * disclaimer path, 2026-08-10 meter-polish pass, only reached the first time a given app version
 * is ever opened) -- both need to answer the exact same question, "is there already a session, or
 * does this driver need to sign in", so this is factored out once here rather than duplicated in
 * both screens (which would risk them silently drifting apart over time).
 *
 * No change was needed here for the 2026-09-04 session-persistence pass: this already read
 * [au.com.threesixty.cabdispatch.domain.SessionHolder.session] rather than caching its own
 * true/false at some earlier point, so now that
 * [au.com.threesixty.cabdispatch.data.AppContainer.init] restores that session from
 * [au.com.threesixty.cabdispatch.domain.SessionStore] before either caller above ever runs, this
 * function starts correctly resolving to [CabDispatchRoutes.IDLE] on a cold start that has a
 * durable, not-stale session to resume — with zero changes to the branch itself.
 */
fun postAuthDestination(): String {
    val session = au.com.threesixty.cabdispatch.domain.SessionHolder.session.value

    // An OPEN SHIFT is never interrupted. A driver already working -- possibly with a passenger in
    // the car -- must not be locked out of their own meter because a pairing flag changed under
    // them: block the next login, never the current fare.
    //
    // Keyed on `shiftId`, deliberately, not on `session != null`. A restored session keeps the
    // driver and vehicle after its shift has aged out -- SessionStore.restore nulls `shiftId` past
    // the 12-hour limit but returns a perfectly good DriverSession around it -- so "has a session"
    // is true for a driver who merely worked yesterday. Gating on that would have skipped the
    // readiness check for every returning driver on the fleet, which is most of them, and the
    // feature would have looked like it worked while doing almost nothing.
    val midShift = session?.shiftId != null

    // First install. A technician commissions the tablet before any driver ever sees it, and works
    // through the whole checklist -- not just the two things that would block a driver. Until that
    // is finished the readiness screen is the destination regardless of how healthy the tablet
    // looks, because "nothing is broken" is not the same as "somebody set this up and confirmed
    // it". Never while a shift is open: a tablet already earning is past this point by definition.
    if (!midShift && !AppContainer.commissioningStore.isCommissioned()) {
        return CabDispatchRoutes.DEVICE_READINESS
    }

    if (!midShift && DeviceReadiness.blockingFailures(currentReadinessInputs()).isNotEmpty()) {
        // Both blocking checks read state that AppContainer.init has already restored from
        // DevicePairingStore, so this costs no network round-trip and a healthy tablet never
        // flashes the gate on its way past. See DeviceReadiness's own doc.
        return CabDispatchRoutes.DEVICE_READINESS
    }

    return if (session != null) CabDispatchRoutes.IDLE else CabDispatchRoutes.LOGIN_VEHICLE_BIND
}

/**
 * The readiness inputs as they stand right now, for the synchronous gate decision above.
 *
 * The two slow checks ([DeviceReadiness.ReadinessCheck.OfflineMaps] and
 * [DeviceReadiness.ReadinessCheck.SignedTariff]) are reported as `null` -- "not checked" -- rather
 * than guessed at, because probing either means touching disk or the network and neither can block
 * anyone anyway. [DeviceReadinessScreen] fills them in for display once it is on screen.
 *
 * `updateAvailable` is likewise `false` here: this cannot know whether a newer build exists without
 * asking the server, and an unreachable release server must never be the thing that stops a driver
 * working. The gate screen performs the real check and re-evaluates.
 */
private fun currentReadinessInputs(): DeviceReadiness.Inputs {
    val command = AppContainer.deviceCommandHeartbeat.state.value
    return DeviceReadiness.Inputs(
        deviceId = command.deviceId,
        deviceRejected = command.deviceRejected,
        forceUpdatePending = command.forceUpdatePending,
        updateAvailable = false,
        heartbeatSucceeding = command.lastPollSucceeded,
        offlineMapsPresent = null,
        signedTariffCached = null,
    )
}
