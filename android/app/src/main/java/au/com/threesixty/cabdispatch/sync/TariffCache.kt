package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.cabDispatchJson
import au.com.threesixty.cabdispatch.data.local.dao.TariffDao
import au.com.threesixty.cabdispatch.data.local.entity.TariffEntity
import au.com.threesixty.cabdispatch.data.remote.ApiService
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.security.Ed25519TariffSignatureVerifier
import au.com.threesixty.cabdispatch.security.canonicalTariffPayload
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
 *
 * Anti-tamper (spec B6): [refresh] verifies [TariffDto.signature] against [signingKeyCache]'s
 * Ed25519 public key (see [canonicalTariffPayload]) BEFORE ever calling [TariffDao.upsert] —
 * a tariff that fails verification throws [TariffSignatureException] and is never written to
 * Room, so the previously-cached (already-verified) tariff simply stays in place until a
 * verifiable one shows up. [getActiveTariff]/[observeActiveTariff] deliberately do NOT
 * re-verify on every read: the ingestion boundary ([refresh]) is the one place a signature is
 * checked, matching the fare engine's requirement that local reads stay cheap/synchronous-ish
 * pure-Room reads on its hot path — trusting rows this class itself already verified before
 * writing them is not a gap, it's the same "verify at the boundary, trust the local store after"
 * shape [TariffEntity] already uses for the tariff data itself.
 */
class TariffCache(
    private val tariffDao: TariffDao,
    private val apiService: ApiService,
    private val signingKeyCache: TariffSigningKeyCache,
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
     *
     * Also verifies [TariffDto.signature] (see [verifySignatureOrThrow]) before caching — a
     * failed verification throws [TariffSignatureException], which propagates exactly like a
     * network failure would to any `runCatching` caller above.
     */
    suspend fun refresh(region: String): TariffDto {
        val dto = apiService.activeTariff(region = region)
        verifySignatureOrThrow(dto)
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

    /**
     * Anti-tamper check (spec B6: "signed tariff payloads ... verified on device"). Prefers
     * [TariffSigningKeyCache.getCachedPublicKey] — a pure local read — over a fresh network
     * fetch, specifically so a device that is offline *right now* but has verified a tariff
     * successfully at some point in the past keeps working exactly as B7 requires: "offline"
     * alone must never be treated as "fail closed" here. A network [TariffSigningKeyCache.refresh]
     * is only attempted as a fallback when nothing has ever been cached (e.g. a genuinely
     * first-ever launch that also has no connectivity yet — in which case there is no key to
     * verify against at all, and failing is correct, not a false positive).
     */
    private suspend fun verifySignatureOrThrow(dto: TariffDto) {
        val signature = dto.signature
            ?: throw TariffSignatureException("Tariff ${dto.id} has no signature — refusing to cache")
        val publicKeyBase64 = signingKeyCache.getCachedPublicKey()
            ?: runCatching { signingKeyCache.refresh() }.getOrNull()
            ?: throw TariffSignatureException(
                "No tariff-signing public key available (never cached, and a fresh fetch failed " +
                    "or the device is offline) — cannot verify tariff ${dto.id}",
            )
        val verified = Ed25519TariffSignatureVerifier(publicKeyBase64)
            .verify(canonicalTariffPayload(dto), signature)
        if (!verified) {
            throw TariffSignatureException(
                "Tariff ${dto.id} signature verification failed — possible tampering, refusing to cache",
            )
        }
    }

    private fun decode(row: TariffEntity): TariffDto =
        cabDispatchJson.decodeFromString(TariffDto.serializer(), row.rawJson)
}

/** Thrown by [TariffCache.refresh] when a fetched tariff's signature doesn't verify — see
 * [TariffCache.verifySignatureOrThrow]. Deliberately a plain [Exception] subtype (not a
 * [RuntimeException]-only marker) so `runCatching { }` callers can distinguish "signature
 * rejected" from an ordinary [java.io.IOException]/HTTP failure via `is TariffSignatureException`
 * if a future caller wants to show a different message for the two cases; today's callers
 * (IdleViewModel/WheelDashboardViewModel) treat every [refresh] failure identically, which is
 * still the correct default: either way, the safe/only action is "keep using the last verified
 * cached tariff". */
class TariffSignatureException(message: String) : Exception(message)
