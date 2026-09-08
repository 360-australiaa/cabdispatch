package au.com.threesixty.cabdispatch.domain.location

import au.com.threesixty.cabdispatch.data.remote.SydneyCbdFallback
import au.com.threesixty.cabdispatch.domain.LocationFix

/**
 * Resolves the Fares Order tariff region (`backend/app/models/tariffs.py`'s `REGION_URBAN`/
 * `REGION_COUNTRY` — the region string every `TariffCache.getActiveTariff(region=...)` /
 * `observeActiveTariff(region=...)` call site needs) a driver's real GPS position falls into —
 * closes the "Region is hardcoded to `urban`" gap HANDOFF.md's "Medium priority" section named
 * (spec B7: "geofenced urban/country/exempt regions").
 *
 * ### Why a distance-from-CBD circle, not a real polygon or a server call
 * Both alternatives named in this pass's brief were investigated before writing this, and neither
 * exists to use today:
 * - **Backend region resolution.** `backend/app/models/geofence.py` defines a `kind="region"`
 *   geofence row shape specifically for this — but that file's own docstring says outright those
 *   rows are "reserved for future use and not yet consumed by any service": there is no endpoint
 *   that takes a lat/lng and returns a region, and `GeofenceRead`/`GeofenceBase`
 *   (`backend/app/schemas/geofence.py`) has no field that would even tie a `kind="region"` row to
 *   a tariff-region label ("urban"/"country") if some existed — the schema is a bare
 *   name/circle-geometry, nothing region-label-shaped. `GET /v1/tariffs/active?region=` and
 *   `GET /v1/fares-order/current?region=urban|country` both *take* a region as input; neither
 *   *derives* one from a position. Building that server-side (a labelled-region-geofence
 *   convention plus a resolve endpoint) is a real new backend feature, materially bigger than this
 *   pass's Android-only brief — noted here for whoever picks it up next, not attempted.
 * - **A real polygon/geofence table check on-device.** Same gap one layer down: even fetching
 *   `GET /v1/geofences?kind=region` today would return rows with no region label to key off, per
 *   the point above — there's nothing seeded to check against yet either.
 * - **This pass's choice.** A plain circle around the Sydney CBD reference point
 *   ([SydneyCbdFallback] — the same point the dashboard's map background already falls back to
 *   when there's no fix) — [REGION_URBAN] inside, [REGION_COUNTRY] outside. Crude (the real Fares
 *   Order urban/country boundary is not a circle), but strictly better than the previous behaviour
 *   (every driver, everywhere, always `"urban"`) for the common case this app actually runs in
 *   today (Sydney metro). [URBAN_RADIUS_KM] is a placeholder tuned by eye against greater Sydney's
 *   rough extent, not a surveyed boundary — trivial to replace with a real polygon/geofence lookup
 *   once one of the two options above exists server-side; every [resolve] call site only reads the
 *   returned region string, so swapping this function's body out later is a one-file change.
 *
 * Never returns `"exempt"` — per spec B7 / `domain/TripModels.kt`, that region is a vehicle/
 * tariff-type classification (wedding cars, special-purpose hire) a driver/admin selects
 * deliberately, not one derivable from GPS position — out of scope for a location resolver.
 */
object RegionResolver {

    const val REGION_URBAN: String = "urban"
    const val REGION_COUNTRY: String = "country"

    /** See class doc's "This pass's choice" — a placeholder radius, not a surveyed boundary. */
    private const val URBAN_RADIUS_KM = 50.0

    /**
     * Beyond this distance from the Sydney CBD reference point a fix is treated as "outside the
     * fleet's operating footprint entirely" (e.g. an overseas field-test tablet) rather than as
     * rural NSW, and falls back to [REGION_URBAN] instead of [REGION_COUNTRY]. Rationale
     * (2026-08-28, found live on a Karachi field-test tablet): the urban/country split above only
     * makes sense inside Australia — a genuine rural-NSW fix is tens-to-a-few-hundred km from the
     * CBD, never thousands. A device ~12,000 km away resolving to `"country"` produced a meter
     * that silently refused to start, because this tenant serves no `"country"` tariff
     * (`GET /v1/tariffs/active?region=country` → 404), so `observeActiveTariff("country")` stayed
     * null and `WheelDashboardViewModel.startMeter()` no-op'd ("Waiting for a signed tariff…").
     * Every tenant always has an urban tariff, so defaulting the clearly-out-of-region case to
     * urban is both safe and the only value that lets a meter run at all off-continent. ~2000 km
     * comfortably contains greater Sydney + all of NSW and its neighbours (Melbourne ~713 km,
     * Brisbane ~730 km, Adelaide ~1160 km from Sydney) so no real AU country fix is misclassified;
     * only genuinely off-continent fixes cross it. Replace this whole heuristic with a real
     * labelled-region geofence lookup once one exists server-side (see class doc).
     */
    private const val OPERATING_FOOTPRINT_RADIUS_KM = 2000.0

    /**
     * @param fix the latest accepted [LocationFix] (e.g. `AppContainer.speedSource.locationFix.value`
     *   or a collected [kotlinx.coroutines.flow.StateFlow] value), or `null` when no fix is
     *   available yet (first launch, permission denied, indoors, GPS disabled) — degrades to
     *   [REGION_URBAN], the exact same fallback every call site already used before this resolver
     *   existed, so "no fix yet" never regresses behaviour relative to the old hardcoded constant.
     */
    fun resolve(fix: LocationFix?): String = resolve(fix?.lat, fix?.lng)

    /** Same as [resolve] but for callers that already have a bare lat/lng rather than a full [LocationFix]. */
    fun resolve(lat: Double?, lng: Double?): String {
        if (lat == null || lng == null) return REGION_URBAN
        val distanceKm = GeoMath.distanceKm(lat, lng, SydneyCbdFallback.LAT, SydneyCbdFallback.LNG)
        return when {
            distanceKm <= URBAN_RADIUS_KM -> REGION_URBAN
            // Clearly outside the fleet's operating footprint (e.g. an overseas field-test tablet)
            // — fall back to urban, the one region every tenant always has a tariff for, rather
            // than to rural-NSW "country" which may not exist and would silently block the meter.
            distanceKm > OPERATING_FOOTPRINT_RADIUS_KM -> REGION_URBAN
            else -> REGION_COUNTRY
        }
    }
}
