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
 * Driving route + turn-by-turn maneuver steps from Mapbox's Directions API — what drives the
 * meter screen's navigator pane (route line on the map, remaining distance/ETA, and the spoken
 * instructions).
 *
 * **Why this and not the Mapbox Navigation SDK.** The Navigation SDK is the "proper" turn-by-turn
 * product (lane guidance, continuous rerouting, its own voice pipeline), and it is *not usable in
 * this project*: like every Mapbox SDK artifact it resolves from Mapbox's authenticated private
 * Maven repo (see `settings.gradle.kts`), which needs a secret `sk.*` downloads token with the
 * Downloads:Read scope. `MAPBOX_DOWNLOADS_TOKEN` in `local.properties` is present but **empty** —
 * only the public `pk.*` runtime token is configured — so the dependency cannot resolve at all.
 * The Directions API, by contrast, is a plain authenticated HTTPS GET that the `pk.*` token is
 * designed for; verified live against this project's token before this file was written
 * (HTTP 200; a Sydney CBD -> Sydney Airport request returned 13.3 km / 22 min / 12 steps with
 * real text instructions).
 *
 * So the guidance this app gives is **Directions-API guidance with spoken steps**, not the
 * Navigation SDK. Be honest about the difference when describing it: there is no lane guidance,
 * and rerouting is a simple "am I far off the line?" re-request rather than Mapbox's continuous
 * off-route detection. Swap this for the real SDK if a downloads token with the nav entitlement
 * is ever added to `local.properties`.
 *
 * Never fabricates: transport failures, non-2xx responses, malformed bodies and "no route found"
 * all surface as [Result.failure]/null rather than a plausible-looking guess.
 */
class MapboxDirections(private val client: OkHttpClient) {

    /**
     * Real driving route from ([fromLat], [fromLng]) to ([toLat], [toLng]).
     *
     * `overview=full` + `geometries=polyline` gives the full-fidelity encoded line (decoded by
     * [decodePolyline]) so the drawn route follows the actual road network rather than a
     * simplified sketch; `steps=true` returns the maneuver list the voice guidance speaks.
     */
    suspend fun route(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
    ): Result<DirectionsRoute> = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No Mapbox access token configured"))
        }
        // Mapbox coordinate order is lon,lat — the reverse of this app's lat/lng convention
        // everywhere else. Converted here at the boundary so callers never have to think about it.
        val url = "$BASE_URL$fromLng,$fromLat;$toLng,$toLat" +
            "?steps=true&overview=full&geometries=polyline&access_token=$token"
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Mapbox directions HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                parseRoute(body) ?: error("No route found")
            }
        }
    }

    private fun parseRoute(body: String): DirectionsRoute? {
        val root = JSON.parseToJsonElement(body).jsonObject
        val route = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val distanceM = route["distance"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val durationS = route["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val encoded = route["geometry"]?.jsonPrimitive?.content ?: return null
        val steps = route["legs"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("steps")?.jsonArray
            ?.mapNotNull { element ->
                val step = element.jsonObject
                val maneuver = step["maneuver"]?.jsonObject ?: return@mapNotNull null
                val loc = maneuver["location"]?.jsonArray ?: return@mapNotNull null
                if (loc.size < 2) return@mapNotNull null
                val lng = loc[0].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
                val lat = loc[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
                val instruction = maneuver["instruction"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                RouteStep(
                    instruction = instruction,
                    lat = lat,
                    lng = lng,
                    distanceM = step["distance"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            }
            .orEmpty()
        return DirectionsRoute(
            distanceM = distanceM,
            durationS = durationS,
            points = decodePolyline(encoded),
            steps = steps,
        )
    }

    private companion object {
        const val BASE_URL = "https://api.mapbox.com/directions/v5/mapbox/driving/"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** A real fetched route. [points] are in this app's lat/lng order, ready to draw. */
data class DirectionsRoute(
    val distanceM: Double,
    val durationS: Double,
    val points: List<RoutePoint>,
    val steps: List<RouteStep>,
)

data class RoutePoint(val lat: Double, val lng: Double)

/**
 * One maneuver. [lat]/[lng] is where the maneuver happens — the navigator advances to the next
 * step (and speaks it) once the vehicle gets within a threshold of this point. [distanceM] is the
 * length of this step, used for the "in N m" remaining-distance readout.
 */
data class RouteStep(
    val instruction: String,
    val lat: Double,
    val lng: Double,
    val distanceM: Double,
)

/**
 * Decodes Google/Mapbox's [encoded polyline algorithm][https://developers.google.com/maps/documentation/utilities/polylinealgorithm]
 * (precision 5, which is what `geometries=polyline` returns) into real coordinates.
 *
 * Hand-rolled rather than pulled from a library for the same reason the rest of this file is
 * REST-based: the Mapbox SDK utils that ship this decoder are behind the same unusable
 * authenticated Maven repo. The algorithm is small, fixed and well-specified — chunked 5-bit
 * groups, little-endian, zig-zag signed, each value a delta on the previous point.
 */
internal fun decodePolyline(encoded: String): List<RoutePoint> {
    val points = ArrayList<RoutePoint>(encoded.length / 2)
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        points.add(RoutePoint(lat / 1e5, lng / 1e5))
    }
    return points
}
