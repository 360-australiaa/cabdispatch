package au.com.threesixty.cabdispatch.ui.screens.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * 03 · Permissions — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `8:39`): left
 * header column (H1 + subtitle + grant-count pill) and a 2×4 grid of 96dp permission cards.
 * Status-only, exactly like every previous version of this screen — granting/revoking happens in
 * Android Settings.
 *
 * Honesty rule carried over from the long-standing doc on this file: no card is allowed to claim
 * a check this app doesn't really make. The Figma frame lists 8 cards; here that is 5 real
 * runtime-permission checks + 1 real [PowerManager.isIgnoringBatteryOptimizations] query (a real
 * check, newly added — the v1 screen's objection was that no call site existed; now one does),
 * and the frame's remaining two (Bluetooth, File storage) render as explicit "Not required on
 * this build" informational cards: the manifest declares no Bluetooth permission and API 29+
 * scoped storage needs none, and saying so is more honest than either omitting the cards (grid
 * hole) or faking a grant state.
 */
@Composable
fun PermissionsChecklistScreen(navController: NavHostController) {
    val context = LocalContext.current
    val cards = remember { buildPermissionCards(context) }
    val grantedCount = cards.count { it.granted }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Deck.canvas)
            .padding(start = 72.dp, end = 40.dp, top = 96.dp, bottom = 44.dp),
    ) {
        // Left header column
        Column(modifier = Modifier.width(376.dp)) {
            Text("Permissions", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = Deck.textPrimary)
            Spacer(Modifier.height(16.dp))
            Text(
                "The meter needs these to run legally and safely.",
                fontFamily = InterFamily,
                fontSize = 17.sp,
                color = Deck.textSecondary,
                modifier = Modifier.width(340.dp),
            )
            Spacer(Modifier.height(16.dp))
            val allOk = grantedCount == cards.size
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background((if (allOk) Deck.forHire else Deck.stopped).copy(alpha = 0.14f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (allOk) "✓ ${cards.size} of ${cards.size} granted" else "$grantedCount of ${cards.size} granted",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (allOk) Deck.forHire else Deck.stopped,
                )
            }
            Spacer(Modifier.weight(1f))
            DeckButton(text = "Back", kind = DeckButtonKind.Ghost, modifier = Modifier.width(180.dp)) {
                navController.popBackStack()
            }
        }
        Spacer(Modifier.width(40.dp))
        // Right — 2-wide grid of permission cards + Continue pinned bottom-right.
        Column(modifier = Modifier.weight(1f)) {
            cards.chunked(2).forEach { rowCards ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowCards.forEach { card -> PermCard(card, modifier = Modifier.weight(1f)) }
                    if (rowCards.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.weight(1f))
            Row {
                Spacer(Modifier.weight(1f))
                DeckButton(text = "Continue", kind = DeckButtonKind.Primary, heightDp = 72, modifier = Modifier.width(360.dp)) {
                    navController.popBackStack()
                }
            }
        }
    }
}

private data class PermCard(
    val emoji: String,
    val title: String,
    val granted: Boolean,
    val statusText: String,
)

private fun runtimeGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun buildPermissionCards(context: Context): List<PermCard> {
    fun of(emoji: String, title: String, permission: String): PermCard {
        val granted = runtimeGranted(context, permission)
        return PermCard(emoji, title, granted, if (granted) "Granted" else "Not granted — open Android Settings")
    }

    val battery = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

    return listOf(
        of("📍", "Location (while in use)", Manifest.permission.ACCESS_FINE_LOCATION),
        of("🛰", "Background location", Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        // Broadened from "QR pairing" only — this permission now also gates the cabin-facing
        // still-frame capture that runs while a duress event is open (2026-08-27 snapshot-gallery
        // pass), so the row can't stay QR-scanning-specific without misleading the driver about
        // why the app wants it.
        of("📷", "Camera (QR pairing, duress cabin capture)", Manifest.permission.CAMERA),
        of("🎙", "Microphone (duress audio)", Manifest.permission.RECORD_AUDIO),
        // POST_NOTIFICATIONS only exists as a runtime permission on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            of("🔔", "Notifications", Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PermCard("🔔", "Notifications", granted = true, statusText = "Granted at install on this Android version")
        },
        PermCard(
            emoji = "🔋",
            title = "Disable battery optimisation",
            granted = battery,
            statusText = if (battery) "Exempt — meter won't be dozed mid-fare" else "Not exempt — grant in Android Settings",
        ),
        // The manifest declares no Bluetooth runtime permission (printer gateway is mocked) and
        // API 29+ scoped storage needs no storage permission — stated plainly, not faked.
        PermCard("🅱", "Bluetooth — nearby devices", granted = true, statusText = "Not required on this build"),
        PermCard("📁", "File storage", granted = true, statusText = "Not required — scoped storage"),
    )
}

@Composable
private fun PermCard(card: PermCard, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Deck.panel)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(card.emoji, fontSize = 26.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(card.title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Deck.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                card.statusText,
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = if (card.granted) Deck.textMuted else Deck.stopped,
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background((if (card.granted) Deck.forHire else Deck.stopped).copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (card.granted) "✓" else "!",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = if (card.granted) Deck.forHire else Deck.stopped,
            )
        }
    }
}
