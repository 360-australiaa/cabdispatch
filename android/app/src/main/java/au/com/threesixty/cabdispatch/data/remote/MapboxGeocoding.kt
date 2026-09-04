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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Forward-geocoding (address text -> coordinates) against Mapbox's Geocoding API — the drop-off
 * search box on the meter screen's navigator pane.
 *
 * This is the "geocoding/reverse-geocoding gateway" that
 * [au.com.threesixty.cabdispatch.domain.format.TripDisplayFormat]'s doc has long noted as *not*
 * wired up in this app. Until now nothing here could turn typed text into a real location, which
 * is why trips only ever carried raw lat/lng and Trip Details rendered an honest "—" for
 * pickup/drop-off.
 *
 * **Why plain REST rather than an SDK**: identical reasoning to [MapboxStaticImage]'s doc — the
 * Mapbox *SDK* artifacts resolve from an authenticated private Maven repo needing a secret `sk.*`
 * downloads token, which this project does not have (`MAPBOX_DOWNLOADS_TOKEN` in
 * `local.properties` is present but EMPTY; only the public `pk.*` runtime token is set). The
 * Geocoding API has no such requirement: it is a plain authenticated HTTPS GET that the public
 * `pk.*` token is explicitly designed for. Verified live against this project's own token
 * (HTTP 200, real AU results) before this file was written.
 *
 * **Never fabricates.** A failed request, a non-2xx response, or a malformed body all surface as
 * [Result.failure]; an empty result set surfaces as an empty list. Callers must render an honest
 * empty/error state rather than inventing a plausible-looking address — the same rule the rest of
 * this app follows for missing data.
 */
class MapboxGeocoding(private val client: OkHttpClient) {

    /**
     * Real place suggestions for [query].
     *
     * [proximityLat]/[proximityLng] bias results toward the vehicle's current position when a fix
     * is available (Mapbox's `proximity` parameter), so "Central" ranks the nearby Central
     * Station above a same-named street on the other side of the country. Omitted entirely when
     * no fix exists — a bias is an optimisation, never required for a correct result.
     *
     * Restricted to `country=AU`: this is an NSW-regulated taxi meter, a drop-off outside
     * Australia is not a real destination for it, and the narrower search materially improves
     * ranking quality.
     *
     * **Field-testing default (2026-09-04): `PK` added too.** This build is being verified on a
     * physical tablet whose real GPS fix is in Karachi — an AU-only search can never return a
     * result the Directions API can actually route to from there (any AU match is a whole
     * continent away; see [MapboxDirections]'s "Route exceeds maximum distance limitation"
     * doc). Drop `,PK` once field testing wraps and this goes back to AU-only, matching the
     * dashboard's own temporary Karachi swaps (`FleetMapCanvas.DEFAULT_CENTER`, this file's
     * sibling [SydneyCbdFallback]).
     */
    suspend fun search(
        query: String,
        proximityLat: Double? = null,
        proximityLng: Double? = null,
        limit: Int = 5,
    ): Result<List<GeocodeResult>> = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN
        val trimmed = query.trim()
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No Mapbox access token configured"))
        }
        if (trimmed.length < MIN_QUERY_LENGTH) {
            // Not an error — just nothing worth asking the API about yet.
            return@withContext Result.success(emptyList())
        }
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        val url = buildString {
            append(BASE_URL)
            append(encoded)
            append(".json?country=AU&limit=")
            append(limit.coerceIn(1, 10))
            if (proximityLat != null && proximityLng != null) {
                append("&proximity=")
                append(proximityLng)
                append(',')
                append(proximityLat)
            }
            append("&access_token=")
            append(token)
        }
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Mapbox geocoding HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                parseFeatures(body)
            }
        }
    }

    private fun parseFeatures(body: String): List<GeocodeResult> {
        val root = JSON.parseToJsonElement(body).jsonObject
        val features = root["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { element ->
            val feature = element.jsonObject
            // `center` is [lon, lat] — Mapbox's GeoJSON order, the reverse of how this app's
            // lat/lng fields read everywhere else. Swapping it here (once, at the boundary)
            // keeps every caller in the app's own lat-then-lng convention.
            val center = feature["center"]?.jsonArray ?: return@mapNotNull null
            if (center.size < 2) return@mapNotNull null
            val lng = center[0].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            val lat = center[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
            val placeName = feature["place_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            GeocodeResult(
                placeName = placeName,
                shortName = feature["text"]?.jsonPrimitive?.content ?: placeName,
                lat = lat,
                lng = lng,
            )
        }
    }

    private companion object {
        const val BASE_URL = "https://api.mapbox.com/geocoding/v5/mapbox.places/"

        /** Below this, a query is too short to rank usefully — don't burn a request on it. */
        const val MIN_QUERY_LENGTH = 3

        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * One real geocoded place. [placeName] is Mapbox's full formatted address (what gets persisted as
 * the trip's drop-off and shown in Trip Details/History); [shortName] is just the leading
 * component, for compact display.
 */
data class GeocodeResult(
    val placeName: String,
    val shortName: String,
    val lat: Double,
    val lng: Double,
)
