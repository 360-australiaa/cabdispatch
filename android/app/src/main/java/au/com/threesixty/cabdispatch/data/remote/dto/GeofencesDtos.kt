package au.com.threesixty.cabdispatch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * DTOs for geofences (`backend/app/api/v1/geofences.py`) — today only the `kind == "airport"`
 * subset is read on-device, for the airport access fee
 * ([au.com.threesixty.cabdispatch.sync.AirportZoneCache]). Same file-level rules as every other
 * DTO file here (see [ApiService]'s header): unknown keys are ignored by
 * [au.com.threesixty.cabdispatch.data.cabDispatchJson], geometry is `Double`, and money is never
 * a `Double`.
 *
 * [GeofenceDto.tollAmount] is the one deliberate departure from the "money arrives as a string"
 * convention: the contract this was written against says the fee is "a decimal string OR a
 * number", so it is decoded as a raw [JsonPrimitive] and parsed by [GeofenceDto.tollAmountOrNull]
 * rather than trusting either shape — a numeric `6.43` and a quoted `"6.43"` both land on the
 * same [BigDecimal], and anything unparseable lands on `null` (never on a fabricated fee).
 */

/** Mirrors `GeofenceRead` — one circular geofence. Only [kind] `"airport"` rows carry a
 * meaningful [tollAmount]. */
@Serializable
data class GeofenceDto(
    val id: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    val name: String,
    val kind: String,
    @SerialName("center_lat") val centerLat: Double,
    @SerialName("center_lng") val centerLng: Double,
    @SerialName("radius_m") val radiusM: Double,
    @SerialName("toll_amount") val tollAmount: JsonPrimitive? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun tollAmountOrNull(): BigDecimal? = tollAmount.toBigDecimalOrNull()
}

/** Response for [ApiService.listGeofences] — the backend's generic `Page[GeofenceRead]`, same
 * items/total/skip/limit shape as [ZoneListResponseDto]. */
@Serializable
data class GeofenceListResponseDto(
    val items: List<GeofenceDto>,
    val total: Int = 0,
    val skip: Int = 0,
    val limit: Int = 0,
)

/** One row of `GET /v1/geofences/presets/airport` — the backend's canonical Sydney Airport
 * terminal-rank presets (T1 / T2 / T3), without ids: these are templates an operator seeds
 * their tenant's real `kind="airport"` geofences from, not zones in force by themselves. */
@Serializable
data class GeofencePresetDto(
    val name: String,
    val kind: String,
    @SerialName("center_lat") val centerLat: Double,
    @SerialName("center_lng") val centerLng: Double,
    @SerialName("radius_m") val radiusM: Double,
    @SerialName("toll_amount") val tollAmount: JsonPrimitive? = null,
) {
    fun tollAmountOrNull(): BigDecimal? = tollAmount.toBigDecimalOrNull()
}

/** Defensive parse — see this file's own header. `JsonNull` is itself a [JsonPrimitive], so an
 * explicit `"toll_amount": null` is handled here too rather than becoming `BigDecimal("null")`. */
private fun JsonPrimitive?.toBigDecimalOrNull(): BigDecimal? {
    if (this == null || this is JsonNull) return null
    return runCatching { BigDecimal(content.trim()) }.getOrNull()
}
