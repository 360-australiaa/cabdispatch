package au.com.threesixty.cabdispatch.data.remote

import au.com.threesixty.cabdispatch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reverse-geocoding (coordinates -> a real formatted address) against Mapbox's Geocoding API —
 * the sibling gateway to [MapboxGeocoding] (forward: free text -> coordinates), used to fill
 * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.pickupAddress] for a trip that
 * opened with no dispatch-offer address to carry (a street-hail/rank job, a Start Meter/Set Price
 * trip — see that column's own doc for exactly why it's often `null`). Same endpoint family as
 * [MapboxGeocoding] (`GET /geocoding/v5/mapbox.places/{lng},{lat}.json`), just called with a real
 * coordinate pair instead of free-text.
 *
 * **Why plain REST rather than an SDK**: identical reasoning to [MapboxGeocoding]/
 * [MapboxStaticImage]/[MapboxDirections] — the Mapbox *SDK* artifacts resolve from an
 * authenticated private Maven repo needing a secret `sk.*` downloads token this project does not
 * have (`MAPBOX_DOWNLOADS_TOKEN` in `local.properties` is present but EMPTY); the Geocoding API is
 * a plain authenticated HTTPS GET the public `pk.*` runtime token is explicitly designed for.
 *
 * **One-time lookup, not a live feature.** Unlike [MapboxGeocoding.search] (called on every
 * keystroke in the navigator's drop-off search box), this is called at most twice per trip — once
 * from [au.com.threesixty.cabdispatch.ui.screens.hired.MeterNavViewModel] the moment a destination
 * is first picked (so the live nav pane's PICK UP card has something to show instead of "—"), and
 * as a fallback from [au.com.threesixty.cabdispatch.data.repository.TripRepository]'s
 * `fillPickupAddressIfMissing` at close time for a trip whose driver never opened the navigator at
 * all — a record-keeping fill for History/Trip Detail either way, not something that ticks.
 *
 * **Never fabricates.** A failed request, a non-2xx response, or a malformed body all surface as
 * [Result.failure]; a coordinate with no address on record (open water, deep bush, a token-less
 * build) surfaces as [Result.success] wrapping `null` — never a guessed/placeholder address.
 * Callers must leave whatever they already show (this app's honest "—" for a `null`
 * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.pickupAddress]) rather than
 * inventing one.
 */
class MapboxReverseGeocoding(private val client: OkHttpClient) {

    /**
     * The real formatted place name for ([lat], [lng]), or `null` if Mapbox has no address on
     * record for that point. Tries `types=address` first (a real street address reads best as a
     * pickup location); [PICKUP_FALLBACK_TYPES] widens the search only when that comes back with
     * *nothing at all* — confirmed live, 2026-09-05: a real Karachi coordinate with a healthy road
     * network still returned zero `address`-typed features (verified with a direct curl against
     * Mapbox's own API, not a guess), which is a real address-coverage gap in parts of the world
     * this app's field-testing has hit, not something specific to Australia. The fallback still
     * only ever returns a real Mapbox feature (a locality/POI/neighbourhood name, e.g. "Shah
     * Faisal, Karachi") — never a region/country-level result broad enough to be useless as a
     * pickup label, and never anything invented. `limit=1` on both requests since only the single
     * best match is ever used.
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): Result<String?> = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No Mapbox access token configured"))
        }
        // Mapbox coordinate order is lon,lat — the reverse of this app's lat/lng convention
        // everywhere else (same boundary conversion MapboxGeocoding/MapboxDirections make).
        // Rounded to 5dp so a near-zero coordinate never formats in scientific notation (same
        // MapboxStaticImage.roundTo convention, reimplemented locally below).
        val lon = lng.roundTo5()
        val la = lat.roundTo5()
        runCatching {
            val address = fetchPlaceName(lon, la, "address", token)
            address ?: fetchPlaceName(lon, la, PICKUP_FALLBACK_TYPES, token)
        }
    }

    private fun fetchPlaceName(lon: Double, lat: Double, types: String, token: String): String? {
        val url = "$BASE_URL$lon,$lat.json?types=$types&limit=1&access_token=$token"
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("Mapbox reverse geocoding HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parseReverseGeocodePlaceName(body)
        }
    }

    private fun Double.roundTo5(): Double {
        val factor = 100000.0
        return Math.round(this * factor) / factor
    }

    private companion object {
        const val BASE_URL = "https://api.mapbox.com/geocoding/v5/mapbox.places/"

        /** Local/neighbourhood-scale fallback types, tried only when a real street `address`
         * isn't on record — deliberately excludes `place`/`district`/`region`/`postcode`/
         * `country`, which would resolve to something too broad to read as a pickup location. */
        const val PICKUP_FALLBACK_TYPES = "poi,neighborhood,locality"
    }
}

/**
 * Parses a Mapbox Geocoding API response body down to the single best `place_name`, or `null`
 * when the response carries no features (a real "nothing on record here" result, not an error) or
 * is malformed in a way that just means "no usable feature" rather than a hard failure. Top-level
 * and `internal` (mirrors [decodePolyline]'s own convention in `MapboxDirections.kt`) so a unit
 * test can pin its behavior directly against a captured/realistic response body without needing
 * to fake an OkHttp call.
 */
internal fun parseReverseGeocodePlaceName(body: String): String? {
    val root = runCatching { REVERSE_GEOCODE_JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val feature = root["features"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    return feature["place_name"]?.jsonPrimitive?.content
}

private val REVERSE_GEOCODE_JSON = Json { ignoreUnknownKeys = true }
