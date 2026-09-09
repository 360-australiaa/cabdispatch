package au.com.threesixty.cabdispatch.domain

/**
 * On-device copies of the two live NSW traffic tables (`GET /v1/traffic/cameras`,
 * `GET /v1/traffic/hazards`) — see [au.com.threesixty.cabdispatch.sync.TrafficCache], the Room
 * cache that populates these, and [au.com.threesixty.cabdispatch.ui.screens.hired.MeterBackdropMap],
 * the one place they are drawn (small, custom, non-interactive markers — informational only,
 * never a fare input). Same role [AirportZone]/[au.com.threesixty.cabdispatch.domain.fare.TollGantryRef]
 * play for their own cached reference data: a plain domain shape with no Room/Retrofit dependency,
 * so a caller never has to think in entity/DTO terms.
 */
data class TrafficCamera(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val direction: String? = null,
    val imageUrl: String? = null,
    val region: String? = null,
)

/** `app.models.traffic.TRAFFIC_HAZARD_CATEGORIES` — the real, fixed set of `category` values the
 * backend guarantees. Not an enum on this DTO/domain type (the backend is the source of truth for
 * what's valid, and a stale build must not crash on a category this list hasn't caught up to
 * yet) — kept here purely so [au.com.threesixty.cabdispatch.ui.screens.hired.MeterBackdropMap]'s
 * icon-colour choice has one documented place to point at rather than a magic string list. */
object TrafficHazardCategories {
    const val INCIDENT = "incident"
    const val ROADWORK = "roadwork"
    const val FLOOD = "flood"
    const val FIRE = "fire"
    const val ALPINE = "alpine"
    const val MAJOR_EVENT = "majorevent"
}

/** One live NSW hazard — incident, roadwork, flood, fire, alpine closure or major-event closure. */
data class TrafficHazard(
    val id: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val headline: String? = null,
    val closureType: String? = null,
    val direction: String? = null,
    val speedLimit: Int? = null,
    val expectedDelayMinutes: Int? = null,
)
