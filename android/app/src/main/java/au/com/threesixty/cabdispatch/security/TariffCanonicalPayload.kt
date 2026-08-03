package au.com.threesixty.cabdispatch.security

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rebuilds the exact canonical JSON byte string the backend Ed25519-signs over a tariff
 * (`app.services.tariff_signing.canonical_tariff_payload`,
 * `backend/app/services/tariff_signing.py` — read that module's docstring before touching this
 * function, it is the wire-format contract this is a byte-for-byte Kotlin port of). This MUST
 * stay in lockstep with that Python function: same field set, same fixed declaration order
 * (never alphabetical, never reordered), same decimal quantization, same compact
 * (no-whitespace) JSON formatting — any drift here makes every real signature fail to verify,
 * which [au.com.threesixty.cabdispatch.sync.TariffCache.refresh] treats as tampering (fail
 * closed), not as "verifier is buggy, ignore it".
 *
 * Field order (fixed): `id`, `tenant_id`, `region`, `effective_from`, `effective_to`, `booked`,
 * then the 15 rate fields in [RATE_FIELD_ORDER] (same order as the backend's `RATE_FIELDS`
 * tuple).
 *
 * Risk flag: [TariffDto.effectiveFrom]/[TariffDto.effectiveTo] are passed through verbatim (not
 * reformatted) here, on the assumption that the backend's JSON serialization of the underlying
 * `datetime` for these exact fields already matches Python's `datetime.isoformat()` byte-for-byte
 * (FastAPI/Pydantic v2's default datetime JSON encoding does, e.g.
 * `"2025-11-03T00:00:00+00:00"`) — the signing code signs `isoformat()` directly, and the API
 * response for the same field is serialized from the same `datetime` value. If a real signature
 * mismatch is ever observed in practice, this is the first thing to byte-diff against the
 * backend's actual signed bytes (`canonical_tariff_payload` can be called directly in a Python
 * shell against a real `Tariff` row for a ground truth to diff against).
 */
private val RATE_FIELD_ORDER: List<Pair<String, (TariffDto) -> String>> = listOf(
    "flag_fall" to TariffDto::flagFall,
    "peak_charge" to TariffDto::peakCharge,
    "dist_rate_1" to TariffDto::distRate1,
    "dist_rate_2" to TariffDto::distRate2,
    "night_rate_1" to TariffDto::nightRate1,
    "night_rate_2" to TariffDto::nightRate2,
    "holiday_rate_1" to TariffDto::holidayRate1,
    "holiday_rate_2" to TariffDto::holidayRate2,
    "waiting_rate_per_min" to TariffDto::waitingRatePerMin,
    "dist_km_threshold" to TariffDto::distKmThreshold,
    "speed_threshold_kmh" to TariffDto::speedThresholdKmh,
    "maxi_multiplier" to TariffDto::maxiMultiplier,
    "multi_hire_pct" to TariffDto::multiHirePct,
    "psl_amount" to TariffDto::pslAmount,
    "surcharge_pct_cap" to TariffDto::surchargePctCap,
)

/** Matches the `Numeric(10, 4)` column precision the backend quantizes every rate field to
 * (`app.services.tariff_signing._RATE_QUANT`) before signing. */
private val RATE_QUANT_SCALE = 4

/**
 * Mirrors `app.services.tariff_signing._fmt_rate`: quantize to exactly 4 decimal places,
 * half-up rounding, plain (non-scientific) notation — e.g. `"5"` -> `"5.0000"`, `"2.56"` ->
 * `"2.5600"`. Using [BigDecimal] (not string padding) so this is correct regardless of how many
 * decimal places — or trailing zeros, or lack thereof — the wire value happens to carry.
 */
private fun formatRate(raw: String): String =
    BigDecimal(raw).setScale(RATE_QUANT_SCALE, RoundingMode.HALF_UP).toPlainString()

/** Plain default config: compact (no `prettyPrint`) output with standard `,`/`:` separators and
 * no extra whitespace — matches Python's `json.dumps(..., separators=(",", ":"))` exactly. This
 * is a throwaway [Json] instance used only to serialize an already-built [JsonObject] (its own
 * key/value types are the only thing that matters here, not `encodeDefaults`/`ignoreUnknownKeys`,
 * which only affect `@Serializable` class (de)serialization). */
private val canonicalJson = Json

fun canonicalTariffPayload(dto: TariffDto): String {
    val payload = buildJsonObject {
        put("id", dto.id)
        put("tenant_id", dto.tenantId)
        put("region", dto.region)
        put("effective_from", dto.effectiveFrom)
        put("effective_to", dto.effectiveTo)
        put("booked", dto.booked)
        for ((key, getter) in RATE_FIELD_ORDER) {
            put(key, formatRate(getter(dto)))
        }
    }
    return canonicalJson.encodeToString(JsonObject.serializer(), payload)
}
