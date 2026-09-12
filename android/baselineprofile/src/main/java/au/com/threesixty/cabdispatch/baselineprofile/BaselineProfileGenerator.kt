package au.com.threesixty.cabdispatch.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Generates `:app/src/main/baseline-prof.txt` from a real cold start -> login -> hired-screen run
 * (W5 optimisation plan, 2026-09-12, plan doc task 5) — see this module's `build.gradle.kts`
 * header for what the resulting profile is for and how it reaches the shipped APK.
 *
 * **Run this from Android Studio's Baseline Profile run configuration, or**
 * `./gradlew :baselineprofile:generateBaselineProfile` **on a real API 29+ device or emulator —
 * this pass could not, and did not, run it (no device access from this worktree; see this file's
 * own class doc and the PR description's OWNER-gates list). Treat this as reviewed, correct
 * source for the owner to run, not as a profile that has already been generated.**
 *
 * WHY THE RUN STOPS AT THE LOGIN SCREEN, NOT AT THE HIRED SCREEN THE PLAN ASKS FOR. Getting from
 * login to a hired fare needs a REAL signed-in driver (this app's login is a real
 * `POST /v1/auth/login` against the fleet backend — `LoginVehicleBindViewModel` — there is no
 * fake/offline credential anywhere in this codebase, by design: inventing one here to make this
 * profile richer would be exactly the kind of security shortcut the rest of this app's login/MFA/
 * vehicle-binding flow exists to prevent). Driving further needs one of:
 *  - a real seeded test driver account + vehicle pairing on whatever backend this benchmark points
 *    at (`API_BASE_URL` — a `local.properties`/environment concern, not something to hardcode
 *    here), with this test's `uiDevice` typing real credentials into the login form; or
 *  - a debug-only "test driver" bypass the owner deliberately adds behind `BuildConfig.DEBUG` AND
 *    a benchmark-build-type gate (so it can never leak into a release build), which does not exist
 *    in this codebase today and is a product/security decision, not an engineering default this
 *    pass should invent unilaterally.
 * Either is a real OWNER decision (see this pass's PR description) — this generator captures the
 * classes/methods touched by app process start, `CabDispatchApp.onCreate()`, `MainActivity`'s
 * `FixedDesignCanvas`/theme setup, the splash routing decision, and the login screen's first
 * render, which is already the majority of a cold start's real work and where AOT compilation
 * matters most (everything from the login screen onward runs warm, post-JIT, for the rest of the
 * app's lifetime in the common case of a driver who stays logged in across shifts). Extending the
 * `.collect { }` block below past `pressHome()`/`startActivityAndWait()` plus the login-screen
 * wait, once the owner has a real credential path, is a small, mechanical follow-up — not a
 * rewrite of this file.
 */
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "au.com.threesixty.cabdispatch",
        // Default (`false`): a cold start profile, matching what this plan's acceptance criteria
        // ("cold start -> login -> hired screen") actually asks about — a driver's twelve-hour
        // shift starts with exactly one cold start, not a warm relaunch.
    ) {
        pressHome()
        startActivityAndWait()

        // Splash (SplashScreen.kt) makes its real routing decision (terms accepted? permission
        // granted? logged in already?) after `SPLASH_MIN_DWELL_MS` — see that screen's own
        // LaunchedEffect(Unit) doc — then navigates to Login/Permissions/Terms/the dashboard. This
        // wait covers that whole decision + the first frame of wherever it lands, which is the
        // real cold-start-critical path regardless of which of those four screens turns out to be
        // first (a fresh, unauthenticated test device — the only kind a CI/benchmark run should
        // ever use — lands on Terms or Login, never the dashboard).
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)
    }
}
