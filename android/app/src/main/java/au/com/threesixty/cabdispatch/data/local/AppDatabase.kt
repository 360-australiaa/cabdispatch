package au.com.threesixty.cabdispatch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import au.com.threesixty.cabdispatch.data.local.dao.ShiftDao
import au.com.threesixty.cabdispatch.data.local.dao.SyncOutboxDao
import au.com.threesixty.cabdispatch.data.local.dao.TariffDao
import au.com.threesixty.cabdispatch.data.local.dao.TariffSigningKeyDao
import au.com.threesixty.cabdispatch.data.local.dao.TripDao
import au.com.threesixty.cabdispatch.data.local.entity.ShiftEntity
import au.com.threesixty.cabdispatch.data.local.entity.SyncOutboxEntity
import au.com.threesixty.cabdispatch.data.local.entity.TariffEntity
import au.com.threesixty.cabdispatch.data.local.entity.TariffSigningKeyEntity
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
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun shiftDao(): ShiftDao
    abstract fun tariffDao(): TariffDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun tariffSigningKeyDao(): TariffSigningKeyDao
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
