// MatchingDeclarationName: same file shape as UpcomingTollAdvisor.kt / UpcomingHazardAdvisor.kt --
// one result type plus the pure function that produces it, named for what the file is FOR.
@file:Suppress("MatchingDeclarationName")

package au.com.threesixty.cabdispatch.domain.fare

import au.com.threesixty.cabdispatch.domain.SpeedCamera
import au.com.threesixty.cabdispatch.domain.SpeedCameraType

/**
 * "Speed camera ahead" advisory -- the same pure, Android-free shape as [upcomingToll] and
 * [upcomingHazard] in this package (owner request, 2026-09-14). A camera counts as ahead only if
 * it is within [SPEED_CAMERA_LOOKAHEAD_M] of the vehicle AND roughly in the direction of travel
 * ([SPEED_CAMERA_LOOKAHEAD_BEARING_TOLERANCE_DEG] either side of a real GPS heading; `null`
 * heading -> `null` answer, never a guess while parked). For a camera published as a line (an
 * average-speed tunnel section) every vertex is a candidate, so the warning also fires for a
 * vehicle that joins the enforced section part-way along, not only at its first point.
 *
 * **Why 700 m.** A warning is only useful with time to check the speedo and ease off: at 80 km/h
 * (22 m/s) 700 m is ~30 s of notice; at 60 km/h ~40 s. The 2 km the toll/hazard chips use would
 * have the chip showing for most of a suburban drive in camera-dense Sydney, which teaches a
 * driver to ignore it -- exactly the outcome a camera warning must avoid.
 */
data class UpcomingSpeedCamera(
    val id: Int,
    val name: String,
    val type: SpeedCameraType,
    val schoolZone: Boolean,
    val tunnel: Boolean,
    val distanceAheadM: Double,
)

const val SPEED_CAMERA_LOOKAHEAD_M = 700.0

const val SPEED_CAMERA_LOOKAHEAD_BEARING_TOLERANCE_DEG = 60.0

// Same accepted shape (and the same three suppressions) as upcomingToll/upcomingHazard: the two
// tunables are test seams, the guard-clause returns and the two `continue`s ARE the scan.
@Suppress("LongParameterList", "ReturnCount", "LoopWithTooManyJumpStatements")
fun upcomingSpeedCamera(
    cameras: List<SpeedCamera>,
    lat: Double,
    lng: Double,
    headingDeg: Double?,
    lookaheadM: Double = SPEED_CAMERA_LOOKAHEAD_M,
    bearingToleranceDeg: Double = SPEED_CAMERA_LOOKAHEAD_BEARING_TOLERANCE_DEG,
): UpcomingSpeedCamera? {
    if (headingDeg == null) return null // no trusted direction of travel -- never guess "ahead"

    var best: SpeedCamera? = null
    var bestDistanceM = Double.MAX_VALUE
    for (camera in cameras) {
        val candidates = camera.path ?: listOf(camera.latitude to camera.longitude)
        for ((pointLat, pointLng) in candidates) {
            val distanceM = tollHaversineM(lat, lng, pointLat, pointLng)
            if (distanceM > lookaheadM || distanceM >= bestDistanceM) continue
            val bearingToCamera = tollBearingDegrees(lat, lng, pointLat, pointLng)
            if (angularDifferenceDeg(headingDeg, bearingToCamera) > bearingToleranceDeg) continue
            best = camera
            bestDistanceM = distanceM
        }
    }

    val camera = best ?: return null
    return UpcomingSpeedCamera(
        id = camera.id,
        name = camera.name,
        type = camera.type,
        schoolZone = camera.schoolZone,
        tunnel = camera.tunnel,
        distanceAheadM = bestDistanceM,
    )
}
