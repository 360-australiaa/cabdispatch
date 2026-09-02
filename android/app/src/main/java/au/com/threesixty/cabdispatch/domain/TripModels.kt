package au.com.threesixty.cabdispatch.domain

import java.math.BigDecimal
import java.math.RoundingMode

enum class TripStatus { FOR_HIRE, HIRED, STOPPED, CLOSED }
enum class AccrualMode { DISTANCE, WAITING }
enum class TariffBand(val label: String) { BAND_1("Tariff 1"), BAND_2("Tariff 2") }
enum class TimeClass(val label: String) { DAY("Day"), NIGHT("Night"), HOLIDAY("Holiday") }

data class TollPreset(val id: String, val label: String, val amount: BigDecimal)

/**
 * Fixed preset list per spec B5 S3: "toll add buttons (M5, Harbour southbound,
 * airport toll presets)". Amounts are illustrative placeholders (round
 * cash-equivalent figures) — TODO(fleet-config sibling agent): source live
 * figures from tenant tariff/toll config server-side rather than hardcoding.
 */
object TollPresets {
    val M5 = TollPreset("m5", "M5", BigDecimal("4.30"))
    val HARBOUR_SOUTHBOUND = TollPreset("harbour_sb", "Harbour (southbound)", BigDecimal("4.19"))
    // $6.43 Sydney Airport access fee (Point to Point Transport (Fares) Order 2026, effective 1
    // June 2026 — was $6.30 under the superseded Fares Order 2025 (no.2)).
    val AIRPORT = TollPreset("airport", "Airport", BigDecimal("6.43"))
    val ALL = listOf(M5, HARBOUR_SOUTHBOUND, AIRPORT)
}

data class FareBreakdown(
    val flagFall: BigDecimal = BigDecimal.ZERO,
    val distanceAmount: BigDecimal = BigDecimal.ZERO,
    val waitingAmount: BigDecimal = BigDecimal.ZERO,
    val peakAmount: BigDecimal = BigDecimal.ZERO,
    val tolls: BigDecimal = BigDecimal.ZERO,
    val psl: BigDecimal = BigDecimal.ZERO,
    val extras: BigDecimal = BigDecimal.ZERO,
) {
    val total: BigDecimal
        get() = listOf(flagFall, distanceAmount, waitingAmount, peakAmount, tolls, psl, extras)
            .fold(BigDecimal.ZERO, BigDecimal::add)
}

data class FareState(
    val status: TripStatus = TripStatus.FOR_HIRE,
    val mode: AccrualMode = AccrualMode.DISTANCE,
    val band: TariffBand = TariffBand.BAND_1,
    val timeClass: TimeClass = TimeClass.DAY,
    val breakdown: FareBreakdown = FareBreakdown(),
    val distanceKm: BigDecimal = BigDecimal.ZERO,
    val currentSpeedKmh: Double = 0.0,
    /**
     * Cumulative whole seconds spent in [AccrualMode.DISTANCE] / [AccrualMode.WAITING]
     * respectively, since [status] became [TripStatus.HIRED]. Added so
     * [au.com.threesixty.cabdispatch.ui.screens.hired.HiredViewModel] can persist
     * this live-ticking state's cumulative counters to
     * [au.com.threesixty.cabdispatch.data.repository.TripRepository] (matching
     * [au.com.threesixty.cabdispatch.data.local.entity.TripEntity.movingS]/
     * `.waitingS`) without a separate, error-prone tally kept outside the engine —
     * see [FareEngineImpl.tick], the only place these increment.
     */
    val movingSeconds: Int = 0,
    val waitingSeconds: Int = 0,
    val tollsApplied: List<TollPreset> = emptyList(),
    /**
     * Passenger count declared for this hiring — mirrors
     * [au.com.threesixty.cabdispatch.domain.fare.FareState.passengerCount]. Editable mid-trip
     * (miscounts happen) via [FareEngineImpl.updatePassengerCount]; changing it re-derives
     * [maxiRateApplied] for the remainder of the trip only, never retroactively. Defaults to 1 so
     * every pre-existing reader of this struct that never looked at this field keeps working.
     */
    val passengerCount: Int = 1,
    /**
     * Mirrors [au.com.threesixty.cabdispatch.domain.fare.FareState.wheelchairHiring] — set once at
     * [FareEngineImpl.startTrip], not editable mid-trip. Purely informational on this screen (a
     * reminder of the NSW Reg cl 82 safe-securement rule) — never itself gates the maxi rate here;
     * [maxiRateApplied] already bakes this carve-out in via the pure engine's own derivation.
     */
    val wheelchairHiring: Boolean = false,
    /**
     * The pure NSW-fares engine's own derived
     * [au.com.threesixty.cabdispatch.domain.fare.FareState.maxiRateApplied] — copied over verbatim
     * on every [FareEngineImpl] state update (start + every tick + passenger-count edit), never
     * recomputed independently here. This is the ONLY thing the UI should ever read to decide
     * whether to show a "maxi rate active" indicator — never re-derive it from
     * [passengerCount]/[wheelchairHiring] locally, or a UI-layer bug could show (or hide) the
     * indicator out of step with what is actually being charged.
     */
    val maxiRateApplied: Boolean = false,
) {
    val total: BigDecimal get() = breakdown.total
}

/** Formats a decimal-as-string-contract money value for display. Never use
 * Float/Double for the underlying value — see ApiService.kt header comment. */
fun BigDecimal.toMoneyString(): String = "$" + this.setScale(2, RoundingMode.HALF_UP).toPlainString()
