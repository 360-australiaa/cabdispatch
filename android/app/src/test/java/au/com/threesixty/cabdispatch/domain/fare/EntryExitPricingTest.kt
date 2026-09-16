package au.com.threesixty.cabdispatch.domain.fare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The `entry_exit` pricing model -- Linkt's own entry point -> exit point prices (2026-09-15,
 * owner: "copy all pricing from Linkt, Linkt pricing is accurate"). The registry here is the
 * Anzac Bridge -> Homebush Bay Drive shape: one WestConnex section Linkt bills at $8.80 through the
 * Rozelle Interchange and the M4 East, with an intermediate exit (Haberfield, $4.75) the through
 * trip passes without leaving. Mirrors `backend/tests/test_tolls_entry_exit.py`.
 */
class EntryExitPricingTest {
    private val ts: ZonedDateTime = ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0, NSW_FARE_ZONE)

    private val anzacEntry = TollGantryRef("LINKT:rozelle-anzac:entry", "ROZELLE", -33.8692, 151.1770, ramp = "entry")
    private val haberfieldExit = TollGantryRef("LINKT:m4-wattle-st:exit", "M4", -33.8830, 151.1400, ramp = "exit")
    private val homebushExit = TollGantryRef("LINKT:m4-homebush:exit", "M4", -33.85709, 151.0709, ramp = "exit")

    /** Linkt puts an interchange's entry point 15 m from its exit point. */
    private val homebushEntry = TollGantryRef("LINKT:m4-homebush:entry", "M4", -33.85696, 151.07098, ramp = "entry")
    private val hillRdExit = TollGantryRef("LINKT:m4-hill-rd:exit", "M4", -33.8500, 151.0600, ramp = "exit")

    private fun linktRoad(id: String, name: String) = TollRoadRef(
        id = id,
        name = name,
        pricingModel = "entry_exit",
        chargingPolicy = "entry_exit_pair",
        networkGroup = "WESTCONNEX",
        directional = null,
        currentPrice = null,
    )

    private fun band(price: String, day: String = "all", interval: String = "0000-2400") =
        TollPriceBandRef(day, interval, BigDecimal(price))

    private fun pair(entry: TollGantryRef, exit: TollGantryRef, price: String) =
        TollPricePairRef(entry.id, exit.id, "WestConnex", listOf(band(price)))

    private fun westConnexRegistry(): TollRegistrySnapshot = TollRegistrySnapshot(
        roadsById = mapOf(
            "ROZELLE" to linktRoad("ROZELLE", "Rozelle Interchange"),
            "M4" to linktRoad("M4", "WestConnex M4"),
        ),
        gantries = listOf(anzacEntry, haberfieldExit, homebushExit, homebushEntry, hillRdExit),
        pricePairs = listOf(
            pair(anzacEntry, haberfieldExit, "4.75"),
            pair(anzacEntry, homebushExit, "8.80"),
            pair(anzacEntry, hillRdExit, "9.40"),
            pair(homebushEntry, hillRdExit, "2.20"),
        ).associateBy { it.id },
    )

    private fun drive(state: TollDetectionState, registry: TollRegistrySnapshot, at: TollGantryRef, km: String) =
        onFix(state, registry, at.latitude, at.longitude, ts, BigDecimal(km))

    @Test
    fun `charges Linkt's price for the last exit passed, revised as the drive goes on`() {
        val registry = westConnexRegistry()
        val state = TollDetectionState()
        assertTrue("an entry alone is no charge", drive(state, registry, anzacEntry, "0.5").isEmpty)
        assertEquals(
            mapOf("ROZELLE" to BigDecimal("4.75")),
            drive(state, registry, haberfieldExit, "5.0").chargedRoadsChanged,
        )
        // Drove on past Haberfield to Homebush: the through price replaces the intermediate one.
        assertEquals(
            mapOf("ROZELLE" to BigDecimal("8.80")),
            drive(state, registry, homebushExit, "11.0").chargedRoadsChanged,
        )
        assertEquals(mapOf("ROZELLE" to BigDecimal("8.80")), state.chargedRoads)
        assertTrue(state.unpricedRoadIds.isEmpty())
    }

    @Test
    fun `driving through an interchange confirms its entry and exit together and opens nothing new`() {
        val registry = westConnexRegistry()
        val state = TollDetectionState()
        drive(state, registry, anzacEntry, "0.5")
        drive(state, registry, haberfieldExit, "5.0")
        // Homebush: both points inside 60 m of one fix.
        drive(state, registry, homebushExit, "11.0")
        assertEquals(1, state.entryExitSections.size)
        // On to Hill Road: still the Anzac Bridge trip, $9.40 -- not 8.80 + 2.20.
        val onToHillRoad = drive(state, registry, hillRdExit, "13.0")
        assertEquals(mapOf("ROZELLE" to BigDecimal("9.40")), onToHillRoad.chargedRoadsChanged)
        assertEquals(1, state.entryExitSections.size)
    }

    @Test
    fun `a fare that starts at an interchange opens its section before seeing that interchange's exit`() {
        val registry = westConnexRegistry()
        val state = TollDetectionState()
        val first = drive(state, registry, homebushEntry, "0.1")
        assertTrue("no spurious flag from the co-located exit point", first.isEmpty)
        assertEquals(1, state.entryExitSections.size)
        assertEquals(mapOf("M4" to BigDecimal("2.20")), drive(state, registry, hillRdExit, "2.0").chargedRoadsChanged)
    }

    @Test
    fun `an exit with no entry is flagged for the driver, never guessed`() {
        val registry = westConnexRegistry()
        val state = TollDetectionState()
        val result = drive(state, registry, haberfieldExit, "3.0")
        assertTrue(result.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("M4"), result.newlyUnpricedRoadIds)
        assertTrue(state.chargedRoads.isEmpty())
    }

    @Test
    fun `leaving and re-entering elsewhere makes a second section that adds to the first`() {
        val registry = westConnexRegistry()
        val state = TollDetectionState()
        drive(state, registry, anzacEntry, "0.5")
        drive(state, registry, haberfieldExit, "5.0")
        onFix(state, registry, -33.90, 151.20, ts, BigDecimal("9.0")) // off the motorway
        drive(state, registry, anzacEntry, "12.0")
        val result = drive(state, registry, homebushExit, "23.0")
        assertEquals(mapOf("ROZELLE" to BigDecimal("13.55")), result.chargedRoadsChanged)
        assertEquals(2, state.entryExitSections.size)
    }

    @Test
    fun `Linkt bands follow the day and interval in NSW time and wrap midnight`() {
        val bands = listOf(
            band("4.55", "weekdays", "0630-0930"),
            band("3.41", "weekdays", "0930-1600"),
            band("4.55", "weekdays", "1600-1900"),
            band("2.85", "weekdays", "1900-0630"),
            band("3.41", "weekend", "0800-2000"),
            band("2.85", "weekend", "2000-0800"),
        )
        val monday8 = ZonedDateTime.of(2026, 9, 14, 8, 0, 0, 0, NSW_FARE_ZONE)
        assertEquals(BigDecimal("4.55"), selectPairBandPrice(bands, monday8))
        assertEquals(BigDecimal("4.55"), selectPairBandPrice(bands, monday8.withZoneSameInstant(ZoneOffset.UTC)))
        val mondayLate = ZonedDateTime.of(2026, 9, 14, 23, 30, 0, 0, NSW_FARE_ZONE)
        assertEquals(BigDecimal("2.85"), selectPairBandPrice(bands, mondayLate))
        val tuesdayEarly = ZonedDateTime.of(2026, 9, 15, 2, 0, 0, 0, NSW_FARE_ZONE)
        assertEquals(BigDecimal("2.85"), selectPairBandPrice(bands, tuesdayEarly))
        val saturdayNoon = ZonedDateTime.of(2026, 9, 19, 12, 0, 0, 0, NSW_FARE_ZONE)
        assertEquals(BigDecimal("3.41"), selectPairBandPrice(bands, saturdayNoon))
        assertNull(selectPairBandPrice(emptyList(), monday8))
    }

    @Test
    fun `the toll-ahead chip prices an entry_exit road from its dearest pair and ignores exit points`() {
        val registry = westConnexRegistry()
        val rozelle = registry.roadsById.getValue("ROZELLE")
        assertEquals(BigDecimal("9.40"), representativeTollPrice(rozelle, anzacEntry, registry))
        // Heading west 500 m east of the Anzac Bridge entry: the chip names the Rozelle Interchange.
        val ahead = upcomingToll(registry, anzacEntry.latitude, anzacEntry.longitude + 0.0055, headingDeg = 270.0)
        assertEquals("Rozelle Interchange", ahead?.roadName)
        assertEquals(BigDecimal("9.40"), ahead?.price)
    }

    @Test
    fun `an abandoned section (exit Linkt has no through-price for) never blocks a later, unrelated road`() {
        // 2026-09-16 field report: a real Sydney tablet trip drove Anzac Bridge <-> Iron Cove
        // Bridge within the Rozelle Interchange without continuing onto a priced WestConnex
        // section -- genuinely free, no pair exists for that exact combination -- then went on to
        // drive a real, separately-priced Harbour crossing. Before this fix the abandoned Rozelle
        // section stayed open forever and silently swallowed the Harbour crossing's own entry,
        // billing it $0.00 with no evidence at all.
        val rozelleEntry = TollGantryRef("LINKT:rozelle-only:entry", "ROZELLE", -33.8600, 151.1000, ramp = "entry")
        val rozelleDeadEndExit = TollGantryRef("LINKT:rozelle-only:exit", "ROZELLE", -33.8605, 151.1005, ramp = "exit")
        val shbEntry = TollGantryRef("LINKT:shb:entry", "SHB", -33.8700, 151.2000, ramp = "entry")
        val shbExit = TollGantryRef("LINKT:shb:exit", "SHB", -33.8400, 151.2100, ramp = "exit")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf(
                "ROZELLE" to linktRoad("ROZELLE", "Rozelle Interchange"),
                "SHB" to linktRoad("SHB", "Sydney Harbour Bridge & Tunnel"),
            ),
            gantries = listOf(rozelleEntry, rozelleDeadEndExit, shbEntry, shbExit),
            // No pair at all for rozelle-only:entry -> rozelle-only:exit (genuinely free/uncaptured
            // combination) -- only the unrelated SHB pair is priced.
            pricePairs = listOf(pair(shbEntry, shbExit, "4.41")).associateBy { it.id },
        )
        val state = TollDetectionState()
        assertTrue(drive(state, registry, rozelleEntry, "0.1").isEmpty)
        // The exit Linkt has no price for: ignored for now -- not yet flagged, since the driver
        // could still come back and cross an exit that DOES price this section.
        val exitResult = drive(state, registry, rozelleDeadEndExit, "0.5")
        assertTrue(exitResult.chargedRoadsChanged.isEmpty())
        assertTrue(state.unpricedRoadIds.isEmpty())
        // Far away, a genuinely separate road's entry: must open its OWN section, not be swallowed
        // -- and flags the abandoned Rozelle section unpriced now that the trip has moved on.
        val shbEntryResult = drive(state, registry, shbEntry, "5.0")
        assertTrue(shbEntryResult.chargedRoadsChanged.isEmpty())
        assertEquals(setOf("ROZELLE"), state.unpricedRoadIds)
        val shbResult = drive(state, registry, shbExit, "9.0")
        assertEquals(mapOf("SHB" to BigDecimal("4.41")), shbResult.chargedRoadsChanged)
        assertEquals(BigDecimal("4.41"), state.chargedRoads["SHB"])
    }

    @Test
    fun `a co-located sibling road's own entry+exit does not abandon the still-open section`() {
        // A real seeded-registry regression (2026-09-16): King Georges Road hosts an M8/M5 East
        // co-located entry AND exit right beside an M4 through-trip's own real exit. The M4 entry
        // is far from that interchange, so it is not folded in as a co-located CANDIDATE -- but the
        // interchange's own entry+exit both confirm in the SAME fix as the M4 trip's real exit, and
        // that must not read as "the M4 section was abandoned, open a fresh one instead" (it very
        // nearly did in the first cut of the backend mirror of this fix).
        val m4Entry = TollGantryRef("LINKT:m4-concord-rd:entry", "M4", -33.83, 151.10, ramp = "entry")
        val kgrEntry = TollGantryRef("LINKT:kgr:entry", "M5E", -33.90, 151.05, ramp = "entry")
        val kgrExit = TollGantryRef("LINKT:kgr:exit", "M5E", -33.9001, 151.0501, ramp = "exit") // ~14 m away
        val registry = TollRegistrySnapshot(
            roadsById = mapOf(
                "M4" to linktRoad("M4", "WestConnex M4"),
                "M5E" to linktRoad("M5E", "WestConnex M5 East"),
            ),
            gantries = listOf(m4Entry, kgrEntry, kgrExit),
            pricePairs = listOf(pair(m4Entry, kgrExit, "12.74")).associateBy { it.id },
        )
        val state = TollDetectionState()
        assertTrue(drive(state, registry, m4Entry, "0.5").isEmpty)
        // One fix: the King Georges Road entry AND exit both confirm together.
        val result = onFix(state, registry, kgrExit.latitude, kgrExit.longitude, ts, BigDecimal("40.0"))
        assertEquals(mapOf("M4" to BigDecimal("12.74")), result.chargedRoadsChanged)
        assertTrue(state.unpricedRoadIds.isEmpty())
    }

    @Test
    fun `two Linkt entry points at one on-ramp are both candidates until the exit picks one`() {
        // Lane Cove Tunnel: "just the tunnel" and "tunnel + Military Road e-ramp" share an entry.
        val justTunnel = TollGantryRef("LINKT:just-lct:entry", "LCT", -33.8294, 151.2138, ramp = "entry")
        val withRamp = TollGantryRef("LINKT:lct-and-ramp:entry", "LCT", -33.82941, 151.21381, ramp = "entry")
        val westExit = TollGantryRef("LINKT:lct-and-ramp:exit", "LCT", -33.80152, 151.14486, ramp = "exit")
        val registry = TollRegistrySnapshot(
            roadsById = mapOf("LCT" to linktRoad("LCT", "Lane Cove Tunnel")),
            gantries = listOf(justTunnel, withRamp, westExit),
            pricePairs = listOf(pair(withRamp, westExit, "6.45")).associateBy { it.id },
        )
        val state = TollDetectionState()
        drive(state, registry, justTunnel, "0.2") // both entries inside one fix
        assertEquals(listOf(justTunnel.id, withRamp.id), state.entryExitSections.single().candidateEntryIds)
        assertEquals(mapOf("LCT" to BigDecimal("6.45")), drive(state, registry, westExit, "7.0").chargedRoadsChanged)
    }
}
