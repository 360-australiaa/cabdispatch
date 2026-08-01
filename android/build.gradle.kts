// Root build file: declares plugin versions once (via `apply false`) so the
// `:app` module can apply them without re-specifying a version.
// No Hilt/KSP plugin here on purpose — see app/build.gradle.kts and
// data/AppContainer.kt for why (manual ServiceLocator instead of DI codegen).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    // kapt (not KSP) for Room's annotation processor only — Room needs *some*
    // Kotlin annotation processor to generate DAO impls; kapt is the mature,
    // battle-tested option. This is unrelated to the "no Hilt/KSP for DI" rule
    // below, which is about dependency-injection *codegen fragility*, not Room.
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
