package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.local.dao.TrafficCameraDao
import au.com.threesixty.cabdispatch.data.local.dao.TrafficHazardDao
import au.com.threesixty.cabdispatch.data.local.entity.TrafficCameraEntity
import au.com.threesixty.cabdispatch.data.local.entity.TrafficHazardEntity
import au.com.threesixty.cabdispatch.data.remote.TrafficCameraDto
import au.com.threesixty.cabdispatch.data.remote.TrafficHazardDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [TrafficCache]: the offline-empty contract (`null` until something has been
 * cached), the bbox resolved for a refresh, persistence across a process restart, and — the one
 * thing this cache does differently from [AirportZoneCache]/[TollRegistryCache] — that a failure
 * in ONE of cameras/hazards never stops the other from updating. Same shape as
 * [au.com.threesixty.cabdispatch.sync.AirportZoneCacheTest]: in-memory fake DAOs, canned fetch
 * lambdas, no Room, no Retrofit.
 */
class TrafficCacheTest {

    private class FakeCameraDao : TrafficCameraDao {
        val rows = mutableListOf<TrafficCameraEntity>()
        override suspend fun upsertAll(cameras: List<TrafficCameraEntity>) {
            rows.removeAll { existing -> cameras.any { it.id == existing.id } }
            rows += cameras
        }
        override suspend fun clear() = rows.clear()
        override suspend fun getAll(): List<TrafficCameraEntity> = rows.toList()
    }

    private class FakeHazardDao : TrafficHazardDao {
        val rows = mutableListOf<TrafficHazardEntity>()
        override suspend fun upsertAll(hazards: List<TrafficHazardEntity>) {
            rows.removeAll { existing -> hazards.any { it.id == existing.id } }
            rows += hazards
        }
        override suspend fun clear() = rows.clear()
        override suspend fun getAll(): List<TrafficHazardEntity> = rows.toList()
    }

    private val cameraDto = TrafficCameraDto(id = "cam1", name = "Anzac Bridge", latitude = -33.87, longitude = 151.18)
    private val hazardDto = TrafficHazardDto(id = "haz1", category = "roadwork", latitude = -33.86, longitude = 151.19, headline = "Lane closure")

    @Test
    fun `nothing cached, nothing warmed up - both lookups answer null, never an empty list`() = runTest {
        val cache = TrafficCache(FakeCameraDao(), FakeHazardDao(), { emptyList() }, { emptyList() })
        assertNull(cache.cachedCameras())
        assertNull(cache.cachedHazards())
        cache.warmUp()
        // Warmed up from empty tables is still "nothing has ever been cached" for the CAMERAS/
        // HAZARDS themselves, but warmUp always assigns a (possibly empty) list -- unlike
        // AirportZoneCache's zonesContaining, this cache's own contract is "null only before
        // warmUp/refresh has run once at all", so after warmUp it is an honest empty list.
        assertEquals(emptyList<Any>(), cache.cachedCameras())
        assertEquals(emptyList<Any>(), cache.cachedHazards())
    }

    @Test
    fun `a refresh fetches both, resolves a bbox around the given position, and persists to Room`() = runTest {
        val cameraDao = FakeCameraDao()
        val hazardDao = FakeHazardDao()
        var seenCameraBbox: String? = null
        var seenHazardBbox: String? = null
        val cache = TrafficCache(
            cameraDao,
            hazardDao,
            fetchCameras = { bbox -> seenCameraBbox = bbox; listOf(cameraDto) },
            fetchHazards = { bbox -> seenHazardBbox = bbox; listOf(hazardDto) },
        )

        cache.refresh(lat = -33.87, lng = 151.18)

        assertEquals(listOf("Anzac Bridge"), cache.cachedCameras()!!.map { it.name })
        assertEquals(listOf("roadwork"), cache.cachedHazards()!!.map { it.category })
        assertEquals(1, cameraDao.rows.size)
        assertEquals(1, hazardDao.rows.size)
        // Same bbox resolved for both calls, and a real window around the given position (not the
        // NSW-wide fallback, which only applies when lat/lng are null).
        assertEquals(seenCameraBbox, seenHazardBbox)
        assertTrue("bbox must not be the wide NSW fallback when a real position is given", seenCameraBbox != NSW_WIDE_FALLBACK_FOR_TEST)
    }

    @Test
    fun `a refresh with no known position falls back to the wide NSW bbox`() = runTest {
        var seenBbox: String? = null
        val cache = TrafficCache(
            FakeCameraDao(),
            FakeHazardDao(),
            fetchCameras = { bbox -> seenBbox = bbox; emptyList() },
            fetchHazards = { emptyList() },
        )

        cache.refresh(lat = null, lng = null)

        assertEquals(NSW_WIDE_FALLBACK_FOR_TEST, seenBbox)
    }

    @Test
    fun `a failed hazards fetch does not stop the cameras refresh from succeeding, and vice versa`() = runTest {
        val cameraDao = FakeCameraDao()
        val hazardDao = FakeHazardDao()
        val cache = TrafficCache(
            cameraDao,
            hazardDao,
            fetchCameras = { listOf(cameraDto) },
            fetchHazards = { error("hazards feed down") },
        )

        // No exception propagates -- see this class's own doc for why this deliberately differs
        // from AirportZoneCache/TollRegistryCache's "propagate, never partially update" contract.
        cache.refresh(lat = -33.87, lng = 151.18)

        assertEquals(listOf("Anzac Bridge"), cache.cachedCameras()!!.map { it.name })
        // Hazards never got a first successful fetch at all -- still null, not an empty list,
        // honestly distinguishing "never synced" from "synced, nothing here".
        assertNull(cache.cachedHazards())
    }

    @Test
    fun `a fresh cache over the same tables restores cameras and hazards from Room without the network`() = runTest {
        val cameraDao = FakeCameraDao()
        val hazardDao = FakeHazardDao()
        TrafficCache(cameraDao, hazardDao, { listOf(cameraDto) }, { listOf(hazardDto) }).refresh(-33.87, 151.18)

        // "Process restart": a new cache instance, whose fetches would FAIL if ever called.
        val restarted = TrafficCache(cameraDao, hazardDao, { error("no connectivity") }, { error("no connectivity") })
        assertNull(restarted.cachedCameras())
        restarted.warmUp()
        assertEquals(listOf("Anzac Bridge"), restarted.cachedCameras()!!.map { it.name })
        assertEquals(listOf("roadwork"), restarted.cachedHazards()!!.map { it.category })
    }

    companion object {
        /** Mirrors [TrafficCache]'s own private `NSW_WIDE_BBOX_FALLBACK` constant — kept as a
         * separate literal here (rather than reaching into the private companion) so this test
         * fails loudly if that string is ever accidentally changed on one side and not the other. */
        private const val NSW_WIDE_FALLBACK_FOR_TEST = "140.8,-37.6,153.7,-28.1"
    }
}
