package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of the Ed25519 public key that verifies `GET /v1/tariffs/active`'s `signature`
 * field (`GET /v1/tariffs/signing-public-key` — see
 * [au.com.threesixty.cabdispatch.sync.TariffSigningKeyCache]).
 *
 * Follows the exact same shape/intent as [TariffEntity]: one platform-wide keypair (not
 * per-tenant, not per-region), so there is always at most one row, keyed by the constant
 * [au.com.threesixty.cabdispatch.sync.TariffSigningKeyCache.SINGLETON_ID] rather than a
 * server-assigned id (the signing-key endpoint has no id of its own to key on). Read purely
 * locally by [au.com.threesixty.cabdispatch.sync.TariffCache] before trusting a freshly-fetched
 * tariff's signature, so the fare engine can keep verifying signed tariffs for days offline the
 * same way it keeps reading cached tariffs offline — see [TariffEntity]'s doc for why that
 * matters (B7 offline behaviour).
 */
@Entity(tableName = "tariff_signing_keys")
data class TariffSigningKeyEntity(
    @PrimaryKey val id: String,
    val publicKeyBase64: String,
    val algorithm: String,
    val fetchedAt: Long,
)
