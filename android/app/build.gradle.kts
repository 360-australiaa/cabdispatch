import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Compose compiler as its own Gradle plugin (A9 toolchain upgrade) -- mandatory as of
    // Kotlin 2.0's K2 compiler; replaces `composeOptions.kotlinCompilerExtensionVersion` below.
    id("org.jetbrains.kotlin.plugin.compose")
    // Room's annotation processor, KSP not kapt (A9 toolchain upgrade). See root build.gradle.kts
    // for why. kapt is gone from this module entirely -- Room was its only user.
    id("com.google.devtools.ksp")
    // Static analysis. Versioned here rather than in the root build file because detekt applies
    // to this module only -- `:app` is the sole Kotlin source set in the project. 1.23.7 was the
    // release built against Kotlin 1.9.x; bumped to the first release with real Kotlin 2.0/K2
    // frontend support alongside the toolchain upgrade above.
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    // Baseline Profile (W5 optimisation plan, 2026-09-12) -- consumes the profile the
    // `:baselineprofile` module's instrumented test generates. See that module's own doc for what
    // it does and what this pass could/couldn't run from this worktree.
    id("androidx.baselineprofile")
}

// Detekt runs on the existing tree with a BASELINE (detekt-baseline.xml), not with the rules
// switched off: every finding that existed at Phase 0 is recorded there and suppressed, so CI
// fails on *new* findings only. That is a deliberate trade -- a fresh 3,000-finding report on a
// 40k-line codebase gets ignored by everyone, and a rule nobody reads is worse than no rule.
// Regenerate with `./gradlew :app:detektBaseline` ONLY when deliberately accepting debt; the
// normal way to clear an entry is to fix the code so it disappears from the report.
detekt {
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
    // Compose codebase: the generated task set would otherwise fan out per variant.
    source.setFrom(files("src/main/java", "src/test/java"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
}

// Mapbox public access token (pk.*), read from local.properties (gitignored, machine-specific —
// see that file's own comment). Exposed as a BuildConfig field below, same pattern as
// API_BASE_URL. This is the RUNTIME token (map styling/tiles) — the separate SECRET
// MAPBOX_DOWNLOADS_TOKEN (sk.*) that unlocks the SDK dependency itself is wired in
// settings.gradle.kts instead (Maven repo credentials, not a BuildConfig field — it must never
// end up in the compiled app, only in the build-time Gradle process). Falls back to an empty
// string (not a crash) if someone's local.properties doesn't have it yet, so a missing token
// degrades to the illustrative grid fallback at runtime rather than failing the build.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val mapboxAccessToken: String = localProperties.getProperty("MAPBOX_ACCESS_TOKEN", "")

// Debug-build API base URL override, same local.properties (gitignored,
// machine-specific) convention as mapboxAccessToken above. Lets each
// developer/tester point their own debug build at whatever backend they
// are actually using -- a real device on the LAN, a live deployed server
// (e.g. the pilot Ubuntu server), or a staging URL -- without editing
// this committed file. Falls back to the emulator-only 10.0.2.2 alias
// (see ApiService.kt header comment) when local.properties does not set
// it, preserving the previous zero-config emulator behavior.
val apiBaseUrlOverride: String = localProperties.getProperty("API_BASE_URL", "http://10.0.2.2:8001")

// Release-variant backend URL. Same local.properties/env pattern as the debug override above,
// but with no usable default: the placeholder is a tripwire, not a fallback. A release APK built
// against `https://api.cabdispatch.example.com` would point the meter at a domain nobody owns, so
// the `afterEvaluate` guard at the bottom of this file refuses to build the release variant while
// this still holds the placeholder value. Resolution order: local.properties, then the
// RELEASE_API_BASE_URL environment variable (for CI/build machines with no local.properties).
val releaseApiBaseUrlPlaceholder = "https://api.cabdispatch.example.com"
val releaseApiBaseUrl: String = localProperties.getProperty("RELEASE_API_BASE_URL")
    ?: System.getenv("RELEASE_API_BASE_URL")
    ?: releaseApiBaseUrlPlaceholder

// -- Release signing (W8 release readiness, 2026-09-12 optimisation plan) --------------------
// The four values that unlock the REAL release keystore. Same local.properties/env pattern as
// mapboxAccessToken/apiBaseUrlOverride/releaseApiBaseUrl above -- but unlike those, there is no
// safe fallback for any of these: an unsigned or wrongly-signed APK is not a degraded build, it is
// one that cannot be installed as an update over a real fleet's existing install, or (worse, if
// some other key were used) one a compromised build pipeline could substitute for the real thing.
// This is deliberately an OWNER-only step: only the owner holds the real keystore file and its two
// passwords, and none of the four is ever meant to exist in this repository, in this worktree, or
// on any machine but the owner's own signing environment / CI secret store. The `afterEvaluate`
// guard at the bottom of this file refuses to build a release artifact while any of the four is
// missing -- see that block for why, and android/README.md's release-build section for exactly
// what the owner sets and where.
val releaseStoreFile: String? = localProperties.getProperty("RELEASE_STORE_FILE")
    ?: System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword: String? = localProperties.getProperty("RELEASE_STORE_PASSWORD")
    ?: System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = localProperties.getProperty("RELEASE_KEY_ALIAS")
    ?: System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = localProperties.getProperty("RELEASE_KEY_PASSWORD")
    ?: System.getenv("RELEASE_KEY_PASSWORD")

// -- Network security: the ALLOW_CLEARTEXT_HOST escape hatch (W8, finding X5) -----------------
// The ONE owner-controlled, TEMPORARY cleartext exception for a real device talking to a real
// backend that is not yet served over TLS -- e.g. the pilot Ubuntu server's bare IP, until
// docs/DEPLOY_UBUNTU.md's "Adding HTTPS later" step actually happens (the 2026-09-12 optimisation
// plan's owner-decisions section deliberately does not guess when that will be -- that date is an
// OWNER decision, not something this pass invents). Read from local.properties, same pattern as
// every other machine-specific value above. Empty by default -- the safe, common case, and the
// only value a release build may ever have (see the `afterEvaluate` guard at the bottom of this
// file, and app/src/main/res/xml/network_security_config.xml's own doc for how this value reaches
// -- and is structurally barred from reaching -- a built APK).
val allowCleartextHost: String = localProperties.getProperty("ALLOW_CLEARTEXT_HOST", "")
    .ifBlank { System.getenv("ALLOW_CLEARTEXT_HOST") ?: "" }

// Generates the debug-only network_security_config.xml override carrying allowCleartextHost (see
// that val's own doc). A plain build-time template write, not a real resource-generation plugin --
// there is no Android/Gradle API to parameterise a network-security-config XML resource directly,
// so this is the standard escape hatch: write the file into `build/generated/...` (never into
// `src/`) and register that directory as an extra resource source dir on the "debug" build type
// only (below, inside the `android { sourceSets }` block).
val generatedDebugNetworkSecurityConfigDir = layout.buildDirectory.dir("generated/networkSecurityConfig/debug/res")
val generateDebugNetworkSecurityConfig = tasks.register("generateDebugNetworkSecurityConfig") {
    // Not `@CacheableTask`/declared-inputs-tracked on purpose -- this is a few milliseconds of
    // string-templating, not worth Gradle's up-to-date ceremony, and a developer who just edited
    // ALLOW_CLEARTEXT_HOST in local.properties must see it take effect on the very next build
    // regardless of what an inputs snapshot thinks changed (local.properties is not a Gradle input
    // file this task declares, so an up-to-date check would otherwise miss that edit entirely).
    outputs.dir(generatedDebugNetworkSecurityConfigDir)
    // Real bug, found live (2026-09-14): the paragraph above is only half the story. Declaring
    // outputs with NO declared inputs does not make Gradle rerun this every time -- the opposite:
    // once `generatedDebugNetworkSecurityConfigDir` exists from a prior run, Gradle's up-to-date
    // check has nothing that changed to compare against (no inputs registered) and the output
    // directory still matches its last recorded snapshot, so it marks this task UP-TO-DATE and
    // skips `doLast` entirely on every later build -- caught in the act: local.properties was
    // edited from a local backend URL to the production one plus a fresh ALLOW_CLEARTEXT_HOST,
    // `assembleDebug` reported success, and the installed APK still shipped the stale
    // (cleartext-less) config from the previous run, throwing "CLEARTEXT communication ... not
    // permitted" against the very host this edit was meant to allow. `upToDateWhen { false }` is
    // the direct fix for exactly this shape: it tells Gradle this task is never eligible for the
    // up-to-date optimisation, so `doLast` below runs on every build regardless, which is what the
    // paragraph above always intended.
    outputs.upToDateWhen { false }
    doLast {
        val xmlDir = generatedDebugNetworkSecurityConfigDir.get().asFile.resolve("xml")
        xmlDir.mkdirs()
        // Indentation below must match the template's own "<domain includeSubdomains=..." lines
        // exactly (20 spaces) -- trimIndent() computes ITS common margin from the raw literal
        // source below, including this interpolated value, so a mismatched indent here throws off
        // trimIndent()'s calculation for every other line in the file, not just this one.
        val extraDomainLine = if (allowCleartextHost.isNotBlank()) {
            "\n                    <domain includeSubdomains=\"false\">$allowCleartextHost</domain>"
        } else {
            ""
        }
        xmlDir.resolve("network_security_config.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!--
              GENERATED at build time by :app's generateDebugNetworkSecurityConfig Gradle task,
              do not edit by hand, do not commit (it lives under build/, already gitignored).
              Overrides app/src/main/res/xml/network_security_config.xml for DEBUG builds ONLY,
              adding the one ALLOW_CLEARTEXT_HOST from local.properties when the owner has set one
              (see app/build.gradle.kts's allowCleartextHost val). Empty when unset, in which
              case this file is byte identical to the baseline three-host config.
            -->
            <network-security-config>
                <domain-config cleartextTrafficPermitted="true">
                    <domain includeSubdomains="false">10.0.2.2</domain>
                    <domain includeSubdomains="false">localhost</domain>
                    <domain includeSubdomains="false">127.0.0.1</domain>$extraDomainLine
                </domain-config>
            </network-security-config>
            """.trimIndent(),
        )
    }
}
// `preBuild` runs before every other task in this module for every variant -- wiring the generator
// there (rather than guessing which of AGP's several resource-related tasks read this source
// directory, several of which turned out to, not only the resource-merge task) guarantees the
// override file exists before anything looks for it, at the cost of the generator also running
// (harmlessly -- it is a debug-only directory a release build never reads) ahead of a release
// build too.
tasks.named("preBuild").configure { dependsOn(generateDebugNetworkSecurityConfig) }

// Room schema export destination (A9 toolchain upgrade -- see AppDatabase.kt's `exportSchema`
// doc for why this was off before and what turning it on now does and does not cover). KSP's
// `room.schemaLocation` arg, not the old kapt `javaCompileOptions.annotationProcessorOptions`
// path. Checked in under androidTest so RoomMigrationTest.kt's future bumps (13, 14, ...) can
// use `MigrationTestHelper`'s asset-backed `createDatabase()` the normal way -- versions 8-11
// still can't, since no JSON was ever captured for them; that test builds their starting schema
// by hand instead and says so.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "au.com.threesixty.cabdispatch"
    // compileSdk 36 (W0 toolchain refresh, 2026-09-12): raised only as far as the bumped
    // dependencies actually require and AGP 8.13.2 (this project's chosen AGP -- see root
    // build.gradle.kts) actually supports -- this only widens which platform APIs the compiler
    // can SEE, it changes nothing about how the app behaves at runtime on any device. targetSdk
    // deliberately stays at 35 below: that is the separate, higher-risk decision that opts the
    // app into whatever new platform behaviour changes API 36 brings, and doing that without a
    // real device regression pass is exactly the kind of change that has bitten this project
    // before (see F3/F4's own history). Revisit targetSdk as its own tested pass.
    compileSdk = 36

    defaultConfig {
        applicationId = "au.com.threesixty.cabdispatch"
        minSdk = 29
        targetSdk = 35
        // 11 / 0.6.2 (2026-09-08): the app-level banners no longer land across the headline of the
        // three header-less onboarding screens (readiness gate, permissions, disclaimer). Seen on a
        // clean install: "TABLET NOT REGISTERED" printed straight through the word "Permissions".
        // Only the gate suppressed them before.
        //
        // 10 / 0.6.1 (2026-09-08): the tablet recovers its vehicle binding instead of publishing
        // to a uuid that no longer exists. A fleet wipe gives every car a new uuid under the same
        // rego, and a session bound to the old one 404'd every 5s for the rest of the shift --
        // silently, so the driver saw a working meter while the dispatcher saw a car that never
        // appeared on the Live Map. A bind made with no signal had the same effect. This build
        // should reach every tablet whose depot has ever re-seeded its fleet.
        //
        // 9 / 0.6.0 (2026-09-08): the meter dial animates on the TARIFF's own bands -- waiting time
        // under 26 km/h, distance above it, energetic past 60 -- and the speed-reactive ember it
        // already had is fixed (it captured speed once at composition, so it had been static).
        //
        // 8 / 0.5.2 (2026-09-08): the commissioning checklist now covers what a technician has to
        // configure -- every runtime permission (including the two that broke its own Scan QR and
        // Install update buttons), battery-optimisation exemption, kiosk pinning, map token,
        // tariff signing key, and the maxi declaration that sets the rate charged.
        //
        // 7 / 0.5.1 (2026-09-08): technician commissioning checklist on first install; remote
        // locate answered on the device route (it used to publish a vehicle position, which 404'd
        // on a tablet whose vehicle had been deleted -- the "Location request failed to send"
        // report); remote restart of the meter app actually implemented and acknowledged, so
        // Locate and Restart stop reading "Pending" forever. Needs the matching backend.
        //
        // 6 / 0.5.0 (2026-09-08): a tablet must be REGISTERED with the depot before anyone can log
        // into the meter -- see domain/DeviceReadiness.kt. Requires the matching backend
        // (device_secret + code-authenticated POST /v1/fleet/devices/register): against an older
        // server the readiness gate still appears but cannot be cleared, so DEPLOY THE BACKEND
        // FIRST and only then push this build to tablets.
        //
        // 5 / 0.4.1 (2026-09-08): fare-time and toll-time classification pinned to NSW local time
        // (Australia/Sydney) instead of the tablet's own zone -- see domain.fare.NSW_FARE_ZONE.
        // A meter on the wrong timezone billed the night rate at the wrong hours and every such
        // trip auto-flagged on sync against the server's recomputation, so this build should
        // reach every tablet in the fleet.
        //
        // 12 / 0.7.0 (2026-09-13, W8 release readiness -- last workstream of the 2026-09-12
        // optimisation plan): release-build MACHINERY, not a fare/UI change -- a real
        // signingConfigs.release wired from local.properties (never committed, owner-only) with a
        // Gradle-time refusal to build without all four keys; a rebuilt network_security_config.xml
        // that no longer hardcodes the production IP unconditionally (finding X5) and instead
        // permits cleartext only to the emulator/localhost plus one owner-controlled,
        // release-blocked ALLOW_CLEARTEXT_HOST escape hatch; a root/bootloader-unlock integrity
        // check (advisory in debug, blocking in release) on the readiness gate; and FLAG_SECURE on
        // Close & Pay, Profile and the duress-arming overlay. See android/HANDOFF.md's changelog
        // entry for this version for the full W0-W7 summary and exactly what remains an OWNER gate.
        //
        // 4 / 0.4.0 (2026-09-07): automatic NSW toll detection with the corrected per-toll-point
        // registry, the card-surcharge absorption ruling, and the GPS simulator. versionCode is
        // what AppUpdateChecker compares against a published release, so it MUST increase for a
        // build to reach a tablet over the air -- a build shipped at the same code is silently
        // skipped as "already up to date".
        versionCode = 12
        versionName = "0.7.0"

        // See apiBaseUrlOverride above -- set API_BASE_URL in your own
        // local.properties to point a debug build at a real device on
        // the LAN, a live deployed backend, or a staging URL. Unset =
        // the 10.0.2.2 emulator-only alias, same as before.
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrlOverride\"")
        // Runtime map token — set programmatically at startup via MapboxOptions.accessToken
        // (CabDispatchApp.kt), which is what the actual Maps SDK v11 API expects (not a manifest
        // meta-data entry, that was the older v9/v10 pattern).
        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxAccessToken\"")

        // W2 (inertial dead-reckoning through a GPS blackout, 2026-09-12, owner gate G3): billing
        // against the tablet's own gyroscope/accelerometer estimate stays OFF until the owner has
        // reviewed >= 3 real drives' shadow-mode residual evidence (see
        // `domain/location/inertial/InertialSpeedEstimator.kt`'s doc and the plan's W2 task 9/
        // acceptance criteria) and flips this in a one-line PR citing that evidence. Shadow mode
        // itself (the estimator running and logging residuals, never billing) stays ON by default
        // so that evidence exists to review in the first place -- it costs nothing extra the
        // estimator was not already going to spend once a hiring is open.
        buildConfigField("boolean", "INERTIAL_BILLING_ENABLED", "false")
        buildConfigField("boolean", "INERTIAL_SHADOW_ENABLED", "true")
    }

    // Android lint. Same baseline strategy as detekt above: `lint-baseline.xml` records the
    // findings that already existed when CI was introduced (2 errors, 42 warnings) so the gate
    // fails on NEW findings only. The two baselined errors are real and should be fixed, but
    // both live in files owned by other Phase 0 / Wave 1 workstreams and are listed for them:
    //   - AndroidManifest.xml: ACCESS_FINE_LOCATION requested without ACCESS_COARSE_LOCATION
    //     [CoarseFineLocation] -- belongs to the fare/location workstream (A1), which is already
    //     editing the manifest for the meter foreground service.
    //   - ui/theme/Theme.kt: a `remember` call returning Unit [RememberReturnType] -- belongs to
    //     the Android UI cleanup workstream, which owns Theme.kt.
    // Delete the corresponding entries from the baseline as those land; do not regenerate the
    // whole file to paper over something new.
    lint {
        // No baseline file (W0, 2026-09-12): every issue lint finds is either fixed or suppressed
        // in place with a comment explaining why, so there is nothing left to grandfather in. A
        // baseline is how a real regression hides forever next to 48 pre-existing ones; zero is
        // the only count that stays honest.
        //
        // Aligned16KB is the one issue disabled here rather than in app/lint.xml's path-scoped
        // ignores: it fires on an absolute path inside the Gradle dependency cache
        // (com.mapbox.common:common's own bundled libandroid-tests-support-code.so, not a file
        // this project owns or can edit), and that cache path is machine-specific -- a path-scoped
        // ignore that works on this box would silently stop matching on CI's own cache location.
        // The 16 KB native-library-alignment requirement is real (Android 15+ devices with a 16 KB
        // page size), but fixing it means a Mapbox SDK version bump verified on a real device, not
        // a lint config change made in this pass.
        disable += "Aligned16KB"
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
    }

    // Release signing (W8 release readiness). storeFile is only set when a path was actually
    // supplied -- `file(null)` throws at configuration time, and leaving it unset here is exactly
    // what lets the `afterEvaluate` guard below be the one place that reports a missing signing
    // property, with one clear message naming which one, rather than a bare Gradle/AGP stack trace
    // for whichever configuration-time null happens to be dereferenced first.
    signingConfigs {
        create("release") {
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = file(releaseStoreFile)
            }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The deployed backend URL for release builds. This is deliberately NOT committed:
            // set RELEASE_API_BASE_URL in local.properties (gitignored) or in the environment on
            // the build machine. While it is unset this stays at the placeholder below and the
            // release build is FAILED by the guard in `afterEvaluate` at the bottom of this file
            // -- see that block for why a placeholder must never be allowed to ship.
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // kotlinOptions{} moved out to the top-level `kotlin { compilerOptions { } }` extension below
    // (W0 toolchain refresh, 2026-09-12): the Kotlin 2.3.x Gradle plugin made the old
    // `android.kotlinOptions` DSL a hard compile error, not just a warning -- see that block for
    // the jvmTarget/allWarningsAsErrors settings themselves and their own rationale.

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // `composeOptions.kotlinCompilerExtensionVersion` is gone (A9 toolchain upgrade) -- the
    // `org.jetbrains.kotlin.plugin.compose` plugin applied above wires the Compose compiler to
    // whatever Kotlin version this module builds with (2.3.21, W0 toolchain refresh) and does not
    // take a version of its own; setting this field with that plugin applied is a Gradle build
    // error, not a no-op.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Robolectric (RoomMigrationTest) needs the merged Android assets/resources on the unit-test
    // classpath -- off by default because it roughly doubles unit-test task time; this is the
    // one test that needs it.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // ALLOW_CLEARTEXT_HOST (see above): the debug build type's OWN resource source set. AGP
    // resolves a resource of the same name/type in a build-type-specific source set as a full
    // replacement of main's version for THAT build type only -- so a release build can never pick
    // up the generated file below no matter what, structurally, independent of and in addition to
    // the Gradle-time `afterEvaluate` refusal-to-build guard at the bottom of this file. Debug
    // builds get this override; release always resolves the static, three-host-only
    // app/src/main/res/xml/network_security_config.xml.
    sourceSets.getByName("debug") {
        res.srcDir(generatedDebugNetworkSecurityConfigDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // W0 (2026-09-12): the measured baseline had zero compiler warnings across every source
        // set that was checked, and the one known warning class (K2's "Condition is always true"
        // on FareEngineImpl.tick's deliberately-redundant null guards) is already suppressed at
        // its origin with @Suppress("SENSELESS_COMPARISON") and a comment, not left to this flag.
        // A new warning failing the build here is meant to feel that abrupt -- it is either a real
        // defect or needs the same origin-level, justified suppression, never a blanket opt-out.
        allWarningsAsErrors.set(true)
    }
}

// Compose compiler configuration (W5, 2026-09-12 optimisation plan) -- the
// `org.jetbrains.kotlin.plugin.compose` plugin applied above exposes this `composeCompiler { }`
// extension for the same compiler that used to be configured through raw
// `-P plugin:androidx.compose.compiler.plugins.kotlin:*` freeCompilerArgs; the extension is the
// same flags, typed, and is what a Kotlin-2.x Compose module is expected to use now.
composeCompiler {
    // See app/compose_compiler_config.conf's own header doc for exactly which classes are listed
    // and why (BigDecimal/java.time have no Kotlin stability metadata at all; a handful of this
    // app's own domain models are read-only-List/BigDecimal-only and verified never mutated in
    // place). Without this, every composable taking a FareState parameter -- including MeterDial
    // and HiredScreen, the two composables that recompose once per fare tick for an entire hiring
    // -- is reported unstable, which silently disables the compiler's skip-if-unchanged check for
    // every OTHER parameter on those composables too, not only the one that legitimately changes
    // every second.
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_compiler_config.conf"))

    // Metrics/reports (recomposition-stability .txt/.json/.csv per module) are real I/O on every
    // single compile, so they are OFF unless explicitly asked for -- gated on a Gradle property
    // rather than always-on, per this task's own instruction not to slow down every build.
    // Usage: `./gradlew :app:compileDebugKotlin -PcomposeMetrics=true --offline`, then read
    // `app/build/compose_metrics/*-module.json` (per-composable stability) and
    // `app/build/compose_reports/*-composables.csv` (skippable/restartable per composable) --
    // MeterDial/HiredScreen/DeckHomeScreen are the ones this plan's acceptance criteria care about.
    if (project.hasProperty("composeMetrics")) {
        metricsDestination.set(layout.buildDirectory.dir("compose_metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose_reports"))
    }
}

// Baseline Profile plugin configuration (W5 optimisation plan, 2026-09-12) -- see
// `:baselineprofile`'s own doc for what generates the profile this consumes and why that
// generation step is an OWNER task, not something this pass ran. `automaticGenerationDuringBuild`
// stays `false` (the default): true would try to run the connected `:baselineprofile` test on
// every release build, which needs a real attached device/emulator this CI/build box does not
// have -- the profile is generated deliberately, on demand, by the owner, not on every build.
baselineProfile {
    // `false` (default) — see the comment above; this project's release build never blocks on a
    // connected device that may not exist.
    automaticGenerationDuringBuild = false
}

dependencies {
    // The `:baselineprofile` module's generated output -- this is what actually wires a real
    // `baseline-prof.txt`, once one exists, into `:app`'s release build; see that module's build
    // file for why none exists yet from this worktree.
    baselineProfile(project(":baselineprofile"))

    // -- Compose (BOM pins all Compose artifact versions together) --
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Explicit (rather than relying on the material3 transitive dep) because
    // S3 (Hired)'s hidden duress gesture uses foundation's pointerInput/
    // detectTapGestures directly — see ui/screens/hired/HiredScreen.kt.
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Extended icon set (Icons.Rounded.*, Icons.AutoMirrored.Rounded.*, etc.) — most icons used
    // across the app's screens (DeckHomeScreen and this pass's CaptainPalette reskins alike) live
    // here, not in the small "core" set material3 ships by default.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // -- Navigation --
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // -- Lifecycle --
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    // ProcessLifecycleOwner — used by domain/duress/DuressCameraCapture.kt to bind CameraX's
    // ImageCapture use case to the app-process lifecycle (this app is always single-activity/
    // foreground-kiosk, so "process lifecycle" and "the driver can see the screen" coincide;
    // there is no separate Activity/Fragment lifecycle worth binding to instead here).
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    // W0/W5 (2026-09-12): `androidx.compose.ui.platform.LocalLifecycleOwner` is deprecated
    // in favour of this artifact's `androidx.lifecycle.compose.LocalLifecycleOwner`;
    // `collectAsStateWithLifecycle` (W5) lives here too.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    // Explicit for `viewModelScope` (used throughout ui/screens/*/ ViewModels).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    // -- Coroutines: explicit runtime dep (transitive-only isn't enough — the
    // Android `Dispatchers.Main` implementation used by `viewModelScope` is
    // provided by this artifact's ServiceLoader registration, not by
    // kotlinx-coroutines-core alone). Version matched to the
    // kotlinx-coroutines-test version below.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // -- Room (local offline store; entities/DAOs added by the sync-domain
    // sibling agent — this module just wires the dependency) --
    // A9 toolchain upgrade: kapt -> KSP for the annotation processor (root build.gradle.kts has
    // the rationale). Practical payoff, not just "the modern option": with KSP, `room.schemaLocation`
    // (below) actually produces schema JSON on this project -- the old kapt setup produced nothing
    // (see the now-obsolete workaround in AppDatabase.kt's MIGRATION_9_10 doc, which had engineers
    // reading Room's generated Java instead). exportSchema is turned on for the same reason -- see
    // androidTest/.../RoomMigrationTest.kt for what that does and does not retroactively give us.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    // MigrationTestHelper (RoomMigrationTest.kt). Runs under Robolectric in testDebugUnitTest,
    // not as a connectedAndroidTest -- no device/emulator involved, see that test's own doc.
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")

    // -- WorkManager (background sync) --
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // -- Networking: Retrofit + OkHttp + kotlinx.serialization converter.
    // kotlinx.serialization chosen over Moshi/Gson for consistency with the
    // backend's JSON contract tooling (see shared/openapi.json generation). --
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // -- Location (fare engine GPS fusion, sibling agent) --
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // -- CameraX (duress cabin-camera still-frame capture, blueprint 4.3/8.3's camera-during-
    // active-duress-only feature — see domain/duress/DuressCameraCapture.kt). camera-core +
    // camera-camera2 (the real Camera2-backed implementation) + camera-lifecycle (binds the
    // ImageCapture use case to a LifecycleOwner) — no camera-view, this never shows a
    // PreviewView/viewfinder to the driver, it's a silent background capture only. --
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")

    // -- QR vehicle pairing (2026-08-28, real implementation replacing the StubQrScanner —
    // domain/QrScanner.kt) — the ML Kit "Google code scanner" module (Play Services on-device
    // model, not the full bundled ML Kit SDK): a ready-made full-screen scan UI + camera
    // permission handling launched via GmsBarcodeScanning.getClient(activity).startScan(), no
    // custom CameraX PreviewView/analyzer needed. Public Google Maven artifact, no secret token
    // (unlike Mapbox's downloads repo) — resolves from the already-declared google() repo. --
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // -- Image loading — Coil, used by MapboxStaticImage.kt's fallback path (kept as the
    // loading/error-state and no-secret-token fallback, see WheelDashboardScreen.kt's
    // MapBackground) and by any other async-image needs elsewhere in the app. --
    implementation("io.coil-kt:coil-compose:2.7.0")

    // -- Mapbox Maps SDK (real interactive map + genuine offline region download, added
    // 2026-08-02 once a secret MAPBOX_DOWNLOADS_TOKEN became available — see
    // settings.gradle.kts's Maven-credentials block for why this specific dependency needs that
    // separate secret token to resolve at all, and HANDOFF.md's offline-maps section for the
    // full writeup). PIN NOTE: 11.8.1 was the most recent version this was written against
    // Mapbox's documented v11 API surface for — check Mapbox's actual release notes and bump if
    // meaningfully newer by the time this is first compiled; the offline-region API in
    // particular (TileStore/OfflineManager) has had real signature changes across v11 minor
    // versions historically, so this dependency (more than anything else in this project) may
    // need small adjustments once someone can actually build against it. --
    implementation("com.mapbox.maps:android:11.8.1")

    // -- BouncyCastle (pure-JVM crypto provider) — needed only for Ed25519 signature
    // verification (security/TariffSignatureVerifier.kt's Ed25519TariffSignatureVerifier,
    // verifying GET /v1/tariffs/active's signature). `java.security`'s own built-in Ed25519
    // support (`NamedParameterSpec.ED25519`) only landed on API 33+, and this project's minSdk
    // is 29 (see app/build.gradle.kts's own `minSdk` above) — bcprov gives KeyFactory/Signature
    // "Ed25519" support on every API level this app targets instead of needing a minSdk bump.
    // jdk18on (not the older jdk15on) is the currently-maintained artifact line. --
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    // -- Encrypted credential storage (security finding X3) ---------------------------------------
    // Backs TokenStore / DevicePairingStore / the offline PIN cache with an Android Keystore
    // AES key instead of plaintext SharedPreferences XML — see domain/SecurePrefs.kt for what was
    // exposed and how the one-time migration off the plain files works. 1.1.0-alpha06 rather than
    // 1.0.0 because 1.0.0's MasterKeys API is deprecated and its Tink dependency misbehaves on
    // API 31+; alpha06 is the build every current androidx sample uses and is stable in practice.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    // JVM unit tests for the offline sync engine (OutboxDrainerTest) — pure
    // Kotlin/coroutines, no Android framework classes, so these run without
    // the SDK/emulator this sandbox doesn't have. Version matched to the
    // kotlinx-coroutines-android version above, not bumped independently.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    // -- Startup/jank (W5 optimisation plan, 2026-09-12) --
    // Installs whatever Baseline Profile is bundled in the APK (the `:baselineprofile` module's
    // generated `baseline-prof.txt`, merged into `assets/dexopt/` by AGP) on first launch on API
    // < 33 devices — API 33+ handles this automatically via Cloud Profiles/Play install-time
    // profiles, but this fleet's tablets (SM-T575, API 29 minSdk) do not, so the library call is
    // still required for them to get the AOT-compiled fast path at all. A no-op if no profile was
    // bundled, so this is safe to add ahead of the profile actually being generated (see the
    // `:baselineprofile` module's own doc for why that generation step is an OWNER task).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // LeakCanary, debug builds only (never shipped — `debugImplementation`, not `implementation`,
    // so a release APK never links it and never pays its overhead or shows its notification).
    // Auto-installs itself via a manifest-merged ContentProvider — no `Application.onCreate()` call
    // needed, unlike StrictMode above in CabDispatchApp.kt. 2.14 is the current stable release as
    // of this pass.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}

// -- Release hardening tripwire (Phase 0, security addendum) --------------------------------
// A release APK is the artifact that reaches drivers' tablets, and the two things below are
// exactly the mistakes that produced the 0.6.2 incident: an APK built with a URL that was never
// meant to ship, distributed to a real fleet. Gradle cannot see "you meant to set this" -- so the
// placeholder is treated as an error rather than a default, and the build stops before any
// release artifact exists.
//
// Why `afterEvaluate` + `doFirst` and not a top-level `if`: a top-level check runs during
// *configuration*, i.e. on every single Gradle invocation including `:app:testDebugUnitTest` and
// `:app:lintDebug` in CI, which would break every debug build on a machine that has no reason to
// set a release URL. Attaching the check to the release tasks themselves means it fires only when
// someone actually asks for a release artifact.
//
// To build a real release: set RELEASE_API_BASE_URL in local.properties (gitignored) or as an
// environment variable on the build machine. It must be https -- see
// docs/audits/2026-09-08-backend-audit.md section 5, which found the shipped APK talking
// plaintext HTTP to production, carrying JWTs, device secrets and GPS in the clear.
afterEvaluate {
    val releaseArtifactTasks = tasks.matching { task ->
        val name = task.name
        (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package")) &&
            name.contains("Release")
    }
    releaseArtifactTasks.configureEach {
        doFirst {
            if (releaseApiBaseUrl == releaseApiBaseUrlPlaceholder) {
                throw GradleException(
                    "Refusing to build a release artifact: API_BASE_URL is still the placeholder " +
                        "'$releaseApiBaseUrlPlaceholder'. Set RELEASE_API_BASE_URL in " +
                        "local.properties or in the environment to the real deployed backend " +
                        "URL. See the release-hardening notes in app/build.gradle.kts.",
                )
            }
            if (!releaseApiBaseUrl.startsWith("https://")) {
                throw GradleException(
                    "Refusing to build a release artifact: RELEASE_API_BASE_URL must be https. " +
                        "A release build must not ship plaintext HTTP -- tokens, the device " +
                        "secret, duress audio and GPS all travel over it.",
                )
            }
        }
    }
}

// -- Release signing tripwire (W8 release readiness, 2026-09-12 optimisation plan) -----------
// Same shape as the API-URL guard above, same reason: an `afterEvaluate` + `doFirst` on the
// release artifact tasks specifically, so this never fires on `:app:testDebugUnitTest`/
// `:app:lintDebug`/`:app:assembleDebug` -- a machine with no reason to hold the real release
// keystore must still be able to run every ordinary build and CI check.
//
// This is a PRESENCE check only (are the four properties set at all), not a validity check (a
// wrong password or a corrupt keystore file still fails, just later, inside AGP's own real signing
// step, with AGP's own error) -- see app/build.gradle.kts's `releaseStoreFile`/etc. vals above for
// why there is deliberately no safe fallback for any of the four to check against instead.
//
// To build a real release: set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and
// RELEASE_KEY_PASSWORD in local.properties (gitignored) or as environment variables on the build
// machine, pointing at the real release keystore only the owner holds. See android/README.md's
// release-build section.
afterEvaluate {
    val releaseSigningTasks = tasks.matching { task ->
        val name = task.name
        (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package")) &&
            name.contains("Release")
    }
    val releaseSigningProperties = linkedMapOf(
        "RELEASE_STORE_FILE" to releaseStoreFile,
        "RELEASE_STORE_PASSWORD" to releaseStorePassword,
        "RELEASE_KEY_ALIAS" to releaseKeyAlias,
        "RELEASE_KEY_PASSWORD" to releaseKeyPassword,
    )
    releaseSigningTasks.configureEach {
        doFirst {
            val missing = releaseSigningProperties.filterValues { it.isNullOrBlank() }.keys
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Refusing to build a release artifact: missing release signing " +
                        (if (missing.size == 1) "property" else "properties") + " " +
                        missing.joinToString(", ") +
                        ". Set all four of RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
                        "RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD in local.properties " +
                        "(gitignored) or in the environment, pointing at the real release " +
                        "keystore -- this is an OWNER-only step, nobody else holds it. See " +
                        "android/README.md's release-build section and the signing-config " +
                        "notes in app/build.gradle.kts.",
                )
            }
        }
    }
}

// -- Cleartext-escape-hatch tripwire (W8 release readiness, finding X5) -----------------------
// ALLOW_CLEARTEXT_HOST is a debug-only convenience (see app/src/main/res/xml/
// network_security_config.xml and the `allowCleartextHost` val above) that already cannot reach a
// release artifact structurally, because the generated override resource is registered on the
// "debug" build type's own source set, never "release"/"main". This guard is the second, explicit
// reason: a release build must FAIL, loudly, if the owner forgot to blank the property out of
// local.properties before cutting a release, rather than silently building a release that (thanks
// to the structural guard) merely ignores the value -- "the flag had no effect" is not the same
// promise as "the build refused to proceed while the flag was still set", and only the latter
// catches the mistake at the moment it is made instead of trusting the structural guard to have
// been implemented correctly forever.
afterEvaluate {
    val releaseCleartextTasks = tasks.matching { task ->
        val name = task.name
        (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package")) &&
            name.contains("Release")
    }
    releaseCleartextTasks.configureEach {
        doFirst {
            if (allowCleartextHost.isNotBlank()) {
                throw GradleException(
                    "Refusing to build a release artifact: ALLOW_CLEARTEXT_HOST is set to " +
                        "'$allowCleartextHost' in local.properties (or the environment). This " +
                        "debug-only cleartext escape hatch must never reach a release build -- " +
                        "blank it out (or unset the environment variable) before building a " +
                        "release artifact. See app/src/main/res/xml/network_security_config.xml " +
                        "and the notes above app/build.gradle.kts's `allowCleartextHost` val.",
                )
            }
        }
    }
}
