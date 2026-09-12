package au.com.threesixty.cabdispatch.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MIGRATION_15_16]'s backfill: every trip already on disk with a `gpsTraceJson` blob must land in
 * the new `trip_trace_points` table, in order, when this upgrade runs (W4 §3, 2026-09-12
 * optimisation plan — see [au.com.threesixty.cabdispatch.data.local.entity.TripTracePointEntity]'s
 * doc for why: `TripRepository` stops treating `gpsTraceJson` as live the moment this version
 * ships, so a trip mid-flight at upgrade time must not silently lose its already-driven route).
 *
 * Deliberately narrower than [RoomMigrationTest]'s full 8->9->...->16 chain-and-validate: this
 * migration's own logic (the backfill loop) is what needs exercising, not the whole schema
 * history, so this builds just enough of a `trips` table by hand (`clientUuid`, `gpsTraceJson`) to
 * drive [MIGRATION_15_16.migrate] directly and inspect the result — a real SQLite file via
 * Robolectric's native SQLite support, the same [FrameworkSQLiteOpenHelperFactory] plumbing
 * [RoomMigrationTest] uses, just without Room or `MigrationTestHelper` in the loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class TripTracePointMigrationTest {

    private val dbName = "trace-migration-test.db"

    private fun openHelper(): SupportSQLiteOpenHelper {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE trips (clientUuid TEXT NOT NULL PRIMARY KEY, " +
                                "gpsTraceJson TEXT NOT NULL DEFAULT '[]')",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // Not exercised -- this test drives MIGRATION_15_16 directly, below.
                    }
                },
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    @Test
    fun `existing trips' gpsTraceJson blobs become ordered trace-point rows`() {
        val db = openHelper().writableDatabase
        db.execSQL(
            "INSERT INTO trips (clientUuid, gpsTraceJson) VALUES ('trip-a', " +
                "'[{\"lat\":-33.87,\"lng\":151.21,\"speed_kmh\":40.0,\"ts\":\"t0\"}," +
                "{\"lat\":-33.88,\"lng\":151.22,\"speed_kmh\":45.0,\"ts\":\"t1\"}]')",
        )
        // A trip with a completely empty trace (the overwhelming common case -- most trips never
        // rebuild after this point anyway) must backfill zero rows, not fail.
        db.execSQL("INSERT INTO trips (clientUuid, gpsTraceJson) VALUES ('trip-b', '[]')")
        // A trip with a corrupt blob must not take the other trips down with it.
        db.execSQL("INSERT INTO trips (clientUuid, gpsTraceJson) VALUES ('trip-c', 'not json')")

        MIGRATION_15_16.migrate(db)

        val query = "SELECT tripClientUuid, seq, lat, lng, speedKmh, ts FROM trip_trace_points " +
            "ORDER BY tripClientUuid, seq"
        db.query(query).use { cursor ->
            assertEquals(2, cursor.count)

            cursor.moveToPosition(0)
            assertEquals("trip-a", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(-33.87, cursor.getDouble(2), 1e-9)
            assertEquals(151.21, cursor.getDouble(3), 1e-9)
            assertEquals(40.0, cursor.getDouble(4), 1e-9)
            assertEquals("t0", cursor.getString(5))

            cursor.moveToPosition(1)
            assertEquals("trip-a", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(-33.88, cursor.getDouble(2), 1e-9)
            assertEquals("t1", cursor.getString(5))
        }

        // trip-b and trip-c contribute nothing -- an empty trace backfills nothing, and a corrupt
        // one is skipped (best-effort), never crashing the migration for trip-a's valid data.
        db.query("SELECT COUNT(*) FROM trip_trace_points WHERE tripClientUuid IN ('trip-b', 'trip-c')").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }

        db.close()
    }

    @Test
    fun `the new table and index exist and are queryable even with zero trips on disk`() {
        val db = openHelper().writableDatabase

        MIGRATION_15_16.migrate(db)

        db.query("SELECT COUNT(*) FROM trip_trace_points").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        // sqlite_master proves the index was actually created, not just the table.
        val indexQuery = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
            "AND name = 'index_trip_trace_points_tripClientUuid'"
        db.query(indexQuery).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        db.close()
    }
}
