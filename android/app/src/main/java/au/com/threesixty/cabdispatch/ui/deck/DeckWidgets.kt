package au.com.threesixty.cabdispatch.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.DeckState
import au.com.threesixty.cabdispatch.ui.theme.DeckType
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * Shared Command Deck widgets — 1:1 ports of the reusable components on the v2 Figma file's
 * design-system page (fileKey `h0PSsXQ971dOJvt25tN7BA`): `c/btn-*` (3:123–3:131), `c/chip`
 * (3:133), `c/state-pill` (3:136), `c/key`/`c/keypad` (3:138/3:140). Chrome-level components
 * (status strip, nav rail, drive panel) live in [DeckChrome.kt].
 */

enum class DeckButtonKind { Primary, Success, Danger, Outline, Ghost }

/**
 * `c/btn-*` — 64dp baseline height (Figma 320×64), radius 14. [heightDp] raises it to the 72–88
 * "primary action" band (the drive panel's START METER is 88). Disabled renders at 40% alpha and
 * swallows taps, matching how the old screens signalled disabled CTAs.
 */
@Composable
fun DeckButton(
    text: String,
    kind: DeckButtonKind,
    modifier: Modifier = Modifier,
    heightDp: Int = Deck.TOUCH_MIN,
    fontSize: Int = 19,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (bg, ink) = when (kind) {
        DeckButtonKind.Primary -> Deck.yellow to Deck.onYellow
        DeckButtonKind.Success -> Deck.forHire to Deck.onForHire
        DeckButtonKind.Danger -> Deck.hired to Deck.onHired
        DeckButtonKind.Outline -> Color.Transparent to Deck.textPrimary
        DeckButtonKind.Ghost -> Color.Transparent to Deck.textSecondary
    }
    val shape = RoundedCornerShape(Deck.R_MD.dp)
    Box(
        modifier = modifier
            .height(heightDp.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (kind == DeckButtonKind.Outline) {
                    Modifier.border(1.5.dp, Deck.strokeStrong, shape)
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize.sp,
            color = ink,
        )
    }
}

/** `c/chip` — small status chip: 8dp dot + Inter Medium 13 label on a card-toned pill. */
@Composable
fun DeckChip(text: String, dotColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Deck.R_SM.dp))
            .background(Deck.card)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(text = text, style = DeckType.label, fontSize = 13.sp, letterSpacing = 0.sp)
    }
}

/** `c/state-pill` — the strip's center pill: state color bg, Inter Bold 16 caps, tracking 1. */
@Composable
fun StatePill(state: DeckState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(99.dp))
            .background(state.color)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(
            text = state.label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 1.sp,
            color = state.ink,
        )
    }
}

/** KPI tile (drive panel + dashboards): Chakra Petch value over an 11sp bold caps label. */
@Composable
fun KpiTile(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Deck.textPrimary) {
    Column(
        modifier = modifier
            .height(84.dp)
            .clip(RoundedCornerShape(Deck.R_MD.dp))
            .background(Deck.card),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
            color = valueColor,
        )
        Text(text = label, style = DeckType.tinyLabel)
    }
}

/** `c/key` — one keypad key, 140×78 (Figma-exact), Chakra Petch 28 on raised surface. */
@Composable
fun DeckKey(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .width(Deck.KEY_W.dp)
            .height(Deck.KEY_H.dp)
            .clip(RoundedCornerShape(Deck.R_MD.dp))
            .background(Deck.card)
            .border(1.dp, Deck.strokeSubtle, RoundedCornerShape(Deck.R_MD.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            // Figma: digits Chakra SemiBold 28; the wider "CLR" drops to 20 to stay inside the key.
            fontSize = if (label.length > 1) 20.sp else 28.sp,
            color = if (accent) Deck.stopped else Deck.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * `c/keypad` — the standard 3×4 numeric pad (448×354 with 14dp gaps): 1–9, backspace, 0, CLR.
 * Callers own the field state; this just reports key presses.
 */
@Composable
fun DeckKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("123", "456", "789").forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowDigits.forEach { d -> DeckKey(label = d.toString(), onClick = { onDigit(d) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DeckKey(label = "⌫", accent = true, onClick = onBackspace)
            DeckKey(label = "0", onClick = { onDigit('0') })
            DeckKey(label = "CLR", accent = true, onClick = onClear)
        }
    }
}
