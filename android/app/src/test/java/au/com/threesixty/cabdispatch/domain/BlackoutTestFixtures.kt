package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.TollRoadRef

/**
 * Shared blackout/tariff test fixtures for [MeterAccuracyTest] and
 * `BlackoutRestartAndGoldenVectorsTest` — extracted out of [MeterAccuracyTest]'s own private
 * companion object (W6, Tests and CI depth, 2026-09-12) purely to keep that already-large class
 * from growing further as this pass adds new tests; every value here is copied verbatim from
 * where it used to live, not recomputed, so no existing golden vector's expected figure moves.
 */

/** The 2026 Order urban rate card, matching `domain.fare.URBAN_TARIFF` field-for-field —
 * decimal-as-string per this project's wire convention. */
internal fun urbanTariffDto(): TariffDto = TariffDto(
    id = "tariff-urban-2026",
    tenantId = null,
    name = "urban-2026",
    region = "urban",
    effectiveFrom = "2026-06-01T00:00:00+00:00",
    booked = false,
    flagFall = "5.17",
    peakCharge = "2.65",
    distRate1 = "2.61",
    distRate2 = "2.37",
    nightRate1 = "3.10",
    nightRate2 = "2.82",
    waitingRatePerMin = "1.130",
    pslAmount = "1.32",
    createdAt = "2026-06-01T00:00:00+00:00",
    updatedAt = "2026-06-01T00:00:00+00:00",
)

// A bent three-gantry corridor -- entry near A, exit near C, with B off to the side between them
// (same shape [au.com.threesixty.cabdispatch.domain.fare.KnownCorridorTest] uses) so the real
// path through B is measurably longer than the straight A-C chord: a test that only checked "did
// SOME distance get billed" could not tell the correct behaviour apart from a regression back to
// the straight-line chord.
internal const val TUNNEL_ENTRY_LAT = -33.9200
internal const val TUNNEL_ENTRY_LNG = 151.1500
internal const val TUNNEL_MID_LAT = -33.9180
internal const val TUNNEL_MID_LNG = 151.1550
internal const val TUNNEL_EXIT_LAT = -33.9200
internal const val TUNNEL_EXIT_LNG = 151.1600

/** `unpriced` deliberately: toll charging is a wholly separate concern from the known-corridor
 * distance catch-up (see that feature's own doc), and giving this road no real price makes "this
 * path never adds a toll" a trivially checkable assertion rather than one that depends on also
 * getting [au.com.threesixty.cabdispatch.domain.fare.onFix]'s own corroboration rules right in
 * the same fixture. */
internal fun bentTunnelRegistry(): TollRegistrySnapshot {
    val road = TollRoadRef(
        id = "TUNNEL",
        name = "Test Tunnel",
        pricingModel = "unpriced",
        directional = "both",
        currentPrice = null,
    )
    return TollRegistrySnapshot(
        roadsById = mapOf(road.id to road),
        gantries = listOf(
            TollGantryRef("TUNNEL-A", road.id, TUNNEL_ENTRY_LAT, TUNNEL_ENTRY_LNG),
            TollGantryRef("TUNNEL-B", road.id, TUNNEL_MID_LAT, TUNNEL_MID_LNG),
            TollGantryRef("TUNNEL-C", road.id, TUNNEL_EXIT_LAT, TUNNEL_EXIT_LNG),
        ),
    )
}
