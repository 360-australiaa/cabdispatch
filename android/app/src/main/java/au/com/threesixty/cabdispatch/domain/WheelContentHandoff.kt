package au.com.threesixty.cabdispatch.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

/**
 * Hand-off points for the two new wheel-content-adjacent screens ("Trip
 * detail" and "Submit shift confirmation", spec §8 rows 16/19) to receive the
 * data they need without a nav-graph argument — same pattern as
 * [SessionHolder.pendingTrip] (see that class's doc for why: route constants
 * in [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes] carry no
 * nav-graph arguments).
 */

/** S3.5-ish detail screen (row 16): set by the "Trips" wheel content pane when a history row is tapped, read by TripDetailViewModel. */
object TripDetailHandoff {
    private val _pendingClientUuid = MutableStateFlow<String?>(null)
    val pendingClientUuid: StateFlow<String?> = _pendingClientUuid.asStateFlow()

    fun set(clientUuid: String) {
        _pendingClientUuid.value = clientUuid
    }

    fun clear() {
        _pendingClientUuid.value = null
    }
}

/**
 * Snapshot of the totals shown on the "Submit shift confirmation" screen
 * (row 19) — captured at the moment Submit Shift succeeds so that screen
 * doesn't need to re-run [au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel.submitShift]
 * (idempotent-ish but pointless) or spin up a second instance of that
 * ViewModel just to redisplay numbers it already computed once.
 */
data class ShiftSubmissionSummary(
    val tripsCount: Int,
    val kmTotal: BigDecimal,
    val cashTotal: BigDecimal,
    val cardTotal: BigDecimal,
    val pslAccrued: BigDecimal,
    val hoursOnShift: Double,
)

object ShiftSubmissionHandoff {
    private val _pending = MutableStateFlow<ShiftSubmissionSummary?>(null)
    val pending: StateFlow<ShiftSubmissionSummary?> = _pending.asStateFlow()

    fun set(summary: ShiftSubmissionSummary) {
        _pending.value = summary
    }

    fun clear() {
        _pending.value = null
    }
}
