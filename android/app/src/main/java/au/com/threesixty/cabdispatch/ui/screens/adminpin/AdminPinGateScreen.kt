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
import androidx.compose.material3.CircularProgressIndicator
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
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.deck.DeckKeypad
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

private const val PIN_LENGTH = 6

/**
 * 32 · Admin PIN Gate — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `28:198`).
 * Same presentation-shell contract as before: [onVerify] fires with the entered PIN once
 * [PIN_LENGTH] digits are present and VERIFY is tapped; the server-side verification
 * (`POST /v1/fleet/devices/{id}/verify-admin-pin`) and all of its error handling stay entirely
 * with [au.com.threesixty.cabdispatch.ui.screens.settings.SettingsViewModel.attemptFactoryReset]
 * — this screen only renders [errorMessage]/[verifying] as reported, and a fresh error clears the
 * entered digits so the driver can retry immediately.
 *
 * v2 layout per the frame: left column (🔐 · 32sp title · true "server-verified" copy · dot
 * progress row · the caller's [subtitle] as the red requesting line · error/spinner), the shared
 * [DeckKeypad] (140×78 keys) on the right with a 448×72 yellow VERIFY beneath, ghost Cancel
 * bottom-left. The frame's "Three failed attempts locks admin actions for 15 minutes" sentence is
 * NOT rendered — no such lockout exists anywhere in this codebase, and this screen doesn't claim
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

    Box(modifier = Modifier.fillMaxSize().background(Deck.canvas)) {
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
                Text("🔐", fontSize = 44.sp)
                Text(
                    "Enter admin PIN to continue",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = Deck.textPrimary,
                )
                Text(
                    "Server-verified — the PIN is checked by the fleet backend and never stored on this device.",
                    fontFamily = InterFamily,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = Deck.textSecondary,
                    modifier = Modifier.width(420.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(PIN_LENGTH) { index ->
                        val filled = index < pin.length
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (filled) Deck.yellow else Deck.card)
                                .border(1.dp, Deck.strokeStrong, CircleShape),
                        )
                    }
                }
                Text(
                    subtitle,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Deck.hired,
                    modifier = Modifier.width(420.dp),
                )
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Deck.hired,
                        modifier = Modifier.width(420.dp),
                    )
                }
                if (verifying) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Deck.yellow, strokeWidth = 2.dp)
                }
            }

            Spacer(Modifier.weight(1f))

            // --- Right column: shared keypad + VERIFY ---
            Column {
                DeckKeypad(
                    onDigit = { d -> if (!verifying && pin.length < PIN_LENGTH) pin += d },
                    onBackspace = { if (!verifying) pin = pin.dropLast(1) },
                    onClear = { if (!verifying) pin = "" },
                )
                Spacer(Modifier.height(24.dp))
                DeckButton(
                    text = "VERIFY",
                    kind = DeckButtonKind.Primary,
                    heightDp = 72,
                    fontSize = 20,
                    enabled = pin.length == PIN_LENGTH && !verifying,
                    modifier = Modifier.width(448.dp),
                ) { onVerify(pin) }
            }
        }

        DeckButton(
            text = "Cancel",
            kind = DeckButtonKind.Ghost,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 96.dp, bottom = 48.dp)
                .width(180.dp),
            onClick = onCancel,
        )
    }
}
