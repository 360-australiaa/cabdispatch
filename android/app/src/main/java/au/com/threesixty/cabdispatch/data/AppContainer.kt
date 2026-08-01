package au.com.threesixty.cabdispatch.data

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.data.local.AppDatabase
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.repository.TripRepository
import au.com.threesixty.cabdispatch.domain.QrScanner
import au.com.threesixty.cabdispatch.domain.RemoteBackedShiftRepository
import au.com.threesixty.cabdispatch.domain.ShiftRepository
import au.com.threesixty.cabdispatch.domain.SpeedSource
import au.com.threesixty.cabdispatch.domain.StubQrScanner
import au.com.threesixty.cabdispatch.domain.StubSpeedSource
import au.com.threesixty.cabdispatch.domain.StubTripStatsRepository
import au.com.threesixty.cabdispatch.domain.TripStatsRepository
import au.com.threesixty.cabdispatch.domain.fare.FareEngine as PureFareEngine
import au.com.threesixty.cabdispatch.hardware.payments.CardPaymentGateway
import au.com.threesixty.cabdispatch.hardware.payments.MockCardPaymentGateway
import au.com.threesixty.cabdispatch.hardware.printing.MockReceiptPrinterGateway
import au.com.threesixty.cabdispatch.hardware.printing.ReceiptPrinterGateway
import au.com.threesixty.cabdispatch.hardware.receipt.EmailReceiptGateway
import au.com.threesixty.cabdispatch.hardware.receipt.MockEmailReceiptGateway
import au.com.threesixty.cabdispatch.hardware.receipt.MockSmsReceiptGateway
import au.com.threesixty.cabdispatch.hardware.receipt.SmsReceiptGateway
import au.com.threesixty.cabdispatch.security.RsaTariffSignatureVerifier
import au.com.threesixty.cabdispatch.security.TariffSignatureVerifier
import au.com.threesixty.cabdispatch.sync.ConnectivitySyncTrigger
import au.com.threesixty.cabdispatch.sync.SyncWorker
import au.com.threesixty.cabdispatch.sync.TariffCache
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

    fun init(context: Context) {
        val appContext = context.applicationContext

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

        val okHttpClient = OkHttpClient.Builder()
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
    }

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

    val tripRepository by lazy { TripRepository(tripDao, syncOutboxDao, apiService) }
    val tariffCache by lazy { TariffCache(tariffDao, apiService) }

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

    // Hardware interfaces — see android/README.md "Real vs mocked". Only
    // [tariffSignatureVerifier] is a real implementation; the rest are
    // clearly-labeled mocks (no certified payment/printer/SMS/email hardware
    // or backend endpoints exist to integrate against in this sandbox).
    val cardPaymentGateway: CardPaymentGateway by lazy { MockCardPaymentGateway() }
    val receiptPrinterGateway: ReceiptPrinterGateway by lazy { MockReceiptPrinterGateway() }
    val smsReceiptGateway: SmsReceiptGateway by lazy { MockSmsReceiptGateway() }
    val emailReceiptGateway: EmailReceiptGateway by lazy { MockEmailReceiptGateway() }
    val tariffSignatureVerifier: TariffSignatureVerifier by lazy { RsaTariffSignatureVerifier() }

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
    val qrScanner: QrScanner by lazy { StubQrScanner() }
    val tripStatsRepository: TripStatsRepository by lazy { StubTripStatsRepository() }
    val shiftRepository: ShiftRepository by lazy { RemoteBackedShiftRepository(apiService) }
    val speedSource: SpeedSource by lazy { StubSpeedSource() }

    // Repository/DAO singletons are added here by sibling agents, e.g.:
    // val fooDao: FooDao by lazy { database.fooDao() }
    // val fooRepository: FooRepository by lazy { FooRepository(fooDao, apiService) }
}
