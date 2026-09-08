package au.com.threesixty.cabdispatch.domain

import java.time.LocalDate
import java.time.ZoneId

/**
 * The Android half of the X1 jurisdiction seam
 * (`docs/plans/2026-09-08-global-meter-program.md` Wave 3; backend counterpart
 * `backend/app/services/regions.FareRegion`). Everything the Android architecture audit's §2.4
 * "Hardcoded NSW / Australia assumptions" table lists is, in principle, a field on this class —
 * see that table for the full literal-by-literal list this is meant to eventually replace.
 *
 * ### What this is, honestly
 * This is the seam, not the wiring. [NSW] below reproduces today's hardcoded constants exactly —
 * [FareEngine.NSW_FARE_ZONE], [NswPublicHolidays], [au.com.threesixty.cabdispatch.domain.location.RegionResolver]'s
 * Sydney-CBD circle — so nothing about NSW's behaviour changes. Nothing on the wire populates a
 * non-NSW [JurisdictionConfig] yet: `GET /v1/tariffs/active` does not return a jurisdiction block
 * today (the backend half of this workstream ships `Tenant.jurisdiction`/`timezone`/`currency` as
 * columns, but the endpoint response is still `SignedTariffRead` alone — see that file's own
 * doc). The day it does, [TariffsDtos] gains the field and [source] stops being
 * [Source.CompiledDefault] — this class exists now so that day is a one-file parse change instead
 * of the ~30-file literal hunt the audit describes.
 *
 * Deliberately NOT wired into [au.com.threesixty.cabdispatch.domain.fare.FareEngine] /
 * [FareEngine]'s golden-tested arithmetic in this pass — those two files' numeric behaviour is
 * pinned bit-for-bit by `FareEngineTest.kt`/`FareTimeClassZoneTest.kt`, and this workstream's hard
 * constraint is that those golden vectors do not change a number or a name. [FareEngine] keeps
 * reading its own [FareEngine.NSW_FARE_ZONE] constant directly; this class is where a future pass
 * threads a per-tenant config through instead, once the server actually serves one.
 */
/** True when ([lat],[lng]) lies inside this jurisdiction's airport precinct; false when the
 * jurisdiction has no airport fee configured. */
fun JurisdictionConfig.isInsideAirportPrecinct(lat: Double, lng: Double): Boolean {
    val cLat = airportPrecinctLat ?: return false
    val cLng = airportPrecinctLng ?: return false
    val r = airportPrecinctRadiusM ?: return false
    return au.com.threesixty.cabdispatch.domain.location.GeoMath.distanceKm(lat, lng, cLat, cLng) * 1000.0 <= r
}

data class JurisdictionConfig(
    /** Machine-stable code — mirrors the backend's `Tenant.jurisdiction` / `FareRegion.code`. */
    val code: String,
    val label: String,
    /** The clock every fare-time/toll-time classification in this jurisdiction runs against —
     * replaces [FareEngine.NSW_FARE_ZONE] as a literal. */
    val fareZone: ZoneId,
    val currencyCode: String,
    /** `null` for a jurisdiction with no GST-equivalent levy — see the backend
     * `FareRegion.gst_divisor` doc for why that must render as "no figure", not a fabricated 0. */
    val gstDivisor: java.math.BigDecimal?,
    /** The tariff "area" vocabulary this jurisdiction's rate card distinguishes — NSW:
     * `["urban", "country", "exempt"]`. Mirrors `AreaClass`/`RegionResolver`'s two GPS-derivable
     * values plus the vehicle/tariff-type-selected `"exempt"`. */
    val regions: List<String>,
    /** Night-rate window as [startHour, endHour) wrapping midnight — NSW: 22..6. */
    val nightStartHour: Int,
    val nightEndHour: Int,
    /** [java.time.DayOfWeek] ordinal-1 values (Monday=1..Sunday=7, matching [java.time.DayOfWeek.getValue])
     * that make a NIGHT-window hiring peak-eligible on their own — NSW: Friday/Saturday, `[5, 6]`. */
    val peakDaysOfWeek: Set<Int>,
    /** Returns this jurisdiction's gazetted public holidays for a calendar year — sourced from
     * [NswPublicHolidays] for [NSW], matching the backend's own `NSW_PUBLIC_HOLIDAYS` copy so the
     * two engines can't independently drift (see that object's own honesty note re: 2027). */
    val holidaysForYear: (Int) -> Set<LocalDate>,
    /** Reference point + radius `RegionResolver` derives urban/country from — see that object's
     * class doc for why this is a circle, not a real boundary. */
    val regionResolverCentreLat: Double,
    val regionResolverCentreLng: Double,
    val urbanRadiusKm: Double,
    val operatingFootprintRadiusKm: Double,
    /**
     * Airport ground-transport access fee, charged ONCE per hiring that STARTS inside the airport
     * precinct (a pickup). NSW: $6.43, Point to Point Transport (Fares) Order 2026 -- the same
     * figure [au.com.threesixty.cabdispatch.domain.TollPresets.AIRPORT] already carries for the
     * manual preset; this is what makes it automatic. `null` in a jurisdiction with no such fee.
     *
     * Applied at pickup only, deliberately: the fee is paid by passengers picked up at the
     * airport. A drop-off drives INTO the precinct and must not be charged, so entering the area
     * mid-trip never triggers it -- only the trip's start position does.
     */
    val airportAccessFee: java.math.BigDecimal? = null,
    /** Centre and radius of the airport precinct used for the pickup test above. The NSW values
     * cover Sydney Airport's T1 and T2/T3 precincts and their rank roads; the radius is an
     * approximation of the precinct boundary, flagged as such, and lives here (not in code paths)
     * so a jurisdiction record can correct it. */
    val airportPrecinctLat: Double? = null,
    val airportPrecinctLng: Double? = null,
    val airportPrecinctRadiusM: Double? = null,
) {
    enum class Source {
        /** Compiled into this build. True for every jurisdiction today — see this file's own doc. */
        CompiledDefault,

        /** Parsed from a server response. Nothing returns this yet. */
        TariffResponse,
    }

    companion object {
        /** Always [Source.CompiledDefault] today. */
        val source: Source = Source.CompiledDefault

        /** The one jurisdiction this app actually ships — reproduces every existing hardcoded
         * NSW/Sydney constant exactly. See this class's own doc for what "seam, not solution"
         * means here. */
        val NSW: JurisdictionConfig = JurisdictionConfig(
            code = "NSW",
            label = "New South Wales",
            fareZone = ZoneId.of("Australia/Sydney"),
            currencyCode = "AUD",
            gstDivisor = java.math.BigDecimal(11),
            regions = listOf("urban", "country", "exempt"),
            nightStartHour = 22,
            nightEndHour = 6,
            peakDaysOfWeek = setOf(5, 6), // Friday, Saturday (DayOfWeek.getValue())
            holidaysForYear = { year ->
                NswPublicHolidays.DATES.filter { it.year == year }.toSet()
            },
            // au.com.threesixty.cabdispatch.data.remote.SydneyCbdFallback's own lat/lng, duplicated
            // here as literals rather than imported: that object lives in the `data.remote`
            // package and importing it from `domain` would invert this codebase's existing
            // domain->data dependency direction. Kept numerically IDENTICAL to the existing
            // constant on purpose (RegionResolverConfigTest asserts this) — including a pre-
            // existing discrepancy this pass found and is NOT silently "fixing": despite its
            // name and doc comment ("Sydney CBD reference point"), `SydneyCbdFallback.LAT`/`LNG`
            // actually hold Karachi's coordinates (24.8607, 67.0011), not Sydney's
            // (-33.8688, 151.2093). `RegionResolver` reads this same constant, so today's real
            // urban/country boundary is a 50km circle around Karachi, not Sydney — see this
            // workstream's final report for the flag; out of scope to correct here (not owned by
            // X1, and correcting it would change RegionResolver's live behaviour, which this seam
            // is deliberately NOT doing).
            regionResolverCentreLat = 24.8607,
            regionResolverCentreLng = 67.0011,
            urbanRadiusKm = 50.0,
            operatingFootprintRadiusKm = 2000.0,
            // Sydney Airport (T1 + T2/T3 precinct), Fares Order 2026 access fee. Radius approximate.
            airportAccessFee = java.math.BigDecimal("6.43"),
            airportPrecinctLat = -33.9399,
            airportPrecinctLng = 151.1753,
            airportPrecinctRadiusM = 1_800.0,
        )
    }
}
