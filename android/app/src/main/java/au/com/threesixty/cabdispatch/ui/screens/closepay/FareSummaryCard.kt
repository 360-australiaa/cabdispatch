package au.com.threesixty.cabdispatch.ui.screens.closepay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.local.entity.airportAccessFee
import au.com.threesixty.cabdispatch.domain.fare.FareBreakdown
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import java.math.BigDecimal
import java.math.RoundingMode

// W7 file split (2026-09-13): extracted from the former monolithic CloseAndPayScreen.kt (1,629
// lines) — see that file's own doc for the split rationale. This file is the fare-summary card
// [MethodPickerScreen][PaymentMethodSheet.kt] shows next to the payment-method tiles: the itemised
// [TotalCol] breakdown plus its two "tap to add an extra amount" rows and their dialogs
// (cleaning fee, tip). [TotalCol]/[CleaningFeeDialog]/[TipPresetDialog]/[CustomTipDialog] are
// `internal` (not `private`) because [PaymentMethodSheet.kt]'s `MethodPickerScreen` calls them
// across this file boundary — same package (`ui.screens.closepay`), so no import needed either
// way, only the visibility modifier changes from the pre-split file.

/**
 * Fully itemized NSW-compliant breakdown (2026-09-02 pass — see `CLOSE_AND_PAY_COMPLIANCE_2026.md`).
 * Reads only real [FareBreakdown]/[CloseAndPayUiState.ReadyToClose] fields — nothing here is a
 * UI-side guess:
 * - Negotiated ("Set Price") trips show the agreed amount as the base line instead of a metered
 *   flagfall/distance/waiting breakdown (the metered accrual genuinely wasn't what was billed —
 *   see [FareBreakdown.negotiatedTotal]'s doc).
 * - The maxi (×[au.com.threesixty.cabdispatch.domain.fare.Tariff.maxiMultiplier]) uplift is its own
 *   line, derived from the real pre-multiplier component rows plus the tariff's own multiplier
 *   field — never a hardcoded "×1.5". Only shown for a metered (non-negotiated) trip, since a
 *   negotiated total is never itself maxi-multiplied (see [FareEngine.close]'s `effectiveFare`).
 * - PSL is a mandatory regulated pass-through, not a driver-optional toggle (2026-09-05 fix —
 *   the Fares Order levy must be collected whenever it's genuinely due, so there is no on/off
 *   switch here any more; drivers see it itemized exactly like Flagfall/Distance/Waiting).
 *   Suppressed entirely for the Sydney Airport Fixed Fare path — the one real, already-coded
 *   exemption (verified: [reconstructFareState] sets `state.fixedFare` for
 *   `trip.type == "airport_fixed"`, and [FareEngine.close]'s `fixedFare` branch hardcodes
 *   `psl = BigDecimal.ZERO` regardless of `includePsl`) — every other trip type always shows it.
 * - Cleaning fee is its own line when non-zero, with an honest cap caption
 *   ([au.com.threesixty.cabdispatch.domain.fare.Tariff.cleaningFeeCap], not a hardcoded "$124.14").
 * - The non-cash surcharge line shows the *actual* live percentage
 *   ([CloseAndPayUiState.ReadyToClose.surchargePct]), not a hardcoded "1.5%" — hidden automatically
 *   for cash/voucher/account/split-fare because [FareBreakdown.surcharge] is genuinely zero for
 *   those (see [CloseAndPayViewModel.recompute] — surcharge only ever computes when
 *   `paymentMethod.persistedValue == "card"`).
 */
@Composable
internal fun TotalCol(
    state: CloseAndPayUiState.ReadyToClose,
    onReportSoiling: () -> Unit,
    onAddTip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val breakdown = state.breakdown
    val tariff = state.tariff
    // Verified (not assumed) against FareEngine.close()/reconstructFareState — see this
    // function's doc — that the Sydney Airport Fixed Fare path always zeroes psl regardless of
    // includePsl, so it's the one real exemption where the levy line is genuinely not due.
    val isAirportFixed = state.trip.type == "airport_fixed"
    // Negotiated ("Set Price") or Sydney Airport Fixed — the two FareEngine.close() branches
    // that absorb the non-cash surcharge (2026-09 product ruling) rather than billing it on top.
    val isAbsorbedFare = isAirportFixed || breakdown.negotiatedTotal != null

    // Scrollable (2026-09-03 tips live pass, real device finding): the added Tip row pushed
    // "Report vehicle soiling" (and, on a taller breakdown, other rows) behind the fixed
    // "← Back to meter" button at the bottom of MethodPickerScreen's Box — this Column had no
    // scroll of its own, so overflow was silently clipped rather than reachable. Same class of
    // bug Phase A's live pass found and fixed on HiredScreen.kt (content clipping behind footer).
    Column(
        modifier = modifier.width(400.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Close & Pay", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = CaptainPalette.textPrimary)
        // HUD kit rebuild: the FARE SUMMARY is two GlassCards — the total (RollingMoneyText, per
        // the kit's own "digits roll" standard, see Hud.kt's header doc) and the itemized
        // breakdown — replacing this pass's previous hand-rolled inset/panel Columns and manual
        // `animateFloatAsState` count-up. Same fields, same order, nothing recomputed differently.
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 20, glow = CaptainPalette.hudAccent) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text("TOTAL DUE", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CaptainPalette.textMuted)
                RollingMoneyText(
                    amount = state.totalDue.money(),
                    fontSize = 84.sp,
                    color = CaptainPalette.success,
                )
                if (state.tip.signum() > 0) {
                    Text(
                        "Includes ${state.tip.money()} tip",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = CaptainPalette.textMuted,
                    )
                }
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 18) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            if (breakdown.negotiatedTotal != null) {
                BreakdownRow("Agreed price (Set Price)", breakdown.negotiatedTotal.money())
            } else {
                BreakdownRow("Flagfall", breakdown.flagFall.money())
                BreakdownRow("Distance", breakdown.distanceCharge.money())
                BreakdownRow("Time", breakdown.waitingCharge.money())
                // Informational only, same wording/placement as HiredScreen.kt's Fare Breakdown
                // card (Phase A): the night-rate uplift is already baked into Distance/Time above
                // (the fare engine applies the night per-km/per-min rate directly — there is no
                // separate night-surcharge line item), so this never adds to the total itself,
                // only explains the higher Distance/Time figures when it applies.
                if (state.trip.timeClass.equals("night", ignoreCase = true)) {
                    val nightMultiplierLabel = if (tariff.distRate1.signum() > 0) {
                        "${tariff.nightRate1.divide(tariff.distRate1, 2, RoundingMode.HALF_UP)}×"
                    } else {
                        null
                    }
                    BreakdownRow("Night Fare (${nightMultiplierLabel ?: "—"})", "included above")
                }
                if (breakdown.peakCharge.signum() > 0) {
                    BreakdownRow("Peak Hiring", breakdown.peakCharge.money())
                }
                if (breakdown.maxiRateApplied) {
                    // "The fare" per the Fares Order = flagfall + peak + distance + waiting — the
                    // ONLY component the maxi multiplier applies to (see FareEngine.close()'s own
                    // comment). The four breakdown fields above are stored PRE-multiplier, so this
                    // line is the multiplier's whole contribution.
                    //
                    // Taken from the engine rather than re-derived here as
                    // `meteredBase * (maxiMultiplier - 1)`: that computed the right quantity but
                    // rounded it independently of the total, so the rows could add up to a cent
                    // more than the TOTAL beneath them. FareBreakdown.maxiUplift is defined as
                    // whatever makes these rows reconcile exactly.
                    val multiplierLabel = tariff.maxiMultiplier.stripTrailingZeros().toPlainString()
                    BreakdownRow("Maxi-cab rate (×$multiplierLabel, 5+ passengers)", breakdown.maxiUplift.money())
                }
            }
            BreakdownRow("Tolls", breakdown.tolls.money())
            // Names the airport pickup fee inside the Tolls total, so the passenger sees WHICH
            // charge that is and from which terminal (TripEntity.airportAccessFeeJson's doc).
            // Never under the Sydney Airport Fixed Fare: that branch zeroes tolls entirely, and a
            // line naming a fee that was not charged would contradict the total above it.
            if (!isAirportFixed) {
                state.trip.airportAccessFee()?.let { fee -> BreakdownSubRow(fee.subLine()) }
            }
            if (breakdown.extras.signum() > 0) BreakdownRow("Extras", breakdown.extras.money())
            if (breakdown.cleaningFee.signum() > 0) BreakdownRow("Cleaning fee", breakdown.cleaningFee.money())
            // Mandatory regulated levy — no toggle. Shown as a fixed line item exactly like
            // Flagfall/Distance/Waiting above; suppressed only for the one real, already-coded
            // exemption (Sydney Airport Fixed Fare, see isAirportFixed's doc above).
            if (!isAirportFixed) {
                BreakdownRow("Point to Point Transport Levy", breakdown.psl.money())
            }
            if (breakdown.surcharge.signum() > 0) {
                val pctLabel = state.surchargePct.stripTrailingZeros().toPlainString()
                // 2026-09 product ruling: a negotiated/fixed fare (see isAbsorbedFare below)
                // ABSORBS the non-cash surcharge — it is still computed and shown here (the
                // driver should see what card fee was absorbed) but it is NOT added to TOTAL
                // DUE above, so it must never read like an ordinary additive line.
                val label = if (isAbsorbedFare) {
                    "Non-cash surcharge ($pctLabel%) — absorbed, not charged"
                } else {
                    "Non-cash payment surcharge ($pctLabel%)"
                }
                BreakdownRow(label, breakdown.surcharge.money())
            }
            BreakdownRow("GST included", breakdown.gstComponent.money())
            // Tip (Close & Pay "tips" pass) — its own line, added at the display level only:
            // never part of any figure above it (Flagfall..GST included are all real
            // FareBreakdown/tariff fields, untouched by state.tip). See CloseAndPayUiState
            // .ReadyToClose.tip/.totalDue's own doc and the backend Trip.tip_amount column doc.
            if (state.tip.signum() > 0) {
                BreakdownRow("Tip", state.tip.money())
            }
            }
        }
        if (breakdown.negotiatedTotal != null) {
            Text(
                "Agreed (Set Price) trip — the levy, any tolls, and a card surcharge (if applicable) above are already included in the agreed amount, not charged on top. Only a cleaning fee adds to the total.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = CaptainPalette.textMuted,
            )
        }
        CleaningFeeEntryRow(currentFee = state.cleaningFee, cap = tariff.cleaningFeeCap, onClick = onReportSoiling)
        TipEntryRow(currentTip = state.tip, onClick = onAddTip)
    }
}

/** Indented, muted explanatory line under a [BreakdownRow] — a component of the row above it,
 * never an additive charge of its own (so no amount column: the figure is already counted). */
@Composable
private fun BreakdownSubRow(text: String) {
    Text(
        text,
        fontFamily = InterFamily,
        fontSize = 14.sp,
        color = CaptainPalette.textMuted,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun BreakdownRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary)
        Text(value, fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = CaptainPalette.textPrimary)
    }
}

/** Entry point for [CloseAndPayViewModel.setCleaningFee] — previously unreachable from any UI (see
 * `FARE_ENGINE_2026_CHANGES.md` Risk Notes). Honest about the real cap: shows [cap] (the tariff's
 * actual [au.com.threesixty.cabdispatch.domain.fare.Tariff.cleaningFeeCap]), never implies a driver
 * can charge more even though the engine would clamp it anyway. */
@Composable
private fun CleaningFeeEntryRow(currentFee: BigDecimal, cap: BigDecimal, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), cornerRadiusDp = 14) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    if (currentFee.signum() > 0) "Cleaning fee reported" else "Report vehicle soiling",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    "Up to ${cap.money()} — the legal maximum, enforced by the engine",
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            Text(
                if (currentFee.signum() > 0) currentFee.money() else "+",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                color = CaptainPalette.hudAccent,
            )
        }
    }
}

/** Entry point for [CloseAndPayViewModel.setTip] (Close & Pay "tips" pass) — same tappable-row
 * shape as [CleaningFeeEntryRow] immediately above, for visual consistency between the two "tap
 * to add an extra amount" rows on this screen. */
@Composable
private fun TipEntryRow(currentTip: BigDecimal, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), cornerRadiusDp = 14) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    if (currentTip.signum() > 0) "Tip added" else "Add a tip",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    "Collected on top of the fare — never part of the metered total or GST",
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            Text(
                if (currentTip.signum() > 0) currentTip.money() else "+",
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                color = CaptainPalette.hudAccent,
            )
        }
    }
}

/** Dialog for [CleaningFeeEntryRow] — mirrors `HiredScreen.kt`'s `CustomTollDialog` shape/keypad
 * pattern for visual consistency across the app's few "type an amount on the shared keypad" flows.
 * The amount typed here is never sent uncapped: [CloseAndPayViewModel.setCleaningFee] itself clamps
 * to [cap], so the over-cap warning below is purely informational, not the only thing preventing
 * an unlawful charge. */
@Composable
internal fun CleaningFeeDialog(cap: BigDecimal, initial: BigDecimal, onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var cents by remember {
        mutableStateOf(if (initial.signum() > 0) initial.movePointRight(2).toBigInteger().toString() else "")
    }
    val amount = if (cents.isEmpty()) BigDecimal.ZERO else BigDecimal(cents).movePointLeft(2)
    val overCap = amount > cap

    GlassCard(modifier = Modifier.width(480.dp), cornerRadiusDp = 24, glow = CaptainPalette.hudAccent) {
    Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Report vehicle soiling", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            "Up to ${cap.money()} — the Point to Point Transport (Fares) Order 2026 sets this as the legal maximum cleaning fee.",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
            textAlign = TextAlign.Center,
        )
        GlassCard(modifier = Modifier.width(320.dp).height(72.dp), cornerRadiusDp = 14) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    amount.money(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    color = if (overCap) CaptainPalette.warning else CaptainPalette.success,
                )
            }
        }
        if (overCap) {
            Text("Will be capped to ${cap.money()} — that's the legal maximum.", fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.warning)
        }
        CaptainKeypad(
            onDigit = { d -> if (cents.length < 6) cents += d },
            onBackspace = { cents = cents.dropLast(1) },
            onClear = { cents = "" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
            CaptainButton(text = "Apply fee", modifier = Modifier.weight(1.4f)) { onConfirm(amount) }
        }
        if (initial.signum() > 0) {
            CaptainButton(text = "Remove cleaning fee", outline = true, modifier = Modifier.fillMaxWidth()) { onConfirm(BigDecimal.ZERO) }
        }
    }
    }
}

/**
 * "Add a tip", tapped — same $2/$5/$10 preset-chip + "Custom amount…" shape as `HiredScreen.kt`'s
 * `TollPresetDialog` (Phase A step 5), reused rather than re-invented for visual consistency
 * across the app's "quick preset or type your own" dialogs. `onSelectPreset`/`onCustom` map
 * straight to [CloseAndPayViewModel.setTip] at the call site.
 */
@Composable
internal fun TipPresetDialog(
    currentTip: BigDecimal,
    onDismiss: () -> Unit,
    onSelectPreset: (BigDecimal) -> Unit,
    onCustom: () -> Unit,
) {
    GlassCard(modifier = Modifier.width(480.dp), cornerRadiusDp = 24, glow = CaptainPalette.hudAccent) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add a tip", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            Text(
                if (currentTip.signum() > 0) "Current tip: ${currentTip.money()}" else "Collected on top of the fare — never part of the metered total or GST.",
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = CaptainPalette.textSecondary,
            )
            // GlassCard tip-preset row ($2/$5/$10/Custom) — the kit's payment-grid tile shape at
            // dialog scale, replacing this pass's previous stacked CaptainChip column.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BigDecimal("2.00"), BigDecimal("5.00"), BigDecimal("10.00")).forEach { preset ->
                    TipPresetTile(
                        label = "$${preset.toBigInteger()}",
                        selected = currentTip.compareTo(preset) == 0,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectPreset(preset) },
                    )
                }
                TipPresetTile(label = "Custom", selected = false, modifier = Modifier.weight(1f), onClick = onCustom)
            }
            if (currentTip.signum() > 0) {
                CaptainButton(text = "Remove tip", outline = true, modifier = Modifier.fillMaxWidth()) { onSelectPreset(BigDecimal.ZERO) }
            }
            CaptainButton(text = "Close", outline = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
        }
    }
}

/** One tile of the [TipPresetDialog]'s GlassCard row — accent glow when [selected] (the currently
 * set tip amount matches this preset), mirrors the payment-grid tile's selected-state treatment. */
@Composable
private fun TipPresetTile(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassCard(
        modifier = modifier.height(64.dp).clickable(onClick = onClick),
        cornerRadiusDp = 14,
        glow = if (selected) CaptainPalette.hudAccent else null,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = if (selected) CaptainPalette.hudAccent else CaptainPalette.textPrimary,
            )
        }
    }
}

/** "Custom amount…", tapped — mirrors `HiredScreen.kt`'s `CustomTollDialog`/this file's own
 * [CleaningFeeDialog] shape/keypad exactly: reuses [CaptainKeypad], no new keypad implementation. */
@Composable
internal fun CustomTipDialog(onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var cents by remember { mutableStateOf("") }
    val amount = if (cents.isEmpty()) BigDecimal.ZERO else BigDecimal(cents).movePointLeft(2)

    GlassCard(modifier = Modifier.width(480.dp), cornerRadiusDp = 24, glow = CaptainPalette.hudAccent) {
    Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Custom tip", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        GlassCard(modifier = Modifier.width(448.dp).height(72.dp), cornerRadiusDp = 14) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    amount.money(),
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 38.sp,
                    color = CaptainPalette.success,
                )
            }
        }
        CaptainKeypad(
            onDigit = { d -> if (cents.length < 6) cents += d },
            onBackspace = { cents = cents.dropLast(1) },
            onClear = { cents = "" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
            CaptainButton(
                text = "Add tip",
                enabled = amount > BigDecimal.ZERO,
                modifier = Modifier.weight(1.4f),
            ) { onConfirm(amount) }
        }
    }
    }
}
