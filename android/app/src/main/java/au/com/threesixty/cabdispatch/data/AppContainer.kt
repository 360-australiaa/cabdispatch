package au.com.threesixty.cabdispatch.data

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.local.AppDatabase
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.MapboxDirections
import au.com.threesixty.cabdispatch.data.remote.MapboxGeocoding
import au.com.threesixty.cabdispatch.data.remote.RealtimeSocket
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import au.com.threesixty.cabdispatch.domain.DuressController
import au.com.threesixty.cabdispatch.domain.DuressRepository
import au.com.threesixty.cabdispatch.domain.JobsRepository
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat
import au.com.threesixty.cabdispatch.domain.MessagesRepository
import au.com.threesixty.cabdispatch.domain.QrScanner
import au.com.threesixty.cabdispatch.domain.DevicePairingStore
import au.com.threesixty.cabdispatch.domain.MaxiVehicleStore
import au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore
import au.com.threesixty.cabdispatch.domain.RealQrScanner
import au.com.threesixty.cabdispatch.domain.RemoteBackedDuressRepository
import au.com.threesixty.cabdispatch.domain.RemoteBackedJobsRepository
import au.com.threesixty.cabdispatch.domain.RemoteBackedMessagesRepository
import au.com.threesixty.cabdispatch.domain.RemoteBackedShiftRepository
import au.com.threesixty.cabdispatch.domain.ShiftRepository
import au.com.threesixty.cabdispatch.domain.SpeedSource
import au.com.threesixty.cabdispatch.domain.RemoteTripStatsRepository
import au.com.threesixty.cabdispatch.domain.TripStatsRepository
import au.com.threesixty.cabdispatch.domain.RemoteBackedZonesRepository
import au.com.threesixty.cabdispatch.domain.ZonesRepository
import au.com.threesixty.cabdispatch.domain.duress.DuressAudioRecorder
import au.com.threesixty.cabdispatch.domain.duress.DuressCameraCapture
import au.com.threesixty.cabdispatch.domain.fare.FareEngine as PureFareEngine
import au.com.threesixty.cabdispatch.domain.location.RealLocationProvider
import au.com.threesixty.cabdispatch.hardware.payments.CardPaymentGateway
import au.com.threesixty.cabdispatch.hardware.payments.MockCardPaymentGateway
import au.com.threesixty.cabdispatch.hardware.printing.MockReceiptPrinterGateway
import au.com.threesixty.cabdispatch.hardware.printing.ReceiptPrinterGateway
import au.com.threesixty.cabdispatch.hardware.receipt.EmailReceiptGateway
import au.com.threesixty.cabdispatch.hardware.receipt.MockEmailReceiptGateway
import au.com.threesixty.cabdispatch.hardware.receipt.MockSmsReceiptGateway
import au.com.threesixty.cabdispatch.hardware.receipt.SmsReceiptGateway
import au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger
import au.com.threesixty.cabdispatch.sync.SyncWorker
import au.com.threesixty.cabdispatch.sync.TariffCache
import au.com.threesixty.cabdispatch.sync.TariffSigningKeyCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Manual service locator — the single composition root for the app's
 * singletons (DB, network client, repositories).
 *
 * Deliberately NOT Hilt/KSP: per project instructions, dependency-injection
 * codegen (Hilt + KSP annotation processing) is avoided so the build stays
 * verifiable-by-inspection in an environment with no Android SDK/emulator to
 * actually compile and run it — a plain Kotlin `object` has zero build-tool
 * surface area to get subtly wrong. Revisit this decision once the project
 * has a real CI build to catch DI graph errors.
 *
 * Lifecycle: [init] is called once from [au.com.threesixty.cabdispatch.CabDispatchApp.onCreate];
 * everything below is a `lateinit var` (not `by lazy` on first access) so a
 * missing [init] call fails loudly at first use rather than silently
 * constructing a DB/client without the real application [Context].
 *
 * --- How a sibling agent registers a new repository or DAO here ---
 * 1. DAO: add `abstract fun fooDao(): FooDao` to [AppDatabase], then expose it
 *    as `val fooDao: FooDao by lazy { database.fooDao() }` below.
 * 2. Repository: add a class `FooRepository(private val fooDao: FooDao, private
 *    val apiService: ApiService)`, then add
 *    `val fooRepository: FooRepository by lazy { FooRepository(fooDao, apiService) }`
 *    below. Repositories should depend on the DAO + [apiService] already
 *    exposed here, not construct their own Retrofit/Room instances.
 * 3. Compose screens read singletons via `AppContainer.fooRepository` (or have
 *    it passed into a ViewModel factory) — no DI annotations required anywhere.
 */
object AppContainer {

    lateinit var database: AppDatabase
        private set

    lateinit var apiService: ApiService
        private set

    /** Shared OkHttp client [apiService]'s Retrofit instance is built on — also reused by
     * [realtimeSocket] so the jobs/messages WS connections share the same connection pool,
     * dispatcher, and logging interceptor as every HTTP call, rather than spinning up a second
     * client. */
    lateinit var okHttpClient: OkHttpClient
        private set

    /**
     * Mutable holder for the current session's bearer token, read by
     * [authInterceptor] on every request. The auth/session sibling agent
     * should update this on login/refresh/logout rather than rebuilding
     * [apiService]. `null` = unauthenticated (auth endpoints only).
     */
    var accessToken: String? = null

    /** Held for the process lifetime — see [ConnectivitySyncTrigger] doc. */
    lateinit var connectivitySyncTrigger: ConnectivitySyncTrigger
        private set

    /** Application [Context], captured once in [init] — [speedSource]'s real
     * [RealLocationProvider] needs it (runtime permission checks + the
     * `FusedLocationProviderClient` instance), same reasoning as every other lateinit field on
     * this object: fail loudly at first use if [init] was never called, rather than silently
     * constructing something Context-shaped without a real application Context. */
    lateinit var appContext: Context
        private set

    lateinit var devicePairingStore: DevicePairingStore
        private set

    /** See [MaxiVehicleStore]'s own doc — a local, honestly-labelled driver self-declaration
     * ("this vehicle has 5+ seats"), not real fleet-registry data. Read by the Home dashboard's
     * Start Meter card (to prefill/edit the declaration) and Settings → Fare schedule (to view/
     * edit it directly), Point to Point Transport (Fares) Order 2026 UI-wiring pass. */
    lateinit var maxiVehicleStore: MaxiVehicleStore
        private set

    /** See [SettingsPreferencesStore]'s own doc — Auto Accept Jobs / Show Map in Background /
     * Allow Cash, the three real preference rows added in the Settings two-pane pass. */
    lateinit var settingsPreferencesStore: SettingsPreferencesStore
        private set

    fun init(context: Context) {
        appContext = context.applicationContext

        // Restore the paired device id across process death (2026-08-28 device-pairing pass) —
        // SessionHolder.deviceId is in-memory only; without this, every cold start forgot pairing
        // and heartbeat silently went back to a no-op even after a real pairing had succeeded.
        devicePairingStore = DevicePairingStore(appContext)
        SessionHolder.deviceId = devicePairingStore.getDeviceId()

        maxiVehicleStore = MaxiVehicleStore(appContext)
        settingsPreferencesStore = SettingsPreferencesStore(appContext)

        database = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "cabdispatch.db",
        ).build()

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(cabDispatchJson.asConverterFactory("application/json".toMediaType()))
            .build()

        apiService = retrofit.create(ApiService::class.java)

        // --- Offline sync engine wiring (B7) ---
        // Backstop: periodic drain every ~15 min, in case the reconnect
        // callback below is ever missed (process death, etc).
        SyncWorker.enqueuePeriodic(WorkManager.getInstance(appContext))
        // Eager: drain the instant connectivity returns, instead of waiting
        // up to 15 min for the backstop.
        connectivitySyncTrigger = ConnectivitySyncTrigger(appContext).also { it.start() }

        // Best-effort warm-up of the tariff-signing public key (see [tariffSigningKeyCache]) so
        // a device that's online at first launch already has it cached before the first tariff
        // [TariffCache.refresh] call needs it — that call also has its own on-demand fallback
        // fetch (see TariffCache.verifySignatureOrThrow), so this is an optimization, not a
        // correctness requirement; failures here are silently swallowed on purpose, same as
        // every other best-effort call in this class.
        startupScope.launch { runCatching { tariffSigningKeyCache.refresh() } }

        // Begins supervising session/shift state for the ambient position heartbeat (see
        // [livePositionHeartbeat]'s own doc) — must be started unconditionally here, not left to
        // whenever some screen happens to first read [livePositionHeartbeat], since (unlike every
        // other `by lazy` singleton above) nothing else in this app ever needs to reference this
        // property by name for it to do its job.
        livePositionHeartbeat.start()
    }

    /** Fire-and-forget process-lifetime scope for one-shot startup tasks that must kick off
     * unconditionally from [init] itself (not lazily on first property access, like every other
     * `CoroutineScope` in this object) — currently just the tariff-signing-key warm-up above. */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = accessToken
        val request = if (token != null) {
            original.newBuilder().addHeader("Authorization", "Bearer $token").build()
        } else {
            original
        }
        chain.proceed(request)
    }

    // --- Offline sync engine (B7): trip queue, tariff cache, sync outbox ---

    val tripDao by lazy { database.tripDao() }
    val shiftDao by lazy { database.shiftDao() }
    val tariffDao by lazy { database.tariffDao() }
    val syncOutboxDao by lazy { database.syncOutboxDao() }
    val tariffSigningKeyDao by lazy { database.tariffSigningKeyDao() }

    val tripRepository by lazy { TripRepository(tripDao, syncOutboxDao, apiService) }

    /** Local cache of the Ed25519 public key that verifies [tariffCache]'s signed tariffs — see
     * that class's doc and `security/TariffSignatureVerifier.kt`'s `Ed25519TariffSignatureVerifier`. */
    val tariffSigningKeyCache by lazy { TariffSigningKeyCache(tariffSigningKeyDao, apiService) }
    val tariffCache by lazy { TariffCache(tariffDao, apiService, tariffSigningKeyCache) }

    // --- S4-S6 agent: fare-breakdown engine + hardware gateways ---
    //
    // RESOLVED (integration pass): there are still TWO "FareEngine" types in
    // this codebase, built by different sibling agents in parallel with no
    // coordination — that part was never in dispute:
    //   - au.com.threesixty.cabdispatch.domain.fare.FareEngine (aliased
    //     PureFareEngine below) — stateless, pure-Kotlin port of the
    //     backend's fare_engine.py, BigDecimal-exact rate tables incl. the
    //     day/night/holiday distance bands and GST-on-total math. Used by
    //     S4/S5 to reconstruct a FareBreakdown from a *persisted*
    //     TripEntity's final counters (see
    //     domain/fare/TripFareReconstruction.kt) — see that file's doc for
    //     why that reconstruction is exact, not approximate.
    //   - au.com.threesixty.cabdispatch.domain.FareEngine (see
    //     domain/FareEngine.kt) — a stateful, coroutine-driven *live-ticking*
    //     engine S3 (HIRED) uses to drive the on-screen running fare once per
    //     second.
    // What WAS a real gap (not just duplicated math, an actual missing
    // integration): S3 never called TripRepository at all, so no TripEntity
    // row existed for S4 to read — CloseAndPayViewModel's doc used to flag
    // this as "S4 will show NoActiveTrip every time". Fixed this pass:
    // HiredViewModel now calls tripRepository.openTrip() when the live engine
    // starts and tripRepository.tick() on every live-engine emission (see
    // FareState.movingSeconds/waitingSeconds, incremented by FareEngineImpl
    // for exactly this purpose), so S4's reconstruction has real persisted
    // counters to read. The two engines' *money math* is deliberately left
    // unconverged — merging them (e.g. having the live engine delegate to
    // PureFareEngine.tick() per second) is a bigger change than a
    // reconciliation pass should make silently, and the golden-vector tests
    // prove PureFareEngine is already byte-identical to the backend, which is
    // what actually matters for the persisted/synced total. Property named
    // `pureFareEngine` (not the bare `fareEngine` the live-ticking one might
    // suggest) specifically so it does not collide with that one.
    val pureFareEngine: PureFareEngine by lazy { PureFareEngine() }

    // Hardware interfaces — see android/README.md "Real vs mocked". The rest are clearly-labeled
    // mocks (no certified payment/printer/SMS/email hardware exists to integrate against in this
    // sandbox). Signature verification is real too, but isn't a singleton here the way these
    // gateways are — [tariffCache] constructs an `Ed25519TariffSignatureVerifier` itself, scoped
    // to whatever public key [tariffSigningKeyCache] hands it at verify time (the key is fetched/
    // cached, not a compile-time constant, so there's no single verifier instance to hold onto
    // for the process lifetime the way a gateway singleton implies) — see TariffCache.kt.
    val cardPaymentGateway: CardPaymentGateway by lazy { MockCardPaymentGateway() }
    val receiptPrinterGateway: ReceiptPrinterGateway by lazy { MockReceiptPrinterGateway() }
    val smsReceiptGateway: SmsReceiptGateway by lazy { MockSmsReceiptGateway() }
    val emailReceiptGateway: EmailReceiptGateway by lazy { MockEmailReceiptGateway() }

    // --- S1-S3 screen dependencies (integration pass) ---
    //
    // Formerly `domain/ScreenDependencies.kt`, a separate temporary
    // composition root the S1-S3 screens agent stood up specifically to avoid
    // racing this file's edits while the offline sync-engine/FareEngine
    // agents were landing in parallel (see that file's now-deleted doc
    // comment). That race is over; consolidated here per this class's own
    // registration pattern so there is exactly one service locator, not two.
    // [tariffCache] above (the real, Room-backed, signed-payload cache) was
    // already here — ScreenDependencies had its OWN second `TariffCache`
    // (domain/TariffCache.kt: an in-memory-only, unsigned,
    // not-persisted-across-restart stub named identically to this one, in a
    // different package). That stub is now deleted; IdleViewModel reads
    // tariffs through [tariffCache] like every other screen.
    // Real ML Kit code-scanner impl (2026-08-28) — see RealQrScanner's own doc. StubQrScanner
    // stays defined for tests/no-camera environments, just no longer what this constructs.
    val qrScanner: QrScanner by lazy { RealQrScanner() }
    // Real bug fixed (2026-09-02, Home-dashboard redesign pass): this was wired to
    // StubTripStatsRepository, whose hardcoded-zero flow meant the dashboard's "TRIPS"/"EARNINGS"
    // tiles always rendered 0/$0 for every driver, always — see RemoteTripStatsRepository's own
    // doc and DASHBOARD_REDESIGN_2026.md. StubTripStatsRepository stays defined for tests/previews.
    val tripStatsRepository: TripStatsRepository by lazy { RemoteTripStatsRepository() }
    val shiftRepository: ShiftRepository by lazy { RemoteBackedShiftRepository(apiService) }

    /**
     * Real fused-location GPS feed (see `domain/location/RealLocationProvider.kt`), replacing
     * the former `StubSpeedSource()` default here — see HANDOFF.md's "GPS is stubbed" gap and
     * that class's doc for the permission-poll loop that starts/stops it. Own process-lifetime
     * `SupervisorJob` scope, same pattern as [duressController] below, so one location subscriber
     * misbehaving (or the coroutine it's collecting on throwing) can't take down anything else
     * sharing a scope. [StubSpeedSource][au.com.threesixty.cabdispatch.domain.StubSpeedSource]
     * remains defined in `domain/FareEngine.kt`, kept (not deleted) as the explicit no-GPS
     * fallback for tests/previews — this property just no longer constructs it by default.
     */
    val speedSource: SpeedSource by lazy {
        RealLocationProvider(appContext, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    // --- Wheel redesign shared foundation (jobs/offers + messages, spec §9) ---
    //
    // [realtimeSocket] is the WS counterpart to [apiService] — Retrofit has no first-class
    // websocket support, so `WS /v1/jobs/live` / `WS /v1/messages/live` go through this instead
    // (see RealtimeSocket's doc for why frames are untyped raw JSON strings).
    val realtimeSocket: RealtimeSocket by lazy { RealtimeSocket(okHttpClient) }

    // Mapbox REST gateways for the meter screen's navigator pane (drop-off search + route/steps).
    // Plain authenticated HTTPS on the public pk.* token, reusing this same OkHttp client — the
    // Mapbox *SDK* equivalents need a secret sk.* downloads token this project doesn't have (see
    // MapboxDirections' own doc for the full constraint).
    val mapboxGeocoding: MapboxGeocoding by lazy { MapboxGeocoding(okHttpClient) }
    val mapboxDirections: MapboxDirections by lazy { MapboxDirections(okHttpClient) }
    val jobsRepository: JobsRepository by lazy {
        RemoteBackedJobsRepository(apiService, realtimeSocket, BuildConfig.API_BASE_URL)
    }
    val messagesRepository: MessagesRepository by lazy {
        RemoteBackedMessagesRepository(apiService, realtimeSocket, BuildConfig.API_BASE_URL)
    }

    // --- Duress (contextual overlays S28-S30, spec §8) ---
    //
    // [duressController] is a process-lifetime singleton (own SupervisorJob-backed
    // CoroutineScope, not tied to any single screen's ViewModel scope) precisely because a
    // duress event must keep relaying GPS / polling for dispatcher resolution across screen
    // navigation (S3 -> S4 -> S2 etc.) — see [DuressController]'s doc for why it isn't just
    // another `by lazy` hung off HiredViewModel.
    val duressRepository: DuressRepository by lazy { RemoteBackedDuressRepository(apiService) }

    /** Real `MediaRecorder`-backed duress audio capture (Blueprint §4.3/§8.3), only needs the
     * process-lifetime application [appContext] — see that class's doc for the permission/
     * simplification write-up. */
    val duressAudioRecorder: DuressAudioRecorder by lazy { DuressAudioRecorder(appContext) }

    /** Real CameraX-backed duress cabin-camera still-frame capture (snapshot gallery,
     * 2026-08-27), same "process-lifetime appContext, nothing more" shape as
     * [duressAudioRecorder] — see that class's doc for the permission/lifecycle write-up. */
    val duressCameraCapture: DuressCameraCapture by lazy { DuressCameraCapture(appContext) }
    val duressController: DuressController by lazy {
        DuressController(
            duressRepository,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            duressAudioRecorder,
            duressCameraCapture,
        )
    }

    // --- Ambient live-position heartbeat (Taxi Meter SaaS Complete Blueprint §6.2.2:
    // "vehicle.heartbeat -> Every 30 seconds: GPS, status, battery" while on shift) ---
    //
    // [livePositionHeartbeat] is a process-lifetime singleton, own `SupervisorJob`-backed
    // `CoroutineScope` — same reasoning as [speedSource]/[duressController] above: it must keep
    // publishing across screen navigation, not be tied to any one screen's ViewModel scope. Unlike
    // [duressController] (externally trigger()/cancel()-driven), this one is self-supervising —
    // see [LivePositionHeartbeat]'s own doc — so [start] only needs to be called once, right here,
    // for it to react to [au.com.threesixty.cabdispatch.domain.SessionHolder.session] (shift
    // open/closed) for the rest of the process lifetime with zero wiring in any screen/ViewModel.
    val livePositionHeartbeat: LivePositionHeartbeat by lazy {
        LivePositionHeartbeat(apiService, speedSource, CoroutineScope(SupervisorJob() + Dispatchers.Default), appContext)
    }

    // --- Zones (Plot / Statistics screens — named dispatch zones, "plot into a zone", live
    // per-zone demand stats, matching a real competitor taxi meter's zone screens, per
    // backend/app/api/v1/zones.py) ---
    //
    // Thin network-only, same reasoning as [jobsRepository] above (see [ZonesRepository]'s own
    // doc) — no Room/offline-queue story needed, just [apiService].
    val zonesRepository: ZonesRepository by lazy { RemoteBackedZonesRepository(apiService) }

    // Repository/DAO singletons are added here by sibling agents, e.g.:
    // val fooDao: FooDao by lazy { database.fooDao() }
    // val fooRepository: FooRepository by lazy { FooRepository(fooDao, apiService) }
}
