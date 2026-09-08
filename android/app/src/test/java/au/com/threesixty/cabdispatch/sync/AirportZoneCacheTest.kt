package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.AirportZoneDao
import au.com.threesixty.cabdispatch.data.local.entity.AirportZoneEntity
import au.com.threesixty.cabdispatch.data.remote.GeofenceDto
import au.com.threesixty.cabdispatch.data.remote.GeofenceListResponseDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Plain-JVM tests for the airport-zone cache: the offline-empty contract (`null` until something
 * has been cached), the `zonesContaining` geometry and ordering the fare engine relies on
 * (smallest zone first), the defensive fee parse, and persistence across a process restart.
 * No Room, no Retrofit — an in-memory [AirportZoneDao] and a canned fetch.
 */
class AirportZoneCacheTest {

    /** In-memory stand-in for the Room DAO — the same `replaceAll` default the real interface
     * carries, over a plain list. */
    private class FakeAirportZoneDao : AirportZoneDao {
        val rows = mutableListOf<AirportZoneEntity>()
        override suspend fun upsertAll(zones: List<AirportZoneEntity>) {
            rows.removeAll { existing -> zones.any { it.id == existing.id } }
            rows += zones
        }
        override suspend fun clear() = rows.clear()
        override suspend fun getAll(): List<AirportZoneEntity> = rows.toList()
    }

    private fun dto(
        id: String,
        name: String,
        lat: Double,
        lng: Double,
        radiusM: Double,
        fee: JsonPrimitive? = JsonPrimitive("6.43"),
        kind: String = "airport",
    ) = GeofenceDto(id = id, name = name, kind = kind, centerLat = lat, centerLng = lng, radiusM = radiusM, tollAmount = fee)

    // T1 International rank, and a T2 rank circle drawn inside a wider T2/T3 precinct circle.
    private val t1 = dto("t1", "T1 International", -33.9361, 151.1656, 300.0)
    private val t2 = dto("t2", "T2 Domestic", -33.9330, 151.1800, 250.0, fee = JsonPrimitive(6.43))
    private val domesticPrecinct = dto("t23", "T2/T3 Domestic precinct", -33.9330, 151.1800, 600.0, fee = JsonPrimitive("7.00"))

    @Test
    fun `nothing cached, nothing warmed up - the lookup answers null, never an empty list`() = runTest {
        val cache = AirportZoneCache(FakeAirportZoneDao()) { emptyList() }
        assertNull(cache.zonesContaining(-33.9361, 151.1656))
        cache.warmUp()
        // Warmed up from an empty table is still "no zone has ever been cached".
        assertNull(cache.zonesContaining(-33.9361, 151.1656))
    }

    @Test
    fun `after a refresh, zonesContaining sorts overlapping zones smallest first and answers empty outside them`() = runTest {
        val dao = FakeAirportZoneDao()
        val cache = AirportZoneCache(dao) { listOf(t1, domesticPrecinct, t2) }

        cache.refresh()

        // A fix on the T2 rank is inside BOTH the T2 circle and the wider precinct circle.
        val onT2 = cache.zonesContaining(-33.9331, 151.1801)
        assertNotNull(onT2)
        assertEquals(listOf("T2 Domestic", "T2/T3 Domestic precinct"), onT2!!.map { it.name })
        assertEquals(BigDecimal("6.43"), onT2.first().fee)
        assertEquals(BigDecimal("7.00"), onT2.last().fee)

        // Only the T1 circle contains the T1 rank.
        assertEquals(listOf("T1 International"), cache.zonesContaining(-33.9361, 151.1656)!!.map { it.name })

        // Sydney CBD: synced, and in no zone -- an EMPTY list, which the engine reads as "no fee",
        // as distinct from the null above.
        assertEquals(emptyList<Any>(), cache.zonesContaining(-33.8688, 151.2093))

        // And it all went to Room, fee as a 2dp decimal string.
        assertEquals(3, dao.rows.size)
        assertEquals("6.43", dao.rows.first { it.id == "t2" }.feeAmount)
    }

    @Test
    fun `a zone with no parseable fee, or of another kind, is dropped rather than cached with an invented fee`() = runTest {
        val dao = FakeAirportZoneDao()
        val cache = AirportZoneCache(dao) {
            listOf(
                t1,
                dto("bad", "No fee", -33.9, 151.1, 300.0, fee = null),
                dto("junk", "Junk fee", -33.9, 151.1, 300.0, fee = JsonPrimitive("six dollars")),
                dto("toll", "A toll zone", -33.9361, 151.1656, 300.0, kind = "toll"),
            )
        }
        cache.refresh()
        assertEquals(listOf("t1"), dao.rows.map { it.id })
        assertEquals(listOf("T1 International"), cache.zonesContaining(-33.9361, 151.1656)!!.map { it.name })
    }

    @Test
    fun `a failed refresh propagates and leaves the previously cached zones in force`() = runTest {
        val dao = FakeAirportZoneDao()
        var fail = false
        val cache = AirportZoneCache(dao) { if (fail) error("503") else listOf(t1) }
        cache.refresh()
        fail = true

        val thrown = runCatching { cache.refresh() }.exceptionOrNull()
        assertNotNull(thrown)
        assertEquals(1, dao.rows.size)
        assertEquals(listOf("T1 International"), cache.zonesContaining(-33.9361, 151.1656)!!.map { it.name })
    }

    @Test
    fun `a fresh cache over the same table restores the zones from Room without the network - offline persistence`() = runTest {
        val dao = FakeAirportZoneDao()
        AirportZoneCache(dao) { listOf(t1, t2) }.refresh()

        // "Process restart": a new cache instance, whose fetch would FAIL if it were ever called.
        val restarted = AirportZoneCache(dao) { error("no connectivity") }
        assertNull(restarted.zonesContaining(-33.9361, 151.1656)) // not warmed up yet: honest null
        restarted.warmUp()
        assertEquals(listOf("T1 International"), restarted.zonesContaining(-33.9361, 151.1656)!!.map { it.name })
        assertEquals(2, restarted.snapshot().size)
    }

    @Test
    fun `the geofence DTO parses toll_amount whether the server sends a number, a string or null`() {
        val page = cabDispatchJson.decodeFromString<GeofenceListResponseDto>(
            """
            {"items":[
              {"id":"a","tenant_id":"t","name":"T1","kind":"airport","center_lat":-33.9361,"center_lng":151.1656,"radius_m":300,"toll_amount":6.43,"created_at":"2026-09-09T00:00:00Z","updated_at":"2026-09-09T00:00:00Z"},
              {"id":"b","tenant_id":"t","name":"T2","kind":"airport","center_lat":-33.933,"center_lng":151.18,"radius_m":250,"toll_amount":"6.43","created_at":"2026-09-09T00:00:00Z","updated_at":"2026-09-09T00:00:00Z"},
              {"id":"c","tenant_id":"t","name":"R","kind":"region","center_lat":-33.9,"center_lng":151.2,"radius_m":5000,"toll_amount":null,"created_at":"2026-09-09T00:00:00Z","updated_at":"2026-09-09T00:00:00Z"}
            ],"total":3,"skip":0,"limit":200}
            """.trimIndent(),
        )
        assertEquals(3, page.total)
        assertEquals(BigDecimal("6.43"), page.items[0].tollAmountOrNull())
        assertEquals(BigDecimal("6.43"), page.items[1].tollAmountOrNull())
        assertNull(page.items[2].tollAmountOrNull())
        assertTrue(page.items.all { it.radiusM > 0 })
    }
}
