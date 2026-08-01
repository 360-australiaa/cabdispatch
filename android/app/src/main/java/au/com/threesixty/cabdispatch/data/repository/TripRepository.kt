package au.com.threesixty.cabdispatch.data.repository

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao
import au.com.threesixty.cabdispatch.data.local.dao.TripDao
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.UUID

/**
 * Owns the full offline lifecycle of a trip (open -> tick* -> close) for S3
 * HIRED / S4 CLOSE_PAY.
 *
 * The load-bearing rule for this whole class: **every public write method
 * commits to Room synchronously before returning, and never awaits a network
 * call.** [apiService] is accepted per the standard
 * [au.com.threesixty.cabdispatch.data.AppContainer] repository-registration
 * pattern but is deliberately UNUSED for writes here — live network sync is
 * [au.com.threesixty.cabdispatch.sync.SyncWorker]'s job, triggered
 * separately (connectivity callback + periodic backstop, see AppContainer).
 * This is what lets a driver run a full trip — open, tick repeatedly, close
 * — with the device in airplane mode the entire time; the UI only ever reads
 * [observeActiveTrip]/[observeTrip], which are pure Room `Flow`s.
 */
class TripRepository(
    private val tripDao: TripDao,
    private val outboxDao: SyncOutboxDao,
    @Suppress("unused") private val apiService: ApiService,
) {

    fun observeActiveTrip(): Flow<TripEntity?> = tripDao.observeActiveTrip()

    fun observeTrip(clientUuid: String): Flow<TripEntity?> = tripDao.observeTrip(clientUuid)

    fun observeTripsByStatus(status: String): Flow<List<TripEntity>> = tripDao.observeTripsByStatus(status)

    fun observeUnsyncedCount(): Flow<Int> = tripDao.observeUnsyncedCount()

    fun observeOutboxSize(): Flow<Int> = outboxDao.observeOutboxSize()

    suspend fun getTrip(clientUuid: String): TripEntity? = tripDao.getByClientUuid(clientUuid)

    /**
     * Opens a new trip. Generates the stable [TripEntity.clientUuid] that
     * both this row and its [SyncOutboxEntity] counterpart are keyed by for
     * the rest of the trip's life — this is the idempotency key
     * `POST /v1/trips/sync` dedupes on server-side.
     */
    suspend fun openTrip(
        vehicleId: String,
        driverId: String,
        shiftId: String?,
        tariffId: String,
        type: String,
        startLat: Double,
        startLng: Double,
        paymentMethod: String = "cash",
        timeClass: String = "day",
        isPeak: Boolean = false,
        maxi: Boolean = false,
    ): TripEntity {
        val now = System.currentTimeMillis()
        val trip = TripEntity(
            clientUuid = UUID.randomUUID().toString(),
            vehicleId = vehicleId,
            driverId = driverId,
            shiftId = shiftId,
            tariffId = tariffId,
            type = type,
            status = TripStatus.OPEN,
            timeClass = timeClass,
            isPeak = isPeak,
            maxi = maxi,
            startAt = Instant.ofEpochMilli(now).toString(),
            startLat = startLat,
            startLng = startLng,
            paymentMethod = paymentMethod,
            createdAt = now,
            updatedAt = now,
        )
        tripDao.insert(trip)
        upsertOutboxRow(trip, ready = false)
        return trip
    }

    /**
     * Appends a batch of telemetry points and refreshes the cumulative
     * counters. Mirrors the shape of `PATCH /v1/trips/{id}/tick`
     * ([TripTickRequestDto][au.com.threesixty.cabdispatch.data.remote.TripTickRequestDto])
     * so the fare-engine sibling can call this the same way whether the
     * device is online or offline — this method never touches the network
     * either way.
     *
     * [tolls] mirrors the [distanceM]/[movingS]/[waitingS] convention: pass
     * the *cumulative* toll total so far (decimal-as-string), not a delta —
     * this call overwrites, it doesn't append. Nullable/defaulted to `null`
     * (meaning "leave [TripEntity.tolls] as it is") rather than required so
     * existing call sites that don't track tolls keep compiling unchanged.
     *
     * HANDOFF.md fix: this parameter used to not exist, which meant toll
     * chips tapped during S3 (see
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel.addToll])
     * only ever updated the *live, in-memory* UI fare display
     * ([au.com.threesixty.cabdispatch.domain.FareEngineImpl]) — nothing wrote
     * the toll total to this Room row, so
     * [au.com.threesixty.cabdispatch.domain.fare.reconstructFareState] (what
     * S4/S5 actually charge/show) always read [TripEntity.tolls]'s "0"
     * default, silently dropping every toll the driver added. See
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel.doPersistTick]
     * for the call site that now passes the real cumulative total.
     */
    suspend fun tick(
        clientUuid: String,
        newPoints: List<TelemetryPointDto>,
        distanceM: Int,
        movingS: Int,
        waitingS: Int,
        tolls: String? = null,
    ): TripEntity {
        val existing = tripDao.getByClientUuid(clientUuid)
            ?: error("tick() called for unknown trip clientUuid=$clientUuid")
        check(existing.status == TripStatus.OPEN) {
            "tick() called on a trip that isn't open (status=${existing.status}, clientUuid=$clientUuid)"
        }

        val mergedTrace = decodeGpsTrace(existing.gpsTraceJson) + newPoints
        val updated = existing.copy(
            gpsTraceJson = cabDispatchJson.encodeToString(mergedTrace),
            distanceM = distanceM,
            movingS = movingS,
            waitingS = waitingS,
            tolls = tolls ?: existing.tolls,
            updatedAt = System.currentTimeMillis(),
        )
        tripDao.update(updated)
        upsertOutboxRow(updated, ready = false)
        return updated
    }

    /**
     * Finalizes the trip on-device. [deviceTotal] is the fare the on-device
     * fare engine computed — it is what the server independently checks the
     * recomputed total against (±1% variance tolerance, spec B6). This is
     * the point at which the outbox row for this trip becomes
     * [SyncOutboxEntity.readyToSync] — see that class's doc for why not
     * sooner.
     */
    suspend fun closeTrip(
        clientUuid: String,
        endLat: Double,
        endLng: Double,
        deviceTotal: String,
        paymentMethod: String? = null,
        surchargePct: String? = null,
        cleaningFee: String = "0",
        includePsl: Boolean = false,
        receiptRef: String? = null,
    ): TripEntity {
        val existing = tripDao.getByClientUuid(clientUuid)
            ?: error("closeTrip() called for unknown trip clientUuid=$clientUuid")
        check(existing.status == TripStatus.OPEN) {
            "closeTrip() called on a trip that isn't open (status=${existing.status}, clientUuid=$clientUuid)"
        }

        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = TripStatus.CLOSED,
            endAt = Instant.ofEpochMilli(now).toString(),
            endLat = endLat,
            endLng = endLng,
            paymentMethod = paymentMethod ?: existing.paymentMethod,
            surchargePct = surchargePct,
            cleaningFee = cleaningFee,
            includePsl = includePsl,
            receiptRef = receiptRef,
            deviceTotal = deviceTotal,
            updatedAt = now,
        )
        tripDao.update(updated)
        upsertOutboxRow(updated, ready = true)
        return updated
    }

    private suspend fun upsertOutboxRow(trip: TripEntity, ready: Boolean) {
        val entityJson = if (ready) {
            cabDispatchJson.encodeToString(TripSyncItemDto.serializer(), toSyncItemDto(trip))
        } else {
            // Draft snapshot: not sent by SyncWorker (readyToSync=false), kept
            // purely so a crash mid-trip still leaves a recoverable trace of
            // "this trip existed" in the outbox, not just in `trips`.
            cabDispatchJson.encodeToString(TripEntity.serializer(), trip)
        }
        outboxDao.upsert(
            SyncOutboxEntity(
                entityType = OutboxEntityType.TRIP,
                clientUuid = trip.clientUuid,
                entityJson = entityJson,
                readyToSync = ready,
                createdAt = trip.createdAt,
            ),
        )
    }

    private fun toSyncItemDto(trip: TripEntity): TripSyncItemDto {
        checkNotNull(trip.endAt) { "toSyncItemDto() requires a closed trip (endAt is null), clientUuid=${trip.clientUuid}" }
        return TripSyncItemDto(
            clientUuid = trip.clientUuid,
            vehicleId = trip.vehicleId,
            driverId = trip.driverId,
            shiftId = trip.shiftId,
            tariffId = trip.tariffId,
            type = trip.type,
            startAt = trip.startAt,
            endAt = trip.endAt,
            startLat = trip.startLat,
            startLng = trip.startLng,
            endLat = trip.endLat,
            endLng = trip.endLng,
            paymentMethod = trip.paymentMethod,
            timeClass = trip.timeClass,
            isPeak = trip.isPeak,
            maxi = trip.maxi,
            tolls = trip.tolls,
            extras = trip.extras,
            cleaningFee = trip.cleaningFee,
            surchargePct = trip.surchargePct,
            includePsl = trip.includePsl,
            gpsTrace = decodeGpsTrace(trip.gpsTraceJson),
            receiptRef = trip.receiptRef,
            deviceTotal = trip.deviceTotal,
        )
    }

    private fun decodeGpsTrace(json: String): List<TelemetryPointDto> =
        cabDispatchJson.decodeFromString(json)
}
