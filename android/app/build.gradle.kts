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
    compileSdk = 35

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
        // 4 / 0.4.0 (2026-09-07): automatic NSW toll detection with the corrected per-toll-point
        // registry, the card-surcharge absorption ruling, and the GPS simulator. versionCode is
        // what AppUpdateChecker compares against a published release, so it MUST increase for a
        // build to reach a tablet over the air -- a build shipped at the same code is silently
        // skipped as "already up to date".
        versionCode = 11
        versionName = "0.6.2"

        // See apiBaseUrlOverride above -- set API_BASE_URL in your own
        // local.properties to point a debug build at a real device on
        // the LAN, a live deployed backend, or a staging URL. Unset =
        // the 10.0.2.2 emulator-only alias, same as before.
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrlOverride\"")
        // Runtime map token — set programmatically at startup via MapboxOptions.accessToken
        // (CabDispatchApp.kt), which is what the actual Maps SDK v11 API expects (not a manifest
        // meta-data entry, that was the older v9/v10 pattern).
        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxAccessToken\"")
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
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // `composeOptions.kotlinCompilerExtensionVersion` is gone (A9 toolchain upgrade) -- the
    // `org.jetbrains.kotlin.plugin.compose` plugin applied above wires the Compose compiler to
    // whatever Kotlin version this module builds with (2.0.21) and does not take a version of
    // its own; setting this field with that plugin applied is a Gradle build error, not a no-op.

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
}

dependencies {
    // -- Compose (BOM pins all Compose artifact versions together) --
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
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
    implementation("androidx.activity:activity-compose:1.9.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // -- Navigation --
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // -- Lifecycle --
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // ProcessLifecycleOwner — used by domain/duress/DuressCameraCapture.kt to bind CameraX's
    // ImageCapture use case to the app-process lifecycle (this app is always single-activity/
    // foreground-kiosk, so "process lifecycle" and "the driver can see the screen" coincide;
    // there is no separate Activity/Fragment lifecycle worth binding to instead here).
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    // Explicit for `viewModelScope` (used throughout ui/screens/*/ ViewModels).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // -- Coroutines: explicit runtime dep (transitive-only isn't enough — the
    // Android `Dispatchers.Main` implementation used by `viewModelScope` is
    // provided by this artifact's ServiceLoader registration, not by
    // kotlinx-coroutines-core alone). Version matched to the
    // kotlinx-coroutines-test version below.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // -- Room (local offline store; entities/DAOs added by the sync-domain
    // sibling agent — this module just wires the dependency) --
    // A9 toolchain upgrade: kapt -> KSP for the annotation processor (root build.gradle.kts has
    // the rationale). Practical payoff, not just "the modern option": with KSP, `room.schemaLocation`
    // (below) actually produces schema JSON on this project -- the old kapt setup produced nothing
    // (see the now-obsolete workaround in AppDatabase.kt's MIGRATION_9_10 doc, which had engineers
    // reading Room's generated Java instead). exportSchema is turned on for the same reason -- see
    // androidTest/.../RoomMigrationTest.kt for what that does and does not retroactively give us.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // MigrationTestHelper (RoomMigrationTest.kt). Runs under Robolectric in testDebugUnitTest,
    // not as a connectedAndroidTest -- no device/emulator involved, see that test's own doc.
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    // -- WorkManager (background sync) --
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // -- Networking: Retrofit + OkHttp + kotlinx.serialization converter.
    // kotlinx.serialization chosen over Moshi/Gson for consistency with the
    // backend's JSON contract tooling (see shared/openapi.json generation). --
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // -- Location (fare engine GPS fusion, sibling agent) --
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // -- CameraX (duress cabin-camera still-frame capture, blueprint 4.3/8.3's camera-during-
    // active-duress-only feature — see domain/duress/DuressCameraCapture.kt). camera-core +
    // camera-camera2 (the real Camera2-backed implementation) + camera-lifecycle (binds the
    // ImageCapture use case to a LifecycleOwner) — no camera-view, this never shows a
    // PreviewView/viewfinder to the driver, it's a silent background capture only. --
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

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
    implementation("io.coil-kt:coil-compose:2.6.0")

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
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
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
