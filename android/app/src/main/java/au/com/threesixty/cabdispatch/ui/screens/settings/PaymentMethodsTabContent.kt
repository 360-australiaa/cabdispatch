package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.fare.AIRPORT_FIXED_FARE_MAXI
import au.com.threesixty.cabdispatch.domain.fare.AIRPORT_FIXED_FARE_STANDARD
import au.com.threesixty.cabdispatch.ui.theme.CaptainChip
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

// W7 file split (2026-09-13): extracted from the former monolithic SettingsScreen.kt (1,437
// lines) — see that file's own doc for the split rationale. This file is the Payment Methods tab
// ([SettingsTab.PAYMENT_METHODS]), fare schedule content folded in per this tab's own doc.
// [PaymentMethodsTabContent] is `internal` (not `private`) because `MainSettingsContent`
// (SettingsScreen.kt) calls it across this file boundary — same package (`ui.screens.settings`),
// so no import needed either way, only the visibility modifier changes from the pre-split file.
// Every Fare-Schedule composable below is only ever called from within this file, so all stayed
// `private`.

/**
 * Payment Methods tab — the real Allow Cash toggle, with the fare schedule folded directly in
 * below it via [FareScheduleBody] rather than given its own eighth tab (task's own "your call, but
 * don't lose it" — rates/charges are already this tab's subject, so folding reads naturally rather
 * than as a leftover).
 */
@Composable
internal fun PaymentMethodsTabContent(
    state: SettingsUiState,
    onSetAllowCash: (Boolean) -> Unit,
    onSetMaxiVehicle: (Boolean) -> Unit,
) {
    FareScheduleBody(state = state, onSetMaxiVehicle = onSetMaxiVehicle) {
        ToggleSettingRow(
            label = "Allow Cash",
            description = "When off, CASH is disabled on the Close & Pay screen — card, CabCharge/TTSS, " +
                "voucher, account and split-fare payments are unaffected.",
            checked = state.allowCash,
            onCheckedChange = onSetAllowCash,
        )
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * Fare Schedule content — NSW Point to Point Transport Regulation 2017 cl.15 fare-display
 * requirement. Formerly its own standalone sub-screen (`SettingsSubScreen.FARE_SCHEDULE`); folded
 * directly into the Payment Methods tab by the Settings two-pane pass (2026-09-03) rather than
 * given its own eighth tab — see [PaymentMethodsTabContent]'s doc. [header] injects that tab's
 * Allow Cash toggle above the fare content, inside this same scrolling `Column` (never a second,
 * nested `verticalScroll` — see this composable's own scroll container below).
 *
 * Every dollar figure and percentage below is read live off [SettingsUiState.fareSchedule] (the
 * signed active `TariffDto`) or, for the two figures the wire tariff doesn't carry yet (the
 * cleaning-fee cap and the Sydney Airport Fixed Fare amounts), off the same
 * [au.com.threesixty.cabdispatch.domain.fare] constants the engine itself bills from — never a new
 * hardcoded literal. Only the Hotline number and its explanatory copy are static text, because
 * that's a fixed regulatory number, not a tenant/tariff value.
 */
@Composable
private fun FareScheduleBody(
    state: SettingsUiState,
    onSetMaxiVehicle: (Boolean) -> Unit,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        header()

        Text(
            "Rates displayed to passengers per the taxi fare regulations (cl.15 display requirement).",
            fontFamily = InterFamily,
            fontSize = 16.sp,
            color = CaptainPalette.textMuted,
        )
        Spacer(Modifier.height(20.dp))

        TaxiFareHotlineNotice()
        Spacer(Modifier.height(20.dp))

        val tariff = state.fareSchedule
        when {
            state.fareScheduleLoading -> CircularProgressIndicator(color = CaptainPalette.hudAccent)
            tariff == null -> Text(
                "No cached fare schedule available.",
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18, glow = CaptainPalette.hudAccent) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(tariff.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.textPrimary)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
                        FareScheduleRow("Hiring charge (flag fall)", tariff.flagFall)
                        if (tariff.peakCharge != "0") FareScheduleRow("Peak time hiring charge", tariff.peakCharge)
                        FareScheduleRow("Distance rate, first ${tariff.distKmThreshold}km", "${tariff.distRate1}/km")
                        FareScheduleRow("Distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.distRate2}/km")
                        FareScheduleRow("Night distance rate, first ${tariff.distKmThreshold}km", "${tariff.nightRate1}/km")
                        FareScheduleRow("Night distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.nightRate2}/km")
                        if (tariff.holidayRate1 != "0") {
                            FareScheduleRow("Holiday distance rate, first ${tariff.distKmThreshold}km", "${tariff.holidayRate1}/km")
                        }
                        if (tariff.holidayRate2 != "0") {
                            FareScheduleRow("Holiday distance rate, beyond ${tariff.distKmThreshold}km", "${tariff.holidayRate2}/km")
                        }
                        FareScheduleRow("Waiting time", "${tariff.waitingRatePerMin}/min")
                        FareScheduleRow("Non-cash payment surcharge cap", "${tariff.surchargePctCap}%")
                    }
                }

                MaxiCabFaresSection(maxiMultiplier = tariff.maxiMultiplier)
                AdditionalChargesSection(pslAmount = tariff.pslAmount, cleaningFeeCap = tariff.cleaningFeeCap)
                SydneyAirportFixedFareSection()
            }
        }

        Spacer(Modifier.height(20.dp))

        // Point to Point Transport (Fares) Order 2026 UI-wiring pass — a local,
        // honestly-labelled driver self-declaration (see MaxiVehicleStore's own doc for why
        // this is not read from a real vehicle record: `VehicleDto` carries no such field
        // anywhere server-side). Placed here, the app's existing vehicle/fare-schedule
        // area, rather than inventing a new settings section. HUD kit rebuild: this row was its own
        // hand-rolled panel+switch before this pass — now the exact same [ToggleSettingRow] every
        // other real toggle on this screen uses, same [GlassCard] surface, same switch wiring.
        ToggleSettingRow(
            label = "This vehicle has 5+ passenger seats",
            description = "Your own declaration for this vehicle, saved on this device — not read from a vehicle record. " +
                "Also shown on the Start Meter card. Turns on the maxi (×1.5) rate only together with 5+ " +
                "passengers, or a Sydney Airport rank maxi request, and never for a wheelchair hiring.",
            checked = state.isMaxiVehicle,
            onCheckedChange = onSetMaxiVehicle,
        )
    }
}

/** One label + tabular-figure dollar value row (`FareScheduleBody`'s convention — see
 * [au.com.threesixty.cabdispatch.ui.screens.pricing.PricingPaneContent]'s own `PricingRow`, the
 * same idea in the Pricing pane). */
@Composable
private fun FareScheduleRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
        Text(
            "$$value",
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = CaptainPalette.textPrimary,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun FareScheduleSectionTitle(text: String) {
    Text(
        text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.sp,
        color = CaptainPalette.hudAccent,
    )
}

@Composable
private fun FareScheduleNote(text: String) {
    Text(
        text,
        fontFamily = InterFamily,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = CaptainPalette.textSecondary,
    )
}

/**
 * Regulation cl.15(1A) requires every vehicle to display, in the Commissioner's approved form, the
 * Taxi Fare Hotline number and a statement that the meter must always be on during a rank or hail
 * trip — this app had no such notice anywhere before this pass. The number itself and this
 * explanatory copy are fixed regulatory text, not tariff data, so they're static (not read off
 * `TariffDto`) — a QR code linking to the hotline is the Commissioner's approved form's "ideally"
 * addition, not a hard requirement, and is left as a future nice-to-have (no QR library in this
 * project yet) rather than fabricated here.
 */
@Composable
private fun TaxiFareHotlineNotice() {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FareScheduleSectionTitle("TAXI FARE HOTLINE")
            Text(
                "1800 500 410",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                color = CaptainPalette.textPrimary,
            )
            FareScheduleNote("Ask your driver, or call this number, if you believe you've been charged incorrectly.")
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
            FareScheduleNote("The meter must always be switched on during a rank or hail trip.")
        }
    }
}

/**
 * Fares Order 2026 cl 2(d) — a maxi-cab (5+ seats excl. driver) may charge up to the configured
 * multiplier only when carrying 5+ passengers, or when requested as a maxi at a Sydney Airport
 * rank — never for a wheelchair-accessible hiring. The percentage shown is derived from
 * `TariffDto.maxiMultiplier` (e.g. "1.5" -> "150%"), never a hardcoded "150%" literal, so this
 * stays correct if the configured multiplier ever changes.
 */
@Composable
private fun MaxiCabFaresSection(maxiMultiplier: String) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FareScheduleSectionTitle("MAXI-CAB FARES")
            CaptainChip(label = "MAXI RATE", value = "${formatMaxiPercent(maxiMultiplier)}%")
            FareScheduleNote(
                "A maxi-cab (5 or more seats, excluding the driver) may charge up to this rate on the fare " +
                    "only when carrying 5 or more passengers, or when a passenger requests a maxi-cab at a " +
                    "Sydney Airport rank. This never applies to a wheelchair-accessible hiring. " +
                    "(Point to Point Transport (Fares) Order 2026, cl 2(d).)",
            )
        }
    }
}

/**
 * Passenger Service Levy (cl 3) and cleaning-fee cap (cl 2(f)) — the old screen showed neither.
 * Both come live off `TariffDto` (`TariffDto.pslAmount` / `TariffDto.cleaningFeeCap`, the latter
 * added server-side alongside the 2026 rate-card pass) rather than a hardcoded literal, so this
 * stays correct if a tenant's configured tariff ever sets a lower cap than the Order's own
 * maximum. Also carries the tolls pass-through rule (no numeric field — tolls vary per trip, so
 * there's nothing to display but the rule itself).
 */
@Composable
private fun AdditionalChargesSection(pslAmount: String, cleaningFeeCap: String) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FareScheduleSectionTitle("ADDITIONAL CHARGES")
            CaptainChip(label = "PASSENGER SERVICE LEVY", value = "$$pslAmount")
            FareScheduleNote(
                "Optional to pass on to the passenger. Charged once per trip, regardless of the number of " +
                    "passengers. (Fares Order 2026, cl 3.)",
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
            CaptainChip(label = "CLEANING FEE CAP", value = "$$cleaningFeeCap + GST")
            FareScheduleNote(
                "Only chargeable when soiling means the vehicle can't reasonably be used before it's " +
                    "cleaned. (Fares Order 2026, cl 2(f).)",
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
            FareScheduleNote(
                "Tolls are passed on at the actual cost incurred during this hiring only — never a " +
                    "\"return\" toll.",
            )
        }
    }
}

/**
 * Sydney Airport Fixed Fare Trial (cl 5) — non-booked journey from a Sydney Airport rank to the
 * CBD trial area. $60/$80 are all-inclusive regulated flat figures, unchanged by the 2026 Order
 * (see [AIRPORT_FIXED_FARE_STANDARD]/[AIRPORT_FIXED_FARE_MAXI]'s own doc), so this reads those
 * same engine constants rather than a new hardcoded literal here.
 */
@Composable
private fun SydneyAirportFixedFareSection() {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FareScheduleSectionTitle("SYDNEY AIRPORT FIXED FARE")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CaptainChip(label = "STANDARD", value = "$${AIRPORT_FIXED_FARE_STANDARD.toPlainString()}")
                CaptainChip(label = "MAXI", value = "$${AIRPORT_FIXED_FARE_MAXI.toPlainString()}")
            }
            FareScheduleNote(
                "All-inclusive fare for a non-booked journey from a Sydney Airport rank to the defined CBD " +
                    "trial area. (Fares Order 2026, cl 5.)",
            )
        }
    }
}

/** Formats a tariff's `TariffDto.maxiMultiplier` wire string ("1.5") as a driver-facing whole or
 * one-decimal percentage ("150"), so the maxi-cab section never hardcodes "150%" as a literal —
 * falls back to the regulated 150% default only if the wire value is somehow unparseable. */
private fun formatMaxiPercent(multiplier: String): String {
    val pct = (multiplier.toDoubleOrNull() ?: 1.5) * 100
    return if (pct == pct.toLong().toDouble()) pct.toLong().toString() else "%.1f".format(pct)
}
