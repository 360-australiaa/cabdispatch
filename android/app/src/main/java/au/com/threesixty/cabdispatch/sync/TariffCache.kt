package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.TariffDao
import au.com.threesixty.cabdispatch.data.local.entity.TariffEntity
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Local cache of the signed tariff/fares-order payload. This is what lets
 * fares keep computing correctly offline for days (B7): the fare engine
 * (sibling agent) is expected to call ONLY [getActiveTariff]/
 * [observeActiveTariff] — both pure Room reads — and never
 * [ApiService.activeTariff] directly. [refresh] is the only method that
 * touches the network, and it is opportunistic/best-effort: call it after
 * login and on reconnect (e.g. from the same trigger that fires
 * [SyncWorker]), but nothing in the app should ever block waiting for it.
 */
class TariffCache(
    private val tariffDao: TariffDao,
    private val apiService: ApiService,
) {

    /** Pure local read — no network, ever. Safe to call from the fare engine's hot path. */
    suspend fun getActiveTariff(region: String, at: Instant = Instant.now()): TariffDto? {
        val row = tariffDao.getActiveForRegion(region, at.toString()) ?: return null
        return decode(row)
    }

    fun observeActiveTariff(region: String, at: Instant = Instant.now()): Flow<TariffDto?> =
        tariffDao.observeActiveForRegion(region, at.toString()).map { row -> row?.let(::decode) }

    /**
     * Fetches `GET /v1/tariffs/active` and caches it. Call when online;
     * throws/propagates network errors to the caller rather than swallowing
     * them, since callers are expected to treat this as best-effort (e.g.
     * `runCatching { tariffCache.refresh(region) }`) — swallowing here would
     * hide failures from a caller that specifically wants to know refresh
     * failed (e.g. to show a "tariff may be stale" indicator).
     */
    suspend fun refresh(region: String): TariffDto {
        val dto = apiService.activeTariff(region = region)
        tariffDao.upsert(
            TariffEntity(
                id = dto.id,
                region = dto.region,
                effectiveFrom = dto.effectiveFrom,
                effectiveTo = dto.effectiveTo,
                rawJson = cabDispatchJson.encodeToString(TariffDto.serializer(), dto),
                fetchedAt = System.currentTimeMillis(),
            ),
        )
        return dto
    }

    private fun decode(row: TariffEntity): TariffDto =
        cabDispatchJson.decodeFromString(TariffDto.serializer(), row.rawJson)
}
