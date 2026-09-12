package au.com.threesixty.cabdispatch.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.com.threesixty.cabdispatch.data.local.AppDatabase
import au.com.threesixty.cabdispatch.data.local.entity.BlackoutResolution
import au.com.threesixty.cabdispatch.data.local.entity.TripBlackoutSegmentEntity
import au.com.threesixty.cabdispatch.data.local.entity.TripEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * W6 (Tests and CI depth, 2026-09-12), task 2's "Room round-trip test" — writes a
 * [TripBlackoutSegmentEntity] through the REAL [TripBlackoutSegmentDao] against a real (in-memory)
 * SQLite database, reads it back through the same DAO, and asserts every field survived unchanged.
 *
 * This is deliberately independent of [MeterAccuracyTest]'s restart-recovery tests, which only ever
 * exercise a hand-built [TripBlackoutSegmentEntity] passed directly to
 * [au.com.threesixty.cabdispatch.domain.FareEngineImpl.resumeTrip] as an in-memory Kotlin object —
 * that proves the fare engine reseeds correctly from SUCH an entity, never that Room itself
 * serialises/deserialises one without silently dropping or mistyping a column (a real risk for a
 * table with two nullable Double pairs, a nullable String, a foreign key and a defaulted enum-as-
 * String column). This test proves the other half: the entity that comes back out of a real
 * database is byte-for-byte the one that went in.
 *
 * Runs under Robolectric (`@RunWith(RobolectricTestRunner::class)`), matching
 * `RoomMigrationTest`'s own justification in this same package's parent directory — no `adb`, no
 * emulator, and Robolectric's bundled native SQLite makes this a real database, not a mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class TripBlackoutSegmentDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TripBlackoutSegmentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // In-memory, not a named file: this test's only job is DAO round-trip fidelity, not
        // migrations (RoomMigrationTest owns those) or cross-process persistence.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.tripBlackoutSegmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The parent [TripEntity] row [TripBlackoutSegmentEntity.tripClientUuid]'s foreign key
     * requires -- Room enforces `FOREIGN KEY ... REFERENCES trips(clientUuid)` (see
     * [TripBlackoutSegmentEntity]'s own `@ForeignKey` annotation) so a segment cannot be written
     * for a trip that does not exist, exactly as it could not on a real device. */
    private suspend fun insertParentTrip(clientUuid: String) {
        db.tripDao().insert(
            TripEntity(
                clientUuid = clientUuid,
                vehicleId = "veh-1",
                driverId = "drv-1",
                shiftId = null,
                tariffId = "tariff-urban-2026",
                type = "rank_hail",
                status = "hired",
                timeClass = "day",
                isPeak = false,
                maxi = false,
                startAt = "2026-09-12T02:00:00Z",
                startLat = -33.87,
                startLng = 151.21,
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
    }

    @Test
    fun `an open blackout segment round-trips through the real DAO with every field intact`() = runTest {
        insertParentTrip("trip-open-1")
        val written = TripBlackoutSegmentEntity(
            clientUuid = "segment-open-1",
            tripClientUuid = "trip-open-1",
            startedAtIso = "2026-09-12T02:05:00Z",
            endedAtIso = null,
            entryLat = -33.9200,
            entryLng = 151.1500,
            exitLat = null,
            exitLng = null,
            entryWasMoving = true,
            resolution = BlackoutResolution.NONE.name,
            billedDistanceKm = "0",
            corridorRoadId = null,
            createdAt = 2_000L,
            updatedAt = 2_000L,
        )
        dao.upsert(written)

        val open = dao.openSegmentFor("trip-open-1")
        assertEquals("openSegmentFor must find the still-open row", written, open)

        val all = dao.forTrip("trip-open-1")
        assertEquals(1, all.size)
        assertEquals(written, all.single())
    }

    @Test
    fun `closing a blackout segment overwrites the same row rather than inserting a second one`() = runTest {
        // Mirrors the entity's own "written twice" doc -- once OPEN (endedAtIso null), once more
        // when it closes, both under the SAME clientUuid, via the DAO's REPLACE conflict strategy.
        insertParentTrip("trip-close-1")
        val opened = TripBlackoutSegmentEntity(
            clientUuid = "segment-close-1",
            tripClientUuid = "trip-close-1",
            startedAtIso = "2026-09-12T02:05:00Z",
            entryLat = -33.9200,
            entryLng = 151.1500,
            entryWasMoving = true,
            createdAt = 2_000L,
            updatedAt = 2_000L,
        )
        dao.upsert(opened)
        assertEquals("the open row must be findable before it closes", opened, dao.openSegmentFor("trip-close-1"))

        val closed = opened.copy(
            endedAtIso = "2026-09-12T02:07:15Z",
            exitLat = -33.9200,
            exitLng = 151.1600,
            resolution = BlackoutResolution.CORRIDOR.name,
            billedDistanceKm = "1.234",
            corridorRoadId = "TUNNEL",
            updatedAt = 3_000L,
        )
        dao.upsert(closed)

        // Exactly one row for this trip -- REPLACE, not a second INSERT.
        val all = dao.forTrip("trip-close-1")
        assertEquals(1, all.size)
        assertEquals("the closing write must fully replace the open row's fields", closed, all.single())

        // And it no longer reads as open.
        assertNull("a closed segment must not be returned by openSegmentFor", dao.openSegmentFor("trip-close-1"))
    }

    @Test
    fun `every field of a resolved CORRIDOR segment survives the round trip exactly`() = runTest {
        // Deliberately exercises every column, including the two nullable Double pairs and the
        // nullable corridorRoadId, at real (non-default) values -- a column that Room silently
        // mistyped or dropped would show up here as a field-by-field mismatch, not just a missing
        // row.
        insertParentTrip("trip-corridor-1")
        val segment = TripBlackoutSegmentEntity(
            clientUuid = "segment-corridor-1",
            tripClientUuid = "trip-corridor-1",
            startedAtIso = "2026-09-12T03:10:00Z",
            endedAtIso = "2026-09-12T03:12:40Z",
            entryLat = -33.9200,
            entryLng = 151.1500,
            exitLat = -33.9200,
            exitLng = 151.1600,
            entryWasMoving = true,
            resolution = BlackoutResolution.CORRIDOR.name,
            billedDistanceKm = BigDecimal("1.437").toPlainString(),
            corridorRoadId = "TUNNEL",
            createdAt = 4_000L,
            updatedAt = 5_000L,
        )
        dao.upsert(segment)

        val readBack = dao.forTrip("trip-corridor-1").single()
        assertEquals(segment.clientUuid, readBack.clientUuid)
        assertEquals(segment.tripClientUuid, readBack.tripClientUuid)
        assertEquals(segment.startedAtIso, readBack.startedAtIso)
        assertEquals(segment.endedAtIso, readBack.endedAtIso)
        assertEquals(segment.entryLat, readBack.entryLat, 0.0)
        assertEquals(segment.entryLng, readBack.entryLng, 0.0)
        assertEquals(segment.exitLat!!, readBack.exitLat!!, 0.0)
        assertEquals(segment.exitLng!!, readBack.exitLng!!, 0.0)
        assertEquals(segment.entryWasMoving, readBack.entryWasMoving)
        assertEquals(segment.resolution, readBack.resolution)
        assertEquals(segment.billedDistanceKm, readBack.billedDistanceKm)
        assertEquals(segment.corridorRoadId, readBack.corridorRoadId)
        assertEquals(segment.createdAt, readBack.createdAt)
        assertEquals(segment.updatedAt, readBack.updatedAt)
        // Belt and braces over the field-by-field checks above: the whole data class, at once.
        assertEquals(segment, readBack)
    }

    @Test
    fun `deleting the parent trip cascades to its blackout segments`() = runTest {
        // FOREIGN KEY ... ON DELETE CASCADE (MIGRATION_14_15) -- a trip purged from the device
        // (never happens for a real closed trip, but the constraint exists and must actually work)
        // must not leave an orphaned audit-trail row with a dangling tripClientUuid.
        insertParentTrip("trip-cascade-1")
        dao.upsert(
            TripBlackoutSegmentEntity(
                clientUuid = "segment-cascade-1",
                tripClientUuid = "trip-cascade-1",
                startedAtIso = "2026-09-12T04:00:00Z",
                entryLat = -33.9200,
                entryLng = 151.1500,
                entryWasMoving = true,
                createdAt = 6_000L,
                updatedAt = 6_000L,
            ),
        )
        assertEquals(1, dao.forTrip("trip-cascade-1").size)

        // Raw SQL, not a DAO method: `TripDao` has no delete method today (nothing in production
        // ever deletes a trip), so this reaches straight for the row through Room's own
        // `SupportSQLiteDatabase` purely to exercise the FOREIGN KEY ... ON DELETE CASCADE
        // constraint itself, without inventing a production API this test is the only caller of.
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM trips WHERE clientUuid = ?",
            arrayOf("trip-cascade-1"),
        )

        assertEquals(
            "the cascade delete must remove the child segment along with its parent trip",
            0,
            dao.forTrip("trip-cascade-1").size,
        )
    }
}
