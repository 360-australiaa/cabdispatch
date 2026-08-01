package au.com.threesixty.cabdispatch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of a signed tariff/fares-order payload
 * (`GET /v1/tariffs/active` — see [au.com.threesixty.cabdispatch.sync.TariffCache]).
 *
 * This is what lets the fare engine (sibling agent) keep computing correct
 * fares for days with zero connectivity: it reads *only* from this table
 * (via `TariffCache.getActiveTariff`), never calling the network directly.
 * [rawJson] holds the full
 * `au.com.threesixty.cabdispatch.data.remote.TariffDto` as received from the
 * server (all rate fields decimal-as-string, untouched) so the fare engine
 * gets the exact same shape whether it reads live or cached.
 *
 * [id] is the server's tariff id (stable, not client-generated — tariffs are
 * server-authored, the device only ever caches them).
 */
@Entity(tableName = "tariffs")
data class TariffEntity(
    @PrimaryKey val id: String,
    val region: String,
    val effectiveFrom: String, // ISO-8601
    val effectiveTo: String? = null, // ISO-8601, null = still current
    val rawJson: String,
    val fetchedAt: Long,
)
