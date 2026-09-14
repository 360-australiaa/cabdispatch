package au.com.threesixty.cabdispatch.domain

/**
 * One NSW speed-enforcement camera from Transport for NSW's published "Speed camera locations"
 * map (owner request, 2026-09-14: "get all the pin points of speeding cameras, bind it to our
 * mapbox, so when driver go around there they will see notification, speeding camera ahead").
 * Bundled with the app as `assets/nsw_speed_cameras.json` (see
 * [au.com.threesixty.cabdispatch.data.SpeedCameraRegistry]) rather than fetched: the list changes
 * a few times a year, the tablet must have it in a tunnel with no signal, and a fixed camera that
 * silently disappears from the map because a request failed is worse than one that is a month
 * stale. Informational only -- never a fare input.
 *
 * [path] is non-null only for the average-speed / tunnel cameras TfNSW publishes as a line (the
 * enforced length of road), listed entry-first in the direction of travel; [latitude]/[longitude]
 * are then that line's first point, the place a driver should be warned about.
 */
data class SpeedCamera(
    val id: Int,
    val name: String,
    val type: SpeedCameraType,
    val latitude: Double,
    val longitude: Double,
    val summary: String? = null,
    val schoolZone: Boolean = false,
    val tunnel: Boolean = false,
    val path: List<Pair<Double, Double>>? = null,
)

enum class SpeedCameraType {
    /** TfNSW "Fixed speed camera" -- including the point-to-point / tunnel average-speed sites. */
    FIXED,

    /** TfNSW "Red-light speed camera" -- an intersection camera that enforces both. */
    RED_LIGHT_SPEED,
}
