package au.com.threesixty.cabdispatch.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import au.com.threesixty.cabdispatch.MainActivity
import au.com.threesixty.cabdispatch.R
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.data.remote.TelemetryPointDto
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import au.com.threesixty.cabdispatch.domain.fare.reconstructFareState
import au.com.threesixty.cabdispatch.domain.fare.toDomainTariff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import au.com.threesixty.cabdispatch.data.local.entity.TripStatus as RoomTripStatus

/**
 * The process-lifetime owner of a running fare — the answer to F4 (architecture audit 2026-09-08,
 * §2.1, rated **blocker**, and the largest single piece of work in that audit).
 *
 * **What was wrong.** [FareEngineImpl]'s tick loop ran in `HiredViewModel.viewModelScope`. A
 * ViewModel scoped to a navigation entry. So the fare stopped accruing the moment that entry went
 * away, and there were three ordinary ways for that to happen *with a passenger in the car*: the
 * driver pressed Back, the OS backgrounded the app and Doze suspended its coroutines, or the
 * process was killed for memory. `DeviceReadiness.kt` documented the second of those in as many
 * words — "it is a trip that stops being charged for while the passenger is still in the car" —
 * and the battery-optimisation exemption offered against it was only ever advisory, a request the
 * OS is free to ignore and the driver free to decline.
 *
 * **What makes it right.** Android has exactly one mechanism that means "keep running, this is
 * work the user knows about and expects to continue": a foreground service with a visible, ongoing
 * notification. That is what this class is. The meter runs for as long as the hiring does,
 * independent of what is on screen and independent of whether the app is in front, and the driver
 * can see it running from anywhere in the system.
 *
 * The notification is not boilerplate. It shows the live running fare, and it is the honest
 * counterpart of the whole exercise: a meter that keeps charging while the app is out of sight has
 * an obligation to keep saying so. `foregroundServiceType="location"` is likewise a statement of
 * fact rather than a formality — the fare is accrued from GPS, and this service exists to keep
 * that GPS-driven accrual alive.
 *
 * **What this class deliberately is not.** It holds no state of its own. Every fare figure lives in
 * [MeterController], which is process-scoped in
 * [au.com.threesixty.cabdispatch.data.AppContainer] and survives this service being stopped and
 * restarted. A service instance that the OS destroys and recreates therefore loses nothing; it
 * re-attaches to a meter that never stopped. That separation is what lets the restart path
 * ([MeterController.restoreOpenTripIfAny]) be about Room and the fare engine, with the service as a
 * consequence rather than a participant.
 */
class MeterForegroundService : Service() {

    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /**
     * `START_STICKY`: if the OS kills this service under memory pressure while a hiring is still
     * open, it should bring it back. On that restart [intent] is null and there is nothing to read
     * from it — which is precisely why this service carries no per-trip state. It re-attaches to
     * [MeterController]'s state flow and, if the controller itself was lost with the process,
     * [au.com.threesixty.cabdispatch.CabDispatchApp]'s own restore path has already put a live
     * meter back in place from the OPEN Room row before this runs.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(MeterController.instance?.state?.value))
        notificationJob?.cancel()
        val state = MeterController.instance?.state
        if (state != null) {
            notificationJob = state
                .onEach { fareState ->
                    // A running fare updates roughly once a second. Re-posting the same
                    // notification id simply replaces its content in place -- no sound, no
                    // re-alerting (setOnlyAlertOnce below), no notification-shade churn.
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(fareState))
                }
                .launchIn(MeterController.instance!!.scope)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        // API 29+ (this app's minSdk) requires the type at startForeground time; API 34 made an
        // untyped call throw outright. `location` is the honest type: this service exists to keep a
        // GPS-driven fare accruing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: FareState?): Notification {
        val total = state?.total ?: BigDecimal.ZERO
        val amount = "$" + total.setScale(2, RoundingMode.HALF_UP).toPlainString()
        // The GPS-lost line is the same honesty requirement F3 put on the dial, carried out to the
        // notification shade: a driver who has the app in the background must not be left reading a
        // fare that has quietly stopped moving without being told why.
        val detail = if (state?.gpsLost == true) {
            "GPS lost - waiting time only"
        } else {
            "Meter running"
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fare running - $amount")
            .setContentText(detail)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            // Ongoing + no timestamp: this is a state, not an event. The driver must not be able to
            // swipe away the only visible sign that the meter is charging.
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Meter",
            // LOW, not DEFAULT: this notification must be permanently visible but must never make a
            // sound. It updates about once a second for the length of a hiring.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the running fare while a trip is in progress."
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "meter_running"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            val intent = Intent(context, MeterForegroundService::class.java)
            // startForegroundService + a startForeground() call within the OS's grace window. Wrapped
            // because the platform throws if the app is judged to be in the background at the moment
            // of the call (API 31+ ForegroundServiceStartNotAllowedException). A meter that fails to
            // acquire foreground status must still tick -- the engine is process-scoped and does not
            // depend on this service existing -- so a failure here degrades to exactly the old
            // behaviour rather than taking the fare down with it.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MeterForegroundService::class.java)) }
        }
    }
}

/**
 * The process-lifetime holder for the live meter: the fare engine, the Room persistence loop, and
 * the foreground service's lifecycle, in the one place that knows when a hiring begins and ends.
 *
 * F4's hoist is not simply "move [FareEngineImpl] somewhere longer-lived". The accrual and its
 * *persistence* have to move together. `HiredViewModel` drove both — it owned the engine and it
 * owned the `fareState.onEach { persistTick() }` subscription that wrote every tick to Room — so
 * hoisting only the engine would have produced a meter that kept charging into a
 * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity] row nobody was updating, which is a
 * worse failure than the one being fixed: the fare would look right on screen and be wrong in the
 * record the passenger is actually billed from and the server actually audits.
 *
 * So this class owns both, on a scope that ends with the process. `HiredViewModel` becomes what the
 * audit asked for — a thin observer that reads [state] and forwards driver actions — and can be
 * created and destroyed as often as navigation likes without the fare noticing.
 */
class MeterController(
    private val appContext: Context,
    private val fareEngine: FareEngine,
    private val tripRepository: TripRepository,
    private val speedSource: SpeedSource,
    /** Resolves a cached tariff row to its DTO — the restore path's one data dependency. Injected
     * as a function so this class stays testable without a Room instance. */
    private val tariffLookup: suspend (String) -> TariffDto?,
    val scope: CoroutineScope,
) {

    val state: StateFlow<FareState> = fareEngine.state

    /** The trip this meter is currently accruing into, or `null` between hirings. */
    @Volatile
    var activeClientUuid: String? = null
        private set

    private var persistJob: Job? = null

    /**
     * Begins a new hiring: starts the engine, starts persisting every tick, and brings the
     * foreground service up. Called once, from the trip's own opening path.
     *
     * [clientUuid] is minted by the caller rather than here, and the Room row is opened by the
     * caller too — see `HiredViewModel.openTripInRoom`'s own doc for the real race that ordering
     * closes.
     */
    fun startTrip(tripContext: TripContext, clientUuid: String) {
        fareEngine.startTrip(
            tripContext.tariff,
            tripContext.startLat,
            tripContext.startLng,
            isMaxiVehicle = tripContext.isMaxiVehicle,
            passengerCount = tripContext.passengerCount,
            wheelchairHiring = tripContext.wheelchairHiring,
            airportRankRequestedMaxi = tripContext.airportRankRequestedMaxi,
            negotiatedTotal = tripContext.negotiatedTotal?.let { runCatching { BigDecimal(it) }.getOrNull() },
        )
        beginPersisting(clientUuid)
        MeterForegroundService.start(appContext)
    }

    /**
     * Rebuilds a live meter from an `OPEN` trip row left behind by a process that died mid-hiring,
     * and resumes ticking — F4's restart requirement, "the dial must show the correct running
     * total, not reset to zero".
     *
     * A no-op in the ordinary case (no open trip), and a no-op if a meter is already running, so it
     * is safe to call unconditionally at process start.
     *
     * When the tariff for the open trip cannot be resolved this deliberately does nothing rather
     * than resuming against a guessed rate card. The trip row itself is untouched and Close & Pay
     * still reaches it (with F11's default-rates fallback if it must), so the outcome is a fare that
     * stopped accruing at the crash rather than one that resumed accruing at the wrong price. Of
     * the two, only the second is unrecoverable.
     */
    suspend fun restoreOpenTripIfAny() {
        if (activeClientUuid != null) return
        val open = tripRepository.observeActiveTripOnce() ?: return
        if (open.status != RoomTripStatus.OPEN) return
        val tariffDto = tariffLookup(open.tariffId) ?: return
        val restored = runCatching { reconstructFareState(open, tariffDto.toDomainTariff()) }.getOrNull() ?: return

        fareEngine.resumeTrip(
            tariff = tariffDto,
            restored = restored,
            movingSeconds = open.movingS,
            waitingSeconds = open.waitingS,
        )
        SessionHolder.markTripLive(open.clientUuid)
        beginPersisting(open.clientUuid)
        MeterForegroundService.start(appContext)
    }

    /**
     * Ends the hiring: stops the engine, stops persisting, and takes the foreground service down.
     * Returns the final [FareState] so the caller can hand it to Close & Pay.
     *
     * The service is stopped here, at the end of the *fare*, not at the end of Close & Pay. Once
     * the meter has stopped there is no ongoing work for the OS to keep alive, and leaving a
     * "Fare running" notification up over a trip that has stopped running would be the same
     * dishonesty this whole class exists to remove, pointing the other way.
     */
    fun stopTrip(): FareState {
        val closed = fareEngine.close()
        persistJob?.cancel()
        persistJob = null
        activeClientUuid = null
        MeterForegroundService.stop(appContext)
        return closed
    }

    fun pause() = fareEngine.pause()
    fun resume() = fareEngine.resume()
    fun addToll(preset: TollPreset) = fareEngine.addToll(preset)
    fun removeAutoToll(roadId: String) = fareEngine.removeAutoToll(roadId)
    fun dismissUnpricedToll(roadId: String) = fareEngine.dismissUnpricedToll(roadId)
    fun updatePassengerCount(count: Int) = fareEngine.updatePassengerCount(count)

    /**
     * Subscribes Room to the engine, for the life of the hiring.
     *
     * This subscription used to live in `HiredViewModel.init` on `viewModelScope`, which is exactly
     * why pressing Back stopped the trip record dead even in the cases where the fare itself might
     * have kept running. On [scope] it ends when the trip ends, and not before.
     */
    private fun beginPersisting(clientUuid: String) {
        activeClientUuid = clientUuid
        persistJob?.cancel()
        persistJob = state
            .onEach { fareState -> persistTick(clientUuid, fareState) }
            .launchIn(scope)
    }

    private suspend fun persistTick(clientUuid: String, fareState: FareState) {
        val point = nextTracePoint()
        runCatching {
            tripRepository.tick(
                clientUuid = clientUuid,
                newPoints = listOfNotNull(point),
                distanceM = fareState.distanceKm.movePointRight(3).setScale(0, RoundingMode.HALF_UP).toInt(),
                movingS = fareState.movingSeconds,
                waitingS = fareState.waitingSeconds,
                tolls = fareState.breakdown.tolls.toPlainString(),
                autoTolledRoads = fareState.autoTollsApplied.associate { it.roadId to it.amount.toPlainString() },
                unpricedTollRoadIds = fareState.unpricedTollRoads.map { it.roadId },
                // Names the airport fee inside `tolls` for the receipt — see
                // TripEntity.airportAccessFeeJson's doc. Null (= leave untouched) on a trip whose
                // ledger has no airport entry.
                airportAccessFee = AirportAccessFeeRecord.fromLedger(fareState.tollsApplied),
                // F9: the charges themselves, not only the lossy integer-metre counters they used
                // to have to be re-derived from. See TripEntity.accruedDistanceCharge's doc.
                accruedDistanceCharge = fareState.breakdown.distanceAmount.toPlainString(),
                accruedWaitingCharge = fareState.breakdown.waitingAmount.toPlainString(),
            )
        }
    }

    /**
     * The one real GPS point each tick appends to the persisted trace — moved here verbatim from
     * `HiredViewModel.nextTracePoint`, whose (long, and still accurate) doc explains why the point
     * carries the tick's own wall-clock time rather than the fix's older GPS timestamp: the server's
     * `recompute_from_trace` replays this trace to validate the device total, and a trace whose
     * temporal resolution tracked the GPS receiver's jittery cadence rather than the billing clock
     * left every second between the last fix and the close invisible to it.
     *
     * `null` (append nothing) only when there has never been a live fix at all — an honest gap,
     * never a fabricated point.
     */
    private fun nextTracePoint(): TelemetryPointDto? {
        val fix = speedSource.locationFix.value ?: return null
        return TelemetryPointDto(
            lat = fix.lat,
            lng = fix.lng,
            speedKmh = fix.speedKmh,
            ts = Instant.now().toString(),
        )
    }

    companion object {
        /**
         * The live controller, published for [MeterForegroundService] to read.
         *
         * A service is constructed by the OS, not by us, so it cannot be given dependencies through
         * a constructor and has no access to the container's graph on its own. Set once by
         * [au.com.threesixty.cabdispatch.data.AppContainer] when the controller is first created,
         * which is process-scoped anyway — this is a publication point for an object that already
         * lives for the whole process, not a second lifetime for it.
         */
        @Volatile
        var instance: MeterController? = null
            private set

        fun publish(controller: MeterController) {
            instance = controller
        }
    }
}

/**
 * One-shot read of the single OPEN trip, for [MeterController.restoreOpenTripIfAny].
 *
 * `first()` rather than `firstOrNull()`: Room's `observeActiveTrip` Flow always emits its current
 * value (a `TripEntity?`, null when there is no open trip) and then stays open, so `first()`
 * returns that first emission and completes. `firstOrNull()` here would mean "null if the flow
 * completes empty", which this flow never does.
 */
private suspend fun TripRepository.observeActiveTripOnce(): TripEntity? = observeActiveTrip().first()
