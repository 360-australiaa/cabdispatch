plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "au.com.threesixty.cabdispatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.com.threesixty.cabdispatch"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Android emulators reach the host machine's localhost via the special
        // alias 10.0.2.2 (not 127.0.0.1/localhost) — see ApiService.kt header
        // comment for the full explanation. Overridden per build type below so
        // a real device on the same LAN/VPN can point at the host's LAN IP or a
        // staging URL without code changes.
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8001\"")
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

    testImplementation("junit:junit:4.13.2")
    // JVM unit tests for the offline sync engine (OutboxDrainerTest) — pure
    // Kotlin/coroutines, no Android framework classes, so these run without
    // the SDK/emulator this sandbox doesn't have. Version matched to the
    // kotlinx-coroutines-android version above, not bumped independently.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
