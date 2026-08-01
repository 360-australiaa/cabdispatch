package au.com.threesixty.cabdispatch.domain

import au.com.threesixty.cabdispatch.data.remote.JobDto
import au.com.threesixty.cabdispatch.data.remote.JobOfferDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A job paired with *this* driver's own pending offer against it — the only pairing that's ever
 * actionable client-side (a job can have several drivers' offers outstanding at once, but a
 * driver only ever sees/acts on their own, see [JobsRepository] doc). */
data class PendingJobOffer(val job: JobDto, val offer: JobOfferDto)

/**
 * Hand-off point for "Available Trips" (spec §8 rows 11-12): the wheel-slot job-offer list ->
 * full-screen accept/decline detail screen. Same no-nav-graph-args convention
 * [SessionHolder.pendingTrip] already uses (see that class's doc for why: route constants in
 * [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes] carry no nav-graph arguments) —
 * and the same shape as [TripDetailHandoff]/[ShiftSubmissionHandoff] (a separate sibling agent's
 * near-identical pattern for the Trips/Shift detail screens, landed in parallel with this file;
 * kept as its own object here rather than folded into that file to avoid a same-file edit race
 * with that agent's pass).
 *
 * Carries the whole [JobDto]/[JobOfferDto] pair rather than just an id, unlike [TripDetailHandoff]
 * (which re-reads a durable Room row by id): a job offer is a ~20s-expiry, network-only,
 * non-persisted thing — a second network round-trip on every list-row tap would burn into that
 * expiry window for no benefit, since the list already has the exact data in memory.
 */
object JobOfferHandoff {
    private val _pending = MutableStateFlow<PendingJobOffer?>(null)
    val pending: StateFlow<PendingJobOffer?> = _pending.asStateFlow()

    fun set(job: JobDto, offer: JobOfferDto) {
        _pending.value = PendingJobOffer(job, offer)
    }

    fun clear() {
        _pending.value = null
    }
}
