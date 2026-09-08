package au.com.threesixty.cabdispatch.ui.screens.adminpin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainKeypad
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

// BUGFIX (2026-09-04, Settings & Diagnostics audit): this was a single fixed `PIN_LENGTH = 6` —
// the VERIFY button only ever enabled at exactly 6 digits, and the keypad refused a 7th digit —
// but the server-side admin PIN this screen exists to check
// (`POST /v1/fleet/devices/{id}/verify-admin-pin`, backing
// au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.attemptFactoryReset) accepts
// any 4-8 digit PIN (`backend/app/schemas/tenant.py::AdminPinSetRequest`, `_PIN_PATTERN =
// r"^\d{4,8}$"`, set via the owner-only `POST /v1/tenants/{id}/admin-pin`). Any tenant who set a
// PIN of a length other than 6 could never pass this gate at all: a shorter PIN left VERIFY
// permanently disabled, a longer one couldn't even be fully typed. Now matches the server's real
// bounds instead of an arbitrary UI guess.
private const val PIN_MIN_LENGTH = 4
private const val PIN_MAX_LENGTH = 8

/**
 * 32 · Admin PIN Gate — Captain Taxis purple redesign (2026-08-29 pass), migrated off the old
 * yellow/black `Deck` palette onto [CaptainPalette] to match [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsScreen],
 * which embeds this screen inline as its factory-reset sub-screen state. Same presentation-shell
 * contract as before: [onVerify] fires with the entered PIN once [PIN_LENGTH] digits are present
 * and VERIFY is tapped; the server-side verification
 * (`POST /v1/fleet/devices/{id}/verify-admin-pin`) and all of its error handling stay entirely
 * with [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.attemptFactoryReset]
 * — this screen only renders [errorMessage]/[verifying] as reported, and a fresh error clears the
 * entered digits so the driver can retry immediately.
 *
 * Layout: left column (lock icon · 32sp title · true "server-verified" copy · dot progress row ·
 * the caller's [subtitle] as the danger-tinted requesting line · error/spinner), the shared
 * [CaptainKeypad] (0-9 numeric pad, elderly-friendly 84dp keys) on the right with a 448×72 primary
 * VERIFY beneath, outline Cancel bottom-left. No "N failed attempts locks admin actions" copy is
 * rendered — no such lockout exists anywhere in this codebase, and this screen doesn't claim
 * behaviour it doesn't have.
 */
@Composable
fun AdminPinGateScreen(
    subtitle: String,
    errorMessage: String?,
    verifying: Boolean,
    onCancel: () -> Unit,
    onVerify: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    // Clears the entered digits once a fresh error arrives (e.g. "Incorrect admin PIN") so the
    // driver isn't stuck staring at a full dot-row after a failed attempt.
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) pin = ""
    }

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 96.dp, end = 96.dp, top = 120.dp),
        ) {
            // --- Left column ---
            Column(
                modifier = Modifier.width(440.dp).padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(CaptainPalette.raised)
                        .border(1.dp, CaptainPalette.panelBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(32.dp))
                }
                Text(
                    "Enter admin PIN to continue",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = CaptainPalette.textPrimary,
                )
                Text(
                    "Server-verified — the PIN is checked by the fleet backend and never stored on this device.",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = CaptainPalette.textSecondary,
                    modifier = Modifier.width(420.dp),
                )
                // PIN_MAX_LENGTH dots shown regardless of the tenant's actual configured PIN
                // length (unknown to this device — only the hash is server-side, never the
                // length): filled progressively as digits are typed, same shape as before this
                // fix's fixed-6 version, just no longer implying a PIN must be exactly 6 long.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(PIN_MAX_LENGTH) { index ->
                        val filled = index < pin.length
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (filled) CaptainPalette.accent else CaptainPalette.raised)
                                .border(1.dp, CaptainPalette.panelBorder, CircleShape),
                        )
                    }
                }
                Text(
                    subtitle,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = CaptainPalette.danger,
                    modifier = Modifier.width(420.dp),
                )
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = CaptainPalette.danger,
                        modifier = Modifier.width(420.dp),
                    )
                }
                if (verifying) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CaptainPalette.accent, strokeWidth = 2.dp)
                }
            }

            Spacer(Modifier.weight(1f))

            // --- Right column: shared keypad + VERIFY ---
            Column {
                CaptainKeypad(
                    onDigit = { d -> if (!verifying && pin.length < PIN_MAX_LENGTH) pin += d },
                    onBackspace = { if (!verifying) pin = pin.dropLast(1) },
                    onClear = { if (!verifying) pin = "" },
                )
                Spacer(Modifier.height(24.dp))
                CaptainButton(
                    text = "VERIFY",
                    heightDp = 72,
                    fontSize = 20.sp,
                    enabled = pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && !verifying,
                    widthDp = 448,
                ) { onVerify(pin) }
            }
        }

        CaptainButton(
            text = "Cancel",
            outline = true,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 96.dp, bottom = 48.dp),
            widthDp = 180,
            onClick = onCancel,
        )
    }
}
