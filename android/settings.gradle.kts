import java.util.Properties
import org.gradle.authentication.http.BasicAuthentication

// Mapbox's full Maps SDK (needed for the real offline-region download feature — see
// app/build.gradle.kts's dependency comment and HANDOFF.md's 2026-08-02 offline-maps section)
// resolves from Mapbox's PRIVATE Maven repo, which requires HTTP Basic auth using a separate
// SECRET "downloads" token (sk.*, "Downloads:Read" scope) — the public pk.* token used at
// runtime for map styling is NOT accepted here, this is a completely different credential.
// Read from local.properties (gitignored, machine-specific, never committed) — same file/pattern
// already used for MAPBOX_ACCESS_TOKEN in app/build.gradle.kts, just loaded independently here
// since settings.gradle.kts evaluates before any project-level build.gradle.kts does.
val mapboxDownloadsToken: String = Properties().apply {
    val f = File(rootDir, "local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("MAPBOX_DOWNLOADS_TOKEN", "")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mapbox's private Maven repo — only resolves com.mapbox.maps:android successfully if
        // mapboxDownloadsToken above is non-empty. If MAPBOX_DOWNLOADS_TOKEN is missing from
        // local.properties, this repo is still declared (harmless) but any attempt to actually
        // fetch the SDK from it will fail Gradle sync with a 401 — that is the expected, honest
        // failure mode for "someone hasn't set up their secret token yet", not a bug to silently
        // work around.
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            credentials {
                username = "mapbox"
                password = mapboxDownloadsToken
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

rootProject.name = "CabDispatch"
include(":app")
