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
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
