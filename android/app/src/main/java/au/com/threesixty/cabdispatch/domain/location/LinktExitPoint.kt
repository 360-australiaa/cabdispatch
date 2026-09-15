package au.com.threesixty.cabdispatch.domain.location

import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.angularDifferenceDeg
import au.com.threesixty.cabdispatch.domain.fare.tollBearingDegrees
import au.com.threesixty.cabdispatch.domain.fare.tollHaversineM
import au.com.threesixty.cabdispatch.domain.location.tunnel.TunnelCorridor

/** How far past a tunnel's far portal its Linkt exit point may be (Homebush Bay Drive is ~1.2 km
 * past the M4 East's Concord Road portal). */
private const val EXIT_POINT_SEARCH_M = 3000.0
private const val EXIT_POINT_BEARING_TOLERANCE_DEG = 60.0

/**
 * The Linkt EXIT point a simulated drive through [corridor] is tolled at: the nearest `entry_exit`
 * exit gantry of the corridor's last road that lies within [EXIT_POINT_SEARCH_M] beyond the far
 * portal and roughly ahead of it (within [EXIT_POINT_BEARING_TOLERANCE_DEG] of the exit heading).
 * Null when the registry has none -- the drive then ends on the plain run-out. Used by the GPS
 * simulator so a real-portal tunnel drive actually passes the point the toll is priced at.
 */
fun linktExitBeyond(corridor: TunnelCorridor, registry: TollRegistrySnapshot): LatLng? {
    val roadId = corridor.roadIds.lastOrNull() ?: return null
    val (endLat, endLng) = corridor.points.last()
    val exitHeading = corridor.bearingAt(corridor.lengthKm)
    return registry.gantries
        .filter { it.tollRoadId == roadId && it.ramp == "exit" }
        .map { it to tollHaversineM(endLat, endLng, it.latitude, it.longitude) }
        .filter { (gantry, distanceM) ->
            val bearing = tollBearingDegrees(endLat, endLng, gantry.latitude, gantry.longitude)
            distanceM <= EXIT_POINT_SEARCH_M &&
                angularDifferenceDeg(exitHeading, bearing) <= EXIT_POINT_BEARING_TOLERANCE_DEG
        }
        .minByOrNull { it.second }
        ?.let { LatLng(it.first.latitude, it.first.longitude) }
}
