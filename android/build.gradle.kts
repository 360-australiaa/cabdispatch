// Root build file: declares plugin versions once (via `apply false`) so the
// `:app` module can apply them without re-specifying a version.
// No Hilt plugin here on purpose — see app/build.gradle.kts and
// data/AppContainer.kt for why (manual ServiceLocator instead of DI codegen).
//
// A9 toolchain upgrade (2026-09-08): Kotlin 1.9.24 -> 2.0.21, AGP 8.5.2 -> 8.7.3. Kotlin 2.0's
// K2 compiler needs Compose wired through the dedicated `org.jetbrains.kotlin.plugin.compose`
// Gradle plugin instead of the old `composeOptions.kotlinCompilerExtensionVersion` mechanism
// (that field is ignored — and unsupported — once this plugin is applied; see app/build.gradle.kts).
// Room's annotation processing moved from kapt to KSP in the same pass (`app/build.gradle.kts`) —
// kapt still works under Kotlin 2.0 but is in maintenance mode upstream and noticeably slower;
// KSP is the vendor-recommended path forward and is what let Room's schema export actually run
// (see the Room migration test doc in androidTest for why that mattered here).
plugins {
    // W0 toolchain refresh (2026-09-12): AGP 8.7.3 -> 8.13.2 (latest stable 8.x), Gradle
    // 8.10.2 -> 9.5.1 (the newest Gradle AGP 8.x actually supports -- 9.6.0+ removed a Gradle
    // internal API AGP 8.x still calls, verified by an actual failed build during this pass), Kotlin
    // 2.0.21 -> 2.3.21, KSP -> 2.3.12. Deliberately NOT AGP 9.x: AGP 9.0 made Kotlin support
    // "built in" to the Android plugin and rejects `org.jetbrains.kotlin.android` outright ("no
    // longer required... Remove the plugin"), which also drops the classic variant API this
    // project's build logic was written against -- a real DSL migration, not a version bump,
    // also verified by an actual failed build (see the W0 section of this program's plan doc).
    // Every GradleDependency-lint dependency bump in app/build.gradle.kts was chosen to be the
    // newest version that still compiles under compileSdk 36/AGP 8.x for exactly that reason -- a
    // small number of libraries (whichever pulls in AGP-9-only compose-ui/core-ktx/lifecycle
    // releases) are one or two minor versions behind their own absolute latest as a result; the
    // full AGP-9 migration is real, future work, not something to fold into this pass.
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
