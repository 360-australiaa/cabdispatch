package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.domain.AutoTollEntry
import au.com.threesixty.cabdispatch.domain.FareState
import au.com.threesixty.cabdispatch.domain.TollPreset
import au.com.threesixty.cabdispatch.domain.TollPresets
import au.com.threesixty.cabdispatch.domain.TripContext
import au.com.threesixty.cabdispatch.domain.UnpricedTollRoad
import au.com.threesixty.cabdispatch.domain.toMoneyString
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainChip
import au.com.threesixty.cabdispatch.ui.theme.CaptainDialogScrim
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.Type
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RollingMoneyText
import java.math.BigDecimal

/**
 * The meter screen's banners and modal surfaces: the accrual note, the set-price and toll
 * dialogs with their auto/unpriced toll rows, the more-actions sheet, the passenger edit
 * dialog and the custom toll dialog.
 *
 * Extracted from HiredScreen.kt in Phase 0 (P0.4) — a mechanical move, same package, same
 * declarations, no behaviour change.
 */

@Composable
internal fun AccrualNote() {
    Text(
        "One of distance or waiting accrues at a time — switches automatically at 26 km/h",
        // 10sp -> 12sp (A4: Type.tiny, the accessibility floor).
        style = Type.tiny,
        color = CaptainPalette.textMuted,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * SET PRICE, tapped (Phase A step 5) — deliberately **not** an editable control.
 * `TripContext.negotiatedTotal` is real: it is the fixed fare the driver agreed with the passenger
 * at Start Meter time (via the dashboard's own Set Price flow), already persisted to
 * `TripEntity.negotiatedTotal` and synced to the backend — but nothing in [HiredViewModel] can
 * *change* it once a trip is running (no `setNegotiatedTotal()`/equivalent exists, and this pass's
 * `HiredViewModel` edit budget is scoped to wiring the already-existing
 * `breakdownExpanded`/`toggleBreakdown()` pair only — adding one would be new business logic this
 * pass has no mandate to add). Building a text field or keypad here that looks like it edits the
 * price, when nothing downstream would ever read the edit, is exactly the fake affordance this
 * codebase's own EXTRAS button already refuses to be (see that dialog's identical shape) — so this
 * shows the real value (or its real absence) and says why, instead.
 */
@Composable
internal fun SetPriceInfoDialog(negotiatedTotal: String?, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .width(520.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set Price", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            if (negotiatedTotal != null) {
                "This trip is a fixed fare of ${formatNegotiatedTotal(negotiatedTotal)}, agreed before the meter started. " +
                    "It can't be changed once a trip is running."
            } else {
                "This trip is running on the metered fare. To fix a price up front instead, use SET PRICE " +
                    "from the dashboard before starting the next trip."
            },
            fontFamily = InterFamily,
            fontSize = 15.sp,
            color = CaptainPalette.textSecondary,
        )
        CaptainButton(text = "OK", outline = true, widthDp = 180) { onDismiss() }
    }
}

/**
 * ADD TOLL, tapped (Phase A step 5) — the same three real toll presets ([TollPresets.ALL]) and
 * custom-amount pad this screen always had, consolidated from four separate inline chips into one
 * dialog reached from the action stack. `onAddPreset`/`onCustom` map straight back to
 * `viewModel.addToll(preset)` at the call site — no new toll logic here.
 *
 * Automatic NSW toll-road detection pass: also the one place the driver sees and corrects what the
 * on-device detector added on its own — [autoTolls] (real gantry crossings already billed, each
 * with its own [onRemoveAutoToll] so a false positive never sticks with no recourse) and
 * [unpricedRoads] (real crossings the registry genuinely can't auto-price — `zone_flat`/unpriced
 * roads — surfaced so the driver adds a manual toll via [onAddManualForUnpriced] instead of the
 * fare silently missing a real cost). Both sections are empty, and therefore invisible, on a trip
 * with no auto-detected crossings — this dialog looks and behaves exactly as it always did until
 * there is something real to show.
 */
@Composable
internal fun TollPresetDialog(
    tollsTotal: BigDecimal,
    autoTolls: List<AutoTollEntry>,
    unpricedRoads: List<UnpricedTollRoad>,
    onDismiss: () -> Unit,
    onAddPreset: (TollPreset) -> Unit,
    onCustom: () -> Unit,
    onRemoveAutoToll: (String) -> Unit,
    onAddManualForUnpriced: () -> Unit,
    onDismissUnpriced: (String) -> Unit,
) {
    // Presets in one Row and the two buttons in another (game-level visual pass): this dialog is
    // hosted inside the ~420dp-tall METER pane (CaptainDialogScrim fills the pane, not the
    // window), and the previous five-row stack ran ~500dp — its Close button was clipped behind
    // the footer on-device. Same chips, same callbacks, just laid out to fit.
    Column(
        modifier = Modifier
            .width(720.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            "Tolls so far: ${tollsTotal.toMoneyString()}",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TollPresets.ALL.forEach { preset ->
                CaptainChip(preset.label.uppercase(), preset.amount.toMoneyString(), modifier = Modifier.weight(1f)) {
                    onAddPreset(preset)
                }
            }
        }
        if (autoTolls.isNotEmpty() || unpricedRoads.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (autoTolls.isNotEmpty()) {
                    Text(
                        "AUTO-DETECTED",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        // 11sp -> 12sp (A4: Type.tiny, the accessibility floor).
                        style = Type.tiny,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.textMuted,
                    )
                    autoTolls.forEach { entry -> AutoTollRow(entry, onRemove = { onRemoveAutoToll(entry.roadId) }) }
                }
                if (unpricedRoads.isNotEmpty()) {
                    Text(
                        "NEEDS A MANUAL TOLL",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        // 11sp -> 12sp (A4: Type.tiny, the accessibility floor).
                        style = Type.tiny,
                        letterSpacing = 1.sp,
                        color = CaptainPalette.warning,
                    )
                    unpricedRoads.forEach { road ->
                        UnpricedTollRow(
                            road,
                            onAdd = onAddManualForUnpriced,
                            onDismiss = { onDismissUnpriced(road.roadId) },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptainButton(text = "Custom amount…", outline = true, modifier = Modifier.weight(1.4f)) { onCustom() }
            CaptainButton(text = "Close", outline = true, modifier = Modifier.weight(1f)) { onDismiss() }
        }
    }
}

/** One auto-detected toll — real road name + amount, with a small REMOVE affordance so the driver
 * can undo a false positive (see [TollPresetDialog]'s own class doc for why this matters). */
@Composable
private fun AutoTollRow(entry: AutoTollEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CaptainPalette.raised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(entry.roadName, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textPrimary)
            Text("Auto-detected · ${entry.amount.toMoneyString()}", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
        }
        CaptainButton(text = "Remove", outline = true, widthDp = 100, heightDp = 40, fontSize = 13.sp, onClick = onRemove)
    }
}

/** One real toll road crossed that the registry can't auto-price — see [TollPresetDialog]'s class
 * doc for why this is never a guessed amount. [onAdd] opens the same manual-amount entry
 * ([onAddManualForUnpriced]) every other toll on this screen already uses. */
@Composable
private fun UnpricedTollRow(road: UnpricedTollRoad, onAdd: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CaptainPalette.raised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(road.roadName, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textPrimary)
            Text("Crossed — no on-file price, add manually", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptainButton(text = "Dismiss", outline = true, widthDp = 90, heightDp = 40, fontSize = 13.sp, onClick = onDismiss)
            CaptainButton(text = "Add", widthDp = 80, heightDp = 40, fontSize = 13.sp, onClick = onAdd)
        }
    }
}

/**
 * MORE, tapped (Phase A step 5; destination search added 2026-09-04) — an overflow sheet for the
 * previously-inline EXTRAS-explainer, passenger-count correction, speech-announcement toggle, and
 * now the real destination search (opens [DestinationSearchDialog] via [onNavigate]), so the
 * action stack itself stays to exactly four rows matching the mockup. Every row still calls the
 * exact same pre-existing ViewModel entry point (`updatePassengerCount()`/`toggleSpeech()`) or the
 * real [MeterNavViewModel] search flow via the caller's lambdas.
 */
@Composable
internal fun MoreActionsSheet(
    speechEnabled: Boolean,
    passengerCount: Int,
    hasDestination: Boolean,
    destinationLabel: String?,
    onToggleSpeech: (Boolean) -> Unit,
    onEditPassengers: () -> Unit,
    onExtras: () -> Unit,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(480.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("More", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onNavigate).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Navigation, contentDescription = if (hasDestination) "Change destination" else "Set destination", tint = CaptainPalette.hudAccent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    if (hasDestination) "Change destination" else "Set destination",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    destinationLabel ?: "Search an address to start turn-by-turn",
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    color = CaptainPalette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onExtras).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Receipt, contentDescription = "Extras", tint = CaptainPalette.accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text("Extras", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text("No chargeable extras configured yet", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onEditPassengers).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = "Passenger count", tint = CaptainPalette.accent, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text("Passenger count", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text("$passengerCount — tap to correct", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Speech announcements", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
                Text(if (speechEnabled) "Announcing fare + nav turns" else "Off", fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary)
            }
            SpeechToggleButton(enabled = speechEnabled, onToggle = { onToggleSpeech(!speechEnabled) })
        }

        CaptainButton(text = "Close", outline = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
    }
}

/**
 * Mid-trip passenger-count correction dialog (Point to Point Transport (Fares) Order 2026
 * UI-wiring pass) — reached only via [MoreActionsSheet] now (previously a small "PAX n ✎" tap-to-
 * edit affordance floating on the meter well); same [HiredViewModel.updatePassengerCount] call and
 * dialog otherwise. Confirming re-derives [au.com.threesixty.cabdispatch.domain.FareState.maxiRateApplied]
 * immediately for the remainder of the trip without touching any already-accrued charge — see that
 * method's own doc.
 */
@Composable
internal fun PassengerEditDialog(initialCount: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var count by remember { mutableStateOf(initialCount) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Correct passenger count", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
        Text(
            "5 or more passengers may trigger the maxi rate — only for a genuine maxi vehicle, and never for a wheelchair hiring.",
            fontFamily = InterFamily,
            fontSize = 14.sp,
            color = CaptainPalette.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (count > 1) CaptainPalette.raised else CaptainPalette.inset)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .then(if (count > 1) Modifier.clickable { count -= 1 } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("−", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = CaptainPalette.textPrimary)
            }
            Box(
                modifier = Modifier.width(96.dp).height(72.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.inset),
                contentAlignment = Alignment.Center,
            ) {
                Text(count.toString(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, color = CaptainPalette.textPrimary)
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (count < 11) CaptainPalette.raised else CaptainPalette.inset)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .then(if (count < 11) Modifier.clickable { count += 1 } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = CaptainPalette.textPrimary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
            CaptainButton(text = "Update", modifier = Modifier.weight(1.4f)) { onConfirm(count) }
        }
    }
}

@Composable
internal fun CustomTollDialog(onDismiss: () -> Unit, onConfirm: (BigDecimal) -> Unit) {
    var cents by remember { mutableStateOf("") }
    val amount = if (cents.isEmpty()) BigDecimal.ZERO else BigDecimal(cents).movePointLeft(2)
    // Two columns (amount + buttons | keypad) rather than one stack — same reason as
    // TollPresetDialog: hosted inside the ~420dp-tall METER pane, and title + amount + a
    // 4-row keypad + buttons stacked vertically ran past the pane's bottom edge on-device.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add toll", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CaptainPalette.inset),
                contentAlignment = Alignment.Center,
            ) {
                // Rolling digits here too — money that changes as the driver types.
                RollingMoneyText(amount = amount.toMoneyString(), fontSize = 38.sp, color = CaptainPalette.success, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(
                text = "Add toll",
                enabled = amount > BigDecimal.ZERO,
                modifier = Modifier.fillMaxWidth(),
            ) { onConfirm(amount) }
            CaptainButton(text = "Cancel", outline = true, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
        CaptainKeypad(
            onDigit = { d -> if (cents.length < 5) cents += d },
            onBackspace = { cents = cents.dropLast(1) },
            onClear = { cents = "" },
        )
    }
}
