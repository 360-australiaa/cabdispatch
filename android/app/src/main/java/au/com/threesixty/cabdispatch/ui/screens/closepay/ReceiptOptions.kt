package au.com.threesixty.cabdispatch.ui.screens.closepay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TenantBranding
import au.com.threesixty.cabdispatch.hardware.SIMULATED_BANNER
import au.com.threesixty.cabdispatch.hardware.TEST_RECEIPT_MARKER
import au.com.threesixty.cabdispatch.ui.deck.rememberDeckClock
import au.com.threesixty.cabdispatch.ui.overlays.reportsChromeHeader
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import au.com.threesixty.cabdispatch.ui.theme.Space
import kotlinx.coroutines.launch

// W7 file split (2026-09-13): extracted from the former monolithic CloseAndPayScreen.kt (1,629
// lines) — see that file's own doc for the split rationale. This file is S4's final step: the
// printed/emailed/texted receipt. [ReceiptScreen] is `internal` (not `private`) because the top
// [CloseAndPayScreen] composable (CloseAndPayScreen.kt) calls it across this file boundary — same
// package (`ui.screens.closepay`), so no import needed either way, only the visibility modifier
// changes from the pre-split file.

@Composable
internal fun ReceiptScreen(s: CloseAndPayUiState.ReceiptStep, vm: CloseAndPayViewModel) {
    val scope = rememberCoroutineScope2()
    // Collected once at the top, lifecycle-aware (StateFlowValueCalledInComposition lint) --
    // reading SessionHolder.session.value directly deep in this composable's content read a raw
    // StateFlow snapshot outside Compose's recomposition tracking, so a session change while the
    // receipt is on screen (unlikely but not impossible: a shift's own submit flow) would never
    // redraw the driver/vehicle line below.
    val session by SessionHolder.session.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Same shape as ClosingStatusStrip, for the same reasons (tablet, 2026-09-08): the pill
        // printed "TRIP CLOSED" over the clock, and the strip never reported itself as chrome so
        // FLEET LOCKED sat on the receipt's operator line.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .reportsChromeHeader()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                HudStatusPill(label = "Trip", value = "CLOSED", tone = HudTone.Success)
            }
            Text(
                rememberDeckClock(),
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = CaptainPalette.textSecondary,
            )
        }
        Spacer(Modifier.height(Space.lg))
        Row(modifier = Modifier.weight(1f).padding(horizontal = 96.dp, vertical = 24.dp)) {
            // Receipt paper — deliberately kept cream/monospace regardless of app theme; it mimics
            // a real thermal-printer slip, not a themed UI surface.
            Column(
                modifier = Modifier
                    .width(430.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF7F5EE))
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val r = s.receipt
                // The operator, from the one place its name lives -- never the "Lilly Cabs"
                // preset this printed until 2026-09-08. TenantBranding documents whether the
                // value is a compiled default or a fetched tenant record.
                ReceiptMono(TenantBranding.operatorName.uppercase(), bold = true, size = 20)
                // NO ABN LINE. The ABN it used to print ("12 345 678 901") was invented, and this
                // document is titled TAX INVOICE / RECEIPT: a fabricated ABN on a tax invoice is a
                // legal defect, not a cosmetic one. There is no ABN anywhere in this app's data
                // model or the backend's tenant record today, so nothing is printed rather than
                // something plausible. The authorisation number IS real (TenantBranding) and is
                // the NSW-regulated identifier a passenger can check, so it is shown instead.
                TenantBranding.authorisationNumber?.let { ReceiptMono("TSP authorisation $it") }
                if (r.simulated) {
                    // A5 · Hardware honesty: the marker is on the docket itself, in the
                    // passenger's line of sight, not only in the driver-facing chrome.
                    ReceiptMono("*** $TEST_RECEIPT_MARKER ***", bold = true, size = 17, color = Color(0xFFB3261E))
                    ReceiptMono(SIMULATED_BANNER, bold = true, color = Color(0xFFB3261E))
                    ReceiptMono("This is not a valid tax invoice.", color = Color(0xFFB3261E))
                }
                ReceiptMono("TAX INVOICE / RECEIPT", bold = true, size = 15)
                ReceiptMono("Receipt ${r.receiptRef ?: "—"}")
                ReceiptMono("${r.startedAt} → ${r.closedAt}")
                // Human identity, not row uuids (tablet, 2026-09-08: the passenger copy printed
                // "Driver f96598dc-b351-... · Vehicle ad0d0d57-..."). Driver name and rego come
                // from the live session (collected at the top of this composable); the uuids
                // remain the honest fallback only when there is no session to read -- which on a
                // just-closed fare there always is.
                val currentSession = session
                ReceiptMono(
                    if (currentSession != null) {
                        "Driver ${currentSession.driverName} · Vehicle ${currentSession.vehicleId}"
                    } else {
                        "Driver ${r.driverId} · Vehicle ${r.vehicleId}"
                    },
                )
                ReceiptMono("------------------------------------", color = Color(0xFF9A968A))
                r.fareLines.forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ReceiptMono(line.label)
                        ReceiptMono(line.amount)
                    }
                }
                ReceiptMono("====================================", color = Color(0xFF9A968A))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ReceiptMono("TOTAL", bold = true, size = 18)
                    ReceiptMono(r.total, bold = true, size = 18)
                }
                ReceiptMono("GST included ${r.gstComponent}")
                if (r.simulated) {
                    ReceiptMono("NOT PAID — $SIMULATED_BANNER", bold = true, color = Color(0xFFB3261E))
                } else {
                    ReceiptMono("PAID — ${r.paymentMethod.uppercase()}", bold = true, color = Color(0xFF1C7C3E))
                }
            }
            Spacer(Modifier.width(64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // A5 · Hardware honesty. Persistent, non-dismissible banner in the driver-facing
                // confirmation whenever the payment was taken through a simulated gateway (debug
                // builds only — the simulated gateways are never constructed in a release build).
                // The passenger-facing docket to the left carries the same warning; this one is so
                // the driver cannot hand it over without having seen it.
                if (s.receipt.simulated) {
                    Box(
                        modifier = Modifier
                            .width(480.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFB3261E))
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Column {
                            Text(
                                SIMULATED_BANNER,
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = Color.White,
                            )
                            Text(
                                "This trip was closed through a simulated payment gateway. " +
                                    "The receipt is marked $TEST_RECEIPT_MARKER and must not be " +
                                    "given to a passenger as proof of payment.",
                                fontFamily = InterFamily,
                                fontSize = 14.sp,
                                color = Color.White,
                            )
                        }
                    }
                }
                Text("Passenger copy", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
                Text(
                    if (s.printerIsReal) {
                        "Print, or offer email or SMS. Reprint any time from Trip Detail."
                    } else {
                        // A5: stated, not implied. No printer integration exists in this build,
                        // so the Print action below is absent rather than failing on press.
                        "No receipt printer on this device — offer email or SMS. " +
                            "Reprint any time from Trip Detail."
                    },
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.textSecondary,
                )
                // A5: these used to call AppContainer.apiService.emailReceipt/smsReceipt inline,
                // IN ADDITION to the ViewModel's mock gateway, and fell back to a fabricated
                // recipient ("passenger@example.com" / "0400000000") when the driver had typed
                // nothing — so a blank field still reported a send. Both now go through the one
                // real gateway (ApiEmailReceiptGateway/ApiSmsReceiptGateway), which posts to the
                // same routes, refuses the blank/unsynced cases, and reports the backend's own
                // mock=true (no provider configured) as a failure instead of a delivery.
                ReceiptActionButton(Icons.Rounded.Email, "Email receipt", busy = s.emailState == ActionState.IN_PROGRESS) {
                    scope.launch { vm.sendEmailReceipt() }
                }
                ReceiptActionButton(Icons.Rounded.Sms, "SMS receipt", busy = s.smsState == ActionState.IN_PROGRESS) {
                    scope.launch { vm.sendSmsReceipt() }
                }
                // Absent unless a real printer gateway is present — see printerIsReal's doc.
                if (s.printerIsReal) {
                    ReceiptActionButton(
                        Icons.Rounded.Print,
                        "Print receipt",
                        busy = s.printState == ActionState.IN_PROGRESS,
                        onClick = vm::printReceipt,
                    )
                }
                // Real per-channel outcomes from the gateways, never a fabricated confirmation.
                listOfNotNull(
                    s.emailError?.let { "Email: " + it }
                        ?: "Email sent".takeIf { s.emailState == ActionState.SUCCESS },
                    s.smsError?.let { "SMS: " + it }
                        ?: "SMS sent".takeIf { s.smsState == ActionState.SUCCESS },
                    s.printError?.let { "Print: " + it }
                        ?: "Printed".takeIf { s.printState == ActionState.SUCCESS },
                ).forEach { note ->
                    Text(note, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.textMuted)
                }
                CaptainButton(text = "Done — back to For Hire", heightDp = 72, widthDp = 480) {
                    vm.finishReceiptStep()
                }
            }
        }
        Text(
            // Was a hardcoded "Trip synced ✓" — a real overclaim, found live 2026-09-05: the trip
            // is genuinely saved locally and posted to today's shift totals (both true the
            // instant closeTrip() returns, above), but the server sync itself is only just
            // starting (see CloseAndPayViewModel.finalizeClose's SyncWorker.enqueueOneTime call)
            // and can take a real few seconds — RatePassengerViewModel.load() surfacing
            // "hasn't synced yet" right after this screen claimed the opposite is what exposed it.
            "Saved locally · sync started · fare posted to shift totals",
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = CaptainPalette.textMuted,
            modifier = Modifier.padding(start = 96.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun ReceiptMono(text: String, bold: Boolean = false, size: Int = 13, color: Color = Color(0xFF23252B)) {
    Text(text, fontFamily = RobotoMonoFamily, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = size.sp, color = color)
}

@Composable
private fun ReceiptActionButton(icon: ImageVector, label: String, busy: Boolean, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.width(480.dp).height(72.dp).clickable(enabled = !busy, onClick = onClick), cornerRadiusDp = 14) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(22.dp), color = CaptainPalette.textSecondary)
            } else {
                Icon(icon, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(22.dp))
                Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = CaptainPalette.textSecondary, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun rememberCoroutineScope2() = androidx.compose.runtime.rememberCoroutineScope()
