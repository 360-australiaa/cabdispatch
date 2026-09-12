package au.com.threesixty.cabdispatch.domain.location.roadpath

import au.com.threesixty.cabdispatch.domain.LocationFix

/**
 * Tries each of [sources] in order, highest confidence first (task 5, W3 plan): an active nav
 * route (the real road ahead, if the driver picked one), then the toll-registry corridor chain
 * (real, surveyed gantry geometry), then the offline map style's own road layer (task 4's spike --
 * see [StyleRoadPath]'s own doc for why this is the least certain of the three, and UNVERIFIED on
 * a real device). The first source whose [RoadPathSource.pathAt] returns non-null wins outright
 * for this blackout -- there is no blending/averaging across sources, matching
 * [au.com.threesixty.cabdispatch.domain.location.inertial.BlackoutReconciler]'s own "a road-path
 * match is always trusted completely over the estimate" rule one level up: two disagreeing road
 * paths would not make either one more true, so the highest-confidence one that actually matched
 * is simply used.
 *
 * Implements [RoadPathSource] itself so
 * [au.com.threesixty.cabdispatch.domain.FareEngineImpl] only ever needs to know about ONE
 * road-path seam -- exactly as
 * [au.com.threesixty.cabdispatch.domain.location.inertial.InertialBillingSource] is
 * `FareEngineImpl`'s one seam onto three separate inertial components.
 */
class CompositeRoadPath(private val sources: List<RoadPathSource>) : RoadPathSource {

    /** Convenience constructor matching the plan's own named three sources -- any of the three may
     * be `null` (not wired, or genuinely unavailable in this build) without the caller needing to
     * build a `listOfNotNull` itself; see `data/AppContainer.kt`'s own wiring doc for why, today,
     * [navRoute] is passed `null`. */
    constructor(
        navRoute: RoadPathSource?,
        corridor: RoadPathSource?,
        style: RoadPathSource?,
    ) : this(listOfNotNull(navRoute, corridor, style))

    override fun pathAt(entryFix: LocationFix): RoadPath? {
        for (source in sources) {
            val path = source.pathAt(entryFix)
            if (path != null) return path
        }
        return null
    }
}
