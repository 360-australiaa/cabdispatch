package au.com.threesixty.cabdispatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the dashboard's calm-motion rule as an actual test rather than a comment (A3,
 * 2026-09-08).
 *
 * WHY A SOURCE-SCANNING TEST. This repo has a motion rule that is stated in at least five separate
 * places — `Hud.kt:437` (the reverted "circle is moving continuously, its doing pain in my head"
 * incident), `CaptainPalette.kt:70`, `FleetCommandOverlays.kt:349`, the program plan's operating
 * rule 9 — and it was violated anyway, repeatedly: at the time of the 2026-09-08 audit the home
 * screen carried an unconditional `SosControl` glow, an always-running refresh spinner, a
 * permanently breathing empty-offer halo and a sparkline that pulsed at a chart of days that had
 * already happened. Every one of those was added by someone who had read the rule.
 *
 * A comment cannot fail a build. This can. It reads the dashboard package's own source and asserts
 * that the only infinite animations in it are the ones this workstream deliberately sanctioned, so
 * the next person to reach for `rememberInfiniteTransition` here has to come and argue with this
 * list first.
 *
 * WHAT COUNTS AS A SITE. `rememberInfiniteTransition` is the primitive; `rememberInfiniteFloat`
 * (CaptainWidgets.kt) is the shared wrapper around it that correctly returns a constant when
 * `enabled` is false. Both are counted, because a `rememberInfiniteFloat(enabled = true, ...)` is
 * every bit as much an always-on loop as the raw primitive — that is exactly what `SosControl` did.
 *
 * THE THREE SANCTIONED LOOPS, all state-gated, and why each survives:
 *  1. **Header status dot** (`CaptainHeader.kt`) — breathes only while the driver is genuinely
 *     AVAILABLE / HIRED / ON BREAK. Neutral (OFF DUTY) is still.
 *  2. **DISPATCH badge** (`LiveDispatchCard.kt`) — composed only when real offers are pending.
 *  3. **Rail METER halo + selection halo** (`CaptainNavRail.kt`) — `enabled = selected || live`,
 *     where `live` means a fare is actually running. "Money is being counted right now" is
 *     information, not decoration.
 *
 * If you are adding a fourth: the bar is that it must be gated on real state that a driver can
 * act on, and it must stop when that state does. If it can run while the vehicle is parked and
 * nothing is happening, it does not belong here.
 */
class DashboardMotionRulesTest {

    private val dashboardDir = File("src/main/java/au/com/threesixty/cabdispatch/ui/screens/dashboard")

    /**
     * Files permitted to contain an infinite-animation call site, and how many each may have.
     * Every other file in the package must have zero.
     *
     * Note that the header's AVAILABLE status dot - sanctioned loop 1 of the three named in this
     * class's KDoc - does NOT appear here, and that is correct rather than an omission: it is a
     * `PulsingDot(animated = ...)`, whose loop lives inside `ui/theme/CaptainWidgets.kt`. The
     * gating that matters is at the call site (`animated = !statusNeutral`), and `PulsingDot`
     * itself is built on `rememberInfiniteFloat`, which returns a plain constant when disabled.
     * So the dot is genuinely state-gated; it simply is not a call site *in this package*.
     */
    private val sanctioned = mapOf(
        // The pending-offer count badge - composed only when real offers exist.
        "LiveDispatchCard.kt" to 1,
        // One shared `enabled = selected || live` call driving both the selection halo and the
        // METER "a fare is running" halo.
        "CaptainNavRail.kt" to 1,
        // The refresh spinner, now constructed only while a refresh is genuinely in flight (it
        // used to run unconditionally with its output ignored). Bounded by a real network call,
        // so it is a progress indicator rather than ambience.
        "EngagementTiles.kt" to 1,
    )

    private val loopPattern = Regex("""rememberInfiniteTransition|rememberInfiniteFloat""")

    @Test
    fun `dashboard package has no unsanctioned infinite animations`() {
        assertTrue(
            "Dashboard source directory not found at ${dashboardDir.absolutePath} — this test " +
                "resolves paths relative to the Gradle module dir; if the package moved, update it.",
            dashboardDir.isDirectory,
        )

        val offenders = mutableListOf<String>()
        dashboardDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                // Count only real call sites: strip `//` line comments first, so this file's own
                // explanatory prose (and the dashboard's, which names these primitives constantly)
                // never trips its own rule.
                val code = file.readLines()
                    .filterNot {
                        val t = it.trimStart()
                        // Comments and imports are not call sites. Imports matter especially:
                        // `import ...rememberInfiniteFloat` would otherwise add a phantom +1 to
                        // every file that legitimately uses it once.
                        t.startsWith("//") || t.startsWith("*") || t.startsWith("import ")
                    }
                    .joinToString("\n")
                val count = loopPattern.findAll(code).count()
                val allowed = sanctioned[file.name] ?: 0
                if (count > allowed) {
                    offenders += "${file.name}: found $count infinite-animation site(s), allowed $allowed"
                }
            }

        assertEquals(
            "Unsanctioned looping animation in the dashboard package. Read this test's KDoc " +
                "before adding one: a loop here must be gated on real driver-actionable state and " +
                "must stop when that state does. Offenders:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The SOS control specifically — it is called out separately because it is the one this repo
     * got wrong for longest, and because it sits permanently in the driver's eyeline. Its glow was
     * `rememberInfiniteFloat(enabled = true, ...)`, hardcoded, for an entire shift.
     */
    @Test
    fun `SosControl has no always-on animation`() {
        val widgets = File("src/main/java/au/com/threesixty/cabdispatch/ui/theme/CaptainWidgets.kt")
        assertTrue("CaptainWidgets.kt not found at ${widgets.absolutePath}", widgets.isFile)

        val source = widgets.readText()
        val sos = source.substringAfter("fun SosControl(").substringBefore("\nfun ")
        assertTrue(
            "SosControl must not create an infinite animation — a permanently pulsing duress " +
                "badge is decoration, not a signal (it is always armed, so an always-on " +
                "indicator carries no information). Use a static ring.",
            !loopPattern.containsMatchIn(sos),
        )
    }
}
