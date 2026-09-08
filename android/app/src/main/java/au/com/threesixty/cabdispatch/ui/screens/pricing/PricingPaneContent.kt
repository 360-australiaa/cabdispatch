package au.com.threesixty.cabdispatch.ui.screens.pricing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.AppContainer
import au.com.threesixty.cabdispatch.data.remote.TariffDto
import au.com.threesixty.cabdispatch.domain.fare.AIRPORT_FIXED_FARE_MAXI
import au.com.threesixty.cabdispatch.domain.fare.AIRPORT_FIXED_FARE_STANDARD
import au.com.threesixty.cabdispatch.domain.location.RegionResolver
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Nav rail `PRICING` pane (plan `squishy-herding-iverson.md`, Phase E) — a real, standalone,
 * **view-only** tariff display. Before this pass the rail's PRICING item didn't open anything
 * pricing-related at all: it silently aliased to [au.com.threesixty.cabdispatch.ui.screens.dashboard.SetPriceDialogV2],
 * the negotiated-fixed-fare entry dialog used when starting a meter — a real, useful dialog, but a
 * different feature entirely, mislabelled. `RAIL_ITEMS`/`FLYOUT_EXTRA_ITEMS` in `DeckHomeScreen.kt`
 * now point PRICING at this pane instead; the Set Price dialog is unaffected and still reachable
 * from the Dashboard's own SET PRICE tile.
 *
 * Two things the mockup shows that this pane deliberately does **not** build, per this plan's
 * confirmed decisions (see the plan doc's Phase E note and `backend/app/api/v1/tariffs.py:74-80`'s
 * own doc):
 * - **No EDIT PRICING button anywhere.** Tariff writes are owner/admin-only server-side by
 *   explicit design — a driver-role account rewriting the rates their own meter bills against
 *   would be a real integrity bug, not a missing feature.
 * - **No vehicle-class tabs (Sedan/SUV/Maxi/Parcel).** [TariffDto] has no `vehicle_class`
 *   dimension — this tenant has exactly one active tariff, not a per-class rate card — and
 *   "Parcel" isn't even a real vehicle class in this business (the backend's
 *   `VALID_VEHICLE_CLASSES` is `STANDARD, PREMIUM, MAXI, WAT`). The maxi-cab rate still shows,
 *   just as a line item inside the one real tariff, exactly like `FareScheduleBody` already shows
 *   it in Settings.
 *
 * Fetches the same signed active tariff [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel]'s
 * `loadFareSchedule` does ([AppContainer.tariffCache.getActiveTariff], keyed by the real
 * GPS-derived [RegionResolver] region) via a screen-local loader — this pane has no other state to
 * justify a dedicated ViewModel, matching the same convention `DeckHomeScreen`'s own `HomeExtras`
 * and `ProfileScreen`'s compliance-expiry cards already use. This intentionally duplicates rather
 * than shares `SettingsScreen.kt`'s private `FareScheduleBody`/row composables — that screen's
 * fare-schedule content stays untouched, and this pane's card layout (Fare Structure / Distance
 * Tiers) genuinely differs from that screen's fuller cl.15-notice layout anyway.
 *
 * **HUD kit rebuild (2026-09-04).** Purely visual — same [TariffDto] fields, same loader, same
 * "no edit button" decision above; only the surfaces changed: both cards are now [GlassCard]s (the
 * Fare Structure card carries the kit's accent glow, matching [au.com.threesixty.cabdispatch.ui.screens.earnings.EarningsWheelContent]'s
 * "hero card glows, detail card doesn't" convention), rows use tabular figures
 * (`fontFeatureSettings = "tnum"`) in [ChakraPetch] so the dollar/percentage columns line up, and
 * dividers are the kit's `hudTrack` line instead of `panelBorder`.
 */
@Composable
fun PricingPaneContent(modifier: Modifier = Modifier) {
    var tariff by remember { mutableStateOf<TariffDto?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val region = RegionResolver.resolve(AppContainer.speedSource.locationFix.value)
        tariff = runCatching { AppContainer.tariffCache.getActiveTariff(region = region) }.getOrNull()
        loading = false
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Rates displayed to passengers per the taxi fare regulations (cl.15 display requirement). " +
                "View only — rate changes are made by your operator, never from this device.",
            fontFamily = InterFamily,
            fontSize = 16.sp,
            color = CaptainPalette.textMuted,
        )
        Box(modifier = Modifier.height(20.dp))

        when {
            loading -> CircularProgressIndicator(color = CaptainPalette.hudAccent)
            tariff == null -> Text(
                "No cached fare schedule available.",
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                FareStructureCard(tariff!!)
                DistanceTiersCard(tariff!!)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared typography
// ---------------------------------------------------------------------------------------------

private val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

private val EyebrowStyle = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    letterSpacing = 1.sp,
    color = CaptainPalette.hudAccent,
)

/** "Fare Structure" card — every figure other than the fixed night-time window and Sydney Airport
 * Fixed Fare (regulated flat constants, unchanged by tariff) is read live off [TariffDto], never a
 * hardcoded literal — same sourcing discipline `SettingsScreen.kt`'s `FareScheduleBody` uses. */
@Composable
private fun FareStructureCard(tariff: TariffDto) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 20, glow = CaptainPalette.hudAccent) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tariff.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = CaptainPalette.textPrimary)
            Text("FARE STRUCTURE", style = EyebrowStyle)
            Divider()
            PricingRow("Hiring charge (flag fall)", "$${tariff.flagFall}")
            if (tariff.peakCharge.nonZeroOrNull() != null) PricingRow("Peak time hiring charge", "$${tariff.peakCharge}")
            PricingRow("Waiting time", "$${tariff.waitingRatePerMin}/min")
            // Fixed regulatory window (FareEngine.TimeClass.NIGHT: "10pm-6am, any night"), not a
            // per-tariff field — same reasoning TaxiFareHotlineNotice's static text uses in
            // SettingsScreen.kt.
            PricingRow("Night-time window", "10pm – 6am, any night")
            PricingRow("Non-cash payment surcharge cap", "${tariff.surchargePctCap}%")
            PricingRow("Maxi-cab rate (5+ seats)", "${formatMaxiPercent(tariff.maxiMultiplier)}%")
            Divider()
            PricingRow("Passenger Service Levy", "$${tariff.pslAmount}")
            PricingRow("Cleaning fee cap", "$${tariff.cleaningFeeCap} + GST")
            Divider()
            PricingRow("Sydney Airport Fixed Fare — Standard", "$${AIRPORT_FIXED_FARE_STANDARD.toPlainString()}")
            PricingRow("Sydney Airport Fixed Fare — Maxi", "$${AIRPORT_FIXED_FARE_MAXI.toPlainString()}")
        }
    }
}

/**
 * "Distance Tiers" card — this tariff bills distance as two flat bands (first/beyond a threshold
 * km), never a real multi-tier scale, per this session's own finding. Shown honestly as exactly
 * two rows rather than inventing a bigger table; holiday rates (country region only) only appear
 * when the tariff actually carries a non-zero value for them, same conditional
 * `FareScheduleBody` already applies.
 */
@Composable
private fun DistanceTiersCard(tariff: TariffDto) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 20) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("DISTANCE TIERS", style = EyebrowStyle)
            Text(
                "This tariff bills distance in two flat bands, not a sliding scale — every trip's " +
                    "distance falls into exactly one of the two rows below.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = CaptainPalette.textMuted,
            )
            Divider()
            DistanceTierRow(
                label = "First ${tariff.distKmThreshold}km",
                dayRate = tariff.distRate1,
                nightRate = tariff.nightRate1,
                holidayRate = tariff.holidayRate1.nonZeroOrNull(),
            )
            Divider()
            DistanceTierRow(
                label = "Beyond ${tariff.distKmThreshold}km",
                dayRate = tariff.distRate2,
                nightRate = tariff.nightRate2,
                holidayRate = tariff.holidayRate2.nonZeroOrNull(),
            )
        }
    }
}

@Composable
private fun DistanceTierRow(label: String, dayRate: String, nightRate: String, holidayRate: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
        PricingRow("Day rate", "$dayRate/km")
        PricingRow("Night rate (10pm–6am)", "$nightRate/km")
        if (holidayRate != null) PricingRow("Holiday rate (country)", "$holidayRate/km")
    }
}

/** One label + tabular-figure value row — the kit's convention for a "figures line up" list (see
 * [au.com.threesixty.cabdispatch.ui.screens.earnings.EarningsWheelContent]'s `BreakdownRow`). */
@Composable
private fun PricingRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textSecondary)
        Text(
            value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = CaptainPalette.textPrimary,
            style = TabularFigures,
        )
    }
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.hudTrack))
}

/** Same formatting rule `SettingsScreen.kt`'s private `formatMaxiPercent` uses — duplicated rather
 * than shared, see this file's class doc for why. */
private fun formatMaxiPercent(multiplier: String): String {
    val pct = (multiplier.toDoubleOrNull() ?: 1.5) * 100
    return if (pct == pct.toLong().toDouble()) pct.toLong().toString() else "%.1f".format(pct)
}

/**
 * This string, or `null` if it's a zero/unset tariff field — decided NUMERICALLY, not by
 * `!= "0"` string equality: the live wire tariff sends unset holiday/peak fields as `"0.0000"`,
 * not the bare literal `"0"` (confirmed live: `GET /v1/tariffs/active?region=urban`), so a plain
 * string check — the exact check `SettingsScreen.kt`'s `FareScheduleBody` already uses for these
 * same fields — never actually filters anything and would show a fake "Holiday rate $0.0000/km"
 * row. Caught live during this pane's own on-device verification pass.
 */
private fun String.nonZeroOrNull(): String? = takeIf { (toDoubleOrNull() ?: 0.0) != 0.0 }
