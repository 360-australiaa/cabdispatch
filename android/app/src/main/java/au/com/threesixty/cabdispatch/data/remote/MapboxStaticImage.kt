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

    private fun Double.roundTo(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (this * factor).roundToInt() / factor
    }
}

/**
 * Fixed fallback center — Sydney CBD (Town Hall) — used when no real driver position is
 * available. [au.com.threesixty.cabdispatch.domain.SpeedSource] (the only location-adjacent data
 * source actually wired in this app, see `AppContainer.speedSource`) exposes speed only, no
 * lat/lng — GPS position is a documented, still-open gap (`HANDOFF.md`: "GPS is stubbed, not
 * real"). Rather than fabricate a position source that doesn't exist, the dashboard map centers
 * here until a real `FusedLocationProviderClient`-backed position lands (see that same doc's
 * "Medium priority" section) — at which point this constant should be replaced by the live fix,
 * not deleted (keep it as the offline/no-fix fallback).
 */
object SydneyCbdFallback {
    const val LAT: Double = -33.8708
    const val LNG: Double = 151.2073
    const val ZOOM: Double = 14.5
}
