package au.com.threesixty.cabdispatch.ui.screens.settings

import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.PAIR_CODE_ALPHABET
import au.com.threesixty.cabdispatch.ui.theme.PAIR_CODE_LENGTH
import au.com.threesixty.cabdispatch.ui.theme.PaneShell

// W7 file split (2026-09-13): extracted from the former monolithic SettingsScreen.kt (1,437
// lines) — see that file's own doc for the split rationale. This file is the Pair Meter
// sub-screen (`SettingsSubScreen.PAIR_METER`). [PairMeterContent] is `internal` (not `private`)
// because the top [SettingsScreen] composable (SettingsScreen.kt) calls it across this file
// boundary — same package (`ui.screens.settings`), so no import needed either way, only the
// visibility modifier changes from the pre-split file.

/**
 * Real "Pair Meter" screen — registers this tablet as a vehicle's meter via a short-lived
 * admin-generated code (`POST /v1/fleet/devices/register`). Two entry paths per spec: manual
 * 8-char code entry (primary — works with zero dashboard dependency) and QR scan (secondary,
 * reuses [au.com.threesixty.cabdispatch.domain.RealQrScanner] via a **separate** result handler
 * from the vehicle-bind rego scanner — same underlying ML Kit call, different semantic target,
 * per the spec's explicit "do not repoint the existing scanner" instruction).
 */
@Composable
internal fun PairMeterContent(state: SettingsUiState, viewModel: SettingsViewModel, onBack: () -> Unit) {
    // This pane is only ever reached from within MainActivity's single-activity nav host, so
    // LocalActivity.current is always present here in practice -- guarded rather than asserted so
    // a future reuse of this composable outside that host degrades to "no scanner" instead of a crash.
    val activity = LocalActivity.current
    if (activity == null) {
        Log.e("PairMeterContent", "LocalActivity.current is null; cannot host the QR scanner")
        return
    }
    var code by remember { mutableStateOf("") }
    val pairState = state.pairMeter

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.hudBg)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        PaneShell(title = "Pair meter", onBack = onBack) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "Enter the 8-character code shown on the dashboard, or scan its QR.",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    color = CaptainPalette.textSecondary,
                )
                Spacer(Modifier.height(24.dp))

                when (pairState) {
                    is PairMeterState.Success -> {
                        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16, glow = CaptainPalette.success) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CaptainPalette.success, modifier = Modifier.size(24.dp))
                                Text(
                                    "Paired" + (pairState.vehicleId?.let { " — vehicle $it" } ?: ""),
                                    fontFamily = InterFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 17.sp,
                                    color = CaptainPalette.success,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                    else -> {
                        // The tablet's own keyboard, not a hand-rolled pad.
                        //
                        // Every other text entry in this app avoids the platform IME because an
                        // earlier on-device check found it never came up (see HiredScreen.kt's
                        // DestinationSearchDialog). Re-measured on this tablet, 2026-09-08, while
                        // screen-pinned: `dumpsys input_method` reports mShowRequested=true /
                        // mInputShown=true and typed characters land. So the pairing code uses the
                        // real keyboard here and on the readiness gate, which is the same code
                        // entered in two places and should not have two different idioms.
                        //
                        // Filtered as typed to the server's own alphabet (PAIR_CODE_ALPHABET),
                        // which is what the removed keypad achieved by omitting keys.
                        TextField(
                            value = code,
                            onValueChange = { raw ->
                                code = raw.uppercase().filter { it in PAIR_CODE_ALPHABET }.take(PAIR_CODE_LENGTH)
                            },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = ChakraPetch,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp,
                                letterSpacing = 6.sp,
                                textAlign = TextAlign.Center,
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { if (code.length == PAIR_CODE_LENGTH) viewModel.submitPairingCode(code) },
                            ),
                            placeholder = {
                                Text(
                                    "········",
                                    fontFamily = ChakraPetch,
                                    fontSize = 28.sp,
                                    letterSpacing = 6.sp,
                                    textAlign = TextAlign.Center,
                                    color = CaptainPalette.textMuted,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = CaptainPalette.raised,
                                unfocusedContainerColor = CaptainPalette.raised,
                                focusedTextColor = CaptainPalette.textPrimary,
                                unfocusedTextColor = CaptainPalette.textPrimary,
                                cursorColor = CaptainPalette.accent,
                                focusedIndicatorColor = CaptainPalette.accent,
                                unfocusedIndicatorColor = CaptainPalette.panelBorder,
                            ),
                        )
                        Spacer(Modifier.height(16.dp))
                        if (pairState is PairMeterState.Error) {
                            Text(pairState.message, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.warning)
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CaptainButton(
                                text = "SCAN QR",
                                outline = true,
                                modifier = Modifier.weight(1f),
                                enabled = pairState !is PairMeterState.Submitting,
                            ) { viewModel.scanPairingQr(activity) }
                            CaptainButton(
                                text = if (pairState is PairMeterState.Submitting) "PAIRING…" else "PAIR",
                                modifier = Modifier.weight(1f),
                                enabled = code.length == PAIR_CODE_LENGTH && pairState !is PairMeterState.Submitting,
                            ) { viewModel.submitPairingCode(code) }
                        }
                    }
                }
            }
        }
    }
}
