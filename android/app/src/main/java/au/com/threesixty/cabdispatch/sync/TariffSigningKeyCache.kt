package au.com.threesixty.cabdispatch.sync

import au.com.threesixty.cabdispatch.data.local.dao.TariffSigningKeyDao
import au.com.threesixty.cabdispatch.data.local.entity.TariffSigningKeyEntity
import au.com.threesixty.cabdispatch.data.remote.ApiService

/**
 * Local cache of the Ed25519 public key that verifies `GET /v1/tariffs/active`'s `signature`
 * field (`GET /v1/tariffs/signing-public-key`). Deliberately mirrors [TariffCache]'s own
 * shape/contract 1:1 (per this class's own instructions — see that class's doc first if this one
 * is confusing): [getCachedPublicKey] is a pure local Room read, safe to call from anywhere,
 * NEVER touches the network; [refresh] is the only method that does, and is best-effort/
 * opportunistic like [TariffCache.refresh] — it throws/propagates network errors rather than
 * swallowing them, so a caller that specifically wants to know refresh failed (vs. "using
 * whatever's cached") can `runCatching { }` around it same as everywhere else in this codebase.
 *
 * Offline behaviour (why this exists as a *cache*, not just an in-memory field held by
 * [TariffCache]): one platform-wide keypair barely ever rotates, and a device with zero
 * connectivity right now must still be able to verify a tariff it already cached signed bytes
 * for days ago — [TariffCache.refresh] reads [getCachedPublicKey] first and only falls back to a
 * network [refresh] call if nothing has ever been cached (e.g. a genuinely first-ever launch
 * with no connectivity yet), so "offline" on its own is never treated as "fail closed on the
 * signature check" — see that method's doc.
 */
class TariffSigningKeyCache(
    private val dao: TariffSigningKeyDao,
    private val apiService: ApiService,
) {

    /** Pure local read — no network, ever. `null` only if [refresh] has never once succeeded on
     * this device. */
    suspend fun getCachedPublicKey(): String? = dao.getById(SINGLETON_ID)?.publicKeyBase64

    /** Fetches `GET /v1/tariffs/signing-public-key` and caches it. Deliberately unauthenticated
     * server-side (see [ApiService.tariffSigningPublicKey]'s doc) — safe to call before login. */
    suspend fun refresh(): String {
        val dto = apiService.tariffSigningPublicKey()
        dao.upsert(
            TariffSigningKeyEntity(
                id = SINGLETON_ID,
                publicKeyBase64 = dto.publicKey,
                algorithm = dto.algorithm,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
        return dto.publicKey
    }

    companion object {
        /** One platform-wide keypair (not per-tenant, not per-region) — see
         * [TariffSigningKeyEntity]'s doc for why this is a constant rather than a server id. */
        const val SINGLETON_ID = "platform_ed25519_signing_key"
    }
}
