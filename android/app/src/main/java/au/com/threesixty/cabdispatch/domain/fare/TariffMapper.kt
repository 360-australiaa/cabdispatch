package au.com.threesixty.cabdispatch.domain.fare

import au.com.threesixty.cabdispatch.data.remote.TariffDto
import java.math.BigDecimal

/**
 * Maps the wire/cache [TariffDto] (all rate fields decimal-as-string, per
 * ApiService.kt's header comment) to this package's [Tariff] domain object
 * (BigDecimal fields) so [FareEngine] never has to know about JSON/DTOs.
 * Used by S4/S5 after reading a cached tariff via
 * [au.com.threesixty.cabdispatch.sync.TariffCache] or
 * [au.com.threesixty.cabdispatch.data.local.dao.TariffDao].
 */
fun TariffDto.toDomainTariff(): Tariff = Tariff(
    name = name,
    area = if (region.equals("urban", ignoreCase = true)) AreaClass.URBAN else AreaClass.COUNTRY,
    flagFall = flagFall.toBigDecimalOrZero(),
    peakCharge = peakCharge.toBigDecimalOrZero(),
    distRate1 = distRate1.toBigDecimalOrZero(),
    distRate2 = distRate2.toBigDecimalOrZero(),
    nightRate1 = nightRate1.toBigDecimalOrZero(),
    nightRate2 = nightRate2.toBigDecimalOrZero(),
    holidayRate1 = holidayRate1.toBigDecimalOrZero(),
    holidayRate2 = holidayRate2.toBigDecimalOrZero(),
    waitingRatePerMin = waitingRatePerMin.toBigDecimalOrZero(),
    distKmThreshold = distKmThreshold.toBigDecimalOrDefault(BigDecimal(12)),
    speedThresholdKmh = speedThresholdKmh.toBigDecimalOrDefault(BigDecimal(26)),
    maxiMultiplier = maxiMultiplier.toBigDecimalOrDefault(BigDecimal("1.5")),
    multiHirePct = multiHirePct.toBigDecimalOrDefault(BigDecimal("0.75")),
    pslAmount = pslAmount.toBigDecimalOrDefault(BigDecimal("1.32")),
    surchargePctCap = surchargePctCap.toBigDecimalOrDefault(BigDecimal("5.0")),
    cleaningFeeCap = cleaningFeeCap.toBigDecimalOrDefault(BigDecimal("124.14")),
)

private fun String.toBigDecimalOrZero(): BigDecimal = toBigDecimalOrDefault(BigDecimal.ZERO)

/** Defensive against a malformed/empty rate string from a stale cache row — never crashes a screen render. */
private fun String.toBigDecimalOrDefault(default: BigDecimal): BigDecimal =
    runCatching { BigDecimal(this) }.getOrDefault(default)
