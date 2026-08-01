package au.com.threesixty.cabdispatch.data

import kotlinx.serialization.json.Json

/**
 * Single shared kotlinx.serialization [Json] instance for the whole app —
 * used both as Retrofit's converter-factory config
 * ([au.com.threesixty.cabdispatch.data.AppContainer]) and for every local
 * JSON blob (gps trace, tariff raw payload, sync outbox `entity_json`), so
 * there is exactly one place that decides `ignoreUnknownKeys` (server-side
 * additive schema changes don't crash old app builds) and `encodeDefaults`
 * (optional fields with server-relevant defaults are always sent
 * explicitly).
 */
internal val cabDispatchJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
