package au.com.threesixty.cabdispatch.data.repository

import au.com.threesixty.cabdispatch.data.BatteryStatsCounters
import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao
import au.com.threesixty.cabdispatch.data.local.dao.TripBlackoutSegmentDao
import au.com.threesixty.cabdispatch.data.local.dao.TripDao
import au.com.threesixty.cabdispatch.data.local.dao.TripTracePointDao
import au.com.threesixty.cabdispatch.data.local.entity.OutboxEntityType
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus
import au.com.threesixty.cabdispatch.data.local.entity.TripTracePointEntity
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.SplitPaymentEntryDto
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.data.remote.GpsBlackoutSegmentDto
import au.com.threesixty.cabdispatch.data.remote.TripSyncItemDto
import au.com.threesixty.cabdispatch.domain.AirportAccessFeeRecord
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.location.GpsSimulator
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
    /** GPS-blackout audit trail (G3, W1, 2026-09-12) -- read once at close time to populate
     * [TripSyncItemDto.gpsBlackoutSegments]. See [TripBlackoutSegmentDao]'s own doc. */
    private val blackoutSegmentDao: TripBlackoutSegmentDao,
    /** Append-only GPS trace storage (G8, W4, 2026-09-12 optimisation plan §3) -- replaces the old
     * "decode/append/re-encode the whole [TripEntity.gpsTraceJson] blob on every tick" pattern.
     * See [TripTracePointDao]'s own doc, and [tick]/[closeTrip]/[observeActiveTripGpsTrace] below
     * for exactly how it is written to, materialised and cleaned up. */
    private val tracePointDao: TripTracePointDao,
) {

    fun observeActiveTrip(): Flow<TripEntity?> = tripDao.observeActiveTrip()

    /**
     * In-memory, per-trip write-behind buffer for [tick]'s trace points -- see [bufferTracePoints]/
     * [flushTraceBuffer] for the batching itself, and [TripTracePointEntity]'s doc for why this
     * replaced the old full-blob rewrite. Keyed by `clientUuid`; there is realistically at most one
     * entry (this app allows only one OPEN trip at a time), but nothing here assumes that.
     *
     * Deliberately plain, unsynchronised, in-memory `MutableMap`s: [TripRepository] is a single
     * [au.com.threesixty.cabdispatch.data.AppContainer] singleton and every write call
     * ([tick]/[closeTrip]) for a given trip is driven from the SAME serial coroutine
     * ([au.com.threesixty.cabdispatch.domain.MeterController]'s one `persistJob`, see that class's
     * doc) -- there is no concurrent writer to guard against. A process death loses whatever sits
     * in these buffers unflushed (at most [TRACE_BATCH_POINT_COUNT] points), which is the honest,
     * documented cost of batching rather than writing every tick: the trip's own
     * distance/moving/waiting counters (what actually decides the fare) are still written to
     * `trips` on every single [tick] call, completely independent of this buffer -- only the
     * route-trace evidence has this bounded, small window of loss.
     */
    private val traceBuffers = mutableMapOf<String, MutableList<TelemetryPointDto>>()
    private val traceBufferFlushedAtMs = mutableMapOf<String, Long>()

    /** Next [TripTracePointEntity.seq] to assign for a trip, seeded from
     * [TripTracePointDao.maxSeq] the first time this process touches that trip (so a process
     * restart mid-trip resumes numbering after whatever was already flushed, never colliding with
     * it) and cached afterwards to avoid a query per flush. */
    private val traceSeqCounters = mutableMapOf<String, Int>()

    /**
     * Read-only view of the active trip's GPS trace, decoded, for the Meter screen's
     * route-polyline backdrop (Meter "game-level" visual pass, 2026-09-03). Reads
     * [tracePointDao] rather than [TripEntity.gpsTraceJson] (G8, W4 §3): that column is no longer
     * kept live-updated tick by tick (see [tick]'s own doc) -- it is materialised once, at
     * [closeTrip] time. Emits an empty list when there is no open trip.
     *
     * Driven off the same [au.com.threesixty.cabdispatch.data.local.dao.TripDao.observeActiveTrip]
     * `Flow` [observeActiveTrip] already uses, which Room re-emits on every [tick]'s
     * `tripDao.update` (the counters change every tick regardless of trace buffering) -- so this
     * still refreshes about once a second during a live trip. The one honest trade-off:
     * a point still sitting in [traceBuffers] (up to [TRACE_BATCH_POINT_COUNT] points / a few
     * seconds behind, see [bufferTracePoints]) has not reached [tracePointDao] yet and so is not
     * yet reflected here -- a bounded, cosmetic lag on a route-polyline overlay, not a correctness
     * issue for anything fare-affecting (which never reads this Flow at all).
     */
    fun observeActiveTripGpsTrace(): Flow<List<TelemetryPointDto>> =
        tripDao.observeActiveTrip().map { trip ->
            if (trip == null) {
                emptyList()
            } else {
                runCatching { tracePointDao.forTrip(trip.clientUuid).map(TripTracePointEntity::toTelemetryPointDto) }
                    .getOrDefault(emptyList())
            }
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
        /** Minted by the caller, not here, as of 2026-09-06 — see
         * [au.com.threesixty.cabdispatch.domain.SessionHolder.liveTripClientUuid]'s doc for the
         * real race this closes: [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel.openTripInRoom]
         * needs the id BEFORE this suspend function's first DB write (which is when
         * [observeActiveTrip]'s Flow can first observe the new row), so it can mark the trip
         * "live" for this process strictly before that write happens, closing the window rather
         * than racing it. Defaulted so this stays source-compatible with any future caller that
         * doesn't care. */
        clientUuid: String = UUID.randomUUID().toString(),
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
        /** See [TripEntity.airportRankRequestedMaxi]'s doc (maxi-at-airport-rank fare-integrity
         * fix, 2026-09-05). Defaulted false so every existing call site keeps compiling/behaving
         * exactly as before; [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel]'s
         * `openTripInRoom` is the one real call site that now passes a non-default value through,
         * mirroring the same flag it already passes to `fareEngine.startTrip`. */
        airportRankRequestedMaxi: Boolean = false,
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
            clientUuid = clientUuid,
            vehicleId = vehicleId,
            driverId = driverId,
            shiftId = shiftId,
            tariffId = tariffId,
            type = type,
            // Read from the simulator's own live state rather than taken as a parameter, so no
            // screen can open a trip on fabricated GPS and forget to say so -- see
            // TripEntity.simulated for why an unflagged simulated trip is a real problem and not
            // just untidy.
            simulated = GpsSimulator.isSimulating(),
            status = TripStatus.OPEN,
            timeClass = timeClass,
            isPeak = isPeak,
            maxi = maxi,
            passengerCount = passengerCount,
            wheelchairHiring = wheelchairHiring,
            airportRankRequestedMaxi = airportRankRequestedMaxi,
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
     *
     * ### [newPoints] no longer touches [TripEntity.gpsTraceJson] directly (G8, W4 §3, 2026-09-12)
     * This used to decode the trip's ENTIRE accumulated trace, append [newPoints], and re-encode
     * the whole thing back into one growing `TEXT` column, on every single call — for a 2-hour
     * hire ticking at 1 Hz that is ~7,000 points decoded and ~7,000 points re-encoded on the LAST
     * tick alone, and the sum of every tick before it: O(n^2) total work and Room writes over the
     * trip's life, all landing on the main persistence path a driver's fare depends on. [newPoints]
     * now goes to [bufferTracePoints] instead — an append-only [tracePointDao] write, batched every
     * 5s or 10 points (see that method's doc) — and [TripEntity.gpsTraceJson] itself is only
     * materialised once, at [closeTrip] time. The counters below ([distanceM]/[movingS]/[waitingS]/
     * the accrued charges) are UNCHANGED: they are still written to `trips` on every call, exactly
     * as before — only the trace storage shape changed.
     */
    suspend fun tick(
        clientUuid: String,
        newPoints: List<TelemetryPointDto>,
        distanceM: Int,
        movingS: Int,
        waitingS: Int,
        tolls: String? = null,
        /** Automatic NSW toll-road detection audit trail (roadId -> current charged amount) — see
         * [TripEntity.autoTolledRoadsJson]'s doc. `null` (the default) leaves the existing value
         * untouched, same convention as [tolls] itself — every pre-existing call site that never
         * names this keeps compiling/behaving exactly as before (no auto-toll detection wired). */
        autoTolledRoads: Map<String, String>? = null,
        /** Real toll-road ids crossed this trip the registry couldn't auto-price — see
         * [TripEntity.unpricedTollRoadIdsJson]'s doc. Same "`null` = leave untouched" convention. */
        unpricedTollRoadIds: List<String>? = null,
        /** The airport access fee on the ledger, if any — see [TripEntity.airportAccessFeeJson]'s
         * doc. Same "`null` = leave the existing value untouched" convention: the fee is applied
         * once at pickup and never withdrawn, so a ledger that has it keeps re-sending the same
         * record and a ledger that never had it simply never writes the column. */
        airportAccessFee: AirportAccessFeeRecord? = null,
        /**
         * The distance/waiting charges the live meter has accrued so far, as decimal strings --
         * F9 (architecture audit §2.2). Same "`null` = leave the existing value untouched"
         * convention as [tolls] above, so every pre-existing call site keeps behaving exactly as
         * before. See [TripEntity.accruedDistanceCharge]'s doc for why persisting the charge beats
         * re-deriving it from [distanceM]'s integer metres at close time.
         */
        accruedDistanceCharge: String? = null,
        accruedWaitingCharge: String? = null,
    ): TripEntity {
        val existing = tripDao.getByClientUuid(clientUuid)
            ?: error("tick() called for unknown trip clientUuid=$clientUuid")
        check(existing.status == TripStatus.OPEN) {
            "tick() called on a trip that isn't open (status=${existing.status}, clientUuid=$clientUuid)"
        }

        bufferTracePoints(clientUuid, newPoints)

        val updated = existing.copy(
            distanceM = distanceM,
            movingS = movingS,
            waitingS = waitingS,
            accruedDistanceCharge = accruedDistanceCharge ?: existing.accruedDistanceCharge,
            accruedWaitingCharge = accruedWaitingCharge ?: existing.accruedWaitingCharge,
            tolls = tolls ?: existing.tolls,
            autoTolledRoadsJson = autoTolledRoads?.let { cabDispatchJson.encodeToString(it) } ?: existing.autoTolledRoadsJson,
            unpricedTollRoadIdsJson = unpricedTollRoadIds?.let { cabDispatchJson.encodeToString(it) } ?: existing.unpricedTollRoadIdsJson,
            airportAccessFeeJson = airportAccessFee?.let { cabDispatchJson.encodeToString(it) } ?: existing.airportAccessFeeJson,
            updatedAt = System.currentTimeMillis(),
        )
        tripDao.update(updated)
        BatteryStatsCounters.recordRoomWrite()
        upsertOutboxRow(updated, ready = false)
        return updated
    }

    /**
     * Buffers [newPoints] in memory and flushes to [tracePointDao] once the batch is due — every
     * [TRACE_BATCH_INTERVAL_MS] (5s) or [TRACE_BATCH_POINT_COUNT] points (10), whichever comes
     * first (G8, W4 §3). A no-op when [newPoints] is empty (nothing new to buffer) — the ordinary
     * "no live fix yet" tick, see
     * [au.com.threesixty.cabdispatch.domain.MeterController.nextTracePoint]'s own doc.
     *
     * The two thresholds bound the worst case on both axes: a fast-moving trip cannot buffer more
     * than 10 points (10 seconds at the fare engine's 1Hz tick) before a write lands, and a
     * completely idle tick loop still flushes whatever it has at least every 5s rather than
     * holding it indefinitely.
     */
    private suspend fun bufferTracePoints(clientUuid: String, newPoints: List<TelemetryPointDto>) {
        if (newPoints.isEmpty()) return
        val buffer = traceBuffers.getOrPut(clientUuid) { mutableListOf() }
        buffer.addAll(newPoints)
        val now = System.currentTimeMillis()
        val lastFlushedAt = traceBufferFlushedAtMs.getOrPut(clientUuid) { now }
        val dueByCount = buffer.size >= TRACE_BATCH_POINT_COUNT
        val dueByTime = (now - lastFlushedAt) >= TRACE_BATCH_INTERVAL_MS
        if (dueByCount || dueByTime) {
            flushTraceBuffer(clientUuid, buffer.toList())
            buffer.clear()
            traceBufferFlushedAtMs[clientUuid] = now
        }
    }

    /**
     * Writes [points] to [tracePointDao] as ONE `INSERT` of however many rows are due (never one
     * `INSERT` per point, and never a read of the trip's existing trace at all — the whole point of
     * this replacing the old full-blob rewrite). [seq] resumes from
     * [TripTracePointDao.maxSeq] the first time this process touches [clientUuid] (a process
     * restart mid-trip must not renumber or collide with rows already flushed before it died), then
     * is cached in [traceSeqCounters] so every later flush for the same trip is a pure in-memory
     * increment, not a query.
     */
    private suspend fun flushTraceBuffer(clientUuid: String, points: List<TelemetryPointDto>) {
        if (points.isEmpty()) return
        var seq = traceSeqCounters[clientUuid] ?: (tracePointDao.maxSeq(clientUuid) ?: -1)
        val entities = points.map { point ->
            seq += 1
            TripTracePointEntity(
                tripClientUuid = clientUuid,
                seq = seq,
                lat = point.lat,
                lng = point.lng,
                speedKmh = point.speedKmh,
                ts = point.ts,
            )
        }
        tracePointDao.insertAll(entities)
        BatteryStatsCounters.recordRoomWrite()
        traceSeqCounters[clientUuid] = seq
    }

    /** Force-flushes [clientUuid]'s trace buffer regardless of the batch thresholds, and forgets
     * the in-memory bookkeeping for it — called once, from [closeTrip], so nothing buffered since
     * the last ordinary flush is lost from the materialised [TripEntity.gpsTraceJson] below. */
    private suspend fun flushAndForgetTraceBuffer(clientUuid: String) {
        val remaining = traceBuffers.remove(clientUuid).orEmpty()
        flushTraceBuffer(clientUuid, remaining)
        traceBufferFlushedAtMs.remove(clientUuid)
        traceSeqCounters.remove(clientUuid)
    }

    /**
     * Finalizes the trip on-device. [deviceTotal] is the fare the on-device
     * fare engine computed — it is what the server independently checks the
     * recomputed total against (±1% variance tolerance, spec B6). This is
     * the point at which the outbox row for this trip becomes
     * [SyncOutboxEntity.readyToSync] — see that class's doc for why not
     * sooner.
     *
     * [endLat]/[endLng] are the real GPS fix at the moment the fare ended
     * (e.g. `AppContainer.speedSource.locationFix.value` — "where the vehicle
     * physically was", not the navigator's chosen destination). Deliberately
     * `Double?`: when no live fix is available at close time, `null` leaves
     * the coordinate already on the row untouched (typically the *intended*
     * drop-off [updateDropoff] wrote while the trip was open) rather than
     * writing a fabricated/wrong-but-plausible value.
     */
    suspend fun closeTrip(
        clientUuid: String,
        endLat: Double?,
        endLng: Double?,
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

        // G8, W4 §3: materialise the trace exactly once, here, rather than keeping it live all
        // trip long — see tick()'s own doc. Force-flushes anything still sitting in the in-memory
        // batch buffer first, so the last (up to 9) points driven right before END FARE was
        // pressed are not silently dropped from the receipt/sync payload.
        flushAndForgetTraceBuffer(clientUuid)
        val fullTrace = runCatching {
            tracePointDao.forTrip(clientUuid).map(TripTracePointEntity::toTelemetryPointDto)
        }.getOrDefault(emptyList())

        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = TripStatus.CLOSED,
            gpsTraceJson = cabDispatchJson.encodeToString(fullTrace),
            endAt = Instant.ofEpochMilli(now).toString(),
            endLat = endLat ?: existing.endLat,
            endLng = endLng ?: existing.endLng,
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
        BatteryStatsCounters.recordRoomWrite()
        upsertOutboxRow(updated, ready = true)
        return updated
    }

    /**
     * Marks a trip synced ([TripDao.markSynced]) AND drops its now-redundant
     * [tracePointDao] rows (G8 cleanup, W4 §3) — called by
     * [au.com.threesixty.cabdispatch.sync.SyncWorker]'s `OutboxDrainer` port in place of a bare
     * `tripDao.markSynced` call, since this is the one point in the sync pipeline that actually
     * KNOWS the server now has this trip's trace: [TripEntity.gpsTraceJson] (materialised once at
     * [closeTrip] time, from exactly these rows) already rode along inside the
     * [TripSyncItemDto] payload that just synced successfully. Deleting any earlier — e.g. at
     * [closeTrip] time — would drop the device's only copy of evidence a failed/retried sync might
     * still need. Best-effort: a failure to delete leaves harmless already-synced rows on-device
     * rather than failing a sync that has already, correctly, been marked complete.
     */
    suspend fun markSyncedAndCleanupTrace(clientUuid: String, serverId: String) {
        tripDao.markSynced(clientUuid, serverId)
        runCatching { tracePointDao.deleteForTrip(clientUuid) }
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
     * the *intended* end point. [closeTrip] overwrites the two coordinates with the real GPS fix
     * at close time when one is available; if not, it leaves whatever this method wrote in place
     * rather than fabricating a value (see [closeTrip]'s own doc) — so they are only reliably
     * "where we're heading" while the trip is open, and become "where the fare actually ended"
     * once closed (falling back to the intended destination only when no live fix exists).
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

    /**
     * Best-effort reverse-geocode fill for [TripEntity.pickupAddress]. Two call sites, both using
     * the same idempotent write: [au.com.threesixty.cabdispatch.ui.screens.hired.MeterNavViewModel]
     * (`resolvePickupAddress`) calls this the moment a destination is first picked, live, so the
     * nav pane's PICK UP card has a real address to show; [au.com.threesixty.cabdispatch.ui.
     * screens.closepay.CloseAndPayViewModel]'s `finalizeClose` calls it again right after
     * [closeTrip] returns as a fallback for a trip whose driver never opened the navigator at all.
     * Both use the trip's real [TripEntity.startLat]/[TripEntity.startLng] against
     * [au.com.threesixty.cabdispatch.data.remote.MapboxReverseGeocoding.reverseGeocode].
     * [TripEntity.pickupAddress]'s own doc explains why this column is often `null` at close time:
     * it's only populated at [openTrip] from a dispatch offer's `originAddress`, so a
     * street-hail/rank job or a Start Meter/Set Price trip opens with nothing to carry — History
     * renders an honest "—" for that until now.
     *
     * Deliberately only fills a currently-`null`/blank [TripEntity.pickupAddress] — a trip that
     * already carries a real dispatch-offer address is left untouched, never overwritten by a
     * coarser reverse-geocoded guess. The caller is responsible for never invoking this with a
     * fabricated [address]: [au.com.threesixty.cabdispatch.data.remote.MapboxReverseGeocoding]
     * only ever hands back a real Mapbox result or `null`, and a `null`/failed lookup must leave
     * this column exactly as it was, not call this method at all.
     *
     * Unlike every other write in this class, this one deliberately does NOT touch the outbox row:
     * [pickupAddress]/[TripEntity.dropoffAddress] are local-only, History/Trip-Detail-only fields
     * that were never part of [TripSyncItemDto]/[toSyncItemDto] (see that method's own field list)
     * — there is nothing for [au.com.threesixty.cabdispatch.sync.SyncWorker] to re-send here.
     */
    suspend fun fillPickupAddressIfMissing(clientUuid: String, address: String): TripEntity? {
        val existing = tripDao.getByClientUuid(clientUuid) ?: return null
        if (!existing.pickupAddress.isNullOrBlank()) return existing
        val updated = existing.copy(pickupAddress = address, updatedAt = System.currentTimeMillis())
        tripDao.update(updated)
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

    private suspend fun toSyncItemDto(trip: TripEntity): TripSyncItemDto {
        checkNotNull(trip.endAt) { "toSyncItemDto() requires a closed trip (endAt is null), clientUuid=${trip.clientUuid}" }
        // GPS-blackout audit trail (G3, W1, 2026-09-12) -- read once here rather than kept live in
        // memory: by close time every segment this trip will ever have is already resolved and
        // persisted (MeterController.persistBlackout writes the closing row on the same tick a
        // blackout resolves, well before the driver can reach Close & Pay). Best-effort: a read
        // failure here must never block a trip from syncing over evidence that is, at worst, one
        // this app has to reconstruct from the raw gps_trace instead.
        val blackoutSegments = runCatching { blackoutSegmentDao.forTrip(trip.clientUuid) }.getOrDefault(emptyList())
        return TripSyncItemDto(
            clientUuid = trip.clientUuid,
            vehicleId = trip.vehicleId,
            driverId = trip.driverId,
            shiftId = trip.shiftId,
            tariffId = trip.tariffId,
            type = trip.type,
            simulated = trip.simulated,
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
            // Maxi-at-airport-rank fare-integrity fix (2026-09-05): the backend's TripSyncItem
            // schema already declares/validates this field (backend/app/schemas/trips.py) and the
            // on-device fare engine already reads it correctly (see TripEntity.airportRankRequestedMaxi's
            // doc) — this line is what actually gets a trip closed with the flag set to carry it to
            // the server, so device_total (which includes the surcharge) doesn't diverge from the
            // server's independent recompute and get rejected for exceeding the sync variance
            // tolerance.
            airportRankRequestedMaxi = trip.airportRankRequestedMaxi,
            tolls = trip.tolls,
            extras = trip.extras,
            cleaningFee = trip.cleaningFee,
            surchargePct = trip.surchargePct,
            includePsl = trip.includePsl,
            gpsTrace = decodeGpsTrace(trip.gpsTraceJson),
            // Only ever resolved segments (endedAtIso != null) -- a blackout still open when the
            // driver pressed END FARE mid-tunnel is a real, if rare, edge case (reacquisition
            // never comes because the trip closed first); its own billing effect is already fully
            // reflected in the trip's ordinary totals either way, and the unresolved local row
            // stays on-device as-is rather than being force-fit into a DTO shape that assumes a
            // resolution happened.
            gpsBlackoutSegments = blackoutSegments.mapNotNull { seg ->
                val endedAt = seg.endedAtIso ?: return@mapNotNull null
                GpsBlackoutSegmentDto(
                    clientUuid = seg.clientUuid,
                    startedAt = seg.startedAtIso,
                    endedAt = endedAt,
                    entryLat = seg.entryLat,
                    entryLng = seg.entryLng,
                    exitLat = seg.exitLat ?: seg.entryLat,
                    exitLng = seg.exitLng ?: seg.entryLng,
                    entryWasMoving = seg.entryWasMoving,
                    resolution = seg.resolution,
                    billedDistanceKm = seg.billedDistanceKm,
                    corridorRoadId = seg.corridorRoadId,
                )
            },
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

    private companion object {
        /** [bufferTracePoints]'s point-count flush threshold — see that method's doc. */
        const val TRACE_BATCH_POINT_COUNT = 10

        /** [bufferTracePoints]'s time-based flush threshold, millis — see that method's doc. */
        const val TRACE_BATCH_INTERVAL_MS = 5_000L
    }
}

/** [TripTracePointEntity] -> [TelemetryPointDto] — the wire/JSON shape every consumer of a trace
 * (the sync payload, the live route-polyline overlay) already expects, so materialising
 * [TripEntity.gpsTraceJson] from this table is a straight `map`, not a reshape. */
private fun TripTracePointEntity.toTelemetryPointDto(): TelemetryPointDto =
    TelemetryPointDto(lat = lat, lng = lng, speedKmh = speedKmh, ts = ts)
