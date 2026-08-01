package au.com.threesixty.cabdispatch.ui.screens.shiftreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.ShiftEntity
import au.com.threesixty.cabdispatch.data.local.entity.ShiftStatus
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.SessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

data class ShiftReportUiState(
    val loading: Boolean = true,
    val shiftClientUuid: String? = null,
    val driverId: String? = null,
    val vehicleId: String? = null,
    val trips: List<TripEntity> = emptyList(),
    val tripsCount: Int = 0,
    val kmTotal: BigDecimal = BigDecimal.ZERO,
    val cashTotal: BigDecimal = BigDecimal.ZERO,
    val cardTotal: BigDecimal = BigDecimal.ZERO,
    val pslAccrued: BigDecimal = BigDecimal.ZERO,
    val unsyncedTripsCount: Int = 0,
    val submitting: Boolean = false,
    val submitError: String? = null,
    val submitted: Boolean = false,
) {
    val totalTakings: BigDecimal get() = cashTotal + cardTotal
}

/**
 * S5 — Shift report (spec B5: "totals, PSL accrued, reconciliation,
 * submit"). Sourced entirely from local Room aggregates over
 * [TripEntity] rows for the active shift — works fully offline, per spec B7.
 *
 * TODO(S1/session sibling agent):
 * [au.com.threesixty.cabdispatch.domain.RemoteBackedShiftRepository] (used by
 * S1 to open a shift) currently only calls the live `POST /v1/shifts/start`
 * endpoint and never writes a [ShiftEntity] row — so
 * [au.com.threesixty.cabdispatch.data.local.dao.ShiftDao.observeActiveShift]
 * is unusable today (always empty). This screen therefore drives entirely
 * off [SessionHolder.session]'s `shiftId` + a direct trips-for-shift query,
 * and only touches [au.com.threesixty.cabdispatch.data.local.dao.ShiftDao]
 * at submit time (read-then-insert-or-update — see [submitShift], since it
 * cannot assume a row already exists). Once S1's shift-open path is upgraded
 * to the same Room-first pattern as
 * [au.com.threesixty.cabdispatch.data.repository.TripRepository], simplify
 * this to observe `ShiftDao.observeActiveShift()` directly, the way S4 does
 * for trips.
 *
 * TODO(sync-engine sibling agent): [au.com.threesixty.cabdispatch.sync.OutboxDrainer]
 * currently only drains `entityType == OutboxEntityType.TRIP` rows — the
 * [OutboxEntityType.SHIFT] row [submitShift] queues below will sit
 * unprocessed in the outbox until the drainer is extended to handle it too.
 * Queuing it now (rather than not at all) means no data is lost once that
 * lands; it'll just be picked up retroactively.
 */
class ShiftReportViewModel : ViewModel() {

    private val tripDao = AppContainer.tripDao
    private val shiftDao = AppContainer.shiftDao
    private val syncOutboxDao = AppContainer.syncOutboxDao
    private val tariffDao = AppContainer.tariffDao

    private val _uiState = MutableStateFlow(ShiftReportUiState())
    val uiState: StateFlow<ShiftReportUiState> = _uiState.asStateFlow()

    /** tariffId -> pslAmount, so repeated trips on the same tariff don't re-hit Room/decode per trip. */
    private val pslCache = mutableMapOf<String, BigDecimal>()

    init {
        val session = SessionHolder.session.value
        val shiftId = session?.shiftId
        if (shiftId == null) {
            _uiState.value = ShiftReportUiState(loading = false)
        } else {
            _uiState.value = ShiftReportUiState(
                loading = true,
                shiftClientUuid = shiftId,
                driverId = session.driverId,
                vehicleId = session.vehicleId,
            )
            viewModelScope.launch {
                tripDao.observeTripsForShift(shiftId).collect { trips -> recomputeAggregates(trips) }
            }
        }
    }

    private suspend fun recomputeAggregates(trips: List<TripEntity>) {
        // Only closed/synced trips count towards shift totals — an OPEN trip
        // hasn't got a final deviceTotal yet (still "0", see TripEntity doc).
        val finalized = trips.filter { it.status != TripStatus.OPEN }

        val kmTotal = finalized.fold(BigDecimal.ZERO) { acc, t ->
            acc + BigDecimal(t.distanceM).divide(BigDecimal(1000), 3, RoundingMode.HALF_UP)
        }
        val cashTotal = finalized.filter { it.paymentMethod == "cash" }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() }
        val cardTotal = finalized.filter { it.paymentMethod == "card" }
            .fold(BigDecimal.ZERO) { acc, t -> acc + t.deviceTotal.toBigDecimalOrZero() }

        var psl = BigDecimal.ZERO
        for (trip in finalized) {
            if (trip.includePsl) psl += pslAmountFor(trip.tariffId)
        }

        val unsynced = trips.count { it.status != TripStatus.SYNCED }

        _uiState.update {
            it.copy(
                loading = false,
                trips = finalized,
                tripsCount = finalized.size,
                kmTotal = kmTotal,
                cashTotal = cashTotal,
                cardTotal = cardTotal,
                pslAccrued = psl,
                unsyncedTripsCount = unsynced,
            )
        }
    }

    private suspend fun pslAmountFor(tariffId: String): BigDecimal {
        pslCache[tariffId]?.let { return it }
        val entity = tariffDao.getById(tariffId) ?: return BigDecimal.ZERO
        val dto = runCatching { cabDispatchJson.decodeFromString<TariffDto>(entity.rawJson) }.getOrNull()
            ?: return BigDecimal.ZERO
        val amount = dto.pslAmount.toBigDecimalOrZero()
        pslCache[tariffId] = amount
        return amount
    }

    fun submitShift() {
        val state = _uiState.value
        val shiftId = state.shiftClientUuid ?: return
        if (state.submitting) return
        _uiState.update { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            runCatching { persistShiftClose(shiftId, state) }
                .onSuccess { _uiState.update { it.copy(submitting = false, submitted = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(submitting = false, submitError = e.message ?: "Could not submit shift") }
                }
        }
    }

    private suspend fun persistShiftClose(shiftId: String, state: ShiftReportUiState) {
        val now = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(now).toString()
        val existing = shiftDao.getByClientUuid(shiftId)

        val updated = if (existing != null) {
            existing.copy(
                status = ShiftStatus.CLOSED,
                endAt = nowIso,
                tripsCount = state.tripsCount,
                kmTotal = state.kmTotal.money(),
                cashTotal = state.cashTotal.money(),
                cardTotal = state.cardTotal.money(),
                pslOwed = state.pslAccrued.money(),
                reconciled = true,
                updatedAt = now,
            )
        } else {
            // First-ever Room write for this shift — see class doc TODO.
            // startAt is approximated from the earliest trip in the shift
            // (ISO-8601 strings sort lexicographically the same as
            // chronologically) since S1 never persisted a real one.
            ShiftEntity(
                clientUuid = shiftId,
                serverId = shiftId.takeUnless { it.startsWith("local-") },
                driverId = state.driverId ?: "",
                vehicleId = state.vehicleId ?: "",
                status = ShiftStatus.CLOSED,
                startAt = state.trips.minOfOrNull { it.startAt } ?: nowIso,
                endAt = nowIso,
                tripsCount = state.tripsCount,
                kmTotal = state.kmTotal.money(),
                cashTotal = state.cashTotal.money(),
                cardTotal = state.cardTotal.money(),
                pslOwed = state.pslAccrued.money(),
                reconciled = true,
                createdAt = now,
                updatedAt = now,
            )
        }
        if (existing != null) shiftDao.update(updated) else shiftDao.insert(updated)

        syncOutboxDao.upsert(
            SyncOutboxEntity(
                entityType = OutboxEntityType.SHIFT,
                clientUuid = shiftId,
                entityJson = cabDispatchJson.encodeToString(
                    ShiftSyncPayload.serializer(),
                    ShiftSyncPayload(
                        clientUuid = shiftId,
                        serverId = updated.serverId,
                        endAt = nowIso,
                        pslOwed = state.pslAccrued.money(),
                        reconciled = true,
                    ),
                ),
                readyToSync = true,
                createdAt = now,
            ),
        )
    }
}

/**
 * Internal outbox payload shape for a shift-end sync — mirrors
 * [au.com.threesixty.cabdispatch.data.remote.ShiftEndDto]'s fields plus the
 * identifiers a future shift-sync drainer needs. Not a wire DTO in
 * ApiService.kt because `POST /v1/shifts/{shiftId}/end` addresses the shift
 * by its *server* id in the URL path, not a body field — whoever extends
 * OutboxDrainer for shifts will need `serverId` (falling back to some
 * resolve-by-clientUuid step if it's null, i.e. the shift was opened
 * entirely offline) to know which URL to call.
 */
@Serializable
data class ShiftSyncPayload(
    val clientUuid: String,
    val serverId: String?,
    val endAt: String,
    val pslOwed: String,
    val reconciled: Boolean,
)

private fun String.toBigDecimalOrZero(): BigDecimal = runCatching { BigDecimal(this) }.getOrDefault(BigDecimal.ZERO)
private fun BigDecimal.money(): String = this.setScale(2, RoundingMode.HALF_UP).toPlainString()
