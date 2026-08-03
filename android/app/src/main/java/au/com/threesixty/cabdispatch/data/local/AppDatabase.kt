package au.com.threesixty.cabdispatch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
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
 * entity's doc and [au.com.threesixty.cabdispatch.sync.TariffSigningKeyCache]. No Migration
 * object is supplied for either bump because this project has never shipped v1 (no installed
 * base to migrate); the schema is still pre-release. Once this ships, bumping `version` again
 * MUST come with a real `Migration` — do NOT reach for
 * `fallbackToDestructiveMigration()`, offline trip data is financial/
 * compliance evidence per B6 ("immutable trip log").
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
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun shiftDao(): ShiftDao
    abstract fun tariffDao(): TariffDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun tariffSigningKeyDao(): TariffSigningKeyDao
}
