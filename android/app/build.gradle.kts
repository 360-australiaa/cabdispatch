import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
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

android {
    namespace = "au.com.threesixty.cabdispatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.com.threesixty.cabdispatch"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

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

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // TODO(sibling agent, release hardening): replace with the deployed
            // backend URL before shipping a release build.
            buildConfigField("String", "API_BASE_URL", "\"https://api.cabdispatch.example.com\"")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // -- Compose (BOM pins all Compose artifact versions together) --
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Explicit (rather than relying on the material3 transitive dep) because
    // S3 (Hired)'s hidden duress gesture uses foundation's pointerInput/
    // detectTapGestures directly — see ui/screens/hired/HiredScreen.kt.
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
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
    // sibling agent — this module just wires the dependency + KSP-free setup) --
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

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

    testImplementation("junit:junit:4.13.2")
    // JVM unit tests for the offline sync engine (OutboxDrainerTest) — pure
    // Kotlin/coroutines, no Android framework classes, so these run without
    // the SDK/emulator this sandbox doesn't have. Version matched to the
    // kotlinx-coroutines-android version above, not bumped independently.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
