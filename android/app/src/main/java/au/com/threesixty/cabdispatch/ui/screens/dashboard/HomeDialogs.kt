package au.com.threesixty.cabdispatch.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.DriverSession
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.screens.vouchers.VouchersPaneContent
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.color
import au.com.threesixty.cabdispatch.ui.theme.DriverAvatar
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * Every dialog and overlay card the home screen raises: the voucher info dialog, the
 * driver ID card, the fixed-price entry dialog and the trip-details dialog with its
 * stepper and toggle rows.
 *
 * Extracted from DeckHomeScreen.kt in Phase 0 (P0.4) — a mechanical move, same package,
 * same declarations, no behaviour change.
 */

// ============================================================================================
// Small shared pieces
// ============================================================================================

@Composable
internal fun VoucherInfoDialog(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Vouchers", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
            // Updated for Phase G (`squishy-herding-iverson.md`): a real voucher-ledger browse
            // screen now exists (the nav rail's VOUCHERS item -> VouchersPaneContent), so this
            // quick-action tile's copy no longer claims "no voucher wallet at all" — it still
            // correctly says redemption itself only ever happens at Close & Pay, against the trip
            // being paid for, never from a standalone "apply" action anywhere in this app.
            Text(
                "Browse available/used/expired vouchers from the VOUCHERS tab in the side menu. A " +
                    "voucher code is redeemed at the end of the trip, during payment.",
                fontFamily = InterFamily,
                fontSize = 17.sp,
                color = CaptainPalette.textSecondary,
            )
            CaptainButton(text = "Got it", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Large passenger-facing driver ID card (2026-08-29 premium pass) — opened by tapping the header
 * avatar. Exists so a passenger can actually match the driver's face to the registered photo
 * (the 88dp header chip is a control, not an ID). Everything shown is real session/backend data:
 * the same [DriverAvatar] photo loader at 300dp, the session's driver name / driver # / vehicle,
 * and the same backend-verified [verified] flag the header badge uses — nothing fabricated.
 */
@Composable
internal fun DriverIdCard(
    session: au.com.threesixty.cabdispatch.domain.DriverSession?,
    verified: Boolean?,
    onOpenProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(CaptainPalette.panel)
                .border(1.5.dp, CaptainPalette.accent.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                .clickable(enabled = false) {}
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("DRIVER ID", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 4.sp, color = CaptainPalette.textMuted)
            DriverAvatar(driverId = session?.driverId, driverName = session?.driverName, onClick = onDismiss, sizeDp = 300)
            Text(
                session?.driverName ?: "No driver signed in",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                color = CaptainPalette.textPrimary,
            )
            Text(
                session?.let { "DRIVER # ${it.driverId.take(8).uppercase()} · VEHICLE ${it.vehicleId}" } ?: "—",
                fontFamily = RobotoMonoFamily,
                fontSize = 17.sp,
                color = CaptainPalette.textSecondary,
            )
            if (verified == true) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(CaptainPalette.primary)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text("VERIFIED DRIVER", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CaptainPalette.onAccent)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                CaptainButton(text = "Close", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                CaptainButton(text = "View profile", modifier = Modifier.weight(1.3f), onClick = onOpenProfile)
            }
        }
    }
}

// ============================================================================================
// Set Price (fixed fare) — unchanged flow (WheelDashboardViewModel.startMeter(negotiatedTotal=)),
// restyled to CaptainPalette. Not one of the 3 given Figma frames; kept because it is real,
// working, pre-existing functionality this pass must not remove.
// ============================================================================================

@Composable
internal fun SetPriceDialogV2(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Set price — fixed fare", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
            Text(
                // 2026-09 product correction: this used to read "Levies and GST still apply on
                // top" — no longer true. A negotiated/fixed price is now all-inclusive (see
                // domain.fare.FareEngine.close's negotiatedTotal branch) — the levy and any tolls
                // are absorbed into the agreed amount, never billed on top of it.
                "Agreed with the passenger before starting. This is the total the passenger pays — levies and tolls are included, not added on top.",
                fontFamily = InterFamily,
                fontSize = 16.sp,
                color = CaptainPalette.textSecondary,
            )
            Box(
                modifier = Modifier.width(448.dp).height(84.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.inset),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (amount.isEmpty()) "$0" else "$" + amount,
                    fontFamily = au.com.threesixty.cabdispatch.ui.theme.ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 44.sp,
                    color = CaptainPalette.success,
                )
            }
            CaptainKeypad(
                onDigit = { d -> if (amount.length < 3) amount += d }, // matches the Fares Order cap this dialog already enforced: $1-$500
                onBackspace = { amount = amount.dropLast(1) },
                onClear = { amount = "" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                CaptainButton(
                    text = "Start at fixed price",
                    heightDp = 72,
                    enabled = (amount.toIntOrNull() ?: 0) in 1..500,
                    modifier = Modifier.weight(1.6f),
                    onClick = { onConfirm(amount) },
                )
            }
        }
    }
}

// ============================================================================================
// Trip details — Point to Point Transport (Fares) Order 2026 UI-wiring pass. Shown on a plain
// (non-Set-Price) Start Meter tap so the driver can honestly declare the inputs the maxi (150%)
// rate actually turns on. Every default below (1 passenger, every toggle off) reproduces this
// app's pre-existing Start Meter behavior exactly — a driver who taps straight through without
// touching anything starts an ordinary, non-maxi, 1-passenger trip, same as before this pass.
// ============================================================================================

/**
 * Elderly-friendly passenger-count stepper (1-11) + three honestly-labelled maxi-rate
 * declaration toggles, shown before a plain metered Start Meter tap actually opens the trip. Not
 * shown for the Set Price ("fixed fare") flow — see [SetPriceDialogV2]'s own comment: that flow
 * is a separate, already-engine-correct feature this pass does not touch.
 *
 * [initialMaxiVehicle] prefills from [au.com.threesixty.cabdispatch.domain.MaxiVehicleStore] (the
 * driver's own prior declaration, or `false` if never set) so this doesn't ask the driver to
 * re-declare a fact about the vehicle on every single trip — but it is still just a local,
 * per-device self-declaration, never fleet-registry data (see that store's own doc), which is why
 * this dialog labels it "This vehicle has 5+ passenger seats", never implying it came from a
 * vehicle record.
 */
@Composable
internal fun TripDetailsDialog(
    initialMaxiVehicle: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (passengerCount: Int, isMaxiVehicle: Boolean, wheelchairHiring: Boolean, airportRankRequestedMaxi: Boolean) -> Unit,
) {
    var passengerCount by rememberSaveable { mutableStateOf(1) }
    var isMaxiVehicle by rememberSaveable { mutableStateOf(initialMaxiVehicle) }
    var wheelchairHiring by rememberSaveable { mutableStateOf(false) }
    var airportRankRequestedMaxi by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Before you start the meter", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            Text(
                "Passenger count is the main thing that turns on the maxi (×1.5) rate — a quick, honest check before you drive off.",
                fontFamily = InterFamily,
                fontSize = 15.sp,
                color = CaptainPalette.textSecondary,
            )

            // --- Passenger count stepper (big, elderly-friendly touch targets) ---
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PASSENGERS", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StepperButton(label = "−", enabled = passengerCount > 1, onClick = { passengerCount = (passengerCount - 1).coerceIn(1, 11) })
                    Box(
                        modifier = Modifier.width(96.dp).height(72.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.inset),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            passengerCount.toString(),
                            fontFamily = au.com.threesixty.cabdispatch.ui.theme.ChakraPetch,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 40.sp,
                            color = CaptainPalette.textPrimary,
                        )
                    }
                    StepperButton(label = "+", enabled = passengerCount < 11, onClick = { passengerCount = (passengerCount + 1).coerceIn(1, 11) })
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(CaptainPalette.panelBorder))

            TripDetailToggleRow(
                title = "This vehicle has 5+ passenger seats",
                subtitle = "Your own declaration for this vehicle — not read from a vehicle record. Saved for next time.",
                checked = isMaxiVehicle,
                onCheckedChange = { isMaxiVehicle = it },
            )
            TripDetailToggleRow(
                title = "Carrying a wheelchair passenger",
                subtitle = "Per NSW Reg cl 82: start the meter only once the passenger is safely secured. The maxi rate never applies to a wheelchair hiring, regardless of passenger count.",
                checked = wheelchairHiring,
                onCheckedChange = { wheelchairHiring = it },
            )
            TripDetailToggleRow(
                title = "Requested as a maxi at a Sydney Airport rank",
                subtitle = "Only tick this if the hirer specifically asked for a maxi taxi at a Sydney Airport rank — not for an ordinary trip that happens to go to the airport.",
                checked = airportRankRequestedMaxi,
                onCheckedChange = { airportRankRequestedMaxi = it },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CaptainButton(text = "Cancel", outline = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                CaptainButton(
                    text = "▶  Start meter",
                    heightDp = 72,
                    modifier = Modifier.weight(1.6f),
                    onClick = { onConfirm(passengerCount, isMaxiVehicle, wheelchairHiring, airportRankRequestedMaxi) },
                )
            }
        }
    }
}

/** One big (64dp) circular +/- button for [TripDetailsDialog]'s passenger stepper. */
@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(if (enabled) CaptainPalette.raised else CaptainPalette.inset)
            .border(1.dp, CaptainPalette.panelBorder, CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            color = if (enabled) CaptainPalette.textPrimary else CaptainPalette.textMuted,
        )
    }
}

/** One labelled toggle row for [TripDetailsDialog] — title + honest explanatory subtitle + a
 * standard Material [Switch], tinted to [CaptainPalette]. */
@Composable
private fun TripDetailToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = CaptainPalette.textPrimary)
            Text(subtitle, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = CaptainPalette.primary, checkedThumbColor = CaptainPalette.accent),
        )
    }
}
