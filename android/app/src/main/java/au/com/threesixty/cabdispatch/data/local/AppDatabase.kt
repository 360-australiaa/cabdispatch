package au.com.threesixty.cabdispatch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import au.com.threesixty.cabdispatch.data.local.dao.ShiftDao
import au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao
import au.com.threesixty.cabdispatch.data.local.dao.TariffDao
import au.com.threesixty.cabdispatch.data.local.dao.TariffSigningKeyDao
import au.com.threesixty.cabdispatch.data.local.dao.TollRegistryDao
import au.com.threesixty.cabdispatch.data.local.dao.TripDao
import au.com.threesixty.cabdispatch.data.local.entity.ShiftEntity
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TariffEntity
import au.com.threesixty.cabdispatch.data.local.entity.TariffSigningKeyEntity
import au.com.threesixty.cabdispatch.data.local.entity.TollGantryEntity
import au.com.threesixty.cabdispatch.data.local.entity.TollPointEntity
import au.com.threesixty.cabdispatch.data.local.entity.TollRoadEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity

/**
 * Offline-first local store (B7: "Full trips run offline; queue in Room;
 * WorkManager sync with idempotency keys").
 *
 * Version bumped 1 -> 2 by the offline sync-engine agent, adding the trip
 * queue ([TripEntity]), shift record ([ShiftEntity]), tariff cache
 * ([TariffEntity]) and sync outbox ([SyncOutboxEntity]) — see each entity's
 * doc comment for its role. Version bumped 2 -> 3 (tariff-signature-verification pass) adding
 * the local cache of the tariff-signing public key ([TariffSigningKeyEntity]) — see that
 * entity's doc and [au.com.threesixty.cabdispatch.sync.TariffSigningKeyCache]. Version bumped
 * 3 -> 4 (payment-methods/dispute pass) adding three new nullable [TripEntity] columns
 * (`voucherCode`, `accountReference`, `splitPaymentsJson`) — no new entity, just new columns on an
 * existing one, see that class's doc for each. Version bumped 4 -> 5 (2026-08-10 meter-polish
 * pass, "Set Price") adding one new nullable [TripEntity] column (`negotiatedTotal`) — same "no
 * new entity, no Migration" shortcut as the 3 -> 4 bump, for the same still-pre-release reason.
 * Version bumped 5 -> 6 (Point to Point Transport (Fares) Order 2026 compliance pass) adding two
 * new defaulted [TripEntity] columns (`passengerCount` Int = 1, `wheelchairHiring` Boolean =
 * false) feeding the fare engine's maxi-rate eligibility check — same no-Migration shortcut again.
 * Version bumped 6 -> 7 (Close & Pay "tips" pass) adding one new nullable [TripEntity] column
 * (`tip`) — same no-Migration shortcut again, for the same still-pre-release reason.
 * Version bumped 7 -> 8 (History/Earnings real-data pass, Phase C 2026-09-03) adding two new
 * nullable [TripEntity] columns (`pickupAddress`, `dropoffAddress`) — same no-Migration shortcut
 * again, for the same still-pre-release reason.
 * Version bumped 8 -> 9 (maxi-at-airport-rank fare-integrity fix, 2026-09-05) adding one new
 * defaulted [TripEntity] column (`airportRankRequestedMaxi` Boolean = false) — the third input to
 * the fare engine's maxi-rate eligibility check (alongside `passengerCount`/`wheelchairHiring`,
 * added in the 5 -> 6 bump above) was already read correctly on-device but was never persisted or
 * sent to the server.
 *
 * Version bumped 9 -> 10 (automatic NSW toll-road detection pass) adding two new entities —
 * [TollRoadEntity]/[TollGantryEntity], the local cache of `GET /v1/toll-roads` that lets
 * [au.com.threesixty.cabdispatch.domain.fare.onFix] auto-detect toll-road crossings with zero
 * connectivity (see [au.com.threesixty.cabdispatch.sync.TollRegistryCache]'s doc) — and two new
 * defaulted [TripEntity] columns (`autoTolledRoadsJson`, `unpricedTollRoadIdsJson`) recording, for
 * local audit only, which roads a trip's auto-detected/needs-manual-entry tolls came from (the
 * actual dollar figure was already flowing through the pre-existing `tolls` column — see
 * [TripEntity.autoTolledRoadsJson]'s own doc for exactly what these two add on top). Also carries
 * [TollRoadEntity]'s `rateClassAPerKm`/`flagfallClassA` columns (2026-09 pricing correction, folded
 * into this same not-yet-released version rather than a separate bump — see that entity's own
 * doc for why the device no longer derives a `distance`-model rate from a corridor length). Real
 * `Migration` per [MIGRATION_8_9]'s own precedent below — never `fallbackToDestructiveMigration`
 * for financial/compliance trip data.
 *
 * **This is the first bump to actually ship a real `Migration`** ([MIGRATION_8_9] below). Every
 * earlier "no-Migration shortcut" bump above assumed "this project has never shipped v1 (no
 * installed base to migrate)" — that assumption held only as long as every test device got a
 * fresh uninstall between builds. It doesn't: a real tablet field-tested at v8 crashed hard
 * (`IllegalStateException: A migration from 8 to 9 was required but not found`) the moment a v9
 * build was installed over it (confirmed live, 2026-09-05). Do NOT reach for
 * `fallbackToDestructiveMigration()` to paper over a future bump instead of writing a real
 * `Migration` — offline trip data is financial/compliance evidence per B6 ("immutable trip log"),
 * and it turns out real devices really do carry it across a version bump now.
 *
 * Local DB encryption (SQLCipher, per B6 anti-tamper: "local DB encrypted")
 * is left to a future pass — this class stays plain Room for now so the
 * skeleton builds without an extra native dependency nobody can verify in
 * this SDK-less sandbox.
 *
 * When adding another entity/DAO (e.g. a future `ShiftRepository`):
 *   1. Add the `@Entity` data class under data/local/entity/.
 *   2. Add the corresponding `@Dao` interface under data/local/dao/.
 *   3. List the entity class in `entities = [...]` below.
 *   4. Add an abstract `fun xDao(): XDao` accessor.
 *   5. Bump `version` and supply a Room `Migration`.
 *   6. Register the DAO as a singleton in [au.com.threesixty.cabdispatch.data.AppContainer].
 */
@Database(
    entities = [
        TripEntity::class,
        ShiftEntity::class,
        TariffEntity::class,
        SyncOutboxEntity::class,
        TariffSigningKeyEntity::class,
        TollRoadEntity::class,
        TollPointEntity::class,
        TollGantryEntity::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun shiftDao(): ShiftDao
    abstract fun tariffDao(): TariffDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun tariffSigningKeyDao(): TariffSigningKeyDao
    abstract fun tollRegistryDao(): TollRegistryDao
}

/**
 * Real migration for the 8 -> 9 bump — see [AppDatabase]'s doc for why this one (unlike every
 * earlier bump) actually needs one. A single defaulted-`false` column add; `trips` is the only
 * table [TripEntity] backs (see its `@Entity(tableName = "trips")`).
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN airportRankRequestedMaxi INTEGER NOT NULL DEFAULT 0")
    }
}

/** Real migration for the 9 -> 10 bump (automatic NSW toll-road detection pass) — see
 * [AppDatabase]'s doc. Three brand-new tables (empty until the next [au.com.threesixty.cabdispatch.sync.TollRegistryCache.refresh]
 * succeeds — see that class's "offline-empty-cache" doc for why an empty cache is always a safe,
 * handled state, never a crash) plus two new defaulted `trips` columns.
 *
 * Extended IN PLACE (rather than adding an 11) by the per-toll-point follow-up, because version 10
 * has never been installed anywhere: the toll-detection work it belongs to was built but never
 * shipped to a device, so no database in existence is at v10 and there is nothing for a 10 -> 11
 * step to migrate. If a v10 build HAS since reached any device, this must become a separate
 * MIGRATION_10_11 instead — silently changing the shape a shipped version created is exactly the
 * corruption `fallbackToDestructiveMigration` was avoided to prevent.
 *
 * **How to check this SQL is right, since nothing here does it for you.** Room compares a
 * migration's result against the schema it expects only at RUNTIME, on first open: one mismatched
 * column type or a missing index is not a build failure, it is a crash on launch for every
 * upgrading device. Room's schema export (`room.schemaLocation`) would surface it in the diff, but
 * it produces nothing on this project's kapt setup (tried; not worth further chasing). The direct
 * check, which is what the statements below were verified against, is Room's OWN generated
 * `createAllTables`:
 *
 *     ./gradlew kaptDebugKotlin
 *     grep 'CREATE TABLE IF NOT EXISTS `toll_'  *       app/build/generated/source/kapt/debug/au/com/threesixty/cabdispatch/data/local/AppDatabase_Impl.java
 *
 * That is the exact SQL Room expects for the CURRENT version. Every statement a migration writes
 * must match its line there character-for-character in column names, types, nullability, primary
 * key, foreign-key clause and indices. Do this for any future migration too. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `toll_roads` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `pricingModel` TEXT NOT NULL,
                `chargingPolicy` TEXT NOT NULL,
                `networkGroup` TEXT,
                `directional` TEXT,
                `derivedCorridorKm` TEXT,
                `priceClassAMax` TEXT,
                `capClassA` TEXT,
                `rateClassAPerKm` TEXT,
                `flagfallClassA` TEXT,
                `networkCapClassA` TEXT,
                `timeOfDayRatesJson` TEXT,
                `confidence` TEXT,
                `fetchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `toll_gantries` (
                `id` TEXT NOT NULL,
                `tollRoadId` TEXT NOT NULL,
                `tollPointId` TEXT,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `fetchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`tollRoadId`) REFERENCES `toll_roads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `toll_points` (
                `id` TEXT NOT NULL,
                `tollRoadId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `priceClassA` TEXT,
                `confidence` TEXT,
                `fetchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`tollRoadId`) REFERENCES `toll_roads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_toll_points_tollRoadId` ON `toll_points` (`tollRoadId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_toll_gantries_tollRoadId` ON `toll_gantries` (`tollRoadId`)")
        db.execSQL("ALTER TABLE trips ADD COLUMN simulated INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN autoTolledRoadsJson TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("ALTER TABLE trips ADD COLUMN unpricedTollRoadIdsJson TEXT NOT NULL DEFAULT '[]'")
    }
}

/**
 * **Placeholder for the version A1 owns.**
 *
 * The global-meter program assigns Room versions across the two concurrent Android workstreams:
 * A1 (fare-engine blockers) takes 11, A2 (auth/sync blockers) takes 12. A1's migration had not
 * landed on the integration trunk this branch is based on (`529793f`), so a 10 -> 11 step does not
 * exist yet — and without one, Room finds no path from a v10 device to [MIGRATION_11_12] below and
 * crashes on launch with exactly the `IllegalStateException` [AppDatabase]'s doc describes.
 *
 * So this branch ships a deliberate no-op to keep the chain continuous and self-contained. It does
 * nothing because A2 changes nothing at version 11.
 *
 * **On merge with A1, delete this object** and register A1's real `MIGRATION_10_11` in its place.
 * The duplicate declaration will make the conflict impossible to miss, which is the point. Nothing
 * has shipped at v11 or v12, so redefining what 11 means at merge time is safe.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) = Unit
}

/**
 * Real migration for the 11 -> 12 bump (A2 auth/sync blockers) — two new
 * [au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity] columns supporting the outbox
 * retry policy:
 *
 * - `nextAttemptAt` (finding S1) — epoch-millis before which a row must not be retried. Defaults to
 *   `0` = "eligible now", which is the right answer for every row already queued on an upgrading
 *   device: they have done nothing wrong and should drain on the next trigger exactly as before.
 * - `deadLettered` (findings S1/S2) — terminal state for a row that can never succeed. Defaults to
 *   `0`, again correct for existing rows.
 *
 * Both defaults mean an upgrading tablet's pending trips keep their current behaviour, and no
 * offline trip data is touched. Boolean is `INTEGER NOT NULL` in Room's SQLite mapping, matching
 * how `simulated`/`airportRankRequestedMaxi` are declared in the migrations above.
 *
 * Verified against Room's own generated `createAllTables` per [MIGRATION_9_10]'s "How to check this
 * SQL is right" note — `./gradlew kaptDebugKotlin`, then read the `sync_outbox` CREATE TABLE line in
 * `app/build/generated/source/kapt/debug/au/com/threesixty/cabdispatch/data/local/AppDatabase_Impl.java`.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_outbox ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sync_outbox ADD COLUMN deadLettered INTEGER NOT NULL DEFAULT 0")
    }
}
