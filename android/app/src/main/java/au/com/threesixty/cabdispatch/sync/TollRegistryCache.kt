package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.TollRegistryDao
import au.com.threesixty.cabdispatch.data.local.entity.TollGantryEntity
import au.com.threesixty.cabdispatch.data.local.entity.TollRoadEntity
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.TollGantryDto
import au.com.threesixty.cabdispatch.data.remote.TollRoadDto
import au.com.threesixty.cabdispatch.data.remote.TollTimeOfDayRateDto
import au.com.threesixty.cabdispatch.domain.fare.TimeOfDayRateRef
import au.com.threesixty.cabdispatch.domain.fare.TollGantryRef
import au.com.threesixty.cabdispatch.domain.fare.TollPriceRef
import au.com.threesixty.cabdispatch.domain.fare.TollRegistrySnapshot
import au.com.threesixty.cabdispatch.domain.fare.TollRoadRef
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.math.BigDecimal

/**
 * Local cache of the real NSW toll-road registry (`GET /v1/toll-roads` + one
 * `GET /v1/toll-roads/{id}` per road, for gantry coordinates). Same role/shape [TariffCache] plays
 * for fares: this is what lets [au.com.threesixty.cabdispatch.domain.fare.onFix] keep
 * auto-detecting tolls for a trip with zero connectivity (B7) — the fare engine is expected to
 * call ONLY [snapshot] (a pure Room read), never [ApiService.tollRoads]/[ApiService.tollRoadDetail]
 * directly. [refresh] is the only method that touches the network, and it is
 * opportunistic/best-effort: call it after login and on reconnect (same trigger points
 * [TariffCache.refresh] uses), but nothing on the fare-engine hot path should ever block waiting
 * for it.
 *
 * **Offline-empty-cache contract (explicit, load-bearing):** [snapshot] returns
 * [TollRegistrySnapshot.EMPTY] — never throws, never blocks on the network — when nothing has ever
 * been cached (a brand-new install with no connectivity yet before its first trip). An empty
 * snapshot's [TollRegistrySnapshot.gantries] is empty, so
 * [au.com.threesixty.cabdispatch.domain.fare.onFix] finds no gantries near any fix and detects
 * nothing for the whole trip — the meter falls back to exactly today's manual-only behaviour
 * (driver-tapped [au.com.threesixty.cabdispatch.domain.TollPresets] chips), never fails the trip
 * and never guesses a toll. See [au.com.threesixty.cabdispatch.domain.FareEngineImpl]'s own doc for
 * where this is surfaced honestly to the driver.
 */
class TollRegistryCache(
    private val dao: TollRegistryDao,
    private val apiService: ApiService,
) {

    /** Pure local read — no network, ever. Safe to call from the fare engine's hot path (loaded
     * once per trip at [au.com.threesixty.cabdispatch.domain.FareEngineImpl.startTrip] time, not
     * once per GPS fix). Returns [TollRegistrySnapshot.EMPTY] (not an error) when nothing has ever
     * been cached — see this class's own "offline-empty-cache" doc above. */
    suspend fun snapshot(): TollRegistrySnapshot {
        val roads = dao.getAllRoads()
        if (roads.isEmpty()) return TollRegistrySnapshot.EMPTY
        val gantries = dao.getAllGantries()
        return TollRegistrySnapshot(
            roadsById = roads.associate { it.id to it.toRef() },
            gantries = gantries.map { it.toRef() },
        )
    }

    /**
     * Fetches the full registry (`GET /v1/toll-roads`, then one `GET /v1/toll-roads/{id}` per road
     * for its real gantry coordinates — 15 calls total for the real 14-road dataset) and replaces
     * the ENTIRE local cache atomically ([TollRegistryDao.replaceAll]). Call when online; throws/
     * propagates network errors to the caller rather than swallowing them, same "callers wrap this
     * in `runCatching`" contract [TariffCache.refresh] documents — a failure ANYWHERE in the fetch
     * (the list call, or any one road's detail call) means [TollRegistryDao.replaceAll] is never
     * called at all, so the previously cached (fully consistent) registry is left exactly as it
     * was rather than partially overwritten with a mix of old and new roads.
     */
    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val roads = apiService.tollRoads()
        val roadEntities = roads.map { it.toEntity(now) }
        val gantryEntities = roads.flatMap { road ->
            apiService.tollRoadDetail(road.id).gantries.map { it.toEntity(road.id, now) }
        }
        dao.replaceAll(roadEntities, gantryEntities)
    }

    private fun TollRoadDto.toEntity(fetchedAt: Long): TollRoadEntity = TollRoadEntity(
        id = id,
        name = name,
        pricingModel = pricingModel,
        directional = directional,
        derivedCorridorKm = derivedCorridorKm,
        priceClassAMax = currentPrice?.priceClassAMax,
        capClassA = currentPrice?.capClassA,
        rateClassAPerKm = currentPrice?.rateClassAPerKm,
        flagfallClassA = currentPrice?.flagfallClassA,
        timeOfDayRatesJson = currentPrice?.timeOfDayRatesClassA?.let { cabDispatchJson.encodeToString(it) },
        confidence = currentPrice?.confidence,
        fetchedAt = fetchedAt,
    )

    private fun TollGantryDto.toEntity(roadId: String, fetchedAt: Long): TollGantryEntity = TollGantryEntity(
        id = id,
        tollRoadId = roadId,
        latitude = latitude,
        longitude = longitude,
        fetchedAt = fetchedAt,
    )

    private fun TollRoadEntity.toRef(): TollRoadRef = TollRoadRef(
        id = id,
        name = name,
        pricingModel = pricingModel,
        directional = directional,
        // derivedCorridorKm is deliberately NOT carried into TollRoadRef — see that entity
        // column's own doc for why the on-device detector no longer reads it at all.
        currentPrice = confidence?.let { conf ->
            TollPriceRef(
                priceClassAMax = priceClassAMax?.toBigDecimalOrNull(),
                capClassA = capClassA?.toBigDecimalOrNull(),
                ratePerKmClassA = rateClassAPerKm?.toBigDecimalOrNull(),
                flagfallClassA = flagfallClassA?.toBigDecimalOrNull(),
                timeOfDayRatesClassA = timeOfDayRatesJson?.let { json ->
                    runCatching { cabDispatchJson.decodeFromString<List<TollTimeOfDayRateDto>>(json) }.getOrNull()
                }?.map { TimeOfDayRateRef(band = it.band, price = it.price) },
                confidence = conf,
            )
        },
    )

    private fun TollGantryEntity.toRef(): TollGantryRef =
        TollGantryRef(id = id, tollRoadId = tollRoadId, latitude = latitude, longitude = longitude)

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
}
