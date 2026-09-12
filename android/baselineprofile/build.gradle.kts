// Baseline Profile generator module (W5 optimisation plan, 2026-09-12).
//
// WHAT THIS MODULE IS. A `com.android.test` module -- never shipped, never merged into the app's
// APK -- whose one instrumented test (`BaselineProfileGenerator`) drives `:app` on a REAL device
// or emulator through Macrobenchmark's `BaselineProfileRule`, which records every class/method
// touched during that run and writes them back as `:app/src/main/baseline-prof.txt`. AGP then
// bundles that file into the release APK; `androidx.profileinstaller` (see `app/build.gradle.kts`)
// installs it into ART's AOT compiler on API < 33 devices (API 33+ Play/Cloud Profiles handle it
// automatically), so the interpreter-then-JIT cold-start path this fleet's tablets take today is
// replaced with ahead-of-time-compiled code for exactly the classes cold start/login/first-fare
// actually touch.
//
// WHAT THIS PASS COULD NOT DO, AND WHY. This worktree has no device or emulator (the task's own
// environment notes: "never run adb, install an APK, or touch a physical tablet"). The module
// structure and the generator test below are complete and correct -- `./gradlew
// :baselineprofile:connectedCheck` (or Android Studio's "Generate Baseline Profile" run
// configuration) on a real device/emulator will produce a real `baseline-prof.txt` -- but nobody
// has run that connected task from this worktree, so no such file exists yet, and `:app` is not
// yet consuming one. **This generation step is an OWNER task** (see this repo's HANDOFF.md and
// this pass's PR description) — it needs an actual API 29+ device (the fleet's own SM-T575 is the
// obvious choice, since a profile trained on different hardware or software layout can differ).
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "au.com.threesixty.cabdispatch.baselineprofile"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 29
        // Macrobenchmark/UiAutomator both need API 29+ to run against; matches `:app`'s own
        // minSdk (`app/build.gradle.kts`) so this module never claims to test a device the app
        // itself doesn't support.
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The module under test — this is what makes `com.android.test` build against `:app`'s own
    // compiled classes/resources instead of standing alone, and what
    // `./gradlew :baselineprofile:generateBaselineProfile` reads the applicationId from.
    targetProjectPath = ":app"

    buildTypes {
        // Baseline Profile generation MUST run against a release-shaped build (debug's JIT-only,
        // no-R8 build would profile the wrong bytecode entirely) — the plugin's own convention is
        // a dedicated non-minified "benchmark" build type that mirrors release wherever it can.
        // `:app` has no such build type yet (task 5's own instruction: do not touch
        // isMinifyEnabled/isShrinkResources — that is the OWNER's R8-enablement task), so this
        // stays the plugin's default single `release`-targeting setup rather than inventing one.
    }

    // Required by AGP for a `com.android.test` module even though this module has no application
    // code of its own beyond the generator test.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// See this file's header doc for why `baselineProfile { }`'s own filtering/build-type config
// lives on `:app` (the consumer), not here (the producer) — this module has nothing to configure
// beyond the plugin/target wiring above.

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.espresso:espresso-core:3.7.0")
    // UiAutomator — the only way to drive another app's (or your own app's, cross-process) UI from
    // a `com.android.test` instrumentation; Compose's own testing APIs need to run IN-process
    // against a `ComposeTestRule`, which a macrobenchmark module deliberately doesn't have (the
    // whole point is measuring the real, separate app process, not a test host process).
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
