package au.com.threesixty.cabdispatch.data.remote

import au.com.threesixty.cabdispatch.BuildConfig
import kotlin.math.roundToInt

/**
 * Builds URLs for Mapbox's Static Images API — a plain authenticated HTTPS GET that returns a
 * rendered map PNG for a given center/zoom/size, e.g.:
 * `https://api.mapbox.com/styles/v1/mapbox/dark-v11/static/{lon},{lat},{zoom}/{w}x{h}@2x?access_token=...`
 *
 * Why this instead of the full Maps SDK (v10/v11, interactive tiles/gestures/offline): that SDK's
 * Gradle dependency resolves from Mapbox's *private* Maven repo, which requires a separate SECRET
 * "downloads" token (`sk.*`, "Downloads:Read" scope) configured as Maven repository credentials
 * in `settings.gradle.kts` — a public `pk.*` token (all [BuildConfig.MAPBOX_ACCESS_TOKEN] is, and
 * all this project has wired) is not accepted there at all; the dependency fails to resolve
 * before any app code even runs. The Static Images API has no such requirement: it's a plain
 * authenticated REST GET, same trust level as any other backend call this app makes, and the
 * `pk.*` token is explicitly designed to be used exactly this way (safe to ship client-side). See
 * `HANDOFF.md`'s map-background section for the full constraint writeup, and don't re-attempt the
 * full SDK without first getting a real `sk.*` downloads token from whoever owns the Mapbox
 * account.
 *
 * This is a genuine map image (real-world imagery/road network at the given coordinates), not the
 * illustrative placeholder it replaces — just non-interactive (no pan/zoom/rotate gestures; a
 * fresh image must be requested for a new center/zoom).
 */
object MapboxStaticImage {

    // Ben's own custom Mapbox Studio style (`mapbox://styles/benfarid/cmtbnyhe4000e01pcgx2t51za`)
    // was switched to independently on both this branch and main (2026-08-27/28) for shared
    // branding with the dashboard's Live Map. Re-checked live at merge time (direct curl, both
    // over Karachi and over NYC as a known data-rich sanity check, 2026-08-28) and it STILL
    // renders a blank/all-white PNG everywhere — an unpublished/broken Studio style, not a token
    // or request-shape problem on this app's side (the real pk.* token itself works fine, see
    // MapboxStaticImage's own doc). Kept on the stock `mapbox/dark-v11` style so the dashboard
    // shows a real map instead of a blank square; swap BASE_URL's username back to "benfarid" and
    // STYLE to the custom id below once that style actually renders something server-side —
    // verify with a plain curl before flipping this back, not just by trusting either side of
    // this merge.
    private const val BASE_URL = "https://api.mapbox.com/styles/v1/mapbox"

    /** See [BASE_URL]'s note above — "dark-v11" (Mapbox's own default dark style) until Ben's
     * custom Studio style (`cmtbnyhe4000e01pcgx2t51za`) actually renders something. */
    private const val STYLE = "dark-v11"

    /** Mapbox Static Images API hard limit — width/height (pre-`@2x` multiplication) must each be <= 1280. */
    private const val MAX_DIMENSION_PX = 1280

    /**
     * @param centerLat driver's current latitude (WGS84).
     * @param centerLng driver's current longitude (WGS84).
     * @param zoom Mapbox zoom level, 0-22 (roughly 15-16 reads as street-level for a dashboard background).
     * @param widthPx background region's on-screen width in real device pixels (e.g. from
     *   `LayoutCoordinates.size.width` in an `onGloballyPositioned` callback) — clamped to the
     *   API's 1280px limit; Static Images API only serves fixed-size raster images, so this
     *   should be the actual rendered size, not a Compose `dp` value, to avoid a blurry upscale.
     * @param heightPx background region's on-screen height in real device pixels, same clamping.
     * @param retina whether to request the `@2x` (double-density) variant — should match the
     *   device's actual pixel density for a crisp result; costs 4x the PNG bytes over the network.
     */
    fun url(
        centerLat: Double,
        centerLng: Double,
        zoom: Double,
        widthPx: Int,
        heightPx: Int,
        retina: Boolean = true,
        accessToken: String = BuildConfig.MAPBOX_ACCESS_TOKEN,
    ): String {
        val w = widthPx.coerceIn(1, MAX_DIMENSION_PX)
        val h = heightPx.coerceIn(1, MAX_DIMENSION_PX)
        val density = if (retina) "@2x" else ""
        // Mapbox expects up to 5 decimal places on lon/lat/zoom; round rather than truncate so a
        // near-zero coordinate doesn't get formatted in scientific notation by `.toString()`.
        val lon = centerLng.roundTo(5)
        val lat = centerLat.roundTo(5)
        val z = zoom.roundTo(2)
        return "$BASE_URL/$STYLE/static/$lon,$lat,$z/${w}x$h$density?access_token=$accessToken"
    }

    /**
     * A real point-to-point trip map (Trip Detail "real map image" pass, 2026-09-05): a pin at the
     * real pickup coordinate, a pin at the real drop-off coordinate (only when the caller passes
     * one — see below), and, only when [drivenPathPoints] genuinely carries 2+ real recorded
     * fixes, the actual driven path as a Static Images API `path-` overlay. Same REST-only
     * approach as [url] above (see that function's doc for why this project can't use the
     * interactive SDK) — just with the Static Images API's marker/path overlay syntax instead of a
     * bare center/zoom.
     *
     * @param pickupLat/[pickupLng] the trip's real [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.startLat]/`.startLng`. Always drawn — every trip has a real start fix.
     * @param dropoffLat/[dropoffLng] the trip's real `endLat`/`endLng`, or `null` when the trip
     *   has no real end coordinate on record (plenty of historical trips predate this — see
     *   [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.endLat]'s doc). This app's
     *   documented convention of never treating `0.0,0.0` as a real fix applies here too — the
     *   caller is expected to have already screened that out (see
     *   [au.com.threesixty.cabdispatch.ui.screens.tripdetail.TripDetailScreen]'s own map-card
     *   doc), so this function draws whatever non-null pair it's given.
     * @param drivenPathPoints the real accumulated GPS trace for this trip
     *   ([au.com.threesixty.cabdispatch.data.local.entity.TripEntity.gpsTraceJson], decoded), as
     *   (lat, lng) pairs in this app's own coordinate order. **Not** a straight line between
     *   pickup and drop-off — see [au.com.threesixty.cabdispatch.ui.screens.tripdetail.TripDetailScreen]'s
     *   map-card doc for why this function never fabricates one when the trace is empty (which,
     *   as of this pass, is every trip — the live meter's persister doesn't feed real points into
     *   `TripEntity.gpsTraceJson` yet, see [au.com.threesixty.cabdispatch.data.repository.TripRepository.tick]'s
     *   own doc). Fewer than 2 points draws no path at all, just the marker(s).
     * @param widthPx/[heightPx] the on-screen pixel size to request — same clamping/no-blurry-upscale
     *   reasoning as [url].
     *
     * Centering/zoom is Mapbox's own `auto` viewport (fits every marker + the path in frame with a
     * fixed pixel [OVERLAY_PADDING_PX] margin) rather than a center/zoom this app would otherwise
     * have to compute itself from the two coordinates.
     */
    fun tripOverlayUrl(
        pickupLat: Double,
        pickupLng: Double,
        dropoffLat: Double?,
        dropoffLng: Double?,
        drivenPathPoints: List<Pair<Double, Double>> = emptyList(),
        widthPx: Int,
        heightPx: Int,
        retina: Boolean = true,
        accessToken: String = BuildConfig.MAPBOX_ACCESS_TOKEN,
    ): String {
        val w = widthPx.coerceIn(1, MAX_DIMENSION_PX)
        val h = heightPx.coerceIn(1, MAX_DIMENSION_PX)
        val density = if (retina) "@2x" else ""
        val overlays = buildList {
            // Path drawn first so the pickup/drop-off pins layer on top of it, not under it.
            if (drivenPathPoints.size >= 2) {
                add("path-3+${PATH_COLOR_HEX}-0.85(${encodePolyline(drivenPathPoints)})")
            }
            add(pinOverlay(PICKUP_PIN_COLOR_HEX, pickupLat, pickupLng))
            if (dropoffLat != null && dropoffLng != null) {
                add(pinOverlay(DROPOFF_PIN_COLOR_HEX, dropoffLat, dropoffLng))
            }
        }
        val overlayPath = overlays.joinToString(",")
        return "$BASE_URL/$STYLE/static/$overlayPath/auto/${w}x$h$density" +
            "?padding=$OVERLAY_PADDING_PX&access_token=$accessToken"
    }

    private fun pinOverlay(colorHex: String, lat: Double, lng: Double): String {
        val lon = lng.roundTo(5)
        val la = lat.roundTo(5)
        return "pin-s+$colorHex($lon,$la)"
    }

    // Small pixel margin so a fitted marker/path never touches the requested image's edge.
    private const val OVERLAY_PADDING_PX = 32

    // Hex colors (no leading '#', per the Static Images API's overlay syntax) — the exact
    // success/danger/hudAccent hues from CaptainPalette.kt's private DarkTokens set, so this map
    // card reads as one system with the rest of the HUD kit and the dark-v11 STYLE this file
    // always requests (see STYLE's own doc — every static image this class builds is the dark
    // style regardless of the app's light/dark theme setting, so the dark token set's hues are the
    // correct fixed pick here, not a light/dark mismatch). Hand-copied rather than imported since
    // this is a plain data/remote URL builder with no Compose/theme dependency, and DarkTokens
    // itself is private to CaptainPalette.
    private const val PICKUP_PIN_COLOR_HEX = "39E27A" // CaptainPalette's DarkTokens.success
    private const val DROPOFF_PIN_COLOR_HEX = "EF4444" // CaptainPalette's DarkTokens.danger
    private const val PATH_COLOR_HEX = "6E3FF3" // CaptainPalette's DarkTokens.hudAccent

    private fun Double.roundTo(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (this * factor).roundToInt() / factor
    }
}

/**
 * Encodes real (lat, lng) points into the Google/Mapbox [encoded polyline
 * algorithm][https://developers.google.com/maps/documentation/utilities/polylinealgorithm]
 * (precision 5) that the Static Images API's `path-` overlay expects — the exact inverse of
 * [decodePolyline] in `MapboxDirections.kt` (see that function's doc for why this is hand-rolled
 * rather than pulled from the Mapbox SDK utils: same unusable authenticated-Maven-repo
 * constraint). `internal` so [MapboxOverlayEncodeTest] can pin it directly against the same
 * canonical Google reference fixture [PolylineDecodeTest] already uses for the decoder.
 */
internal fun encodePolyline(points: List<Pair<Double, Double>>): String {
    val sb = StringBuilder()
    var prevLatE5 = 0
    var prevLngE5 = 0
    for ((lat, lng) in points) {
        val latE5 = Math.round(lat * 1e5).toInt()
        val lngE5 = Math.round(lng * 1e5).toInt()
        encodePolylineValue(latE5 - prevLatE5, sb)
        encodePolylineValue(lngE5 - prevLngE5, sb)
        prevLatE5 = latE5
        prevLngE5 = lngE5
    }
    return sb.toString()
}

private fun encodePolylineValue(value: Int, sb: StringBuilder) {
    var v = value shl 1
    if (value < 0) v = v.inv()
    while (v >= 0x20) {
        sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
        v = v shr 5
    }
    sb.append((v + 63).toChar())
}

/**
 * Fallback center used only when no real GPS fix is available yet (`fix == null` at the call
 * site) — every caller re-centers on the live fix the moment one lands, this is purely the
 * before-first-fix / GPS-denied placeholder.
 *
 * **Field-testing default (2026-09-04): Karachi, Pakistan** — matches the dashboard's own
 * `FleetMapCanvas.DEFAULT_CENTER` field-testing swap (`dashboard/src/pages/live-map/
 * FleetMapCanvas.tsx`), which this constant's name now lags — swap the two `LAT`/`LNG` values
 * back to Sydney CBD, Town Hall (`-33.8708`, `151.2073`), once Karachi field testing wraps. Only
 * matters before any fix has ever been read; it never affects a route, fare, or anything the
 * driver is actually charged for.
 */
object SydneyCbdFallback {
    const val LAT: Double = 24.8607
    const val LNG: Double = 67.0011
    const val ZOOM: Double = 14.5
}
