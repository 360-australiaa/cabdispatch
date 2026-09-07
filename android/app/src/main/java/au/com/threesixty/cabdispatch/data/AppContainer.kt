package au.com.threesixty.cabdispatch.data

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.local.AppDatabase
import au.com.threesixty.cabdispatch.data.local.MIGRATION_8_9
import au.com.threesixty.cabdispatch.data.local.MIGRATION_9_10
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.MapboxDirections
import au.com.threesixty.cabdispatch.data.remote.MapboxGeocoding
import au.com.threesixty.cabdispatch.data.remote.MapboxReverseGeocoding
import au.com.threesixty.cabdispatch.data.remote.RealtimeSocket
import au.com.threesixty.cabdispatch.data.remote.RefreshRequestDto
import au.com.threesixty.cabdispatch.data.remote.RefreshResponseDto
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import au.com.threesixty.cabdispatch.domain.AppUpdateChecker
import au.com.threesixty.cabdispatch.domain.DeviceCommandHeartbeat
import au.com.threesixty.cabdispatch.domain.DriverEngagementRepository
import au.com.threesixty.cabdispatch.domain.DuressController
import au.com.threesixty.cabdispatch.domain.DuressRepository
import au.com.threesixty.cabdispatch.domain.RemoteBackedDriverEngagementRepository
import au.com.threesixty.cabdispatch.domain.JobsRepository
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.LivePositionHeartbeat
import au.com.threesixty.cabdispatch.domain.MessagesRepository
import au.com.threesixty.cabdispatch.domain.QrScanner
import au.com.threesixty.cabdispatch.domain.CommissioningStore
import au.com.threesixty.cabdispatch.domain.DevicePairingStore
import au.com.threesixty.cabdispatch.domain.MaxiVehicleStore
import au.com.threesixty.cabdispatch.domain.SessionStore
import au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore
import au.com.threesixty.cabdispatch.domain.TokenStore
import au.com.threesixty.cabdispatch.domain.RealQrScanner
import au.com.threesixty.cabdispatch.domain.RemoteBackedDuressRepository
import au.com.threesixty.cabdispatch.domain.RemoteBackedJobsRepository
import au.com.threesixty.cabdispatch.domain.RemoteBackedMessagesRepository
import au.com.threesixty.cabdispatch.domain.RemoteBackedShiftRepository
import au.com.threesixty.cabdispatch.domain.ShiftRepository
import au.com.threesixty.cabdispatch.domain.SpeedSource
import au.com.threesixty.cabdispatch.domain.location.GpsSimulator
import au.com.threesixty.cabdispatch.domain.location.SwitchableSpeedSource
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
import au.com.threesixty.cabdispatch.sync.TollRegistryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Route
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
     * Durable half of [accessToken]/[refreshToken] — see [TokenStore]'s own doc for the real bug
     * (a process restart silently losing all auth) this and the write-through setters below close.
     * `private set`, same convention as [devicePairingStore]: callers read/write
     * [accessToken]/[refreshToken] directly, never this store.
     */
    lateinit var tokenStore: TokenStore
        private set

    /**
     * Mutable holder for the current session's bearer token, read by [authInterceptor] on every
     * request. Updated on login/refresh/logout rather than rebuilding [apiService]. `null` =
     * unauthenticated (auth endpoints only). Every assignment write-throughs to [tokenStore] (see
     * that class's doc) so the token survives a process restart instead of silently reverting to
     * `null` and 401ing every authenticated call until the driver's next full PIN re-login — the
     * `::tokenStore.isInitialized` guard exists only because this property's own `= null` default
     * runs before [init] has constructed [tokenStore] yet; no real caller ever assigns this before
     * [init] completes.
     */
    var accessToken: String? = null
        set(value) {
            field = value
            if (::tokenStore.isInitialized) tokenStore.setAccessToken(value)
        }

    /**
     * The refresh token [DriverAuthRepository][au.com.threesixty.cabdispatch.domain.DriverAuthRepository]'s
     * two login call sites capture alongside [accessToken] — added 2026-09-06 alongside
     * [tokenAuthenticator]. Real gap this closed: [DriverLoginResponseDto][au.com.threesixty.cabdispatch.data.remote.DriverLoginResponseDto]
     * carried a `refresh_token` field the whole time and nothing ever read it, so a 401 from an
     * expired [accessToken] (`ACCESS_TOKEN_EXPIRE_MINUTES`, 30 by default) had no way to recover —
     * every call after that point just failed, same "HTTP 401 Unauthorized" shape observed live on
     * the Live Dispatch panel and at least one trip sync. Write-throughs to [tokenStore] exactly
     * like [accessToken] — see that property's doc.
     */
    var refreshToken: String? = null
        set(value) {
            field = value
            if (::tokenStore.isInitialized) tokenStore.setRefreshToken(value)
        }

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

    /** Whether a technician has ever completed first-install setup on this tablet — see
     * [CommissioningStore]. Chooses which framing the readiness screen takes. */
    lateinit var commissioningStore: CommissioningStore
        private set

    /** See [SessionStore]'s own doc — durable half of [SessionHolder]'s driver identity/vehicle
     * binding/shift id, restored in [init] below so a process restart resumes mid-shift instead of
     * bouncing to the login screen. */
    lateinit var sessionStore: SessionStore
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

        // Restore the bearer/refresh token pair across process death (2026-09-06, real bug found
        // live: "HTTP 401 Unauthorized" permanently stuck on Live Dispatch, a trip stuck "hasn't
        // synced") — see TokenStore's own doc. Must run before anything makes an authenticated
        // call (the OkHttpClient/Retrofit setup below), and assigning through the accessToken/
        // refreshToken properties themselves (not a separate restore path) means every other
        // write site (DriverAuthRepository's two logins, tokenAuthenticator's refresh, every
        // logout) automatically keeps this store in sync with zero further changes needed there.
        tokenStore = TokenStore(appContext)
        accessToken = tokenStore.getAccessToken()
        refreshToken = tokenStore.getRefreshToken()

        // Restore the paired device id across process death (2026-08-28 device-pairing pass) —
        // SessionHolder.deviceId is in-memory only; without this, every cold start forgot pairing
        // and heartbeat silently went back to a no-op even after a real pairing had succeeded.
        devicePairingStore = DevicePairingStore(appContext)
        SessionHolder.deviceId = devicePairingStore.getDeviceId()
        commissioningStore = CommissioningStore(appContext)

        // Restore the driver's session across process death (2026-09-04 session-persistence
        // pass) — see SessionStore's own doc for exactly what is/isn't restored and how shift
        // staleness is decided. attachStore() must run before the restoring set() call below (so
        // that call's own write-through isn't silently dropped) and this whole block must run
        // before anything reads SessionHolder.session — in particular before SplashScreen's
        // postAuthDestination() branches on it to pick the start destination, which is what turns
        // a restored session into "resume mid-shift" instead of "bounce to login" from the driver's
        // point of view. No code change was needed in the nav host itself: postAuthDestination()
        // already branched on SessionHolder.session.value being non-null, it simply had nothing
        // but `null` to ever read before this.
        sessionStore = SessionStore(appContext)
        SessionHolder.attachStore(sessionStore)
        sessionStore.restore()?.let { SessionHolder.set(it) }

        maxiVehicleStore = MaxiVehicleStore(appContext)
        settingsPreferencesStore = SettingsPreferencesStore(appContext)

        database = Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "cabdispatch.db",
        )
            // MIGRATION_8_9: see AppDatabase.kt's doc — the first bump that ships a real
            // Migration, because a real field-test device carrying v8 data crashed hard without
            // one. Never add fallbackToDestructiveMigration here instead (financial trip data).
            .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
            .build()

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
            .authenticator(tokenAuthenticator)
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

        // Best-effort warm-up of the NSW toll-road registry (automatic toll-detection pass) — same
        // "fire-and-forget, never block startup, the fare engine never calls the network directly"
        // shape as the tariff-signing-key warm-up immediately above. [tollRegistryCache.snapshot]
        // returns an honest empty registry (never throws) if this hasn't completed yet by the time
        // a trip starts — see [TollRegistryCache]'s own "offline-empty-cache" doc.
        startupScope.launch { runCatching { tollRegistryCache.refresh() } }

        // Begins supervising session/shift state for the ambient position heartbeat (see
        // [livePositionHeartbeat]'s own doc) — must be started unconditionally here, not left to
        // whenever some screen happens to first read [livePositionHeartbeat], since (unlike every
        // other `by lazy` singleton above) nothing else in this app ever needs to reference this
        // property by name for it to do its job.
        livePositionHeartbeat.start()

        // Begins supervising SessionHolder.deviceIdFlow for the fleet-command heartbeat (kiosk
        // lock / force-update / locate) — see [deviceCommandHeartbeat]'s own doc. Same "must start
        // unconditionally here" reasoning as [livePositionHeartbeat] immediately above: this was
        // the actual production gap (real device `1c9211b61ae15c68`) — the class existed and its
        // polling/auth logic was already correct, but nothing ever constructed or started an
        // instance of it, so an admin's dashboard toggle changed a server-side flag no running
        // code anywhere ever polled.
        deviceCommandHeartbeat.start()
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

    /**
     * OkHttp [Authenticator] counterpart to [authInterceptor] — added 2026-09-06.
     * [authInterceptor] only ever attaches whatever [accessToken] currently holds; this is what
     * actually recovers from an expired one instead of leaving every call after that point 401
     * forever (see [refreshToken]'s doc for the bug this closes).
     *
     * Runs synchronously on OkHttp's own thread (the [Authenticator] contract) — the refresh call
     * below is a plain blocking [okhttp3.Call.execute] against [plainOkHttpClient], not a suspend
     * call through [apiService]; bridging that into a blocking context here would be more complex
     * than one raw request for no benefit. `synchronized(this)` means concurrent 401s from several
     * in-flight requests queue behind one real refresh call rather than each firing their own —
     * the first one through re-checks whether [accessToken] already moved past what ITS specific
     * request failed with (another thread's refresh may have already landed while this one waited
     * for the lock) before deciding a fresh refresh call is actually needed.
     *
     * Returning `null` is OkHttp's own "give up" contract, which propagates the original 401 to
     * the caller — every real caller in this app already treats a 401 as a real, user-facing
     * failure (there is no separate "silently retrying" state to show), so giving up cleanly here
     * (no refresh token to use, or the refresh call itself failed/was rejected) is the right
     * default rather than looping.
     */
    private val tokenAuthenticator = Authenticator { _, response ->
        val path = response.request.url.encodedPath
        // Never try to refresh a 401 from the refresh call itself (a real "this refresh token is
        // itself invalid/expired" answer) or a request this authenticator already retried once
        // (the fresh token was ALSO rejected — refreshing again would not help).
        if (path.endsWith("/v1/auth/refresh") || responseChainLength(response) >= 2) {
            return@Authenticator null
        }
        val currentRefreshToken = refreshToken ?: return@Authenticator null
        val failedAccessToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(this) {
            val newAccessToken = if (accessToken != null && accessToken != failedAccessToken) {
                accessToken
            } else {
                runCatching { performBlockingTokenRefresh(currentRefreshToken) }.getOrNull()
            }
            newAccessToken?.let {
                response.request.newBuilder().header("Authorization", "Bearer $it").build()
            }
        }
    }

    /** A bare, interceptor/authenticator-free client for [tokenAuthenticator]'s own refresh call —
     * deliberately not [okHttpClient] itself (which carries [tokenAuthenticator]): the real
     * refresh token travels in this request's BODY, not an Authorization header, so
     * [authInterceptor] attaching a stale bearer token would be harmless but pointless, and
     * reusing a client that carries this same authenticator risks a confusing recursive-
     * authenticate path for no benefit. */
    private val plainOkHttpClient = OkHttpClient()

    /** The actual `POST /v1/auth/refresh` call [tokenAuthenticator] makes — real request/response,
     * no fabricated fallback. Updates [accessToken]/[refreshToken] in place on success (so the
     * *next* 401 anywhere reads the new pair) and returns the new access token for
     * [tokenAuthenticator] to retry the failed request with immediately. `null` on any failure
     * (network error, non-2xx, malformed body) — [tokenAuthenticator] treats that as "give up",
     * never as "pretend it worked". */
    private fun performBlockingTokenRefresh(currentRefreshToken: String): String? {
        val requestBody = cabDispatchJson
            .encodeToString(RefreshRequestDto.serializer(), RefreshRequestDto(currentRefreshToken))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/v1/auth/refresh")
            .post(requestBody)
            .build()
        plainOkHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val tokens = cabDispatchJson.decodeFromString(RefreshResponseDto.serializer(), body)
            accessToken = tokens.accessToken
            refreshToken = tokens.refreshToken
            return tokens.accessToken
        }
    }

    /** How many times this exact request has already been retried, per OkHttp's own
     * `response.priorResponse` chain — the standard way to bound an [Authenticator]'s retries
     * without a separate counter field. `1` for a fresh (never-retried) response. */
    private fun responseChainLength(response: okhttp3.Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    // --- Offline sync engine (B7): trip queue, tariff cache, sync outbox ---

    val tripDao by lazy { database.tripDao() }
    val shiftDao by lazy { database.shiftDao() }
    val tariffDao by lazy { database.tariffDao() }
    val syncOutboxDao by lazy { database.syncOutboxDao() }
    val tariffSigningKeyDao by lazy { database.tariffSigningKeyDao() }
    val tollRegistryDao by lazy { database.tollRegistryDao() }

    val tripRepository by lazy { TripRepository(tripDao, syncOutboxDao, apiService) }

    /** Local cache of the Ed25519 public key that verifies [tariffCache]'s signed tariffs — see
     * that class's doc and `security/TariffSignatureVerifier.kt`'s `Ed25519TariffSignatureVerifier`. */
    val tariffSigningKeyCache by lazy { TariffSigningKeyCache(tariffSigningKeyDao, apiService) }
    val tariffCache by lazy { TariffCache(tariffDao, apiService, tariffSigningKeyCache) }

    /** Local cache of the real NSW toll-road registry (automatic toll-detection pass) — see
     * [TollRegistryCache]'s own doc. [au.com.threesixty.cabdispatch.domain.FareEngineImpl] reads
     * this (via [au.com.threesixty.cabdispatch.domain.TollRegistryProvider]) once per trip, never
     * per GPS fix. */
    val tollRegistryCache by lazy { TollRegistryCache(tollRegistryDao, apiService) }

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
        SwitchableSpeedSource(
            real = RealLocationProvider(appContext, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
            simulator = gpsSimulator,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    /**
     * Fabricated-GPS test harness (see [GpsSimulator], and read its doc before using it).
     *
     * Constructed unconditionally rather than behind `BuildConfig.DEBUG`, because the tablet in
     * the field runs a DEBUG build against the production backend -- a debug gate here would give
     * the appearance of protection while providing none. What actually keeps a simulated trip out
     * of the real ledger is `TripEntity.simulated`, stamped from the simulator's own state and
     * synced through to the server and dashboard. Idle until something starts it, and its only
     * entry point is the Settings screen's diagnostics section.
     */
    val gpsSimulator: GpsSimulator by lazy {
        GpsSimulator(CoroutineScope(SupervisorJob() + Dispatchers.Default))
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

    // Reverse-geocoding gateway (real pickup/drop-off addresses pass, 2026-09-05) — same REST-only
    // reasoning as the two above, just coordinates -> address instead of the other way around. See
    // MapboxReverseGeocoding's own doc; called at most twice per trip (MeterNavViewModel.resolve-
    // PickupAddress the moment a destination is first picked, CloseAndPayViewModel as a fallback
    // at close) and idempotent either way, never on every tick.
    val mapboxReverseGeocoding: MapboxReverseGeocoding by lazy { MapboxReverseGeocoding(okHttpClient) }
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

    /**
     * Process-lifetime fleet-command heartbeat (kiosk lock / force-update / locate) — see
     * [DeviceCommandHeartbeat]'s own class doc for the full write-up. Own `SupervisorJob`-backed
     * `CoroutineScope`, same reasoning as [livePositionHeartbeat] immediately above: it must keep
     * polling across screen navigation and even while logged off, not be tied to any one screen's
     * ViewModel scope. [init] calls [DeviceCommandHeartbeat.start] on this unconditionally, and
     * [au.com.threesixty.cabdispatch.MainActivity] collects [DeviceCommandHeartbeat.state] to
     * drive screen pinning ([au.com.threesixty.cabdispatch.domain.KioskLockController]) and the
     * two [au.com.threesixty.cabdispatch.ui.overlays.FleetCommandOverlays] banners.
     */
    val deviceCommandHeartbeat: DeviceCommandHeartbeat by lazy {
        DeviceCommandHeartbeat(
            apiService,
            speedSource,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            appContext,
            devicePairingStore,
        )
    }

    // --- Zones (Plot / Statistics screens — named dispatch zones, "plot into a zone", live
    // per-zone demand stats, matching a real competitor taxi meter's zone screens, per
    // backend/app/api/v1/zones.py) ---
    //
    // Thin network-only, same reasoning as [jobsRepository] above (see [ZonesRepository]'s own
    // doc) — no Room/offline-queue story needed, just [apiService].
    val zonesRepository: ZonesRepository by lazy { RemoteBackedZonesRepository(apiService) }

    // --- Driver engagement (dashboard WALLET / RATING / ANNOUNCEMENTS / INCENTIVE tiles, backend
    // commit 58ccfcf's `/v1/me/{wallet,rating,announcements,incentives}` reads) --- thin network-only, same reasoning as [zonesRepository]
    // (see [DriverEngagementRepository]'s own doc).
    val driverEngagementRepository: DriverEngagementRepository by lazy {
        RemoteBackedDriverEngagementRepository(apiService)
    }

    /**
     * Real OTA self-update checker (2026-09-06) — see [AppUpdateChecker]'s own class doc for the
     * full flow and, critically, the Knox Manage / one-system-tap constraints it does not paper
     * over. Thin network+IO-only wrapper around [apiService]/[okHttpClient]/[appContext], same
     * "no own scope needed" shape as [mapboxGeocoding]/[mapboxDirections] above — unlike
     * [deviceCommandHeartbeat]/[livePositionHeartbeat] this is not a self-driving background loop,
     * every call into it ([AppUpdateChecker.checkForUpdate]/[AppUpdateChecker.downloadAndVerify])
     * is explicitly triggered by
     * [ForceUpdatePendingBanner][au.com.threesixty.cabdispatch.ui.overlays.ForceUpdatePendingBanner].
     */
    val appUpdateChecker: AppUpdateChecker by lazy { AppUpdateChecker(apiService, okHttpClient, appContext) }

    // Repository/DAO singletons are added here by sibling agents, e.g.:
    // val fooDao: FooDao by lazy { database.fooDao() }
    // val fooRepository: FooRepository by lazy { FooRepository(fooDao, apiService) }
}
