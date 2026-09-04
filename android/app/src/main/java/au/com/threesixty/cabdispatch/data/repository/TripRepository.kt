package au.com.threesixty.cabdispatch.data.repository

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao
import au.com.threesixty.cabdispatch.data.local.dao.TripDao
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.SplitPaymentEntryDto
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto
import au.com.threesixty.cabdispatch.domain.SessionHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    /**
     * Read-only view of the active trip's persisted GPS trace ([TripEntity.gpsTraceJson], decoded)
     * for the Meter screen's route-polyline backdrop (Meter "game-level" visual pass, 2026-09-03).
     * Purely additive: same Room `Flow` as [observeActiveTrip], same [decodeGpsTrace] this class
     * already uses for sync — no new write path, no behavior change. Emits an empty list when there
     * is no open trip or the trace is still `"[]"`. NOTE (honest gap, not fixed here): the live
     * meter's own persister ([au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s
     * `doPersistTick`) currently calls [tick] with `newPoints = emptyList()`, so this trace only
     * grows once a caller starts feeding real telemetry points into [tick] — see the Meter
     * backdrop's own doc for how it supplements this with live fixes in the meantime.
     */
    fun observeActiveTripGpsTrace(): Flow<List<TelemetryPointDto>> =
        tripDao.observeActiveTrip().map { trip ->
            trip?.let { runCatching { decodeGpsTrace(it.gpsTraceJson) }.getOrDefault(emptyList()) } ?: emptyList()
        }

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
        /** See [TripEntity.passengerCount]'s doc (Point to Point Transport (Fares) Order 2026
         * compliance pass) — defaulted to 1 so every existing call site (a normal metered Start
         * Meter tap) keeps compiling/behaving exactly as before. No UI call site passes a
         * non-default value yet; wiring a passenger-count/wheelchair entry point is a future
         * pass's job, not this one's. */
        passengerCount: Int = 1,
        /** See [TripEntity.wheelchairHiring]'s doc — same "no UI call site sets this yet" note as
         * [passengerCount]. */
        wheelchairHiring: Boolean = false,
        /** See [TripEntity.negotiatedTotal]'s doc — "Set Price" entry point (2026-08-10
         * meter-polish pass). Defaulted null so every existing call site (a normal metered Start
         * Meter tap) keeps compiling/behaving unchanged. */
        negotiatedTotal: String? = null,
    ): TripEntity {
        val now = System.currentTimeMillis()
        // Real address plumbing (History pane columns, Phase C 2026-09-03): reads the same
        // hand-off object au.com.threesixty.cabdispatch.ui.screens.hired.HiredScreen's Trip
        // Details card already reads addresses from (SessionHolder.pendingTrip — see
        // au.com.threesixty.cabdispatch.domain.TripContext.originAddress/.destAddress's doc)
        // rather than adding new parameters here, since this method's sole call site
        // (HiredViewModel.openTripInRoom) was out of this pass's edit scope and already captures
        // that exact TripContext instance as a local before calling here — re-reading the global
        // hand-off at this point yields the identical object, not a race, because nothing clears
        // it between HiredViewModel's init reading it and this suspend call running (the only
        // clear-before-navigation path is the dashboard's Start Meter CANCEL, which never reaches
        // this screen at all). `null` on both exactly when TripContext carried no address (street
        // hail/rank job, a Start Meter/Set Price trip, or the Dispatch wheel-content pane's own
        // accept path — see that doc's known gap) — the History pane must render "—", never a
        // fabricated address, for that case.
        val pendingContext = SessionHolder.pendingTrip.value
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
            passengerCount = passengerCount,
            wheelchairHiring = wheelchairHiring,
            startAt = Instant.ofEpochMilli(now).toString(),
            startLat = startLat,
            startLng = startLng,
            paymentMethod = paymentMethod,
            negotiatedTotal = negotiatedTotal,
            pickupAddress = pendingContext?.originAddress,
            dropoffAddress = pendingContext?.destAddress,
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
        /** Only meaningful when [paymentMethod] resolves to `"voucher"` — see [TripEntity.voucherCode]. */
        voucherCode: String? = null,
        /** Only meaningful when [paymentMethod] resolves to `"account"` — see [TripEntity.accountReference]. */
        accountReference: String? = null,
        /** Only meaningful when [paymentMethod] resolves to `"split_fare"` — see [TripEntity.splitPaymentsJson].
         * JSON-encoded onto that column as-is; see [TripSyncItemDto]'s own doc for the known gap around this
         * not yet reaching the server via [toSyncItemDto]/`POST /v1/trips/sync`. */
        splitPayments: List<SplitPaymentEntryDto>? = null,
        /** Driver tip (Close & Pay "tips" pass) — see [TripEntity.tip]'s doc. `null` = no tip
         * recorded for this close. */
        tip: String? = null,
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
            voucherCode = voucherCode,
            accountReference = accountReference,
            splitPaymentsJson = splitPayments?.let { cabDispatchJson.encodeToString(it) },
            tip = tip,
            updatedAt = now,
        )
        tripDao.update(updated)
        upsertOutboxRow(updated, ready = true)
        return updated
    }

    /**
     * Corrects a trip's declared passenger count mid-trip (miscounts happen — see
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel.updatePassengerCount]'s doc).
     * Point to Point Transport (Fares) Order 2026 UI-wiring pass: without this, a mid-trip
     * correction would only ever update the live in-memory meter display
     * ([au.com.threesixty.cabdispatch.domain.FareEngineImpl]) and never reach this persisted
     * [TripEntity] row — meaning [au.com.threesixty.cabdispatch.domain.fare.TripFareReconstruction]
     * (what Close & Pay actually bills from) would silently keep billing off the ORIGINAL
     * passenger count the driver already corrected on-screen. New method, not a change to [tick]'s
     * existing signature/behavior, per this pass's constraint not to touch other call sites.
     */
    suspend fun updatePassengerCount(clientUuid: String, passengerCount: Int): TripEntity {
        val existing = tripDao.getByClientUuid(clientUuid)
            ?: error("updatePassengerCount() called for unknown trip clientUuid=$clientUuid")
        check(existing.status == TripStatus.OPEN) {
            "updatePassengerCount() called on a trip that isn't open (status=${existing.status}, clientUuid=$clientUuid)"
        }
        val updated = existing.copy(passengerCount = passengerCount, updatedAt = System.currentTimeMillis())
        tripDao.update(updated)
        upsertOutboxRow(updated, ready = false)
        return updated
    }

    /**
     * Records the drop-off the driver picked in the meter screen's navigator
     * ([au.com.threesixty.cabdispatch.ui.screens.hired.MeterNavViewModel.selectDestination]) on
     * the open trip: [TripEntity.dropoffAddress] (a real geocoded `place_name`, never a guess —
     * the column that History/Trip Details render and that was never populated before this
     * pass except from a dispatch offer's `destAddress`) plus [TripEntity.endLat]/[endLng] as
     * the *intended* end point. [closeTrip] overwrites the two coordinates with the real end fix
     * at close time (today S4 still passes the start point there — its own standing TODO), so
     * they are only ever "where we're heading" while the trip is open.
     *
     * Pure metadata: none of the fare-reconstruction inputs (`distanceM`/`movingS`/`waitingS`/
     * `tolls`/...) are touched, so this can never move the fare. Same read-copy-write shape as
     * [updatePassengerCount]; the outbox draft is refreshed (not marked ready) for the same
     * crash-recovery reason [tick] does it.
     */
    suspend fun updateDropoff(clientUuid: String, address: String, lat: Double, lng: Double): TripEntity {
        val existing = tripDao.getByClientUuid(clientUuid)
            ?: error("updateDropoff() called for unknown trip clientUuid=$clientUuid")
        check(existing.status == TripStatus.OPEN) {
            "updateDropoff() called on a trip that isn't open (status=${existing.status}, clientUuid=$clientUuid)"
        }
        val updated = existing.copy(
            dropoffAddress = address,
            endLat = lat,
            endLng = lng,
            updatedAt = System.currentTimeMillis(),
        )
        tripDao.update(updated)
        upsertOutboxRow(updated, ready = false)
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
            // See TripSyncItemDto's own doc: the backend's TripSyncItem schema doesn't declare
            // these three fields yet, so they round-trip in the outgoing JSON but are silently
            // ignored server-side today. Sent anyway (forward-compatible, harmless) rather than
            // omitted, so a future backend extension needs no Android-side change.
            voucherCode = trip.voucherCode,
            accountReference = trip.accountReference,
            splitPayments = trip.splitPaymentsJson?.let { cabDispatchJson.decodeFromString<List<SplitPaymentEntryDto>>(it) },
            negotiatedTotal = trip.negotiatedTotal,
            timeClass = trip.timeClass,
            isPeak = trip.isPeak,
            maxi = trip.maxi,
            passengerCount = trip.passengerCount,
            wheelchairHiring = trip.wheelchairHiring,
            tolls = trip.tolls,
            // (kept for clarity: passengerCount/wheelchairHiring above round-trip to the wire even
            // though no UI call site sets them to a non-default value yet, same forward-compatible
            // "send it anyway" convention this file already uses for voucherCode/accountReference/
            // splitPayments above.)
            extras = trip.extras,
            cleaningFee = trip.cleaningFee,
            surchargePct = trip.surchargePct,
            includePsl = trip.includePsl,
            gpsTrace = decodeGpsTrace(trip.gpsTraceJson),
            receiptRef = trip.receiptRef,
            deviceTotal = trip.deviceTotal,
            // See TripEntity.tip's doc — a tip is never folded into deviceTotal above (the
            // fare-engine total), it round-trips as its own field, same "send it anyway,
            // forward-compatible" convention this method already uses for voucherCode/
            // accountReference/splitPayments.
            tipAmount = trip.tip,
        )
    }

    private fun decodeGpsTrace(json: String): List<TelemetryPointDto> =
        cabDispatchJson.decodeFromString(json)
}
